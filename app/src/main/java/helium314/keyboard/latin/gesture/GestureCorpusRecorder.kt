// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.KeptRecords
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import helium314.keyboard.latin.NgramContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Local corpus of real swipes from everyday typing (M4 tuning data for the in-tree decoder).
 *
 * One JSONL line per completed swipe: raw touch path in keyboard pixels, the letter-key geometry
 * of the keyboard it was drawn on, and the decoder's top candidates (top-1 is what got committed,
 * serving as pseudo-label). If the user then picks another suggestion, deletes the swiped word, or edits it into
 * another word (also after committing it, when it is opened for editing again within [RESUME_WINDOW_MS]), a
 * follow-up line `{"type":"final","ref":id,"how":"pick"|"deleted"|"edited",...}` records the correction. Since 2026-10-23 a
 * pick's or an edit's line also says what happened ([correction]: kept, typed, via, auto, known), and ids are the swipe's
 * time in ms (unique across keyboard starts; before, a count that began again at every start).
 *
 * Schema shares `points` / `locale` / `committed` with gesturelab's and the trainer's swipes.jsonl.
 * Everything stays in the app's external files dir (`gesture_corpus.jsonl`); nothing is uploaded. On by default, kept to
 * the newest "Swipes kept" ([Settings.PREF_GESTURE_CORPUS_KEPT]) swipes with their follow-up lines (2026-10-18).
 * Gesture typing is off in password fields, so those never reach here.
 */
object GestureCorpusRecorder {
    private const val TAG = "GestureCorpusRecorder"
    private const val FILE_NAME = "gesture_corpus.jsonl"
    private const val MAX_CANDIDATES = 5
    // "Swipes kept": the slider's range, its far end keeping all
    const val KEPT_MIN = 1_000
    const val KEPT_MAX = 20_000
    const val KEPT_STEP = 1_000
    const val KEPT_ALL = KEPT_MAX + KEPT_STEP
    const val BYTES_PER_SWIPE = 1_500 // about, for the size the slider shows

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gesture-corpus").apply { isDaemon = true } }
    // the last id given: ids are the swipe's time in ms, one more than the last when two would be the same (2026-10-23:
    // a count that began again at every keyboard start left the corpus' finals to be matched to swipes by guesswork)
    private var lastIssuedId = 0L
    @Volatile private var file: File? = null
    @Volatile private var prefs: SharedPreferences? = null
    // a swipe line starts a record, its "final" lines follow it (they are written before the next swipe's)
    private val kept = KeptRecords { it.contains("\"type\":\"swipe\"") }
    /** id of the most recent swipe whose outcome is still open (its word is being composed or edited), or -1 */
    @Volatile private var pendingId = -1L
    // the most recent swipe, its word as the keyboard put it in, and when: a committed word opened for editing again
    // is that swipe's word being corrected, if it is the same text and not long after
    @Volatile private var lastId = -1L
    @Volatile private var lastWord = ""
    @Volatile private var lastTime = 0L
    private const val RESUME_WINDOW_MS = 60_000L
    // what the swipe results log wants to know about the last swipe
    @Volatile private var lastDecodeMs = 0L
    @Volatile private var lastSpeed = 0f
    @Volatile private var lastTuning = ""
    // the pending swipe's word as it is edited, counted as it happens (2026-10-23): how many of its leading letters were
    // never deleted (deleting all and typing the same first letter again leaves 0), and how many letters were typed in
    @Volatile private var editKept = 0
    @Volatile private var editTyped = 0
    /** Whether the keyboard knows a word (dictionaries, learned words): set by Suggest with each swipe. */
    @Volatile var isKnown: (String) -> Boolean = { false }

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
        prefs = context.prefs()
    }

    /** The fewest swipes another reader of the corpus needs kept (it sets this): the corpus never keeps fewer. */
    @Volatile internal var minimumKept: () -> Int = { 0 }

    /** How many swipes the corpus keeps, null for all: "Swipes kept", or more if [minimumKept] asks. */
    internal fun keptLimit(): Int? {
        val setting = prefs?.getInt(Settings.PREF_GESTURE_CORPUS_KEPT, Defaults.PREF_GESTURE_CORPUS_KEPT) ?: Defaults.PREF_GESTURE_CORPUS_KEPT
        if (setting >= KEPT_ALL) return null
        return maxOf(setting, minimumKept())
    }

    /** "Swipes kept" was changed: a lower number deletes the oldest swipes now (on the corpus' own thread). */
    fun onKeptChanged() {
        executor.execute {
            try { file?.let { kept.trimIfOver(it, keptLimit()) } }
            catch (e: Exception) { Log.w(TAG, "failed to trim the corpus", e) }
        }
    }

    internal var corpusOn: () -> Boolean = { Settings.getValues()?.mRecordGestureCorpus == true } // (replaceable by tests)
    // whether the last swipe was made while typing privately (incognito, a private tab, an app asking for no learning, a
    // password): decided when the swipe is made, so its outcome isn't logged later in a normal field either (re-review
    // 2026-10-06: the check at writing time let that through)
    @Volatile private var lastPrivate = false
    fun isEnabled(): Boolean = corpusOn() && !lastPrivate && file != null

    /** The swiped word is followed to its outcome: always (a word typed after a deleted swipe is learned as corrected by
     *  hand), and for the corpus and the swipe results log. */
    fun isFollowing(): Boolean = true

    // when the last swipe was deleted right away, 0 once a word stuck since
    @Volatile private var deletedAt = 0L
    private const val RETYPE_WINDOW_MS = 30_000L

    /** A word is being typed where a swipe was just deleted: the swipe didn't offer it (learned as corrected by hand). */
    fun isRetypeAfterDeletedSwipe(): Boolean = deletedAt > 0L && System.currentTimeMillis() - deletedAt <= RETYPE_WINDOW_MS

    /** A typed word was committed: whatever swipe was deleted before it is settled. */
    fun onTypedWordCommitted() { deletedAt = 0L }

    /** Another field: a swipe deleted in the last one says nothing about what's typed here (review 2026-10-06 Low). */
    fun onFieldChanged() {
        // a swipe still open was kept as it came: the next field's first word is not its outcome, and may be private
        // (review 2026-10-08 M7, 2026-10-10)
        if (pendingId >= 0) finish(SwipeMetrics.OUTCOME_KEPT, -1, null)
        deletedAt = 0L
    }

    fun corpusFile(): File? = file

    /** The word before the swipe as the corpus records it: "<s>" at the start of a sentence, "" when there is none. */
    fun previousWord(ngramContext: NgramContext): String =
        if (ngramContext.isNthPrevWordBeginningOfSentence(1)) "<s>" else ngramContext.getNthPrevWord(1)?.toString() ?: ""

    /** Called with the final (tail) batch-input decode; [candidates] are the decoder's ranked results. [previousWord]
     *  goes into the corpus only (2026-10-18: to measure how far the word before decides ties like its / it's); the
     *  corpus is written only while recording is on and the field isn't private, and stays on the phone. */
    fun onSwipe(composedData: ComposedData, keyboard: Keyboard, candidates: Collection<SuggestedWordInfo>, localeTag: String,
                previousWord: String = "") {
        if (!isFollowing()) return
        val pointers = composedData.mInputPointers
        val size = pointers.pointerSize
        if (size < 2) return
        // a swipe still open when the next one comes was kept as it came
        if (pendingId >= 0) finish(SwipeMetrics.OUTCOME_KEPT, -1, null)
        lastPrivate = SwipeMetrics.privateNow()
        // the native decoder reports the same word once per dictionary it was found in; keep the best-ranked
        val cands = candidates.distinctBy { it.mWord }.take(MAX_CANDIDATES).map { it.mWord to it.mScore }
        if (cands.isEmpty()) { // nothing to follow: a dead swipe or a decode failure, logged so "never offered" stays honest
            val outcome = if (OwnGestureDecoder.lastVocabularyMissing) SwipeMetrics.OUTCOME_NO_VOCABULARY else SwipeMetrics.OUTCOME_NONE
            if (!lastPrivate) SwipeMetrics.onOutcome(nextId(System.currentTimeMillis()), "", outcome, -1, null,
                OwnGestureDecoder.lastDecodeMs, OwnGestureDecoder.lastSpeedKeysPerSecond, OwnGestureDecoder.currentTuning.key)
            return
        }
        val time = System.currentTimeMillis()
        val id = nextId(time)
        pendingId = id
        lastId = id
        lastWord = cands.firstOrNull()?.first ?: ""
        lastTime = time
        editKept = lastWord.length
        editTyped = 0
        lastDecodeMs = OwnGestureDecoder.lastDecodeMs
        lastSpeed = OwnGestureDecoder.lastSpeedKeysPerSecond // the decoder's own figure, from the preprocessed path
        lastTuning = OwnGestureDecoder.currentTuning.key
        if (!isEnabled()) return
        // the trace and the keys only when the corpus is recorded (review 2026-10-06: they were copied for every swipe)
        val xs = pointers.xCoordinates.copyOf(size)
        val ys = pointers.yCoordinates.copyOf(size)
        val ts = pointers.times.copyOf(size)
        val keys = letterKeys(keyboard)
        val kbW = keyboard.mOccupiedWidth
        val kbH = keyboard.mOccupiedHeight
        val layoutName = keyboard.mId?.mSubtype?.mainLayoutName ?: "" // (a test's stand-in keyboard has none)
        executor.execute {
            try {
                val obj = JSONObject()
                obj.put("type", "swipe")
                obj.put("id", id)
                obj.put("time", time)
                obj.put("source", "keyboard")
                obj.put("decoder", "own") // (older corpus files may say "native": Google's library)
                obj.put("locale", localeTag)
                obj.put("layout", layoutName)
                obj.put("committed", cands.firstOrNull()?.first ?: "")
                obj.put("prev", previousWord)
                val pts = JSONArray()
                for (i in 0 until size) pts.put(JSONArray().put(xs[i]).put(ys[i]).put(ts[i]))
                obj.put("points", pts)
                val candArr = JSONArray()
                for ((w, s) in cands) candArr.put(JSONObject().put("word", w).put("score", s))
                obj.put("candidates", candArr)
                val kb = JSONObject().put("w", kbW).put("h", kbH)
                val keyArr = JSONArray()
                for (k in keys) keyArr.put(JSONArray().put(k.c.toString()).put(k.cx).put(k.cy).put(k.w).put(k.h))
                kb.put("keys", keyArr)
                obj.put("keyboard", kb)
                append(obj)
            } catch (e: Exception) {
                Log.w(TAG, "failed to record swipe", e)
            }
        }
    }

    /** The word Suggest put first on the strip for the pending swipe, which is what gets inserted: the decoder's first
     *  word was noted at the swipe, but Suggest drops removed words and reorders (re-review 2026-10-07: a kept word was
     *  logged as edited into itself). */
    fun onShown(word: String?) {
        if (pendingId < 0 || word.isNullOrEmpty()) return
        lastWord = word
        editKept = word.length // (shown before any edit)
    }

    /** A swiped word's id: its time [now] in ms, one more than the last id if that is not later (unique across keyboard
     *  starts unless the clock goes back; internal for tests). */
    @Synchronized internal fun nextId(now: Long): Long = maxOf(now, lastIssuedId + 1).also { lastIssuedId = it }

    /**
     * The user replaced the pending swiped word with [word] via the suggestion strip; [rank] 0-based (0 = the word itself).
     * [fromSwipeStrip]: the strip still showed the swipe's own words (the word was untouched since the swipe), else it
     * showed words for what was typed or deleted since.
     */
    fun onSuggestionPicked(word: String, rank: Int, fromSwipeStrip: Boolean) =
        finish(SwipeMetrics.OUTCOME_PICKED, rank, word, if (fromSwipeStrip) VIA_STRIP else VIA_STRIP_TYPED, false)

    /**
     * The keyboard edited the pending swiped word: [now] is the word as it reads after the edit (null: not known there),
     * [letterTyped] whether a letter was typed into it (else letters were deleted). Counted as it happens, so a word
     * deleted to nothing and typed again keeps none of its letters, whatever it ends as.
     */
    fun onEdited(now: String?, letterTyped: Boolean) {
        if (pendingId < 0) return
        if (letterTyped) editTyped++
        if (now != null) editKept = minOf(editKept, keptPrefix(lastWord, now))
    }

    /** How many leading letters [now] still has of [swiped] (case aside: shift is no edit). */
    private fun keptPrefix(swiped: String, now: String): Int {
        var i = 0
        while (i < swiped.length && i < now.length && swiped[i].equals(now[i], ignoreCase = true)) i++
        return i
    }

    /** The user deleted the pending swiped word (backspace on a batch word). */
    fun onWordDeleted() = finish(SwipeMetrics.OUTCOME_DELETED, -1, null)

    /** Any other commit / new word: the pending swipe is settled as-is. */
    fun onWordSettled() = finish(SwipeMetrics.OUTCOME_KEPT, -1, null)

    /**
     * The composing word was committed as [word] some way other than a pick from the strip (which reports itself):
     * the pending swiped word is settled if that is what it still says, else it was edited into [word]. [typed] is the
     * word as typed before the commit (an auto-correction commits another word).
     */
    fun onWordCommitted(word: String, typed: String = word) {
        if (pendingId < 0) return
        // auto-capitalisation ("the" committed as "The") is not an edit
        if (word.equals(lastWord, ignoreCase = true)) finish(SwipeMetrics.OUTCOME_KEPT, -1, null)
        else finish(SwipeMetrics.OUTCOME_EDITED, -1, word, VIA_COMMIT, word != typed)
    }

    /** The pending swipe's outcome: to the swipe results log, and (corrections only) to the corpus; [via] and [auto] as
     *  [correction] writes them. */
    private fun finish(outcome: String, rank: Int, word: String?, via: String? = null, auto: Boolean = false) {
        val id = pendingId
        if (id < 0) return
        pendingId = -1L
        deletedAt = if (outcome == SwipeMetrics.OUTCOME_DELETED) System.currentTimeMillis() else 0L
        // (made while typing privately, or settled while typing privately: nothing of it is written; 2026-10-10)
        if (lastPrivate || SwipeMetrics.privateNow()) return
        SwipeMetrics.onOutcome(id, lastWord, outcome, rank, word, lastDecodeMs, lastSpeed, lastTuning)
        when (outcome) {
            // (a pick at rank 0 is the swiped word itself, unless letters were typed: then the strip's first word is
            // another word, and it was a correction too; 2026-10-23)
            SwipeMetrics.OUTCOME_PICKED -> if (rank != 0 || word != lastWord) correction(id, "pick", word, via, auto)
            SwipeMetrics.OUTCOME_DELETED -> correction(id, "deleted", null)
            SwipeMetrics.OUTCOME_EDITED -> correction(id, "edited", word, via, auto)
        }
    }

    // where a picked or edited word came from (the "via" of its final line)
    const val VIA_STRIP = "strip" // picked from the strip while it showed the swipe's own words
    const val VIA_STRIP_TYPED = "strip_typed" // picked from the strip after letters were typed or deleted
    const val VIA_COMMIT = "commit" // committed by space, punctuation or anything else

    /** A committed word was opened for editing again, reading [word]: the last swipe's outcome is open again if that is its word. */
    fun onWordResumed(word: String) {
        // an open swipe's word set up again (the cursor moved, or a letter changed in its middle): what it reads now
        if (pendingId >= 0) { onEdited(word, false); return }
        if (lastId >= 0 && word.equals(lastWord, ignoreCase = true) && System.currentTimeMillis() - lastTime <= RESUME_WINDOW_MS) {
            pendingId = lastId
            editKept = lastWord.length
            editTyped = 0
        }
    }

    /**
     * A "final" line. A pick's or an edit's (since 2026-10-23) also has what happened: `kept` leading letters of the
     * swiped word never deleted, `typed` letters typed in, `via` ([VIA_STRIP], [VIA_STRIP_TYPED], [VIA_COMMIT]), `auto`
     * whether auto-correction changed what was typed at the commit, `known` whether the keyboard knows the final word.
     */
    private fun correction(id: Long, how: String, word: String?, via: String? = null, auto: Boolean = false) {
        if (!isEnabled()) return
        val time = System.currentTimeMillis()
        val kept = editKept
        val typed = editTyped
        val known = if (via != null && word != null) runCatching { isKnown(word) }.getOrDefault(false) else false
        executor.execute {
            try {
                val obj = JSONObject().put("type", "final").put("ref", id).put("time", time).put("how", how)
                if (word != null) obj.put("final", word)
                if (via != null) obj.put("kept", kept).put("typed", typed).put("via", via).put("auto", auto).put("known", known)
                append(obj)
            } catch (e: Exception) {
                Log.w(TAG, "failed to record correction", e)
            }
        }
    }

    // (on the corpus' own thread; internal for tests)
    internal fun append(obj: JSONObject) {
        file?.let { kept.append(it, obj.toString() + "\n", keptLimit()) }
    }

    private class KeyGeom(val c: Char, val cx: Float, val cy: Float, val w: Float, val h: Float)

    private fun letterKeys(keyboard: Keyboard): List<KeyGeom> {
        val seen = HashSet<Char>()
        val out = ArrayList<KeyGeom>()
        for (key in keyboard.sortedKeys) {
            val code = key.code
            if (code <= 0 || (code != '.'.code && !Character.isLetter(code))) continue
            val c = Character.toLowerCase(code).toChar()
            if (!seen.add(c)) continue
            out.add(KeyGeom(c, key.x + key.width / 2f, key.y + key.height / 2f, key.width.toFloat(), key.height.toFloat()))
        }
        return out
    }
}

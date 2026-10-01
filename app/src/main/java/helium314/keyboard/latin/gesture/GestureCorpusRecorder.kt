// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in local corpus of real swipes from everyday typing (M4 tuning data for the in-tree decoder).
 *
 * One JSONL line per completed swipe: raw touch path in keyboard pixels, the letter-key geometry
 * of the keyboard it was drawn on, and the decoder's top candidates (top-1 is what got committed,
 * serving as pseudo-label). If the user then picks another suggestion, deletes the swiped word, or edits it into
 * another word (also after committing it, when it is opened for editing again within [RESUME_WINDOW_MS]), a
 * follow-up line `{"type":"final","ref":id,"how":"pick"|"deleted"|"edited",...}` records the correction.
 *
 * Schema shares `points` / `locale` / `committed` with gesturelab's and the trainer's swipes.jsonl.
 * Everything stays in the app's external files dir (`gesture_corpus.jsonl`); nothing is uploaded.
 * Gesture typing is off in password fields, so those never reach here.
 */
object GestureCorpusRecorder {
    private const val TAG = "GestureCorpusRecorder"
    private const val FILE_NAME = "gesture_corpus.jsonl"
    private const val MAX_CANDIDATES = 5

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gesture-corpus").apply { isDaemon = true } }
    private val counter = AtomicLong(0)
    @Volatile private var file: File? = null
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

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
    }

    fun isEnabled(): Boolean = Settings.getValues().mRecordGestureCorpus && file != null

    /** The swiped word is followed to its outcome for the corpus and/or the swipe results log. */
    fun isFollowing(): Boolean = isEnabled() || SwipeMetrics.isEnabled()

    fun corpusFile(): File? = file

    /** Called with the final (tail) batch-input decode; [candidates] are the decoder's ranked results. */
    fun onSwipe(composedData: ComposedData, keyboard: Keyboard, candidates: Collection<SuggestedWordInfo>, localeTag: String) {
        if (!isFollowing()) return
        val pointers = composedData.mInputPointers
        val size = pointers.pointerSize
        if (size < 2) return
        // a swipe still open when the next one comes was kept as it came
        if (pendingId >= 0) finish(SwipeMetrics.OUTCOME_KEPT, -1, null)
        val xs = pointers.xCoordinates.copyOf(size)
        val ys = pointers.yCoordinates.copyOf(size)
        val ts = pointers.times.copyOf(size)
        // the native decoder reports the same word once per dictionary it was found in; keep the best-ranked
        val cands = candidates.distinctBy { it.mWord }.take(MAX_CANDIDATES).map { it.mWord to it.mScore }
        val keys = letterKeys(keyboard)
        val kbW = keyboard.mOccupiedWidth
        val kbH = keyboard.mOccupiedHeight
        val layoutName = keyboard.mId.mSubtype.mainLayoutName
        val id = counter.incrementAndGet()
        val time = System.currentTimeMillis()
        pendingId = id
        lastId = id
        lastWord = cands.firstOrNull()?.first ?: ""
        lastTime = time
        lastDecodeMs = OwnGestureDecoder.lastDecodeMs
        lastSpeed = OwnGestureDecoder.lastSpeedKeysPerSecond // the decoder's own figure, from the preprocessed path
        lastTuning = OwnGestureDecoder.currentTuning.key
        if (!isEnabled()) return
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

    /** The user replaced the pending swiped word with [word] via the suggestion strip; [rank] 0-based (0 = the word itself). */
    fun onSuggestionPicked(word: String, rank: Int) = finish(SwipeMetrics.OUTCOME_PICKED, rank, word)

    /** The user deleted the pending swiped word (backspace on a batch word). */
    fun onWordDeleted() = finish(SwipeMetrics.OUTCOME_DELETED, -1, null)

    /** Any other commit / new word: the pending swipe is settled as-is. */
    fun onWordSettled() = finish(SwipeMetrics.OUTCOME_KEPT, -1, null)

    /**
     * The composing word was committed as [word] some way other than a pick from the strip (which reports itself):
     * the pending swiped word is settled if that is what it still says, else it was edited into [word].
     */
    fun onWordCommitted(word: String) {
        if (pendingId < 0) return
        // auto-capitalisation ("the" committed as "The") is not an edit
        if (word.equals(lastWord, ignoreCase = true)) finish(SwipeMetrics.OUTCOME_KEPT, -1, null) else finish(SwipeMetrics.OUTCOME_EDITED, -1, word)
    }

    /** The pending swipe's outcome: to the swipe results log, and (corrections only) to the corpus. */
    private fun finish(outcome: String, rank: Int, word: String?) {
        val id = pendingId
        if (id < 0) return
        pendingId = -1L
        SwipeMetrics.onOutcome(id, lastWord, outcome, rank, word, lastDecodeMs, lastSpeed, lastTuning)
        when (outcome) {
            SwipeMetrics.OUTCOME_PICKED -> if (rank != 0) correction(id, "pick", word)
            SwipeMetrics.OUTCOME_DELETED -> correction(id, "deleted", null)
            SwipeMetrics.OUTCOME_EDITED -> correction(id, "edited", word)
        }
    }

    /** A committed word was opened for editing again, reading [word]: the last swipe's outcome is open again if that is its word. */
    fun onWordResumed(word: String) {
        if (lastId >= 0 && word == lastWord && System.currentTimeMillis() - lastTime <= RESUME_WINDOW_MS) pendingId = lastId
    }

    private fun correction(id: Long, how: String, word: String?) {
        if (!isEnabled()) return
        val time = System.currentTimeMillis()
        executor.execute {
            try {
                val obj = JSONObject().put("type", "final").put("ref", id).put("time", time).put("how", how)
                if (word != null) obj.put("final", word)
                append(obj)
            } catch (e: Exception) {
                Log.w(TAG, "failed to record correction", e)
            }
        }
    }

    private fun append(obj: JSONObject) {
        file?.appendText(obj.toString() + "\n")
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

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opt-in local log of what corrections do to the learned words ("Log corrections"), to look back at how the learning
 * rules work out. One tab-separated line per event in `learning_events.tsv` in the app's external files dir:
 *
 * time (ms) · event · how the word got there · word before · word after · learned count of the word before (before
 * the change, after it) · learned count of the word after (before, after) · language · app version · for undo and
 * redo only: the uses given (+) or taken back (-)
 *
 * A count of -1: the word isn't stored. A word that isn't in the dictionaries is stored at 0 by its first use and
 * counted from its second on. Nothing leaves the phone, and no word goes to logcat.
 *
 * The counts before are read when the event happens, the counts after a moment later ([SETTLE_MS]) on the log's own
 * thread, because the dictionaries apply their changes on a background thread. The same word used again within that
 * moment shows in the counts after too.
 */
object LearningEventLog {
    private const val TAG = "LearningEventLog"
    private const val FILE_NAME = "learning_events.tsv"
    private const val SETTLE_MS = 400L
    private const val FIELDS = 11

    // events
    /** a word committed as it stands (space, a pick from the strip, a swipe kept): +1 */
    const val ACCEPTED = "accepted"
    /** a word picked up again (e.g. space, backspace) and committed unchanged by a separator: not counted again */
    const val REACCEPTED = "reaccepted"
    /** backspace right after an auto-correction: the correction -1, the typed word counts when it's committed */
    const val AUTOCORRECT_REVERTED = "autocorrect_reverted"
    /** backspace took a fresh swipe away: it was never counted, nothing changes */
    const val SWIPE_DELETED = "swipe_deleted"
    /** a word picked up again by the cursor and changed: the old word -1, the new word +1 (when known, else a later
     *  `accepted` line from [EDIT]) */
    const val ACCEPTED_EDITED = "accepted_edited"
    /** a swiped word edited before it was committed: the final word +1 */
    const val SWIPE_EDITED = "swipe_edited"
    /** long-press Remove (strip) or Remove in the learned words screen: gone from the learned words */
    const val REMOVED = "removed"
    /** taken off the removed words in the learned words screen */
    const val RESTORED = "restored"
    /** the keyboard's undo took a step back: what the step's learning gave is taken back, what it took is given back
     *  (word before: uses taken back; word after: uses given; the last column says how many) */
    const val UNDO = "undo"
    /** the keyboard's redo did the step again: its learning again, exactly as it was (logged as [UNDO]) */
    const val REDO = "redo"
    val EVENTS = listOf(ACCEPTED, AUTOCORRECT_REVERTED, SWIPE_DELETED, ACCEPTED_EDITED, SWIPE_EDITED, REMOVED, RESTORED,
        UNDO, REDO)

    // how the word got there
    const val TYPED = "typed"
    const val SWIPED = "swiped"
    const val AUTOCORRECT = "autocorrect"
    const val STRIP = "strip"
    const val PREDICTION = "prediction"
    /** changed in place, the cursor inside the word; learned when the cursor leaves it */
    const val EDIT = "edit"
    /** the learned words screen */
    const val SETTINGS = "settings"
    /** an undo / redo that lost track of the step (the history was dropped, the text no longer lines up, or nothing was
     *  left and the app's own undo / redo ran): the learned words don't change */
    const val UNTRACKED = "untracked"
    /** an undo / redo of a step that changed no learned word (e.g. deleting text): nothing changes */
    const val NONE = "none"

    /** Reads how often a word was learned, -1 if it isn't stored. */
    fun interface Counts {
        fun of(word: String): Int
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "learning-log").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
        prefs = context.prefs()
    }

    // (read from the preferences, not the settings values: the learned words screen logs too, and the switch is one
    // for all keyboards)
    @JvmStatic
    fun isEnabled(): Boolean = file != null && prefs?.getBoolean(Settings.PREF_LEARNING_LOG, Defaults.PREF_LEARNING_LOG) == true

    /** An event in [language]: [before] and [after] are the words (empty if none), [counts] reads their learned counts;
     *  [change]: the uses given or taken back, if the event says (undo, redo). */
    @JvmStatic
    @JvmOverloads
    fun log(event: String, origin: String, before: String, after: String, language: String, counts: Counts,
            change: String = "") {
        if (!isEnabled()) return
        val time = System.currentTimeMillis()
        val beforeThen = countOf(counts, before)
        val afterThen = countOf(counts, after)
        executor.schedule({
            try {
                file?.appendText(line(time, event, origin, before, after, beforeThen, countOf(counts, before),
                    afterThen, countOf(counts, after), language, BuildConfig.VERSION_NAME, change) + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "could not log a learning event", e)
            }
        }, SETTLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun countOf(counts: Counts, word: String): Int =
        if (word.isEmpty()) -1 else try { counts.of(word) } catch (e: Exception) { -1 }

    /** The counts in one language's learned words: the word as written, else lowercase (a sentence-start capital). */
    @JvmStatic
    fun countsIn(history: ExpandableBinaryDictionary): Counts = Counts { word ->
        val count = history.getLearnedCount(word)
        val lower = word.lowercase()
        if (count >= 0 || lower == word) count else history.getLearnedCount(lower)
    }

    /** One line of the log, without the line break. Tabs and line breaks in words would break the columns: spaces.
     *  [change] is a last column only where there is one (undo, redo). */
    fun line(time: Long, event: String, origin: String, before: String, after: String, beforeThen: Int, beforeNow: Int,
             afterThen: Int, afterNow: Int, language: String, version: String, change: String = ""): String =
        (listOf(time, event, origin, clean(before), clean(after), beforeThen, beforeNow, afterThen, afterNow, language, version)
            + (if (change.isEmpty()) emptyList() else listOf(change))).joinToString("\t")

    private fun clean(word: String) = word.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** How many events of each kind (see [EVENTS]) were logged in the last [days] days (0 = all), and how many of them
     *  lost track ([UNTRACKED]: undo and redo). */
    class Summary(val counts: Map<String, Int>, val untrackedCounts: Map<String, Int> = emptyMap()) {
        val total get() = counts.values.sum()
        fun of(event: String) = counts[event] ?: 0
        fun untrackedOf(event: String) = untrackedCounts[event] ?: 0
    }

    fun summary(days: Int): Summary {
        val f = file ?: return Summary(emptyMap())
        if (!f.isFile) return Summary(emptyMap())
        return try { f.useLines { summarize(it, days, System.currentTimeMillis()) } }
        catch (e: Exception) {
            Log.w(TAG, "could not read the learning events", e)
            Summary(emptyMap())
        }
    }

    fun summarize(lines: Sequence<String>, days: Int, now: Long): Summary {
        val since = if (days <= 0) 0L else now - days * 86_400_000L
        val counts = HashMap<String, Int>()
        val untracked = HashMap<String, Int>()
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < FIELDS) continue
            val time = p[0].toLongOrNull() ?: continue
            if (time < since) continue
            counts[p[1]] = (counts[p[1]] ?: 0) + 1
            if (p[2] == UNTRACKED) untracked[p[1]] = (untracked[p[1]] ?: 0) + 1
        }
        return Summary(counts, untracked)
    }

    /** Deletes the log after the events already queued (they wait [SETTLE_MS]); waits, so a summary read right after it
     *  sees the empty state. */
    fun clear() {
        try { executor.schedule({ file?.delete() }, SETTLE_MS, TimeUnit.MILLISECONDS).get(SETTLE_MS + 2000, TimeUnit.MILLISECONDS) }
        catch (e: Exception) { Log.w(TAG, "could not clear the learning events", e) }
    }
}

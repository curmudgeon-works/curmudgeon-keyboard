// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Opt-in local log of how every swipe ended, for the three numbers that say how swiping is going (and the decode
 * time): first choice right (kept as swiped), picked from the strip (with the rank), never offered (deleted,
 * retyped or edited into another word). One tab-separated line per outcome in `swipe_results.tsv` in the app's
 * external files dir: time, swipe id, outcome, rank, swiped word, final word, decode ms, speed (key widths per
 * second), tuning key, decoder version ([helium314.keyboard.gesture.DECODER_VERSION]), app version, keyboard start
 * ([START]: swipe ids begin again at every start). Nothing leaves the phone. A swipe whose word is edited again later gets a second line with the
 * same id; the summary keeps the last.
 *
 * Fed by [GestureCorpusRecorder], which follows each swiped word until it is settled, whether or not the corpus
 * itself is being recorded.
 */
object SwipeMetrics {
    private const val TAG = "SwipeMetrics"
    private const val FILE_NAME = "swipe_results.tsv"
    const val OUTCOME_KEPT = "kept"
    const val OUTCOME_PICKED = "picked"
    const val OUTCOME_DELETED = "deleted"
    const val OUTCOME_EDITED = "edited"
    /** the decoder found no word at all */
    const val OUTCOME_NONE = "none"
    /** the swipe came before the vocabulary was built (right after a start): a dead swipe, nothing written */
    const val OUTCOME_NO_VOCABULARY = "novocab"

    /** This start of the keyboard, on every line: with the id it names one swipe (lines from before 2026-10-06 have none). */
    private val START = System.currentTimeMillis().toString(36)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "swipe-metrics").apply { isDaemon = true } }
    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
    }

    // (what the settings say, replaceable by tests)
    internal var metricsOn: () -> Boolean = { Settings.getValues()?.mSwipeMetrics == true }
    /** Typing privately now: incognito, a private tab or an app asking for no learning, a password (review 2026-10-06). */
    internal var privateNow: () -> Boolean = { Settings.getValues()?.mIncognitoModeEnabled == true }

    // (whether a swipe is private is decided when it's made, by GestureCorpusRecorder, not here when it's written)
    fun isEnabled(): Boolean = metricsOn() && file != null

    /** A swipe ended: [outcome] is one of the OUTCOME_ constants, [rank] the 0-based strip rank of a pick (else -1). */
    fun onOutcome(id: Long, swiped: String, outcome: String, rank: Int, finalWord: String?, decodeMs: Long, keysPerSecond: Float, tuningKey: String) {
        if (!isEnabled()) return
        val time = System.currentTimeMillis()
        executor.execute {
            try {
                file?.appendText(listOf(time, id, outcome, rank, swiped, finalWord ?: "", decodeMs,
                    String.format(java.util.Locale.ROOT, "%.1f", keysPerSecond), tuningKey,
                    helium314.keyboard.gesture.DECODER_VERSION, helium314.keyboard.latin.BuildConfig.VERSION_NAME, START).joinToString("\t") + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "could not log a swipe outcome", e)
            }
        }
    }

    class Summary(val swipes: Int, val firstChoice: Int, val fromStrip: Int, val neverOffered: Int, val decodeMs: List<Int>) {
        fun pct(n: Int) = if (swipes == 0) 0 else Math.round(100f * n / swipes)
        val decodeAverage get() = if (decodeMs.isEmpty()) 0 else decodeMs.sum() / decodeMs.size
        val decodeWorst get() = decodeMs.maxOrNull() ?: 0
    }

    /** How one swipe ended (its last line in the log), for the summaries and the chart. */
    class Outcome(val time: Long, val outcome: String, val rank: Int, val decodeMs: Int?, val tuning: String) {
        val firstChoice get() = outcome == OUTCOME_KEPT || (outcome == OUTCOME_PICKED && rank == 0)
        val fromStrip get() = outcome == OUTCOME_PICKED && rank != 0
        val neverOffered get() = !firstChoice && !fromStrip
    }

    /** The log read once: every swipe's outcome, oldest first, and the times tracking was restarted. */
    class Results(val outcomes: List<Outcome>, val restarts: List<Long>)

    /** How far below the highest id a swipe logged again can be (the dead swipes after it took the ids between). */
    private const val RELOG_SPAN = 5

    /** A line that restarts the tracking: the summaries and the chart can start from it, nothing is deleted. */
    private const val MARK_RESTART = "restart"

    /**
     * Reads the whole log. A swipe edited again later has a second line with its id: the last one counts. Ids start
     * from 1 again on every start of the keyboard, so an id back at 1 or 2 (or far below the highest) begins a new run
     * of ids; a swipe logged again is always the latest swipe, a few ids below the highest at most. Before 2026-10-05
     * the summary kept one swipe per id across all runs, and counted 189 of 637 swipes in one day.
     */
    fun read(): Results {
        val f = file
        if (f == null || !f.isFile) return Results(emptyList(), emptyList())
        val last = LinkedHashMap<Pair<String, Long>, Outcome>()
        val restarts = ArrayList<Long>()
        var run = 0
        var maxId = 0L
        try {
            f.forEachLine { line ->
                val p = line.split('\t')
                val time = p[0].toLongOrNull() ?: return@forEachLine
                if (p.size >= 3 && p[2] == MARK_RESTART) { restarts.add(time); return@forEachLine }
                if (p.size < 7) return@forEachLine
                val id = p[1].toLongOrNull() ?: return@forEachLine
                // a new run of ids (the keyboard started again) starts at 1 (2 if the first swipe's line was lost); a line
                // a little below the highest is the latest swipe logged again after dead swipes took ids in between
                // (review 2026-10-06: counted it twice)
                // lines naming their keyboard start need no guessing (review 2026-10-06: the guess still counted some twice)
                val start = p.getOrNull(11)?.takeIf { it.isNotEmpty() }
                if (start == null) {
                    if (id < maxId && (id <= 2 || maxId - id > RELOG_SPAN)) { run++; maxId = 0L }
                    maxId = maxOf(maxId, id)
                }
                val key = (start ?: "run$run") to id
                last.remove(key) // (re-inserted: the order stays by time of the last outcome)
                last[key] = Outcome(time, p[2], p[3].toIntOrNull() ?: -1, p[6].toIntOrNull(), p.getOrElse(8) { "" })
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the swipe results", e)
        }
        return Results(last.values.sortedBy { it.time }, restarts)
    }

    /** The numbers over [outcomes]. */
    fun summaryOf(outcomes: List<Outcome>) = Summary(outcomes.size, outcomes.count { it.firstChoice },
        outcomes.count { it.fromStrip }, outcomes.count { it.neverOffered }, outcomes.mapNotNull { it.decodeMs })

    /** The numbers over the outcomes logged in the last [days] days (0 = all). */
    fun summary(days: Int): Summary {
        val since = if (days <= 0) 0L else System.currentTimeMillis() - days * 86_400_000L
        return summaryOf(read().outcomes.filter { it.time >= since })
    }

    /** What the chart can show: all of the log, since the swipe tuning (the 6 settings) last changed, or since the last
     *  restart of the tracking. */
    enum class Range { ALL, SINCE_TUNING, SINCE_RESTART }

    /** Where [range] starts in [log], or null when it has no start (no restart yet; nothing logged). */
    fun rangeStart(log: Results, range: Range): Long? = when (range) {
        Range.ALL -> log.outcomes.firstOrNull()?.time
        Range.SINCE_RESTART -> log.restarts.lastOrNull()
        Range.SINCE_TUNING -> {
            // the swipes with the latest swipe's tuning, back to the first swipe after one with other settings
            val tuning = log.outcomes.lastOrNull()?.tuning
            if (tuning == null) null
            else log.outcomes.takeLastWhile { it.tuning == tuning }.firstOrNull()?.time
        }
    }

    /** One point of the chart: the [swipes] swipes up to swipe number [index] of the range (counted from 1). */
    class Point(val index: Int, val swipes: Int, val firstChoicePct: Float, val neverOfferedPct: Float, val decodeMs: Float)

    /** Each point averages this many swipes (fewer at the start of a range) … */
    const val ROLLING_WINDOW = 50
    /** … and comes every this many swipes, more for a long range so the chart keeps at most [MAX_POINTS] points. */
    private const val POINT_EVERY = 10
    private const val MAX_POINTS = 300

    /**
     * The chart's points from [from] on, by swipe (2026-10-06): a rolling average of the last [ROLLING_WINDOW] swipes
     * every [POINT_EVERY] swipes (a step of 10 alone swung ±14 points by chance; 50 swings about ±6). The first point
     * comes at the 10th swipe, averaging what there is; the last is always the latest swipe.
     */
    fun points(log: Results, from: Long): List<Point> {
        val o = log.outcomes.filter { it.time >= from }
        if (o.isEmpty()) return emptyList()
        var step = POINT_EVERY
        while (o.size / step > MAX_POINTS) step *= 2
        val ends = (step..o.size step step).toMutableList()
        if (ends.isEmpty() || ends.last() != o.size) ends.add(o.size)
        return ends.map { end ->
            val g = o.subList(maxOf(0, end - ROLLING_WINDOW), end)
            val ms = g.mapNotNull { it.decodeMs }
            Point(end, g.size, 100f * g.count { it.firstChoice } / g.size, 100f * g.count { it.neverOffered } / g.size,
                if (ms.isEmpty()) 0f else ms.average().toFloat())
        }
    }

    /** Restarts the tracking from now: a marker line; nothing is deleted. Waits, so a read right after it sees it. */
    fun restartTracking() {
        try {
            executor.submit { file?.appendText("${System.currentTimeMillis()}\t-1\t$MARK_RESTART\n") }
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "could not restart the swipe results tracking", e)
        }
    }

    /** Deletes the log, after any outcome already queued; waits so a summary read right after it sees the empty state. */
    fun clear() {
        try { executor.submit { file?.delete() }.get(2, java.util.concurrent.TimeUnit.SECONDS) }
        catch (e: Exception) { Log.w(TAG, "could not clear the swipe results", e) }
    }
}

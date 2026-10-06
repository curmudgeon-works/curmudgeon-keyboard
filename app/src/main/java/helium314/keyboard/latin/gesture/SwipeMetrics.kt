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
 * second), tuning key, decoder version ([helium314.keyboard.gesture.DECODER_VERSION]), app version. Nothing leaves
 * the phone. A swipe whose word is edited again later gets a second line with the
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

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "swipe-metrics").apply { isDaemon = true } }
    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
    }

    fun isEnabled(): Boolean = Settings.getValues().mSwipeMetrics && file != null

    /** A swipe ended: [outcome] is one of the OUTCOME_ constants, [rank] the 0-based strip rank of a pick (else -1). */
    fun onOutcome(id: Long, swiped: String, outcome: String, rank: Int, finalWord: String?, decodeMs: Long, keysPerSecond: Float, tuningKey: String) {
        if (!isEnabled()) return
        val time = System.currentTimeMillis()
        executor.execute {
            try {
                file?.appendText(listOf(time, id, outcome, rank, swiped, finalWord ?: "", decodeMs,
                    String.format(java.util.Locale.ROOT, "%.1f", keysPerSecond), tuningKey,
                    helium314.keyboard.gesture.DECODER_VERSION, helium314.keyboard.latin.BuildConfig.VERSION_NAME).joinToString("\t") + "\n")
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

    /** A line that restarts the tracking: the summaries and the chart can start from it, nothing is deleted. */
    private const val MARK_RESTART = "restart"

    /**
     * Reads the whole log. A swipe edited again later has a second line with its id: the last one counts. Ids start
     * from 1 again on every start of the keyboard, so a lower id than the one before begins a new run of ids (a swipe
     * logged again is always the latest swipe, never an older one); before 2026-10-05 the summary kept one swipe per
     * id across all runs, and counted 189 of 637 swipes in one day.
     */
    fun read(): Results {
        val f = file
        if (f == null || !f.isFile) return Results(emptyList(), emptyList())
        val last = LinkedHashMap<Pair<Int, Long>, Outcome>()
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
                if (id < maxId) { run++; maxId = 0L }
                maxId = maxOf(maxId, id)
                val key = run to id
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

    /** One point of the chart: [swipes] swipes up to [time]. */
    class Point(val time: Long, val swipes: Int, val firstChoicePct: Float, val neverOfferedPct: Float, val decodeMs: Float)

    /** At least this many swipes per point: a bucket with fewer is merged into the next (the last into the one before). */
    private const val MIN_SWIPES_PER_POINT = 10

    /**
     * The chart's points from [from] to now: one per hour while the range is under 2 days, per day under 60 days, per
     * week beyond.
     */
    fun points(log: Results, from: Long, now: Long = System.currentTimeMillis()): List<Point> {
        val span = now - from
        val bucket = when {
            span < 2 * 86_400_000L -> 3_600_000L
            span < 60 * 86_400_000L -> 86_400_000L
            else -> 7 * 86_400_000L
        }
        val groups = log.outcomes.filter { it.time >= from }.groupBy { (it.time - from) / bucket }.toSortedMap().values
        val merged = ArrayList<MutableList<Outcome>>()
        var carry = ArrayList<Outcome>()
        for (g in groups) {
            carry.addAll(g)
            if (carry.size >= MIN_SWIPES_PER_POINT) { merged.add(carry); carry = ArrayList() }
        }
        if (carry.isNotEmpty()) { if (merged.isEmpty()) merged.add(carry) else merged.last().addAll(carry) }
        return merged.map { g ->
            val ms = g.mapNotNull { it.decodeMs }
            Point(g.last().time, g.size, 100f * g.count { it.firstChoice } / g.size, 100f * g.count { it.neverOffered } / g.size,
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

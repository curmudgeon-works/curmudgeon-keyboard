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

    /** The numbers over the outcomes logged in the last [days] days (0 = all). */
    fun summary(days: Int): Summary {
        val f = file ?: return Summary(0, 0, 0, 0, emptyList())
        if (!f.isFile) return Summary(0, 0, 0, 0, emptyList())
        val since = if (days <= 0) 0L else System.currentTimeMillis() - days * 86_400_000L
        val last = LinkedHashMap<Long, List<String>>() // the latest outcome per swipe id
        try {
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 7) return@forEachLine
                val time = p[0].toLongOrNull() ?: return@forEachLine
                if (time < since) return@forEachLine
                last[p[1].toLongOrNull() ?: return@forEachLine] = p
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the swipe results", e)
        }
        var first = 0; var strip = 0; var never = 0
        val ms = ArrayList<Int>(last.size)
        for (p in last.values) {
            when (p[2]) {
                OUTCOME_KEPT -> first++
                OUTCOME_PICKED -> if (p[3].toIntOrNull() == 0) first++ else strip++
                else -> never++
            }
            p[6].toIntOrNull()?.let { ms.add(it) }
        }
        return Summary(last.size, first, strip, never, ms)
    }

    fun clear() {
        executor.execute { file?.delete() }
    }
}

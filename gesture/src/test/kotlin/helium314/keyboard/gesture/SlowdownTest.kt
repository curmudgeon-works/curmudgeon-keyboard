// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Slowing down over a key is a soft weight: it favours words with that letter, but a slow
 * spot is never a point that must match a letter, and a full stop counts as hesitation.
 * A straight t→p swipe runs over both 'i' and 'o', so "tip" and "top" fit it equally well.
 */
class SlowdownTest {
    private val geometry = QwertyFixture.geometry
    private val preprocessor = GesturePreprocessor()

    /**
     * The straight t→p path, a third of a key below the key centers (a perfect path would score the
     * floor for both words), [speedAtO] times the normal speed within half a key of 'o'.
     */
    private fun tpPath(speedAtO: Float): List<GesturePoint> {
        val o = geometry.keyForWordChar('o')!!
        val ideal = SyntheticPathGenerator.idealPath("tp", geometry)
        val out = ArrayList<GesturePoint>(ideal.size)
        var t = 0L
        for ((i, p) in ideal.withIndex()) {
            if (i > 0) {
                val prev = ideal[i - 1]
                val d = sqrt((p.x - prev.x) * (p.x - prev.x) + (p.y - prev.y) * (p.y - prev.y))
                val nearO = abs(p.x - o.centerX) < geometry.keyWidth / 2
                t += (d / (if (nearO) speedAtO else 1f)).toLong().coerceAtLeast(1L)
            }
            out.add(GesturePoint(p.x, p.y + QwertyFixture.KEY_HEIGHT / 3, t))
        }
        return out
    }

    private fun score(path: List<GesturePoint>, word: String, emphasis: Float): Float =
        KushlerScorer(KushlerConfig(slowEmphasis = emphasis))
            .score(preprocessor.preprocess(path, geometry), SokgraphBuilder.build(word, geometry)!!, geometry)

    @Test
    fun slowingOverAKeyFavoursItsLetter() {
        val path = tpPath(speedAtO = 0.4f)
        assertTrue(score(path, "top", 1f) < score(path, "tip", 1f), "slow over 'o' should favour top")
    }

    @Test
    fun noEmphasisNoPreference() {
        val path = tpPath(speedAtO = 0.4f)
        assertTrue(abs(score(path, "top", 0f) - score(path, "tip", 0f)) < 1e-4f, "emphasis 0 = slowdown ignored")
    }

    @Test
    fun aStopIsNotASlowdown() {
        val path = tpPath(speedAtO = 0.02f)
        assertTrue(abs(score(path, "top", 1f) - score(path, "top", 0f)) < 1e-4f, "a stop reads as hesitation, no bonus")
    }
}

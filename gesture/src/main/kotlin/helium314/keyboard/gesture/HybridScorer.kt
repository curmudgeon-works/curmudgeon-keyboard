// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

/**
 * Blend of [KushlerScorer] (inflection points matched to keys) and [LocationScorer]
 * (the whole path against the word's ideal path). Weights are configurable; the
 * default (half each) is what did best on replayed real swipes.
 */
class HybridScorer(
    private val kushler: KushlerScorer = KushlerScorer(),
    private val location: LocationScorer = LocationScorer(),
    private val kushlerWeight: Float = 0.5f,
    private val locationWeight: Float = 0.5f,
) : Scorer {
    override val name = "hybrid"

    override fun score(gesture: PreprocessedGesture, sokgraph: Sokgraph, geometry: KeyboardGeometry): Float {
        val k = kushler.score(gesture, sokgraph, geometry)
        val l = location.score(gesture, sokgraph, geometry)
        if (k == Float.MAX_VALUE || l == Float.MAX_VALUE) return Float.MAX_VALUE
        return (kushlerWeight * k + locationWeight * l).coerceAtLeast(Scorer.MIN_SCORE)
    }
}

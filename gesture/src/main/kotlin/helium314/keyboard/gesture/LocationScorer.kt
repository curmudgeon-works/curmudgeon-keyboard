// SPDX-License-Identifier: GPL-3.0-only
// Whole-path location scorer: the drawn path and the word's ideal polyline (sokgraph), both
// resampled to the same number of points, compared point by point in keyboard coordinates,
// with the ends weighted more (they carry the most information). Location only: the path's
// shape is not compared separately.
package helium314.keyboard.gesture

import kotlin.math.sqrt

class LocationConfig(
    /** Number of resampled points for both the path and the template. */
    val sampleCount: Int = 40,
    /** Weight of the first/last sample relative to the middle; the ends are where people land where they mean to. */
    val endpointEmphasis: Float = 2f,
)

class LocationScorer(private val config: LocationConfig = LocationConfig()) : Scorer {
    override val name = "location"

    override fun score(gesture: PreprocessedGesture, sokgraph: Sokgraph, geometry: KeyboardGeometry): Float {
        if (gesture.points.isEmpty() || sokgraph.points.isEmpty()) return Float.MAX_VALUE
        val n = config.sampleCount
        val (gx, gy) = Geom.resampleToN(padIfSingle(FloatArray(gesture.points.size) { gesture.points[it].x }),
            padIfSingle(FloatArray(gesture.points.size) { gesture.points[it].y }), n)
        val (tx, ty) = Geom.resampleToN(padIfSingle(FloatArray(sokgraph.points.size) { sokgraph.points[it].x }),
            padIfSingle(FloatArray(sokgraph.points.size) { sokgraph.points[it].y }), n)
        var dist = 0f
        var weightSum = 0f
        val half = (n - 1) / 2f
        for (i in 0 until n) {
            val u = if (half <= 0f) 0f else (i - half) / half // -1..1
            val w = 1f + (config.endpointEmphasis - 1f) * u * u
            val dx = gx[i] - tx[i]
            val dy = gy[i] - ty[i]
            dist += w * sqrt(dx * dx + dy * dy)
            weightSum += w
        }
        return (dist / weightSum / geometry.keyWidth).coerceAtLeast(Scorer.MIN_SCORE) // in key widths
    }

    /** resampleToN needs ≥1 point; duplicate a lone point so degenerate templates work. */
    private fun padIfSingle(a: FloatArray): FloatArray =
        if (a.size == 1) floatArrayOf(a[0], a[0]) else a
}

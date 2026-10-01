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
    /**
     * Speed awareness (swipe speed in key widths per second above [speedFromKeysPerSecond]): the end emphasis falls
     * by [endsRelaxPerKeyPerSecond] per key/s (never below 1), since fast swipes land less exactly; and a sample's
     * distance saturates at [saturationKeyWidths] key widths (0 = off) so one corner a key short cannot sink a
     * word that fits everywhere else — [saturationPerKeyPerSecond] lowers that cap further for fast swipes.
     */
    val speedFromKeysPerSecond: Float = 12f,
    val endsRelaxPerKeyPerSecond: Float = 0f,
    val saturationKeyWidths: Float = 0f,
    val saturationPerKeyPerSecond: Float = 0f,
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
        val kw = geometry.keyWidth
        val excess = (gesture.meanSpeed * 1000f / kw - config.speedFromKeysPerSecond).coerceAtLeast(0f)
        val emphasis = (config.endpointEmphasis - config.endsRelaxPerKeyPerSecond * excess).coerceAtLeast(1f)
        val cap = if (config.saturationKeyWidths <= 0f) 0f
            else (config.saturationKeyWidths - config.saturationPerKeyPerSecond * excess).coerceAtLeast(0.5f) * kw
        var dist = 0f
        var weightSum = 0f
        val half = (n - 1) / 2f
        for (i in 0 until n) {
            val u = if (half <= 0f) 0f else (i - half) / half // -1..1
            val w = 1f + (emphasis - 1f) * u * u
            val dx = gx[i] - tx[i]
            val dy = gy[i] - ty[i]
            var d = sqrt(dx * dx + dy * dy)
            if (cap > 0f) d = cap * (1f - kotlin.math.exp(-d / cap)) // soft cap: linear when small, levels off at cap
            dist += w * d
            weightSum += w
        }
        return (dist / weightSum / kw).coerceAtLeast(Scorer.MIN_SCORE) // in key widths
    }

    /** resampleToN needs ≥1 point; duplicate a lone point so degenerate templates work. */
    private fun padIfSingle(a: FloatArray): FloatArray =
        if (a.size == 1) floatArrayOf(a[0], a[0]) else a
}

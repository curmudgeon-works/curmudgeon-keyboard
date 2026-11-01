// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

/**
 * One line of the replay corpus (corpus.tsv, made by make_corpus.py from the device's gesture_corpus.jsonl): the swipe
 * as the device saw it, and the keyboard geometry it is replayed with. Columns: id, locale, label source, label, what
 * the device got, its candidates, keys, points, then (FUTO imports) the session, and (since 2026-10-31) the apostrophe
 * setting the device decoded it with: [PERIOD] or [SKIP] as the corpus line's "decode.apostropheViaPeriod" says (the
 * device records it since 2026-10-23), else empty.
 */
object ReplayCorpus {
    /** The apostrophe setting on: through the period key, which means both ways since 2026-10-31. */
    const val PERIOD = "period"
    /** The apostrophe setting off: the apostrophe is skipped. */
    const val SKIP = "skip"
    /** Both ways, named as such (an override only; the device has no third state). */
    const val BOTH = "both"

    class Swipe(
        val id: Int, val locale: String, val labelSource: String, val label: String, val phoneGot: String,
        val geometry: KeyboardGeometry, val points: List<GesturePoint>,
        /** who swiped it, where the corpus says (a 9th column: the FUTO import's sessions); else empty */
        val session: String = "",
        /** the apostrophe setting the device decoded it with ([PERIOD] / [SKIP]), empty when the corpus doesn't say */
        val apostrophe: String = "",
    )

    /**
     * The geometry a swipe is replayed with, scoring apostrophe words as the device did: with [override] set (the
     * GESTURE_REPLAY_APOSTROPHE environment variable: period, skip or both) that for every swipe; else the setting the
     * device decoded it with, as [recorded] says, on meaning both ways as on the device since 2026-10-31; else skipped,
     * the device's default before the setting was recorded (2026-10-18 to 2026-10-31).
     */
    fun geometry(keys: List<KeyInfo>, recorded: String, override: String?): KeyboardGeometry = when (override ?: recorded) {
        PERIOD, BOTH -> KeyboardGeometry(keys, apostropheViaPeriod = true, apostropheBothWays = true)
        else -> KeyboardGeometry(keys, apostropheViaPeriod = false)
    }

    fun parseKeys(field: String): List<KeyInfo> = field.split(';').map { k ->
        val p = k.split(':')
        KeyInfo(p[0][0], p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
    }

    fun parsePoints(field: String): List<GesturePoint> =
        field.split(';').map { p -> val v = p.split(','); GesturePoint(v[0].toFloat(), v[1].toFloat(), v[2].toLong()) }

    /** The swipe a corpus [line] holds, replayed with the geometry [geometry] gives for it. */
    fun parse(line: String, override: String?): Swipe {
        val f = line.split('\t')
        val recorded = f.getOrElse(9) { "" }
        return Swipe(f[0].toInt(), f[1], f[2], f[3], f[4], geometry(parseKeys(f[6]), recorded, override), parsePoints(f[7]),
            f.getOrElse(8) { "" }, recorded)
    }

    /** A corpus line as make_corpus.py writes it, for tests. */
    fun line(id: Int, label: String, phoneGot: String, keys: List<KeyInfo>, points: List<GesturePoint>, session: String = "", apostrophe: String = ""): String =
        listOf(id.toString(), "en-US", "kept", label, phoneGot, phoneGot,
            keys.joinToString(";") { "${it.char}:${it.centerX}:${it.centerY}:${it.width}:${it.height}" },
            points.joinToString(";") { "${it.x},${it.y},${it.t}" }, session, apostrophe).joinToString("\t")
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

/**
 * A replay variant from a spec like "k=0.4 slow=1": the device's decoder ([PhoneDecoderFactory] at its defaults) with
 * the named settings changed. "phone" (or any spec naming nothing) is the device's decoder itself.
 *
 * k = inflection scorer's share of the blend (the rest is the location scorer), slow = slowdown soft weight,
 * turn = turn confidence scale, ends = location scorer's end emphasis, samples = its resampled points,
 * endR = start/end key radius, corridor = how far off the path a letter may be, stop = stop detection (dt factor),
 * skip = penalty for an inflection no letter uses, cap = candidates scored, reach / shortR / shortL = the long-reach
 * corner rule (reach length, allowed shortfall reaching right / left), prog = letter-order slack, band / slack =
 * the path-length band (ratio, additive key widths), exc = how far above the keys (key heights) a swipe is cut,
 * caps = 1 to capitalize the letter an excursion leaves from (the device's default is 0 since 2026-10-18),
 * apos = the cost of an apostrophe word matched without its period-key detour (the setting on, both ways),
 * spdFrom = the speed (key widths per second) the fast-swipe rules start at, matchRelax / fqRate = how much they
 * relax the corner match / lean on frequency per key width per second over it, hist = the learned-word boost.
 */
object ReplaySpec {
    fun configs(spec: String): PhoneDecoderFactory.Configs {
        val v = spec.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toFloat() }
        fun f(key: String, default: Float) = v[key] ?: default
        fun i(key: String, default: Int) = v[key]?.toInt() ?: default
        val phone = PhoneDecoderFactory.configs()
        val kushler = phone.kushler
        val location = phone.location
        val decoder = phone.decoder
        val preprocessor = phone.preprocessor
        return PhoneDecoderFactory.Configs(
            kushler = kushler.copy(slowEmphasis = f("slow", kushler.slowEmphasis), skipInflectionPenalty = f("skip", kushler.skipInflectionPenalty),
                speedFromKeysPerSecond = f("spdFrom", kushler.speedFromKeysPerSecond),
                matchRelaxPerKeyPerSecond = f("matchRelax", kushler.matchRelaxPerKeyPerSecond)),
            location = location.copy(sampleCount = i("samples", location.sampleCount), endpointEmphasis = f("ends", location.endpointEmphasis),
                speedFromKeysPerSecond = f("spdFrom", location.speedFromKeysPerSecond),
                endsRelaxPerKeyPerSecond = f("endsRelax", location.endsRelaxPerKeyPerSecond),
                saturationKeyWidths = f("sat", location.saturationKeyWidths), saturationPerKeyPerSecond = f("satRate", location.saturationPerKeyPerSecond)),
            decoder = decoder.copy(endpointRadiusKeyWidths = f("endR", decoder.endpointRadiusKeyWidths),
                corridorRadiusKeyWidths = f("corridor", decoder.corridorRadiusKeyWidths),
                progressSlackKeyWidths = f("prog", decoder.progressSlackKeyWidths), lengthRatioBand = f("band", decoder.lengthRatioBand),
                lengthSlackKeyWidths = f("slack", decoder.lengthSlackKeyWidths), missedLetterShare = f("miss", decoder.missedLetterShare),
                speedWidenFromKeysPerSecond = f("spdFrom", decoder.speedWidenFromKeysPerSecond),
                speedWidenPerKeyPerSecond = f("spdRate", decoder.speedWidenPerKeyPerSecond),
                frequencyEmphasisFromKeysPerSecond = f("fqFrom", decoder.frequencyEmphasisFromKeysPerSecond),
                frequencyEmphasisPerKeyPerSecond = f("fqRate", decoder.frequencyEmphasisPerKeyPerSecond),
                maxCandidates = i("cap", decoder.maxCandidates), longReachKeyWidths = f("reach", decoder.longReachKeyWidths),
                reachShortfallRightKeyWidths = f("shortR", decoder.reachShortfallRightKeyWidths),
                reachShortfallLeftKeyWidths = f("shortL", decoder.reachShortfallLeftKeyWidths),
                learnedBoost = i("hist", decoder.learnedBoost),
                capsExcursions = v["caps"]?.let { it != 0f } ?: decoder.capsExcursions,
                apostropheSkipPenalty = f("apos", decoder.apostropheSkipPenalty)),
            preprocessor = preprocessor.copy(turnConfidenceScale = f("turn", preprocessor.turnConfidenceScale),
                stopDtFactor = f("stop", preprocessor.stopDtFactor),
                excursionMinHeightKeyHeights = f("exc", preprocessor.excursionMinHeightKeyHeights)),
            kushlerWeight = f("k", phone.kushlerWeight),
        )
    }

    /** The decoder, with its scorers, a [spec] names. */
    fun build(spec: String): PhoneDecoderFactory.Built = PhoneDecoderFactory.build(configs(spec))

    fun decoder(spec: String): GestureDecoder = build(spec).decoder
}

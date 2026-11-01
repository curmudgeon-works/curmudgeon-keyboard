// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

/**
 * The one place the device's decoder is built from the user's settings. The app (OwnGestureDecoder.decoderFor) and the
 * replay's "phone" variant (ReplayTest) both call it, so the swipes replayed on the desk are scored by the decoder the
 * device runs, not by a copy of it (review 2026-10-21 L4: the two had agreed only by maintenance; the replay's copy
 * capitalized excursions the device had stopped capitalizing on 2026-10-18).
 *
 * The decoder's defaults live here too, and the app's Defaults.kt takes its swipe-decoder defaults from them, so a
 * replay with [Settings] at its defaults is the device at its defaults.
 */
object PhoneDecoderFactory {
    // the defaults of the settings the device builds its decoder from (Defaults.kt: PREF_GESTURE_*)
    /** Multiplier on the confidence of turn inflections. */
    const val DEFAULT_TURN_WEIGHT = 1.0f
    /** How much slowing down over a key favors words with that letter (soft weight); 0 = off. */
    const val DEFAULT_SLOWDOWN_WEIGHT = 0.5f
    /** Share of the inflection scorer in the blend; the rest is the whole-path location scorer (replay 2026-09-28: 0.4 beat 0.5). */
    const val DEFAULT_KUSHLER_WEIGHT = 0.4f
    /** Added to a learned word's frequency (0..255 scale) in the swipe vocabulary. */
    const val DEFAULT_HISTORY_BOOST = 64
    /** Fast swipes: per key width per second over [DEFAULT_FAST_SPEED], the word frequency weighs this much more (replay 2026-10-01). */
    const val DEFAULT_FAST_COMMON_WORDS = 0.05f
    /** Fast swipes: per key width per second over [DEFAULT_FAST_SPEED], the corner match costs are divided down by this much. */
    const val DEFAULT_CORNER_MISS = 0.05f
    /** Key widths per second above which a swipe counts as fast. */
    const val DEFAULT_FAST_SPEED = 12f
    /** With the apostrophe setting on (both ways): an apostrophe word matched without its period-key detour ranks (1 + this) times worse. */
    const val DEFAULT_APOSTROPHE_SKIP_COST = 0.05f
    /** Swiping up above the keyboard capitalizes the letter left from; off since 2026-10-18 (stray capitals: goIng, thOugh in 1% of real swipes). */
    const val DEFAULT_CAPS_SWIPE = false
    /** Percent of a key height a swipe must rise above the keyboard to count as an excursion; 75 until 2026-10-18. */
    const val DEFAULT_CAPS_HEIGHT_PERCENT = 125
    /** [PreprocessorConfig.stopDtFactor] on the device: tighter than the class default (3.5), as since the first on-device build. */
    const val STOP_DT_FACTOR = 2.5f

    /**
     * What the device builds its decoder from: the user's tuning (the eight weights of OwnGestureDecoder.Tuning, in its
     * order), the caps settings and "suggest learned words". At their defaults, the device as shipped.
     * [capsHeight] is in key heights (the setting is in percent).
     */
    data class Settings(
        val turn: Float = DEFAULT_TURN_WEIGHT,
        val slowdown: Float = DEFAULT_SLOWDOWN_WEIGHT,
        val kushler: Float = DEFAULT_KUSHLER_WEIGHT,
        val historyBoost: Int = DEFAULT_HISTORY_BOOST,
        val fastCommon: Float = DEFAULT_FAST_COMMON_WORDS,
        val cornerMiss: Float = DEFAULT_CORNER_MISS,
        val fastFrom: Float = DEFAULT_FAST_SPEED,
        val apostropheSkip: Float = DEFAULT_APOSTROPHE_SKIP_COST,
        val capsHeight: Float = DEFAULT_CAPS_HEIGHT_PERCENT / 100f,
        val capsSwipe: Boolean = DEFAULT_CAPS_SWIPE,
        val learnedWords: Boolean = true,
    )

    /** The four configs and the blend a decoder is built from; equal when the decoders behave the same. */
    data class Configs(
        val kushler: KushlerConfig,
        val location: LocationConfig,
        val decoder: DecoderConfig,
        val preprocessor: PreprocessorConfig,
        /** [HybridScorer]'s inflection-scorer share; the location scorer gets the rest. */
        val kushlerWeight: Float,
    )

    /** A built decoder with its scorers, so the app can score every swipe with all three and publish them. */
    class Built(val configs: Configs) {
        val kushler = KushlerScorer(configs.kushler)
        val location = LocationScorer(configs.location)
        val hybrid = HybridScorer(kushler, location, kushlerWeight = configs.kushlerWeight, locationWeight = 1f - configs.kushlerWeight)
        /** The active scorer first, then the two it blends, the order the app publishes them in. */
        val scorers: List<Scorer> = listOf(hybrid, kushler, location)
        val decoder = GestureDecoder(hybrid, configs.decoder, GesturePreprocessor(configs.preprocessor))
    }

    /** The configs the device builds its decoder from for [settings]. */
    fun configs(settings: Settings = Settings()): Configs = Configs(
        kushler = KushlerConfig(slowEmphasis = settings.slowdown, matchRelaxPerKeyPerSecond = settings.cornerMiss,
            speedFromKeysPerSecond = settings.fastFrom),
        location = LocationConfig(),
        // (the learned-word boost and "suggest learned words" are applied at scoring, like the other settings here)
        decoder = DecoderConfig(capsExcursions = settings.capsSwipe, frequencyEmphasisPerKeyPerSecond = settings.fastCommon,
            frequencyEmphasisFromKeysPerSecond = settings.fastFrom, learnedBoost = settings.historyBoost, learnedWords = settings.learnedWords,
            apostropheSkipPenalty = settings.apostropheSkip),
        preprocessor = PreprocessorConfig(excursionMinHeightKeyHeights = settings.capsHeight, turnConfidenceScale = settings.turn,
            stopDtFactor = STOP_DT_FACTOR),
        kushlerWeight = settings.kushler,
    )

    /** The device's decoder for [settings]. */
    fun build(settings: Settings = Settings()): Built = Built(configs(settings))

    /** A decoder from [configs]: the device's, or the replay's with some of them changed. */
    fun build(configs: Configs): Built = Built(configs)
}

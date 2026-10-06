// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.SharedPreferences
import android.os.SystemClock
import helium314.keyboard.gesture.DecoderConfig
import helium314.keyboard.gesture.GestureDecoder
import helium314.keyboard.gesture.GesturePoint
import helium314.keyboard.gesture.GesturePreprocessor
import helium314.keyboard.gesture.HybridScorer
import helium314.keyboard.gesture.KeyInfo
import helium314.keyboard.gesture.KeyboardGeometry
import helium314.keyboard.gesture.KushlerConfig
import helium314.keyboard.gesture.KushlerScorer
import helium314.keyboard.gesture.LocationScorer
import helium314.keyboard.gesture.PreprocessorConfig
import helium314.keyboard.gesture.Scorer
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SuggestionResults
import helium314.keyboard.latin.utils.prefs
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Bridge between HeliBoard's batch-input pipeline and the in-tree gesture decoder
 * (:gesture module), which decodes every swipe.
 *
 * Runs on the InputLogicHandler non-UI thread (same thread the native decoder is
 * queried on), so no extra threading is needed — but decode latency is logged so
 * it can be profiled on device.
 *
 * Every decode scores with ALL THREE scorers (hybrid, kushler, location; the shared pipeline runs once; scoring
 * is the cheap stage). The active scorer's results drive the keyboard; the per-scorer
 * top-4 lists are published as a [LastDecodeRecord] for the Swipe Trainer.
 */
object OwnGestureDecoder {
    private const val TAG = "OwnGestureDecoder"
    private const val MAX_RESULTS = 10 // at least; more when the user asks for more suggestions (up to 40)

    /** The user's inflection weights and scorer blend, read from the (per keyboard) preferences on every swipe. */
    class Tuning(val turn: Float, val slowdown: Float, val kushler: Float, val historyBoost: Int,
                 val fastCommon: Float = Defaults.PREF_GESTURE_FAST_COMMON_WORDS, val cornerMiss: Float = Defaults.PREF_GESTURE_CORNER_MISS,
                 /** key widths per second above which a swipe counts as fast (the two fast-swipe settings start there) */
                 val fastFrom: Float = Defaults.PREF_GESTURE_FAST_SPEED) {
        /** One decimal each (two for the fast-swipe pair); the label the statistics are kept under. The fast-swipe pair
         *  is only in it when not at its default, so the results counted before it existed stay under their label. */
        val key: String = String.format(Locale.ROOT, "T%.1f S%.1f K%.1f H%d", turn, slowdown, kushler, historyBoost) +
            (if (round2(fastCommon) != Defaults.PREF_GESTURE_FAST_COMMON_WORDS) String.format(Locale.ROOT, " F%.2f", fastCommon) else "") +
            (if (round2(cornerMiss) != Defaults.PREF_GESTURE_CORNER_MISS) String.format(Locale.ROOT, " C%.2f", cornerMiss) else "") +
            (if (Math.round(fastFrom * 10) / 10f != Defaults.PREF_GESTURE_FAST_SPEED) String.format(Locale.ROOT, " V%.1f", fastFrom) else "")

        /** The tuning as the sliders show it: each value 0 to 1 over its slider's range ([RANGES] order). */
        val shown: List<Float> get() = listOf(turn, slowdown, kushler, historyBoost.toFloat(), fastCommon, cornerMiss, fastFrom)
            .zip(RANGES) { v, top -> v / top }
        override fun equals(other: Any?) = other is Tuning && other.key == key
        override fun hashCode() = key.hashCode()

        companion object {
            /** Each slider's top (its bottom is 0), in slider order: turns, slowdowns, blend, learned-word boost, fast
             *  swipes and common words, fast swipes and corners, fast swipe threshold (key widths per second, the 7th
             *  parameter). Shown 0 to 1 over these. */
            val RANGES = listOf(1.5f, 1f, 1f, 128f, 0.2f, 0.2f, 40f)

            val DEFAULT = Tuning(Defaults.PREF_GESTURE_TURN_WEIGHT,
                Defaults.PREF_GESTURE_SLOWDOWN_WEIGHT, Defaults.PREF_GESTURE_KUSHLER_WEIGHT, Defaults.PREF_GESTURE_HISTORY_BOOST)

            fun read(prefs: SharedPreferences) = Tuning(
                prefs.getFloat(Settings.PREF_GESTURE_TURN_WEIGHT, Defaults.PREF_GESTURE_TURN_WEIGHT),
                prefs.getFloat(Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Defaults.PREF_GESTURE_SLOWDOWN_WEIGHT),
                prefs.getFloat(Settings.PREF_GESTURE_KUSHLER_WEIGHT, Defaults.PREF_GESTURE_KUSHLER_WEIGHT),
                prefs.getInt(Settings.PREF_GESTURE_HISTORY_BOOST, Defaults.PREF_GESTURE_HISTORY_BOOST),
                prefs.getFloat(Settings.PREF_GESTURE_FAST_COMMON_WORDS, Defaults.PREF_GESTURE_FAST_COMMON_WORDS),
                prefs.getFloat(Settings.PREF_GESTURE_CORNER_MISS, Defaults.PREF_GESTURE_CORNER_MISS),
                prefs.getFloat(Settings.PREF_GESTURE_FAST_SPEED, Defaults.PREF_GESTURE_FAST_SPEED),
            )

            /** The tuning a [key] stands for, or null if it isn't one; keys from before pauses were dropped carry a P, ignored. */
            fun parse(key: String): Tuning? {
                val m = Regex("T([\\d.]+)(?: P[\\d.]+)? S([\\d.]+) K([\\d.]+) H(\\d+)(?: F([\\d.]+))?(?: C([\\d.]+))?(?: V([\\d.]+))?").matchEntire(key) ?: return null
                val v = m.groupValues.drop(1).take(4).map { it.toFloatOrNull() ?: return null }
                val f = m.groupValues[5].toFloatOrNull() ?: Defaults.PREF_GESTURE_FAST_COMMON_WORDS
                val c = m.groupValues[6].toFloatOrNull() ?: Defaults.PREF_GESTURE_CORNER_MISS
                val speed = m.groupValues[7].toFloatOrNull() ?: Defaults.PREF_GESTURE_FAST_SPEED
                return Tuning(v[0], v[1], v[2], v[3].toInt(), f, c, speed)
            }

            fun write(prefs: SharedPreferences, tuning: Tuning) = prefs.edit()
                .putFloat(Settings.PREF_GESTURE_TURN_WEIGHT, tuning.turn)
                .putFloat(Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, tuning.slowdown)
                .putFloat(Settings.PREF_GESTURE_KUSHLER_WEIGHT, tuning.kushler)
                .putInt(Settings.PREF_GESTURE_HISTORY_BOOST, tuning.historyBoost)
                .putFloat(Settings.PREF_GESTURE_FAST_COMMON_WORDS, tuning.fastCommon)
                .putFloat(Settings.PREF_GESTURE_CORNER_MISS, tuning.cornerMiss)
                .putFloat(Settings.PREF_GESTURE_FAST_SPEED, tuning.fastFrom)
                .apply()
        }
    }

    private fun round2(v: Float) = Math.round(v * 100) / 100f

    /** The tuning the last swipe was decoded with (the statistics are kept under its key). */
    @Volatile var currentTuning: Tuning = Tuning.DEFAULT
        private set
    /** How long the last decode took on this phone, for the swipe statistics. */
    @Volatile var lastDecodeMs: Long = 0
        private set
    /** The last swipe found no vocabulary at all (still building after a start): a dead swipe, logged as such. */
    @Volatile var lastVocabularyMissing = false
        private set
    /** The last swipe's speed as the decoder measured it (key widths per second), for the swipe results log. */
    @Volatile var lastSpeedKeysPerSecond: Float = 0f
        private set

    private var scorers: List<Scorer> = listOf(HybridScorer(), KushlerScorer(), LocationScorer())
    // the caps settings and the tuning are part of the immutable configs, so the decoder is rebuilt when they change
    private var decoderCapsHeight = Float.NaN
    private var decoderCapsSwipe = true
    private var decoderTuning: Tuning? = null
    private var decoder = GestureDecoder(HybridScorer()) // ctor scorer unused by decodeWithScorers

    @Synchronized
    private fun decoderFor(capsHeight: Float, capsSwipe: Boolean, tuning: Tuning): GestureDecoder {
        if (capsHeight != decoderCapsHeight || capsSwipe != decoderCapsSwipe || tuning != decoderTuning) {
            val kushler = KushlerScorer(KushlerConfig(slowEmphasis = tuning.slowdown, matchRelaxPerKeyPerSecond = tuning.cornerMiss,
                speedFromKeysPerSecond = tuning.fastFrom))
            val location = LocationScorer()
            val hybrid = HybridScorer(kushler, location, kushlerWeight = tuning.kushler, locationWeight = 1f - tuning.kushler)
            scorers = listOf(hybrid, kushler, location)
            decoder = GestureDecoder(hybrid, DecoderConfig(capsExcursions = capsSwipe, frequencyEmphasisPerKeyPerSecond = tuning.fastCommon,
                frequencyEmphasisFromKeysPerSecond = tuning.fastFrom),
                preprocessor = GesturePreprocessor(PreprocessorConfig(excursionMinHeightKeyHeights = capsHeight,
                    turnConfidenceScale = tuning.turn, stopDtFactor = 2.5f)))
            decoderCapsHeight = capsHeight
            decoderCapsSwipe = capsSwipe
            decoderTuning = tuning
        }
        return decoder
    }

    // keyboard geometry cache — keyboards change with layout/rotation, so cache per instance
    private var cachedKeyboardRef: WeakReference<Keyboard>? = null
    private var cachedGeometry: KeyboardGeometry? = null
    private var cachedApostropheViaPeriod = true

    // phony source dict per locale so results look main-dict-sourced like native gesture results
    private var cachedSourceDict: DecoderSourceDictionary? = null

    /**
     * Decode the batch-input pointers into a [SuggestionResults] shaped like the ones
     * the native decoder produces via DictionaryFacilitator.getSuggestionResults.
     * Returns empty results when the vocabulary isn't built yet (never blocks).
     */
    fun getSuggestionResults(
        composedData: ComposedData,
        keyboard: Keyboard,
        locales: List<Locale>,
        activeScorerPref: String?,
        capsHeight: Float,
        wanted: Int = 0,
    ): SuggestionResults {
        val results = SuggestionResults(SuggestedWords.MAX_SUGGESTIONS, false, false)
        // this swipe's own facts from here on: an early return must not leave the last swipe's behind (review 2026-10-06:
        // a stale "no vocabulary" mislabeled the next dead swipe in the results log)
        lastVocabularyMissing = false
        lastDecodeMs = 0
        lastSpeedKeysPerSecond = 0f
        val locale = locales.first()
        val points = adaptPointers(composedData)
        if (points.size < 2) return results
        val prefs = Settings.getCurrentContext()?.prefs()
        val apostropheViaPeriod = prefs?.getBoolean(Settings.PREF_GESTURE_APOSTROPHE_VIA_PERIOD, Defaults.PREF_GESTURE_APOSTROPHE_VIA_PERIOD)
            ?: Defaults.PREF_GESTURE_APOSTROPHE_VIA_PERIOD
        val capsSwipe = prefs?.getBoolean(Settings.PREF_GESTURE_CAPS_SWIPE, Defaults.PREF_GESTURE_CAPS_SWIPE) ?: Defaults.PREF_GESTURE_CAPS_SWIPE
        val geometry = geometryFor(keyboard, apostropheViaPeriod) ?: return results
        val tuning = prefs?.let { Tuning.read(it) } ?: Tuning.DEFAULT
        currentTuning = tuning
        GestureDecoderVocabulary.historyBoost = tuning.historyBoost
        GestureDecoderVocabulary.includeLearned = Settings.getValues()?.mUsePersonalizedDicts != false
        val context = Settings.getCurrentContext()
        val specs = if (context != null) GestureDecoderVocabulary.specsFor(context, locales)
                    else locales.map { GestureDecoderVocabulary.LocaleSpec(it, 1f) }
        val vocabulary = GestureDecoderVocabulary.getOrBuildAsync(specs)
        lastVocabularyMissing = vocabulary == null
        if (vocabulary == null) {
            Log.d(TAG, "vocabulary for $locales not ready yet, no gesture results")
            return results
        }

        val start = SystemClock.elapsedRealtime()
        val decoder = decoderFor(capsHeight, capsSwipe, tuning)
        val all = decoder.decodeWithScorers(points, geometry, vocabulary, scorers, wanted.coerceIn(MAX_RESULTS, 40))
        val elapsed = SystemClock.elapsedRealtime() - start
        lastSpeedKeysPerSecond = decoder.lastSpeedKeysPerSecond
        lastDecodeMs = elapsed

        val activeName = if (activeScorerPref != null && all.containsKey(activeScorerPref)) activeScorerPref
                         else Defaults.PREF_GESTURE_DECODER_SCORER
        val active = all[activeName] ?: all.values.firstOrNull() ?: emptyList()
        Log.d(TAG, "decoded ${points.size} points -> ${active.size} words in $elapsed ms " +
                "(scorer=$activeName, top=${active.firstOrNull()?.word})")

        LastDecodeHolder.publish(points, all.mapValues { it.value.take(4) }, activeName, locale.toLanguageTag())

        val sourceDict = sourceDictFor(locale)
        for (scored in active) {
            results.add(SuggestedWordInfo(scored.word, "", toNativeScore(scored.score),
                SuggestedWordInfo.KIND_CORRECTION, sourceDict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
        }
        return results
    }

    /**
     * Decoder scores are float, lower-better; native dictionary scores are int,
     * higher-better. Monotone map into a positive range comparable to native scores
     * (well above Suggest's SUPPRESS_SUGGEST_THRESHOLD, ratios meaningful for
     * replaceSingleLetterFirstSuggestion's 0.94 comparison).
     */
    private fun toNativeScore(score: Float): Int =
        (1_000_000.0 / (1.0 + score.toDouble())).toInt().coerceAtLeast(1)

    private fun adaptPointers(composedData: ComposedData): List<GesturePoint> {
        val pointers = composedData.mInputPointers
        val size = pointers.pointerSize
        if (size <= 0) return emptyList()
        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates
        val times = pointers.times
        val out = ArrayList<GesturePoint>(size)
        for (i in 0 until size) {
            out.add(GesturePoint(xs[i].toFloat(), ys[i].toFloat(), times[i].toLong()))
        }
        return out
    }

    @Synchronized
    private fun geometryFor(keyboard: Keyboard, apostropheViaPeriod: Boolean): KeyboardGeometry? {
        if (cachedKeyboardRef?.get() === keyboard && cachedApostropheViaPeriod == apostropheViaPeriod) return cachedGeometry
        val seen = HashSet<Char>()
        val keys = ArrayList<KeyInfo>()
        for (key in keyboard.sortedKeys) {
            val code = key.code
            // letter keys + the period key (the apostrophe waypoint for contraction
            // gestures, OG-Swype style); absent period key just excludes such words
            val isPeriod = code == KeyboardGeometry.PERIOD_KEY_CHAR.code
            if (code <= 0 || (!isPeriod && !Character.isLetter(code))) continue
            val c = Character.toLowerCase(code).toChar() // BMP letters only on latin layouts
            if (!seen.add(c)) continue
            keys.add(KeyInfo(c,
                key.x + key.width / 2f, key.y + key.height / 2f,
                key.width.toFloat(), key.height.toFloat()))
        }
        if (keys.size < 5) return null // not a letter keyboard
        val geometry = KeyboardGeometry(keys, apostropheViaPeriod)
        cachedKeyboardRef = WeakReference(keyboard)
        cachedGeometry = geometry
        cachedApostropheViaPeriod = apostropheViaPeriod
        return geometry
    }

    @Synchronized
    private fun sourceDictFor(locale: Locale): Dictionary {
        cachedSourceDict?.let { if (it.mLocale == locale) return it }
        return DecoderSourceDictionary(locale).also { cachedSourceDict = it }
    }

    /** Placeholder dictionary so results carry TYPE_MAIN + locale like native gesture results. */
    private class DecoderSourceDictionary(locale: Locale) : Dictionary(TYPE_MAIN, locale) {
        override fun getSuggestions(
            composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int,
            weightForLocale: Float, inOutWeightOfLangModelVsSpatialModel: FloatArray,
        ): ArrayList<SuggestedWordInfo>? = null

        override fun isInDictionary(word: String): Boolean = false
    }
}

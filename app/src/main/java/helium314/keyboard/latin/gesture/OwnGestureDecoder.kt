// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.SharedPreferences
import android.os.SystemClock
import helium314.keyboard.gesture.GestureDecoder
import helium314.keyboard.gesture.GesturePoint
import helium314.keyboard.gesture.GesturePreprocessor
import helium314.keyboard.gesture.HybridScorer
import helium314.keyboard.gesture.KeyInfo
import helium314.keyboard.gesture.KeyboardGeometry
import helium314.keyboard.gesture.KushlerScorer
import helium314.keyboard.gesture.PreprocessorConfig
import helium314.keyboard.gesture.Scorer
import helium314.keyboard.gesture.Shark2Scorer
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SuggestionResults
import helium314.keyboard.latin.utils.prefs
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Bridge between HeliBoard's batch-input pipeline and the in-tree gesture decoder
 * (:gesture module). Only reachable when BuildConfig.USE_OWN_GESTURE_DECODER (lab
 * flavor); in the normal flavor every call site is behind the constant-false flag.
 *
 * Runs on the InputLogicHandler non-UI thread (same thread the native decoder is
 * queried on), so no extra threading is needed — but decode latency is logged so
 * it can be profiled on device.
 *
 * Every decode scores with ALL THREE scorers (the shared pipeline runs once; scoring
 * is the cheap stage). The active scorer's results drive the keyboard; the per-scorer
 * top-4 lists are published as a [LastDecodeRecord] for the Swipe Trainer.
 */
object OwnGestureDecoder {
    private const val TAG = "OwnGestureDecoder"
    private const val MAX_RESULTS = 10

    /** The user's inflection weights and scorer blend, read from the (per keyboard) preferences on every swipe. */
    class Tuning(val turn: Float, val pause: Float, val slowdown: Float, val kushler: Float, val historyBoost: Int) {
        /** One decimal each; the label the statistics are kept under. */
        val key: String = String.format(Locale.ROOT, "T%.1f P%.1f S%.1f K%.1f H%d", turn, pause, slowdown, kushler, historyBoost)
        override fun equals(other: Any?) = other is Tuning && other.key == key
        override fun hashCode() = key.hashCode()

        companion object {
            val DEFAULT = Tuning(Defaults.PREF_GESTURE_TURN_WEIGHT, Defaults.PREF_GESTURE_PAUSE_WEIGHT,
                Defaults.PREF_GESTURE_SLOWDOWN_WEIGHT, Defaults.PREF_GESTURE_KUSHLER_WEIGHT, Defaults.PREF_GESTURE_HISTORY_BOOST)

            fun read(prefs: SharedPreferences) = Tuning(
                prefs.getFloat(Settings.PREF_GESTURE_TURN_WEIGHT, Defaults.PREF_GESTURE_TURN_WEIGHT),
                prefs.getFloat(Settings.PREF_GESTURE_PAUSE_WEIGHT, Defaults.PREF_GESTURE_PAUSE_WEIGHT),
                prefs.getFloat(Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Defaults.PREF_GESTURE_SLOWDOWN_WEIGHT),
                prefs.getFloat(Settings.PREF_GESTURE_KUSHLER_WEIGHT, Defaults.PREF_GESTURE_KUSHLER_WEIGHT),
                prefs.getInt(Settings.PREF_GESTURE_HISTORY_BOOST, Defaults.PREF_GESTURE_HISTORY_BOOST),
            )

            /** The tuning a [key] stands for, or null if it isn't one. */
            fun parse(key: String): Tuning? {
                val m = Regex("T([\\d.]+) P([\\d.]+) S([\\d.]+) K([\\d.]+) H(\\d+)").matchEntire(key) ?: return null
                val v = m.groupValues.drop(1).map { it.toFloatOrNull() ?: return null }
                return Tuning(v[0], v[1], v[2], v[3], v[4].toInt())
            }

            fun write(prefs: SharedPreferences, tuning: Tuning) = prefs.edit()
                .putFloat(Settings.PREF_GESTURE_TURN_WEIGHT, tuning.turn)
                .putFloat(Settings.PREF_GESTURE_PAUSE_WEIGHT, tuning.pause)
                .putFloat(Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, tuning.slowdown)
                .putFloat(Settings.PREF_GESTURE_KUSHLER_WEIGHT, tuning.kushler)
                .putInt(Settings.PREF_GESTURE_HISTORY_BOOST, tuning.historyBoost)
                .apply()
        }
    }

    /** The tuning the last swipe was decoded with (the statistics are kept under its key). */
    @Volatile var currentTuning: Tuning = Tuning.DEFAULT
        private set

    private var scorers: List<Scorer> = listOf(HybridScorer(), KushlerScorer(), Shark2Scorer())
    // the caps height and the tuning are part of the immutable configs, so the decoder is rebuilt when they change
    private var decoderCapsHeight = Float.NaN
    private var decoderTuning: Tuning? = null
    private var decoder = GestureDecoder(HybridScorer()) // ctor scorer unused by decodeWithScorers

    @Synchronized
    private fun decoderFor(capsHeight: Float, tuning: Tuning): GestureDecoder {
        if (capsHeight != decoderCapsHeight || tuning != decoderTuning) {
            val hybrid = HybridScorer(kushlerWeight = tuning.kushler, shark2Weight = 1f - tuning.kushler)
            scorers = listOf(hybrid, KushlerScorer(), Shark2Scorer())
            decoder = GestureDecoder(hybrid,
                preprocessor = GesturePreprocessor(PreprocessorConfig(excursionMinHeightKeyHeights = capsHeight,
                    turnConfidenceScale = tuning.turn, pauseConfidence = tuning.pause, pauseDtFactor = 2.5f,
                    slowdownConfidence = tuning.slowdown)))
            decoderCapsHeight = capsHeight
            decoderTuning = tuning
        }
        return decoder
    }

    // keyboard geometry cache — keyboards change with layout/rotation, so cache per instance
    private var cachedKeyboardRef: WeakReference<Keyboard>? = null
    private var cachedGeometry: KeyboardGeometry? = null

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
    ): SuggestionResults {
        val results = SuggestionResults(SuggestedWords.MAX_SUGGESTIONS, false, false)
        val locale = locales.first()
        val points = adaptPointers(composedData)
        if (points.size < 2) return results
        val geometry = geometryFor(keyboard) ?: return results
        val prefs = Settings.getCurrentContext()?.prefs()
        val tuning = prefs?.let { Tuning.read(it) } ?: Tuning.DEFAULT
        currentTuning = tuning
        GestureDecoderVocabulary.historyBoost = tuning.historyBoost
        val specs = locales.map {
            GestureDecoderVocabulary.LocaleSpec(it,
                prefs?.let { p -> LanguagePriority.factor(p, it) } ?: 1f,
                prefs?.let { p -> LanguagePriority.sharesUserHistory(p, it) } ?: false)
        }
        val vocabulary = GestureDecoderVocabulary.getOrBuildAsync(specs)
        if (vocabulary == null) {
            Log.d(TAG, "vocabulary for $locales not ready yet, no gesture results")
            return results
        }

        val start = SystemClock.elapsedRealtime()
        val all = decoderFor(capsHeight, tuning).decodeWithScorers(points, geometry, vocabulary, scorers, MAX_RESULTS)
        val elapsed = SystemClock.elapsedRealtime() - start

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
    private fun geometryFor(keyboard: Keyboard): KeyboardGeometry? {
        if (cachedKeyboardRef?.get() === keyboard) return cachedGeometry
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
        val geometry = KeyboardGeometry(keys)
        cachedKeyboardRef = WeakReference(keyboard)
        cachedGeometry = geometry
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

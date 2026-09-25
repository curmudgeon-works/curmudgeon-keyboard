// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.SharedPreferences
import java.util.Locale

/**
 * Per-language settings for multilingual typing, stored by language tag independent of subtypes:
 * - priority (Low / Medium / High): a fixed factor on that language's suggestion scores, on top of the
 *   automatic confidence of [helium314.keyboard.latin.DictionaryFacilitatorImpl.DictionaryGroup]
 * - share user history: the words learned in that language count for every language, at full weight
 */
object LanguagePriority {
    const val LOW = 1
    const val MEDIUM = 2
    const val HIGH = 3
    const val DEFAULT = HIGH // everything at full weight until the user says otherwise

    private const val PREF_PRIORITY_PREFIX = "language_priority_"
    private const val PREF_SHARE_HISTORY_PREFIX = "share_user_history_"
    const val PREF_ADDED_PREFIX = "language_added_"

    /** The preference keys holding a language's priority, share switch and adding time (global keys, one per language). */
    fun keys(locale: Locale): List<String> =
        listOf(PREF_PRIORITY_PREFIX + locale.toLanguageTag(), PREF_SHARE_HISTORY_PREFIX + locale.toLanguageTag(),
            PREF_ADDED_PREFIX + locale.toLanguageTag())

    fun get(prefs: SharedPreferences, locale: Locale): Int =
        prefs.getInt(PREF_PRIORITY_PREFIX + locale.toLanguageTag(), DEFAULT).coerceIn(LOW, HIGH)

    fun set(prefs: SharedPreferences, locale: Locale, priority: Int) =
        prefs.edit().putInt(PREF_PRIORITY_PREFIX + locale.toLanguageTag(), priority.coerceIn(LOW, HIGH)).apply()

    /** Score factor for a priority, the same for tap and swipe suggestions: high 1, medium 0.85, low 0.5.
     *  (The swipe vocabulary first puts every language on the main language's frequency scale by rank, see
     *  GestureDecoderVocabulary; tap suggestions use the dictionaries' own scales.) */
    fun factor(priority: Int): Float = when (priority) {
        LOW -> 0.5f
        MEDIUM -> 0.85f
        else -> 1f
    }

    fun factor(prefs: SharedPreferences, locale: Locale): Float = factor(get(prefs, locale))

    fun sharesUserHistory(prefs: SharedPreferences, locale: Locale): Boolean =
        prefs.getBoolean(PREF_SHARE_HISTORY_PREFIX + locale.toLanguageTag(), false)

    fun setSharesUserHistory(prefs: SharedPreferences, locale: Locale, shared: Boolean) =
        prefs.edit().putBoolean(PREF_SHARE_HISTORY_PREFIX + locale.toLanguageTag(), shared).apply()

    /** When the language was last added to a keyboard (0 for languages added before this was recorded). */
    fun added(prefs: SharedPreferences, locale: Locale): Long =
        prefs.getLong(PREF_ADDED_PREFIX + locale.toLanguageTag(), 0L)

    fun markAdded(prefs: SharedPreferences, locale: Locale) =
        prefs.edit().putLong(PREF_ADDED_PREFIX + locale.toLanguageTag(), System.currentTimeMillis()).apply()

    /** Short label for settings rows. */
    fun label(priority: Int): String = when (priority) {
        LOW -> "L"
        MEDIUM -> "M"
        else -> "H"
    }
}

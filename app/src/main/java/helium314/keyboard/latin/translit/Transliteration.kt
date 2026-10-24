// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translit

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A language whose words can be suggested in another script ([TranslitTable]): [latinTag] is the keyboard language
 *  typed in, [name] the other language's own name for the setting ("Suggest in हिंदी"), [asset] its table, [script]
 *  the other script (ScriptUtils.SCRIPT_*: its words in the text are words the cursor can pick up again). */
class TranslitSpec(val latinTag: String, val name: String, val asset: String, val script: String)

/** What a keyboard does with Suggest in …: [locale]'s words (one of its languages, with the table [spec]) in [mode]
 *  ([Transliteration.SOME] or [Transliteration.ALL]). */
class TranslitChoice(val spec: TranslitSpec, val locale: Locale, val mode: Int)

/**
 * Suggest in हिंदी: the words of a Latin-script language (Hinglish) suggested in the script of its language (Hindi in
 * Devanagari), looked up in a table by the Latin word already chosen. Which languages have one is [specs] (data: add a
 * table and a line to have another one).
 */
object Transliteration {
    /** Nothing in the other script. */
    const val OFF = 0
    /** The other script's word one tap away, on a tint; space types the Latin word. */
    const val SOME = 1
    /** Space types the other script's word, the Latin word is one tap away on a tint. */
    const val ALL = 2

    /** The tables there are. */
    val specs = listOf(
        TranslitSpec("hi-Latn", "हिंदी", "translit/hi-Latn.bin", ScriptUtils.SCRIPT_DEVANAGARI),
    )

    fun specFor(locale: Locale): TranslitSpec? = specFor(locale.toLanguageTag())

    fun specFor(languageTag: String): TranslitSpec? = specs.firstOrNull { it.latinTag == languageTag }

    /** The keyboard's setting for [locale] (its own with separate settings, as a language's priority is). */
    fun key(locale: Locale) = Settings.PREF_SUGGEST_IN_SCRIPT_PREFIX + locale.toLanguageTag()

    fun isKey(key: String) = key.startsWith(Settings.PREF_SUGGEST_IN_SCRIPT_PREFIX)

    /** [OFF], [SOME] or [ALL]; the default ([Defaults.PREF_SUGGEST_IN_SCRIPT]) when nothing is picked. */
    fun get(prefs: SharedPreferences, locale: Locale): Int =
        prefs.getInt(key(locale), Defaults.PREF_SUGGEST_IN_SCRIPT).coerceIn(OFF, ALL)

    fun set(prefs: SharedPreferences, locale: Locale, mode: Int) =
        prefs.edit().putInt(key(locale), mode.coerceIn(OFF, ALL)).apply()

    /** What a keyboard with the languages [locales] does: the first of them with a table and the setting not [OFF]
     *  (its table starts being read); null when none (nothing changes then). */
    fun choice(context: Context, prefs: SharedPreferences, locales: List<Locale>): TranslitChoice? {
        for (locale in locales) {
            val spec = specFor(locale) ?: continue
            val mode = get(prefs, locale)
            if (mode == OFF) continue
            prepare(context, spec)
            return TranslitChoice(spec, locale, mode)
        }
        return null
    }

    // the tables read so far, by asset: read once, kept (mapped from a file, see TranslitTable.load)
    private val tables = ConcurrentHashMap<String, TranslitTable>()
    private val loading = Collections.synchronizedSet(HashSet<String>())

    /** [spec]'s table if it is read; null while it is still being read ([prepare]). */
    fun table(spec: TranslitSpec): TranslitTable? = tables[spec.asset]

    /** Starts reading [spec]'s table in the background, unless it is read or being read. Only called while a keyboard
     *  has the setting on: with it off, nothing is read. */
    fun prepare(context: Context, spec: TranslitSpec) {
        if (tables.containsKey(spec.asset) || !loading.add(spec.asset)) return
        val app = context.applicationContext ?: context
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { load(app, spec) }
    }

    /** [spec]'s table, read now if it isn't yet (slow: not on the main thread). */
    fun load(context: Context, spec: TranslitSpec): TranslitTable? {
        tables[spec.asset]?.let { return it }
        val table = TranslitTable.load(context, spec.asset)
        if (table != null) tables[spec.asset] = table
        loading.remove(spec.asset)
        return table
    }
}

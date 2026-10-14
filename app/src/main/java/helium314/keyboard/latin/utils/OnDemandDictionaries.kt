// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.utils.ScriptUtils.script
import org.json.JSONArray
import java.util.Locale

/**
 * The dictionaries not built into the app that can be downloaded from curmudgeon.works (2026-10-10, owner: "Option 2 --
 * on demand one-tap download makes sense"): the browser downloads the file, opening it adds it (the .dict intent
 * filter, or Add dictionary from file). The list ships in assets/dictionaries_on_demand.json; the files and their
 * licences live in the curmudgeon-dictionaries releases, the page names them.
 */
object OnDemandDictionaries {
    const val PAGE = "https://curmudgeon.works/dictionaries"

    class Entry(val tag: String, val name: String, val bytes: Long, val licence: String)

    @Volatile private var entries: List<Entry>? = null

    fun all(context: Context): List<Entry> = entries ?: runCatching {
        val a = JSONArray(context.assets.open("dictionaries_on_demand.json").reader().readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Entry(it.getString("tag"), it.getString("name"), it.optLong("bytes"), it.optString("licence")) } }
    }.getOrDefault(emptyList()).also { entries = it }

    /** The download for [locale]: its own tag, else its language's ("et-EE" → et; the keyboard's Indonesian is "in"). */
    fun forLocale(context: Context, locale: Locale): Entry? {
        val all = all(context)
        val tag = locale.toLanguageTag()
        all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it }
        val language = when (locale.language) { "in" -> "id"; "iw" -> "he"; else -> locale.language }
        // (only a download made for the language as a whole, not another region's: en-CA is no English for en-IN; and in
        // the same script: Hindi's Devanagari list is no Hinglish one)
        val script = locale.script()
        return all.firstOrNull { it.tag.constructLocale().let { l -> l.language == language && l.country.isEmpty() && l.script() == script } }
    }

    /** The page, at [locale]'s entry. */
    fun pageFor(locale: Locale) = "$PAGE#${locale.toLanguageTag()}"
}

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
 * licenses live in the curmudgeon-dictionaries releases, the page names them.
 */
object OnDemandDictionaries {
    const val PAGE = "https://curmudgeon.works/dictionaries"

    class Entry(val tag: String, val name: String, val bytes: Long, val license: String)

    @Volatile private var entries: List<Entry>? = null

    fun all(context: Context): List<Entry> = entries ?: runCatching {
        val a = JSONArray(context.assets.open("dictionaries_on_demand.json").reader().readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Entry(it.getString("tag"), it.getString("name"), it.optLong("bytes"), it.optString("license")) } }
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

    /** Our dictionaries' own file type (dl.curmudgeon.works serves them with it; the app's .dict filter declares it). */
    const val MIME_TYPE = "application/x-curmudgeon-dict"

    /**
     * The file picker for a dictionary: in Downloads, showing ours (their own type, which a picker set to octet-stream
     * hid) and any other .dict. The keyboard has no internet permission (owner 2026-10-10: "Let's skip internet access"):
     * the browser downloads, this or Chrome's Downloads brings the file in.
     */
    fun pickerIntent(): android.content.Intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(android.content.Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", MIME_TYPE))
        .apply {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
        }
}

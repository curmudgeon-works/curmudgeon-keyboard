// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.io.File
import java.util.Locale

/**
 * Which main dictionary a language uses (2026-10-12, backlog "DICTIONARY CHOICE"), picked in the language's dictionary
 * dialog: the internal one, the added main dictionary ("main_user.dict"), or another added main-type file (a restore
 * keeps the device's old one aside as "main_prev.user.dict", "main_prev2.user.dict"…; any "<type>…user.dict").
 *
 * Stored per language, for all keyboards (owner: "A lang will always have a single dict across keyboards"): the
 * app-wide preference `dict_selected_<language tag>_<type>` (e.g. `dict_selected_hi_main`), holding the selected
 * file's name in the language's dictionary folder, or [INTERNAL]. The restore uses the same preference: on a clash it
 * keeps the device's dictionary aside under a "_prev" name and selects the backup's by writing its file name here
 * ([select]).
 *
 * With nothing selected, or the selected file gone: "main_user.dict" if there, else the internal one, never a "_prev"
 * file (before, the first file of a kind in the folder listing's order won); only with no internal dictionary for the
 * language and no "main_user.dict" the first of the other added main files by name.
 */
object DictionarySelection {
    const val PREF_PREFIX = "dict_selected_"
    const val INTERNAL = "internal"
    /** The added main dictionary's name, as NewDictionaryDialog writes it. */
    val DEFAULT_USER_MAIN = Dictionary.TYPE_MAIN + "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX

    fun prefKey(locale: Locale, type: String) = PREF_PREFIX + locale.toLanguageTag() + "_" + type

    private fun prefs(ctx: Context) = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    /** The stored choice: a file name, [INTERNAL], or null (none). */
    fun selected(ctx: Context, locale: Locale, type: String = Dictionary.TYPE_MAIN): String? =
        prefs(ctx).getString(prefKey(locale, type), null)

    /** [name]: a file name in the language's dictionary folder, or [INTERNAL]. */
    fun select(ctx: Context, locale: Locale, name: String, type: String = Dictionary.TYPE_MAIN) =
        prefs(ctx).edit { putString(prefKey(locale, type), name) }

    fun clear(ctx: Context, locale: Locale, type: String = Dictionary.TYPE_MAIN) =
        prefs(ctx).edit { remove(prefKey(locale, type)) }

    /** A dictionary file's kind by its name, as the folder names them: "main_user.dict", "main_prev.user.dict" and
     *  "main.dict" are "main". */
    fun typeOf(file: File): String = file.name.substringBefore("_").substringBefore(".")

    fun isAdded(file: File) = file.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)

    private fun isAddedMain(file: File) = isAdded(file) && typeOf(file) == Dictionary.TYPE_MAIN

    /**
     * The added main dictionary in use among [files] (the language's folder), or null for the internal one, by
     * [selection] (see the class). [hasInternal]: the language has an internal main dictionary.
     */
    fun mainAdded(files: Collection<File>, selection: String?, hasInternal: Boolean): File? {
        val added = files.filter { isAddedMain(it) }
        if (selection == INTERNAL && hasInternal) return null
        selection?.let { s -> added.firstOrNull { it.name == s }?.let { return it } }
        added.firstOrNull { it.name == DEFAULT_USER_MAIN }?.let { return it }
        return if (hasInternal) null else added.minByOrNull { it.name }
    }

    /** What the dialog marks as in use: the file's name, or [INTERNAL] (also when there is no main dictionary at all). */
    fun inUse(ctx: Context, locale: Locale, files: Collection<File>, hasInternal: Boolean): String =
        mainAdded(files, selected(ctx, locale), hasInternal)?.name ?: INTERNAL

    /** [files] without the added main dictionaries not in use ([main]: the one in use; null: the internal one). */
    fun withoutUnused(files: List<File>, main: File?): List<File> = files.filter { !isAddedMain(it) || it == main }
}

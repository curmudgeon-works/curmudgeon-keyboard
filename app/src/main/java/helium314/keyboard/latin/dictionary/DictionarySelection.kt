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
 * keeps the device's old one aside as "main_prev_user.dict", "main_prev2_user.dict"…; any "<type>_…user.dict").
 *
 * Stored per language, for all keyboards (owner: "A lang will always have a single dict across keyboards"): the
 * app-wide preference `dict_selected_<language tag>_<type>` (e.g. `dict_selected_hi_main`), holding the selected
 * file's name in the language's dictionary folder, or [INTERNAL]. The restore uses the same preference
 * (RestoreDictionaries): on a clash it renames the device's "<type>_user.dict" to the next free "_prev" name, brings
 * the backup's in as "<type>_user.dict" and selects it; on a device copy with the backup's content it renames that copy
 * to the backup's name and selects it. The dialog won't delete the version in use while a keyboard has the language;
 * a deleted selected file's selection is cleared.
 *
 * With nothing selected, or the selected file gone: "main_user.dict" if there, else the internal one, never a "_prev"
 * file (before, the first file of a kind in the folder listing's order won); only with no internal dictionary for the
 * language and no "main_user.dict" the first of the other added main files by name.
 *
 * Every other kind (in practice "emoji") the same way, by its own preference (`dict_selected_hi_emoji`), its default
 * "<kind>_user.dict" and its internal one if there is any (owner 2026-10-12, review M3: "empji dict restore behavior
 * should match main"; before, every added file of another kind was tried, the first in the listing's order winning).
 */
object DictionarySelection {
    const val PREF_PREFIX = "dict_selected_"
    const val INTERNAL = "internal"
    /** The added main dictionary's name, as NewDictionaryDialog writes it. */
    val DEFAULT_USER_MAIN = defaultName(Dictionary.TYPE_MAIN)

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

    /** A dictionary file's kind by its name, as the folder names them: "main_user.dict", "main_prev_user.dict" and
     *  "main.dict" are "main". */
    fun typeOf(file: File): String = file.name.substringBefore("_").substringBefore(".")

    fun isAdded(file: File) = file.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)

    private fun isAdded(file: File, type: String) = isAdded(file) && typeOf(file) == type

    /** The added dictionary's name used when nothing of [type] is selected: "main_user.dict", "emoji_user.dict". */
    fun defaultName(type: String) = type + "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX

    /**
     * The added dictionary of [type] in use among [files] (the language's folder), or null for the internal one (or
     * none), by [selection] (see the class). [hasInternal]: the language has an internal dictionary of [type].
     */
    fun added(files: Collection<File>, type: String, selection: String?, hasInternal: Boolean): File? {
        val added = files.filter { isAdded(it, type) }
        if (selection == INTERNAL && hasInternal) return null
        selection?.let { s -> added.firstOrNull { it.name == s }?.let { return it } }
        val defaultFile = defaultName(type)
        added.firstOrNull { it.name == defaultFile }?.let { return it }
        return if (hasInternal) null else added.minByOrNull { it.name }
    }

    /** The added main dictionary in use ([added] of "main"). */
    fun mainAdded(files: Collection<File>, selection: String?, hasInternal: Boolean): File? =
        added(files, Dictionary.TYPE_MAIN, selection, hasInternal)

    /** What the dialog marks as in use for [type]: the file's name, or [INTERNAL] (also when there is no dictionary
     *  of [type] at all). */
    fun inUse(ctx: Context, locale: Locale, files: Collection<File>, hasInternal: Boolean, type: String = Dictionary.TYPE_MAIN): String =
        added(files, type, selected(ctx, locale, type), hasInternal)?.name ?: INTERNAL

    /** [files] without the added dictionaries not in use, of every kind: per kind [added], by [selection] (the stored
     *  choice of a kind) and [hasInternal] (the language has an internal one of the kind). */
    fun withoutUnused(files: List<File>, selection: (String) -> String?, hasInternal: (String) -> Boolean): List<File> {
        val inUse = files.filter { isAdded(it) }.map { typeOf(it) }.distinct()
            .associateWith { added(files, it, selection(it), hasInternal(it)) }
        return files.filter { !isAdded(it) || inUse[typeOf(it)] == it }
    }
}

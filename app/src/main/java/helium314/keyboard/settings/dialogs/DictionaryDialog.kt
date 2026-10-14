// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionarySelection
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.createDictionaryTextAnnotated
import helium314.keyboard.latin.utils.DeleteButton
import helium314.keyboard.latin.utils.ExpandButton
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dictionaryFilePicker
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.getUserAndInternalDictionaries
import java.io.File
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import helium314.keyboard.dictionarypack.DictionaryPackConstants

@Composable
fun DictionaryDialog(
    onDismissRequest: () -> Unit,
    locale: Locale,
    /** The keyboard whose language row opened it; null from the Dictionaries screen. */
    keyboard: helium314.keyboard.latin.settings.SettingsSubtype? = null,
) {
    val ctx = LocalContext.current
    val (dictionaries, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
    val mainDict = dictionaries.firstOrNull { it.name == Dictionary.TYPE_MAIN + "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX }
    val addonDicts = dictionaries.filterNot { it == mainDict }
    val picker = dictionaryFilePicker(locale)
    // the dictionary of each kind in use: a tap on a greyed row (the internal main one, an added one of any kind, e.g.
    // a restore's "emoji_prev_user.dict") selects it, for every keyboard with this language (2026-10-12,
    // DictionarySelection; emoji as main since review M3); rows and names as before
    var selectionChanged by remember { mutableIntStateOf(0) }
    // (only main has internal dictionaries: none of another kind is bundled)
    fun hasInternalOf(type: String) = type == Dictionary.TYPE_MAIN && hasInternal
    val inUse = remember(selectionChanged, dictionaries, hasInternal) {
        (dictionaries.filter { DictionarySelection.isAdded(it) }.map { DictionarySelection.typeOf(it) } + Dictionary.TYPE_MAIN)
            .distinct().associateWith { DictionarySelection.inUse(ctx, locale, dictionaries, hasInternalOf(it), it) }
    }
    fun select(name: String, type: String) {
        if (name == inUse[type]) return
        DictionarySelection.select(ctx, locale, name, type)
        selectionChanged++
        ctx.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)) // (the keyboard loads it)
    }
    val greyed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) // for disabled look
    fun selectableRow(dict: File): Pair<Boolean, () -> Unit>? =
        if (DictionarySelection.isAdded(dict)) DictionarySelection.typeOf(dict).let { type ->
            Pair(inUse[type] == dict.name) { select(dict.name, type) }
        }
        else null
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = {},
        confirmButtonText = null,
        cancelButtonText = stringResource(R.string.dialog_close),
        title = { Text(locale.localizedDisplayName(LocalResources.current)) },
        content = {
            Column {
                if (hasInternal) {
                    val color = if (inUse[Dictionary.TYPE_MAIN] == DictionarySelection.INTERNAL) MaterialTheme.typography.titleSmall.color else greyed
                    val bottomPadding = if (mainDict == null) 12.dp else 0.dp
                    Text(stringResource(R.string.internal_dictionary_summary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { select(DictionarySelection.INTERNAL, Dictionary.TYPE_MAIN) }
                            .padding(bottom = bottomPadding),
                        color = color,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                if (mainDict != null)
                    DictionaryDetails(mainDict, locale, keyboard, selectableRow(mainDict), greyed) { selectionChanged++ }
                if (addonDicts.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.dictionary_category_title),
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                    addonDicts.forEach { DictionaryDetails(it, locale, keyboard, selectableRow(it), greyed) { selectionChanged++ } }
                }
                val dictString = createDictionaryTextAnnotated(locale)
                if (dictString.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.dictionary_available),
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(dictString, style = LocalTextStyle.current.merge(lineHeight = 1.8.em))
                }
            }
        },
        scrollContent = true,
        neutralButtonText = stringResource(R.string.add_dictionary_from_downloads),
        onNeutral = {
            val intent = helium314.keyboard.latin.utils.OnDemandDictionaries.pickerIntent()
            picker.launch(intent)
        }
    )
}

@Composable
private fun DictionaryDetails(dict: File, locale: Locale, keyboard: helium314.keyboard.latin.settings.SettingsSubtype?,
                              /** An added dictionary's row: whether it is in use among those of its kind, and selecting
                               *  it; null: not selectable. */
                              main: Pair<Boolean, () -> Unit>? = null,
                              greyed: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
                              /** Another dictionary was selected (the in-use dialog's Replace). */
                              onSelectionChanged: () -> Unit = { }) {
    val header = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(dict) ?: return
    val type = header.mIdString.substringBefore(":")
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    // (by the file, as the in-use dialog names it: two added main ones were both "Main dictionary", 20261013_L12-NAMES)
    val title = dictionaryDisplayName(LocalContext.current, dict.name)
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // a dictionary not in use (of its kind): greyed, a tap selects it
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = if (main == null || main.first) androidx.compose.ui.graphics.Color.Unspecified else greyed,
            modifier = Modifier.weight(1f).let { m -> if (main == null) m else m.clickable { main.second() } })
        DeleteButton { showDeleteDialog = true }
        ExpandButton { showDetails = !showDetails }
    }
    // default animations look better but make the dialog flash, see also MultiSliderPreference
    AnimatedVisibility(showDetails, enter = fadeIn(), exit = fadeOut()) {
        Text(
            header.info(LocalConfiguration.current.locale()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp, top = 0.dp, end = 10.dp, bottom = 12.dp)
        )
    }
    // the version in use while any keyboard has the language: no delete (review L3.2; before, owner 2026-10-11: "No delete
    // allowed if other keyboards using dict", which let the asking keyboard's only copy go). The dialog names the
    // keyboards using it and says how to free it; opened from the only keyboard using it, with another dictionary for
    // the language, it offers to replace it with that one and delete it (owner 2026-10-13, 20261013_L12; the "Turn the
    // language off here" button is gone: "If it's not replaceable then turn off button shouldn't exist")
    val context = LocalContext.current
    val blockers = if (!showDeleteDialog) emptyList() else remember(showDeleteDialog) { dictionaryDeleteBlockers(context, locale, dict) }
    if (showDeleteDialog && blockers.isNotEmpty()) {
        val inUse = remember(blockers) { dictionaryInUse(context, locale, dict, keyboard, blockers) }
        ThreeButtonAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            onConfirmed = {
                inUse.replaceWith?.let { replaceAndDelete(context, locale, dict, it) }
                onSelectionChanged()
            },
            confirmButtonText = inUse.replaceButton,
            cancelButtonText = stringResource(if (inUse.replaceButton != null) android.R.string.cancel else android.R.string.ok),
            title = { Text(inUse.title) },
            content = { Text(inUse.message) },
        )
    } else if (showDeleteDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButtonText = stringResource(R.string.remove),
            onConfirmed = { deleteDictionary(context, locale, dict, onSelectionChanged) },
            content = {
                // (the old Dictionaries screen, no keyboard asking: who uses it, none or the one, owner 2026-10-11)
                val users = if (keyboard != null) null else remember { allDictionaryUsers(context, locale) }
                Text(stringResource(R.string.remove_dictionary_message, type) + when {
                    users == null -> ""
                    users.isEmpty() -> "\n\n" + stringResource(R.string.dictionary_used_by_none)
                    else -> "\n\n" + stringResource(R.string.dictionary_used_by_one, dictionaryUserNames(context, users.take(1)).first())
                })
            }
        )
    }
}

/** The plain delete of [dict] (not in use): the file goes, a selection naming it is cleared, the keyboard reloads, and
 *  the dialog's list for the language is drawn again through [onSelectionChanged], as after the in-use dialog's Replace
 *  (round-5 review 20261013_F6: the deleted row stayed until the dialog was opened again). */
internal fun deleteDictionary(context: android.content.Context, locale: Locale, dict: File, onSelectionChanged: () -> Unit) {
    dict.delete()
    // (a selection naming a file no longer there is cleared, never left behind)
    val kind = DictionarySelection.typeOf(dict)
    if (DictionarySelection.selected(context, locale, kind) == dict.name) DictionarySelection.clear(context, locale, kind)
    context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
    onSelectionChanged()
}

/** What the "in use" dialog says when the trash can is tapped on [dict], the version of its kind in use for [locale]
 *  that [blockers] keep ([dictionaryDeleteBlockers], not empty); [keyboard]: the one whose language row opened it, null
 *  from the Dictionaries screen (owner 2026-10-13, 20261013_L12). [replaceWith]: what its Replace button selects
 *  ([DictionarySelection.INTERNAL] or a file name), with [replaceButton] its text; null: no such button (OK only). */
internal class DictionaryInUse(val title: String, val message: String, val replaceWith: String?, val replaceButton: String?)

internal fun dictionaryInUse(context: android.content.Context, locale: Locale, dict: File,
                             keyboard: helium314.keyboard.latin.settings.SettingsSubtype?,
                             blockers: List<helium314.keyboard.latin.settings.SettingsSubtype>): DictionaryInUse {
    val title = context.getString(R.string.dictionary_in_use_title, dictionaryDisplayName(context, dict.name))
    val usedBy = context.getString(R.string.dictionary_in_use_users, dictionaryUserNames(context, blockers).joinToString(", "))
    // (the asking keyboard the only one using it: replaced when there is another, main or second language alike)
    val sole = keyboard != null && blockers.size == 1 && blockers.single().sameKeyboard(keyboard)
    val next = if (sole) nextDictionary(context, locale, dict) else null
    val case = when {
        next != null -> context.getString(R.string.dictionary_in_use_select_first)
        // (another kind than main: the sentence names it, "the only emoji dictionary", 20261013_F4)
        sole -> DictionarySelection.typeOf(dict).let { kind ->
            if (kind == Dictionary.TYPE_MAIN) context.getString(R.string.dictionary_in_use_only_one)
            else context.getString(R.string.dictionary_in_use_only_one_kind, dictionaryKindName(context, kind).lowercase())
        }
        else -> context.resources.getQuantityString(R.plurals.dictionary_in_use_select_for, blockers.size)
    }
    return DictionaryInUse(title, "$usedBy $case", next,
        next?.let { context.getString(R.string.dictionary_replace_and_delete, dictionaryDisplayName(context, it)) })
}

/** The dictionary that replaces [dict] (its kind) for [locale]: the built-in one if the language has one of the kind,
 *  else the next added one of the kind in the list (by name, after [dict], then from the start); null: none (owner
 *  2026-10-13, 20261013_L12: "Next = Built-in if the language has one, else the next user-added dictionary"). */
internal fun nextDictionary(context: android.content.Context, locale: Locale, dict: File): String? {
    val (files, hasInternal) = getUserAndInternalDictionaries(context, locale)
    val kind = DictionarySelection.typeOf(dict)
    if (kind == Dictionary.TYPE_MAIN && hasInternal) return DictionarySelection.INTERNAL
    val names = files.filter { DictionarySelection.isAdded(it) && DictionarySelection.typeOf(it) == kind }.map { it.name }.sorted()
    val at = names.indexOf(dict.name)
    return (names.drop(at + 1) + names.take(at.coerceAtLeast(0))).firstOrNull { it != dict.name }
}

/** [next] ([nextDictionary]) selected for [locale] in place of [dict], then [dict] deleted: the in-use dialog's Replace
 *  (owner 2026-10-13, 20261013_L12: "Replace with <next> and delete this one"). The keyboard loads the new one. */
internal fun replaceAndDelete(context: android.content.Context, locale: Locale, dict: File, next: String) {
    DictionarySelection.select(context, locale, next, DictionarySelection.typeOf(dict))
    dict.delete()
    context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
}

/** A dictionary's name ([name]: its file name, or [DictionarySelection.INTERNAL]) everywhere the dictionaries are named:
 *  their rows, the in-use dialog's title and its Replace button (round-5 review 20261013_L12-NAMES): the built-in one's
 *  ("Internal main dictionary"), the default added main one's ("Main dictionary"), another added main one "Main
 *  dictionary (main_prev2)", one of another kind "<Kind> dictionary (<file name>)", e.g. "Emoji dictionary (emoji)". */
internal fun dictionaryDisplayName(context: android.content.Context, name: String): String {
    if (name == DictionarySelection.INTERNAL) return context.getString(R.string.internal_dictionary_summary)
    if (name == DictionarySelection.DEFAULT_USER_MAIN) return context.getString(R.string.main_dictionary)
    val file = name.removeSuffix("_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)
    val kind = DictionarySelection.typeOf(File(name))
    return if (kind == Dictionary.TYPE_MAIN) context.getString(R.string.main_dictionary_file, file)
        else context.getString(R.string.kind_dictionary_file, dictionaryKindName(context, kind), file)
}

/** A dictionary kind's name, as a title starts it ("Emoji"); a kind the app has no name for: its own word ("Symbols"). */
internal fun dictionaryKindName(context: android.content.Context, kind: String): String = when (kind) {
    Dictionary.TYPE_EMOJI -> context.getString(R.string.dictionary_kind_emoji)
    else -> kind.replaceFirstChar { it.titlecase() }
}

/** The keyboards keeping the added dictionary [dict] of [locale] from being deleted (review L3.2): it is the version of
 *  its kind in use ([DictionarySelection.inUse]: selected, or the default name; also the only copy, and an added main
 *  one over the built-in), and every keyboard with the language, the asking one too, on or off, uses it. A version not
 *  in use can go; so can the one in use once no keyboard has the language. */
internal fun dictionaryDeleteBlockers(context: android.content.Context, locale: Locale, dict: File): List<helium314.keyboard.latin.settings.SettingsSubtype> {
    val (files, hasInternal) = getUserAndInternalDictionaries(context, locale)
    val kind = DictionarySelection.typeOf(dict)
    val inUse = DictionarySelection.inUse(context, locale, files, kind == Dictionary.TYPE_MAIN && hasInternal, kind)
    return if (inUse != dict.name) emptyList() else allDictionaryUsers(context, locale)
}

/** Every keyboard with [locale] as its language or a second one: the enabled ones, then the switched-off ones (owner
 *  2026-10-11: they count as switched-on ones; before, a dictionary only a switched-off keyboard used could be deleted,
 *  and was gone when it came back). */
internal fun allDictionaryUsers(context: android.content.Context, locale: Locale): List<helium314.keyboard.latin.settings.SettingsSubtype> =
    (helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes(true)
        .map { with(helium314.keyboard.latin.settings.SettingsSubtype) { it.toSettingsSubtype() } } +
        helium314.keyboard.latin.settings.SwitchedOffKeyboards.list(
            helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)))
        .distinct()
        .filter { kb -> kb.locale == locale || locale in helium314.keyboard.latin.utils.getSecondaryLocales(kb.extraValues) }

/** [users] as the dialogs name them: a switched-off one "<name> (switched off)". */
internal fun dictionaryUserNames(context: android.content.Context,
                                 users: List<helium314.keyboard.latin.settings.SettingsSubtype>): List<String> {
    val enabled = helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes(true)
        .map { with(helium314.keyboard.latin.settings.SettingsSubtype) { it.toSettingsSubtype() } }.toSet()
    return users.map {
        val name = helium314.keyboard.settings.screens.keyboardName(it, context)
        if (it in enabled) name else context.getString(R.string.keyboard_switched_off, name)
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        DictionaryDialog({}, Locale.ENGLISH)
    }
}

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
    /** The keyboard whose language row opened it, with how to turn the language off there; null from the Dictionaries screen. */
    keyboard: helium314.keyboard.latin.settings.SettingsSubtype? = null,
    onTurnOff: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val (dictionaries, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
    val mainDict = dictionaries.firstOrNull { it.name == Dictionary.TYPE_MAIN + "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX }
    val addonDicts = dictionaries.filterNot { it == mainDict }
    val picker = dictionaryFilePicker(locale)
    // the main dictionary in use: a tap on a greyed main row (the internal one, an added one) selects it, for every
    // keyboard with this language (2026-10-12, DictionarySelection); rows and names as before
    var selectionChanged by remember { mutableIntStateOf(0) }
    val inUse = remember(selectionChanged, dictionaries, hasInternal) { DictionarySelection.inUse(ctx, locale, dictionaries, hasInternal) }
    fun select(name: String) {
        if (name == inUse) return
        DictionarySelection.select(ctx, locale, name)
        selectionChanged++
        ctx.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)) // (the keyboard loads it)
    }
    val greyed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) // for disabled look
    fun mainRow(dict: File): Pair<Boolean, () -> Unit>? =
        if (DictionarySelection.isAdded(dict) && DictionarySelection.typeOf(dict) == Dictionary.TYPE_MAIN)
            Pair(inUse == dict.name) { select(dict.name) }
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
                    val color = if (inUse == DictionarySelection.INTERNAL) MaterialTheme.typography.titleSmall.color else greyed
                    val bottomPadding = if (mainDict == null) 12.dp else 0.dp
                    Text(stringResource(R.string.internal_dictionary_summary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { select(DictionarySelection.INTERNAL) }
                            .padding(bottom = bottomPadding),
                        color = color,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                if (mainDict != null)
                    DictionaryDetails(mainDict, locale, keyboard, onTurnOff, mainRow(mainDict), greyed)
                if (addonDicts.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.dictionary_category_title),
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                    addonDicts.forEach { DictionaryDetails(it, locale, keyboard, onTurnOff, mainRow(it), greyed) }
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
                              onTurnOff: (() -> Unit)?,
                              /** A main dictionary's row: whether it is in use, and selecting it; null: another kind. */
                              main: Pair<Boolean, () -> Unit>? = null,
                              greyed: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    val header = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(dict) ?: return
    val type = header.mIdString.substringBefore(":")
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val title = if (type != DictionaryInfoUtils.DEFAULT_MAIN_DICT) type
        else stringResource(R.string.main_dictionary)
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // a main dictionary not in use: greyed, a tap selects it
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
    // used by other keyboards: no delete (owner 2026-10-11: "No delete allowed if other keyboards using dict"); the dialog
    // names them and, opened from a keyboard with it as a second language, offers to turn it off there instead
    val context = LocalContext.current
    val others = if (!showDeleteDialog) emptyList() else remember(showDeleteDialog) { dictionaryUsers(context, locale, keyboard) }
    if (showDeleteDialog && others.isNotEmpty()) {
        val canTurnOff = keyboard != null && onTurnOff != null && keyboard.locale != locale
        ThreeButtonAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            onConfirmed = { onTurnOff?.invoke() },
            confirmButtonText = if (canTurnOff) stringResource(R.string.dictionary_turn_off_here) else null,
            cancelButtonText = stringResource(if (canTurnOff) android.R.string.cancel else android.R.string.ok),
            title = { Text(stringResource(R.string.dictionary_in_use_title, type)) },
            content = { Text(stringResource(R.string.dictionary_in_use_message,
                dictionaryUserNames(context, others).joinToString("\n"))) },
        )
    } else if (showDeleteDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButtonText = stringResource(R.string.remove),
            onConfirmed = {
                dict.delete()
                context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
            },
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

/** The enabled keyboards with [locale] as a language, but [keyboard] (the one asking: a language row). (No keyboard
 *  asking: the old Dictionaries screen, which nothing opens; there all but one, so a dictionary one keyboard uses can go.) */
internal fun dictionaryUsers(context: android.content.Context, locale: Locale,
                             keyboard: helium314.keyboard.latin.settings.SettingsSubtype?): List<helium314.keyboard.latin.settings.SettingsSubtype> {
    val users = allDictionaryUsers(context, locale) // (the edited keyboard left out by its id)
    return if (keyboard != null) users.filter { !it.sameKeyboard(keyboard) } else if (users.size < 2) emptyList() else users
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

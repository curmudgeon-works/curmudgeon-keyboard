// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeleteButton
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog

/**
 * The saved Layouts (like the Themes row on Appearance): pick one to put it on this keyboard (shown on the preview at
 * once; OK keeps it, Cancel puts back what was there), save the current one under a name, rename or delete your own.
 */
@Composable
fun LayoutPresetsPreference(keyboard: SettingsSubtype, setKeyboard: (SettingsSubtype) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var generation by remember { mutableIntStateOf(0) }
    val presets = remember(generation) { LayoutPresets.load(prefs) }
    var showList by remember { mutableStateOf(false) }
    // what was set when the list opened, for Cancel: the settings and the keyboard (its keys)
    val initial = remember(showList) { LayoutPresets.current(prefs) }
    val initialKeyboard = remember(showList) { keyboard }
    var confirmed by remember(showList) { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var toRename: LayoutPresets.Preset? by remember { mutableStateOf(null) }
    var toDelete: LayoutPresets.Preset? by remember { mutableStateOf(null) }
    fun store(list: List<LayoutPresets.Preset>) { LayoutPresets.save(prefs, list); generation++ }
    val chosen = prefs.getString(LayoutPresets.PREF_SELECTED, null)?.let { name -> presets.firstOrNull { it.name == name } }
    val tweaked = chosen != null && LayoutPresets.isTweaked(ctx, keyboard, chosen)
    val summary = chosen?.let { if (tweaked) it.name + " (" + stringResource(R.string.theme_tweaked) + ")" else it.name }
        ?: stringResource(R.string.layout_presets_summary)
    Preference(name = stringResource(R.string.layout_presets), description = summary, onClick = { showList = true }) { NextScreenIcon() }
    // while the list is open nothing of the keyboard's is cleaned up (its unsaved layout must survive a preview
    // for Cancel); afterwards what no keyboard uses goes
    // OK would drop the keyboard's unsaved layout: asked first (the preset, and what was there for a "no")
    var askLoss: Triple<LayoutPresets.Preset, Map<String, Any?>, SettingsSubtype>? by remember { mutableStateOf(null) }
    fun keep(preset: LayoutPresets.Preset, from: SettingsSubtype) {
        LayoutPresets.restorePopupSet(ctx, from, preset) // its popup set, if deleted since
        prefs.edit { putString(LayoutPresets.PREF_SELECTED, preset.name) }
    }
    if (showList || askLoss != null) androidx.compose.runtime.DisposableEffect(Unit) {
        helium314.keyboard.latin.utils.LayoutUtilsCustom.cleanupHeld++
        onDispose {
            helium314.keyboard.latin.utils.LayoutUtilsCustom.cleanupHeld--
            helium314.keyboard.latin.utils.LayoutUtilsCustom.removeUnusedPrivateLayouts(ctx)
        }
    }
    if (showList)
        ListPickerDialog(
            onDismissRequest = {
                if (!confirmed) {
                    if (LayoutPresets.current(prefs) != initial) LayoutPresets.applySettings(ctx, initial)
                    if (keyboard != initialKeyboard) setKeyboard(initialKeyboard)
                }
                showList = false
            },
            title = { Text(stringResource(R.string.layout_presets)) },
            items = presets,
            getItemName = { it.name },
            confirmImmediately = false,
            // each tap starts from what was there when the list opened, so one previewed Layout never leaks into the next
            onItemHighlighted = {
                LayoutPresets.applySettings(ctx, initial + LayoutPresets.settingsFor(initialKeyboard, it))
                setKeyboard(LayoutPresets.keyboardWith(ctx, initialKeyboard, it))
            },
            onItemSelected = {
                confirmed = true
                if (helium314.keyboard.latin.utils.LayoutUtilsCustom.dropsUnsaved(initialKeyboard, keyboard))
                    askLoss = Triple(it, initial, initialKeyboard)
                else keep(it, initialKeyboard)
            },
            trailing = { preset ->
                IconButton({ confirmed = true; showList = false; toRename = preset }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.layout_preset_rename)) }
                DeleteButton { confirmed = true; showList = false; toDelete = preset }
            },
            footer = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable { confirmed = true; showList = false; saveAs = true }
                        .padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_plus), null, Modifier.padding(horizontal = 12.dp))
                    Text(stringResource(R.string.layout_preset_save), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    if (saveAs)
        TextInputDialog(
            onDismissRequest = { saveAs = false },
            title = { Text(stringResource(R.string.layout_preset_save)) },
            initialText = stringResource(R.string.layout_preset_default_name, presets.size + 1),
            checkTextValid = { name -> name.isNotBlank() && presets.none { it.name == name } },
            onConfirmed = { name ->
                store(presets + LayoutPresets.Preset(name, LayoutPresets.snapshot(ctx, keyboard)))
                prefs.edit { putString(LayoutPresets.PREF_SELECTED, name) } // what this keyboard has now is this Layout
            },
        )
    toRename?.let { preset ->
        TextInputDialog(
            onDismissRequest = { toRename = null },
            title = { Text(stringResource(R.string.layout_preset_rename)) },
            initialText = preset.name,
            checkTextValid = { name -> name.isNotBlank() && presets.none { it !== preset && it.name == name } },
            onConfirmed = { name ->
                store(presets.map { if (it === preset) LayoutPresets.Preset(name, it.values) else it })
                if (prefs.getString(LayoutPresets.PREF_SELECTED, null) == preset.name) prefs.edit { putString(LayoutPresets.PREF_SELECTED, name) }
            },
        )
    }
    askLoss?.let { (preset, before, beforeKeyboard) ->
        ConfirmationDialog(
            onDismissRequest = { // no: everything as it was when the list opened
                LayoutPresets.applySettings(ctx, before)
                if (keyboard != beforeKeyboard) setKeyboard(beforeKeyboard)
                askLoss = null
            },
            title = { Text(stringResource(R.string.layout_presets)) },
            content = { Text(stringResource(R.string.unsaved_layout_will_be_lost)) },
            onConfirmed = { keep(preset, beforeKeyboard); askLoss = null },
        )
    }
    toDelete?.let { preset ->
        ConfirmationDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.layout_preset_delete, preset.name)) },
            confirmButtonText = stringResource(R.string.delete),
            onConfirmed = {
                store(presets.filter { it !== preset })
                if (prefs.getString(LayoutPresets.PREF_SELECTED, null) == preset.name) prefs.edit { remove(LayoutPresets.PREF_SELECTED) }
            },
        )
    }
}

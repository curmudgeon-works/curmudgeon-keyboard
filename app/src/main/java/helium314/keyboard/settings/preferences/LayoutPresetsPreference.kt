// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.realPrefs
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
import androidx.compose.runtime.collectAsState
import helium314.keyboard.latin.utils.getActivity
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
 * Saving, renaming and deleting open over the list, which stays (with the preview) until OK or Cancel.
 */
@Composable
fun LayoutPresetsPreference(keyboard: SettingsSubtype, setKeyboard: (SettingsSubtype) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var generation by remember { mutableIntStateOf(0) }
    val presets = remember(generation) { LayoutPresets.load(prefs) }
    // Layouts that couldn't be read: said under the list until a save has set them aside (R9 2026-10-11)
    val skipped = remember(generation) { LayoutPresets.skipped(prefs) }
    // the built-in Layouts first (Curmudgeon, the default; HeliBoard; HeliBoard extra), then the user's
    val builtIn = remember { LayoutPresets.builtIn(ctx) }
    val all = builtIn + presets
    var showList by remember { mutableStateOf(false) }
    // what was set when the list opened, for Cancel: the settings and the keyboard (its keys)
    val initial = remember(showList) { LayoutPresets.current(prefs) }
    val initialKeyboard = remember(showList) { keyboard }
    // the settings common to all keyboards: the first one's name, said under the update question's list
    val common = remember(showList) { helium314.keyboard.settings.screens.commonFrom(ctx, KeyboardProfiles.Group.LAYOUT) }
    var confirmed by remember(showList) { mutableStateOf(false) }
    var previewed: LayoutPresets.Preset? by remember(showList) { mutableStateOf(null) } // the one tapped, on the keyboard now
    var saveAs by remember { mutableStateOf(false) }
    var toRename: LayoutPresets.Preset? by remember { mutableStateOf(null) }
    var toDelete: LayoutPresets.Preset? by remember { mutableStateOf(null) }
    fun store(list: List<LayoutPresets.Preset>) { LayoutPresets.save(prefs, list); generation++ }
    // none chosen: the default, Curmudgeon; "unsaved" in italics the moment anything differs from the chosen one (2026-10-07:
    // it said "Curmudgeon (tweaked)", and only once the row was drawn again): read again on every settings change
    val changed = (ctx.getActivity() as? helium314.keyboard.settings.SettingsActivity)?.prefChanged?.collectAsState()
    val chosen = prefs.getString(LayoutPresets.PREF_SELECTED, null)?.let { name -> all.firstOrNull { it.name == name } }
        ?: builtIn.first()
    // differs from it (none chosen: Curmudgeon, the default): the keyboard's own copy, "Custom · from Work" (2026-10-11, no
    // number since; was "unsaved"); its Layout deleted: "Custom · from Work" until it chooses one again (review E2: only a
    // stored origin means deleted, CustomLabels.row)
    val row = remember(changed?.value, keyboard, chosen, generation, prefs.getString(LayoutPresets.PREF_SELECTED, null),
        helium314.keyboard.settings.CustomLabels.origin(prefs, helium314.keyboard.settings.CustomLabels.Kind.LAYOUT)) {
        helium314.keyboard.settings.CustomLabels.layoutRow(ctx, prefs, keyboard, chosen) }
    val tweaked = row.custom
    Preference(name = stringResource(R.string.layout_presets),
        description = row.text ?: chosen.name, descriptionItalic = tweaked,
        onClick = { showList = true }) { NextScreenIcon() }
    // which of the user's Layouts differ from the keyboard as it was when the list opened: those get the save icon
    val differs = remember(showList, generation) { presets.associateWith { LayoutPresets.isTweaked(ctx, keyboard, it) } }
    var toUpdate: LayoutPresets.Preset? by remember { mutableStateOf(null) }
    // while the list is open nothing of the keyboard's is cleaned up (its unsaved layout must survive a preview
    // for Cancel); afterwards what no keyboard uses goes
    // OK would drop the keyboard's unsaved layout: asked first (the preset, and what was there for a "no")
    var askLoss: Triple<LayoutPresets.Preset, Map<String, Any?>, SettingsSubtype>? by remember { mutableStateOf(null) }
    fun keep(preset: LayoutPresets.Preset, from: SettingsSubtype) {
        LayoutPresets.restorePopupSet(ctx, from, preset) // its popup set, if deleted since
        LayoutPresets.chosen(prefs, from, preset) // (its name; no Custom label for it, nor for popups it replaced)
    }
    if (showList || askLoss != null) androidx.compose.runtime.DisposableEffect(Unit) {
        helium314.keyboard.latin.utils.LayoutUtilsCustom.cleanupHeld++
        onDispose {
            helium314.keyboard.latin.utils.LayoutUtilsCustom.cleanupHeld--
            helium314.keyboard.latin.utils.LayoutUtilsCustom.removeUnusedPrivateLayouts(ctx)
        }
    }
    // the previewed Layout off again: what was there when the list opened
    fun putBack() {
        if (LayoutPresets.current(prefs) != initial) LayoutPresets.applySettings(ctx, initial)
        if (keyboard != initialKeyboard) setKeyboard(initialKeyboard)
    }
    if (showList)
        ListPickerDialog(
            onDismissRequest = {
                if (!confirmed) putBack()
                showList = false
            },
            title = { Text(stringResource(R.string.layout_presets)) },
            items = all,
            // the chosen Layout marked when the list opens (2026-10-06), none while the settings differ from it ("unsaved":
            // it marked Curmudgeon then, 2026-10-07)
            selectedItem = if (tweaked) null else chosen,
            getItemName = { it.name },
            confirmImmediately = false,
            // each tap starts from what was there when the list opened, so one previewed Layout never leaks into the next
            onItemHighlighted = {
                previewed = it
                LayoutPresets.applySettings(ctx, initial + LayoutPresets.settingsFor(initialKeyboard, it, helium314.keyboard.settings.SavedItemDefaults.takeNewDefaults(ctx.realPrefs())))
                setKeyboard(LayoutPresets.keyboardWith(ctx, initialKeyboard, it))
            },
            onItemSelected = { tapped ->
                confirmed = true
                // (the list's own copy: renamed since, it's found again by content; deleted, nothing is on the keyboard)
                val it = all.firstOrNull { p -> p == tapped } ?: return@ListPickerDialog
                if (helium314.keyboard.latin.utils.LayoutUtilsCustom.dropsUnsaved(initialKeyboard, keyboard))
                    askLoss = Triple(it, initial, initialKeyboard)
                else keep(it, initialKeyboard)
            },
            // the built-in Layouts can't be changed; the user's own: long press renames (2026-10-11), the save icon
            // (only where it differs from the keyboard) updates it with this keyboard's settings, the trash deletes
            trailing = { preset -> if (!preset.builtIn) {
                if (differs[preset] == true)
                    IconButton({ if (previewed != null) { putBack(); previewed = null }; toUpdate = preset }) {
                        Icon(painterResource(R.drawable.ic_save), stringResource(R.string.update_item_icon_screen)) }
                DeleteButton { toDelete = preset }
            } },
            onItemLongClick = { preset -> if (!preset.builtIn) toRename = preset },
            footer = {
                if (skipped > 0) helium314.keyboard.settings.dialogs.SkippedItemsText(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.layout_presets_skipped, skipped, skipped))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable { saveAs = true }
                        .padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_plus), null, Modifier.padding(horizontal = 12.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.layout_preset_save), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    val sameAsNow = if (!saveAs) null else remember(saveAs) { presets.firstOrNull { !LayoutPresets.isTweaked(ctx, keyboard, it) }?.name }
    if (saveAs)
        TextInputDialog(
            onDismissRequest = { saveAs = false },
            title = { Text(stringResource(R.string.layout_preset_save)) },
            initialText = stringResource(R.string.layout_preset_default_name, presets.size + 1),
            checkTextValid = { name -> name.isNotBlank() && all.none { it.name == name } },
            // a saved Layout with these settings already: said, nothing blocked (2026-10-11: "include the already exists note")
            description = sameAsNow?.let { { helium314.keyboard.settings.dialogs.SameSettingsNote(it) } },
            confirmButtonText = stringResource(if (sameAsNow != null) R.string.save_anyway else android.R.string.ok),
            onConfirmed = { name ->
                // what is on the keyboard now, a previewed Layout included
                // (a setting still at what the Layout on it, a previewed one included, keeps for its default stays at its default)
                store(presets + LayoutPresets.saved(ctx, keyboard, name, previewed ?: chosen))
                // what this keyboard has now is this Layout (from the list: only once it's OK'd)
                if (!showList) {
                    prefs.edit { putString(LayoutPresets.PREF_SELECTED, name) }
                    helium314.keyboard.settings.CustomLabels.clear(prefs, helium314.keyboard.settings.CustomLabels.Kind.LAYOUT) // (the copy is that Layout now)
                }
            },
        )
    toRename?.let { preset ->
        TextInputDialog(
            onDismissRequest = { toRename = null },
            title = { Text(stringResource(R.string.layout_preset_rename)) },
            initialText = preset.name,
            checkTextValid = { name -> name.isNotBlank() && all.none { it !== preset && it.name == name } },
            onConfirmed = { name ->
                store(presets.map { if (it === preset) LayoutPresets.Preset(name, it.values, it.builtIn, it.remembered) else it })
                KeyboardProfiles.replaceValueEverywhere(ctx.realPrefs(), LayoutPresets.PREF_SELECTED, preset.name, name)
            },
        )
    }
    toUpdate?.let { preset ->
        // (the keyboard whose set the update question's change is written to, as for themes and palettes: none for the
        // common settings, so this keyboard's own set hidden behind them is listed too, review SP5/6; was the keyboard
        // as it was when the list opened, left out in every mode)
        val edited = remember(preset) { KeyboardProfiles.editingKeyboard(ctx.realPrefs(), helium314.keyboard.settings.PrefsDraft.currentSetId(ctx)) }
        val others = remember(preset) { helium314.keyboard.settings.screens.otherUsers(ctx, LayoutPresets.PREF_SELECTED, preset.name,
            edited, KeyboardProfiles.Group.LAYOUT) }
        val sameAs = remember(preset) { presets.firstOrNull { it != preset && differs[it] == false }?.name }
        helium314.keyboard.settings.dialogs.UpdateSavedItemDialog(preset.name,
            helium314.keyboard.settings.screens.updateNames(ctx, others, common), sameAs,
            onDismissRequest = { toUpdate = null }, commonFrom = common, anyHidden = others.isNotEmpty(),
        ) { selected ->
            val updated = LayoutPresets.saved(ctx, initialKeyboard, preset.name, chosen)
            store(presets.map { if (it.name == preset.name) updated else it })
            prefs.edit { putString(LayoutPresets.PREF_SELECTED, preset.name) } // (this keyboard is that Layout now)
            helium314.keyboard.settings.CustomLabels.clear(prefs, helium314.keyboard.settings.CustomLabels.Kind.LAYOUT)
            for (i in selected) LayoutPresets.applyToOther(ctx, others[i], updated)
            toUpdate = null
        }
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
        // the keyboards using it keep its settings: named (2026-10-08)
        val keepers = remember(preset) { helium314.keyboard.settings.screens.deleteKeepersText(ctx,
            R.plurals.delete_keeps_layout, LayoutPresets.PREF_SELECTED, preset.name, keyboard) } // (maybe previewed keys)
        ConfirmationDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.layout_preset_delete, preset.name)) },
            content = keepers?.let { { Text(it) } },
            confirmButtonText = stringResource(R.string.delete),
            onConfirmed = {
                if (preset == previewed) { putBack(); previewed = null } // its preview goes with it
                LayoutPresets.delete(ctx.realPrefs(), preset.name); generation++
            },
        )
    }
}

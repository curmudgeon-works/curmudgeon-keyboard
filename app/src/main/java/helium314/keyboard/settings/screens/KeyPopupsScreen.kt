// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.settings.KeyboardProfiles
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues

import kotlinx.coroutines.delay

import androidx.compose.runtime.LaunchedEffect

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import helium314.keyboard.latin.utils.BackButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.latin.utils.DeleteButton
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.POPUP_KEYS_ORDER_DEFAULT
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.getActivity
import androidx.compose.runtime.collectAsState
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_ALL
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MAIN
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.SkippedItemsText
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.RichInputMethodManager
import kotlin.math.roundToInt

/**
 * Every key of a keyboard with its long-press popups, each popup switchable and draggable. The first enabled
 * popup is the key's hint. Two folding groups: letters and digits, and the keys that change with the text
 * field (comma, period, ... in plain text, web address and email fields), each variant on its own.
 */
/** The long-press popups of a keyboard as an inline section: the presets row, then the key groups, foldable. */
@Composable
fun KeyPopupsSection(keyboard: SettingsSubtype, onKeyboardChanged: (SettingsSubtype) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var generation by remember { mutableIntStateOf(0) }
    var showAccentsDialog by remember { mutableStateOf(false) }
    val preview = helium314.keyboard.settings.dialogs.LocalPreviewKeyboard.current
    // read again on every settings change too: a Layout applied from the Layouts row on this screen writes the popups and
    // the set (and may bring a set back) without this section knowing (review 2026-10-08: the row stayed "Custom")
    val changed = (ctx.getActivity() as? helium314.keyboard.settings.SettingsActivity)?.prefChanged?.collectAsState()
    val overrides = remember(generation, changed?.value) { KeyPopupOverrides.load(prefs) }
    Column {
        // presets: the generated defaults for every key at once (the user's own per-key edits stay on top)
        val userSets = remember(generation, changed?.value) { KeyPopupOverrides.loadSets(ctx.realPrefs()) }
        // sets that couldn't be read: said under the list until a save has set them aside (R9 2026-10-11)
        val skippedSets = remember(generation, changed?.value) { KeyPopupOverrides.skippedSets(ctx.realPrefs()) }
        // a change made while a built-in set is selected is held here until the user names a set for it
        var pendingChange: Pair<String, List<String>?>? by remember { mutableStateOf(null) }
        var showSaveAsDialog by remember { mutableStateOf(false) }
        val presets = popupPresets(keyboard, userSets, helium314.keyboard.settings.SavedItemDefaults.takeNewDefaults(ctx.realPrefs()))
        val accentsValue = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)
            ?: prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)!!
        val symbolsLayout = keyboard.layoutName(LayoutType.SYMBOLS)
        val current = currentPopupPreset(prefs, keyboard, presets, overrides)
        @Composable fun presetName(p: Preset) = p.userName ?: stringResource(p.name)
        // the keyboard's popups differ from the set it chose (edits stay on the keyboard until saved, 2026-10-11): it
        // shows "Custom · from Mine"; no set: "from" the built-in it has the recipe of (review E2, popupsRow)
        val row = popupsRow(ctx, prefs, keyboard, presets, userSets, overrides,
            helium314.keyboard.settings.SavedItemDefaults.takeNewDefaults(ctx.realPrefs()))
        val custom = row.custom
        val rowLabel = row.text ?: ""
        // every arrangement belongs to a set of the user's own: into the selected one, or into a new one to be named
        fun storeInSet(name: String, all: Map<String, List<String>>) {
            storePopupSet(ctx, keyboard, name, accentsValue, symbolsLayout, all)
            generation++
        }
        fun deleteSet(name: String) {
            deletePopupSet(ctx, name) // (the keyboard's popups stay as they are: no new preview)
            generation++
        }
        var setToDelete: String? by remember { mutableStateOf(null) }
        var setToRename: String? by remember { mutableStateOf(null) }
        var setToUpdate: String? by remember { mutableStateOf(null) }
        // the settings common to all keyboards: the first one's name, said under the update question's list
        val common = remember(showAccentsDialog) { commonFrom(ctx, KeyboardProfiles.Group.LAYOUT) }
        val takeNew = helium314.keyboard.settings.SavedItemDefaults.takeNewDefaults(ctx.realPrefs())
        // which of the user's sets differ from the keyboard's popups as they were when the list opened
        val setDiffers = remember(showAccentsDialog, generation) {
            userSets.associate { it.name to popupSetDiffers(prefs, keyboard, it, overrides, takeNew) } }
        setToUpdate?.let { name ->
            val set = userSets.firstOrNull { it.name == name }
            // the other keyboards on it, and the saved Layouts that hold it (owner 2026-10-11: those are listed too); not the
            // keyboard whose set the change is written to, as for themes and palettes: none for the common settings, so this
            // keyboard's own set hidden behind them is listed too (review SP5/6; was this keyboard, left out in every mode)
            val edited = remember(name) { KeyboardProfiles.editingKeyboard(ctx.realPrefs(), helium314.keyboard.settings.PrefsDraft.currentSetId(ctx)) }
            val keyboards = remember(name) { otherUsers(ctx, KeyPopupOverrides.PREF_SELECTED_SET, name, edited,
                KeyboardProfiles.Group.LAYOUT) }
            val layouts = remember(name) { helium314.keyboard.settings.LayoutPresets.load(ctx.realPrefs())
                .filter { it.values[KeyPopupOverrides.PREF_SELECTED_SET] == name }.map { it.name } }
            val sameAs = remember(name) { userSets.firstOrNull { it.name != name && setDiffers[it.name] == false }?.name }
            if (set != null) helium314.keyboard.settings.dialogs.UpdateSavedItemDialog(name,
                userNames(ctx, keyboards) + layouts.map { ctx.getString(R.string.update_item_layout, it) }, sameAs,
                onDismissRequest = { setToUpdate = null }, commonFrom = common, group = KeyboardProfiles.Group.LAYOUT, anyListed = keyboards.isNotEmpty(),
            ) { selected ->
                storeInSet(name, KeyPopupOverrides.load(prefs))
                val updated = KeyPopupOverrides.loadSets(ctx.realPrefs()).first { it.name == name }
                for (i in selected) {
                    if (i < keyboards.size) applySetToOther(ctx, keyboards[i], updated, takeNew)
                    else updateSetInLayout(ctx.realPrefs(), layouts[i - keyboards.size], updated)
                }
                setToUpdate = null
            }
        }
        setToRename?.let { oldName ->
            TextInputDialog(
                onDismissRequest = { setToRename = null },
                onConfirmed = { newName ->
                    renamePopupSet(ctx, oldName, newName.trim())
                    setToRename = null
                    generation++
                },
                title = { Text(stringResource(R.string.key_popups_rename_set)) },
                initialText = oldName,
                checkTextValid = { text -> text.isNotBlank() && (text.trim() == oldName || userSets.none { it.name == text.trim() }) },
            )
        }
        setToDelete?.let { name ->
            // the keyboards using it keep its popups: named (2026-10-08)
            val keepers = remember(name) { deleteKeepersText(ctx, R.plurals.delete_keeps_popup_set, KeyPopupOverrides.PREF_SELECTED_SET, name) }
            ConfirmationDialog(
                onDismissRequest = { setToDelete = null },
                onConfirmed = { deleteSet(name); setToDelete = null },
                title = { Text(stringResource(R.string.key_popups_delete_set_title, name)) },
                content = keepers?.let { { Text(it) } },
                confirmButtonText = stringResource(R.string.delete),
            )
        }
        val sameAsNow = if (!showSaveAsDialog) null else remember(showSaveAsDialog) {
            userSets.firstOrNull { !popupSetDiffers(prefs, keyboard, it, KeyPopupOverrides.load(prefs), takeNew) }?.name }
        if (showSaveAsDialog)
            TextInputDialog(
                onDismissRequest = { showSaveAsDialog = false; pendingChange = null }, // cancel = the change is dropped
                onConfirmed = { name ->
                    val all = KeyPopupOverrides.load(prefs).toMutableMap() // (the keyboard's popups now, not as last drawn)
                    pendingChange?.let { (k, v) -> if (v == null) all.remove(k) else all[k] = v }
                    storeInSet(name.trim(), all)
                    pendingChange = null
                },
                title = { Text(stringResource(if (pendingChange != null) R.string.key_popups_save_change_title else R.string.key_popups_save_as_new)) },
                initialText = if (current?.userName != null) "" else stringResource(R.string.key_popups_my_set),
                checkTextValid = { KeyPopupOverrides.isNewSetName(userSets, it) },
                // a set with these popups already: said, nothing blocked (2026-10-11: "include the already exists note")
                description = sameAsNow?.let { { helium314.keyboard.settings.dialogs.SameSettingsNote(it) } },
                confirmButtonText = stringResource(if (sameAsNow != null) R.string.save_anyway else android.R.string.ok),
            )
        // a tap shows the preset on the preview and the list stays (like Themes); OK keeps it, Cancel puts back what
        // was there when the list opened (the keyboard's accents and symbols page, the arrangement, the set, the map)
        val opened = remember(showAccentsDialog) { keyboard to listOf(prefs.getString(KeyPopupOverrides.PREF, null),
            prefs.getString(KeyPopupOverrides.PREF_SELECTED_SET, null), prefs.getString(Settings.PREF_SYMBOL_POPUP_MAP, null),
            prefs.getString(Settings.PREF_POPUP_KEYS_ORDER, null)) }
        // (a tap clears the label and a deleted origin's name: Cancel puts them back too, 2026-10-11)
        val openedLabel = remember(showAccentsDialog) { with(helium314.keyboard.settings.CustomLabels.Kind.POPUPS) {
            prefs.getInt(key, 0) to prefs.getString(fromKey, null) } }
        var confirmed by remember(showAccentsDialog) { mutableStateOf(false) }
        fun putBack() {
            val (kb, values) = opened
            prefs.edit().apply {
                listOf(KeyPopupOverrides.PREF, KeyPopupOverrides.PREF_SELECTED_SET, Settings.PREF_SYMBOL_POPUP_MAP, Settings.PREF_POPUP_KEYS_ORDER).zip(values)
                    .forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
                with(helium314.keyboard.settings.CustomLabels.Kind.POPUPS) {
                    if (prefs.getInt(key, 0) != openedLabel.first) { if (openedLabel.first > 0) putInt(key, openedLabel.first) else remove(key) }
                    if (prefs.getString(fromKey, null) != openedLabel.second) {
                        if (openedLabel.second != null) putString(fromKey, openedLabel.second) else remove(fromKey) }
                }
            }.apply()
            if (keyboard != kb) onKeyboardChanged(kb)
            generation++
            reloadPreview()
        }
        /** [preset] on the keyboard: its accents level and symbols page, its arrangement (a built-in has none), the set's
         *  name, the letter-to-symbol map. */
        fun applyPreset(preset: Preset) {
            var changed = if (KeyPopupOverrides.ACCENTS in preset.follows) keyboard.without(ExtraValue.MORE_POPUPS)
                else keyboard.with(ExtraValue.MORE_POPUPS, preset.morePopups)
            changed = if (preset.symbolsLayout == null) changed.withoutLayout(LayoutType.SYMBOLS)
                else changed.withLayout(LayoutType.SYMBOLS, preset.symbolsLayout)
            // a preset with its own popup order sets it, and the keyboard's own order (if any) goes
            if (preset.popupOrder != null) changed = changed.without(ExtraValue.POPUP_ORDER)
            KeyPopupOverrides.save(prefs, preset.overrides ?: emptyMap())
            helium314.keyboard.settings.CustomLabels.clear(prefs, helium314.keyboard.settings.CustomLabels.Kind.POPUPS) // (an item chosen: no copy)
            prefs.edit().apply {
                if (preset.userName != null) putString(KeyPopupOverrides.PREF_SELECTED_SET, preset.userName) else remove(KeyPopupOverrides.PREF_SELECTED_SET)
                // (a set's setting kept at its default, with "Saved items take new defaults" on: the default again)
                if (KeyPopupOverrides.MAP in preset.follows) remove(Settings.PREF_SYMBOL_POPUP_MAP)
                else if (preset.symbolMap != null) putString(Settings.PREF_SYMBOL_POPUP_MAP, preset.symbolMap)
                if (KeyPopupOverrides.ORDER in preset.follows) remove(Settings.PREF_POPUP_KEYS_ORDER)
                else if (preset.popupOrder != null) putString(Settings.PREF_POPUP_KEYS_ORDER, preset.popupOrder)
            }.apply()
            onKeyboardChanged(changed)
            generation++
            reloadPreview()
        }
        if (showAccentsDialog)
            ListPickerDialog(
                onDismissRequest = { if (!confirmed) putBack(); showAccentsDialog = false },
                title = { Text(stringResource(R.string.key_popups_presets)) },
                items = presets,
                getItemName = { presetName(it) },
                selectedItem = if (custom) null else current, // (none marked while the keyboard has its own copy)
                confirmImmediately = false,
                onItemHighlighted = { applyPreset(it) },
                // (rename / delete: what was only previewed is put back first)
                // the user's own sets: long press renames (2026-10-11), the save icon (only where the set differs from the
                // keyboard) updates it with this keyboard's popups, the trash deletes
                trailing = { p -> p.userName?.let { name ->
                    if (setDiffers[name] == true)
                        IconButton({ if (!confirmed) putBack(); confirmed = true; showAccentsDialog = false; setToUpdate = name }) {
                            Icon(painterResource(R.drawable.ic_save), stringResource(R.string.update_item_icon_screen)) }
                    DeleteButton { if (!confirmed) putBack(); confirmed = true; showAccentsDialog = false; setToDelete = name }
                } },
                onItemLongClick = { p -> p.userName?.let { name -> if (!confirmed) putBack(); confirmed = true; showAccentsDialog = false; setToRename = name } },
                // the current arrangement (built-in or own, with any changes) as a new set of the user's own
                footer = {
                    if (skippedSets > 0) SkippedItemsText(pluralStringResource(R.plurals.popup_sets_skipped, skippedSets, skippedSets))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                        // (saving keeps what's on the keyboard now, a previewed preset included: that's what gets saved)
                        modifier = Modifier.fillMaxWidth().clickable { confirmed = true; showAccentsDialog = false; pendingChange = null; showSaveAsDialog = true }
                            .padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_plus), null, Modifier.padding(horizontal = 12.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.key_popups_save_as_new), color = MaterialTheme.colorScheme.primary)
                    }
                },
                onItemSelected = { preset ->
                    confirmed = true
                    applyPreset(preset)
                    preview?.keepAfterClose() // the picked popups stay on the preview a while
                }
            )
        // the deep customization, advanced only: every key's popups (a tab per key group), the keys and popups as JSON
        val advanced by SettingsMode.state(ctx)
        AdvancedBlock(advanced) {
            // the preset layouts first (with saving the current one), then the full customization under them
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { showAccentsDialog = true }.heightIn(min = ROW_HEIGHT).padding(vertical = 4.dp).padding(start = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.key_popups_presets), style = MaterialTheme.typography.bodyLarge)
                    Text(rowLabel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NextScreenIcon()
            }
            NavRow(stringResource(R.string.key_popups_full)) {
                SettingsDestination.navigateTo(SettingsDestination.withKeyboard(SettingsDestination.CustomizePopups, keyboard), ctx)
            }
            LayoutFilesRow(keyboard)
        }
        // own sets are saved (last entry), renamed (pencil) and deleted (trash icon) in the preset list
    }
}

/** Every arrangement belongs to a set of the user's own: stores [all] as set [name] and selects it. */
internal fun storePopupSet(ctx: Context, keyboard: SettingsSubtype, name: String, accentsValue: String, symbolsLayout: String?, all: Map<String, List<String>>) {
    val prefs = ctx.prefs()
    // with the letter-to-symbol map and the popup order as they are (re-review 2026-10-07: a set without them took the
    // last tapped built-in's)
    val symbolMap = prefs.getString(Settings.PREF_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP)!!
    val popupOrder = keyboard.getExtraValueOf(ExtraValue.POPUP_ORDER) ?: prefs.getString(Settings.PREF_POPUP_KEYS_ORDER, Defaults.PREF_POPUP_KEYS_ORDER)!!
    // and which of them the keyboard reads at their default (not picked): see UserSet.atDefault; with "Saved items take
    // new defaults" off, also what is still at the value the chosen set kept for its default (review 2026-10-11 A1: Update
    // made it a pick)
    val chosen = prefs.getString(KeyPopupOverrides.PREF_SELECTED_SET, null)?.let { n -> KeyPopupOverrides.loadSets(ctx.realPrefs()).firstOrNull { it.name == n } }
        ?.takeIf { !helium314.keyboard.settings.SavedItemDefaults.takeNewDefaults(ctx.realPrefs()) }
    val kept = chosen?.let { helium314.keyboard.settings.SavedItemDefaults.remembered(it) } ?: emptyMap()
    fun keptAtDefault(key: String) = helium314.keyboard.settings.SavedItemDefaults.atChosenDefault(key, prefs.getString(key, null), kept)
    val atDefault = setOfNotNull(
        KeyPopupOverrides.ACCENTS.takeIf { keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)?.let { chosen != null && KeyPopupOverrides.ACCENTS in chosen.atDefault
            && it == chosen.morePopups } ?: !prefs.contains(Settings.PREF_MORE_POPUP_KEYS) },
        KeyPopupOverrides.MAP.takeIf { !prefs.contains(Settings.PREF_SYMBOL_POPUP_MAP) || keptAtDefault(Settings.PREF_SYMBOL_POPUP_MAP) },
        KeyPopupOverrides.ORDER.takeIf { keyboard.getExtraValueOf(ExtraValue.POPUP_ORDER) == null
            && (!prefs.contains(Settings.PREF_POPUP_KEYS_ORDER) || keptAtDefault(Settings.PREF_POPUP_KEYS_ORDER)) })
    val set = KeyPopupOverrides.UserSet(name, accentsValue, symbolsLayout, all, symbolMap, popupOrder, atDefault)
    KeyPopupOverrides.saveSets(ctx.realPrefs(), KeyPopupOverrides.loadSets(ctx.realPrefs()).filter { it.name != name } + set)
    KeyPopupOverrides.save(prefs, all)
    prefs.edit().putString(KeyPopupOverrides.PREF_SELECTED_SET, name).apply()
    helium314.keyboard.settings.CustomLabels.clear(prefs, helium314.keyboard.settings.CustomLabels.Kind.POPUPS) // (it is the set now)
    reloadPreview()
}

/**
 * Whether the keyboard's popups differ from user set [set] it chose: the arrangement [overrides], the accents level,
 * the symbols page, the symbol map and the popup order (a setting the set kept at its default compares with what the
 * keyboard reads with "Saved items take new defaults" [takeNew] on: the default).
 */
internal fun popupSetDiffers(prefs: SharedPreferences, keyboard: SettingsSubtype, set: KeyPopupOverrides.UserSet,
                             overrides: Map<String, List<String>>, takeNew: Boolean): Boolean {
    if (overrides != set.overrides) return true
    if (keyboard.layoutName(LayoutType.SYMBOLS) != set.symbolsLayout) return true
    val follows = if (takeNew) set.atDefault else emptySet()
    val accents = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)
    if (KeyPopupOverrides.ACCENTS in follows) { if (accents != null) return true }
    else if ((accents ?: prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)) != set.morePopups) return true
    // a field the set keeps at its default: the same when the keyboard reads the default, stored or not (review D3)
    if (KeyPopupOverrides.MAP in follows) {
        if (prefs.getString(Settings.PREF_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP) != Defaults.PREF_SYMBOL_POPUP_MAP) return true
    }
    else if (set.symbolMap != null && prefs.getString(Settings.PREF_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP) != set.symbolMap) return true
    val order = keyboard.getExtraValueOf(ExtraValue.POPUP_ORDER)
    if (KeyPopupOverrides.ORDER in follows) {
        if ((order ?: prefs.getString(Settings.PREF_POPUP_KEYS_ORDER, Defaults.PREF_POPUP_KEYS_ORDER)) != Defaults.PREF_POPUP_KEYS_ORDER) return true
    }
    else if (set.popupOrder != null && (order ?: prefs.getString(Settings.PREF_POPUP_KEYS_ORDER, Defaults.PREF_POPUP_KEYS_ORDER)) != set.popupOrder) return true
    return false
}

/** Renames the user's popup set [oldName] to [name], and every keyboard that had it chosen follows. */
internal fun renamePopupSet(ctx: Context, oldName: String, name: String) {
    val real = ctx.realPrefs()
    KeyPopupOverrides.saveSets(real, KeyPopupOverrides.loadSets(real).map {
        if (it.name == oldName) KeyPopupOverrides.UserSet(name, it.morePopups, it.symbolsLayout, it.overrides, it.symbolMap, it.popupOrder, it.atDefault) else it
    })
    KeyboardProfiles.replaceValueEverywhere(real, KeyPopupOverrides.PREF_SELECTED_SET, oldName, name)
    helium314.keyboard.settings.LayoutPresets.onPopupSetRenamed(real, oldName, name) // and the saved Layouts (review 2026-10-08 area 4 #4)
}

/** Deletes the user's popup set [name]: every keyboard that had it chosen has none chosen (2026-10-07: only the keyboard
 *  being edited forgot it) and keeps its popups as they are, shown as "Custom" (2026-10-08: the keyboard being edited
 *  lost its popups, and with separate settings off or the Layout menu shared every keyboard did). */
/** User set [set] (just updated with the edited keyboard's popups) on another [keyboard] selected in the update dialog
 *  (2026-10-11): its arrangement, accents level, symbols page, map and order, the set's name, no Custom label. */
internal fun applySetToOther(ctx: Context, keyboard: SettingsSubtype, set: KeyPopupOverrides.UserSet, takeNew: Boolean) {
    val real = ctx.realPrefs()
    // (listed by its own set's choice since 2026-10-12, so it has a set; review 2026-10-11 C8 was one without a set
    // of its own yet, read through the shared set)
    // (owner 2026-10-11: lookups never create records; a listed one gets its set from the net, one neither listed nor
    // with a set is left alone)
    val id = KeyboardProfiles.idForListed(real, keyboard) ?: return
    // (its own set as stored, shown or hidden: with one set of settings or the menu shared too, as it was asked by
    // its own set's choice; a ProfilePreferences view writes a shared menu's settings to the main keyboard's set.
    // Owner 2026-10-11: a switched-off keyboard is asked by its own choice; owner 2026-10-12: "our saving policies
    // should not be affected by the common/separate toggle at all.")
    val view = helium314.keyboard.latin.settings.OwnSetPreferences(real, id)
    val follows = if (takeNew) set.atDefault else emptySet()
    KeyPopupOverrides.save(view, set.overrides)
    view.edit().apply {
        putString(KeyPopupOverrides.PREF_SELECTED_SET, set.name)
        if (KeyPopupOverrides.MAP in follows) remove(Settings.PREF_SYMBOL_POPUP_MAP)
        else if (set.symbolMap != null) putString(Settings.PREF_SYMBOL_POPUP_MAP, set.symbolMap)
        if (KeyPopupOverrides.ORDER in follows) remove(Settings.PREF_POPUP_KEYS_ORDER)
        else if (set.popupOrder != null) putString(Settings.PREF_POPUP_KEYS_ORDER, set.popupOrder)
    }.commit()
    helium314.keyboard.settings.CustomLabels.clear(view, helium314.keyboard.settings.CustomLabels.Kind.POPUPS)
    var changed = if (KeyPopupOverrides.ACCENTS in follows) keyboard.without(ExtraValue.MORE_POPUPS) else keyboard.with(ExtraValue.MORE_POPUPS, set.morePopups)
    changed = if (set.symbolsLayout == null) changed.withoutLayout(LayoutType.SYMBOLS) else changed.withLayout(LayoutType.SYMBOLS, set.symbolsLayout)
    if (set.popupOrder != null) changed = changed.without(ExtraValue.POPUP_ORDER)
    if (changed != keyboard) helium314.keyboard.latin.utils.SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, changed, ctx)
}

/** Saved Layout [layoutName] selected in a popup set's update dialog: its copy of the set becomes [set]'s. */
internal fun updateSetInLayout(real: SharedPreferences, layoutName: String, set: KeyPopupOverrides.UserSet) {
    val presets = helium314.keyboard.settings.LayoutPresets.load(real)
    helium314.keyboard.settings.LayoutPresets.save(real, presets.map { p ->
        if (p.name != layoutName) p
        else {
            val o = org.json.JSONObject().also { j -> set.overrides.forEach { (k, v) -> j.put(k, org.json.JSONArray(v)) } }
            fun kept(field: String, value: Any?) = if (field in set.atDefault) null else value
            helium314.keyboard.settings.LayoutPresets.Preset(p.name, p.values + mapOf(
                KeyPopupOverrides.PREF to o.toString(),
                Settings.PREF_SYMBOL_POPUP_MAP to kept(KeyPopupOverrides.MAP, set.symbolMap),
                Settings.PREF_POPUP_KEYS_ORDER to kept(KeyPopupOverrides.ORDER, set.popupOrder),
                "morePopups" to kept(KeyPopupOverrides.ACCENTS, set.morePopups),
                "layout:" + LayoutType.SYMBOLS.name to set.symbolsLayout,
            ), p.builtIn, p.remembered + listOfNotNull(
                set.symbolMap?.takeIf { KeyPopupOverrides.MAP in set.atDefault }?.let { Settings.PREF_SYMBOL_POPUP_MAP to it },
                set.popupOrder?.takeIf { KeyPopupOverrides.ORDER in set.atDefault }?.let { Settings.PREF_POPUP_KEYS_ORDER to it }).toMap())
        }
    })
}

/** Customize popups' Save (the tick, or "Save and exit"): the change stays, and Layout & Typing (the screen it was
 *  opened from) still holds it; its own Save or Discard decides it, with everything else done there (owner 2026-10-11,
 *  option 1: "every action is held until the screen's Save; Discard undoes it everywhere it reached"). Until then this
 *  rebased Layout & Typing's snapshot of the popup sets too, which this screen no longer writes: a set deleted there
 *  stayed deleted after its Discard, while the saved Layouts got its name back (review C2). */
internal fun customizePopupsSaved() {
    helium314.keyboard.latin.utils.SettingsEventLog.log("Customize popups saved: held until Layout & Typing's Save")
    helium314.keyboard.settings.LayoutDraft.subScreenSavedOpen(helium314.keyboard.settings.LayoutDraft.SubScreen.POPUPS) // (named in its Discard question)
}

internal fun deletePopupSet(ctx: Context, name: String) {
    val real = ctx.realPrefs()
    helium314.keyboard.settings.CustomLabels.detach(real, KeyPopupOverrides.PREF_SELECTED_SET, name,
        helium314.keyboard.settings.CustomLabels.Kind.POPUPS) // (keyboards keep their popups: Custom)
    KeyPopupOverrides.saveSets(real, KeyPopupOverrides.loadSets(real).filter { it.name != name })
    KeyboardProfiles.replaceValueEverywhere(real, KeyPopupOverrides.PREF_SELECTED_SET, name, null)
    helium314.keyboard.settings.LayoutPresets.onPopupSetRenamed(real, name, null) // (saved Layouts keep the popups, not the name)
}

/** The presets the list offers [keyboard]: the built-ins, then the user's own [userSets]; what a set kept at its default
 *  follows today's ([takeNew], "Saved items take new defaults") or is applied as saved. */
internal fun popupPresets(keyboard: SettingsSubtype, userSets: List<KeyPopupOverrides.UserSet>, takeNew: Boolean = true): List<Preset> =
    listOf(
        // each built-in preset is a whole recipe: accents level, symbols page, symbol map, popup order (nothing is
        // kept from the one before; 2026-10-03)
        // Curmudgeon's own: every variant a key has, plus the symbol map (the default 2026-09-26 to 0.3.007 and 2026-10-07 to
        // 0.3.011; HeliBoard extra's since 2026-10-10)
        Preset(R.string.key_popups_preset_standard, POPUP_KEYS_ALL, null, symbolMap = Defaults.CURMUDGEON_SYMBOL_POPUP_MAP,
            popupOrder = POPUP_KEYS_ORDER_DEFAULT),
        // HeliBoard's own, the default since 0.3.008: its accents level, no symbol map, and its popup order (as HeliBoard ships it, checked
        // against upstream 415c45f1 of 2026-09-30); the hint is the first popup entry, as everywhere here
        Preset(R.string.key_popups_preset_heliboard, POPUP_KEYS_MAIN, null, symbolMap = "", popupOrder = POPUP_KEYS_ORDER_DEFAULT),
        // HeliBoard extra: HeliBoard's with every variant a key has (2026-10-06: "More accented letters" dropped; that
        // level stays in the accents setting)
        Preset(R.string.key_popups_preset_heliboard_packed, POPUP_KEYS_ALL, null, symbolMap = "", popupOrder = POPUP_KEYS_ORDER_DEFAULT),
    ).let { builtIn ->
        // the Arabic-script symbols page only makes sense for keyboards of that script
        if (keyboard.locale.script() == ScriptUtils.SCRIPT_ARABIC) builtIn + Preset(R.string.key_popups_preset_arabic, POPUP_KEYS_NORMAL, "symbols_arabic", symbolMap = "", popupOrder = POPUP_KEYS_ORDER_DEFAULT)
        else builtIn
    } + userSets.map { Preset(0, it.morePopups, it.symbolsLayout, it.name, it.overrides, symbolMap = it.symbolMap, popupOrder = it.popupOrder,
        follows = if (takeNew) it.atDefault else emptySet()) }

/** The symbols page a keyboard's popup preset chose: [layout] (null: none, the default page) and the preset's name
 *  ([presetName], null for the default one). */
internal data class PresetSymbols(val layout: String?, val presetName: String?)

/**
 * The symbols page [keyboard]'s popup preset chose ([prefs]: its settings view), the "Built-in" of its Symbols tab in
 * the JSON editor (owner 2026-10-13, JSON-S2): the user's set it chose, its page; else a built-in page it names (as a
 * preset sets it); else, a copy of its own, the built-in preset with a page whose recipe it has otherwise; else none.
 */
internal fun presetSymbols(ctx: Context, prefs: SharedPreferences, keyboard: SettingsSubtype): PresetSymbols {
    val sets = KeyPopupOverrides.loadSets(ctx.realPrefs())
    prefs.getString(KeyPopupOverrides.PREF_SELECTED_SET, null)?.let { name ->
        sets.firstOrNull { it.name == name }?.let { return PresetSymbols(it.symbolsLayout, it.name) } }
    val builtIns = popupPresets(keyboard, sets).filter { it.userName == null && it.symbolsLayout != null }
    fun named(layout: String) = builtIns.firstOrNull { it.symbolsLayout == layout }?.let { ctx.getString(it.name) }
    val now = keyboard.layoutName(LayoutType.SYMBOLS)
    if (now == null) return PresetSymbols(null, null)
    if (!helium314.keyboard.latin.utils.LayoutUtilsCustom.isCustomLayout(now)) return PresetSymbols(now, named(now))
    val preset = builtIns.firstOrNull { p ->
        currentPopupPreset(prefs, keyboard.withLayout(LayoutType.SYMBOLS, p.symbolsLayout!!), listOf(p), emptyMap()) != null }
    return PresetSymbols(preset?.symbolsLayout, preset?.let { ctx.getString(it.name) })
}

/** The presets row for [keyboard] ([prefs]: its settings view, [overrides] its own arrangement): the user's set it chose,
 *  else the built-in whose recipe it has; a keyboard with its own arrangement on a built-in's recipe (or one that never
 *  chose any) differs from that built-in, a recipe no built-in has from the default one: "Custom · from HeliBoard extra",
 *  and the built-in's plain name once it matches again; its set deleted: "Custom · from Mine" until it chooses one
 *  (review E2: the same rules as Themes and Layouts, [helium314.keyboard.settings.CustomLabels.row]). */
internal fun popupsRow(ctx: Context, prefs: SharedPreferences, keyboard: SettingsSubtype, presets: List<Preset>,
                       userSets: List<KeyPopupOverrides.UserSet>, overrides: Map<String, List<String>>, takeNew: Boolean
): helium314.keyboard.settings.CustomLabels.Row {
    val current = currentPopupPreset(prefs, keyboard, presets, overrides)
    val chosenSet = current?.userName?.let { name -> userSets.firstOrNull { it.name == name } }
    val builtIns = presets.filter { it.userName == null }
    val from = current
        ?: currentPopupPreset(prefs, keyboard, builtIns, emptyMap()) // (the recipe, its own arrangement on top)
        ?: builtIns.firstOrNull { it.morePopups == Defaults.PREF_MORE_POPUP_KEYS && it.symbolsLayout == null // the default
            && (it.symbolMap == null || it.symbolMap == Defaults.PREF_SYMBOL_POPUP_MAP)
            && (it.popupOrder == null || it.popupOrder == Defaults.PREF_POPUP_KEYS_ORDER) }
    val name = from?.let { it.userName ?: ctx.getString(it.name) }
    val kind = helium314.keyboard.settings.CustomLabels.Kind.POPUPS
    val row = helium314.keyboard.settings.CustomLabels.row(ctx, prefs, kind, KeyPopupOverrides.PREF_SELECTED_SET, name) {
        current == null || (chosenSet != null && popupSetDiffers(prefs, keyboard, chosenSet, overrides, takeNew)) }
    if (name != null || row.custom || current != null) return row
    // (no built-in to name, none being the default: plain "Custom")
    helium314.keyboard.settings.CustomLabels.mark(prefs, kind)
    return helium314.keyboard.settings.CustomLabels.Row(helium314.keyboard.settings.CustomLabels.text(ctx, null), true)
}

/** The preset the presets row names for [keyboard] (settings [prefs], its own arrangement [overrides]): its chosen set
 *  of the user's own; else a built-in whose recipe it has, when it has no arrangement of its own (2026-10-08: a keyboard
 *  with its own popups and no set showed a built-in's name); null = "Custom". */
internal fun currentPopupPreset(prefs: SharedPreferences, keyboard: SettingsSubtype, presets: List<Preset>,
                                overrides: Map<String, List<String>>): Preset? {
    val selectedSet = prefs.getString(KeyPopupOverrides.PREF_SELECTED_SET, null)
    val accentsValue = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)
        ?: prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)!!
    val symbolsLayout = keyboard.layoutName(LayoutType.SYMBOLS)
    val symbolMap = prefs.getString(Settings.PREF_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP)!!
    val popupOrder = keyboard.getExtraValueOf(ExtraValue.POPUP_ORDER) ?: prefs.getString(Settings.PREF_POPUP_KEYS_ORDER, Defaults.PREF_POPUP_KEYS_ORDER)!!
    return presets.firstOrNull { it.userName != null && it.userName == selectedSet }
        ?: presets.firstOrNull { it.userName == null && overrides.isEmpty() && it.morePopups == accentsValue && it.symbolsLayout == symbolsLayout
            && (it.symbolMap == null || it.symbolMap == symbolMap) && (it.popupOrder == null || it.popupOrder == popupOrder) }
        // none: an arrangement no preset describes (e.g. accents "more" with the symbol map; review 2026-10-06: it said "Curmudgeon")
}

/** A row that opens another screen: title, summary, arrow; the Preferences screen's row style. */
@Composable
fun NavRow(title: String, summary: String? = null, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = ROW_HEIGHT).padding(vertical = 4.dp).padding(start = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (helium314.keyboard.settings.preferences.LocalPendingChange.current) androidx.compose.ui.text.font.FontStyle.Italic else null)
            if (summary != null)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NextScreenIcon()
    }
}

/**
 * Every key's long-press popups, a tab per key group (letters and digits, the keys that change with the text field,
 * the symbol pages, the number pad); each key folds open to its popups, switchable and draggable. A change stays on the
 * keyboard (2026-10-11): the set it chose changes only through its save icon in the presets list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizePopupsScreen(keyboard: SettingsSubtype, onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    // what is there as the screen opens: what it changes from here is kept by its Save when the app is left (owner
    // 2026-10-11, S2: "yes let's keep saved subscreen changes")
    rememberSaveable { helium314.keyboard.settings.LayoutDraft.subScreenOpenedOpen(ctx); true }
    var generation by remember { mutableIntStateOf(0) }
    val groups = remember(keyboard, generation) { keysWithPopups(ctx, keyboard) }
    val overrides = remember(generation) { KeyPopupOverrides.load(prefs) }
    val unfolded = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) { mutableStateListOf<String>() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // a change stays on the keyboard (owner 2026-10-11: a saved item changes only when deliberately updated, its save
    // icon in the presets list): the keyboard shows "Custom · from <set>" until then
    fun applyChange(overrideKey: String, labels: List<String>?) {
        val all = overrides.toMutableMap().also { if (labels == null) it.remove(overrideKey) else it[overrideKey] = labels }
        KeyPopupOverrides.save(prefs, all)
        generation++
        reloadPreview()
    }
    val tryIt = remember { TryItState() }
    // Keep / Discard like the other screens: every change shows at once (the preview), the cross puts back what was
    // there when the screen opened (the arrangement, the chosen set, the saved sets), the tick keeps it
    val real = ctx.realPrefs()
    fun now() = arrayListOf(prefs.getString(KeyPopupOverrides.PREF, null), prefs.getString(KeyPopupOverrides.PREF_SELECTED_SET, null),
        real.getString(KeyPopupOverrides.PREF_SETS, null))
    var opened by rememberSaveable { mutableStateOf(now()) }
    val changed = remember(generation) { now() } != opened
    fun putBack() {
        prefs.edit().apply {
            if (opened[0] == null) remove(KeyPopupOverrides.PREF) else putString(KeyPopupOverrides.PREF, opened[0])
            if (opened[1] == null) remove(KeyPopupOverrides.PREF_SELECTED_SET) else putString(KeyPopupOverrides.PREF_SELECTED_SET, opened[1])
        }.apply()
        real.edit().apply { if (opened[2] == null) remove(KeyPopupOverrides.PREF_SETS) else putString(KeyPopupOverrides.PREF_SETS, opened[2]) }.apply()
        generation++
        reloadPreview()
    }
    // leaving the settings with the screen open undoes its unsaved changes (written as made: the preview), whether Layout
    // & typing's are kept or undone then (owner 2026-10-14: "Sub-items should still discard as per usual?"); what its
    // Save kept stays (its tick: the new starting point)
    helium314.keyboard.settings.UndoUnsavedOnLeave("Customize popups") { (now() != opened).also { if (it) putBack() } }
    var askReject by rememberSaveable { mutableStateOf(false) }
    var askAccept by rememberSaveable { mutableStateOf(false) }
    var askLeave by rememberSaveable { mutableStateOf(false) }
    fun leave() { if (changed) askLeave = true else onClickBack() }
    androidx.activity.compose.BackHandler(enabled = changed) { leave() }
    if (askReject) helium314.keyboard.settings.dialogs.DiscardChangesDialog({ askReject = false }) { putBack() }
    // kept here (this screen's cross no longer puts it back); Layout & Typing's Save or Discard decides it (owner
    // 2026-10-11, see customizePopupsSaved)
    fun save() {
        customizePopupsSaved()
        opened = now()
    }
    if (askAccept) helium314.keyboard.settings.dialogs.SaveChangesDialog({ askAccept = false }) { save() }
    if (askLeave) helium314.keyboard.settings.dialogs.UnsavedChangesDialog(
        onKeepWorking = { askLeave = false },
        onDiscardAndExit = { askLeave = false; putBack(); onClickBack() },
        onSaveAndExit = { askLeave = false; save(); onClickBack() },
    )
    // its search: the keys of every tab by their names, as the colors list filters its colors (owner 2026-10-13,
    // SEARCH-SCOPE: "Every screen should have a search bar"; before, none). Back closes it first
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }
    fun closeSearch() { showSearch = false; searchText = androidx.compose.ui.text.input.TextFieldValue() }
    androidx.activity.compose.BackHandler(enabled = showSearch) { closeSearch() }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { Column {
            TopAppBar(title = { Text(stringResource(R.string.key_popups_full)) },
                navigationIcon = { BackButton { if (showSearch) closeSearch() else leave() } },
                actions = {
                    if (changed) {
                        IconButton({ askReject = true }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.appearance_reject)) }
                        IconButton({ askAccept = true }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.appearance_accept)) }
                    }
                    IconButton({ if (showSearch) closeSearch() else showSearch = true }) { helium314.keyboard.latin.utils.SearchIcon() }
                })
            helium314.keyboard.settings.ExpandableSearchField(
                expanded = showSearch,
                onDismiss = ::closeSearch,
                search = searchText,
                onSearchChange = { searchText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } },
        bottomBar = { TryItBar(keyboard, tryIt) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                groups.forEachIndexed { i, (titleId, keys) ->
                    val changed = keys.any { overrides.containsKey(it.overrideKey) }
                    Tab(selected = i == tab, onClick = { tab = i }, text = { Text(stringResource(titleId) + if (changed) " •" else "") })
                }
            }
            // the tab's keys; searching, the keys of every tab whose name matches
            val rows = popupSearchRows(groups, tab, searchText.text) { it.title }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                for ((groupTitle, keyInfo) in rows) key(groupTitle, keyInfo.overrideKey) {
                    val id = "$groupTitle:${keyInfo.overrideKey}"
                    val override = overrides[keyInfo.overrideKey]
                    val summary = (override ?: keyInfo.popups).joinToString(" ")
                    FoldRow(
                        title = keyInfo.title,
                        subtitle = summary.ifEmpty { stringResource(R.string.key_popups_none) },
                        unfolded = id in unfolded,
                        changed = override != null,
                        modifier = Modifier.padding(start = 10.dp),
                    ) { if (id in unfolded) unfolded.remove(id) else unfolded.add(id) }
                    if (id in unfolded)
                        PopupEditor(
                            allPopups = (keyInfo.pool + override.orEmpty()).distinct(), // the user's own additions too
                            enabledInOrder = override ?: keyInfo.popups,
                            onChanged = { applyChange(keyInfo.overrideKey, it) },
                            onReset = { applyChange(keyInfo.overrideKey, null) },
                            modifier = Modifier.padding(start = 22.dp),
                        )
                }
            }
        }
    }
}

/** The rows Customize popups lists: with no [search], those of tab [tab] of [groups] (title id to its keys); else the
 *  keys of every tab whose name ([title]) has a word starting with it, as the colors list matches (owner 2026-10-13,
 *  SEARCH-SCOPE). Each with its tab's title. */
internal fun <K> popupSearchRows(groups: List<Pair<Int, List<K>>>, tab: Int, search: String, title: (K) -> String): List<Pair<Int, K>> {
    val term = search.trim()
    if (term.isEmpty()) return groups[tab.coerceAtMost(groups.lastIndex)].let { (t, keys) -> keys.map { t to it } }
    return groups.flatMap { (t, keys) -> keys.map { t to it } }
        // (words split on brackets too: "(Web address)" is found by "web", round-7 review 20261013_L9_3; the whole title
        // too, so the "(" key, whose title is all bracket, is still found by "(")
        .filter { (_, k) -> title(k).let { t -> listOf(t) + t.split(" ", "_", "(", ")") }.any { it.startsWith(term, true) } }
}

/** A text field to try the keyboard being edited: focusing it opens that keyboard, changes rebuild it live. */
enum class TryItMode { TEXT, NUMBER, PHONE, EMOJI, CLIPBOARD }

/** What the try-it field asks the keyboard for; items on the screen set it (e.g. the number pad item) and focus the field. */
class TryItState {
    var mode by mutableStateOf(TryItMode.TEXT)
    val focusRequester = FocusRequester()
    /** False while this keyboard isn't the one selected in Android ([helium314.keyboard.settings.LocalImeSelected], set
     *  by [TryItBar]): nothing brings a keyboard up then, the field's tap offers the switch instead (owner 2026-10-14). */
    var keyboardAllowed = true
    fun show(mode: TryItMode, people: Boolean = false) {
        // the one gate: every summon (the screens' dialogs and switches through PreviewKeyboard, the mode buttons, the
        // items that ask for a pad) comes here
        if (!keyboardAllowed) return
        this.mode = mode
        // emoji / clipboard: the keyboard is told before the field's focus starts it, so it opens on that panel
        if (mode == TryItMode.EMOJI || mode == TryItMode.CLIPBOARD)
            runCatching { KeyboardSwitcher.getInstance().openPanelOnStart(mode == TryItMode.EMOJI, people) }
        runCatching { focusRequester.requestFocus() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TryItBar(keyboard: SettingsSubtype, state: TryItState, onFocus: (Boolean) -> Unit = {}, onUsed: () -> Unit = {}) {
    // the emoji tab: a text field whose keyboard is switched to the emoji panel once it is up; leaving the tab
    // switches back to the letters
    var wasEmoji by remember { mutableStateOf(false) }
    LaunchedEffect(state.mode) {
        if (state.mode == TryItMode.EMOJI || state.mode == TryItMode.CLIPBOARD) {
            wasEmoji = true
            // the keyboard opens straight on the panel when it starts, or switches now if it is already up
            // (the note for the keyboard is left by TryItState.show)
        } else if (wasEmoji) {
            wasEmoji = false
            runCatching { KeyboardSwitcher.getInstance().setAlphabetKeyboard() }
        }
    }
    var tryText by remember { mutableStateOf("") }
    // another keyboard selected in Android: no keyboard comes up here (it would be that one, not this), the field says
    // to switch and a tap on it opens Android's keyboard picker, as the bar on top does; back with this one, all as before
    val selected = helium314.keyboard.settings.LocalImeSelected.current
    state.keyboardAllowed = selected
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val ctx = LocalContext.current
    LaunchedEffect(selected) { if (!selected) focusManager.clearFocus(force = true) }
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            if (!selected) androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                // (the field as it looks, never focused; the box over it takes the tap)
                OutlinedTextField(
                    value = "", onValueChange = { }, readOnly = true, singleLine = true,
                    label = { Text(stringResource(R.string.try_it_switch_to_preview), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false },
                )
                androidx.compose.foundation.layout.Box(Modifier.matchParentSize().clickable { showKeyboardPicker(ctx) })
            } else {
                // a classic EditText in the outlined field's dress, not a Compose text field: Compose's fields hand the
                // keyboard plain text, without the suggestion span a pick leaves in the word, so the cursor back in हां
                // (picked from haan) got a strip computed from हां on a Latin keyboard. The EditText keeps the span, as
                // the text boxes of most apps do, and the field shows the keyboard as those apps see it (owner
                // 2026-10-24: "let's do edittext?")
                val currentOnFocus by rememberUpdatedState(onFocus)
                val currentOnUsed by rememberUpdatedState(onUsed)
                val interaction = remember { MutableInteractionSource() }
                val colors = OutlinedTextFieldDefaults.colors()
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val textSize = MaterialTheme.typography.bodyLarge.fontSize.value
                val inputType = when (state.mode) {
                    TryItMode.NUMBER -> InputType.TYPE_CLASS_NUMBER
                    TryItMode.PHONE -> InputType.TYPE_CLASS_PHONE
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                }
                var editText by remember { mutableStateOf<EditText?>(null) }
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
                        // a summon (TryItState.show) asks the Compose side for the focus; it goes on to the view
                        .focusRequester(state.focusRequester)
                        .onFocusChanged { if (it.isFocused) editText?.requestFocus() }
                ) {
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = tryText,
                        innerTextField = {
                            AndroidView(
                                factory = { context ->
                                    // (no spell checker on it: its red underline and popup on a Devanagari word
                                    // got in the way of the keyboard's own strip; owner 2026-10-24: "ew, popup is
                                    // really bad here. Can disable spell check on try it")
                                    object : EditText(context) {
                                        override fun isSuggestionsEnabled() = false
                                    }.apply {
                                        background = null
                                        setPadding(0, 0, 0, 0)
                                        setSingleLine(true)
                                        imeOptions = EditorInfo.IME_ACTION_DONE
                                        setTextColor(textColor)
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                                        // Enter is part of trying the keyboard (e.g. "back to ABC after enter"): it must
                                        // not close it, which a one-line field does by default
                                        setOnEditorActionListener { _, _, _ -> true }
                                        doAfterTextChanged { tryText = it?.toString() ?: ""; currentOnUsed() } // typing in it: the preview is in use
                                        var focus: FocusInteraction.Focus? = null
                                        setOnFocusChangeListener { view, hasFocus ->
                                            currentOnFocus(hasFocus)
                                            if (hasFocus) {
                                                showKeyboardForPreview(keyboard)
                                                // the label floats as it does over a focused Compose field
                                                focus = FocusInteraction.Focus().also { interaction.tryEmit(it) }
                                                // a view focused by code shows no keyboard by itself (a tapped one does)
                                                (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, 0)
                                            } else focus?.let { interaction.tryEmit(FocusInteraction.Unfocus(it)); focus = null }
                                        }
                                        editText = this
                                    }
                                },
                                update = { view ->
                                    if (view.inputType != inputType) {
                                        view.inputType = inputType
                                        // the keyboard follows the new type at once (the number pad on 123)
                                        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(view)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        enabled = true,
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        interactionSource = interaction,
                        label = { Text(stringResource(R.string.key_popups_try), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = colors,
                        container = { OutlinedTextFieldDefaults.Container(enabled = true, isError = false, interactionSource = interaction, colors = colors) }
                    )
                }
            }
            // which keyboard the preview shows: letters, the number pad, the phone pad, the emoji panel, the clipboard;
            // no check mark on the selected one (its background shows it), tight padding and at most 48 dp each, so five
            // fit and still leave the field room on a 411 dp phone (Material's 58 dp minimum squeezed it to a column);
            // none while another keyboard is selected, the field takes the whole width (owner 2026-10-14: "remove the
            // 5 buttons in the case")
            if (selected) SingleChoiceSegmentedButtonRow(Modifier.padding(start = 8.dp)) {
                val modes = listOf(TryItMode.TEXT to "ABC", TryItMode.NUMBER to "123", TryItMode.PHONE to "\u260E",
                    TryItMode.EMOJI to "\uD83D\uDE00", TryItMode.CLIPBOARD to "\uD83D\uDCCB")
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { state.show(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.widthIn(max = 48.dp),
                        icon = {},
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
        }
    }
}

/** Opens Android's keyboard picker, as the "not your current keyboard" bar's Switch does (tests replace it). */
internal var showKeyboardPicker: (Context) -> Unit = { ctx ->
    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
}

/** A built-in set ([name] resource) or one the user saved ([userName], with its per-key arrangement). */
/** HeliBoard's popup order (language accents, numbers, symbols, layout, other languages, all on), as it ships. */
// (HeliBoard's popup order is POPUP_KEYS_ORDER_DEFAULT itself, checked against upstream 415c45f1: one copy, review 2026-10-06)

internal data class Preset(val name: Int, val morePopups: String, val symbolsLayout: String?, // (data: equal by content, the list rebuilds them on every change)
                     val userName: String? = null, val overrides: Map<String, List<String>>? = null,
                     val symbolMap: String? = null, // the letter -> symbols map it sets; null = leaves the map as it is, "" = none (HeliBoard)
                     val popupOrder: String? = null, // the popup order it sets; null = leaves the order as it is
                     val follows: Set<String> = emptySet()) // of UserSet's ACCENTS / MAP / ORDER: set back to the default instead

private class KeyInfo(val label: String, val title: String, val popups: List<String>, val pool: List<String>, val overrideKey: String = label)

/** Builds the keyboard for plain text, web address and email fields and collects each key's popups. */
private fun keysWithPopups(ctx: Context, keyboard: SettingsSubtype): List<Pair<Int, List<KeyInfo>>> {
    val prefs = ctx.prefs()
    val width = ResourceUtils.getKeyboardWidth(ctx, Settings.getValues())
    val numberRow = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW)
    fun keysFor(inputType: Int, subtype: SettingsSubtype = keyboard, element: Int = KeyboardId.ELEMENT_ALPHABET): List<Key> {
        val editorInfo = EditorInfo().apply { this.inputType = inputType }
        val layoutSet = KeyboardLayoutSet.Builder(ctx, editorInfo)
            .setSubtype(RichInputMethodSubtype.get(subtype.toAdditionalSubtype()))
            .setKeyboardGeometry(width, width) // height doesn't matter for the popups
            .setNumberRowEnabled(numberRow)
            .build()
        return layoutSet.getKeyboard(element).sortedKeys.filter { it.code > 0 && it.label != null }
    }
    fun popupsOf(key: Key) = key.popupKeys?.mapNotNull { it.mLabel ?: it.mOutputText }?.filter { it.isNotBlank() }.orEmpty()

    val plain = keysFor(InputType.TYPE_CLASS_TEXT)
    // the full pool: the keyboard built with every accented letter the languages know
    val poolByLabel = keysFor(InputType.TYPE_CLASS_TEXT, keyboard.with(ExtraValue.MORE_POPUPS, POPUP_KEYS_ALL))
        .associate { it.label!! to popupsOf(it) }
    val letters = plain.filter { Character.isLetterOrDigit(it.code) }.map {
        val defaults = popupsOf(it)
        KeyInfo(it.label!!, it.label!!, defaults, (defaults + poolByLabel[it.label!!].orEmpty()).distinct())
    }
    // the other keys, in every text field variant they show up in
    val contextual = LinkedHashMap<String, KeyInfo>()
    val variants = listOf(
        R.string.key_popups_variant_text to InputType.TYPE_CLASS_TEXT,
        R.string.key_popups_variant_url to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        R.string.key_popups_variant_email to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
    )
    for ((variantName, inputType) in variants) {
        for (key in keysFor(inputType).filter { !Character.isLetterOrDigit(it.code) }) {
            val label = key.label!!
            if (label in contextual) continue
            val popups = popupsOf(key)
            if (popups.isEmpty()) continue
            contextual[label] = KeyInfo(label, "$label  (${ctx.getString(variantName)})", popups, popups)
        }
    }
    // the number pad (numeric fields): its operator keys have popups; kept apart from same-looking keys elsewhere
    val numpad = keysFor(InputType.TYPE_CLASS_NUMBER, element = KeyboardId.ELEMENT_NUMPAD)
        .map { KeyInfo(it.label!!, it.label!!, popupsOf(it), popupsOf(it), KeyPopupOverrides.overrideKey(KeyboardId.ELEMENT_NUMPAD, it.label!!)) }
        .filter { it.popups.isNotEmpty() }
    // the two symbol pages: every key with popups, kept apart from same-looking keys of the letter page
    fun symbolPage(element: Int) = keysFor(InputType.TYPE_CLASS_TEXT, element = element)
        .filter { !Character.isLetterOrDigit(it.code) }
        .map { KeyInfo(it.label!!, it.label!!, popupsOf(it), popupsOf(it), KeyPopupOverrides.overrideKey(element, it.label!!)) }
    return listOf(
        R.string.key_popups_letters to letters,
        R.string.key_popups_contextual to contextual.values.toList(),
        R.string.key_popups_symbols to symbolPage(KeyboardId.ELEMENT_SYMBOLS),
        R.string.key_popups_more_symbols to symbolPage(KeyboardId.ELEMENT_SYMBOLS_SHIFTED),
        R.string.key_popups_numpad to numpad,
    )
}

@Composable
private fun FoldRow(
    title: String,
    unfolded: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    changed: Boolean = false,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clickable { onClick() }.heightIn(min = ROW_HEIGHT).padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title + if (changed) " •" else "", style = style)
            if (subtitle != null)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.rotate(if (unfolded) 90f else -90f))
    }
}

/** The popups of one key: switch on/off, drag the grip to reorder; the first enabled one is the hint. */
@Composable
private fun PopupEditor(
    allPopups: List<String>,
    enabledInOrder: List<String>,
    onChanged: (List<String>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // enabled ones in their order, then the disabled ones
    val items = remember(allPopups, enabledInOrder) {
        mutableStateListOf(*(enabledInOrder.filter { it in allPopups } + allPopups.filter { it !in enabledInOrder }).toTypedArray())
    }
    val enabled = remember(enabledInOrder) { mutableStateListOf(*enabledInOrder.toTypedArray()) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    var dragging: String? by remember { mutableStateOf(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    fun commit() = onChanged(items.filter { it in enabled })
    Column(modifier) {
        for (popup in items) key(popup) {
            val isDragged = dragging == popup
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { heights[popup] = it.height }
                    .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) }
                    .zIndex(if (isDragged) 1f else 0f)
            ) {
                Text("≡", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp).pointerInput(popup, items) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dragging = popup
                            dragOffset = 0f
                            drag(down.id) { change ->
                                val dy = change.positionChange().y
                                change.consume()
                                dragOffset += dy
                                val index = items.indexOf(popup)
                                if (dy > 0 && index < items.lastIndex) {
                                    val below = heights[items[index + 1]] ?: 0
                                    if (dragOffset > below / 2f) { items.add(index + 1, items.removeAt(index)); dragOffset -= below }
                                } else if (dy < 0 && index > 0) {
                                    val above = heights[items[index - 1]] ?: 0
                                    if (dragOffset < -above / 2f) { items.add(index - 1, items.removeAt(index)); dragOffset += above }
                                }
                            }
                            dragging = null
                            dragOffset = 0f
                            commit()
                        }
                    })
                Text(popup, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = popup in enabled, onCheckedChange = { on ->
                    if (on) enabled.add(popup) else enabled.remove(popup)
                    commit()
                })
            }
        }
        // anything the pool lacks
        var custom by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = custom, onValueChange = { custom = it }, singleLine = true,
                label = { Text(stringResource(R.string.key_popups_add)) },
                modifier = Modifier.weight(1f)
            )
            TextButton(enabled = custom.isNotBlank() && custom.trim() !in items, onClick = {
                val value = custom.trim()
                items.add(value); enabled.add(value); custom = ""
                commit()
            }) { Text(stringResource(R.string.key_popups_add_button)) }
            TextButton(onClick = onReset) { Text(stringResource(R.string.button_default)) }
        }
    }
}

/** The keyboard shown for the try-it field is the one being edited: switch to it if another is in use. */
private fun showKeyboardForPreview(keyboard: SettingsSubtype) {
    if (!RichInputMethodManager.isInitialized()) return
    val subtype = keyboard.toAdditionalSubtype()
    if (RichInputMethodManager.getInstance().currentSubtype.rawSubtype != subtype)
        KeyboardSwitcher.getInstance().switchToSubtype(subtype)
}

/** Rebuild the live keyboard so the hint and long-press popups show the change immediately. */
private fun reloadPreview() {
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

/** The row opening the Layout files editor: every secondary layout in one editor (a tab each), one file. */
@Composable
fun LayoutFilesRow(keyboard: SettingsSubtype) {
    val ctx = LocalContext.current
    NavRow(stringResource(R.string.layout_files)) {
        SettingsDestination.navigateTo(SettingsDestination.withKeyboard(SettingsDestination.LayoutFiles, keyboard), ctx)
    }
}

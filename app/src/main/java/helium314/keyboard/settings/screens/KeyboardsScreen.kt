// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import helium314.keyboard.latin.utils.getActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsMode
import androidx.compose.runtime.mutableIntStateOf
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.realPrefs
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import kotlin.math.roundToInt
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference

/**
 * The top level of the settings: one entry per keyboard, each opening that keyboard's full settings menu
 * ([MainSettingsScreen]), plus adding keyboards and the few things that are not per keyboard.
 */
@Composable
fun KeyboardsScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    var showAddKeyboard by remember { mutableStateOf(false) }
    var keyboardToCopy: SettingsSubtype? by remember { mutableStateOf(null) } // asks: its own settings, or the common ones?
    var keyboardToDelete: SettingsSubtype? by remember { mutableStateOf(null) }
    var keyboardMenu: SettingsSubtype? by remember { mutableStateOf(null) } // press and hold: Rename, Delete
    var keyboardToRename: SettingsSubtype? by remember { mutableStateOf(null) }
    var newKeyboard: SettingsSubtype? by remember { mutableStateOf(null) } // added, asks where its settings come from
    val real = ctx.realPrefs()
    // the switch is on App settings > Per keyboard now: read again whenever a setting changes (coming back from there)
    val changed = (ctx.getActivity() as? helium314.keyboard.settings.SettingsActivity)?.prefChanged?.collectAsState()
    @Suppress("UNUSED_EXPRESSION") changed?.value
    val separate = KeyboardProfiles.isSeparate(real)
    // the menus all keyboards share (separate settings): shown once below the keyboards, not under each
    fun sharedMenu(group: KeyboardProfiles.Group) = KeyboardProfiles.isShared(real, group)
    val expanded = remember { mutableStateListOf<SettingsSubtype>() } // several keyboards can be unfolded at once
    // the settings screens edit the shared set unless a keyboard's own section was entered; with separate settings
    // this screen's search edits the keyboard in use (the shared set is read by no keyboard then)
    KeyboardProfiles.editingId = if (KeyboardProfiles.isSeparate(real))
        KeyboardProfiles.idFor(real, SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype()) else KeyboardProfiles.SHARED
    var generation by remember { mutableIntStateOf(0) } // re-read the keyboards after a delete
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        // (the separate-settings switch was a bar at the bottom here: now on App settings > Per keyboard)
        Scaffold(contentWindowInsets = WindowInsets(0)) { innerPadding ->
            @Suppress("UNUSED_EXPRESSION") generation
            val enabled = SubtypeSettings.getEnabledSubtypes(true)
            // drag-to-reorder by the grip: the dragged row follows the finger and swaps with its neighbours
            val order = remember(generation) { mutableStateListOf(*enabled.map { it.toSettingsSubtype() }.toTypedArray()) }
            val rowHeights = remember { mutableStateMapOf<SettingsSubtype, Int>() }
            var dragging: SettingsSubtype? by remember { mutableStateOf(null) }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState(), enabled = dragging == null)
                    .then(Modifier.padding(innerPadding))
            ) {
                var dragOffset by remember { mutableFloatStateOf(0f) }
                fun persistOrder() {
                    val value = SubtypeSettings.createPrefSubtypes(order)
                    ctx.prefs().edit { putString(Settings.PREF_ENABLED_SUBTYPES, value) }
                    SubtypeSettings.reloadEnabledSubtypes(ctx)
                    generation++
                }
                HorizontalDivider() // under the title bar, above the first keyboard (like the other screens' first group)
                for (keyboard in order) key(keyboard) { // stable identity: a reorder moves the block instead of recreating it (which killed the drag)
                    val subtype = keyboard.toAdditionalSubtype()
                    val isDragged = dragging == keyboard
                    // tap: languages & layout of the keyboard (with separate settings: unfold all its sections);
                    // press and hold: delete it (while another remains)
                    // a single keyboard has nothing to fold: its sections are listed below like the shared ones
                    val folding = separate && enabled.size > 1
                    val isExpanded = folding && keyboard in expanded
                    Column(
                        Modifier
                            .onSizeChanged { rowHeights[keyboard] = it.height } // the row with its unfolded sections
                            .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) }
                            .zIndex(if (isDragged) 1f else 0f)
                            // the picked-up block: lifted, opaque, so it visibly slides over the others
                            .then(if (isDragged) Modifier.shadow(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
                    ) {
                    Preference(
                        name = keyboardName(keyboard, ctx),
                        description = subtype.mainLayoutName()?.getStringResourceOrName("layout_", ctx) ?: "",
                        onClick = {
                            if (folding) { if (isExpanded) expanded.remove(keyboard) else expanded.add(keyboard) }
                            else {
                                if (separate) KeyboardProfiles.editingId = KeyboardProfiles.idFor(real, keyboard)
                                SettingsDestination.navigateTo(SettingsDestination.withKeyboard(SettingsDestination.Languages, keyboard))
                            }
                        },
                        icon = R.drawable.ic_settings_layout, // a keyboard (the globe is for its languages)
                        onLongClick = { keyboardMenu = keyboard },
                    ) {
                        if (order.size > 1)
                            Text("\u2261", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp).pointerInput(keyboard, order) { // rebuilt with the list, or the gesture keeps a stale one
                                    // the grip takes the press the moment the finger lands: no hold, and the row's
                                    // press-and-hold (delete) and the page scroll never see it
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        dragging = keyboard
                                        dragOffset = 0f
                                        drag(down.id) { change ->
                                            val dy = change.positionChange().y
                                            change.consume()
                                            dragOffset += dy
                                            val index = order.indexOf(keyboard)
                                            // moved past half of the neighbour: swap places and keep the block under the finger
                                            if (dy > 0 && index < order.lastIndex) {
                                                val below = rowHeights[order[index + 1]] ?: 0
                                                if (dragOffset > below / 2f) { order.add(index + 1, order.removeAt(index)); dragOffset -= below }
                                            } else if (dy < 0 && index > 0) {
                                                val above = rowHeights[order[index - 1]] ?: 0
                                                if (dragOffset < -above / 2f) { order.add(index - 1, order.removeAt(index)); dragOffset += above }
                                            }
                                        }
                                        dragging = null
                                        dragOffset = 0f
                                        persistOrder()
                                    }
                                })
                        if (folding) Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.rotate(if (isExpanded) 90f else -90f))
                        else NextScreenIcon()
                    }
                    if (isExpanded)
                        KeyboardSettingsEntries(keyboard, Modifier.padding(start = 24.dp), showAdvanced = false,
                            onEnter = { KeyboardProfiles.editingId = KeyboardProfiles.idFor(real, keyboard) },
                            // with separate settings only the menus each keyboard keeps to itself
                            groups = if (separate) { g -> !sharedMenu(g) } else null,
                            showRefine = separate && !sharedMenu(KeyboardProfiles.Group.REFINE))
                    }
                }
                Preference(
                    name = stringResource(R.string.add_keyboard),
                    onClick = { showAddKeyboard = true },
                    icon = R.drawable.ic_plus
                ) { NextScreenIcon() }
                // the sections shared by all keyboards
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (!separate)
                    KeyboardSettingsEntries(SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype(), showLanguages = false)
                else if (enabled.size == 1)
                    KeyboardSettingsEntries(enabled[0].toSettingsSubtype(), showLanguages = false, showAdvanced = false,
                        onEnter = { KeyboardProfiles.editingId = KeyboardProfiles.idFor(real, enabled[0].toSettingsSubtype()) })
                else
                    Text(
                        stringResource(R.string.separate_settings_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                // with separate settings: Advanced once, for all keyboards (its settings are app-wide)
                if (separate && enabled.size > 1) {
                    // the menus every keyboard shares, once (they edit one set for all)
                    val inUse = SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype()
                    KeyboardSettingsEntries(inUse, showLanguages = false, showAdvanced = false,
                        onEnter = { KeyboardProfiles.editingId = KeyboardProfiles.idFor(real, inUse) },
                        groups = { g -> sharedMenu(g) })
                }
                if (separate) AdvancedEntry(showRefine = sharedMenu(KeyboardProfiles.Group.REFINE))
                // (About: the last row of Advanced, 2026-10-04)
            }
        }
        val enabledNow = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
        keyboardMenu?.let { keyboard ->
            val rename = stringResource(R.string.rename_keyboard)
            val delete = stringResource(R.string.delete)
            ListPickerDialog(
                onDismissRequest = { keyboardMenu = null },
                onItemSelected = { item ->
                    keyboardMenu = null
                    if (item == rename) keyboardToRename = keyboard else keyboardToDelete = keyboard
                },
                title = { Text(keyboardName(keyboard, ctx)) },
                items = listOfNotNull(rename, delete.takeIf { enabledNow.size > 1 }), // (the last keyboard can't go)
                getItemName = { it },
                showRadioButtons = false,
            )
        }
        keyboardToRename?.let { keyboard ->
            helium314.keyboard.settings.dialogs.TextInputDialog(
                onDismissRequest = { keyboardToRename = null },
                onConfirmed = { name ->
                    val renamed = withKeyboardName(keyboard, name)
                    if (renamed != keyboard) {
                        SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, renamed, ctx) // its settings go with it
                        if (expanded.remove(keyboard)) expanded.add(renamed)
                        generation++
                    }
                },
                title = { Text(stringResource(R.string.rename_keyboard)) },
                initialText = customKeyboardName(keyboard) ?: "",
                // shows the languages' name, which an empty box gives back
                textInputLabel = { Text(keyboardName(keyboard.without(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_NAME), ctx)) },
                checkTextValid = { true },
            )
        }
        keyboardToDelete?.let { keyboard ->
            ConfirmationDialog(
                onDismissRequest = { keyboardToDelete = null },
                onConfirmed = {
                    if (keyboard.isAdditionalSubtype(ctx.prefs())) SubtypeUtilsAdditional.removeAdditionalSubtype(ctx, keyboard.toAdditionalSubtype())
                    SubtypeSettings.removeEnabledSubtype(ctx, keyboard.toAdditionalSubtype())
                    KeyboardProfiles.onKeyboardDeleted(real, keyboard)
                    keyboardToDelete = null
                    generation++
                },
                title = { Text(stringResource(R.string.delete_keyboard_title)) },
                content = { Text(stringResource(R.string.delete_keyboard_message, keyboardName(keyboard, ctx))) },
                confirmButtonText = stringResource(R.string.delete),
            )
        }
        newKeyboard?.let { added ->
            val defaults = stringResource(R.string.new_keyboard_default_settings)
            ListPickerDialog<Any>(
                onDismissRequest = { newKeyboard = null }, // (nothing added)
                onItemSelected = { from ->
                    addNewKeyboard(ctx, added, (from as? SettingsSubtype) ?: SettingsSubtype(Locale.ROOT, DEFAULTS_ONLY))
                    newKeyboard = null
                    generation++
                },
                title = { Text(stringResource(R.string.new_keyboard_copy_settings, keyboardName(added, ctx))) },
                items = enabledNow + defaults,
                getItemName = { if (it is SettingsSubtype) keyboardName(it, ctx) else it as String },
                showRadioButtons = false,
            )
        }
        if (showAddKeyboard)
            ListPickerDialog<Any>(
                onDismissRequest = { showAddKeyboard = false },
                onItemSelected = { item ->
                    if (item is CopyOf) {
                        showAddKeyboard = false
                        // with one set of settings for all there is nothing to choose: the copy has them too
                        if (separate) keyboardToCopy = item.keyboard
                        else { copyKeyboard(ctx, item.keyboard, withOwnSettings = false); generation++ }
                        return@ListPickerDialog
                    }
                    val locale = item as Locale
                    val plain = SubtypeUtilsAdditional.createDefaultSubtype(locale).toSettingsSubtype()
                    // that language's keyboard is in the list already: another one, numbered ("English 2"), so the
                    // listed one keeps its settings
                    val settingsSubtype = if (!enabledNow.contains(plain)) plain else nextNumbered(plain)
                    showAddKeyboard = false
                    // with its own settings, it asks which keyboard's settings to start from; else it has the shared ones
                    if (separate) newKeyboard = settingsSubtype
                    else { addNewKeyboard(ctx, settingsSubtype, null); generation++ } // stays here, it appears at the end
                },
                title = { Text(stringResource(R.string.add_keyboard)) },
                // copies of the keyboards first, then a new keyboard for any language
                items = enabledNow.map { CopyOf(it) } + SubtypeSettings.getAvailableSubtypeLocales().sortedBy { it.localizedDisplayName(ctx.resources) },
                getItemName = { if (it is CopyOf) stringResource(R.string.copy_of_keyboard, keyboardName(it.keyboard, ctx)) else (it as Locale).localizedDisplayName(ctx.resources) },
                showRadioButtons = false,
            )
        keyboardToCopy?.let { source ->
            ThreeButtonAlertDialog(
                onDismissRequest = { keyboardToCopy = null },
                onConfirmed = { copyKeyboard(ctx, source, withOwnSettings = true); generation++ },
                title = { Text(stringResource(R.string.copy_of_keyboard, keyboardName(source, ctx))) },
                content = { Text(stringResource(R.string.copy_keyboard_message)) },
                confirmButtonText = stringResource(R.string.copy_keyboard_with_settings),
                neutralButtonText = stringResource(R.string.copy_keyboard_common_settings),
                // (the neutral button doesn't close the dialog by itself: re-review 2026-10-07, every tap added a copy)
                onNeutral = { copyKeyboard(ctx, source, withOwnSettings = false); generation++; keyboardToCopy = null },
                confirmFirst = true,
            )
        }
    }
}

/** An Add keyboard entry: a copy of an existing keyboard. */
private class CopyOf(val keyboard: SettingsSubtype)

/**
 * Adds a copy of [source]: the same languages and layout, told apart by a number; with [withOwnSettings] it takes
 * [source]'s own settings (separate settings), otherwise it starts from the common ones.
 */
/** [source] with the lowest copy number ("English 2", 3, ...) no listed keyboard has. */
private fun nextNumbered(source: SettingsSubtype): SettingsSubtype {
    val base = source.without(ExtraValue.KEYBOARD_COPY)
    val taken = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
        .filter { it.without(ExtraValue.KEYBOARD_COPY) == base }
        .mapNotNull { it.getExtraValueOf(ExtraValue.KEYBOARD_COPY)?.toIntOrNull() }
    val number = generateSequence(2) { it + 1 }.first { it !in taken }
    return base.with(ExtraValue.KEYBOARD_COPY, number.toString())
}

private fun copyKeyboard(ctx: Context, source: SettingsSubtype, withOwnSettings: Boolean) {
    val real = ctx.realPrefs()
    val copy = LayoutUtilsCustom.withOwnPrivateLayouts(nextNumbered(source), ctx) // (its own unnamed keys, not the source's file)
    SubtypeUtilsAdditional.changeAdditionalSubtype(copy, copy, ctx) // registers it
    SubtypeSettings.addEnabledSubtype(ctx.prefs(), copy.toAdditionalSubtype())
    ownSetSourceFor(real, source, withOwnSettings)?.let { KeyboardProfiles.copy(real, it, KeyboardProfiles.idFor(real, copy)) }
}

/** The set a copied keyboard's own set starts from: the source's or the shared one; null with one set of settings for
 *  all keyboards (re-review 2026-10-07: a copy got a frozen own set then, which came alive, stale, once settings were
 *  made separate). */
internal fun ownSetSourceFor(real: android.content.SharedPreferences, source: SettingsSubtype, withOwnSettings: Boolean): Int? =
    if (!KeyboardProfiles.isSeparate(real)) null
    else if (withOwnSettings) KeyboardProfiles.idFor(real, source) else KeyboardProfiles.SHARED

private const val DEFAULTS_ONLY = "defaults only" // (a marker, never a keyboard)

/**
 * Adds [keyboard] to the list; with separate settings its own set starts as [from]'s (null: the keyboard in use's), or
 * at every default ([DEFAULTS_ONLY]). A source of another script keeps its popups to itself (they're for its letters):
 * the new keyboard has the default popups.
 */
private fun addNewKeyboard(ctx: Context, keyboard: SettingsSubtype, from: SettingsSubtype?) {
    val real = ctx.realPrefs()
    SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, keyboard, ctx) // registers it unless it equals a built-in one
    SubtypeSettings.addEnabledSubtype(ctx.prefs(), keyboard.toAdditionalSubtype())
    if (!KeyboardProfiles.isSeparate(real)) return
    val id = KeyboardProfiles.idFor(real, keyboard)
    if (from?.extraValues == DEFAULTS_ONLY) { KeyboardProfiles.write(real, id, emptyMap(), markDefaults = true); return }
    val source = from ?: SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype()
    KeyboardProfiles.copy(real, KeyboardProfiles.idFor(real, source), id)
    if (with(helium314.keyboard.latin.utils.ScriptUtils) { source.locale.script() != keyboard.locale.script() }) {
        val own = helium314.keyboard.latin.settings.ProfilePreferences(real) { id }
        own.edit().apply { listOf("key_popups", "key_popup_set_selected", Settings.PREF_SYMBOL_POPUP_MAP).forEach { remove(it) } }.apply()
    }
}

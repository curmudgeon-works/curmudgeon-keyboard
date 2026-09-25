// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

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
    onClickAbout: () -> Unit,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    var showAddKeyboard by remember { mutableStateOf(false) }
    var keyboardToDelete: SettingsSubtype? by remember { mutableStateOf(null) }
    val real = ctx.realPrefs()
    var separate by remember { mutableStateOf(KeyboardProfiles.isSeparate(real)) }
    var askEnable by remember { mutableStateOf(false) } // some keyboards have an older set: keep or reset?
    var askDisable by remember { mutableStateOf(false) } // which set becomes the shared one?
    val expanded = remember { mutableStateListOf<SettingsSubtype>() } // several keyboards can be unfolded at once
    // the settings screens edit the shared set unless a keyboard's own section was entered
    KeyboardProfiles.editingId = KeyboardProfiles.SHARED
    var generation by remember { mutableIntStateOf(0) } // re-read the keyboards after a delete
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        // the switch stays visible at the bottom, the list scrolls above it
        val toggleBar: @Composable () -> Unit = {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                    // off = one set of settings for every keyboard (the sections above); on = each keyboard its own
                    fun toggle(on: Boolean) {
                        // both ways ask first, saying what happens
                        if (on) askEnable = true else askDisable = true
                        KeyboardProfiles.refreshImeId(real)
                    }
                    Preference(
                        name = stringResource(R.string.separate_settings_per_keyboard),
                        description = stringResource(R.string.separate_settings_per_keyboard_summary),
                        onClick = { toggle(!separate) },
                        icon = R.drawable.ic_settings_preferences
                    ) { Switch(checked = separate, onCheckedChange = { toggle(it) }) }
                }
            }
        }
        Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = toggleBar) { innerPadding ->
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
                                SettingsDestination.navigateTo(SettingsDestination.Languages + keyboard.toPref())
                            }
                        },
                        icon = R.drawable.ic_settings_layout, // a keyboard (the globe is for its languages)
                        onLongClick = if (enabled.size > 1) ({ keyboardToDelete = keyboard }) else null,
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
                        KeyboardSettingsEntries(keyboard, Modifier.padding(start = 24.dp),
                            onEnter = { KeyboardProfiles.editingId = KeyboardProfiles.idFor(real, keyboard) })
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
                    KeyboardSettingsEntries(enabled[0].toSettingsSubtype(), showLanguages = false,
                        onEnter = { KeyboardProfiles.editingId = KeyboardProfiles.idFor(real, enabled[0].toSettingsSubtype()) })
                else
                    Text(
                        stringResource(R.string.separate_settings_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                Preference(
                    name = stringResource(R.string.settings_screen_about),
                    onClick = onClickAbout,
                    icon = R.drawable.ic_settings_about
                ) { NextScreenIcon() }
            }
        }
        val enabledNow = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
        if (askEnable)
            ConfirmationDialog(
                onDismissRequest = { askEnable = false },
                // each keyboard gets its saved settings back; one without starts from a copy of the common ones
                onConfirmed = { KeyboardProfiles.enable(real, enabledNow, keepExisting = true); KeyboardProfiles.refreshImeId(real); separate = true; askEnable = false; expanded.add(SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype()) },
                title = { Text(stringResource(R.string.separate_settings_per_keyboard)) },
                content = { Text(stringResource(R.string.separate_settings_enable_message)) },
                confirmButtonText = stringResource(R.string.separate_settings_turn_on),
                cancelButtonText = stringResource(R.string.separate_settings_keep_one),
                confirmFirst = true,
            )
        if (askDisable) {
            // the primary keyboard: the first in the list (and in the switching order)
            val primary = enabledNow.firstOrNull() ?: SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype()
            ConfirmationDialog(
                onDismissRequest = { askDisable = false },
                onConfirmed = { KeyboardProfiles.disable(real, primary); KeyboardProfiles.refreshImeId(real); separate = false; askDisable = false },
                title = { Text(stringResource(R.string.separate_settings_disable_title)) },
                content = { Text(stringResource(R.string.separate_settings_disable_message, keyboardName(primary, ctx))) },
                confirmButtonText = stringResource(R.string.separate_settings_use_primary),
                cancelButtonText = stringResource(R.string.separate_settings_keep_separate),
                confirmFirst = true,
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
        if (showAddKeyboard)
            ListPickerDialog(
                onDismissRequest = { showAddKeyboard = false },
                onItemSelected = { locale ->
                    val settingsSubtype = SubtypeUtilsAdditional.createDefaultSubtype(locale).toSettingsSubtype()
                    SubtypeUtilsAdditional.changeAdditionalSubtype(settingsSubtype, settingsSubtype, ctx) // registers it unless it equals a built-in one
                    SubtypeSettings.addEnabledSubtype(ctx.prefs(), settingsSubtype.toAdditionalSubtype())
                    if (separate) // a new keyboard starts as a copy of the one in use
                        KeyboardProfiles.copy(real, KeyboardProfiles.idFor(real, SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype()),
                            KeyboardProfiles.idFor(real, settingsSubtype))
                    showAddKeyboard = false
                    generation++ // stays on this screen, the new keyboard appears at the end of the list
                },
                title = { Text(stringResource(R.string.add_keyboard)) },
                items = SubtypeSettings.getAvailableSubtypeLocales().sortedBy { it.localizedDisplayName(ctx.resources) },
                getItemName = { it.localizedDisplayName(ctx.resources) },
                showRadioButtons = false,
            )
    }
}

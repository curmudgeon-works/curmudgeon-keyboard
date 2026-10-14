// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.dialogs.AddKeyboardDialog
import helium314.keyboard.settings.dialogs.StartSettings
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
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
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var keyboardToDelete: SettingsSubtype? by remember { mutableStateOf(null) }
    var keyboardMenu: SettingsSubtype? by remember { mutableStateOf(null) } // press and hold: Rename, Delete
    var keyboardToRename: SettingsSubtype? by remember { mutableStateOf(null) }
    var switchedOffOpen by remember { mutableStateOf(false) } // the Switched-off keyboards tile unfolded
    // a keyboard being added (and the keyboard it's a copy of): one dialog asks its settings and word lists, then adds it
    var adding: Pair<SettingsSubtype, SettingsSubtype?>? by remember { mutableStateOf(null) }
    val real = ctx.realPrefs()
    // the switch is on App settings > Per keyboard now: read again whenever a setting changes (coming back from there)
    val changed = (ctx.getActivity() as? helium314.keyboard.settings.SettingsActivity)?.prefChanged?.collectAsState()
    @Suppress("UNUSED_EXPRESSION") changed?.value
    val separate = KeyboardProfiles.isSeparate(real)
    // the menus all keyboards share (separate settings): shown once below the keyboards, not under each
    fun sharedMenu(group: KeyboardProfiles.Group) = KeyboardProfiles.isShared(real, group)
    val expanded = remember { mutableStateListOf<SettingsSubtype>() } // several keyboards can be unfolded at once
    // the screens of a keyboard's own section get its set in their route (KeyboardScopeContext); this screen, its search
    // and what opens from it without one edit the main choice (KeyboardProfiles.mainEditingId): the keyboard in use with
    // separate settings, else the shared set. Nothing is set here (2026-10-09: setting a global while drawing made
    // every frame redraw, and a Back or tap during a slide edited the other keyboard)
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
                    val mainBefore = KeyboardProfiles.mainKeyboard(real)
                    ctx.prefs().edit { putString(Settings.PREF_ENABLED_SUBTYPES, value) }
                    SubtypeSettings.reloadEnabledSubtypes(ctx)
                    onOrderChanged(ctx, mainBefore)
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
                                SettingsDestination.navigateTo(SettingsDestination.inSet(SettingsDestination.withKeyboard(
                                    SettingsDestination.Languages, keyboard), if (separate) KeyboardProfiles.idForListed(real, keyboard) else null))
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
                            setId = { KeyboardProfiles.idForListed(real, keyboard) },
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
                // the switched-off keyboards (Advanced, while any): one tile, unfolding them to switch on or delete (owner
                // 2026-10-11: "Maybe make a single tile for Switched off keyboards, tile expands to show a list where you
                // can switch on"; "Switched off tile should say these are likely bugs and [should] be deleted")
                val switchedOff = SwitchedOffKeyboards.list(real)
                if (advanced && switchedOff.isNotEmpty()) {
                    Preference(
                        name = stringResource(R.string.switched_off_keyboards, switchedOff.size),
                        description = stringResource(R.string.switched_off_keyboards_summary),
                        onClick = { switchedOffOpen = !switchedOffOpen },
                        icon = R.drawable.ic_settings_layout,
                    ) { Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.rotate(if (switchedOffOpen) 90f else -90f)) }
                    if (switchedOffOpen) Column(Modifier.padding(start = 24.dp)) {
                        Text(
                            stringResource(R.string.switched_off_keyboards_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        for (keyboard in switchedOff) key(keyboard) {
                            val name = keyboardName(keyboard, ctx)
                            val switchOn = stringResource(R.string.switch_on_keyboard_of, name)
                            Preference(
                                name = name,
                                description = keyboard.toAdditionalSubtype().mainLayoutName()?.getStringResourceOrName("layout_", ctx) ?: "",
                                onClick = { }, // (its two buttons do it)
                            ) {
                                // Delete is the main one: these are most likely left behind (owner 2026-10-11)
                                TextButton(
                                    onClick = {
                                        if (SwitchedOffKeyboards.switchOn(ctx, keyboard)) generation++
                                        else android.widget.Toast.makeText(ctx, R.string.switch_on_keyboard_listed, android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.semantics { contentDescription = switchOn },
                                ) { Text(stringResource(R.string.switch_on_keyboard)) }
                                FilledIconButton(onClick = { keyboardToDelete = keyboard }) {
                                    Icon(painterResource(R.drawable.ic_bin), stringResource(R.string.delete_keyboard_of, name))
                                }
                            }
                        }
                    }
                }
                // the sections shared by all keyboards
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (!separate)
                    KeyboardSettingsEntries(SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype(), showLanguages = false)
                else if (enabled.size == 1)
                    KeyboardSettingsEntries(enabled[0].toSettingsSubtype(), showLanguages = false, showAdvanced = false,
                        setId = { KeyboardProfiles.idForListed(real, enabled[0].toSettingsSubtype()) })
                else
                    Text(
                        stringResource(R.string.separate_settings_hint), // (the menus below: the first keyboard's)
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                // with separate settings: Advanced once, for all keyboards (its settings are app-wide)
                if (separate && enabled.size > 1) {
                    // the menus every keyboard shares, once: the main keyboard's set (owner 2026-10-11: no separate
                    // common set, the first keyboard's own settings are the common ones)
                    val main = KeyboardProfiles.mainKeyboard(real)
                    KeyboardSettingsEntries(main, showLanguages = false, showAdvanced = false,
                        setId = { KeyboardProfiles.idForListed(real, main) },
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
                itemsAreActions = true, // Rename / Delete look like the buttons they are (white under Black with orange before)
            )
        }
        keyboardToRename?.let { keyboard ->
            helium314.keyboard.settings.dialogs.TextInputDialog(
                onDismissRequest = { keyboardToRename = null },
                onConfirmed = { name ->
                    val renamed = renameKeyboard(ctx, keyboard, name)
                    if (renamed != keyboard) {
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
            DeleteKeyboardDialog(keyboard, onDismissRequest = { keyboardToDelete = null }) { mergeInto ->
                deleteKeyboard(ctx, keyboard, mergeInto)
                keyboardToDelete = null
                generation++
            }
        }
        if (showAddKeyboard)
            ListPickerDialog<Any>(
                onDismissRequest = { showAddKeyboard = false },
                onItemSelected = { item ->
                    if (item is CopyOf) {
                        showAddKeyboard = false
                        adding = nextNumbered(ctx, item.keyboard) to item.keyboard
                        return@ListPickerDialog
                    }
                    showAddKeyboard = false
                    adding = newKeyboardFor(ctx, item as Locale) to null // (it appears at the end of the list, the screen stays)
                },
                title = { Text(stringResource(R.string.add_keyboard)) },
                // copies of the keyboards first, then a new keyboard for any language
                // (without advanced only languages with a dictionary, built in, added or to download; 2026-10-10)
                items = enabledNow.map { CopyOf(it) } + SubtypeSettings.getAvailableSubtypeLocales()
                    .filter { advanced || dictionaryAvailable(ctx, it) }.sortedBy { it.localizedDisplayName(ctx.resources) },
                getItemName = { if (it is CopyOf) stringResource(R.string.copy_of_keyboard, keyboardName(it.keyboard, ctx)) else (it as Locale).localizedDisplayName(ctx.resources) },
                showRadioButtons = false,
            )
        adding?.let { (added, copyOf) ->
            AddKeyboardDialog(added, copyOf, keyboards = enabledNow,
                inUse = SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype(),
                onDismissRequest = { adding = null }, // (nothing added)
            ) { start, learned, blacklisted ->
                val keyboard = createKeyboard(ctx, added, copyOf, start)
                startWords(ctx, keyboard, learned, blacklisted)
                generation++
            }
        }
    }
}

/** An Add keyboard entry: a copy of an existing keyboard. */
private class CopyOf(val keyboard: SettingsSubtype)

/** The switched-off keyboards: an own set or a learned-words pool (by id), not in the list ([SwitchedOffKeyboards.list],
 *  the one helper for them; 2026-10-11). */
internal fun switchedOffKeyboards(ctx: Context): Set<SettingsSubtype> =
    SwitchedOffKeyboards.list(ctx.realPrefs()).toCollection(LinkedHashSet())

/** What the keyboards, enabled or switched off, are set up as (their definitions without their ids): a new keyboard
 *  set up the same is numbered ("English 2", [nextNumbered]), the owner's naming rule; nothing clashes any more (owner
 *  2026-10-11, keyboards have unique ids: two keyboards set up alike are two keyboards). */
internal fun takenKeyboards(ctx: Context): Set<SettingsSubtype> =
    (SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() } + switchedOffKeyboards(ctx)).mapTo(LinkedHashSet()) { it.withoutIds() }

// (owner 2026-10-11, keyboards have unique ids: no "Not changed: <name> is already set up like this" any more. A rename,
// other keys or languages never make a keyboard another one: takenBy and mayChangeKeyboard are gone)

/** Rename's OK: the renamed keyboard (typed duplicates allowed: its id keeps it apart, owner 2026-10-11). */
internal fun renameKeyboard(ctx: Context, keyboard: SettingsSubtype, name: String): SettingsSubtype {
    val renamed = withKeyboardName(keyboard, name)
    if (renamed != keyboard) SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, renamed, ctx) // (its id stays: its settings with it)
    return renamed
}

/** Add: a new keyboard of [locale] (its id comes with [createKeyboard]); when a keyboard is set up as its plain
 *  definition (listed or switched off) it is numbered ("English 2"), the naming rule. */
internal fun newKeyboardFor(ctx: Context, locale: Locale): SettingsSubtype {
    val plain = SubtypeUtilsAdditional.createDefaultSubtype(locale).toSettingsSubtype()
    return if (plain !in takenKeyboards(ctx)) plain else nextNumbered(ctx, plain)
}

/** The Add dialog's OK: [added] (or a copy of [copyOf]) added, with the settings it starts from. */
internal fun createKeyboard(ctx: Context, added: SettingsSubtype, copyOf: SettingsSubtype?, start: StartSettings): SettingsSubtype {
    val keyboard = if (copyOf != null) copyKeyboard(ctx, copyOf) else addNewKeyboard(ctx, added)
    startSettings(ctx, keyboard, start)
    return keyboard
}

/** [source] with the lowest copy number ("English 2", 3, ...) no keyboard has, switched-off ones too; without an id
 *  (a new keyboard gets its own when it is added). */
internal fun nextNumbered(ctx: Context, source: SettingsSubtype): SettingsSubtype {
    val base = source.withoutIds().without(ExtraValue.KEYBOARD_COPY)
    val taken = takenKeyboards(ctx)
        .filter { it.without(ExtraValue.KEYBOARD_COPY) == base }
        .mapNotNull { it.getExtraValueOf(ExtraValue.KEYBOARD_COPY)?.toIntOrNull() }
    val number = generateSequence(2) { it + 1 }.first { it !in taken }
    return base.with(ExtraValue.KEYBOARD_COPY, number.toString())
}

/** @return the copy (its settings: [startSettings]): a new id, came from the original's (owner 2026-10-11) */
private fun copyKeyboard(ctx: Context, source: SettingsSubtype): SettingsSubtype {
    val numbered = LayoutUtilsCustom.withOwnPrivateLayouts(nextNumbered(ctx, source), ctx) // (its own unnamed keys, not the source's file)
    val copy = helium314.keyboard.latin.settings.KeyboardIds.fresh(ctx.realPrefs(), numbered, from = source.keyboardId)
    SubtypeUtilsAdditional.changeAdditionalSubtype(copy, copy, ctx) // registers it
    SubtypeSettings.addEnabledSubtype(ctx.prefs(), copy.toAdditionalSubtype())
    return copy
}

/** Adds [keyboard] to the list, a new keyboard with a new id (its settings: [startSettings]). @return it as listed */
private fun addNewKeyboard(ctx: Context, keyboard: SettingsSubtype): SettingsSubtype {
    val added = helium314.keyboard.latin.settings.KeyboardIds.fresh(ctx.realPrefs(), keyboard.withoutIds())
    SubtypeUtilsAdditional.changeAdditionalSubtype(added, added, ctx) // registers it
    SubtypeSettings.addEnabledSubtype(ctx.prefs(), added.toAdditionalSubtype())
    return added
}

/**
 * The settings [keyboard] starts from, as picked when it was added: another keyboard's own (as that one reads them now),
 * the common ones (the first keyboard's), or every default: its own set, made at once (owner 2026-10-11: every keyboard
 * always has its own set; with one set for all keyboards it reads the first keyboard's until settings are separate).
 * A source of another script keeps its popups to itself (they're for its letters): the new keyboard has the default popups.
 */
internal fun startSettings(ctx: Context, keyboard: SettingsSubtype, start: StartSettings) {
    val real = ctx.realPrefs()
    val from = when (start) {
        is StartSettings.From -> KeyboardProfiles.idForListed(real, start.keyboard) ?: KeyboardProfiles.START_DEFAULTS
        StartSettings.Common -> KeyboardProfiles.SHARED
        StartSettings.Defaults -> KeyboardProfiles.START_DEFAULTS
    }
    val otherScript = start is StartSettings.From &&
        with(helium314.keyboard.latin.utils.ScriptUtils) { start.keyboard.locale.script() != keyboard.locale.script() }
    // (adding or copying a keyboard: one of the explicit moments that create its records, owner 2026-10-11)
    KeyboardProfiles.startKeyboardFrom(real, keyboard.keyboardId ?: return, from, noPopups = otherScript)
}

/** [keyboard] takes the learned words of [learned] and the blacklists of [blacklisted] (in the background; a toast says
 *  how many came in). */
private fun startWords(ctx: Context, keyboard: SettingsSubtype, learned: List<SettingsSubtype>, blacklisted: List<SettingsSubtype>) {
    if (learned.isEmpty() && blacklisted.isEmpty()) return
    val app = ctx.applicationContext
    helium314.keyboard.latin.personalization.LearnedImport.start(ctx, keyboard, learned, blacklisted) { result ->
        val text = if (result == null) app.getString(R.string.import_words_failed)
            else app.getString(R.string.import_words_done, result.learned, result.blacklisted)
        android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
    }
}

/** The delete dialog's Delete: [keyboard] (listed or switched off) goes, with its set, its words and its registration as
 *  an added keyboard; [mergeInto] takes its lists ([DeleteKeyboardDialog]). Switched off, it isn't in the list: taking
 *  it out does nothing (an added keyboard's definition marks it as one, so no listed keyboard is taken for it). */
internal fun deleteKeyboard(ctx: Context, keyboard: SettingsSubtype, mergeInto: SettingsSubtype?) {
    if (KeyboardProfiles.mainBlocksDelete(ctx.realPrefs(), keyboard)) return // (see DeleteKeyboardDialog)
    if (keyboard.isAdditionalSubtype(ctx.prefs())) SubtypeUtilsAdditional.removeAdditionalSubtype(ctx, keyboard.toAdditionalSubtype())
    SubtypeSettings.removeEnabledSubtype(ctx, keyboard.toAdditionalSubtype())
    helium314.keyboard.latin.personalization.LearnedKeyboards.deleteKeyboard(ctx, keyboard, mergeInto) // (its words too)
}

/** The keyboard list was reordered: when another keyboard is first now while its settings are the common ones (separate
 *  settings off, a menu shared), every keyboard reads the new first one's at once (owner 2026-10-11: "yep"): the keyboard
 *  reloads its look and a line says so. */
internal fun onOrderChanged(ctx: Context, mainBefore: SettingsSubtype) {
    val real = ctx.realPrefs()
    val main = KeyboardProfiles.mainKeyboard(real)
    if (main.sameKeyboard(mainBefore) || !KeyboardProfiles.mainIsCommon(real)) return
    helium314.keyboard.latin.utils.SettingsEventLog.log("the main keyboard is ${main.toPref()} now")
    KeyboardProfiles.refreshImeId(real)
    helium314.keyboard.settings.preferences.clearSettingsCaches()
    helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
    (ctx.getActivity() as? helium314.keyboard.settings.SettingsActivity)?.prefChanged()
    android.widget.Toast.makeText(ctx, mainChangedMessage(ctx, main), android.widget.Toast.LENGTH_LONG).show()
}

/** What the line says when [main] is the new first keyboard: separate settings off, all settings are common ("Common
 *  settings now come from Hindi"); on, the shared menus are named, in the menus' order ("Appearance, Swiping now come
 *  from Hindi"), as the restore's note names them ([helium314.keyboard.settings.preferences.commonMenusNote]). */
internal fun mainChangedMessage(ctx: Context, main: SettingsSubtype): String {
    val real = ctx.realPrefs()
    val name = keyboardName(main, ctx)
    if (!KeyboardProfiles.isSeparate(real)) return ctx.getString(R.string.common_settings_now_from, name)
    val shared = KeyboardProfiles.Group.entries.filter { KeyboardProfiles.isShared(real, it) }
    val menus = shared.joinToString(", ") { ctx.getString(groupTitle(it)) }
    return ctx.resources.getQuantityString(R.plurals.shared_menus_now_from, shared.size, menus, name)
}

/**
 * Delete [keyboard]? Its learned and blacklisted words go too; the keyboards reading them ("Use lists from other
 * keyboards") are named, with one pick, none by default (owner 2026-10-11: "list of keyboards where user can select one
 * keyboard or none (none selected by default) Ok/cancel"): the picked one takes the lists merged into its own, and the
 * others read them from there. [onConfirmed] gets the pick.
 */
@Composable
internal fun DeleteKeyboardDialog(keyboard: SettingsSubtype, onDismissRequest: () -> Unit, onConfirmed: (SettingsSubtype?) -> Unit) {
    val ctx = LocalContext.current
    // the first keyboard while its settings are the common ones: not deleted (owner 2026-10-11: "can't delete when
    // common settings are selected. User should be asked to push it down in prio order first.")
    if (KeyboardProfiles.mainBlocksDelete(ctx.realPrefs(), keyboard)) {
        helium314.keyboard.settings.dialogs.InfoDialog(ctx.getString(R.string.delete_keyboard_main_blocked, keyboardName(keyboard, ctx)), onDismissRequest)
        return
    }
    val readers = remember(keyboard) { helium314.keyboard.latin.personalization.LearnedKeyboards.listReaders(ctx.realPrefs(), keyboard) }
    var pick: SettingsSubtype? by remember { mutableStateOf(null) }
    helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { onConfirmed(pick) },
        confirmButtonText = stringResource(R.string.delete),
        title = { Text(stringResource(R.string.delete_keyboard_title)) },
        scrollContent = true,
        content = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_keyboard_message, keyboardName(keyboard, ctx)))
                if (readers.isNotEmpty()) {
                    Text(stringResource(R.string.delete_keyboard_readers))
                    (listOf<SettingsSubtype?>(null) + readers).forEach { kb ->
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { pick = kb }.heightIn(min = 44.dp)) {
                            androidx.compose.material3.RadioButton(selected = pick == kb, onClick = { pick = kb })
                            Text(if (kb == null) stringResource(R.string.delete_keyboard_merge_none) else keyboardName(kb, ctx))
                        }
                    }
                }
            }
        },
    )
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
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
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_ALL
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MAIN
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MORE
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.ListPickerDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
    val overrides = remember(generation) { KeyPopupOverrides.load(prefs) }
    Column {
        // presets: the generated defaults for every key at once (the user's own per-key edits stay on top)
        val userSets = remember(generation) { KeyPopupOverrides.loadSets(ctx.realPrefs()) }
        val selectedUserSet = prefs.getString(KeyPopupOverrides.PREF_SELECTED_SET, null)?.let { name -> userSets.firstOrNull { it.name == name } }
        // a change made while a built-in set is selected is held here until the user names a set for it
        var pendingChange: Pair<String, List<String>?>? by remember { mutableStateOf(null) }
        var showSaveAsDialog by remember { mutableStateOf(false) }
        val presets = listOf(
            // the Curmudgeon default: every variant a key has, plus the symbol map (2026-09-26: "a lot richer")
            Preset(R.string.key_popups_preset_standard, POPUP_KEYS_ALL, null, symbolMap = Defaults.PREF_SYMBOL_POPUP_MAP),
            Preset(R.string.key_popups_preset_heliboard, POPUP_KEYS_NORMAL, null, symbolMap = ""),
            Preset(R.string.key_popups_preset_main, POPUP_KEYS_MAIN, null),
            Preset(R.string.key_popups_preset_more, POPUP_KEYS_MORE, null),
            Preset(R.string.key_popups_preset_all, POPUP_KEYS_ALL, null),
        ).let { builtIn ->
            // the Arabic-script symbols page only makes sense for keyboards of that script
            if (keyboard.locale.script() == ScriptUtils.SCRIPT_ARABIC) builtIn + Preset(R.string.key_popups_preset_arabic, POPUP_KEYS_NORMAL, "symbols_arabic")
            else builtIn
        } + userSets.map { Preset(0, it.morePopups, it.symbolsLayout, it.name, it.overrides) }
        val accentsValue = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)
            ?: prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)!!
        val symbolsLayout = keyboard.layoutName(LayoutType.SYMBOLS)
        val symbolMap = prefs.getString(Settings.PREF_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP)!!
        val current = presets.firstOrNull { it.userName != null && it.userName == selectedUserSet?.name }
            ?: presets.firstOrNull { it.userName == null && it.morePopups == accentsValue && it.symbolsLayout == symbolsLayout && (it.symbolMap == null || it.symbolMap == symbolMap) }
            ?: presets[0]
        // every arrangement belongs to a set of the user's own: into the selected one, or into a new one to be named
        fun storeInSet(name: String, all: Map<String, List<String>>) {
            storePopupSet(ctx, name, accentsValue, symbolsLayout, all)
            generation++
        }
        @Composable fun presetName(p: Preset) = p.userName ?: stringResource(p.name)
        fun deleteSet(name: String) {
            KeyPopupOverrides.saveSets(ctx.realPrefs(), userSets.filter { it.name != name })
            if (name == selectedUserSet?.name) { // the keyboard falls back to the built-in arrangement
                KeyPopupOverrides.save(prefs, emptyMap())
                prefs.edit().remove(KeyPopupOverrides.PREF_SELECTED_SET).apply()
                reloadPreview()
            }
            generation++
        }
        var setToDelete: String? by remember { mutableStateOf(null) }
        var setToRename: String? by remember { mutableStateOf(null) }
        setToRename?.let { oldName ->
            TextInputDialog(
                onDismissRequest = { setToRename = null },
                onConfirmed = { newName ->
                    val name = newName.trim()
                    KeyPopupOverrides.saveSets(ctx.realPrefs(), userSets.map {
                        if (it.name == oldName) KeyPopupOverrides.UserSet(name, it.morePopups, it.symbolsLayout, it.overrides) else it
                    })
                    if (selectedUserSet?.name == oldName) prefs.edit().putString(KeyPopupOverrides.PREF_SELECTED_SET, name).apply()
                    setToRename = null
                    generation++
                },
                title = { Text(stringResource(R.string.key_popups_rename_set)) },
                initialText = oldName,
                checkTextValid = { text -> text.isNotBlank() && (text.trim() == oldName || userSets.none { it.name == text.trim() }) },
            )
        }
        setToDelete?.let { name ->
            ConfirmationDialog(
                onDismissRequest = { setToDelete = null },
                onConfirmed = { deleteSet(name); setToDelete = null },
                title = { Text(stringResource(R.string.key_popups_delete_set_title, name)) },
                confirmButtonText = stringResource(R.string.delete),
            )
        }
        if (showSaveAsDialog)
            TextInputDialog(
                onDismissRequest = { showSaveAsDialog = false; pendingChange = null }, // cancel = the change is dropped
                onConfirmed = { name ->
                    val all = overrides.toMutableMap()
                    pendingChange?.let { (k, v) -> if (v == null) all.remove(k) else all[k] = v }
                    storeInSet(name.trim(), all)
                    pendingChange = null
                },
                title = { Text(stringResource(if (pendingChange != null) R.string.key_popups_save_change_title else R.string.key_popups_save_as_new)) },
                initialText = if (current.userName != null) "" else stringResource(R.string.key_popups_my_set),
                checkTextValid = { it.isNotBlank() },
            )
        if (showAccentsDialog)
            ListPickerDialog(
                onDismissRequest = { showAccentsDialog = false },
                items = presets,
                getItemName = { presetName(it) },
                selectedItem = current,
                trailing = { p -> p.userName?.let { name ->
                    IconButton({ showAccentsDialog = false; setToRename = name }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.key_popups_rename_set)) }
                    DeleteButton { showAccentsDialog = false; setToDelete = name }
                } },
                // the current arrangement (built-in or own, with any changes) as a new set of the user's own
                footer = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().clickable { showAccentsDialog = false; pendingChange = null; showSaveAsDialog = true }
                            .padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_plus), null, Modifier.padding(horizontal = 12.dp))
                        Text(stringResource(R.string.key_popups_save_as_new), color = MaterialTheme.colorScheme.primary)
                    }
                },
                onItemSelected = { preset ->
                    var changed = keyboard.with(ExtraValue.MORE_POPUPS, preset.morePopups)
                    changed = if (preset.symbolsLayout == null) changed.withoutLayout(LayoutType.SYMBOLS)
                        else changed.withLayout(LayoutType.SYMBOLS, preset.symbolsLayout)
                    // a saved set brings its arrangement; a built-in has none
                    KeyPopupOverrides.save(prefs, preset.overrides ?: emptyMap())
                    prefs.edit().apply {
                        if (preset.userName != null) putString(KeyPopupOverrides.PREF_SELECTED_SET, preset.userName) else remove(KeyPopupOverrides.PREF_SELECTED_SET)
                        if (preset.symbolMap != null) putString(Settings.PREF_SYMBOL_POPUP_MAP, preset.symbolMap)
                    }.apply()
                    onKeyboardChanged(changed)
                    generation++
                    reloadPreview()
                }
            )
        // the deep customization, advanced only: every key's popups (a tab per key group), the keys and popups as JSON
        val advanced by SettingsMode.state(ctx)
        if (advanced) AdvancedBlock {
            // the preset layouts first (with saving the current one), then the full customization under them
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { showAccentsDialog = true }.heightIn(min = ROW_HEIGHT).padding(vertical = 4.dp).padding(start = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.key_popups_presets), style = MaterialTheme.typography.bodyLarge)
                    Text(presetName(current), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NextScreenIcon()
            }
            NavRow(stringResource(R.string.key_popups_full), stringResource(R.string.key_popups_full_summary)) {
                SettingsDestination.navigateTo(SettingsDestination.CustomizePopups + keyboard.toPref())
            }
            LayoutFilesRow()
        }
        // own sets are saved (last entry), renamed (pencil) and deleted (trash icon) in the preset list
    }
}

/** Every arrangement belongs to a set of the user's own: stores [all] as set [name] and selects it. */
private fun storePopupSet(ctx: Context, name: String, accentsValue: String, symbolsLayout: String?, all: Map<String, List<String>>) {
    val prefs = ctx.prefs()
    val set = KeyPopupOverrides.UserSet(name, accentsValue, symbolsLayout, all)
    KeyPopupOverrides.saveSets(ctx.realPrefs(), KeyPopupOverrides.loadSets(ctx.realPrefs()).filter { it.name != name } + set)
    KeyPopupOverrides.save(prefs, all)
    prefs.edit().putString(KeyPopupOverrides.PREF_SELECTED_SET, name).apply()
    reloadPreview()
}

/** A row that opens another screen: title, summary, arrow; the Preferences screen's row style. */
@Composable
fun NavRow(title: String, summary: String? = null, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = ROW_HEIGHT).padding(vertical = 4.dp).padding(start = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NextScreenIcon()
    }
}

/**
 * Every key's long-press popups, a tab per key group (letters and digits, the keys that change with the text field,
 * the symbol pages, the number pad); each key folds open to its popups, switchable and draggable. A change goes into
 * the selected popup set of the user's own, or asks for the name of a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizePopupsScreen(keyboard: SettingsSubtype, onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var generation by remember { mutableIntStateOf(0) }
    val groups = remember(keyboard, generation) { keysWithPopups(ctx, keyboard) }
    val overrides = remember(generation) { KeyPopupOverrides.load(prefs) }
    val selectedUserSet = remember(generation) {
        prefs.getString(KeyPopupOverrides.PREF_SELECTED_SET, null)?.let { name -> KeyPopupOverrides.loadSets(ctx.realPrefs()).firstOrNull { it.name == name } }
    }
    val accentsValue = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS) ?: prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)!!
    val symbolsLayout = keyboard.layoutName(LayoutType.SYMBOLS)
    val unfolded = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) { mutableStateListOf<String>() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // a change made while a built-in set is selected is held here until the user names a set for it
    var pendingChange: Pair<String, List<String>?>? by remember { mutableStateOf(null) }
    fun applyChange(overrideKey: String, labels: List<String>?) {
        val all = overrides.toMutableMap().also { if (labels == null) it.remove(overrideKey) else it[overrideKey] = labels }
        if (selectedUserSet != null) { storePopupSet(ctx, selectedUserSet.name, accentsValue, symbolsLayout, all); generation++ }
        else pendingChange = overrideKey to labels
    }
    val tryIt = remember { TryItState() }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.key_popups_full)) }, navigationIcon = { BackButton(onClickBack) }) },
        bottomBar = { TryItBar(keyboard, tryIt) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                groups.forEachIndexed { i, (titleId, keys) ->
                    val changed = keys.any { overrides.containsKey(it.overrideKey) }
                    Tab(selected = i == tab, onClick = { tab = i }, text = { Text(stringResource(titleId) + if (changed) " •" else "") })
                }
            }
            val (groupTitle, keys) = groups[tab.coerceAtMost(groups.lastIndex)]
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                for (keyInfo in keys) key(groupTitle, keyInfo.overrideKey) {
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
    pendingChange?.let { change ->
        TextInputDialog(
            onDismissRequest = { pendingChange = null }, // cancel = the change is dropped
            onConfirmed = { name ->
                val all = overrides.toMutableMap()
                change.second.let { if (it == null) all.remove(change.first) else all[change.first] = it }
                storePopupSet(ctx, name.trim(), accentsValue, symbolsLayout, all)
                pendingChange = null
                generation++
            },
            title = { Text(stringResource(R.string.key_popups_save_change_title)) },
            initialText = stringResource(R.string.key_popups_my_set),
            checkTextValid = { it.isNotBlank() },
        )
    }
}

/** A text field to try the keyboard being edited: focusing it opens that keyboard, changes rebuild it live. */
enum class TryItMode { TEXT, NUMBER, PHONE }

/** What the try-it field asks the keyboard for; items on the screen set it (e.g. the number pad item) and focus the field. */
class TryItState {
    var mode by mutableStateOf(TryItMode.TEXT)
    val focusRequester = FocusRequester()
    fun show(mode: TryItMode) { this.mode = mode; runCatching { focusRequester.requestFocus() } }
}

@Composable
fun TryItBar(keyboard: SettingsSubtype, state: TryItState, onFocus: (Boolean) -> Unit = {}) {
    var tryText by remember { mutableStateOf("") }
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            OutlinedTextField(
                value = tryText, onValueChange = { tryText = it },
                label = { Text(stringResource(R.string.key_popups_try)) },
                keyboardOptions = KeyboardOptions(keyboardType = when (state.mode) {
                    TryItMode.NUMBER -> KeyboardType.Number
                    TryItMode.PHONE -> KeyboardType.Phone
                    else -> KeyboardType.Text
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(state.focusRequester)
                    .onFocusChanged { onFocus(it.isFocused); if (it.isFocused) showKeyboardForPreview(keyboard) }
            )
            // which keyboard the preview shows: letters, the number pad, the phone pad
            SingleChoiceSegmentedButtonRow(Modifier.padding(start = 8.dp)) {
                val modes = listOf(TryItMode.TEXT to "ABC", TryItMode.NUMBER to "123", TryItMode.PHONE to "\u260E")
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { state.show(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

/** A built-in set ([name] resource) or one the user saved ([userName], with its per-key arrangement). */
private class Preset(val name: Int, val morePopups: String, val symbolsLayout: String?,
                     val userName: String? = null, val overrides: Map<String, List<String>>? = null,
                     val symbolMap: String? = null) // the letter -> symbols map; null = the Curmudgeon one, "" = none (HeliBoard)

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
fun LayoutFilesRow() = NavRow(stringResource(R.string.layout_files), stringResource(R.string.layout_files_summary)) {
    SettingsDestination.navigateTo(SettingsDestination.LayoutFiles)
}

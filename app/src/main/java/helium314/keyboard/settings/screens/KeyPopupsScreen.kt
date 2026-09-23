// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import helium314.keyboard.latin.RichInputMethodSubtype
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_ALL
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MAIN
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MORE
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.settings.dialogs.ListPickerDialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.windowInsetsPadding
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
    val groups = remember(keyboard, generation) { keysWithPopups(ctx, keyboard) }
    val overrides = remember(generation) { KeyPopupOverrides.load(prefs) }
    val unfolded = remember { mutableStateListOf<String>() }
    Column {
        Text(
            stringResource(R.string.key_popups_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        // presets: the generated defaults for every key at once (the user's own per-key edits stay on top)
        val presets = listOf(
            Preset(R.string.key_popups_preset_standard, POPUP_KEYS_NORMAL, null),
            Preset(R.string.key_popups_preset_main, POPUP_KEYS_MAIN, null),
            Preset(R.string.key_popups_preset_more, POPUP_KEYS_MORE, null),
            Preset(R.string.key_popups_preset_all, POPUP_KEYS_ALL, null),
            Preset(R.string.key_popups_preset_arabic, POPUP_KEYS_NORMAL, "symbols_arabic"),
        )
        val accentsValue = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)
            ?: prefs.getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)!!
        val symbolsLayout = keyboard.layoutName(LayoutType.SYMBOLS)
        val current = presets.firstOrNull { it.morePopups == accentsValue && it.symbolsLayout == symbolsLayout } ?: presets[0]
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { showAccentsDialog = true }.padding(vertical = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.key_popups_presets), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(current.name), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NextScreenIcon()
        }
        if (showAccentsDialog)
            ListPickerDialog(
                onDismissRequest = { showAccentsDialog = false },
                items = presets,
                getItemName = { stringResource(it.name) },
                selectedItem = current,
                onItemSelected = { preset ->
                    var changed = keyboard.with(ExtraValue.MORE_POPUPS, preset.morePopups)
                    changed = if (preset.symbolsLayout == null) changed.withoutLayout(LayoutType.SYMBOLS)
                        else changed.withLayout(LayoutType.SYMBOLS, preset.symbolsLayout)
                    onKeyboardChanged(changed)
                    generation++
                    reloadPreview()
                }
            )
        for ((groupTitle, keys) in groups) {
            val groupId = "group:$groupTitle"
            FoldRow(title = stringResource(groupTitle), unfolded = groupId in unfolded, style = MaterialTheme.typography.titleMedium) {
                if (groupId in unfolded) unfolded.remove(groupId) else unfolded.add(groupId)
            }
            if (groupId !in unfolded) continue
            for (keyInfo in keys) key(groupTitle, keyInfo.overrideKey) {
                val id = "$groupTitle:${keyInfo.overrideKey}"
                val override = overrides[keyInfo.overrideKey]
                val summary = (override ?: keyInfo.popups).joinToString(" ")
                FoldRow(
                    title = keyInfo.title,
                    subtitle = summary.ifEmpty { stringResource(R.string.key_popups_none) },
                    unfolded = id in unfolded,
                    changed = override != null,
                    modifier = Modifier.padding(start = 12.dp),
                ) { if (id in unfolded) unfolded.remove(id) else unfolded.add(id) }
                if (id in unfolded)
                    PopupEditor(
                        allPopups = (keyInfo.pool + override.orEmpty()).distinct(), // the user's own additions too
                        enabledInOrder = override ?: keyInfo.popups,
                        onChanged = { KeyPopupOverrides.set(prefs, keyInfo.overrideKey, it); generation++; reloadPreview() },
                        onReset = { KeyPopupOverrides.set(prefs, keyInfo.overrideKey, null); generation++; reloadPreview() },
                        modifier = Modifier.padding(start = 24.dp),
                    )
            }
        }
    }
}

/** A text field to try the keyboard being edited: focusing it opens that keyboard, changes rebuild it live. */
@Composable
fun TryItBar(keyboard: SettingsSubtype) {
    var tryText by remember { mutableStateOf("") }
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        OutlinedTextField(
            value = tryText, onValueChange = { tryText = it },
            label = { Text(stringResource(R.string.key_popups_try)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .onFocusChanged { if (it.isFocused) showKeyboardForPreview(keyboard) }
        )
    }
}

private class Preset(val name: Int, val morePopups: String, val symbolsLayout: String?)

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
        modifier = modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp)
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

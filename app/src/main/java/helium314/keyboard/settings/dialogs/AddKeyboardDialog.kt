// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.keyboardName

/** What a keyboard being added starts its settings from: another keyboard's, the common ones, or every default. */
sealed interface StartSettings {
    class From(val keyboard: SettingsSubtype) : StartSettings
    data object Common : StartSettings
    data object Defaults : StartSettings
}

/**
 * Adding [added] (owner 2026-10-10: "Add keyboard should always ask to copy another keyboard's settings, regardless of
 * use separate settings being toggled off ... in a single dialog box along with word list selection"): one keyboard's
 * settings (a radio button per keyboard, the common settings, every default) and the learned and blacklisted words of
 * any number of keyboards (check boxes). [copyOf]: a copy, which starts as that keyboard (its settings and both its
 * lists picked); else the keyboard in use's settings, no lists. Cancel adds nothing.
 */
@Composable
fun AddKeyboardDialog(added: SettingsSubtype, copyOf: SettingsSubtype?, keyboards: List<SettingsSubtype>, inUse: SettingsSubtype,
                      onDismissRequest: () -> Unit,
                      onConfirmed: (StartSettings, learned: List<SettingsSubtype>, blacklisted: List<SettingsSubtype>) -> Unit) {
    val ctx = LocalContext.current
    val real = ctx.realPrefs()
    val separate = KeyboardProfiles.isSeparate(real)
    val withLists = LearnedKeyboards.isDone(real) // (before the learned words were made per keyboard: nothing to take)
    var start by remember(added) {
        mutableStateOf((copyOf ?: inUse.takeIf { it in keyboards })?.let { StartSettings.From(it) } ?: StartSettings.Common)
    }
    val learned = remember(added) { mutableStateListOf<SettingsSubtype>().apply { copyOf?.let { add(it) } } }
    val blacklisted = remember(added) { mutableStateListOf<SettingsSubtype>().apply { copyOf?.let { add(it) } } }
    fun picked(s: StartSettings) = when (val now = start) { is StartSettings.From -> s is StartSettings.From && s.keyboard == now.keyboard; else -> s == now }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { onConfirmed(start, learned.toList(), blacklisted.toList()) },
        title = { Text(stringResource(R.string.add_keyboard_dialog_title, keyboardName(added, ctx))) },
        confirmButtonText = stringResource(R.string.add_keyboard_button),
        scrollContent = true,
        content = {
            Column {
                for (keyboard in keyboards) {
                    Text(keyboardName(keyboard, ctx), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    // a choice that doesn't fit goes on the next line, whole (it was squeezed: "Blacklisted" one
                    // letter per line; owner 2026-10-23, as on the Languages screen)
                    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                        RadioItem(stringResource(R.string.add_keyboard_settings), picked(StartSettings.From(keyboard))) { start = StartSettings.From(keyboard) }
                        if (withLists) {
                            CheckBoxItem(stringResource(R.string.learned_words_list_learned), keyboard, learned)
                            CheckBoxItem(stringResource(R.string.learned_words_list_blacklisted), keyboard, blacklisted)
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    RadioItem(stringResource(R.string.copy_keyboard_common_settings), picked(StartSettings.Common)) { start = StartSettings.Common }
                }
                Row {
                    RadioItem(stringResource(R.string.new_keyboard_default_settings), picked(StartSettings.Defaults)) { start = StartSettings.Defaults }
                }
                // one set of settings for all keyboards now: the pick waits for separate settings
                if (!separate)
                    Text(stringResource(R.string.add_keyboard_settings_later), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
}

@Composable
private fun RadioItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClick).padding(end = 8.dp)) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}

@Composable
private fun CheckBoxItem(text: String, source: SettingsSubtype, ticked: SnapshotStateList<SettingsSubtype>) {
    fun toggle(on: Boolean) { if (on) { if (source !in ticked) ticked.add(source) } else ticked.remove(source) }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { toggle(source !in ticked) }.padding(end = 8.dp)) {
        Checkbox(checked = source in ticked, onCheckedChange = { toggle(it) })
        Text(text)
    }
}

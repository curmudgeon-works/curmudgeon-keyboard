// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R

/**
 * "Update Sunset?" (owner 2026-10-11): the save icon on a saved item's row gives it the settings on the screen. When
 * other keyboards (or saved Layouts / themes holding it) use it, they're listed as [SelectOthersList] has it: the
 * selected ones take the change too, the others keep their current settings, named Custom. [sameAs]: another saved
 * item that already has these settings, said in a note ("include the already exists note"); nothing is blocked.
 * The first line is the same in every mode, "Sunset gets the settings on this screen." (owner 2026-10-18: "I like this
 * common message"). [commonFrom]: the first keyboard's name when the item's menu is common to all keyboards and there
 * are more than one ([helium314.keyboard.settings.screens.commonFrom]); with [anyListed] (a keyboard listed, its own
 * settings hidden behind the common ones) a line says the change goes into those (owner 2026-10-18: "yes, makes sense
 * to follow established pattern from restore"; no "(hidden)" marks: "Don't mention '(hidden)' in parentheses, that
 * seems confusing. Broader messaging on common settings should address this").
 */
@Composable
fun UpdateSavedItemDialog(
    name: String,
    others: List<String>, // the other users' names, in the order to show
    sameAs: String?,
    onDismissRequest: () -> Unit,
    commonFrom: String? = null,
    anyListed: Boolean = false,
    group: helium314.keyboard.latin.settings.KeyboardProfiles.Group = helium314.keyboard.latin.settings.KeyboardProfiles.Group.APPEARANCE, // (the item's menu)
    onConfirmed: (selected: Set<Int>) -> Unit, // indices into [others]
) {
    val selected = rememberAllSelected(others)
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { onConfirmed(selected.toSet()) },
        confirmButtonText = stringResource(if (sameAs != null) R.string.update_item_anyway else R.string.update_item),
        title = { Text(stringResource(R.string.update_item_title, name)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sameAs?.let { SameSettingsNote(it) }
                Text(stringResource(R.string.update_item_text_screen, name))
                if (others.isNotEmpty()) SelectOthersList(stringResource(R.string.update_item_others_select), others, selected)
                if (commonFrom != null && anyListed) HiddenSetsNote(commonFrom, group)
            }
        },
    )
}

/** All of [others] selected, the start of every [SelectOthersList] (owner 2026-10-18: "all should come selected yes"). */
@Composable
fun <T> rememberAllSelected(others: List<T>): SnapshotStateList<Int> =
    remember(others) { mutableStateListOf<Int>().apply { addAll(others.indices) } }

/**
 * The one way a dialog changing a saved item (a saved Layout, theme or popup set, a palette, a custom key layout) asks
 * about the others using it (owner 2026-10-18: "select those that should take this change. The others will retain
 * their current settings named Warm 2 / Custom <- as applicable. This should be our standard pattern."): [text], then a
 * checkbox per other ([others], their names); the selected ones ([selected], indices into [others]) take the change,
 * the others keep what they have now, under the name [text] gives. No second question, no choice per one.
 */
@Composable
fun SelectOthersList(text: String, others: List<String>, selected: SnapshotStateList<Int>) {
    Text(text)
    Column {
        others.forEachIndexed { i, other ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .clickable { if (i in selected) selected.remove(i) else selected.add(i) }) {
                Checkbox(checked = i in selected, onCheckedChange = { if (it) selected.add(i) else selected.remove(i) })
                Text(other, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Under an update question's list with the settings common when a keyboard is in it ([commonFrom]: the first
 *  keyboard's name): every keyboard follows the first, the listed ones get the change in their own settings, as the
 *  restore's note on common menus says it (owner 2026-10-18). */
@Composable
fun HiddenSetsNote(commonFrom: String, group: helium314.keyboard.latin.settings.KeyboardProfiles.Group) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(helium314.keyboard.settings.screens.commonChangeNote(ctx, commonFrom, listOf(group)), style = MaterialTheme.typography.bodyMedium)
}

/** "These settings are the same as “Moonlight”." (Update and Save as new). */
@Composable
fun SameSettingsNote(sameAs: String) {
    Text(stringResource(R.string.same_settings_as, sameAs), color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium)
}

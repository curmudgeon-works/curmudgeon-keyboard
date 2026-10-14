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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R

/**
 * "Update Sunset?" (owner 2026-10-11): the save icon on a saved item's row gives it this keyboard's settings. When
 * other keyboards (or saved Layouts / themes holding it) use it, each is listed with a tick, all unticked: the ticked
 * ones get the change too, the others keep it as it was (shown as Custom). [sameAs]: another saved item that already
 * has these settings, said in a note ("include the already exists note"); nothing is blocked.
 */
@Composable
fun UpdateSavedItemDialog(
    name: String,
    others: List<String>, // the other users' names, in the order to show
    sameAs: String?,
    onDismissRequest: () -> Unit,
    onConfirmed: (ticked: Set<Int>) -> Unit, // indices into [others]
) {
    val ticked = remember(others) { mutableStateListOf<Int>() }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { onConfirmed(ticked.toSet()) },
        confirmButtonText = stringResource(if (sameAs != null) R.string.update_item_anyway else R.string.update_item),
        title = { Text(stringResource(R.string.update_item_title, name)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sameAs?.let { SameSettingsNote(it) }
                Text(stringResource(R.string.update_item_text, name))
                if (others.isNotEmpty()) {
                    Text(stringResource(R.string.update_item_others, name))
                    Column {
                        others.forEachIndexed { i, other ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                    .clickable { if (i in ticked) ticked.remove(i) else ticked.add(i) }) {
                                Checkbox(checked = i in ticked, onCheckedChange = { if (it) ticked.add(i) else ticked.remove(i) })
                                Text(other, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** "These settings are the same as “Moonlight”." (Update and Save as new). */
@Composable
fun SameSettingsNote(sameAs: String) {
    Text(stringResource(R.string.same_settings_as, sameAs), color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium)
}

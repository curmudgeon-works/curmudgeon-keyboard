// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.settings.Palettes

/**
 * The palette editor's Save when other keyboards or saved themes ([others], their names in order) use the palette
 * [name] (owner 2026-10-11: "Each keyboard using it should have three options -- use the new edited; use app default;
 * use 'Warm N', not 'Custom N'"): each takes the new colors, the default ones, or the old ones as [keeper] ("Warm 2",
 * one for all that keep them, made only when one does). Each starts on the new colors (owner 2026-10-11: "on second
 * thought, yes let's do new colors as the default option").
 */
@Composable
fun PaletteEditDialog(
    name: String,
    keeper: String,
    others: List<String>,
    onDismissRequest: () -> Unit,
    onConfirmed: (choices: List<Palettes.Choice>) -> Unit, // in [others]' order
) {
    val choices = remember(others) { mutableStateListOf<Palettes.Choice>().apply { repeat(others.size) { add(Palettes.Choice.NEW) } } }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { onConfirmed(choices.toList()) },
        confirmButtonText = stringResource(R.string.save),
        title = { Text(stringResource(R.string.palette_edit_title, name)) },
        scrollContent = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.palette_edit_text))
                val options = listOf(
                    Palettes.Choice.NEW to stringResource(R.string.palette_take_new),
                    Palettes.Choice.DEFAULT to stringResource(R.string.palette_take_default),
                    Palettes.Choice.KEEP_OLD to stringResource(R.string.palette_take_old, keeper),
                )
                others.forEachIndexed { i, other ->
                    Column {
                        Text(other, style = MaterialTheme.typography.titleSmall)
                        for ((choice, text) in options) Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                                .selectable(selected = choices[i] == choice, role = Role.RadioButton) { choices[i] = choice }) {
                            RadioButton(selected = choices[i] == choice, onClick = null)
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
    )
}

/**
 * The trash on a palette that other keyboards or saved themes ([others], their names) use (owner 2026-10-11: "Move all
 * to default, Or keep all on Warm and move this to default"): [onAll] every one of them and this keyboard go to the
 * default colors and the palette leaves the list; [onOnlyHere] only this keyboard does, the others keep it.
 */
@Composable
fun PaletteDeleteDialog(name: String, others: List<String>, onDismissRequest: () -> Unit, onAll: () -> Unit, onOnlyHere: () -> Unit) {
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = onAll,
        confirmButtonText = stringResource(R.string.palette_delete_all),
        neutralButtonText = stringResource(R.string.palette_delete_only_here),
        onNeutral = { onOnlyHere(); onDismissRequest() },
        title = { Text(stringResource(R.string.palette_delete_title, name)) },
        scrollContent = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.palette_delete_users, others.joinToString("\n")))
                Text(stringResource(R.string.palette_delete_text, name))
            }
        },
    )
}

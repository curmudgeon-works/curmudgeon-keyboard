// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.settings.Palettes

/**
 * The palette editor's Save when other keyboards or saved themes ([others], their names in order) use the palette
 * [name]: the edit reaches every user, as palettes come from one common set (owner 2026-10-18: "only this keyboard is
 * no longer valid"), and the others are listed as [SelectOthersList] has it (owner 2026-10-18: "select which each one
 * takes, else they retain their current setting named Warm 2"): the selected ones take the new colors, the others keep
 * their current colors as [keeper] ("Warm 2", one for all that keep them, made only when one does). All start selected
 * (owner 2026-10-11: "new colors as the default option"; 2026-10-18: "all should come selected yes"). The default
 * colors aren't offered any more (were, with the new and the old colors, one radio each per user; owner 2026-10-18).
 */
@Composable
fun PaletteEditDialog(
    name: String,
    keeper: String,
    others: List<String>,
    onDismissRequest: () -> Unit,
    onConfirmed: (choices: List<Palettes.Choice>) -> Unit, // in [others]' order: NEW or KEEP_OLD
) {
    val selected = rememberAllSelected(others)
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { onConfirmed(others.indices.map { if (it in selected) Palettes.Choice.NEW else Palettes.Choice.KEEP_OLD }) },
        confirmButtonText = stringResource(R.string.save),
        title = { Text(stringResource(R.string.palette_edit_title, name)) },
        scrollContent = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectOthersList(stringResource(R.string.palette_edit_text_select, name, keeper), others, selected)
            }
        },
    )
}

/**
 * The trash on a palette that other keyboards or saved themes ([others], their names) use: [onAll] every one of them
 * and this keyboard go to the default colors and the palette leaves the list. No "only this keyboard": palettes come
 * from one common set, like fonts, and the button answers the question (owner 2026-10-18: "the headline question
 * "Delete Warm?" should be answered by the button in an obvious way").
 */
@Composable
fun PaletteDeleteDialog(name: String, others: List<String>, onDismissRequest: () -> Unit, onAll: () -> Unit) {
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = onAll,
        confirmButtonText = stringResource(R.string.palette_delete_all_yes),
        title = { Text(stringResource(R.string.palette_delete_title, name)) },
        scrollContent = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.palette_delete_users_all, others.joinToString("\n")))
                Text(stringResource(R.string.palette_delete_text_all, name))
            }
        },
    )
}

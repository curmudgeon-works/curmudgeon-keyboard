// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.LearnedImport
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.keyboardName

/**
 * Asked once when [keyboard] was added (2026-10-10): start it with the learned and blacklisted words of other keyboards?
 * Every other keyboard with a Learned and a Blacklisted box, unticked; a copy's [copyOf] has both ticked. Cancel imports
 * nothing. With no other keyboard it closes at once, shown never.
 */
@Composable
fun ImportWordsDialog(keyboard: SettingsSubtype, copyOf: SettingsSubtype?, onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val sources = remember(keyboard) { LearnedImport.sources(ctx.realPrefs(), keyboard) }
    if (sources.isEmpty()) {
        LaunchedEffect(keyboard) { onDismissRequest() }
        return
    }
    val learned = remember(keyboard) { mutableStateListOf<SettingsSubtype>().apply { copyOf?.takeIf { it in sources }?.let { add(it) } } }
    val blacklisted = remember(keyboard) { mutableStateListOf<SettingsSubtype>().apply { copyOf?.takeIf { it in sources }?.let { add(it) } } }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = {
            val app = ctx.applicationContext
            LearnedImport.start(ctx, keyboard, learned.toList(), blacklisted.toList()) { result ->
                val text = if (result == null) app.getString(R.string.import_words_failed)
                    else app.getString(R.string.import_words_done, result.learned, result.blacklisted)
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        },
        title = { Text(stringResource(R.string.import_words_title, keyboardName(keyboard, ctx))) },
        confirmButtonText = stringResource(R.string.import_words_button),
        checkOk = { learned.isNotEmpty() || blacklisted.isNotEmpty() },
        scrollContent = true,
        content = {
            Column {
                for (source in sources) {
                    Text(keyboardName(source, ctx), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheckBoxItem(stringResource(R.string.learned_words_list_learned), source, learned)
                        CheckBoxItem(stringResource(R.string.learned_words_list_blacklisted), source, blacklisted)
                    }
                }
            }
        },
    )
}

@Composable
private fun CheckBoxItem(text: String, source: SettingsSubtype, ticked: SnapshotStateList<SettingsSubtype>) {
    fun toggle(on: Boolean) { if (on) { if (source !in ticked) ticked.add(source) } else ticked.remove(source) }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { toggle(source !in ticked) }.padding(end = 16.dp)) {
        Checkbox(checked = source in ticked, onCheckedChange = { toggle(it) })
        Text(text)
    }
}

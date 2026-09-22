// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.utils.LanguageList
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import java.util.Locale

/**
 * Simple-mode Languages screen: one row per language, Off / Low / Medium / High priority and whether its learned
 * words count for every language. Keyboards (subtypes) are derived from it by [LanguageList]; advanced mode
 * keeps the per-keyboard [LanguageScreen].
 */
@Composable
fun LanguageListScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var generation by remember { mutableIntStateOf(0) } // bumped after every change so the list re-sorts
    val languages = remember(generation) { LanguageList.languages(ctx) }
    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.language_and_layouts_title))
                Text(
                    stringResource(R.string.language_list_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        filteredItems = { term ->
            languages.filter { locale ->
                locale.localizedDisplayName(ctx.resources).replace("(", "")
                    .splitOnWhitespace().any { it.startsWith(term, true) }
            }
        },
        itemContent = { LanguageRow(it) { generation++ } }
    )
}

@Composable
private fun LanguageRow(locale: Locale, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var priority by remember(locale) { mutableIntStateOf(LanguageList.priority(ctx, locale)) }
    var shared by remember(locale) { mutableStateOf(LanguagePriority.sharesUserHistory(ctx.prefs(), locale)) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                locale.localizedDisplayName(ctx.resources),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            val options = listOf(
                LanguageList.OFF to stringResource(R.string.language_priority_off),
                LanguagePriority.LOW to stringResource(R.string.language_priority_low),
                LanguagePriority.MEDIUM to stringResource(R.string.language_priority_medium),
                LanguagePriority.HIGH to stringResource(R.string.language_priority_high),
            )
            SingleChoiceSegmentedButtonRow {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = priority == value,
                        onClick = {
                            if (priority != value) {
                                priority = value
                                LanguageList.setPriority(ctx, locale, value)
                                onChanged()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        label = { Text(label) }
                    )
                }
            }
        }
        if (priority != LanguageList.OFF) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.share_user_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = shared,
                    onCheckedChange = {
                        shared = it
                        LanguagePriority.setSharesUserHistory(ctx.prefs(), locale, it)
                    }
                )
            }
        }
    }
}

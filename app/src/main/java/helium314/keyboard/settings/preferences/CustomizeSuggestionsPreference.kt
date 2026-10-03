// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestionRules
import helium314.keyboard.latin.SuggestionRules.Kind
import helium314.keyboard.latin.SuggestionRules.Rule
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Customize suggestions" (Others): how many suggestions the strip shows, and a rule for each position from the 2nd
 * on ([SuggestionRules]). The 1st suggestion is always the best match.
 */
@Composable
fun CustomizeSuggestionsPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val count = prefs.getInt(Settings.PREF_SUGGESTION_COUNT, Defaults.PREF_SUGGESTION_COUNT)
    val rules = SuggestionRules.parse(prefs.getString(Settings.PREF_SUGGESTION_RULES, Defaults.PREF_SUGGESTION_RULES))
    val set = rules.count { it.kind != Kind.DEFAULT }
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = countLabel(count) + if (set == 0) "" else " · " + stringResource(R.string.suggestion_rules_set, set.toString())
    )
    if (!showDialog) return
    // the keyboard's languages in the order the rules number them: the main one, then the others
    val languages = remember(ctx) { keyboardLanguages(ctx) }
    var newCount by rememberSaveable { mutableIntStateOf(count) }
    // the listed positions, defaults included (unlike the stored form, which drops trailing ones)
    var listedText by rememberSaveable { mutableStateOf(listText(rules)) }
    val listed = SuggestionRules.parse(listedText)
    fun setListed(list: List<Rule>) { listedText = listText(list) }
    var picking by rememberSaveable { mutableIntStateOf(-1) }
    val maxPositions = if (newCount > 0) newCount else SuggestedWords.MAX_SUGGESTIONS

    ThreeButtonAlertDialog(
        onDismissRequest = { showDialog = false },
        onConfirmed = {
            prefs.edit {
                putInt(Settings.PREF_SUGGESTION_COUNT, newCount)
                putString(Settings.PREF_SUGGESTION_RULES, SuggestionRules.encode(listed.take(maxPositions - 1)))
            }
        },
        title = { Text(setting.title) },
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.suggestion_count_title, countLabel(newCount)))
                Slider(
                    value = newCount.toFloat(),
                    onValueChange = { newCount = it.roundToInt() },
                    valueRange = 0f..SuggestedWords.MAX_SUGGESTIONS.toFloat(),
                )
                Text(stringResource(R.string.suggestion_rules_intro), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
                listed.forEachIndexed { i, rule ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picking = i }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(ordinal(i + 2), Modifier.width(52.dp), style = MaterialTheme.typography.titleSmall)
                        Text(ruleName(rule, languages), Modifier.weight(1f))
                        if (i == listed.lastIndex)
                            IconButton(onClick = { setListed(listed.dropLast(1)) }) {
                                Icon(painterResource(R.drawable.ic_bin), stringResource(R.string.delete))
                            }
                    }
                }
                if (listed.size + 1 < maxPositions)
                    TextButton(onClick = { setListed(listed + SuggestionRules.DEFAULT); picking = listed.size }) {
                        Text(stringResource(R.string.suggestion_rule_add, ordinal(listed.size + 2)))
                    }
            }
        },
        neutralButtonText = stringResource(R.string.button_default),
        onNeutral = {
            prefs.edit { remove(Settings.PREF_SUGGESTION_COUNT); remove(Settings.PREF_SUGGESTION_RULES) }
            showDialog = false
        },
    )
    if (picking in listed.indices) {
        val position = picking // the picker is closed (picking = -1) before it reports the choice
        val options = buildList {
            add(SuggestionRules.DEFAULT)
            languages.indices.forEach { add(Rule(Kind.LANGUAGE, it + 1)) }
            add(Rule(Kind.OTHER_FIRST_LETTER))
            add(Rule(Kind.LOW_SCORE))
            languages.indices.forEach { add(Rule(Kind.COMMON, it + 1)) }
        }
        ListPickerDialog(
            onDismissRequest = { picking = -1 },
            items = options,
            onItemSelected = { chosen -> setListed(listed.toMutableList().also { it[position] = chosen }); picking = -1 },
            title = { Text(stringResource(R.string.suggestion_rule_for, ordinal(position + 2))) },
            selectedItem = listed[position],
            getItemName = { ruleName(it, languages) },
        )
    }
}

private fun listText(rules: List<Rule>) =
    rules.joinToString(",") { if (it.kind.hasLanguage) "${it.kind.code}${it.language}" else "${it.kind.code}" }

@Composable
private fun countLabel(count: Int) =
    if (count == 0) stringResource(R.string.suggestion_count_auto) else stringResource(R.string.suggestion_count_n, count.toString())

@Composable
private fun ruleName(rule: Rule, languages: List<String>): String {
    val language = languages.getOrNull(rule.language - 1) ?: stringResource(R.string.suggestion_rule_language_n, rule.language.toString())
    return when (rule.kind) {
        Kind.DEFAULT -> stringResource(R.string.suggestion_rule_default)
        Kind.LANGUAGE -> stringResource(R.string.suggestion_rule_language, language)
        Kind.OTHER_FIRST_LETTER -> stringResource(R.string.suggestion_rule_first_letter)
        Kind.LOW_SCORE -> stringResource(R.string.suggestion_rule_low_score)
        Kind.COMMON -> stringResource(R.string.suggestion_rule_common, language)
    }
}

/** 2nd, 3rd, 4th … 11th, 12th, 13th … 21st. */
private fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

/** The selected keyboard's languages, main one first, as the suggestion rules number them. */
private fun keyboardLanguages(ctx: Context): List<String> {
    val subtype = SubtypeSettings.getSelectedSubtype(ctx.prefs())
    val display = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
    return (listOf(subtype.locale()) + getSecondaryLocales(subtype.extraValue)).distinct().map { it.getDisplayName(display) }
}

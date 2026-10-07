// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
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
    // (the number of suggestions has its own tile since 2026-10-06, SuggestionCountPreference; this row is the order)
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = if (set == 0) stringResource(R.string.suggestion_rule_default)
            else stringResource(R.string.suggestion_rules_set, set.toString())
    )
    if (!showDialog) return
    // the keyboard's languages in the order the rules number them: the main one, then the others
    val languages = remember(ctx) { keyboardLanguages(ctx) }
    // the listed positions, defaults included (unlike the stored form, which drops trailing ones)
    var listedText by rememberSaveable { mutableStateOf(listText(rules)) }
    val listed = SuggestionRules.parse(listedText)
    fun setListed(list: List<Rule>) { listedText = listText(list) }
    var picking by rememberSaveable { mutableIntStateOf(-1) }
    val maxPositions = if (count > 0) count else SuggestedWords.MAX_SUGGESTIONS

    ThreeButtonAlertDialog(
        onDismissRequest = { showDialog = false },
        onConfirmed = {
            prefs.edit { putString(Settings.PREF_SUGGESTION_RULES, SuggestionRules.encode(listed.take(maxPositions - 1))) }
        },
        title = { Text(setting.title) },
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.suggestion_rules_intro),
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
            prefs.edit { remove(Settings.PREF_SUGGESTION_RULES) }
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

/**
 * "Number of suggestions" (its own tile, 2026-10-06): always a number. Automatic (0, as many as fill the strip) shows
 * the count it uses now; − and + step from there to a set number (1 to [SuggestedWords.MAX_SUGGESTIONS]); tapping the
 * number turns it into a box in place to type it (Done or leaving the box saves; empty = automatic again).
 */
@Composable
fun SuggestionCountPreference(setting: Setting) {
    val prefs = LocalContext.current.prefs()
    // (re-read when the stored value changes under it: review 2026-10-06, the tile kept a value the cross had discarded)
    val stored = prefs.getInt(Settings.PREF_SUGGESTION_COUNT, Defaults.PREF_SUGGESTION_COUNT)
    var count by remember(stored) { mutableIntStateOf(stored) }
    var editing by remember { mutableStateOf(false) }
    fun set(n: Int) {
        count = n.coerceIn(0, SuggestedWords.MAX_SUGGESTIONS)
        prefs.edit { putInt(Settings.PREF_SUGGESTION_COUNT, count) }
    }
    // what the strip shows: the set number, or (automatic) as many as fill it now
    val shown = if (count > 0) count else helium314.keyboard.latin.Suggest.stripFillTarget.coerceIn(1, SuggestedWords.MAX_SUGGESTIONS)
    Preference(
        name = setting.title,
        onClick = { editing = true },
        description = if (count == 0) stringResource(R.string.suggestion_count_fills) else null,
    ) {
        TextButton(onClick = { set(shown - 1) }, enabled = shown > 1) { Text("−", style = MaterialTheme.typography.titleLarge) }
        if (editing) {
            val state = androidx.compose.foundation.text.input.rememberTextFieldState(
                shown.toString(), androidx.compose.ui.text.TextRange(0, shown.toString().length))
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            var hadFocus by remember { mutableStateOf(false) }
            fun commit() {
                if (!editing) return
                set(state.text.toString().trim().toIntOrNull() ?: 0)
                editing = false
            }
            androidx.compose.foundation.text.BasicTextField(
                state = state,
                modifier = Modifier.width(56.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) commit() },
                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                onKeyboardAction = { commit() },
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                inputTransformation = { if (!asCharSequence().all { it.isDigit() } || length > 3) revertAllChanges() },
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        } else TextButton(onClick = { editing = true }) {
            Text(shown.toString(), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { set(shown + 1) }, enabled = shown < SuggestedWords.MAX_SUGGESTIONS) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun listText(rules: List<Rule>) =
    rules.joinToString(",") { if (it.kind.hasLanguage) "${it.kind.code}${it.language}" else "${it.kind.code}" }

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

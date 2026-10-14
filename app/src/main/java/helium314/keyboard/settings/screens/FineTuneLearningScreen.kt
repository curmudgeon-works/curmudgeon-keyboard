// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.LearnableText
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity

/**
 * "Fine-tune learning" (2026-10-10, split from Refine, now "Fine-tune suggestions"): how the keyboard learns your
 * words: when your own words win over corrections, the log of what corrections do to the learned words, and what a
 * space and a tap in the strip learn (2026-10-11). How much each action counts, forgetting and removed words are to come
 * here as numbers. Advanced only; one set for all keyboards unless App settings > Per keyboard says otherwise.
 */
@Composable
fun FineTuneLearningScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // every change applies at once; the top bar's tick keeps the changes since the screen opened, the cross undoes them
    val draft = helium314.keyboard.settings.rememberPrefsDraft("finetunelearning",
        listOf(Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT) + LearnableText.keys, onClickBack)
    SearchSettingsScreen(
        onClickBack = draft.leave,
        title = stringResource(R.string.fine_tune_learning_screen),
        topActions = draft.topActions,
        settings = emptyList(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalRowStart provides 22.dp) {
            GroupTitle(R.string.learning_group)
            androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalPendingChange provides
                (Settings.PREF_AUTOCORRECT_FREQUENT_WORDS in draft.pending || Settings.PREF_TRUST_TYPED_COUNT in draft.pending)) {
                SettingsActivity.settingsContainer[Settings.PREF_AUTOCORRECT_FREQUENT_WORDS]?.Preference()
            }
            SettingsActivity.settingsContainer[Settings.PREF_LEARNING_LOG]?.Preference()
            GroupTitle(R.string.learn_table_title)
            LearnTable(draft.pending)
          }
        }
    }
    draft.dialogs()
}

private val kindNames = mapOf(
    LearnableText.Kind.WORD to (R.string.learn_kind_word to R.string.learn_kind_word_example),
    LearnableText.Kind.DOTTED to (R.string.learn_kind_dotted to R.string.learn_kind_dotted_example),
    LearnableText.Kind.EMAIL to (R.string.learn_kind_email to R.string.learn_kind_email_example),
    LearnableText.Kind.NUMBER to (R.string.learn_kind_number to R.string.learn_kind_number_example),
    LearnableText.Kind.WITH_DIGITS to (R.string.learn_kind_digits to R.string.learn_kind_digits_example),
    LearnableText.Kind.OTHER to (R.string.learn_kind_other to R.string.learn_kind_other_example),
)

/** "What gets learned": a row per kind of text (an example under its name), a tick for Space and one for Strip tap.
 *  A row in italics has a change not yet kept ([pending]). */
@Composable
private fun LearnTable(pending: Set<String>) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    for (kind in LearnableText.Kind.entries) {
        helium314.keyboard.settings.KnownDefaults.note(kind.spaceKey, kind.bySpace)
        helium314.keyboard.settings.KnownDefaults.note(kind.stripKey, kind.byStrip)
    }
    val column = Modifier.width(72.dp)
    Text(stringResource(R.string.learn_table_summary), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, end = 16.dp, bottom = 4.dp))
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.learn_table_space), column, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.learn_table_strip), column, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
    for (kind in LearnableText.Kind.entries) {
        val (name, example) = kindNames.getValue(kind)
        val space = prefs.getBoolean(kind.spaceKey, kind.bySpace)
        val strip = prefs.getBoolean(kind.stripKey, kind.byStrip)
        val changed = kind.spaceKey in pending || kind.stripKey in pending
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(stringResource(name), style = MaterialTheme.typography.bodyLarge.let {
                    if (changed) it.copy(fontStyle = FontStyle.Italic) else it })
                Text(stringResource(example), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(column, contentAlignment = Alignment.Center) {
                Checkbox(checked = space, onCheckedChange = { prefs.edit { putBoolean(kind.spaceKey, it) } })
            }
            Box(column, contentAlignment = Alignment.Center) {
                Checkbox(checked = strip, onCheckedChange = { prefs.edit { putBoolean(kind.stripKey, it) } })
            }
        }
    }
}

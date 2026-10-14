// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity

/**
 * "Fine-tune learning" (2026-10-10, split from Refine, now "Fine-tune suggestions"): how the keyboard learns your
 * words. Today when your own words win over corrections and the log of what corrections do to the learned words; the
 * learning rules (what a space and a strip tap learn, how much each action counts, forgetting, removed words) are to
 * come here as numbers. Advanced only; one set for all keyboards unless App settings > Per keyboard says otherwise.
 */
@Composable
fun FineTuneLearningScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // every change applies at once; the top bar's tick keeps the changes since the screen opened, the cross undoes them
    val draft = helium314.keyboard.settings.rememberPrefsDraft("finetunelearning",
        listOf(Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT), onClickBack)
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
          }
        }
    }
    draft.dialogs()
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity

/**
 * Settings few people need, kept out of the way (advanced only): custom toolbar key codes, the emoji key of a physical keyboard, the timestamp key's format, the
 * currencies on the symbols pages' currency key.
 */
@Composable
fun OthersScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) Log.v("irrelevant", "recompose on preference change")
    val items = listOf(
        Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, // what a toolbar key sends on tap / long-press
        Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY,
        Settings.PREF_TIMESTAMP_FORMAT,
        // (Customize suggestions: Text correction's Suggestions group; their spacing: Appearance, a theme setting;
        //  undo / redo: their own group on Layout & Typing)
        Settings.PREF_CUSTOM_CURRENCY_KEY, // the symbols pages' currency key and its popup
        // (the symbols on the keys area by area are gone as settings: Appearance's "Hide symbols on keys" sets all three)
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_others),
        settings = items,
        simpleModeKeys = emptySet(), // advanced-only screen
    )
}

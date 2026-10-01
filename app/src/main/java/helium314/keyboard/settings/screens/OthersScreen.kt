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
 * Settings few people need, kept out of the way (advanced only): the toolbar opening and closing by itself and
 * pinning a toolbar key by long-press (only for a toolbar that opens with the arrow), custom toolbar key codes, the
 * toolbar reversed for right-to-left languages, the emoji key of a physical keyboard, the timestamp key's format, the
 * currencies on the symbols pages' currency key.
 */
@Composable
fun OthersScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) Log.v("irrelevant", "recompose on preference change")
    val expandable = Settings.readToolbarMode(prefs) == ToolbarMode.EXPANDABLE
    val items = listOf(
        if (expandable) Settings.PREF_AUTO_SHOW_TOOLBAR else null,
        if (expandable) Settings.PREF_AUTO_HIDE_TOOLBAR else null,
        if (expandable) Settings.PREF_QUICK_PIN_TOOLBAR_KEYS else null,
        Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, // what a toolbar key sends on tap / long-press
        if (Settings.readToolbarMode(prefs) != ToolbarMode.HIDDEN) Settings.PREF_VARIABLE_TOOLBAR_DIRECTION else null,
        Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY,
        Settings.PREF_TIMESTAMP_FORMAT,
        Settings.PREF_SUGGESTION_RULES, // Customize suggestions: how many, and rules for the 2nd one on
        Settings.PREF_SUGGESTION_WORD_PADDING, // and how far apart they sit (was inside the suggestion font dialog)
        Settings.PREF_UNDO_HISTORY_LENGTH, // the keyboard's own undo / redo
        Settings.PREF_UNDO_UNIT,
        Settings.PREF_REDO_UNIT,
        Settings.PREF_CUSTOM_CURRENCY_KEY, // the symbols pages' currency key and its popup
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_others),
        settings = items,
        simpleModeKeys = emptySet(), // advanced-only screen
    )
}

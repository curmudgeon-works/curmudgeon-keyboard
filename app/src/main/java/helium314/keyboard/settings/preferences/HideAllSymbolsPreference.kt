// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity

/** The three "show symbols" preferences the one switch stands for: number row, non-number keys, bottom-right ellipsis. */
val symbolHintPrefs = listOf(
    Settings.PREF_SHOW_NUMBER_ROW_HINTS to Defaults.PREF_SHOW_NUMBER_ROW_HINTS,
    Settings.PREF_SHOW_HINTS to Defaults.PREF_SHOW_HINTS,
    Settings.PREF_SHOW_POPUP_HINTS to Defaults.PREF_SHOW_POPUP_HINTS,
)

/** The symbols are drawn when a keyboard is built, and built keyboards are cached. */
fun reloadSymbolHints() {
    KeyboardLayoutSet.onSystemLocaleChanged()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

/**
 * "Hide symbols on keys": a plain switch, on when all three are hidden (the per-area rows on Others can hide some: then
 * it's off, and turning it on hides them all). Off shows them all.
 */
@Composable
fun HideAllSymbolsPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) Log.v("irrelevant", "recompose on preference change")
    val allHidden = symbolHintPrefs.all { (key, default) -> !prefs.getBoolean(key, default) }
    fun set(hide: Boolean) {
        prefs.edit { symbolHintPrefs.forEach { (key, _) -> putBoolean(key, !hide) } }
        reloadSymbolHints()
    }
    Preference(name = setting.title, description = setting.description, onClick = { set(!allHidden) }) {
        Switch(checked = allHidden, onCheckedChange = { set(it) })
    }
}

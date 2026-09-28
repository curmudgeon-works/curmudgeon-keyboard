// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.preferences.ToolbarKeysPreference
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardLayoutSet
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ToolbarKeysCustomizer
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.ReorderSwitchPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.previewDark

@Composable
fun ToolbarScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val items = toolbarItems(prefs)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_toolbar),
        settings = items,
        simpleModeKeys = emptySet(), // advanced-only screen: empties if the switch is turned off while here
    )
}

/** The toolbar's rows as they apply now (they depend on the toolbar mode); on Layout & Typing's Toolbar group. */
fun toolbarItems(prefs: SharedPreferences): List<String?> {
    val toolbarMode = Settings.readToolbarMode(prefs)
    val clipboardToolbarVisible = toolbarMode != ToolbarMode.HIDDEN
        || !prefs.getBoolean(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
    return listOf(
        Settings.PREF_TOOLBAR_VISIBILITY, // (with Text correction's Show suggestions: what the row above the keys shows)
        if (toolbarMode == ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_HIDING_GLOBAL else null,
        // (swipe down on it to hide the keyboard: on Swiping)
        // the main, clipboard and pinned toolbars' keys in one list (M / C / P on each key)
        SettingsWithoutKey.TOOLBAR_KEYS_ALL,
        // (auto show / auto hide, pinning by long-press, custom key codes, variable direction: on Others)
        // the button that opens and closes the toolbar (moved here from the Suggestion strip font dialog)
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_TOOLBAR_EXPAND_ICON else null,
    )
}

/** Every key the toolbar rows write (Layout & Typing's Keep / Discard). */
val toolbarKeys = listOf(
    Settings.PREF_TOOLBAR_MODE, Settings.PREF_TOOLBAR_HIDING_GLOBAL, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE,
    Settings.PREF_TOOLBAR_KEYS, Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS,
    Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Settings.PREF_QUICK_PIN_TOOLBAR_KEYS, Settings.PREF_AUTO_SHOW_TOOLBAR,
    Settings.PREF_AUTO_HIDE_TOOLBAR, Settings.PREF_VARIABLE_TOOLBAR_DIRECTION, Settings.PREF_TOOLBAR_EXPAND_ICON,
    Settings.PREF_TOOLBAR_VISIBILITY, Settings.PREF_TOOLBAR_OPENED_BY_KEY,
)

fun createToolbarSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TOOLBAR_VISIBILITY, R.string.toolbar_visibility) {
        // with suggestions off there is no row for an arrow: the toolbar opens from the top-left key's long-press
        val suggestions = LocalContext.current.prefs().getBoolean(Settings.PREF_SHOW_SUGGESTIONS, Defaults.PREF_SHOW_SUGGESTIONS)
        val items = if (suggestions) listOf(
            stringResource(R.string.toolbar_visibility_always) to Settings.TOOLBAR_ALWAYS,
            stringResource(R.string.toolbar_visibility_above) to Settings.TOOLBAR_ABOVE,
            stringResource(R.string.toolbar_visibility_in_place) to Settings.TOOLBAR_IN_PLACE,
            stringResource(R.string.toolbar_visibility_hidden) to Settings.TOOLBAR_HIDDEN,
        ) else listOf(
            stringResource(R.string.toolbar_visibility_always) to Settings.TOOLBAR_ALWAYS,
            stringResource(R.string.toolbar_visibility_from_key) to Settings.TOOLBAR_FROM_KEY,
            stringResource(R.string.toolbar_visibility_hidden) to Settings.TOOLBAR_HIDDEN,
        )
        // shown as what applies: "above" / "in place" read as "from the top-left key" while suggestions are off
        val prefs = LocalContext.current.prefs()
        val stored = Settings.readToolbarVisibility(prefs)
        val shown = if (!suggestions && stored != Settings.TOOLBAR_ALWAYS && stored != Settings.TOOLBAR_HIDDEN) Settings.TOOLBAR_FROM_KEY
            else if (suggestions && stored == Settings.TOOLBAR_FROM_KEY) Settings.TOOLBAR_ABOVE else stored
        if (shown != prefs.getString(Settings.PREF_TOOLBAR_VISIBILITY, null)) prefs.edit { putString(Settings.PREF_TOOLBAR_VISIBILITY, shown) }
        ListPreference(it, items, shown) {
            KeyboardLayoutSet.onSystemLocaleChanged() // (the top-left key's popup comes and goes)
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_EXPAND_ICON, R.string.toolbar_button) {
        ListPreference(it, listOf(
            stringResource(R.string.pref_toolbar_expand_icon_arrow) to "arrow",
            stringResource(R.string.pref_toolbar_expand_icon_incognito) to "incognito",
            stringResource(R.string.pref_toolbar_expand_icon_settings) to "settings",
            stringResource(R.string.pref_toolbar_expand_icon_none) to "none",
        ), Defaults.PREF_TOOLBAR_EXPAND_ICON) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_TOOLBAR_HIDING_GLOBAL, R.string.toolbar_hiding_global) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_HIDING_GLOBAL) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE, R.string.toolbar_swipe_down_to_hide, R.string.toolbar_swipe_down_to_hide_summary) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE)
    },
    Setting(context, SettingsWithoutKey.TOOLBAR_KEYS_ALL, R.string.toolbar_keys) {
        ToolbarKeysPreference(it)
    },
    // (each list on its own too, found by search)
    Setting(context, Settings.PREF_TOOLBAR_KEYS, R.string.main_toolbar_keys) {
        ReorderSwitchPreference(it, Defaults.PREF_TOOLBAR_KEYS)
    },
    Setting(context, Settings.PREF_PINNED_TOOLBAR_KEYS, R.string.pinned_toolbar_keys) {
        ReorderSwitchPreference(it, Defaults.PREF_PINNED_TOOLBAR_KEYS)
    },
    Setting(context, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, R.string.clipboard_toolbar_keys) {
        ReorderSwitchPreference(it, Defaults.PREF_CLIPBOARD_TOOLBAR_KEYS)
    },
    Setting(context, Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, R.string.customize_toolbar_key_codes) {
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = it.title,
            onClick = { showDialog = true },
        )
        if (showDialog)
            ToolbarKeysCustomizer(
                key = it.key,
                onDismissRequest = { showDialog = false }
            )
    },
    Setting(context, Settings.PREF_QUICK_PIN_TOOLBAR_KEYS,
        R.string.quick_pin_toolbar_keys, R.string.quick_pin_toolbar_keys_summary)
    {
        SwitchPreference(it, Defaults.PREF_QUICK_PIN_TOOLBAR_KEYS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_AUTO_SHOW_TOOLBAR, R.string.auto_show_toolbar, R.string.auto_show_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_SHOW_TOOLBAR)
    },
    Setting(context, Settings.PREF_AUTO_HIDE_TOOLBAR, R.string.auto_hide_toolbar, R.string.auto_hide_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_HIDE_TOOLBAR)
    },
    Setting(context, Settings.PREF_VARIABLE_TOOLBAR_DIRECTION,
        R.string.var_toolbar_direction, R.string.var_toolbar_direction_summary)
    {
        SwitchPreference(it, Defaults.PREF_VARIABLE_TOOLBAR_DIRECTION)
    }
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            ToolbarScreen { }
        }
    }
}

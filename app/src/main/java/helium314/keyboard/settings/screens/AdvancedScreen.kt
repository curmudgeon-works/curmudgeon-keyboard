// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_ALL
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MAIN
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MORE
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SystemBroadcastReceiver
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.checkTimestampFormat
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.preferences.FactoryResetPreference
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.BackupRestorePreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity

@Composable
fun AdvancedSettingsScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val items = listOf(
        // (force incognito: on Text correction, next to learning from what you type)
        // (on Layout & Typing: long-press delay and symbols-key numpad (Typing), space key changes input method
        //  (Layout), delete swipe (Backspace); space bar swipes on Swiping; "more diacritics" is the popup presets)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.PREF_SHOW_SETUP_WIZARD_ICON else null,
        // (switching back to letters after…: one row with a dialog on Layout & Typing's Typing group)
        // (the physical keyboard's emoji key and the timestamp format: on Others)
        SettingsWithoutKey.BACKUP_RESTORE,
        SettingsWithoutKey.FACTORY_RESET, // advanced only (not in the simple set below): tinted
        if (BuildConfig.DEBUG || prefs.getBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, Defaults.PREF_SHOW_DEBUG_SETTINGS))
            SettingsWithoutKey.DEBUG_SETTINGS else null,
        R.string.settings_category_experimental,
        Settings.PREF_EMOJI_MAX_SDK,
        Settings.PREF_URL_DETECTION,
        Settings.PREF_RECORD_GESTURE_CORPUS,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_advanced),
        settings = items,
        simpleModeKeys = setOf(
            SettingsWithoutKey.BACKUP_RESTORE,
            DebugSettings.PREF_SHOW_DEBUG_SETTINGS, SettingsWithoutKey.DEBUG_SETTINGS,
        ),
    )
}

@SuppressLint("ApplySharedPref")
fun createAdvancedSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_ALWAYS_INCOGNITO_MODE,
        R.string.incognito, R.string.prefs_force_incognito_mode_summary)
    {
        SwitchPreference(it, Defaults.PREF_ALWAYS_INCOGNITO_MODE) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEY_LONGPRESS_TIMEOUT, R.string.prefs_key_longpress_timeout_settings) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEY_LONGPRESS_TIMEOUT,
            range = 100f..700f,
            description = { stringResource(R.string.abbreviation_unit_milliseconds, it.toString()) }
        )
    },
    Setting(context, Settings.PREF_SPACE_HORIZONTAL_SWIPE, R.string.show_horizontal_space_swipe) {
        val items = listOf(
            stringResource(R.string.space_swipe_move_cursor_entry) to KeyboardActionListener.SwipeAction.MOVE_CURSOR.name,
            stringResource(R.string.switch_language) to KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE.name,
            stringResource(R.string.space_swipe_toggle_numpad_entry) to KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD.name,
            stringResource(R.string.action_none) to KeyboardActionListener.SwipeAction.NONE.name,
        )
        ListPreference(it, items, Defaults.PREF_SPACE_HORIZONTAL_SWIPE)
    },
    Setting(context, Settings.PREF_SPACE_VERTICAL_SWIPE, R.string.show_vertical_space_swipe) {
        val items = listOf(
            stringResource(R.string.space_swipe_move_cursor_entry) to KeyboardActionListener.SwipeAction.MOVE_CURSOR.name,
            stringResource(R.string.switch_language) to KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE.name,
            stringResource(R.string.space_swipe_toggle_numpad_entry) to KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD.name,
            stringResource(R.string.space_swipe_hide_keyboard_entry) to KeyboardActionListener.SwipeAction.HIDE_KEYBOARD.name,
            stringResource(R.string.space_swipe_touchpad_mode_entry) to KeyboardActionListener.SwipeAction.TOUCHPAD_MODE.name,
            stringResource(R.string.action_none) to KeyboardActionListener.SwipeAction.NONE.name,
        )
        ListPreference(it, items, Defaults.PREF_SPACE_VERTICAL_SWIPE)
    },
    Setting(context, Settings.PREF_LANGUAGE_SWIPE_DISTANCE, R.string.prefs_language_swipe_distance) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_LANGUAGE_SWIPE_DISTANCE,
            range = 2f..18f,
            description = { it.toString() }
        )
    },
    Setting(context, Settings.PREF_TOUCHPAD_SENSITIVITY, R.string.touchpad_sensitivity) {
        SliderPreference(
            name = it.title,
            key = it.key,
            default = Defaults.PREF_TOUCHPAD_SENSITIVITY,
            range = 0f..100f,
            description = { value -> value.toInt().toString() }
        )
    },
    Setting(context, Settings.PREF_DELETE_SWIPE, R.string.delete_swipe) {
        DeleteSwipePreference(it)
    },
    Setting(context, Settings.PREF_SPACE_TO_CHANGE_LANG,
        R.string.prefs_long_press_keyboard_to_change_lang)
    {
        SwitchPreference(it, Defaults.PREF_SPACE_TO_CHANGE_LANG)
    },
    Setting(context, Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD, R.string.prefs_long_press_symbol_for_numpad) {
        SwitchPreference(it, Defaults.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD)
    },
    Setting(context, Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY, R.string.prefs_enable_emoji_alt_physical_key,
        R.string.prefs_enable_emoji_alt_physical_key_summary)
    {
        SwitchPreference(it, Defaults.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY)
    },
    Setting(context, Settings.PREF_SHOW_SETUP_WIZARD_ICON, R.string.show_setup_wizard_icon, R.string.show_setup_wizard_icon_summary) {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_SHOW_SETUP_WIZARD_ICON) { SystemBroadcastReceiver.toggleAppIcon(ctx) }
    },
    Setting(context, Settings.PREF_ABC_AFTER_SYMBOL_SPACE,
        R.string.switch_keyboard_after, R.string.after_symbol_and_space)
    {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_SYMBOL_SPACE)
    },
    Setting(context, Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
        R.string.switch_keyboard_after, R.string.after_numpad_and_space)
    {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_NUMPAD_SPACE)
    },
    Setting(context, Settings.PREF_ABC_AFTER_EMOJI, R.string.switch_keyboard_after, R.string.after_emoji) {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_EMOJI)
    },
    Setting(context, Settings.PREF_ABC_AFTER_CLIP, R.string.switch_keyboard_after, R.string.after_clip) {
        SwitchPreference(it, Defaults.PREF_ABC_AFTER_CLIP)
    },
    Setting(context, Settings.PREF_CUSTOM_CURRENCY_KEY, R.string.customize_currencies) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            val prefs = LocalContext.current.prefs()
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.customize_currencies_detail)) },
                initialText = prefs.getString(setting.key, Defaults.PREF_CUSTOM_CURRENCY_KEY)!!,
                onConfirmed = { prefs.edit { putString(setting.key, it) }; KeyboardLayoutSet.onSystemLocaleChanged() },
                title = { Text(stringResource(R.string.customize_currencies)) },
                neutralButtonText = if (prefs.contains(setting.key)) stringResource(R.string.button_default) else null,
                onNeutral = { prefs.edit { remove(setting.key)}; KeyboardLayoutSet.onSystemLocaleChanged() },
                checkTextValid = { text -> text.splitOnWhitespace().none { it.length > 8 } }
            )
        }
    },
    Setting(context, Settings.PREF_MORE_POPUP_KEYS, R.string.show_popup_keys_title) {
        val items = listOf(POPUP_KEYS_NORMAL, POPUP_KEYS_MAIN, POPUP_KEYS_MORE, POPUP_KEYS_ALL).map { setting ->
            stringResource(morePopupKeysResId(setting)) to setting
        }
        ListPreference(it, items, Defaults.PREF_MORE_POPUP_KEYS) { KeyboardLayoutSet.onSystemLocaleChanged() }
    },
    Setting(context, SettingsWithoutKey.BACKUP_RESTORE, R.string.backup_restore_title) {
        BackupRestorePreference(it)
    },
    Setting(context, SettingsWithoutKey.FACTORY_RESET, R.string.factory_reset, R.string.factory_reset_summary) {
        FactoryResetPreference(it)
    },
    Setting(context, Settings.PREF_TIMESTAMP_FORMAT, R.string.timestamp_format_title) { setting ->
        TextInputPreference(setting, Defaults.PREF_TIMESTAMP_FORMAT, stringResource(R.string.timestamp_description)) { checkTimestampFormat(it) }
    },
    Setting(context, SettingsWithoutKey.DEBUG_SETTINGS, R.string.debug_settings_title) {
        Preference(
            name = it.title,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.Debug) }
        ) { NextScreenIcon() }
    },
    Setting(context, Settings.PREF_EMOJI_MAX_SDK, R.string.prefs_key_emoji_max_sdk) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = 0,
            range = 21f..36f,
            description = {
                "Android " + when(it) {
                    21 -> "5.0"
                    22 -> "5.1"
                    23 -> "6"
                    24 -> "7.0"
                    25 -> "7.1"
                    26 -> "8.0"
                    27 -> "8.1"
                    28 -> "9"
                    29 -> "10"
                    30 -> "11"
                    31 -> "12"
                    32 -> "12L"
                    33 -> "13"
                    34 -> "14"
                    35 -> "15"
                    36 -> "16"
                    else -> "version unknown"
                }
            },
            onConfirmed = {
                SupportedEmojis.load(ctx)
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
        )
    },
    Setting(context, Settings.PREF_URL_DETECTION, R.string.url_detection_title, R.string.url_detection_summary) {
        SwitchPreference(it, Defaults.PREF_URL_DETECTION)
    },
    Setting(context, Settings.PREF_RECORD_GESTURE_CORPUS, R.string.record_gesture_corpus, R.string.record_gesture_corpus_summary) {
        SwitchPreference(it, Defaults.PREF_RECORD_GESTURE_CORPUS)
    },
)

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AdvancedSettingsScreen { }
        }
    }
}

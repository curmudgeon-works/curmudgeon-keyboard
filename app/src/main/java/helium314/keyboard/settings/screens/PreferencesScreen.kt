// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import helium314.keyboard.keyboard.internal.keyboard_parser.KeyboardParser
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.ReorderSwitchPreference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.SwitchPreferenceWithEmojiDictWarning
import helium314.keyboard.latin.utils.previewDark

@Composable
fun PreferencesScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // input and clipboard history; the additional keys are on each keyboard's Layout screen
    val items = preferencesInputItems(prefs) + clipboardHistoryItems(prefs)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_preferences),
        settings = items,
        simpleModeKeys = preferencesSimpleModeKeys,
    )
}

/** The input group, shown at the top of the Layout screen (the Preferences screen stays reachable through search);
 *  hints, the symbol map, the per-key popups (which replaced the popup order) and the TLD popups
 *  are on the Layout screen. */
fun preferencesInputItems(prefs: SharedPreferences): List<Any?> =
    listOf(
        R.string.settings_category_input,
        Settings.PREF_POPUP_ON,
        if (AudioAndHapticFeedbackManager.getInstance().hasVibrator())
            Settings.PREF_VIBRATE_ON else null,
        if (prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON))
            Settings.PREF_VIBRATION_DURATION_SETTINGS else null,
        if (prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON))
            Settings.PREF_VIBRATE_IN_DND_MODE else null,
        Settings.PREF_SOUND_ON,
        if (prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON))
            Settings.PREF_KEYPRESS_SOUND_VOLUME else null,
        Settings.PREF_SAVE_SUBTYPE_PER_APP,
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
    )

fun clipboardHistoryItems(prefs: SharedPreferences): List<Any?> {
    val clipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    return listOf(
        R.string.settings_category_clipboard_history,
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
    )
}

val preferencesSimpleModeKeys = setOf(
    Settings.PREF_SHOW_HINTS, Settings.PREF_SYMBOL_POPUP_MAP, Settings.PREF_POPUP_ON,
    Settings.PREF_VIBRATE_ON, Settings.PREF_SOUND_ON, Settings.PREF_SHOW_NUMBER_ROW,
    Settings.PREF_SHOW_EMOJI_KEY, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
)

fun createPreferencesSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS, R.string.backspace_hold_deletes_words, R.string.backspace_hold_deletes_words_summary) {
        SwitchPreference(it, Defaults.PREF_BACKSPACE_HOLD_DELETES_WORDS)
    },
    Setting(context, Settings.PREF_BACKSPACE_REPEAT_INTERVAL, R.string.backspace_repeat_interval) {
        BackspaceSpeedPreference(it)
    },
    Setting(context, Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD, R.string.backspace_deletes_swiped_word, R.string.backspace_deletes_swiped_word_summary) {
        SwitchPreference(it, Defaults.PREF_BACKSPACE_DELETES_SWIPED_WORD)
    },
    Setting(context, Settings.PREF_SAVE_SUBTYPE_PER_APP, R.string.save_subtype_per_app) {
        SwitchPreference(it, Defaults.PREF_SAVE_SUBTYPE_PER_APP)
    },
    Setting(context, Settings.PREF_SHOW_HINTS, R.string.hints_other_keys, R.string.show_hints_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_HINTS) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_SHOW_LETTER_HINTS, R.string.letter_hints, R.string.letter_hints_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_LETTER_HINTS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_POPUP_KEYS_ORDER, R.string.popup_order) {
        ReorderSwitchPreference(it, Defaults.PREF_POPUP_KEYS_ORDER)
    },
    Setting(
        context, Settings.PREF_SHOW_TLD_POPUP_KEYS, R.string.show_tld_popup_keys,
        R.string.show_tld_popup_keys_summary
    ) {
        SwitchPreference(it, Defaults.PREF_SHOW_TLD_POPUP_KEYS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SYMBOL_POPUP_MAP, R.string.symbol_popup_map, R.string.symbol_popup_map_summary) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            val prefs = LocalContext.current.prefs()
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                textInputLabel = { Text(stringResource(R.string.symbol_popup_map_detail)) },
                initialText = prefs.getString(setting.key, Defaults.PREF_SYMBOL_POPUP_MAP)!!,
                onConfirmed = { prefs.edit { putString(setting.key, it.trim()) }; KeyboardLayoutSet.onSystemLocaleChanged() },
                title = { Text(stringResource(R.string.symbol_popup_map)) },
                neutralButtonText = if (prefs.contains(setting.key)) stringResource(R.string.button_default) else null,
                onNeutral = { prefs.edit { remove(setting.key) }; KeyboardLayoutSet.onSystemLocaleChanged() },
                singleLine = false,
                checkTextValid = { KeyboardParser.isValidSymbolPopupMap(it) }
            )
        }
    },
    Setting(context, Settings.PREF_SHOW_POPUP_HINTS, R.string.show_popup_hints, R.string.show_popup_hints_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_POPUP_HINTS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_POPUP_ON, R.string.popup_on_keypress) {
        SwitchPreference(it, Defaults.PREF_POPUP_ON) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_VIBRATE_ON, R.string.vibrate_on_keypress) {
        SwitchPreference(it, Defaults.PREF_VIBRATE_ON)
    },
    Setting(context, Settings.PREF_VIBRATE_IN_DND_MODE, R.string.vibrate_in_dnd_mode) {
        SwitchPreference(it, Defaults.PREF_VIBRATE_IN_DND_MODE)
    },
    Setting(context, Settings.PREF_SOUND_ON, R.string.sound_on_keypress) {
        SwitchPreference(it, Defaults.PREF_SOUND_ON)
    },
    Setting(context, Settings.PREF_SHOW_EMOJI_DESCRIPTIONS, R.string.show_emoji_descriptions) {
        SwitchPreferenceWithEmojiDictWarning(it, Defaults.PREF_SHOW_EMOJI_DESCRIPTIONS)
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW, R.string.number_row, R.string.number_row_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, R.string.number_row_in_symbols) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_LOCALIZED_NUMBER_ROW, R.string.localized_number_row, R.string.localized_number_row_summary) {
        SwitchPreference(it, Defaults.PREF_LOCALIZED_NUMBER_ROW) {
            KeyboardLayoutSet.onSystemLocaleChanged()
            KeyboardSwitcher.getInstance().reloadKeyboard()
        }
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW_HINTS, R.string.number_row_hints) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW_HINTS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, R.string.show_language_switch_key) {
        SwitchPreference(it, Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_LANGUAGE_SWITCH_KEY, R.string.language_switch_key_behavior) {
        ListPreference(
            it,
            listOf(
                stringResource(R.string.switch_language) to "internal",
                stringResource(R.string.language_switch_key_switch_input_method) to "input_method",
                stringResource(R.string.language_switch_key_switch_both) to "both"
            ),
            Defaults.PREF_LANGUAGE_SWITCH_KEY
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_EMOJI_KEY, R.string.show_emoji_key) {
        SwitchPreference(it, Defaults.PREF_SHOW_EMOJI_KEY) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        R.string.remove_redundant_popups, R.string.remove_redundant_popups_summary)
    {
        SwitchPreference(it, Defaults.PREF_REMOVE_REDUNDANT_POPUPS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        R.string.enable_clipboard_history, R.string.enable_clipboard_history_summary)
    {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY) { ClipboardDao.getInstance(ctx)?.clearNonPinned() }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, R.string.clipboard_history_retention_time) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME,
            description = {
                if (it > 120) stringResource(R.string.settings_no_limit)
                else stringResource(R.string.abbreviation_unit_minutes, it.toString())
            },
            range = 1f..121f,
        ) { ClipboardDao.getInstance(ctx)?.clearOldClips(true) }
    },
    Setting(context, Settings.PREF_VIBRATION_DURATION_SETTINGS, R.string.prefs_keypress_vibration_duration_settings) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VIBRATION_DURATION_SETTINGS,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else stringResource(R.string.abbreviation_unit_milliseconds, it.toString())
            },
            range = -1f..100f,
            onValueChanged = { it?.let { AudioAndHapticFeedbackManager.getInstance().vibrate(it.toLong()) } }
        )
    },
    Setting(context, Settings.PREF_KEYPRESS_SOUND_VOLUME, R.string.prefs_keypress_sound_volume_settings) { setting ->
        val audioManager = LocalContext.current.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEYPRESS_SOUND_VOLUME,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else (it * 100).toInt().toString()
            },
            range = -0.01f..1f,
            onValueChanged = { it?.let { audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, it) } }
        )
    },
)

// todo (later): not good to have it hardcoded, but reading a bunch of files may be noticeably slow
private val localesWithLocalizedNumberRow = listOf("ar", "bn", "fa", "gu", "hi", "kn", "mr", "ne", "ur")

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            PreferencesScreen { }
        }
    }
}

/**
 * Hold-to-delete speed: the interval between deletions, and optionally a speed-up — after holding for a while
 * the interval ramps (over a second) to a faster top speed. All in one dialog.
 */
@Composable
private fun BackspaceSpeedPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val interval = prefs.getInt(Settings.PREF_BACKSPACE_REPEAT_INTERVAL, Defaults.PREF_BACKSPACE_REPEAT_INTERVAL)
    val speedUp = prefs.getBoolean(Settings.PREF_BACKSPACE_SPEED_UP, Defaults.PREF_BACKSPACE_SPEED_UP)
    val after = prefs.getInt(Settings.PREF_BACKSPACE_SPEED_UP_AFTER, Defaults.PREF_BACKSPACE_SPEED_UP_AFTER)
    val top = prefs.getInt(Settings.PREF_BACKSPACE_TOP_INTERVAL, Defaults.PREF_BACKSPACE_TOP_INTERVAL)
    fun seconds(ms: Int) = String.format(java.util.Locale.getDefault(), "%.1f", ms / 1000f)
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = stringResource(R.string.backspace_repeat_interval_value, interval) +
            if (speedUp) " · " + stringResource(R.string.backspace_speed_up_value, top, seconds(after)) else "",
    )
    if (!showDialog) return
    var newInterval by rememberSaveable { mutableFloatStateOf(interval.toFloat()) }
    var newSpeedUp by rememberSaveable { mutableStateOf(speedUp) }
    var newAfter by rememberSaveable { mutableFloatStateOf(after.toFloat()) }
    var newTop by rememberSaveable { mutableFloatStateOf(top.toFloat()) }
    ThreeButtonAlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(setting.title) },
        neutralButtonText = stringResource(R.string.button_default),
        onNeutral = {
            showDialog = false
            prefs.edit {
                remove(Settings.PREF_BACKSPACE_REPEAT_INTERVAL); remove(Settings.PREF_BACKSPACE_SPEED_UP)
                remove(Settings.PREF_BACKSPACE_SPEED_UP_AFTER); remove(Settings.PREF_BACKSPACE_TOP_INTERVAL)
            }
        },
        onConfirmed = {
            prefs.edit {
                putInt(Settings.PREF_BACKSPACE_REPEAT_INTERVAL, newInterval.toInt())
                putBoolean(Settings.PREF_BACKSPACE_SPEED_UP, newSpeedUp)
                putInt(Settings.PREF_BACKSPACE_SPEED_UP_AFTER, newAfter.toInt())
                putInt(Settings.PREF_BACKSPACE_TOP_INTERVAL, newTop.toInt())
            }
        },
        content = {
            Column {
                Text(stringResource(R.string.backspace_start_speed))
                Slider(value = newInterval, onValueChange = { newInterval = it }, valueRange = 50f..500f, steps = 17)
                Text(stringResource(R.string.backspace_repeat_interval_value, newInterval.toInt()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.backspace_speed_up), Modifier.weight(1f))
                    Switch(checked = newSpeedUp, onCheckedChange = { newSpeedUp = it })
                }
                if (newSpeedUp) {
                    Text(stringResource(R.string.backspace_speed_up_after), Modifier.padding(top = 8.dp))
                    Slider(value = newAfter, onValueChange = { newAfter = it }, valueRange = 500f..5000f, steps = 8)
                    Text(stringResource(R.string.backspace_seconds_value, seconds(newAfter.toInt())),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.backspace_top_speed), Modifier.padding(top = 8.dp))
                    Slider(value = newTop, onValueChange = { newTop = it }, valueRange = 25f..200f, steps = 6)
                    Text(stringResource(R.string.backspace_repeat_interval_value, newTop.toInt()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

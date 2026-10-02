// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.runtime.remember
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.latin.settings.KeyboardProfiles
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
import helium314.keyboard.latin.KeypressSounds
import helium314.keyboard.latin.R
import helium314.keyboard.settings.preferences.reloadSymbolHints
import androidx.compose.foundation.layout.Box
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
import helium314.keyboard.settings.preferences.SystemFeedback
import helium314.keyboard.settings.preferences.SystemFeedbackNote
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
    val items = preferencesInputItems(prefs, LocalContext.current) + clipboardHistoryItems(prefs)
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
fun preferencesInputItems(prefs: SharedPreferences, ctx: Context): List<Any?> {
    // key sound and volume only while sound can actually play (see SystemFeedback)
    val soundRows = prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON) && SystemFeedback.soundBlocker(ctx) == null
    return listOf(
        R.string.settings_category_input,
        Settings.PREF_KEY_LONGPRESS_TIMEOUT, // (from Advanced)
        Settings.PREF_POPUP_ON,
        if (AudioAndHapticFeedbackManager.getInstance().hasVibrator())
            Settings.PREF_VIBRATE_ON else null,
        if (prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON))
            Settings.PREF_VIBRATION_DURATION_SETTINGS else null,
        if (prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON))
            Settings.PREF_VIBRATE_IN_DND_MODE else null,
        Settings.PREF_SOUND_ON,
        if (soundRows) Settings.PREF_KEYPRESS_SOUND else null,
        if (soundRows) Settings.PREF_KEYPRESS_SOUND_VOLUME else null,
        // (keyboard per app: on Advanced, with the other settings shared by all keyboards)
    )
}

/** Rows shown only while the switch above them is on; drawn indented under it. */
val dependentInputItems = setOf(
    Settings.PREF_VIBRATION_DURATION_SETTINGS, Settings.PREF_VIBRATE_IN_DND_MODE, Settings.PREF_KEYPRESS_SOUND, Settings.PREF_KEYPRESS_SOUND_VOLUME,
)

fun clipboardHistoryItems(prefs: SharedPreferences): List<Any?> {
    val clipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    return listOf(
        R.string.settings_category_clipboard_history,
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_SIZE else null,
    )
}

val preferencesSimpleModeKeys = setOf(
    Settings.PREF_SHOW_HINTS, Settings.PREF_SYMBOL_POPUP_MAP, Settings.PREF_POPUP_ON,
    Settings.PREF_VIBRATE_ON, Settings.PREF_SOUND_ON, Settings.PREF_SHOW_NUMBER_ROW,
    Settings.PREF_SHOW_EMOJI_KEY, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
)

/** The four "back to letters after…" switches: one row, a dialog with the four. */
val abcAfterKeys = listOf(
    Triple(Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Defaults.PREF_ABC_AFTER_SYMBOL_SPACE, R.string.after_symbol_and_space),
    Triple(Settings.PREF_ABC_AFTER_NUMPAD_SPACE, Defaults.PREF_ABC_AFTER_NUMPAD_SPACE, R.string.after_numpad_and_space),
    Triple(Settings.PREF_ABC_AFTER_EMOJI, Defaults.PREF_ABC_AFTER_EMOJI, R.string.after_emoji),
    Triple(Settings.PREF_ABC_AFTER_CLIP, Defaults.PREF_ABC_AFTER_CLIP, R.string.after_clip),
)

fun createPreferencesSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.ABC_AFTER, R.string.switch_keyboard_after) { setting ->
        val prefs = LocalContext.current.prefs()
        abcAfterKeys.forEach { (key, default, _) -> helium314.keyboard.settings.KnownDefaults.note(key, default) }
        var show by rememberSaveable { mutableStateOf(false) }
        // which are on, in words: "Space/enter after symbols or numpad; Selecting emoji or clipboard entry"
        val (symbols, numpad, emoji, clip) = abcAfterKeys.map { (key, default, _) -> prefs.getBoolean(key, default) }
        val space = when {
            symbols && numpad -> stringResource(R.string.abc_after_space_both)
            symbols -> stringResource(R.string.abc_after_space_symbols)
            numpad -> stringResource(R.string.abc_after_space_numpad)
            else -> null
        }
        val selecting = when {
            emoji && clip -> stringResource(R.string.abc_after_select_both)
            emoji -> stringResource(R.string.abc_after_select_emoji)
            clip -> stringResource(R.string.abc_after_select_clip)
            else -> null
        }
        Preference(name = setting.title, onClick = { show = true },
            description = listOfNotNull(space, selecting).joinToString("; ").ifEmpty { stringResource(R.string.abc_after_none) })
        if (show) ThreeButtonAlertDialog(
            onDismissRequest = { show = false },
            onConfirmed = { },
            confirmButtonText = null,
            cancelButtonText = stringResource(R.string.dialog_close),
            title = { Text(setting.title) },
            content = { Column { abcAfterKeys.forEach { (key, default, label) ->
                SwitchPreference(name = stringResource(label), key = key, default = default)
            } } },
        )
    },
    Setting(context, Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS, R.string.backspace_hold_deletes_words) {
        SwitchPreference(it, Defaults.PREF_BACKSPACE_HOLD_DELETES_WORDS)
    },
    // (the interval between deletions while held is the key long-press delay; only the speed-up is set here)
    Setting(context, Settings.PREF_BACKSPACE_SPEED_UP, R.string.backspace_speed_up) {
        BackspaceSpeedUpPreference(it)
    },
    Setting(context, Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD, R.string.backspace_deletes_swiped_word, R.string.backspace_deletes_swiped_word_summary) {
        SwitchPreference(it, Defaults.PREF_BACKSPACE_DELETES_SWIPED_WORD)
    },
    Setting(context, Settings.PREF_SAVE_SUBTYPE_PER_APP, R.string.save_subtype_per_app) {
        // on Advanced, among the settings shared by all keyboards
        SwitchPreference(it, Defaults.PREF_SAVE_SUBTYPE_PER_APP)
    },
    // the symbols area by area (on Others; Appearance's "Hide symbols on keys" sets all three)
    Setting(context, Settings.PREF_SHOW_HINTS, R.string.hints_other_keys) {
        SwitchPreference(it, Defaults.PREF_SHOW_HINTS, inverted = true) { reloadSymbolHints() }
    },
    Setting(context, Settings.PREF_REMOVE_REDUNDANT_POPUPS, R.string.remove_redundant_popups) {
        SwitchPreference(it, Defaults.PREF_REMOVE_REDUNDANT_POPUPS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
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
    Setting(context, Settings.PREF_SHOW_POPUP_HINTS, R.string.show_popup_hints) {
        SwitchPreference(it, Defaults.PREF_SHOW_POPUP_HINTS, inverted = true) { reloadSymbolHints() }
    },
    Setting(context, Settings.PREF_POPUP_ON, R.string.popup_on_keypress) {
        SwitchPreference(it, Defaults.PREF_POPUP_ON) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_VIBRATE_ON, R.string.vibrate_on_keypress) {
        SystemFeedbackNote(
            blocked = { ctx -> SystemFeedback.vibrationBlockers(ctx).firstOrNull()?.let { r -> ctx.getString(r) } },
            open = SystemFeedback::openVibrationSettings,
        ) { onSwitched, _ -> SwitchPreference(it, Defaults.PREF_VIBRATE_ON) { on -> onSwitched(on) } }
    },
    Setting(context, Settings.PREF_VIBRATE_IN_DND_MODE, R.string.vibrate_in_dnd_mode) {
        SwitchPreference(it, Defaults.PREF_VIBRATE_IN_DND_MODE)
    },
    Setting(context, Settings.PREF_SOUND_ON, R.string.sound_on_keypress) {
        SystemFeedbackNote(
            blocked = { ctx -> SystemFeedback.soundBlocker(ctx)?.let { r -> ctx.getString(r) } },
            open = SystemFeedback::openSoundSettings,
        ) { onSwitched, blockedNow ->
            // Android keeps key sounds off: the switch stays as set, greyed (its sound and volume rows are hidden).
            // Its own state, updated by the flip itself: read from the saved value only, the first flip on wasn't
            // greyed (nothing redrew the row until a later flip)
            val prefs = LocalContext.current.prefs()
            var on by remember { mutableStateOf(prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)) }
            SwitchPreference(it, Defaults.PREF_SOUND_ON, dimmed = on && blockedNow) { now -> on = now; onSwitched(now) }
        }
    },
    Setting(context, Settings.PREF_SHOW_EMOJI_DESCRIPTIONS, R.string.show_emoji_descriptions) {
        SwitchPreferenceWithEmojiDictWarning(it, Defaults.PREF_SHOW_EMOJI_DESCRIPTIONS)
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW, R.string.show_numbers_row) {
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
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW_HINTS, inverted = true) { reloadSymbolHints() }
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
    Setting(context, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        R.string.enable_clipboard_history, R.string.enable_clipboard_history_summary)
    {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY) { ClipboardDao.getInstance(ctx)?.clearNonPinned() }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_SIZE, R.string.clipboard_history_size, R.string.clipboard_history_size_summary) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_HISTORY_SIZE,
            description = { stringResource(R.string.clipboard_history_size_entries, it.toString()) },
            range = 10f..500f,
            stepSize = 10,
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
        val prefs = LocalContext.current.prefs()
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEYPRESS_SOUND_VOLUME,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else (it * 100).toInt().toString()
            },
            range = -0.01f..1f,
            onValueChanged = { it?.let {
                val sound = prefs.getString(Settings.PREF_KEYPRESS_SOUND, Defaults.PREF_KEYPRESS_SOUND)!!
                if (!KeypressSounds.play(sound, it)) audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, it)
            } }
        )
    },
    Setting(context, Settings.PREF_KEYPRESS_SOUND, R.string.keypress_sound) { setting ->
        val ctx = LocalContext.current
        val names = mapOf(
            KeypressSounds.ANDROID to R.string.keypress_sound_android, "click" to R.string.keypress_sound_click,
            "tick" to R.string.keypress_sound_tick, "lock" to R.string.keypress_sound_lock, "unlock" to R.string.keypress_sound_unlock,
            "camera" to R.string.keypress_sound_camera, KeypressSounds.BEEP to R.string.keypress_sound_beep,
        )
        val volume = ctx.prefs().getFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, Defaults.PREF_KEYPRESS_SOUND_VOLUME)
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // a tap plays the sound; OK keeps it
        ListPreference(setting, KeypressSounds.available().map { stringResource(names[it]!!) to it }, Defaults.PREF_KEYPRESS_SOUND,
            live = true) { sound ->
            if (!KeypressSounds.play(sound, volume)) audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, volume)
        }
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
/**
 * A switch whose details live in a dialog: switching it on opens the dialog; tapping the row while on opens it too;
 * switching off just switches off. [dialogContent] gets the values being edited; OK writes them via [save].
 */
@Composable
private fun SwitchWithDialogPreference(
    setting: Setting, key: String, default: Boolean,
    dialogContent: @Composable () -> Unit, save: () -> Unit, onDefault: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) Log.v("irrelevant", "recompose on preference change")
    helium314.keyboard.settings.KnownDefaults.note(key, default)
    val on = prefs.getBoolean(key, default)
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Preference(name = setting.title, onClick = { if (on) showDialog = true else { prefs.edit { putBoolean(key, true) }; showDialog = true } }) {
        Switch(checked = on, onCheckedChange = { turnOn ->
            prefs.edit { putBoolean(key, turnOn) }
            if (turnOn) showDialog = true
        })
    }
    if (showDialog) ThreeButtonAlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(setting.title) },
        neutralButtonText = onDefault?.let { stringResource(R.string.button_default) },
        onNeutral = { onDefault?.invoke() },
        onConfirmed = save,
        content = { dialogContent() },
    )
}

/** Holding backspace speeds up: when it starts, and the top speed. */
@Composable
private fun BackspaceSpeedUpPreference(setting: Setting) {
    val prefs = LocalContext.current.prefs()
    helium314.keyboard.settings.KnownDefaults.note(Settings.PREF_BACKSPACE_SPEED_UP_AFTER, Defaults.PREF_BACKSPACE_SPEED_UP_AFTER)
    helium314.keyboard.settings.KnownDefaults.note(Settings.PREF_BACKSPACE_TOP_INTERVAL, Defaults.PREF_BACKSPACE_TOP_INTERVAL)
    fun seconds(ms: Int) = String.format(java.util.Locale.getDefault(), "%.1f", ms / 1000f)
    var newAfter by rememberSaveable { mutableFloatStateOf(prefs.getInt(Settings.PREF_BACKSPACE_SPEED_UP_AFTER, Defaults.PREF_BACKSPACE_SPEED_UP_AFTER).toFloat()) }
    var newTop by rememberSaveable { mutableFloatStateOf(prefs.getInt(Settings.PREF_BACKSPACE_TOP_INTERVAL, Defaults.PREF_BACKSPACE_TOP_INTERVAL).toFloat()) }
    SwitchWithDialogPreference(setting, Settings.PREF_BACKSPACE_SPEED_UP, Defaults.PREF_BACKSPACE_SPEED_UP,
        dialogContent = { Column {
            Text(stringResource(R.string.backspace_speed_up_after))
            Slider(value = newAfter, onValueChange = { newAfter = it }, valueRange = 500f..5000f, steps = 8)
            Text(stringResource(R.string.backspace_seconds_value, seconds(newAfter.toInt())),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.backspace_top_speed), Modifier.padding(top = 8.dp))
            Slider(value = newTop, onValueChange = { newTop = it }, valueRange = 25f..200f, steps = 6)
            Text(stringResource(R.string.backspace_repeat_interval_value, newTop.toInt()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        save = { prefs.edit {
            putInt(Settings.PREF_BACKSPACE_SPEED_UP_AFTER, newAfter.toInt()); putInt(Settings.PREF_BACKSPACE_TOP_INTERVAL, newTop.toInt()) } },
        onDefault = { newAfter = Defaults.PREF_BACKSPACE_SPEED_UP_AFTER.toFloat(); newTop = Defaults.PREF_BACKSPACE_TOP_INTERVAL.toFloat() },
    )
}

/** Swiping left from backspace selects more text to delete: how fast the selection grows with the finger. */
@Composable
internal fun DeleteSwipePreference(setting: Setting) {
    val prefs = LocalContext.current.prefs()
    helium314.keyboard.settings.KnownDefaults.note(Settings.PREF_DELETE_SWIPE_SPEED, Defaults.PREF_DELETE_SWIPE_SPEED)
    var speed by rememberSaveable { mutableFloatStateOf(prefs.getFloat(Settings.PREF_DELETE_SWIPE_SPEED, Defaults.PREF_DELETE_SWIPE_SPEED)) }
    SwitchWithDialogPreference(setting, Settings.PREF_DELETE_SWIPE, Defaults.PREF_DELETE_SWIPE,
        dialogContent = { Column {
            Text(stringResource(R.string.delete_swipe_speed))
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..3f, steps = 9)
            Text("${(speed * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        save = { prefs.edit { putFloat(Settings.PREF_DELETE_SWIPE_SPEED, speed) } },
        onDefault = { speed = Defaults.PREF_DELETE_SWIPE_SPEED },
    )
}

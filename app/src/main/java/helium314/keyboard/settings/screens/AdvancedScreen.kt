// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.gesture.SwipeMetrics
import helium314.keyboard.latin.personalization.LearnedPools
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.LearningEventLog
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import android.widget.Toast
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import helium314.keyboard.settings.dialogs.LocalPreviewEmoji
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
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.preferences.CustomizeSuggestionsPreference
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
        // settings shared by all keyboards
        Settings.PREF_AUTO_PREVIEW_KEYBOARD, // settings screens bring up the preview keyboard by themselves
        Settings.PREF_SAVE_SUBTYPE_PER_APP, // which keyboard comes up in an app (moved from Layout & Typing)
        Settings.PREF_SHARE_LEARNED_WORDS, // one set of learned & blacklisted words for all keyboards, or one each
        SettingsWithoutKey.BACKUP_RESTORE,
        SettingsWithoutKey.FACTORY_RESET,
        if (BuildConfig.DEBUG || prefs.getBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, Defaults.PREF_SHOW_DEBUG_SETTINGS))
            SettingsWithoutKey.DEBUG_SETTINGS else null,
        // (once under Experimental; the emoji version is on Appearance's Emoji group, next to the emoji font, URL
        //  detection on Text correction's Correction group)
        // (recording the swipe corpus and logging swipe results: on the Swipe screen, Swipe logging)
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_advanced),
        settings = items,
        // (no simple set: the screen as a whole is advanced, its entry on the main screen shows only in advanced mode;
        // everything in it shows, untinted)
    )
}

@SuppressLint("ApplySharedPref")
fun createAdvancedSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_ALWAYS_INCOGNITO_MODE,
        R.string.prefs_force_incognito_mode_summary) // "Disable learning of new words" as the title, no subtext
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
    Setting(context, Settings.PREF_SHARE_LEARNED_WORDS, R.string.share_learned_words) { setting ->
        ShareLearnedWordsPreference(setting)
    },
    Setting(context, SettingsWithoutKey.BACKUP_RESTORE, R.string.backup_restore_title) {
        BackupRestorePreference(it)
    },
    Setting(context, SettingsWithoutKey.FACTORY_RESET, R.string.factory_reset) {
        FactoryResetPreference(it)
    },
    // the keyboard's own undo / redo (EditHistory): how many steps back, and a whole step or one character per press
    Setting(context, Settings.PREF_UNDO_HISTORY_LENGTH, R.string.undo_history_length) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_UNDO_HISTORY_LENGTH,
            description = { stringResource(R.string.undo_history_length_steps, it.toString()) },
            range = 1f..100f,
        )
    },
    Setting(context, Settings.PREF_UNDO_UNIT, R.string.undo_unit) {
        UnitChoiceRow(it, Defaults.PREF_UNDO_UNIT)
    },
    // how many suggestions the strip shows, and rules for the 2nd one on (SuggestionRules)
    Setting(context, Settings.PREF_SUGGESTION_RULES, R.string.customize_suggestions) {
        CustomizeSuggestionsPreference(it)
    },
    Setting(context, Settings.PREF_SUGGESTION_WORD_PADDING, R.string.suggestion_spacing_title) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            live = true,
            applyOnRelease = true, // a keyboard rebuild per drag step flickers
            default = Defaults.PREF_SUGGESTION_WORD_PADDING,
            description = { "$it dp" },
            range = 0f..30f,
            onConfirmed = { KeyboardSwitcher.getInstance().setThemeNeedsReload() },
        )
    },
    Setting(context, Settings.PREF_REDO_UNIT, R.string.redo_unit) {
        UnitChoiceRow(it, Defaults.PREF_REDO_UNIT)
    },
    Setting(context, SettingsWithoutKey.DEBUG_SETTINGS, R.string.debug_settings_title) {
        Preference(
            name = it.title,
            onClick = { SettingsDestination.navigateTo(SettingsDestination.Debug) }
        ) { NextScreenIcon() }
    },
    Setting(context, Settings.PREF_EMOJI_MAX_SDK, R.string.prefs_key_emoji_max_sdk) { setting ->
        val ctx = LocalContext.current
        // the top is this phone's Android or the newest emoji list's, whichever is newer: every emoji shows
        val top = maxOf(SupportedEmojis.LATEST, Build.VERSION.SDK_INT)
        // opens on the emoji panel, which follows the slider while it's dragged
        CompositionLocalProvider(LocalPreviewEmoji provides true) {
        SliderPreference(
            live = true,
            name = setting.title,
            key = setting.key,
            default = SupportedEmojis.DEFAULT, // this phone's Android
            range = 21f..top.toFloat(),
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
                    in 33..40 -> "${it - 20}"
                    else -> "(level $it)"
                }
            },
            onConfirmed = {
                SupportedEmojis.load(ctx)
                KeyboardSwitcher.getInstance().clearEmojiCache()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
        )
        }
    },
    Setting(context, Settings.PREF_URL_DETECTION, R.string.url_detection_title, R.string.url_detection_summary) {
        SwitchPreference(it, Defaults.PREF_URL_DETECTION)
    },
    Setting(context, Settings.PREF_RECORD_GESTURE_CORPUS, R.string.record_gesture_corpus, R.string.record_gesture_corpus_summary) {
        SwitchPreference(it, Defaults.PREF_RECORD_GESTURE_CORPUS)
    },
    Setting(context, Settings.PREF_AUTO_PREVIEW_KEYBOARD, R.string.auto_preview_keyboard) {
        SwitchPreference(it, Defaults.PREF_AUTO_PREVIEW_KEYBOARD)
    },
    Setting(context, Settings.PREF_SWIPE_METRICS, R.string.swipe_metrics, R.string.swipe_metrics_summary) { def ->
        val ctx = LocalContext.current
        var generation by remember { mutableIntStateOf(0) }
        var on by remember { mutableStateOf(ctx.prefs().getBoolean(def.key, Defaults.PREF_SWIPE_METRICS)) }
        Column {
            SwitchPreference(def, Defaults.PREF_SWIPE_METRICS) { on = it }
            if (on) {
                val week = remember(generation) { SwipeMetrics.summary(7) }
                val all = remember(generation) { SwipeMetrics.summary(0) }
                Column(Modifier.padding(start = 22.dp, end = 16.dp, bottom = 8.dp)) {
                    for ((label, s) in listOf(R.string.swipe_metrics_week to week, R.string.swipe_metrics_all to all)) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                        Text(if (s.swipes == 0) stringResource(R.string.swipe_metrics_none)
                            else stringResource(R.string.swipe_metrics_line, s.swipes, s.pct(s.firstChoice), s.pct(s.fromStrip),
                                s.pct(s.neverOffered), s.decodeAverage, s.decodeWorst),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { SwipeMetrics.clear(); generation++ }) { Text(stringResource(R.string.swipe_metrics_clear)) }
                }
            }
        }
    },
    // what corrections do to the learned words, laid out like the swipe results above
    Setting(context, Settings.PREF_LEARNING_LOG, R.string.learning_log, R.string.learning_log_summary) { def ->
        val ctx = LocalContext.current
        var generation by remember { mutableIntStateOf(0) }
        var on by remember { mutableStateOf(ctx.prefs().getBoolean(def.key, Defaults.PREF_LEARNING_LOG)) }
        Column {
            SwitchPreference(def, Defaults.PREF_LEARNING_LOG) { on = it }
            if (on) {
                val week = remember(generation) { LearningEventLog.summary(7) }
                val all = remember(generation) { LearningEventLog.summary(0) }
                Column(Modifier.padding(start = 22.dp, end = 16.dp, bottom = 8.dp)) {
                    for ((label, s) in listOf(R.string.swipe_metrics_week to week, R.string.swipe_metrics_all to all)) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                        Text(if (s.total == 0) stringResource(R.string.learning_log_none)
                            else stringResource(R.string.learning_log_line, s.of(LearningEventLog.ACCEPTED),
                                s.of(LearningEventLog.AUTOCORRECT_REVERTED), s.of(LearningEventLog.SWIPE_DELETED),
                                s.of(LearningEventLog.ACCEPTED_EDITED), s.of(LearningEventLog.SWIPE_EDITED),
                                s.of(LearningEventLog.REMOVED), s.of(LearningEventLog.RESTORED),
                                s.of(LearningEventLog.UNDO), s.untrackedOf(LearningEventLog.UNDO),
                                s.of(LearningEventLog.REDO), s.untrackedOf(LearningEventLog.REDO)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { LearningEventLog.clear(); generation++ }) { Text(stringResource(R.string.swipe_metrics_clear)) }
                }
            }
        }
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

/** A character or a whole word per press (undo / redo): two buttons in the row, like a language's L / M / H. */
/** The switch asks first, saying what happens to the words (see LearnedPools), which are then moved in the background. */
@Composable
private fun ShareLearnedWordsPreference(setting: Setting) {
    val ctx = LocalContext.current
    val real = ctx.realPrefs()
    var shared by remember { mutableStateOf(LearnedStores.isShared(real)) }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Preference(name = setting.title, onClick = { asking = true }) {
        Switch(checked = shared, onCheckedChange = { asking = true })
    }
    if (asking) ConfirmationDialog(
        onDismissRequest = { asking = false },
        onConfirmed = {
            val to = !shared
            shared = to
            scope.launch {
                val ok = withContext(Dispatchers.IO) { LearnedPools.setShared(ctx, real, to) }
                if (!ok) {
                    shared = !to
                    Toast.makeText(ctx, R.string.share_learned_words_failed, Toast.LENGTH_LONG).show()
                }
            }
        },
        title = { Text(setting.title) },
        content = { Text(stringResource(if (shared) R.string.share_learned_words_off_message else R.string.share_learned_words_on_message)) },
    )
}

@Composable
private fun UnitChoiceRow(setting: Setting, default: String) {
    val prefs = LocalContext.current.prefs()
    var value by remember { mutableStateOf(prefs.getString(setting.key, default) ?: default) }
    val options = listOf("character" to stringResource(R.string.undo_unit_char_short), "word" to stringResource(R.string.undo_unit_word_short))
    Preference(name = setting.title, onClick = { }) {
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, (v, label) ->
                SegmentedButton(
                    selected = value == v,
                    onClick = { value = v; prefs.edit { putString(setting.key, v) } },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label) },
                )
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.SwitchPreferenceWithEmojiDictWarning
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit
import helium314.keyboard.keyboard.internal.PopupKeySpec
import helium314.keyboard.settings.preferences.TextInputPreference

@Composable
fun TextCorrectionScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val autocorrectEnabled = prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION)
    // Show suggestions is the one switch for suggestions (the toolbar's visibility no longer hides this row)
    val suggestionsVisible = true
    val suggestionsEnabled = suggestionsVisible && prefs.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, Defaults.PREF_SHOW_SUGGESTIONS)
    val gestureEnabled = prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
    val items = listOf(
        SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY,
        R.string.settings_category_correction,
        Settings.PREF_AUTO_CORRECTION,
        if (autocorrectEnabled) Settings.PREF_MORE_AUTO_CORRECTION else null,
        if (autocorrectEnabled) Settings.PREF_AUTOCORRECT_SHORTCUTS else null,
        if (autocorrectEnabled) Settings.PREF_AUTOCORRECT_WITH_DIGITS else null,
        if (autocorrectEnabled) Settings.PREF_AUTO_CORRECT_THRESHOLD else null,
        // (backspace reverts autocorrect: in the Backspace group of the Preferences screen)
        Settings.PREF_AUTO_CAP,
        Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE,
        // your own words win over corrections and spell checks (also with auto-correct off: the underlines)
        Settings.PREF_AUTOCORRECT_FREQUENT_WORDS,
        Settings.PREF_URL_DETECTION, // (from Advanced; advanced here too) web and email addresses as one word
        R.string.settings_category_space,
        Settings.PREF_AUTOSPACE_AFTER_SUGGESTION,
        if (gestureEnabled) Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING else null, // (also on the Swipe screen)
        if (gestureEnabled) Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING else null,
        Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION,
        Settings.PREF_SHIFT_REMOVES_AUTOSPACE,
        Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD,
        R.string.settings_category_suggestions,
        if (suggestionsVisible) Settings.PREF_SHOW_SUGGESTIONS else null,
        if (suggestionsEnabled) Settings.PREF_ALWAYS_SHOW_SUGGESTIONS else null,
        if (suggestionsEnabled && prefs.getBoolean(Settings.PREF_ALWAYS_SHOW_SUGGESTIONS, Defaults.PREF_ALWAYS_SHOW_SUGGESTIONS))
            Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT else null,
        // (right after Show suggestions and the rows indented under it)
        Settings.PREF_BIGRAM_PREDICTIONS,
        Settings.PREF_KEY_USE_PERSONALIZED_DICTS,
        if (prefs.getBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, Defaults.PREF_KEY_USE_PERSONALIZED_DICTS))
            Settings.PREF_ADD_TO_PERSONAL_DICTIONARY else null,
        Settings.PREF_SUGGESTION_RULES, // Customize suggestions: how many, rules for the 2nd one on (from Others; advanced)
        Settings.PREF_ALWAYS_INCOGNITO_MODE, // (from Advanced; advanced here too) never learn, like incognito fields
        // (PREF_CENTER_SUGGESTION_TEXT_TO_ENTER, "show the word space will type as the middle suggestion", is no longer
        // shown: the strip has no middle and shows the typed word first anyway; its Setting stays, off, see SettingsValues)
        if (suggestionsEnabled || autocorrectEnabled) Settings.PREF_SUGGEST_EMOJIS else null,
        if (suggestionsEnabled || autocorrectEnabled) Settings.PREF_INLINE_EMOJI_SEARCH else null,
        Settings.PREF_SUGGEST_PUNCTUATION,
        if (prefs.getBoolean(Settings.PREF_SUGGEST_PUNCTUATION, Defaults.PREF_SUGGEST_PUNCTUATION))
            Settings.PREF_PUNCTUATION_SUGGESTIONS else null,
        Settings.PREF_SUGGEST_CLIPBOARD_CONTENT,
        Settings.PREF_USE_CONTACTS,
        Settings.PREF_USE_APPS,
    )
    // every change applies at once and can be tried in the box at the bottom (the keyboard comes up for a moment, as on
    // Appearance); the top bar's tick keeps the changes since the screen opened, the cross undoes them
    val draft = helium314.keyboard.settings.rememberPrefsDraft("correction", correctionKeys, onClickBack)
    val tryIt = remember { TryItState() }
    // the keyboard being edited (its own settings), else the one in use: the preview switches to it
    val keyboard = helium314.keyboard.latin.settings.KeyboardProfiles.editingKeyboard(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(LocalContext.current))
        ?: helium314.keyboard.latin.utils.SubtypeSettings.getSelectedSubtype(prefs).toSettingsSubtype()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val revealer = remember { helium314.keyboard.settings.TapRevealer() }
    var hiddenBarTop by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var shownBarTop by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    fun keyboardLine(): Int {
        if (shownBarTop > 0) return shownBarTop
        if (hiddenBarTop <= 0) return Int.MAX_VALUE
        val strip = ctx.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        return hiddenBarTop - helium314.keyboard.latin.utils.ResourceUtils.getKeyboardHeight(ctx.resources, Settings.getValues()) - strip
    }
    val preview = remember { PreviewKeyboard(tryIt, scope, showIme = { softKeyboard?.show() }, reveal = { revealer.revealAbove(keyboardLine()) }) {
        focusManager.clearFocus(force = true); softKeyboard?.hide() } }
    fun shape() = correctionKeys.map { prefs.all[it] }
    var lastShape by remember { mutableStateOf(shape()) }
    androidx.compose.runtime.LaunchedEffect(b?.value) {
        val now = shape()
        if (now != lastShape) { lastShape = now; preview.changed(emoji = false) }
    }
    var bottomBarTop by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = Int.MAX_VALUE } }
    androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.dialogs.LocalKeepKeyboard provides true,
        helium314.keyboard.settings.dialogs.LocalPreviewKeyboard provides preview,
        helium314.keyboard.settings.dialogs.LocalBottomBarTop provides bottomBarTop) {
    SearchSettingsScreen(
        onClickBack = draft.leave,
        title = stringResource(R.string.settings_screen_correction),
        settings = items,
        topActions = draft.topActions,
        bottomBar = { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.onGloballyPositioned {
            bottomBarTop = it.positionInWindow().y.toInt()
            // the try-it bar stays usable while a dialog is open
            (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = bottomBarTop
            val imeUp = androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            if (imeUp) shownBarTop = bottomBarTop else hiddenBarTop = bottomBarTop
        }) { TryItBar(keyboard, tryIt, onFocus = preview::onFocus, onUsed = preview::onUsed) } },
        revealer = revealer,
        // (Customize suggestions writes the count too)
        isPending = { it in draft.pending || (it == Settings.PREF_SUGGESTION_RULES && Settings.PREF_SUGGESTION_COUNT in draft.pending)
            || (it == Settings.PREF_AUTOCORRECT_FREQUENT_WORDS && Settings.PREF_TRUST_TYPED_COUNT in draft.pending) },
        simpleModeKeys = setOf(
            SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY, Settings.PREF_AUTO_CORRECTION, Settings.PREF_AUTO_CAP,
            Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Settings.PREF_AUTOCORRECT_FREQUENT_WORDS,
            Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, Settings.PREF_AUTOSPACE_AFTER_SUGGESTION,
            Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION,
            Settings.PREF_SHOW_SUGGESTIONS, Settings.PREF_BIGRAM_PREDICTIONS, Settings.PREF_KEY_USE_PERSONALIZED_DICTS,
            // (suggest emojis and contact names: advanced)
            Settings.PREF_ADD_TO_PERSONAL_DICTIONARY,
        ),
    )
    draft.dialogs()
    }
}

/** Every preference on the Text correction screen (shown or not), for its tick / cross and its preview. */
private val correctionKeys = listOf(
    Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Settings.PREF_AUTO_CORRECTION, Settings.PREF_MORE_AUTO_CORRECTION,
    Settings.PREF_AUTOCORRECT_SHORTCUTS, Settings.PREF_AUTOCORRECT_WITH_DIGITS, Settings.PREF_AUTO_CORRECT_THRESHOLD,
    Settings.PREF_AUTO_CAP, Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION,
    Settings.PREF_AUTOSPACE_AFTER_SUGGESTION, Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING, Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING,
    Settings.PREF_SHIFT_REMOVES_AUTOSPACE, Settings.PREF_SHOW_SUGGESTIONS, Settings.PREF_ALWAYS_SHOW_SUGGESTIONS,
    Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT, Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER, Settings.PREF_SUGGEST_EMOJIS,
    Settings.PREF_INLINE_EMOJI_SEARCH, Settings.PREF_KEY_USE_PERSONALIZED_DICTS, Settings.PREF_ALWAYS_INCOGNITO_MODE,
    Settings.PREF_BIGRAM_PREDICTIONS, Settings.PREF_SUGGEST_PUNCTUATION, Settings.PREF_PUNCTUATION_SUGGESTIONS,
    Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, Settings.PREF_USE_CONTACTS, Settings.PREF_USE_APPS, Settings.PREF_ADD_TO_PERSONAL_DICTIONARY,
    Settings.PREF_URL_DETECTION, Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT,
    Settings.PREF_SUGGESTION_COUNT, Settings.PREF_SUGGESTION_RULES,
)

fun createCorrectionSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY, R.string.edit_personal_dictionary) {
        // a line above it too, like under the headings
        androidx.compose.foundation.layout.Column {
            androidx.compose.material3.HorizontalDivider()
            Preference(
                name = stringResource(R.string.edit_personal_dictionary),
                onClick = { SettingsDestination.navigateTo(SettingsDestination.PersonalDictionaries) },
            ) { NextScreenIcon() }
        }
    },
    Setting(context, Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, R.string.autocorrect_frequent_words) {
        TrustWordsRow(it)
    },
    Setting(context, Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE,
        R.string.prefs_block_potentially_offensive_title
    ) {
        SwitchPreference(it, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
    },
    Setting(context, Settings.PREF_AUTO_CORRECTION,
        R.string.autocorrect
    ) {
        SwitchPreference(it, Defaults.PREF_AUTO_CORRECTION)
    },
    Setting(context, Settings.PREF_MORE_AUTO_CORRECTION,
        R.string.more_autocorrect
    ) {
        SwitchPreference(it, Defaults.PREF_MORE_AUTO_CORRECTION)
    },
    Setting(context, Settings.PREF_AUTOCORRECT_SHORTCUTS,
        R.string.auto_correct_shortcuts
    ) {
        SwitchPreference(it, Defaults.PREF_AUTOCORRECT_SHORTCUTS)
    },
    Setting(context, Settings.PREF_AUTOCORRECT_WITH_DIGITS,
        R.string.autocorrect_with_digits
    ) {
        SwitchPreference(it, Defaults.PREF_AUTOCORRECT_WITH_DIGITS)
    },
    Setting(context, Settings.PREF_AUTO_CORRECT_THRESHOLD, R.string.auto_correction_confidence) {
        val items = listOf(
            stringResource(R.string.auto_correction_threshold_mode_modest) to 0.185f,
            stringResource(R.string.auto_correction_threshold_mode_aggressive) to 0.067f,
            stringResource(R.string.auto_correction_threshold_mode_very_aggressive) to -1f,
        )
        // todo: consider making it a slider, and maybe somehow adjust range so we can show %
        ListPreference(it, items, Defaults.PREF_AUTO_CORRECT_THRESHOLD)
    },
    Setting(context, Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, R.string.backspace_reverts_autocorrect) {
        SwitchPreference(it, Defaults.PREF_BACKSPACE_REVERTS_AUTOCORRECT)
    },
    Setting(context, Settings.PREF_AUTO_CAP,
        R.string.auto_cap
    ) {
        SwitchPreference(it, Defaults.PREF_AUTO_CAP)
    },
    Setting(context, Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD,
        R.string.use_double_space_period
    ) {
        SwitchPreference(it, Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD)
    },
    Setting(context, Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION,
        R.string.autospace_after_punctuation
    ) {
        SwitchPreference(it, Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION)
    },
    Setting(context, Settings.PREF_AUTOSPACE_AFTER_SUGGESTION, R.string.autospace_after_suggestion) {
        SwitchPreference(it, Defaults.PREF_AUTOSPACE_AFTER_SUGGESTION)
    },
    Setting(context, Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, R.string.autospace_after_gesture_typing) {
        SwitchPreference(it, Defaults.PREF_AUTOSPACE_AFTER_GESTURE_TYPING)
    },
    Setting(context, Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING, R.string.autospace_before_gesture_typing) {
        SwitchPreference(it, Defaults.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING)
    },
    Setting(context, Settings.PREF_SHIFT_REMOVES_AUTOSPACE, R.string.shift_removes_autospace) {
        SwitchPreference(it, Defaults.PREF_SHIFT_REMOVES_AUTOSPACE)
    },
    Setting(context, Settings.PREF_SHOW_SUGGESTIONS,
        R.string.prefs_show_suggestions
    ) {
        SwitchPreference(it, Defaults.PREF_SHOW_SUGGESTIONS) {
            // the row above the keys changes with it (and, toolbar opening from a key, the top-left key's popup)
            helium314.keyboard.keyboard.KeyboardLayoutSet.onSystemLocaleChanged()
            helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_ALWAYS_SHOW_SUGGESTIONS,
        R.string.prefs_always_show_suggestions, R.string.prefs_always_show_suggestions_summary
    ) {
        // (shown only with Show suggestions on, right after it)
        SwitchPreference(it, Defaults.PREF_ALWAYS_SHOW_SUGGESTIONS)
    },
    Setting(context, Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT,
        R.string.prefs_always_show_suggestions_except_web_text, R.string.prefs_always_show_suggestions_except_web_text_summary
    ) {
        // depends on the one above: indented under it
        Indented { SwitchPreference(it, Defaults.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT) }
    },
    Setting(context, Settings.PREF_KEY_USE_PERSONALIZED_DICTS,
        R.string.use_personalized_dicts, R.string.use_personalized_dicts_summary
    ) { setting ->
        // (off keeps what was learned: nothing to warn about)
        SwitchPreference(setting, Defaults.PREF_KEY_USE_PERSONALIZED_DICTS)
    },
    Setting(context, Settings.PREF_BIGRAM_PREDICTIONS,
        R.string.bigram_prediction
    ) {
        SwitchPreference(it, Defaults.PREF_BIGRAM_PREDICTIONS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SUGGEST_PUNCTUATION, R.string.suggest_punctuation
    ) {
        SwitchPreference(it, Defaults.PREF_SUGGEST_PUNCTUATION) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_PUNCTUATION_SUGGESTIONS, R.string.custom_punctuation_suggestions) { setting ->
        val defaultSpecs = PopupKeySpec.splitKeySpecs(stringResource(R.string.suggested_punctuations))
            ?.joinToString(" ") { if (it.length > 1 && it.startsWith('\\')) it.substring(1) else it }
        TextInputPreference(setting, defaultSpecs ?: "")
    },
    Setting(context, Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER,
        R.string.center_suggestion_text_to_enter // (the old summary spoke of a middle suggestion: the strip has none)
    ) {
        SwitchPreference(it, Defaults.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER)
    },
    Setting(context, Settings.PREF_SUGGEST_CLIPBOARD_CONTENT,
        R.string.suggest_clipboard_content
    ) {
        SwitchPreference(it, Defaults.PREF_SUGGEST_CLIPBOARD_CONTENT)
    },
    Setting(context, Settings.PREF_USE_CONTACTS,
        R.string.use_contacts_dict
    ) { setting ->
        val activity = LocalContext.current.getActivity() ?: return@Setting
        var granted by remember { mutableStateOf(PermissionsUtil.checkAllPermissionsGranted(activity, Manifest.permission.READ_CONTACTS)) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            if (granted)
                activity.prefs().edit { putBoolean(setting.key, true) }
        }
        SwitchPreference(setting, Defaults.PREF_USE_CONTACTS,
            allowCheckedChange = {
                if (it && !granted) {
                    launcher.launch(Manifest.permission.READ_CONTACTS)
                    false
                } else true
            }
        )
    },
    Setting(context, Settings.PREF_USE_APPS,
        R.string.use_apps_dict
    ) { setting ->
        SwitchPreference(setting, Defaults.PREF_USE_APPS)
    },
    Setting(
        context, Settings.PREF_SUGGEST_EMOJIS, R.string.suggest_emojis
    ) {
        SwitchPreferenceWithEmojiDictWarning(it, Defaults.PREF_SUGGEST_EMOJIS)
    },
    Setting(
        context, Settings.PREF_INLINE_EMOJI_SEARCH, R.string.inline_emoji_search) {
        SwitchPreferenceWithEmojiDictWarning(it, Defaults.PREF_INLINE_EMOJI_SEARCH)
    },
    Setting(context, Settings.PREF_ADD_TO_PERSONAL_DICTIONARY,
        R.string.add_to_personal_dictionary
    ) {
        SwitchPreference(it, Defaults.PREF_ADD_TO_PERSONAL_DICTIONARY)
    },
)

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TextCorrectionScreen {  }
        }
    }
}

/** A row that depends on the one above it: [steps] × 16 dp further in than the screen's rows. */
@Composable
private fun Indented(steps: Int = 1, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalRowStart provides
        helium314.keyboard.settings.preferences.LocalRowStart.current + (16 * steps).dp, content = content)

private const val MAX_TRUST_COUNT = 20

/** "Trust words you've typed − 3 + times" and its switch: the count is set right in the row (1 at least). */
@Composable
private fun TrustWordsRow(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    helium314.keyboard.settings.KnownDefaults.note(setting.key, Defaults.PREF_AUTOCORRECT_FREQUENT_WORDS)
    helium314.keyboard.settings.KnownDefaults.note(Settings.PREF_TRUST_TYPED_COUNT, Defaults.PREF_TRUST_TYPED_COUNT)
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val on = prefs.getBoolean(setting.key, Defaults.PREF_AUTOCORRECT_FREQUENT_WORDS)
    val count = prefs.getInt(Settings.PREF_TRUST_TYPED_COUNT, Defaults.PREF_TRUST_TYPED_COUNT).coerceIn(1, MAX_TRUST_COUNT)
    fun setCount(n: Int) = prefs.edit { putInt(Settings.PREF_TRUST_TYPED_COUNT, n) }
    // (like a Preference row, but the count follows the title instead of sitting at the end with the switch)
    val compact = helium314.keyboard.settings.preferences.LocalCompactPreferences.current
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().clickable { prefs.edit { putBoolean(setting.key, !on) } }
            .then(if (compact) androidx.compose.ui.Modifier.heightIn(min = 56.dp).padding(vertical = 4.dp).padding(start = 10.dp)
                else androidx.compose.ui.Modifier.heightIn(min = 44.dp).padding(vertical = 10.dp)
                    .padding(start = helium314.keyboard.settings.preferences.LocalRowStart.current, end = 12.dp)),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        // the title, the count and "times" wrap like one line of text: what doesn't fit goes below (the count as one piece)
        val style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.let {
            if (helium314.keyboard.settings.preferences.LocalPendingChange.current)
                it.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else it }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(androidx.compose.ui.Modifier.weight(1f),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            setting.title.split(" ").forEach { Text(it, style = style) }
            // (dimmed while off; still settable)
            androidx.compose.foundation.layout.Row(if (on) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.alpha(0.5f),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StepButton("\u2212", stringResource(R.string.trust_typed_fewer), count > 1) { setCount(count - 1) }
                Text("$count", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                StepButton("+", stringResource(R.string.trust_typed_more), count < MAX_TRUST_COUNT) { setCount(count + 1) }
            }
            Text(stringResource(R.string.trust_typed_times), style = style)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { prefs.edit { putBoolean(setting.key, it) } },
            modifier = androidx.compose.ui.Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick, enabled = enabled,
        modifier = androidx.compose.ui.Modifier.size(36.dp).semantics { contentDescription = description }) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            color = if (enabled) androidx.compose.material3.MaterialTheme.colorScheme.primary
                else androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

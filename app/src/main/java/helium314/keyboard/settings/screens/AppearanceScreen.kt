// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import helium314.keyboard.settings.dialogs.PreviewKeyboardHooks
import helium314.keyboard.settings.dialogs.LocalPreviewKeyboard
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import helium314.keyboard.settings.painterResourceCompat
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.latin.utils.DeleteButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import helium314.keyboard.settings.dialogs.LocalKeepKeyboard
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ColorThemePickerDialog
import helium314.keyboard.settings.dialogs.CustomizeIconsDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.BackgroundImagePref
import helium314.keyboard.settings.preferences.CustomFontPreference
import helium314.keyboard.settings.preferences.KeyboardScalePreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

@Composable
fun AppearanceScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val dayNightMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
    val items = listOf(
        R.string.settings_screen_theme,
        SettingsWithoutKey.APPEARANCE_LOOKS,
        Settings.PREF_THEME_STYLE,
        Settings.PREF_ICON_STYLE,
        Settings.PREF_CUSTOM_ICON_NAMES,
        Settings.PREF_THEME_COLORS,
        Settings.PREF_THEME_KEY_BORDERS,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            Settings.PREF_THEME_DAY_NIGHT else null,
        if (dayNightMode) Settings.PREF_THEME_COLORS_NIGHT else null,
        Settings.PREF_NAVBAR_COLOR,
        SettingsWithoutKey.BACKGROUND_IMAGE,
        SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE,
        R.string.settings_category_miscellaneous,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD,
        if (prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
            )
            Settings.PREF_SPLIT_SPACER_SCALE_PREFIX else null,
        if (prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS))
            Settings.PREF_NARROW_KEY_GAPS else null,
        Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX,
        Settings.PREF_BOTTOM_ROW_SCALE_PREFIX,
        Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
        Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
        Settings.PREF_SPACE_BAR_TEXT,
        SettingsWithoutKey.CUSTOM_FONT,
        Settings.PREF_FONT_SCALE,
        SettingsWithoutKey.CUSTOM_EMOJI_FONT,
        Settings.PREF_EMOJI_FONT_SCALE,
        if (prefs.getFloat(Settings.PREF_EMOJI_FONT_SCALE, Defaults.PREF_EMOJI_FONT_SCALE) != 1f)
            Settings.PREF_EMOJI_KEY_FIT else null,
        if (prefs.getInt(Settings.PREF_EMOJI_MAX_SDK, 0) >= 24)
            Settings.PREF_EMOJI_SKIN_TONE else null,
        R.string.settings_category_suggestion_strip,
        Settings.PREF_SUGGESTION_TEXT_SIZE,
        Settings.PREF_SUGGESTION_BOLD,
        Settings.PREF_SUGGESTION_ITALIC,
        Settings.PREF_SUGGESTION_UNDERLINE,
        Settings.PREF_SUGGESTION_WORD_PADDING,
        Settings.PREF_TOOLBAR_EXPAND_ICON,
        R.string.settings_category_key_gaps,
        Settings.PREF_KEY_HORIZONTAL_GAP,
        Settings.PREF_KEY_VERTICAL_GAP,
    )
    // every change shows on the live keyboard at once; the draft remembers how things were when the screen opened
    val draft = remember { AppearanceDraft.of(ctx) }
    val changed = draft.hasChanges(ctx)
    var askOnLeave by remember { mutableStateOf(false) }
    var askReject by remember { mutableStateOf(false) }
    var askAccept by remember { mutableStateOf(false) }
    val tryIt = remember { TryItState() }
    val keyboard = SubtypeSettings.getSelectedSubtype(prefs).toSettingsSubtype()
    val focusManager = LocalFocusManager.current
    val softKeyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val preview = remember { PreviewKeyboard(tryIt, scope) { focusManager.clearFocus(); softKeyboard?.hide() } }
    // a changed appearance value (a switch, say) brings the keyboard up for a moment; dialogs report themselves
    var lastValues by remember { mutableStateOf(AppearanceLooks.current(prefs)) }
    LaunchedEffect(b?.value) {
        val now = AppearanceLooks.current(prefs)
        if (now != lastValues) { lastValues = now; preview.changed() }
    }
    fun leave() { if (changed) askOnLeave = true else onClickBack() }
    BackHandler(enabled = changed) { leave() }
    CompositionLocalProvider(LocalKeepKeyboard provides true, LocalPreviewKeyboard provides preview) { SearchSettingsScreen(
        onClickBack = ::leave,
        title = stringResource(R.string.settings_screen_appearance),
        settings = items,
        simpleModeKeys = setOf(
            SettingsWithoutKey.APPEARANCE_LOOKS, Settings.PREF_THEME_COLORS, Settings.PREF_THEME_KEY_BORDERS, Settings.PREF_THEME_DAY_NIGHT,
            Settings.PREF_THEME_COLORS_NIGHT, Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_FONT_SCALE,
        ),
        // cross and tick: reject or accept everything changed since the screen opened, each asks first
        topActions = {
            if (changed) {
                IconButton({ askReject = true }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.appearance_reject)) }
                IconButton({ askAccept = true }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.appearance_accept)) }
            }
        },
        bottomBar = { TryItBar(keyboard, tryIt, onFocus = preview::onFocus) },
    )
    if (askReject)
        ConfirmationDialog(
            onDismissRequest = { askReject = false },
            title = { Text(stringResource(R.string.appearance_reject)) },
            content = { Text(stringResource(R.string.appearance_reject_message)) },
            onConfirmed = { draft.reject(ctx) },
        )
    if (askAccept)
        ConfirmationDialog(
            onDismissRequest = { askAccept = false },
            title = { Text(stringResource(R.string.appearance_accept)) },
            content = { Text(stringResource(R.string.appearance_accept_message)) },
            onConfirmed = { draft.accept() },
        )
    }
    if (askOnLeave)
        ThreeButtonAlertDialog(
            onDismissRequest = { askOnLeave = false },
            title = { Text(stringResource(R.string.appearance_keep_title)) },
            content = { Text(stringResource(R.string.appearance_keep_message)) },
            confirmButtonText = stringResource(R.string.appearance_keep),
            onConfirmed = { draft.accept(); onClickBack() },
            neutralButtonText = stringResource(R.string.appearance_discard),
            onNeutral = { draft.reject(ctx); askOnLeave = false; onClickBack() },
        )
}

fun createAppearanceSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.APPEARANCE_LOOKS, R.string.appearance_looks, R.string.appearance_looks_summary) {
        SavedLooksPreference(it)
    },
    Setting(context, Settings.PREF_THEME_STYLE, R.string.theme_style) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val items = KeyboardTheme.STYLES.map {
            it.getStringResourceOrName("style_name_", ctx) to it
        }
        ListPreference(
            setting,
            items,
            Defaults.PREF_ICON_STYLE,
            live = true,
        ) {
            if (it != KeyboardTheme.STYLE_HOLO) {
                if (prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_HOLO_WHITE)
                    prefs.edit { remove(Settings.PREF_THEME_COLORS) }
                if (prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) == KeyboardTheme.THEME_HOLO_WHITE)
                    prefs.edit { remove(Settings.PREF_THEME_COLORS_NIGHT) }
            }
            KeyboardIconsSet.needsReload = true // only relevant for Settings.PREF_CUSTOM_ICON_NAMES
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_ICON_STYLE, R.string.icon_style) { setting ->
        val ctx = LocalContext.current
        val items = KeyboardTheme.STYLES.map { it.getStringResourceOrName("style_name_", ctx) to it }
        // each style previewed by a few of its own icons, right in the list
        val sampleIcons = listOf(KeyboardIconsSet.NAME_SHIFT_KEY, KeyboardIconsSet.NAME_DELETE_KEY, KeyboardIconsSet.NAME_ENTER_KEY,
            KeyboardIconsSet.NAME_LANGUAGE_SWITCH_KEY, KeyboardIconsSet.NAME_TOOLBAR_KEY)
        ListPreference(
            setting,
            items,
            Defaults.PREF_ICON_STYLE,
            live = true,
            previewKeyboard = false, // the rows show the icons
            itemTrailing = { (_, style) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)) {
                    sampleIcons.forEach { name ->
                        KeyboardIconsSet.iconForStyle(style, name)?.let { Icon(painterResourceCompat(it, 22), null, Modifier.size(22.dp)) }
                    }
                }
            },
        ) {
            KeyboardIconsSet.needsReload = true // only relevant for Settings.PREF_CUSTOM_ICON_NAMES
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_CUSTOM_ICON_NAMES, R.string.customize_icons) { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            KeyboardIconsSet.instance.loadIcons(LocalContext.current)
            CustomizeIconsDialog(setting.key) { showDialog = false }
        }
    },
    Setting(context, Settings.PREF_THEME_COLORS, R.string.theme_colors) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS)!!.getStringResourceOrName("theme_name_", ctx),
            onClick = { showDialog = true }
        )
        if (showDialog)
            ColorThemePickerDialog(
                onDismissRequest = { showDialog = false },
                setting = setting,
                isNight = false,
                default = Defaults.PREF_THEME_COLORS
            )
    },
    Setting(context, Settings.PREF_THEME_COLORS_NIGHT, R.string.theme_colors_night) { setting ->
        val ctx = LocalContext.current
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
        val prefs = ctx.prefs()
        if ((b?.value ?: 0) < 0)
            Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS_NIGHT)!!.getStringResourceOrName("theme_name_", ctx),
            onClick = { showDialog = true }
        )
        if (showDialog)
            ColorThemePickerDialog(
                onDismissRequest = { showDialog = false },
                setting = setting,
                isNight = true,
                default = Defaults.PREF_THEME_COLORS_NIGHT
            )
    },
    Setting(context, Settings.PREF_THEME_KEY_BORDERS, R.string.key_borders) {
        SwitchPreference(it, Defaults.PREF_THEME_KEY_BORDERS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_THEME_DAY_NIGHT, R.string.day_night_mode, R.string.day_night_mode_summary) {
        SwitchPreference(it, Defaults.PREF_THEME_DAY_NIGHT) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_NAVBAR_COLOR, R.string.theme_navbar, R.string.day_night_mode_summary) {
        SwitchPreference(it, Defaults.PREF_NAVBAR_COLOR)
    },
    Setting(context, SettingsWithoutKey.BACKGROUND_IMAGE, R.string.customize_background_image) {
        BackgroundImagePref(it, false)
    },
    Setting(context, SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE,
        R.string.customize_background_image_landscape, R.string.summary_customize_background_image_landscape)
    {
        BackgroundImagePref(it, true)
    },
    Setting(context, Settings.PREF_ENABLE_SPLIT_KEYBOARD, R.string.enable_split_keyboard) {
        var show by remember { mutableStateOf(false) }
        val prefAndName = listOfNotNull(
            Settings.PREF_ENABLE_SPLIT_KEYBOARD to stringResource(R.string.button_default),
            Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE to stringResource(R.string.landscape),
            if (!FoldableUtils.isFoldable) null else
                Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED to stringResource(R.string.folded),
            if (!FoldableUtils.isFoldable) null else
                Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE to stringResource(R.string.folded) + " / " + stringResource(R.string.landscape)
        )
        Preference(
            name = stringResource(R.string.enable_split_keyboard),
            onClick = { show = true },
            description = prefAndName.filter { LocalContext.current.prefs().getBoolean(it.first, Defaults.PREF_ENABLE_SPLIT_KEYBOARD) }
                .joinToString(", ") { it.second }.takeIf { it.isNotEmpty() }
        )
        if (show) {
            ThreeButtonAlertDialog(
                onDismissRequest = { show = false },
                onConfirmed = {},
                confirmButtonText = null,
                cancelButtonText = stringResource(R.string.dialog_close),
                content = {
                    Column {
                        prefAndName.forEach {
                            SwitchPreference(name = it.second, key = it.first, default = Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
                        }
                    }
                }
            )
        }
    },
    Setting(context, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, R.string.split_spacer_scale) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_SPLIT_SPACER_SCALE,
            range = 0.5f..2f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    // todo: also for landscape + folded, but maybe consider this variable gap setting first so it could be a scale setting (was in some PR)
    Setting(context, Settings.PREF_NARROW_KEY_GAPS, R.string.prefs_narrow_key_gaps) {
        SwitchPreference(it, Defaults.PREF_NARROW_KEY_GAPS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, R.string.prefs_keyboard_height_scale) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_KEYBOARD_HEIGHT_SCALE,
            range = 0.3f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, R.string.prefs_bottom_row_scale) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_BOTTOM_ROW_SCALE,
            range = 0.5f..2f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, R.string.prefs_bottom_padding_scale) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_BOTTOM_PADDING_SCALE,
            range = 0f..5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SIDE_PADDING_SCALE_PREFIX, R.string.prefs_side_padding_scale) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.split), stringResource(R.string.folded)),
            defaults = Defaults.PREF_SIDE_PADDING_SCALE,
            range = 0f..3f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SPACE_BAR_TEXT, R.string.prefs_space_bar_text) {
        TextInputPreference(it, Defaults.PREF_SPACE_BAR_TEXT)
    },
    Setting(context, SettingsWithoutKey.CUSTOM_FONT, R.string.custom_font) {
        CustomFontPreference(it, Settings.getCustomFontFile(LocalContext.current), R.string.custom_font)
    },
    Setting(context, Settings.PREF_FONT_SCALE, R.string.prefs_font_scale) { def ->
        SliderPreference(
            live = true,
            name = def.title,
            key = def.key,
            default = Defaults.PREF_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, SettingsWithoutKey.CUSTOM_EMOJI_FONT, R.string.custom_emoji_font) {
        CustomFontPreference(it, Settings.getCustomEmojiFontFile(LocalContext.current), R.string.custom_emoji_font)
    },
    Setting(context, Settings.PREF_EMOJI_FONT_SCALE, R.string.prefs_emoji_font_scale) { setting ->
        SliderPreference(
            live = true,
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_EMOJI_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_EMOJI_KEY_FIT, R.string.prefs_emoji_key_fit) {
        SwitchPreference(it, Defaults.PREF_EMOJI_KEY_FIT) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_EMOJI_SKIN_TONE, R.string.prefs_emoji_skin_tone) { setting ->
        val items = listOf(
            stringResource(R.string.prefs_emoji_skin_tone_neutral) to "",
            "\uD83C\uDFFB" to "\uD83C\uDFFB",
            "\uD83C\uDFFC" to "\uD83C\uDFFC",
            "\uD83C\uDFFD" to "\uD83C\uDFFD",
            "\uD83C\uDFFE" to "\uD83C\uDFFE",
            "\uD83C\uDFFF" to "\uD83C\uDFFF"
        )
        ListPreference(setting, items, Defaults.PREF_EMOJI_SKIN_TONE, live = true) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SUGGESTION_TEXT_SIZE, R.string.pref_suggestion_text_size) { setting ->
        SliderPreference(
            live = true,
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_SUGGESTION_TEXT_SIZE,
            range = 10f..32f,
            description = { "$it dp" }
        )
    },
    Setting(context, Settings.PREF_SUGGESTION_BOLD, R.string.pref_suggestion_bold) {
        SwitchPreference(it, Defaults.PREF_SUGGESTION_BOLD)
    },
    Setting(context, Settings.PREF_SUGGESTION_ITALIC, R.string.pref_suggestion_italic) {
        SwitchPreference(it, Defaults.PREF_SUGGESTION_ITALIC)
    },
    Setting(context, Settings.PREF_SUGGESTION_UNDERLINE, R.string.pref_suggestion_underline) {
        SwitchPreference(it, Defaults.PREF_SUGGESTION_UNDERLINE)
    },
    Setting(context, Settings.PREF_SUGGESTION_WORD_PADDING, R.string.pref_suggestion_word_padding) { setting ->
        SliderPreference(
            live = true,
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_SUGGESTION_WORD_PADDING,
            range = 0f..30f,
            description = { "$it dp" }
        )
    },
    Setting(context, Settings.PREF_KEY_HORIZONTAL_GAP, R.string.pref_key_horizontal_gap) { setting ->
        SliderPreference(
            live = true,
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEY_HORIZONTAL_GAP,
            range = 0f..3f,
            description = { "%.2f%%".format(it) }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_KEY_VERTICAL_GAP, R.string.pref_key_vertical_gap) { setting ->
        SliderPreference(
            live = true,
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEY_VERTICAL_GAP,
            range = 0f..6f,
            description = { "%.2f%%".format(it) }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_TOOLBAR_EXPAND_ICON, R.string.pref_toolbar_expand_icon) { setting ->
        val items = listOf(
            stringResource(R.string.pref_toolbar_expand_icon_arrow) to "arrow",
            stringResource(R.string.pref_toolbar_expand_icon_incognito) to "incognito",
            stringResource(R.string.pref_toolbar_expand_icon_settings) to "settings",
            stringResource(R.string.pref_toolbar_expand_icon_none) to "none",
        )
        ListPreference(setting, items, Defaults.PREF_TOOLBAR_EXPAND_ICON, live = true) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            AppearanceScreen { }
        }
    }
}

/** The saved looks: pick one to apply it, save the current look under a name, rename or delete your own. */
@Composable
private fun SavedLooksPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var generation by remember { mutableIntStateOf(0) }
    val looks = remember(generation) { AppearanceLooks.load(prefs) }
    var showList by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var toRename: AppearanceLooks.Look? by remember { mutableStateOf(null) }
    var toDelete: AppearanceLooks.Look? by remember { mutableStateOf(null) }
    fun store(list: List<AppearanceLooks.Look>) { AppearanceLooks.save(prefs, list); generation++ }
    Preference(name = setting.title, description = setting.description, onClick = { showList = true }) { NextScreenIcon() }
    if (showList)
        ListPickerDialog(
            onDismissRequest = { showList = false },
            title = { Text(setting.title) },
            items = looks,
            getItemName = { it.name },
            showRadioButtons = false,
            onItemSelected = { AppearanceLooks.apply(ctx, it.values) },
            trailing = { look ->
                IconButton({ showList = false; toRename = look }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.appearance_look_rename)) }
                DeleteButton { showList = false; toDelete = look }
            },
            footer = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable { showList = false; saveAs = true }
                        .padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_plus), null, Modifier.padding(horizontal = 12.dp))
                    Text(stringResource(R.string.appearance_look_save), color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    if (saveAs)
        TextInputDialog(
            onDismissRequest = { saveAs = false },
            title = { Text(stringResource(R.string.appearance_look_save)) },
            initialText = stringResource(R.string.appearance_look_default_name, looks.size + 1),
            checkTextValid = { name -> name.isNotBlank() && looks.none { it.name == name } },
            onConfirmed = { name -> store(looks + AppearanceLooks.Look(name, AppearanceLooks.current(prefs))) },
        )
    toRename?.let { look ->
        TextInputDialog(
            onDismissRequest = { toRename = null },
            title = { Text(stringResource(R.string.appearance_look_rename)) },
            initialText = look.name,
            checkTextValid = { name -> name.isNotBlank() && looks.none { it !== look && it.name == name } },
            onConfirmed = { name -> store(looks.map { if (it === look) AppearanceLooks.Look(name, it.values) else it }) },
        )
    }
    toDelete?.let { look ->
        ConfirmationDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.appearance_look_delete, look.name)) },
            confirmButtonText = stringResource(R.string.delete),
            onConfirmed = { store(looks.filter { it !== look }) },
        )
    }
}

/**
 * The keyboard as the Appearance preview: up while a dialog is open or for a few seconds after a change, and
 * gone again afterwards; only when the user put the cursor in the try-it field themselves does it stay.
 */
private class PreviewKeyboard(private val tryIt: TryItState, private val scope: CoroutineScope, private val hide: () -> Unit) : PreviewKeyboardHooks {
    private var focused = false
    private var byUs = false // we brought it up, so we take it down
    private var dialogs = 0
    private var hideJob: Job? = null

    fun onFocus(isFocused: Boolean) {
        focused = isFocused
        if (!isFocused) byUs = false
    }

    private fun show() {
        hideJob?.cancel()
        if (!focused) { byUs = true; tryIt.show(TryItMode.TEXT) }
    }

    private fun hideIfOurs() {
        if (!byUs) return
        byUs = false
        hide()
    }

    override fun dialogOpened() { dialogs++; show() }
    override fun dialogClosed() {
        dialogs = (dialogs - 1).coerceAtLeast(0)
        if (dialogs == 0) hideIfOurs()
    }

    fun changed() {
        if (dialogs > 0) return
        show()
        hideJob = scope.launch { delay(3000); hideIfOurs() }
    }
}

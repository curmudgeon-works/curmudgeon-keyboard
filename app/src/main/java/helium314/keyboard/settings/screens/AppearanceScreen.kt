// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.ui.layout.layout
import helium314.keyboard.settings.dialogs.UnsavedChangesDialog
import helium314.keyboard.settings.dialogs.SaveChangesDialog
import helium314.keyboard.settings.dialogs.DiscardChangesDialog
import androidx.compose.runtime.DisposableEffect

import helium314.keyboard.settings.dialogs.LocalPreviewEmojiPeople

import helium314.keyboard.settings.preferences.ScalePart

import helium314.keyboard.settings.DropDownField

import helium314.keyboard.settings.WithSmallTitle

import androidx.compose.runtime.mutableFloatStateOf

import androidx.compose.material3.Slider

import helium314.keyboard.latin.utils.ResourceUtils

import androidx.core.view.WindowInsetsCompat

import androidx.core.view.ViewCompat

import androidx.compose.ui.platform.LocalView

import helium314.keyboard.settings.TapRevealer

import helium314.keyboard.settings.dialogs.LocalPreviewEmoji

import helium314.keyboard.settings.preferences.TextStyleKeys

import helium314.keyboard.settings.preferences.TextStylePreference
import helium314.keyboard.settings.preferences.FontsPreference

import androidx.compose.foundation.layout.Box

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt

import helium314.keyboard.settings.dialogs.LocalBottomBarTop

import helium314.keyboard.keyboard.KeyboardLayoutSet

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
import helium314.keyboard.settings.preferences.symbolHintPrefs
import helium314.keyboard.settings.preferences.HideAllSymbolsPreference
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.FontLibrary
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
        // ---- the theme first: the saved themes, then everything a theme sets
        // (keyboard height, numbers row, split keyboard, bottom row and side padding are on Layout & Typing)
        // the saved themes on top, on their own between two lines: a theme covers the whole screen (but the emoji
        // version), not just the Theme group (2026-10-03)
        SettingsWithoutKey.DIVIDER,
        SettingsWithoutKey.APPEARANCE_LOOKS,
        SettingsWithoutKey.DIVIDER,
        R.string.appearance_group_theme,
        // light / dark following the system first; when on, the light and the dark colours sit under it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            Settings.PREF_THEME_DAY_NIGHT else null,
        Settings.PREF_THEME_COLORS,
        if (dayNightMode) Settings.PREF_THEME_COLORS_NIGHT else null,
        Settings.PREF_SUGGESTION_WORD_PADDING, // spacing between suggestions (advanced; saved with a theme)
        SettingsWithoutKey.FONTS, // keys, symbols, suggestions: one dialog
        Settings.PREF_THEME_STYLE,
        Settings.PREF_ICON_STYLE,
        Settings.PREF_CUSTOM_ICON_NAMES,
        // (part of themes too since 2026-10-02; the per-area symbol rows are on Others)
        SettingsWithoutKey.HIDE_ALL_SYMBOLS,
        Settings.PREF_THEME_KEY_BORDERS,
        Settings.PREF_KEY_HORIZONTAL_GAP,
        Settings.PREF_KEY_VERTICAL_GAP,
        Settings.PREF_SPACE_BAR_TEXT,
        // the background picture last
        SettingsWithoutKey.BACKGROUND_IMAGE,
        SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE,
        // only with a picture set: keys painted or clear on it
        if (listOf(false, true).any { night -> listOf(false, true).any { land -> Settings.getCustomBackgroundFile(ctx, night, land).exists() } })
            Settings.PREF_BACKGROUND_WHOLE_PICTURE else null,
        // ---- then the emoji preferences (in themes, but the version, last here)
        R.string.appearance_group_emoji,
        Settings.PREF_EMOJI_FONT_SCALE,
        if (prefs.getFloat(Settings.PREF_EMOJI_FONT_SCALE, Defaults.PREF_EMOJI_FONT_SCALE) != 1f)
            Settings.PREF_EMOJI_KEY_FIT else null,
        if (prefs.getInt(Settings.PREF_EMOJI_MAX_SDK, helium314.keyboard.keyboard.emoji.SupportedEmojis.DEFAULT) >= 24)
            Settings.PREF_EMOJI_SKIN_TONE else null,
        SettingsWithoutKey.CUSTOM_EMOJI_FONT,
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
        // which emojis show: what the emoji font can draw, overridable (e.g. for a newer font); advanced, not in themes
        Settings.PREF_EMOJI_MAX_SDK,
    )
    // every change shows on the live keyboard at once; the draft remembers how things were when the screen opened
    // after Accept or Reject the current state is the new starting point: a fresh snapshot
    var draft by remember { mutableStateOf(AppearanceDraft.of(ctx)) }
    val changed = draft.hasChanges(ctx)
    val pendingKeys = if (changed) draft.changedKeys(ctx) else emptySet()
    val pendingFiles = if (changed) draft.changedFiles() else emptySet()
    var askOnLeave by remember { mutableStateOf(false) }
    var askReject by remember { mutableStateOf(false) }
    var askAccept by remember { mutableStateOf(false) }
    val tryIt = remember { TryItState() }
    // the keyboard being edited (its own settings), else the one in use: the preview switches to it
    val keyboard = helium314.keyboard.latin.settings.KeyboardProfiles.editingKeyboard(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)) ?: SubtypeSettings.getSelectedSubtype(prefs).toSettingsSubtype()
    val focusManager = LocalFocusManager.current
    val softKeyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // where the keyboard's top (with the try-it bar on it) will be: as seen the last time it was up, else computed from
    // the keyboard's own height; a row tapped below that line is moved above it as the keyboard is raised
    val revealer = remember { TapRevealer() }
    val view = LocalView.current
    var hiddenBarTop by remember { mutableIntStateOf(-1) }
    var shownBarTop by remember { mutableIntStateOf(-1) }
    fun keyboardLine(): Int {
        if (shownBarTop > 0) return shownBarTop
        if (hiddenBarTop <= 0) return Int.MAX_VALUE
        val sv = Settings.getValues()
        val strip = ctx.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        return hiddenBarTop - ResourceUtils.getKeyboardHeight(ctx.resources, sv) - strip
    }
    val preview = remember { PreviewKeyboard(tryIt, scope, showIme = { softKeyboard?.show() }, reveal = { revealer.revealAbove(keyboardLine()) }) {
        focusManager.clearFocus(force = true); softKeyboard?.hide() } }
    // a changed appearance value (a switch, say) brings the keyboard up for a moment; dialogs report themselves
    var lastValues by remember { mutableStateOf(AppearanceLooks.current(prefs)) }
    LaunchedEffect(b?.value) {
        val now = AppearanceLooks.current(prefs)
        if (now != lastValues) {
            val changedKeys = (now.keys + lastValues.keys).filter { now[it] != lastValues[it] }
            lastValues = now
            preview.changed(emoji = changedKeys.any { it in emojiKeys })
        }
    }
    // checked when leaving, not taken from this composition: the top bar's back arrow can hold an older copy of this
    // function (from before the first change), and the back gesture and the arrow must both ask
    fun leave() { if (draft.hasChanges(ctx)) askOnLeave = true else { AppearanceDraft.close(); onClickBack() } }
    // back in the app after leaving it (the changes were undone then): a new snapshot of what is there now
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) draft = AppearanceDraft.of(ctx) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = changed) { leave() }
    var bottomBarTop by remember { mutableIntStateOf(-1) }
    DisposableEffect(Unit) { onDispose { (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = Int.MAX_VALUE } }
    CompositionLocalProvider(LocalKeepKeyboard provides true, LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) { SearchSettingsScreen(
        onClickBack = ::leave,
        title = stringResource(R.string.settings_screen_appearance),
        settings = items,
        simpleModeKeys = setOf(
            SettingsWithoutKey.DIVIDER, SettingsWithoutKey.APPEARANCE_LOOKS, Settings.PREF_THEME_STYLE, Settings.PREF_THEME_COLORS, Settings.PREF_THEME_KEY_BORDERS, Settings.PREF_THEME_DAY_NIGHT,
            Settings.PREF_THEME_COLORS_NIGHT, SettingsWithoutKey.FONTS,
            SettingsWithoutKey.HIDE_ALL_SYMBOLS,
            // the emoji size (with its fit) and skin tone (the emoji font from a file: advanced)
            Settings.PREF_EMOJI_FONT_SCALE, Settings.PREF_EMOJI_KEY_FIT, Settings.PREF_EMOJI_SKIN_TONE,
        ),
        // cross and tick: reject or accept everything changed since the screen opened, each asks first
        topActions = {
            if (changed) {
                IconButton({ askReject = true }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.appearance_reject)) }
                IconButton({ askAccept = true }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.appearance_accept)) }
            }
        },
        bottomBar = { Box(Modifier.onGloballyPositioned {
            bottomBarTop = it.positionInWindow().y.roundToInt()
            // the try-it bar stays usable while a dialog is open (typing, the ABC / 123 / ☎ / 😀 tabs)
            (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = bottomBarTop
            val imeUp = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeUp) shownBarTop = bottomBarTop else hiddenBarTop = bottomBarTop
        }) { TryItBar(keyboard, tryIt, onFocus = preview::onFocus, onUsed = preview::onUsed) } },
        revealer = revealer,
        isPending = { tile -> tileChanged(tile, pendingKeys, pendingFiles) },
    )
    if (askReject) DiscardChangesDialog({ askReject = false }) { draft.reject(ctx); draft = AppearanceDraft.of(ctx) }
    if (askAccept) SaveChangesDialog({ askAccept = false }) { draft.accept(); draft = AppearanceDraft.of(ctx) }
    }
    if (askOnLeave) UnsavedChangesDialog(
        onKeepWorking = { askOnLeave = false },
        onDiscardAndExit = { draft.reject(ctx); askOnLeave = false; onClickBack() },
        onSaveAndExit = { draft.accept(); askOnLeave = false; onClickBack() },
    )
}

/** Which of Midnight / Daylight the light-dark pairing came from (see the light / dark switch), for turning it off. */
private const val PAIRED_FROM = "day_night_paired_from"

/** Settings whose change is best seen on the emoji panel. */
private val emojiKeys = setOf(Settings.PREF_EMOJI_FONT_SCALE, Settings.PREF_EMOJI_KEY_FIT, Settings.PREF_EMOJI_SKIN_TONE,
    Settings.PREF_EMOJI_MAX_SDK, Settings.PREF_EMOJI_FONT)

fun createAppearanceSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.FONTS, R.string.fonts_title) {
        FontsPreference(it, listOf(
            R.string.text_style_keys to TextStyleKeys(Settings.PREF_KEY_FONT, FontLibrary.SLOT_KEY, Settings.PREF_FONT_SCALE,
                Defaults.PREF_FONT_SCALE, 0.5f..1.5f, Settings.PREF_KEY_TEXT_BOLD,
                // until the B toggle is used, the key style decides: Holo draws its keys bold
                { p -> p.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE) == KeyboardTheme.STYLE_HOLO },
                Settings.PREF_KEY_TEXT_ITALIC, Settings.PREF_KEY_TEXT_UNDERLINE),
            R.string.text_style_symbols to TextStyleKeys(Settings.PREF_HINT_FONT, FontLibrary.SLOT_HINT, Settings.PREF_HINT_FONT_SCALE,
                Defaults.PREF_HINT_FONT_SCALE, 0.5f..2f, Settings.PREF_HINT_TEXT_BOLD, { Defaults.PREF_HINT_TEXT_BOLD },
                Settings.PREF_HINT_TEXT_ITALIC, Settings.PREF_HINT_TEXT_UNDERLINE),
            R.string.text_style_suggestions to TextStyleKeys(Settings.PREF_SUGGESTION_FONT, FontLibrary.SLOT_SUGGESTION,
                Settings.PREF_SUGGESTION_TEXT_SIZE, Defaults.PREF_SUGGESTION_TEXT_SIZE.toFloat(), 10f..32f,
                Settings.PREF_SUGGESTION_BOLD, { Defaults.PREF_SUGGESTION_BOLD }, Settings.PREF_SUGGESTION_ITALIC, Settings.PREF_SUGGESTION_UNDERLINE,
                sizeIsInt = true, sizeText = { "${it.roundToInt()} dp" },
                // their colour (part of themes), under the size
                extraKeys = listOf(Settings.PREF_SUGGESTION_TEXT_COLOR),
                extra = { reload -> helium314.keyboard.settings.preferences.SuggestionColorRow(reload) }),
        ))
    },
    Setting(context, SettingsWithoutKey.HIDE_ALL_SYMBOLS, R.string.hide_all_symbols) {
        HideAllSymbolsPreference(it)
    },
    Setting(context, SettingsWithoutKey.APPEARANCE_LOOKS, R.string.appearance_looks) {
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
            // (upstream reset Holo White colours on leaving the Holo style; they're allowed with every style now)
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
        // "Colors (light)" under the light / dark switch when it's on, plain "Colors" otherwise
        val dayNight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
        Box(if (dayNight) Modifier.tuckedUnder().padding(start = 16.dp) else Modifier) {
            Preference(
                name = if (dayNight) stringResource(R.string.theme_colors_light) else setting.title,
                description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS)!!.getStringResourceOrName("theme_name_", ctx),
                onClick = { showDialog = true }
            )
        }
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
        Box(Modifier.tuckedUnder().padding(start = 16.dp)) { // (shown only under the light / dark switch, when it's on)
            Preference(
                name = setting.title,
                description = prefs.getString(setting.key, Defaults.PREF_THEME_COLORS_NIGHT)!!.getStringResourceOrName("theme_name_", ctx),
                onClick = { showDialog = true }
            )
        }
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
    Setting(context, Settings.PREF_THEME_DAY_NIGHT, R.string.day_night_mode) { setting ->
        val prefs = LocalContext.current.prefs()
        SwitchPreference(setting, Defaults.PREF_THEME_DAY_NIGHT) { on ->
            // Midnight (black) or Daylight (light) with light / dark turned on: the pair, Daylight in light mode and
            // Midnight in dark mode (the two themes differ only in their colours); off again: the one it came from
            val day = prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
            val night = prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
            val black = KeyboardTheme.THEME_BLACK; val light = KeyboardTheme.THEME_LIGHT
            if (on && day == night && (day == black || day == light)) prefs.edit {
                putString(Settings.PREF_THEME_COLORS, light); putString(Settings.PREF_THEME_COLORS_NIGHT, black)
                putString(PAIRED_FROM, day)
            } else if (!on && day == light && night == black) {
                val from = prefs.getString(PAIRED_FROM, black)!!
                prefs.edit { putString(Settings.PREF_THEME_COLORS, from); putString(Settings.PREF_THEME_COLORS_NIGHT, from); remove(PAIRED_FROM) }
            }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_BACKGROUND_WHOLE_PICTURE, R.string.background_whole_picture) {
        Box(Modifier.padding(start = 16.dp)) {
            // shown the other way round: on (the default) = keys painted over the picture
            SwitchPreference(it, Defaults.PREF_BACKGROUND_WHOLE_PICTURE, inverted = true) { KeyboardSwitcher.getInstance().setThemeNeedsReload() } }
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
                title = { Text(stringResource(R.string.enable_split_keyboard)) },
                confirmButtonText = null,
                cancelButtonText = stringResource(R.string.dialog_close),
                content = {
                    Column {
                        prefAndName.forEach {
                            SwitchPreference(name = it.second, key = it.first, default = Defaults.PREF_ENABLE_SPLIT_KEYBOARD) {
                                // the live keyboard shows the split right away
                                KeyboardLayoutSet.onSystemLocaleChanged()
                                KeyboardSwitcher.getInstance().setThemeNeedsReload()
                            }
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
    Setting(context, Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, R.string.prefs_keyboard_height_scale) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            alwaysShown = setOf(stringResource(R.string.landscape)), // portrait and landscape sliders, no box to tick
            baseVariantName = stringResource(R.string.portrait),
            defaults = Defaults.PREF_KEYBOARD_HEIGHT_SCALE,
            range = 0.3f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, R.string.prefs_bottom_row_size) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.folded)),
            defaults = Defaults.PREF_BOTTOM_ROW_SCALE,
            range = 0.5f..2f,
            description = { "${(100 * it).toInt()}%" },
            firstTitle = stringResource(R.string.bottom_row_part_scale),
            more = listOf(ScalePart(stringResource(R.string.bottom_row_part_padding), Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
                Defaults.PREF_BOTTOM_PADDING_SCALE, 0f..5f) { "${(100 * it).toInt()}%" }),
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SIDE_PADDING_SCALE_PREFIX, R.string.prefs_side_padding_scale) { setting ->
        KeyboardScalePreference(
            live = true,
            name = setting.title,
            baseKey = setting.key,
            dimensions = listOf(stringResource(R.string.landscape), stringResource(R.string.split), stringResource(R.string.folded)),
            alwaysShown = setOf(stringResource(R.string.split)), // portrait, landscape, split, split landscape: all four, no boxes
            defaults = Defaults.PREF_SIDE_PADDING_SCALE,
            range = 0f..3f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SPACE_BAR_TEXT, R.string.prefs_space_bar_text) {
        TextInputPreference(it, Defaults.PREF_SPACE_BAR_TEXT)
    },
    Setting(context, SettingsWithoutKey.CUSTOM_EMOJI_FONT, R.string.custom_emoji_font) {
        CompositionLocalProvider(LocalPreviewEmoji provides true) {
        // a choice from the font list shared by all keyboards (the file of before moved into it)
        helium314.keyboard.settings.preferences.EmojiFontPreference(it)
        }
    },
    Setting(context, Settings.PREF_EMOJI_FONT_SCALE, R.string.prefs_emoji_font_scale) { setting ->
        CompositionLocalProvider(LocalPreviewEmoji provides true) {
        SliderPreference(
            live = true,
            applyOnRelease = true, // a keyboard rebuild per drag step flickers
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_EMOJI_FONT_SCALE,
            range = 0.5f..1.5f,
            description = { "${(100 * it).toInt()}%" }
        ) { KeyboardSwitcher.getInstance().clearEmojiCache(); KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        }
    },
    Setting(context, Settings.PREF_EMOJI_KEY_FIT, R.string.prefs_emoji_key_fit) {
        CompositionLocalProvider(LocalPreviewEmoji provides true) {
        SwitchPreference(it, Defaults.PREF_EMOJI_KEY_FIT) { KeyboardSwitcher.getInstance().clearEmojiCache(); KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        }
    },
    Setting(context, Settings.PREF_EMOJI_SKIN_TONE, R.string.prefs_emoji_skin_tone) { setting ->
        CompositionLocalProvider(LocalPreviewEmoji provides true, LocalPreviewEmojiPeople provides true) {
        val items = listOf(
            stringResource(R.string.prefs_emoji_skin_tone_neutral) to "",
            "\uD83C\uDFFB" to "\uD83C\uDFFB",
            "\uD83C\uDFFC" to "\uD83C\uDFFC",
            "\uD83C\uDFFD" to "\uD83C\uDFFD",
            "\uD83C\uDFFE" to "\uD83C\uDFFE",
            "\uD83C\uDFFF" to "\uD83C\uDFFF"
        )
        ListPreference(setting, items, Defaults.PREF_EMOJI_SKIN_TONE, live = true) { KeyboardSwitcher.getInstance().clearEmojiCache(); KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        }
    },
    Setting(context, Settings.PREF_KEY_HORIZONTAL_GAP, R.string.pref_key_horizontal_gap) { setting ->
        SliderPreference(
            live = true,
            applyOnRelease = true, // a keyboard rebuild per drag step flickers
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEY_HORIZONTAL_GAP,
            range = 0f..3f,
            description = { "%.2f%%".format(it) }
        ) { KeyboardLayoutSet.onSystemLocaleChanged(); KeyboardSwitcher.getInstance().setThemeNeedsReload() } // built keyboards are cached: drop them so the gap shows
    },
    Setting(context, Settings.PREF_KEY_VERTICAL_GAP, R.string.pref_key_vertical_gap) { setting ->
        SliderPreference(
            live = true,
            applyOnRelease = true, // a keyboard rebuild per drag step flickers
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEY_VERTICAL_GAP,
            range = 0f..6f,
            description = { "%.2f%%".format(it) }
        ) { KeyboardLayoutSet.onSystemLocaleChanged(); KeyboardSwitcher.getInstance().setThemeNeedsReload() } // built keyboards are cached: drop them so the gap shows
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
    val builtIn = remember { AppearanceLooks.builtIn(ctx) }
    var showList by remember { mutableStateOf(false) }
    // a tap shows the theme on the live keyboard; OK keeps it, Cancel puts back what was set when the list opened
    val initial = remember(showList) { AppearanceLooks.current(prefs) }
    // the background pictures when the list opened, to put back on Cancel (a copy, made only while the list is open)
    val initialPictures = remember(showList) { if (showList) AppearanceLooks.currentPictures(ctx) else null }
    var confirmed by remember(showList) { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var toRename: AppearanceLooks.Look? by remember { mutableStateOf(null) }
    var toDelete: AppearanceLooks.Look? by remember { mutableStateOf(null) }
    fun store(list: List<AppearanceLooks.Look>) { AppearanceLooks.save(prefs, list); generation++ }
    // the chosen theme's name; "tweaked" when a value it sets (or its pictures) differs now, "unsaved" while the
    // theme's part of the screen has changes not saved yet
    // (a new install's look is Midnight: named so until another theme is chosen)
    val chosenName = prefs.getString(AppearanceLooks.PREF_SELECTED, null)
        ?: ctx.getString(R.string.theme_preset_midnight).takeIf { prefs.getBoolean("look_default_midnight", false) }
    val chosen = chosenName?.let { name -> (builtIn + looks).firstOrNull { it.name == name } }
    val tweaked = chosen != null && AppearanceLooks.isTweaked(ctx, chosen)
    val draft = AppearanceDraft.of(ctx)
    val unsaved = draft.changedKeys(ctx).any { AppearanceLooks.inScope(it) || it == AppearanceLooks.PREF_SELECTED }
        || draft.changedFiles().any { it.startsWith("custom_background") }
    val state = listOfNotNull(stringResource(R.string.theme_tweaked).takeIf { tweaked }, stringResource(R.string.theme_unsaved).takeIf { unsaved })
    val summary = chosen?.let { if (state.isEmpty()) it.name else it.name + " (" + state.joinToString(", ") + ")" }
    Preference(name = setting.title, description = summary, onClick = { showList = true }) { NextScreenIcon() }
    if (showList)
        ListPickerDialog(
            onDismissRequest = {
                if (!confirmed) {
                    if (AppearanceLooks.current(prefs) != initial) AppearanceLooks.apply(ctx, initial)
                    initialPictures?.let { AppearanceLooks.applyPictures(ctx, it) }
                }
                initialPictures?.let { AppearanceLooks.deletePictures(ctx, it) }
                showList = false
            },
            title = { Text(setting.title) },
            items = builtIn + looks,
            getItemName = { it.name },
            confirmImmediately = false,
            // a theme only changes what it lists: the rest stays as it was when the list opened (height, fonts, switches…);
            // starting from `initial` on every tap also means one previewed theme never leaks into the next
            onItemHighlighted = { AppearanceLooks.apply(ctx, initial + it.values); AppearanceLooks.applyPictures(ctx, it) },
            onItemSelected = { confirmed = true; prefs.edit { putString(AppearanceLooks.PREF_SELECTED, it.name) } },
            // the built-in themes come first and can't be changed; the user's own are renamed and deleted here
            trailing = { look -> if (look !in builtIn) {
                IconButton({ confirmed = true; showList = false; toRename = look }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.appearance_look_rename)) }
                DeleteButton { confirmed = true; showList = false; toDelete = look }
            } },
            footer = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().clickable { confirmed = true; showList = false; saveAs = true }
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
            onConfirmed = { name ->
                store(looks + AppearanceLooks.Look(name,
                    AppearanceLooks.snapshot(prefs) + (AppearanceLooks.PICTURES to AppearanceLooks.savePictures(ctx))))
                prefs.edit { putString(AppearanceLooks.PREF_SELECTED, name) } // what's on the keyboard now is this theme
            },
        )
    toRename?.let { look ->
        TextInputDialog(
            onDismissRequest = { toRename = null },
            title = { Text(stringResource(R.string.appearance_look_rename)) },
            initialText = look.name,
            checkTextValid = { name -> name.isNotBlank() && looks.none { it !== look && it.name == name } },
            onConfirmed = { name ->
                store(looks.map { if (it === look) AppearanceLooks.Look(name, it.values) else it })
                if (prefs.getString(AppearanceLooks.PREF_SELECTED, null) == look.name) prefs.edit { putString(AppearanceLooks.PREF_SELECTED, name) }
            },
        )
    }
    toDelete?.let { look ->
        ConfirmationDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.appearance_look_delete, look.name)) },
            confirmButtonText = stringResource(R.string.delete),
            onConfirmed = {
                AppearanceLooks.deletePictures(ctx, look); store(looks.filter { it !== look })
                if (prefs.getString(AppearanceLooks.PREF_SELECTED, null) == look.name) prefs.edit { remove(AppearanceLooks.PREF_SELECTED) }
            },
        )
    }
}

/**
 * The keyboard as the Appearance preview: up while a dialog is open or for a few seconds after a change, and
 * gone again afterwards; only when the user put the cursor in the try-it field themselves does it stay.
 */
internal class PreviewKeyboard( // (also the Preferences screen's, for the key sound settings)
    private val tryIt: TryItState, private val scope: CoroutineScope, private val showIme: () -> Unit,
    private val reveal: () -> Unit, private val hide: () -> Unit) : PreviewKeyboardHooks {
    init { latest = java.lang.ref.WeakReference(this) }

    companion object {
        /** The preview keyboard of the screen opened last: the Save / Discard questions reach it even when composed
         *  outside the screen's LocalPreviewKeyboard. */
        var latest: java.lang.ref.WeakReference<PreviewKeyboard>? = null
    }

    private var focused = false
    private var byUs = false // we brought it up, so we take it down
    private var dialogs = 0
    private var hideJob: Job? = null

    fun onFocus(isFocused: Boolean) {
        // the try-it box got the focus back by itself (the Save / Discard question closing hands the window, and with it
        // the box's focus and keyboard, back): not wanted until the screen is touched again
        if (isFocused && isQuiet()) { scope.launch { hide() }; return }
        focused = isFocused
        if (!isFocused) byUs = false
    }

    private var emojiByUs = false // we switched the preview to the emoji panel, so we switch it back

    private fun show(emoji: Boolean = false, people: Boolean = false) {
        hideJob?.cancel()
        // "Bring up preview keyboard automatically" off: only the user's tap on the try-it box brings it up; a keyboard
        // already up (they tapped) still follows, e.g. to the emoji panel
        val auto = helium314.keyboard.latin.settings.Settings.getCurrentContext()?.prefs()
            ?.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_AUTO_PREVIEW_KEYBOARD,
                helium314.keyboard.latin.settings.Defaults.PREF_AUTO_PREVIEW_KEYBOARD) ?: true
        if (!auto && !focused) return
        reveal() // the tapped row moves above where the keyboard will end, together with it
        if (emoji) {
            // straight to the emoji panel (the people page for the skin tone), like the 😀 tab
            emojiByUs = true
            if (!focused) byUs = true
            tryIt.show(TryItMode.EMOJI, people)
        } else if (!focused) { byUs = true; tryIt.show(TryItMode.TEXT) }
        // a dialog opening at the same moment takes the window focus for a while and the keyboard request can be
        // lost; once the dialog has told the system it doesn't need the keyboard, asking again works
        scope.launch { delay(150); showIme(); delay(400); if (byUs || focused) showIme() }
    }

    private fun backToLetters() {
        if (!emojiByUs) return
        emojiByUs = false
        tryIt.mode = TryItMode.TEXT // the tab goes back to ABC, the keyboard to the letters
        runCatching { KeyboardSwitcher.getInstance().setAlphabetKeyboard() }
    }

    private fun hideIfOurs() {
        backToLetters()
        if (!byUs) return
        byUs = false
        hide()
    }

    private var quietSince = 0L
    // quiet() was called and the screen hasn't been touched since
    private fun isQuiet() = quietSince > 0 && SettingsActivity.lastTouchDown < quietSince

    override fun quiet() {
        hideJob?.cancel()
        backToLetters()
        // down now, the try-it box without focus (else the keyboard comes back with the focus when the question goes),
        // whoever brought it up
        byUs = false
        focused = false
        quietSince = android.os.SystemClock.uptimeMillis()
        hide()
    }

    override fun dialogOpened(emoji: Boolean, people: Boolean) { dialogs++; if (!isQuiet()) show(emoji, people) }
    private var keepAfterClose = false
    override fun keepAfterClose() { keepAfterClose = true }

    override fun dialogClosed() {
        dialogs = (dialogs - 1).coerceAtLeast(0)
        if (dialogs != 0) return
        if (keepAfterClose) { // what was picked stays visible a while (typing in the box keeps it up for good)
            keepAfterClose = false
            hideJob?.cancel()
            hideJob = scope.launch { delay(3000); hideIfOurs() }
        } else hideIfOurs()
    }

    /** The user is typing in the try-it box: the keyboard is theirs now, it doesn't go away on a timer. */
    fun onUsed() {
        hideJob?.cancel()
        byUs = false
    }

    fun changed(emoji: Boolean) {
        if (dialogs > 0 || isQuiet()) return
        show(emoji)
        hideJob = scope.launch { delay(3000); hideIfOurs() }
    }
}

/** Whether a tile on this screen covers a preference or file changed since the draft's snapshot. */
private fun tileChanged(tile: String, keys: Set<String>, files: Set<String>): Boolean {
    if (keys.isEmpty() && files.isEmpty()) return false
    fun any(vararg k: String) = k.any { it in keys }
    fun prefix(vararg p: String) = keys.any { key -> p.any { key.startsWith(it) } }
    return when (tile) {
        SettingsWithoutKey.APPEARANCE_LOOKS -> false
        SettingsWithoutKey.HIDE_ALL_SYMBOLS -> symbolHintPrefs.any { it.first in keys }
        SettingsWithoutKey.FONTS -> any(Settings.PREF_SUGGESTION_TEXT_COLOR, Settings.PREF_KEY_FONT, Settings.PREF_FONT_SCALE, Settings.PREF_KEY_TEXT_BOLD,
            Settings.PREF_KEY_TEXT_ITALIC, Settings.PREF_KEY_TEXT_UNDERLINE,
            Settings.PREF_HINT_FONT, Settings.PREF_HINT_FONT_SCALE, Settings.PREF_HINT_TEXT_BOLD, Settings.PREF_HINT_TEXT_ITALIC,
            Settings.PREF_HINT_TEXT_UNDERLINE, Settings.PREF_SUGGESTION_FONT, Settings.PREF_SUGGESTION_TEXT_SIZE,
            Settings.PREF_SUGGESTION_BOLD, Settings.PREF_SUGGESTION_ITALIC, Settings.PREF_SUGGESTION_UNDERLINE)
        SettingsWithoutKey.CUSTOM_EMOJI_FONT -> Settings.PREF_EMOJI_FONT in keys
        SettingsWithoutKey.BACKGROUND_IMAGE -> files.any { it.startsWith("custom_background_image") && !it.contains("landscape") }
        SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE -> files.any { it.startsWith("custom_background_image_landscape") }
        Settings.PREF_ENABLE_SPLIT_KEYBOARD -> any(Settings.PREF_ENABLE_SPLIT_KEYBOARD, Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE,
            Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE)
        Settings.PREF_BOTTOM_ROW_SCALE_PREFIX -> prefix(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX)
        Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT -> tile in keys
            || prefix(Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX)
        else -> tile in keys || keys.any { it.startsWith(tile) } // the scales keep a key per orientation after their prefix
    }
}

/** A row that belongs to the one above it (the colours under the light / dark switch): 8 dp closer to it. */
private fun Modifier.tuckedUnder(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val pull = 8.dp.roundToPx()
    layout(placeable.width, (placeable.height - pull).coerceAtLeast(0)) { placeable.place(0, -pull) }
}

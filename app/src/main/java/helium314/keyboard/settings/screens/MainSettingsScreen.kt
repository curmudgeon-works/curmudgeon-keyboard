// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AdvancedTint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.settings.SettingsDestination
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import helium314.keyboard.latin.settings.SettingsSubtype
import android.content.Context
import helium314.keyboard.settings.SettingsMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.gesture.OwnGestureDecoder
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.previewDark

@Composable
fun MainSettingsScreen(
    keyboard: SettingsSubtype,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = keyboardName(keyboard, ctx),
        settings = emptyList(),
        showKeyboardName = false, // (the title is its name)
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                KeyboardSettingsEntries(keyboard,
                    showAdvanced = !helium314.keyboard.latin.settings.KeyboardProfiles.isSeparate(LocalContext.current.realPrefs()))
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen(SettingsSubtype(java.util.Locale.ENGLISH, "")) {}
        }
    }
}

/** "English (US) + Hinglish" */
/** The name the user gave [keyboard] (long-press > Rename), or null. */
fun customKeyboardName(keyboard: SettingsSubtype): String? =
    keyboard.getExtraValueOf(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_NAME)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { it.isNotBlank() }

/** [keyboard] named [name] (blank: its own name taken off, back to its languages). */
fun withKeyboardName(keyboard: SettingsSubtype, name: String): SettingsSubtype {
    val key = helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_NAME
    return if (name.isBlank()) keyboard.without(key) else keyboard.with(key, java.net.URLEncoder.encode(name.trim(), "UTF-8"))
}

fun keyboardName(keyboard: SettingsSubtype, ctx: Context): String =
    customKeyboardName(keyboard) ?: (listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)).joinToString(" + ") { it.localizedDisplayName(ctx.resources) } +
        (keyboard.getExtraValueOf(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_COPY)?.let { " ($it)" } ?: "")

/** The sections of one keyboard's settings, as menu entries. Used by the keyboard's own screen and inline under the keyboards list. */
@Composable
fun KeyboardSettingsEntries(keyboard: SettingsSubtype, modifier: Modifier = Modifier, showLanguages: Boolean = true,
    showAdvanced: Boolean = true, onEnter: () -> Unit = {},
    // which menus (with separate settings: a keyboard's own ones, or the shared ones once below the keyboards); null: all
    groups: ((helium314.keyboard.latin.settings.KeyboardProfiles.Group) -> Boolean)? = null, showRefine: Boolean = false) {
    fun shows(group: helium314.keyboard.latin.settings.KeyboardProfiles.Group) = groups?.invoke(group) ?: true
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    Column(modifier) {
    // this keyboard's languages, layout, dictionaries, popup order ...
    if (showLanguages) Preference(
        name = stringResource(R.string.languages_title),
        description = keyboardName(keyboard, ctx),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Languages + keyboard.toPref()) },
        icon = R.drawable.ic_settings_languages
    ) { NextScreenIcon() }
    // the keyboard's input settings, popups, number row and hints, layout (on the main screen: the keyboard in use);
    // named Preferences since the Preferences screen's groups moved in
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.LAYOUT)) Preference(
        name = stringResource(R.string.settings_screen_preferences),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Subtype + keyboard.toPref()) },
        icon = R.drawable.ic_settings_preferences
    ) { NextScreenIcon() }
    // (no Preferences: its input and clipboard history groups are on the Layout screen)
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.APPEARANCE)) Preference(
        name = stringResource(R.string.settings_screen_appearance),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Appearance) },
        icon = R.drawable.ic_settings_appearance
    ) { NextScreenIcon() }
    // gesture typing, the swipe extras and the tuning in one screen
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.SWIPE)) Preference(
            name = stringResource(R.string.swipe_screen),
            onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.SwipeTuning + keyboard.toPref()) },
            icon = R.drawable.ic_settings_gesture
        ) { NextScreenIcon() }
    // (the toolbar is a group on Layout & Typing)
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.TEXT_CORRECTION)) Preference(
        name = stringResource(R.string.settings_screen_correction),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.TextCorrection) },
        icon = R.drawable.ic_settings_correction
    ) { NextScreenIcon() }
    // (no Dictionaries: tapping a language in the keyboard's Languages list manages its dictionaries)
    // the settings few need (advanced only): Advanced; app-wide, so with separate settings it's one entry outside the
    // keyboards (AdvancedEntry), not one per keyboard
    // "Refine swipe and learning" when the keyboard keeps its own (advanced only)
    if (showRefine) RefineEntry(onEnter)
    if (showAdvanced) AdvancedEntry(onEnter)
    }
}

/** "Refine swipe and learning" (advanced only): once for all keyboards, or under each keyboard when it keeps its own. */
@Composable
fun RefineEntry(onEnter: () -> Unit = {}) {
    val advanced by SettingsMode.state(LocalContext.current)
    AdvancedTint(advanced) {
        Preference(
            name = stringResource(R.string.learning_swiping_screen),
            onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.LearningSwiping) },
            icon = R.drawable.ic_settings_refine // "tune" (2026-10-06): its own icon, not Swiping's
        ) { NextScreenIcon() }
    }
}

/** The entries the same for every keyboard: "Refine suggestions & learning" (advanced only) and the app's own settings
 *  (App settings, once named Advanced; shown in simple mode too since 2026-10-06). */
@Composable
fun AdvancedEntry(onEnter: () -> Unit = {}, showRefine: Boolean = true) {
    if (showRefine) RefineEntry(onEnter)
    Preference(
        name = stringResource(R.string.settings_screen_advanced),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Advanced) },
        icon = R.drawable.ic_settings_advanced
    ) { NextScreenIcon() }
}

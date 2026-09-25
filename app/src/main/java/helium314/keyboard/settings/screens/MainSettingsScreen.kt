// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

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
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

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
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                KeyboardSettingsEntries(keyboard)
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
fun keyboardName(keyboard: SettingsSubtype, ctx: Context): String =
    (listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)).joinToString(" + ") { it.localizedDisplayName(ctx.resources) }

/** The sections of one keyboard's settings, as menu entries. Used by the keyboard's own screen and inline under the keyboards list. */
@Composable
fun KeyboardSettingsEntries(keyboard: SettingsSubtype, modifier: Modifier = Modifier, showLanguages: Boolean = true, onEnter: () -> Unit = {}) {
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
    Preference(
        name = stringResource(R.string.settings_screen_preferences),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Subtype + keyboard.toPref()) },
        icon = R.drawable.ic_settings_preferences
    ) { NextScreenIcon() }
    // (no Preferences: its input and clipboard history groups are on the Layout screen)
    Preference(
        name = stringResource(R.string.settings_screen_appearance),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Appearance) },
        icon = R.drawable.ic_settings_appearance
    ) { NextScreenIcon() }
    if (advanced) AdvancedTint {
        Preference(
            name = stringResource(R.string.settings_screen_toolbar),
            onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Toolbar) },
            icon = R.drawable.ic_settings_toolbar
        ) { NextScreenIcon() }
    }
    // with the own decoder, gesture typing's items are on the Swiping screen
    if (JniUtils.sHaveGestureLib && !BuildConfig.USE_OWN_GESTURE_DECODER)
        Preference(
            name = stringResource(R.string.settings_screen_gesture),
            onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.GestureTyping) },
            icon = R.drawable.ic_settings_gesture
        ) { NextScreenIcon() }
    // the own decoder: gesture typing, the swipe extras and the tuning in one screen
    if (BuildConfig.USE_OWN_GESTURE_DECODER)
        Preference(
            name = stringResource(R.string.swipe_screen),
            onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.SwipeTuning + keyboard.toPref()) },
            icon = R.drawable.ic_settings_gesture
        ) { NextScreenIcon() }
    // we don't even show the menu if data gathering phase ended more than 2 weeks ago
    if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS)
        Preference(
            name = stringResource(R.string.gesture_data_screen),
            onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.DataGathering) },
            icon = R.drawable.ic_settings_gesture
        ) { NextScreenIcon() }
    Preference(
        name = stringResource(R.string.settings_screen_correction),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.TextCorrection) },
        icon = R.drawable.ic_settings_correction
    ) { NextScreenIcon() }
    // (no Dictionaries: tapping a language in the keyboard's Languages list manages its dictionaries)
    Preference(
        name = stringResource(R.string.settings_screen_advanced),
        onClick = { onEnter(); SettingsDestination.navigateTo(SettingsDestination.Advanced) },
        icon = R.drawable.ic_settings_advanced
    ) { NextScreenIcon() }
    }
}

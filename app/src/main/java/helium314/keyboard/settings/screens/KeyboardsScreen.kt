// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference

/**
 * The top level of the settings: one entry per keyboard, each opening that keyboard's full settings menu
 * ([MainSettingsScreen]), plus adding keyboards and the few things that are not per keyboard.
 */
@Composable
fun KeyboardsScreen(
    onClickAllKeyboards: () -> Unit,
    onClickAbout: () -> Unit,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    var showAddKeyboard by remember { mutableStateOf(false) }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .then(Modifier.padding(innerPadding))
            ) {
                for (subtype in SubtypeSettings.getEnabledSubtypes(true)) {
                    val keyboard = subtype.toSettingsSubtype()
                    // all keyboards share their settings (for now): the entry goes straight to languages & layout
                    Preference(
                        name = keyboardName(keyboard, ctx),
                        description = subtype.mainLayoutName()?.getStringResourceOrName("layout_", ctx) ?: "",
                        onClick = { SettingsDestination.navigateTo(SettingsDestination.Subtype + keyboard.toPref()) },
                        icon = R.drawable.ic_settings_languages
                    ) { NextScreenIcon() }
                }
                Preference(
                    name = stringResource(R.string.add_keyboard),
                    onClick = { showAddKeyboard = true },
                    icon = R.drawable.ic_plus
                ) { NextScreenIcon() }
                // upstream's full list: every built-in keyboard with an on/off switch, incl. disabled ones
                if (advanced) Preference(
                    name = stringResource(R.string.keyboards_title),
                    onClick = onClickAllKeyboards,
                    icon = R.drawable.ic_settings_languages
                ) { NextScreenIcon() }
                // the sections shared by all keyboards
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                KeyboardSettingsEntries(SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype(), showLanguages = false)
                Preference(
                    name = stringResource(R.string.settings_screen_about),
                    onClick = onClickAbout,
                    icon = R.drawable.ic_settings_about
                ) { NextScreenIcon() }
            }
        }
        if (showAddKeyboard)
            ListPickerDialog(
                onDismissRequest = { showAddKeyboard = false },
                onItemSelected = { locale ->
                    val settingsSubtype = SubtypeUtilsAdditional.createDefaultSubtype(locale).toSettingsSubtype()
                    SubtypeUtilsAdditional.changeAdditionalSubtype(settingsSubtype, settingsSubtype, ctx) // registers it unless it equals a built-in one
                    SubtypeSettings.addEnabledSubtype(ctx.prefs(), settingsSubtype.toAdditionalSubtype())
                    SettingsDestination.navigateTo(SettingsDestination.Subtype + settingsSubtype.toPref())
                },
                title = { Text(stringResource(R.string.add_keyboard)) },
                items = SubtypeSettings.getAvailableSubtypeLocales().sortedBy { it.localizedDisplayName(ctx.resources) },
                getItemName = { it.localizedDisplayName(ctx.resources) },
                showRadioButtons = false,
            )
    }
}

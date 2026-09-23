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
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableIntStateOf
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.preferences.Preference

/**
 * The top level of the settings: one entry per keyboard, each opening that keyboard's full settings menu
 * ([MainSettingsScreen]), plus adding keyboards and the few things that are not per keyboard.
 */
@Composable
fun KeyboardsScreen(
    onClickAbout: () -> Unit,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    var showAddKeyboard by remember { mutableStateOf(false) }
    var keyboardToDelete: SettingsSubtype? by remember { mutableStateOf(null) }
    var generation by remember { mutableIntStateOf(0) } // re-read the keyboards after a delete
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
                @Suppress("UNUSED_EXPRESSION") generation
                val enabled = SubtypeSettings.getEnabledSubtypes(true)
                for (subtype in enabled) {
                    val keyboard = subtype.toSettingsSubtype()
                    // tap: languages & layout of the keyboard; press and hold: delete it (while another remains)
                    Preference(
                        name = keyboardName(keyboard, ctx),
                        description = subtype.mainLayoutName()?.getStringResourceOrName("layout_", ctx) ?: "",
                        onClick = { SettingsDestination.navigateTo(SettingsDestination.Languages + keyboard.toPref()) },
                        icon = R.drawable.ic_settings_languages,
                        onLongClick = if (enabled.size > 1) ({ keyboardToDelete = keyboard }) else null,
                    ) { NextScreenIcon() }
                }
                Preference(
                    name = stringResource(R.string.add_keyboard),
                    onClick = { showAddKeyboard = true },
                    icon = R.drawable.ic_plus
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_about),
                    onClick = onClickAbout,
                    icon = R.drawable.ic_settings_about
                ) { NextScreenIcon() }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // off = one set of settings for every keyboard (the sections below); on = each keyboard its own
                // (not available yet: the switch is shown so the screen has its final shape)
                Preference(
                    name = stringResource(R.string.separate_settings_per_keyboard),
                    description = stringResource(R.string.separate_settings_per_keyboard_summary),
                    onClick = {},
                    icon = R.drawable.ic_settings_preferences
                ) { Switch(checked = false, onCheckedChange = null, enabled = false) }
            }
        }
        keyboardToDelete?.let { keyboard ->
            ConfirmationDialog(
                onDismissRequest = { keyboardToDelete = null },
                onConfirmed = {
                    if (keyboard.isAdditionalSubtype(ctx.prefs())) SubtypeUtilsAdditional.removeAdditionalSubtype(ctx, keyboard.toAdditionalSubtype())
                    SubtypeSettings.removeEnabledSubtype(ctx, keyboard.toAdditionalSubtype())
                    keyboardToDelete = null
                    generation++
                },
                title = { Text(stringResource(R.string.delete_keyboard_title)) },
                content = { Text(stringResource(R.string.delete_keyboard_message, keyboardName(keyboard, ctx))) },
                confirmButtonText = stringResource(R.string.delete),
            )
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

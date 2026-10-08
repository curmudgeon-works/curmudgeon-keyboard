// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.gestureTrailColor
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ColorPickerDialog

/**
 * The swipe trail's colour for this keyboard (Appearance, 2026-10-07): a row with the colour in use as a swatch; the
 * picker shows each colour on the preview keyboard as it's picked, Cancel puts back what was set, Default = the
 * theme's colour (the key removed, as with the suggestions' colour: not set means the theme's, so no fixed default).
 */
@Composable
fun TrailColorPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showPicker by remember { mutableStateOf(false) }
    // read again when a setting changes (a theme change shows at once), not on every redraw
    val changed = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    val color = remember(changed?.value) { gestureTrailColor(prefs, KeyboardTheme.getColorsForCurrentTheme(ctx)) }
    // (the activity counts every settings change itself: the swatch follows)
    fun reload() = KeyboardSwitcher.getInstance().setThemeNeedsReload()
    Preference(
        name = setting.title,
        onClick = { showPicker = true },
        value = { Box(Modifier.size(28.dp).background(Color(color), CircleShape)) },
    )
    if (showPicker) {
        val before = remember { prefs.all[Settings.PREF_GESTURE_TRAIL_COLOR] as? Int }
        var confirmed by remember { mutableStateOf(false) }
        ColorPickerDialog(
            onDismissRequest = {
                if (!confirmed) prefs.edit { if (before == null) remove(Settings.PREF_GESTURE_TRAIL_COLOR) else putInt(Settings.PREF_GESTURE_TRAIL_COLOR, before) }
                reload()
                showPicker = false
            },
            initialColor = color,
            title = setting.title,
            showDefault = true,
            onDefault = { confirmed = true; prefs.edit { remove(Settings.PREF_GESTURE_TRAIL_COLOR) }; reload() },
            onConfirmed = { confirmed = true; prefs.edit { putInt(Settings.PREF_GESTURE_TRAIL_COLOR, it) }; reload() },
            onPreview = { prefs.edit { putInt(Settings.PREF_GESTURE_TRAIL_COLOR, it) }; reload() },
        )
    }
}

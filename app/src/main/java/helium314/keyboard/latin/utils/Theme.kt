// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardScopeContext
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsActivity

/**
 * The settings app's colours: the phone's own (the wallpaper's Material You colours on Android 12+, a fixed accent
 * below) — unless the keyboard's colours in use are Black with orange, when the whole app is black and orange too
 * (decided 2026-10-07: that theme carries its look everywhere). Read again when a setting changes, so picking the
 * theme recolours the app at once. [keyboardId]: the keyboard set of the screen on top (null: the main choice, see
 * KeyboardProfiles.mainEditingId), so opening a keyboard's menu shows that keyboard's look.
 */
@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), keyboardId: Int? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val changed = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    val curmudgeon = remember(changed?.value, dark, keyboardId) {
        val prefs = if (keyboardId == null) ctx.prefs() else KeyboardScopeContext(ctx, keyboardId).prefs()
        runCatching { AppLook.usesCurmudgeon(prefs, dark) }.getOrDefault(false) }
    val material3 = Typography()
    val colorScheme = when {
        curmudgeon -> AppLook.curmudgeonScheme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        // todo (later): more colors
        dark -> darkColorScheme(primary = colorResource(R.color.accent))
        else -> lightColorScheme(primary = colorResource(R.color.accent))
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            titleLarge = material3.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = material3.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = material3.titleSmall.copy(fontWeight = FontWeight.Bold)
        ),
        //shapes = Shapes(),
        content = content
    )
}

object AppLook {
    /** Curmudgeon's orange, the one the Black with orange theme uses on the keyboard. */
    const val orange = Defaults.CURMUDGEON_ORANGE

    /** Whether the colours the keyboard shows for the app's current mode ([dark]) are Black with orange: the dark
     *  colours when dark mode follows the phone and the app is dark, else the one colours setting. */
    fun usesCurmudgeon(prefs: SharedPreferences, dark: Boolean): Boolean {
        val dayNight = prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
        val name = if (dayNight && dark) prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
            else prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
        return name == KeyboardTheme.THEME_CURMUDGEON
    }

    private val orangeColor = Color(orange)

    /** Black surfaces, the orange for everything that stands out (switches, headings, buttons, text box lines). */
    val curmudgeonScheme = darkColorScheme(
        primary = orangeColor,
        onPrimary = Color(0xFF2A1600),
        primaryContainer = Color(0xFF6E3F0E),
        onPrimaryContainer = Color(0xFFFFDDB8),
        secondary = orangeColor, // the group headings
        onSecondary = Color(0xFF2A1600),
        secondaryContainer = Color(0xFF4A2E12),
        onSecondaryContainer = Color(0xFFFFDDB8),
        tertiary = Color(0xFFE0C080),
        onTertiary = Color(0xFF3A2A00),
        tertiaryContainer = Color(0xFF55400A),
        onTertiaryContainer = Color(0xFFFFE0B2),
        background = Color.Black,
        onBackground = Color(0xFFECECEC),
        surface = Color.Black,
        onSurface = Color(0xFFECECEC),
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFFBDBDBD),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0C0C0C),
        surfaceContainer = Color(0xFF141414),
        surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF242424),
        outline = Color(0xFF8A8A8A),
        outlineVariant = Color(0xFF3A3A3A),
    )
}

const val previewDark = true

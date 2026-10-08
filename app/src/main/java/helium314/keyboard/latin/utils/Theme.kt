// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * The settings app's colours (2026-10-07): Curmudgeon's orange, light and dark, on Material 3's usual surfaces. It
 * followed the wallpaper (the dynamic scheme) from 0.3.008 to here; the keyboard's own colours are the theme's
 * (Dynamic by default) and are not touched by this.
 */
@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val material3 = Typography()
    MaterialTheme(
        colorScheme = if (dark) darkScheme else lightScheme,
        typography = Typography(
            titleLarge = material3.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = material3.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = material3.titleSmall.copy(fontWeight = FontWeight.Bold)
        ),
        //shapes = Shapes(),
        content = content
    )
}

/** The orange itself (the swipe trail's fallback, Defaults.PREF_SUGGESTION_TEXT_COLOR). */
private val orange = Color(0xFFFF8C00)
private val orangeContainerLight = Color(0xFFFFDDB8)
private val onOrangeContainerLight = Color(0xFF2E1500)

// light: the orange darkened where it's text or a thin line on white (the bright one fails small-text contrast),
// the bright one for fills (switch tracks, buttons: white text on it)
private val lightScheme = lightColorScheme(
    primary = Color(0xFFB45F00),
    onPrimary = Color.White,
    primaryContainer = orangeContainerLight,
    onPrimaryContainer = onOrangeContainerLight,
    secondary = Color(0xFFA05400), // the group headings
    onSecondary = Color.White,
    secondaryContainer = orangeContainerLight,
    onSecondaryContainer = onOrangeContainerLight,
    tertiary = Color(0xFF7A5B2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFF2A1A00),
)

// dark: the orange as it is (it reads well on dark), its containers a deep brown
private val darkScheme = darkColorScheme(
    primary = orange,
    onPrimary = Color(0xFF3F2000),
    primaryContainer = Color(0xFF7A4300),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFFFFB870), // the group headings
    onSecondary = Color(0xFF3F2000),
    secondaryContainer = Color(0xFF5C3A14),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = Color(0xFFE0C080),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF55400A),
    onTertiaryContainer = Color(0xFFFFE0B2),
)

const val previewDark = true

// SPDX-License-Identifier: GPL-3.0-only
@file:JvmName("SuggestionColors")
package helium314.keyboard.latin.utils

import android.content.SharedPreferences
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * The swipe trail's colour: the one picked (Appearance, "Swipe trail colour", per keyboard), else the theme's, else
 * the orange where no theme colours can be had.
 */
fun gestureTrailColor(prefs: SharedPreferences, colors: Colors?): Int =
    if (prefs.contains(Settings.PREF_GESTURE_TRAIL_COLOR))
        prefs.getInt(Settings.PREF_GESTURE_TRAIL_COLOR, Defaults.PREF_GESTURE_TRAIL_COLOR)
    else colors?.get(ColorType.GESTURE_TRAIL) ?: Defaults.PREF_GESTURE_TRAIL_COLOR

/**
 * The suggestion strip's word colour: the one picked (Appearance → Fonts), else the swipe trail's colour (the one
 * picked for this keyboard, else the theme's), so the strip and the trail always match and change with the theme
 * (since 0.3.008; it was a fixed orange), else that orange where no theme colours can be had.
 */
fun suggestionTextColor(prefs: SharedPreferences, colors: Colors?): Int =
    if (prefs.contains(Settings.PREF_SUGGESTION_TEXT_COLOR))
        prefs.getInt(Settings.PREF_SUGGESTION_TEXT_COLOR, Defaults.PREF_SUGGESTION_TEXT_COLOR)
    else gestureTrailColor(prefs, colors)

// SPDX-License-Identifier: GPL-3.0-only
@file:JvmName("SuggestionColors")
package helium314.keyboard.latin.utils

import android.content.SharedPreferences
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * The suggestion strip's word colour: the one picked (Appearance → Fonts), else the theme's swipe trail colour, so
 * the strip and the trail always match and change with the theme (since 0.3.008; it was a fixed orange), else that
 * orange where no theme colours can be had.
 */
fun suggestionTextColor(prefs: SharedPreferences, colors: Colors?): Int =
    if (prefs.contains(Settings.PREF_SUGGESTION_TEXT_COLOR))
        prefs.getInt(Settings.PREF_SUGGESTION_TEXT_COLOR, Defaults.PREF_SUGGESTION_TEXT_COLOR)
    else colors?.get(ColorType.GESTURE_TRAIL) ?: Defaults.PREF_SUGGESTION_TEXT_COLOR

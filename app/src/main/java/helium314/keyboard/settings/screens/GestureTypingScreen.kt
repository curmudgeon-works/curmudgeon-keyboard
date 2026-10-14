// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.latin.utils.previewDark

fun createGestureTypingSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_GESTURE_INPUT, R.string.gesture_input) {
        SwitchPreference(it, Defaults.PREF_GESTURE_INPUT)
    },
    Setting(context, Settings.PREF_GESTURE_CAPS_SWIPE, R.string.gesture_caps_swipe) {
        SwitchPreference(it, Defaults.PREF_GESTURE_CAPS_SWIPE)
    },
    Setting(context, Settings.PREF_GESTURE_APOSTROPHE_VIA_PERIOD, R.string.gesture_apostrophe_via_period) {
        SwitchPreference(it, Defaults.PREF_GESTURE_APOSTROPHE_VIA_PERIOD)
    },
    Setting(context, Settings.PREF_GESTURE_CAPS_HEIGHT, R.string.gesture_caps_height, R.string.gesture_caps_height_summary) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_CAPS_HEIGHT,
            range = 25f..250f,
            stepSize = 25,
            description = { stringResource(R.string.gesture_caps_height_value, (it / 100f).toString()) }
        )
    },
    Setting(context, Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN, R.string.gesture_fast_typing_cooldown) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_FAST_TYPING_COOLDOWN,
            range = 0f..500f,
            description = {
                if (it <= 0) stringResource(R.string.gesture_fast_typing_cooldown_instant)
                else stringResource(R.string.abbreviation_unit_milliseconds, it.toString())
            },
            // the value at the right, what to do with it underneath
            valueOnRight = true,
            summary = stringResource(R.string.gesture_fast_typing_cooldown_summary),
        )
    },
    Setting(context, Settings.PREF_GESTURE_TRAIL_THICKNESS, R.string.gesture_trail_thickness) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_TRAIL_THICKNESS,
            range = 0f..300f,
            stepSize = 10,
            description = { if (it <= 0) stringResource(R.string.gesture_trail_off) else "$it%" },
            live = true, applyOnRelease = true, // try it on the preview keyboard with the dialog open
        )
    },
    Setting(context, Settings.PREF_GESTURE_TRAIL_WHOLE, R.string.gesture_trail_whole) {
        SwitchPreference(it, Defaults.PREF_GESTURE_TRAIL_WHOLE)
    },
    Setting(context, Settings.PREF_GESTURE_TRAIL_WHOLE_LINGER, R.string.gesture_trail_whole_linger) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_TRAIL_WHOLE_LINGER,
            range = 0f..3000f,
            stepSize = 100,
            description = { if (it < 0) stringResource(R.string.gesture_trail_until_touch)
                else stringResource(R.string.abbreviation_unit_milliseconds, it.toString()) },
            live = true, applyOnRelease = true, // try it on the preview keyboard with the dialog open
            offLabel = stringResource(R.string.gesture_trail_never_vanish), offValue = -1, // -1: only the next touch
        )
    },
    Setting(context, Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, R.string.gesture_trail_fadeout_duration) { def ->
        SliderPreference(
            name = def.title,
            key = def.key,
            default = Defaults.PREF_GESTURE_TRAIL_FADEOUT_DURATION,
            range = 100f..1900f,
            description = { stringResource(R.string.abbreviation_unit_milliseconds, (it + 100).toString()) },
            stepSize = 10,
            live = true, applyOnRelease = true,
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
)

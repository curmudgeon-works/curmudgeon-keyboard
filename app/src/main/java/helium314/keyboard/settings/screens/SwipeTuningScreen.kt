// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.settings.AdvancedTint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.R
import helium314.keyboard.latin.gesture.GestureStats
import helium314.keyboard.latin.gesture.OwnGestureDecoder
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SliderPreference
import androidx.core.content.edit
import kotlin.math.roundToInt

@Composable
private fun GroupTitle(titleId: Int) =
    Text(stringResource(titleId), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))

/**
 * The swipe settings (capitalizing by swiping up, the apostrophe via the period key), then the own swipe decoder's inflection weights and scorer blend for one keyboard, with the statistics of every
 * tuning tried so far, so the user can see which one suits their swiping and switch to it.
 */
@Composable
fun SwipeTuningScreen(keyboard: SettingsSubtype, onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    var statsGeneration by remember { mutableIntStateOf(0) }
    val rows = remember(statsGeneration, b?.value) { GestureStats.read(ctx.realPrefs()) }
    val current = OwnGestureDecoder.Tuning.read(prefs)
    val recommended = GestureStats.recommended(rows)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.swipe_screen),
        settings = emptyList(),
    ) {
        // the content is taller than a screen now that the gesture typing items are here
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            // ---- what a swipe can do: gesture typing itself (its own screen without the own decoder), then the extras
            GroupTitle(R.string.swipe_settings)
            val advanced by SettingsMode.state(ctx)
            SettingsMode.filter(gestureTypingItems(prefs), gestureTypingSimpleModeKeys, advanced).forEach {
                if (it !is String) return@forEach
                if (it in gestureTypingSimpleModeKeys) SettingsActivity.settingsContainer[it]?.Preference()
                else AdvancedTint { SettingsActivity.settingsContainer[it]?.Preference() }
            }
            SettingsActivity.settingsContainer[Settings.PREF_GESTURE_CAPS_SWIPE]?.Preference()
            if (prefs.getBoolean(Settings.PREF_GESTURE_CAPS_SWIPE, Defaults.PREF_GESTURE_CAPS_SWIPE))
                SettingsActivity.settingsContainer[Settings.PREF_GESTURE_CAPS_HEIGHT]?.Preference()
            SettingsActivity.settingsContainer[Settings.PREF_GESTURE_APOSTROPHE_VIA_PERIOD]?.Preference()
            // a backspace tap right after a swipe takes the whole swiped word (moved from Layout & Typing; advanced)
            if (advanced) AdvancedTint { SettingsActivity.settingsContainer[Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD]?.Preference() }
            // on = move cursor, off = nothing; the other spacebar swipe actions stay in Advanced (shown off here)
            val moveCursor = Settings.readHorizontalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.MOVE_CURSOR
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.space_swipe_move_cursor), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.space_swipe_move_cursor_summary), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = moveCursor, onCheckedChange = { on ->
                    prefs.edit { putString(Settings.PREF_SPACE_HORIZONTAL_SWIPE,
                        (if (on) KeyboardActionListener.SwipeAction.MOVE_CURSOR else KeyboardActionListener.SwipeAction.NONE).name) }
                })
            }
            // advanced: every space bar swipe (moved from Advanced), with the distance / sensitivity their actions use
            if (advanced) AdvancedTint {
                listOfNotNull(Settings.PREF_SPACE_HORIZONTAL_SWIPE, Settings.PREF_SPACE_VERTICAL_SWIPE,
                    if (Settings.readHorizontalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE
                        || Settings.readVerticalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE)
                        Settings.PREF_LANGUAGE_SWIPE_DISTANCE else null,
                    if (Settings.readVerticalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.TOUCHPAD_MODE)
                        Settings.PREF_TOUCHPAD_SENSITIVITY else null,
                ).forEach { SettingsActivity.settingsContainer[it]?.Preference() }
            }

            // ---- how the decoder weighs a swipe, and how each weighting did
            GroupTitle(R.string.swipe_tuning)
            PreferenceCategory(stringResource(R.string.swipe_tuning_inflections))
            Text(stringResource(R.string.swipe_tuning_summary), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall)
            WeightSlider(Settings.PREF_GESTURE_TURN_WEIGHT, Defaults.PREF_GESTURE_TURN_WEIGHT, R.string.swipe_tuning_turns, 0f..1.5f)
            WeightSlider(Settings.PREF_GESTURE_PAUSE_WEIGHT, Defaults.PREF_GESTURE_PAUSE_WEIGHT, R.string.swipe_tuning_pauses, 0f..1f)
            WeightSlider(Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Defaults.PREF_GESTURE_SLOWDOWN_WEIGHT, R.string.swipe_tuning_slowdowns, 0f..1f)
            WeightSlider(Settings.PREF_GESTURE_KUSHLER_WEIGHT, Defaults.PREF_GESTURE_KUSHLER_WEIGHT, R.string.swipe_tuning_blend, 0f..1f,
                R.string.swipe_tuning_blend_summary)
            BoostSlider()

            PreferenceCategory(stringResource(R.string.swipe_tuning_stats))
            if (rows.isEmpty())
                Text(stringResource(R.string.swipe_tuning_no_stats), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            // the current tuning first, then the rest by how well they did
            val ordered = rows.entries.sortedWith(compareBy({ it.key != current.key }, { -it.value.score }))
            for ((key, row) in ordered) {
                val isCurrent = key == current.key
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(key, Modifier.weight(1f), fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        if (isCurrent)
                            Text(stringResource(R.string.swipe_tuning_current), style = MaterialTheme.typography.labelMedium)
                        if (key == recommended) {
                            Text(stringResource(R.string.swipe_tuning_recommended), Modifier.padding(start = 8.dp),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            if (!isCurrent) TextButton(onClick = {
                                OwnGestureDecoder.Tuning.parse(key)?.let { OwnGestureDecoder.Tuning.write(prefs, it) }
                                (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                            }) { Text(stringResource(R.string.swipe_tuning_use)) }
                        }
                    }
                    fun pct(n: Int) = if (row.swipes == 0) 0 else (100f * n / row.swipes).roundToInt()
                    Text(stringResource(R.string.swipe_tuning_row, row.swipes, pct(row.kept), pct(row.pickedSecond),
                        pct(row.pickedThird + row.pickedLater), pct(row.deleted)), style = MaterialTheme.typography.bodySmall)
                    if (isCurrent) TextButton(onClick = { GestureStats.clear(ctx.realPrefs(), key); statsGeneration++ }) {
                        Text(stringResource(R.string.swipe_tuning_reset))
                    }
                }
            }
        }
    }
}

/** The learned-word boost: whole numbers, shown as the summary explains. */
@Composable
private fun BoostSlider() {
    SliderPreference(
        name = stringResource(R.string.swipe_tuning_history_boost),
        key = Settings.PREF_GESTURE_HISTORY_BOOST,
        description = { value: Int -> "$value  ·  " + stringResource(R.string.swipe_tuning_history_boost_summary) },
        default = Defaults.PREF_GESTURE_HISTORY_BOOST,
        range = 0f..128f,
        stepSize = 8,
    )
}

/** A weight with one decimal; the stored value is rounded so the statistics key stays readable. */
@Composable
private fun WeightSlider(key: String, default: Float, title: Int, range: ClosedFloatingPointRange<Float>, summary: Int? = null) {
    val prefs = LocalContext.current.prefs()
    SliderPreference(
        name = stringResource(title),
        key = key,
        description = { value: Float -> String.format(java.util.Locale.ROOT, "%.1f", value) + (summary?.let { "  ·  " + stringResource(it) } ?: "") },
        default = default,
        range = range,
        onConfirmed = { value: Float -> prefs.edit { putFloat(key, (value * 10).roundToInt() / 10f) } },
    )
}

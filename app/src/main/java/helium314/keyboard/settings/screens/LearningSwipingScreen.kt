// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.gesture.GestureStats
import helium314.keyboard.latin.gesture.OwnGestureDecoder
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import kotlin.math.roundToInt

/** The decoder weights of "Swipe tuning". */
internal val swipeTuningKeys = listOf(
    Settings.PREF_GESTURE_TURN_WEIGHT, Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Settings.PREF_GESTURE_KUSHLER_WEIGHT,
    Settings.PREF_GESTURE_HISTORY_BOOST, Settings.PREF_GESTURE_FAST_COMMON_WORDS, Settings.PREF_GESTURE_CORNER_MISS,
    Settings.PREF_GESTURE_FAST_SPEED,
    Settings.PREF_SUGGESTION_COUNT, Settings.PREF_SUGGESTION_RULES,
)

/**
 * "Refine swipe and learning" (2026-10-04): the swipe decoder's tuning with the statistics of every tuning
 * tried and the swipe logs (moved from the Swiping screen), and how the keyboard learns (trusting your words, moved
 * from Text correction; the corrections log). The same for every keyboard (KeyboardProfiles' global keys). More
 * learning rules and swipe traits are to come here.
 */
@Composable
fun LearningSwipingScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    // every change applies at once; the top bar's tick keeps the changes since the screen opened, the cross undoes them
    val draft = helium314.keyboard.settings.rememberPrefsDraft("learnswipe",
        swipeTuningKeys + listOf(Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT), onClickBack)
    var statsGeneration by remember { mutableIntStateOf(0) }
    var askReset by remember { mutableStateOf<String?>(null) } // the tuning whose results the reset question is about
    val rows = remember(statsGeneration, b?.value) { GestureStats.read(ctx.realPrefs()) }
    val current = OwnGestureDecoder.Tuning.read(prefs)
    val recommended = GestureStats.recommended(rows)
    val gestureOn = prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
    // a try-it bar to swipe in while tuning (the keyboard in use), and the dialogs keep the keyboard up
    val keyboard = remember { SubtypeSettings.getSelectedSubtype(prefs).toSettingsSubtype() }
    val tryIt = remember { TryItState() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listScroll = rememberScrollState()
    val view = androidx.compose.ui.platform.LocalView.current
    val tapReveal = remember { helium314.keyboard.settings.ListTapReveal(listScroll, scope, ctx, view) }
    val preview = remember { PreviewKeyboard(tryIt, scope, showIme = { softKeyboard?.show() }, reveal = tapReveal::reveal) {
        focusManager.clearFocus(force = true); softKeyboard?.hide() } }
    var bottomBarTop by remember { mutableIntStateOf(-1) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = Int.MAX_VALUE } }
    androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.dialogs.LocalKeepKeyboard provides true,
        helium314.keyboard.settings.dialogs.LocalPreviewKeyboard provides preview,
        helium314.keyboard.settings.dialogs.LocalBottomBarTop provides bottomBarTop) {
    SearchSettingsScreen(
        onClickBack = draft.leave,
        title = stringResource(R.string.learning_swiping_screen),
        topActions = draft.topActions,
        settings = emptyList(),
    ) {
        androidx.compose.material3.Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = {
            androidx.compose.foundation.layout.Box(Modifier.onGloballyPositioned {
                bottomBarTop = it.positionInWindow().y.toInt()
                tapReveal.onBarPlaced(bottomBarTop)
                (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = bottomBarTop
            }) { TryItBar(keyboard, tryIt, onFocus = preview::onFocus, onUsed = preview::onUsed) }
        }) { innerPadding ->
        Column(Modifier.padding(innerPadding).then(tapReveal.list).verticalScroll(listScroll)) {
          androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalRowStart provides 22.dp) {
            // ---- customize suggestions (moved from Text correction, 2026-10-06): how many, and a rule per position
            GroupTitle(R.string.customize_suggestions)
            androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalPendingChange provides
                    (Settings.PREF_SUGGESTION_RULES in draft.pending || Settings.PREF_SUGGESTION_COUNT in draft.pending)) {
                SettingsActivity.settingsContainer[Settings.PREF_SUGGESTION_RULES]?.Preference()
            }
            // ---- swiping: how the decoder weighs a swipe (only while swiping is on), and how each weighting did
            helium314.keyboard.settings.AdvancedReveal(gestureOn) { Column {
                GroupTitle(R.string.swipe_tuning)
                // one snappy line on what the sliders are (the longer R.string.swipe_tuning_summary is kept but not
                // shown: each slider says what it does and its scale)
                Text(stringResource(R.string.swipe_tuning_subtext), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, end = 16.dp, bottom = 4.dp))
                // every slider shows 0 to 1 over its own range (2026-10-06); the stored values, defaults and the statistics
                // keys stay in the decoder's units. Turns and slowdowns need no subtext: their names say it
                WeightSlider(draft.pending, Settings.PREF_GESTURE_TURN_WEIGHT, Defaults.PREF_GESTURE_TURN_WEIGHT, R.string.swipe_tuning_turns, 0f..1.5f)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Defaults.PREF_GESTURE_SLOWDOWN_WEIGHT, R.string.swipe_tuning_slowdowns, 0f..1f)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_KUSHLER_WEIGHT, Defaults.PREF_GESTURE_KUSHLER_WEIGHT, R.string.swipe_tuning_blend, 0f..1f,
                    R.string.swipe_tuning_blend_summary)
                BoostSlider(draft.pending)
                // what counts as a fast swipe: the two sliders after it start at this speed (2026-10-06)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_FAST_SPEED, Defaults.PREF_GESTURE_FAST_SPEED, R.string.swipe_tuning_fast_speed,
                    0f..OwnGestureDecoder.Tuning.RANGES[4], R.string.swipe_tuning_fast_speed_summary)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_FAST_COMMON_WORDS, Defaults.PREF_GESTURE_FAST_COMMON_WORDS, R.string.swipe_tuning_fast_common,
                    0f..0.2f, R.string.swipe_tuning_fast_common_summary, decimals = 2)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_CORNER_MISS, Defaults.PREF_GESTURE_CORNER_MISS, R.string.swipe_tuning_corner_miss,
                    0f..0.2f, R.string.swipe_tuning_corner_miss_summary, decimals = 2)
            } }

            PreferenceCategory(stringResource(R.string.swipe_tuning_stats))
            if (rows.isEmpty())
                Text(stringResource(R.string.swipe_tuning_no_stats), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            // the current tuning first, then the rest by how well they did
            val ordered = rows.entries.sortedWith(compareBy({ it.key != current.key }, { -it.value.score }))
            for ((key, row) in ordered) {
                val isCurrent = key == current.key
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shownKey(key), Modifier.weight(1f), fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        if (isCurrent)
                            Text(stringResource(R.string.swipe_tuning_current), style = MaterialTheme.typography.labelMedium)
                        if (key == recommended) {
                            Text(stringResource(R.string.swipe_tuning_recommended), Modifier.padding(start = 8.dp),
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            // (choosing a tuning means nothing while swiping is off: the results are history then)
                            if (!isCurrent) TextButton(enabled = gestureOn, onClick = {
                                OwnGestureDecoder.Tuning.parse(key)?.let { OwnGestureDecoder.Tuning.write(prefs, it) }
                                (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                            }) { Text(stringResource(R.string.swipe_tuning_use)) }
                        }
                    }
                    fun pct(n: Int) = if (row.swipes == 0) 0 else (100f * n / row.swipes).roundToInt()
                    Text(stringResource(R.string.swipe_tuning_row, row.swipes, pct(row.kept), pct(row.pickedSecond),
                        pct(row.pickedThird + row.pickedLater), pct(row.deleted)), style = MaterialTheme.typography.bodySmall)
                    if (row.timed > 0)
                        Text(stringResource(R.string.swipe_tuning_time, row.averageMs, row.slowestMs), style = MaterialTheme.typography.bodySmall)
                    if (isCurrent) TextButton(onClick = { askReset = key }) {
                        Text(stringResource(R.string.swipe_tuning_reset))
                    }
                }
            }
            // results put aside with "save and start afresh", oldest first, with the days they cover
            for (saved in remember(statsGeneration) { GestureStats.readSaved(ctx.realPrefs()) }) {
                val row = saved.row
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shownKey(saved.tuningKey), Modifier.weight(1f))
                        Text(statsDates(row), style = MaterialTheme.typography.labelMedium)
                    }
                    fun pct(n: Int) = if (row.swipes == 0) 0 else (100f * n / row.swipes).roundToInt()
                    Text(stringResource(R.string.swipe_tuning_row, row.swipes, pct(row.kept), pct(row.pickedSecond),
                        pct(row.pickedThird + row.pickedLater), pct(row.deleted)), style = MaterialTheme.typography.bodySmall)
                    if (row.timed > 0)
                        Text(stringResource(R.string.swipe_tuning_time, row.averageMs, row.slowestMs), style = MaterialTheme.typography.bodySmall)
                }
            }
            // ---- the swipe logs (diagnostics): only while swiping is on
            helium314.keyboard.settings.AdvancedReveal(gestureOn) { Column {
                GroupTitle(R.string.swipe_logging)
                SettingsActivity.settingsContainer[Settings.PREF_RECORD_GESTURE_CORPUS]?.Preference()
                SettingsActivity.settingsContainer[Settings.PREF_SWIPE_METRICS]?.Preference()
            } }
            // ---- learning: when your own words win over corrections (moved from Text correction), and the log of what
            // corrections do to the learned words
            GroupTitle(R.string.learning_group)
            androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalPendingChange provides
                (Settings.PREF_AUTOCORRECT_FREQUENT_WORDS in draft.pending || Settings.PREF_TRUST_TYPED_COUNT in draft.pending)) {
                SettingsActivity.settingsContainer[Settings.PREF_AUTOCORRECT_FREQUENT_WORDS]?.Preference()
            }
            SettingsActivity.settingsContainer[Settings.PREF_LEARNING_LOG]?.Preference()
          }
        }
        }
    }
    }
    draft.dialogs()
    // resetting the current tuning's results: gone for good, or kept as a dated row at the end; either way it counts
    // from zero again
    askReset?.let { key ->
        helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog(
            onDismissRequest = { askReset = null },
            title = { Text(stringResource(R.string.swipe_tuning_reset_title)) },
            content = { Text(stringResource(R.string.swipe_tuning_reset_message)) },
            neutralButtonText = stringResource(R.string.swipe_tuning_reset_delete),
            onNeutral = { GestureStats.clear(ctx.realPrefs(), key); statsGeneration++; askReset = null },
            confirmButtonText = stringResource(R.string.swipe_tuning_reset_save),
            onConfirmed = { GestureStats.saveAndClear(ctx.realPrefs(), key); statsGeneration++ },
            keepKeyboard = false,
        )
    }
}


/** The days a row of results covers, e.g. "Sep 28 – Oct 2" ("… – Oct 2" for results counted before dates were kept). */
@Composable
private fun statsDates(row: GestureStats.Row): String {
    val format = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
    val to = if (row.last > 0) format.format(java.util.Date(row.last)) else "…"
    val from = if (row.first > 0) format.format(java.util.Date(row.first)) else "…"
    return if (from == to) from else "$from – $to"
}

/** A statistics row's tuning as the sliders show it (0 to 1 each); a key that isn't a tuning as it is. */
@Composable
private fun shownKey(key: String): String {
    val v = OwnGestureDecoder.Tuning.parse(key)?.shown ?: return key
    val f = v.map { String.format(java.util.Locale.ROOT, "%.2f", it) }
    return stringResource(R.string.swipe_tuning_key_shown, f[0], f[1], f[2], f[3], f[4], f[5], f[6])
}

/** The learned-word boost: whole numbers stored, shown 0 to 1. */
@Composable
private fun BoostSlider(pending: Set<String>) = androidx.compose.runtime.CompositionLocalProvider(
    helium314.keyboard.settings.preferences.LocalPendingChange provides (Settings.PREF_GESTURE_HISTORY_BOOST in pending)) {
    SliderPreference(
        name = stringResource(R.string.swipe_tuning_history_boost),
        key = Settings.PREF_GESTURE_HISTORY_BOOST,
        // shown 0 to 1 over 0..128 (added to a word weight of 0..255); stored as the number added
        description = { value: Int -> String.format(java.util.Locale.ROOT, "%.2f", value / 128f) },
        default = Defaults.PREF_GESTURE_HISTORY_BOOST,
        range = 0f..128f,
        stepSize = 8,
        valueOnRight = true,
        summary = stringResource(R.string.swipe_tuning_history_boost_summary),
    )
}

/** A weight with one decimal (or [decimals]); the stored value is rounded so the statistics key stays readable. Shown
 *  0 to 1 over [range] (the value divided by the range's top). */
@Composable
private fun WeightSlider(pending: Set<String>, key: String, default: Float, title: Int, range: ClosedFloatingPointRange<Float>,
                         summary: Int? = null, decimals: Int = 1) = androidx.compose.runtime.CompositionLocalProvider(
    helium314.keyboard.settings.preferences.LocalPendingChange provides (key in pending)) {
    val prefs = LocalContext.current.prefs()
    val scale = if (decimals == 2) 100f else 10f
    SliderPreference(
        name = stringResource(title),
        key = key,
        description = { value: Float -> String.format(java.util.Locale.ROOT, "%.2f", value / range.endInclusive) },
        default = default,
        range = range,
        onConfirmed = { value: Float -> prefs.edit { putFloat(key, (value * scale).roundToInt() / scale) } },
        valueOnRight = true,
        summary = summary?.let { stringResource(it) },
    )
}

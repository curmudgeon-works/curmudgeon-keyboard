// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.runtime.mutableStateOf
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

/** The "Swipe settings" group's preferences (not the decoder weights of "Swipe tuning"). */
private val swipeSettingKeys = listOf(
    Settings.PREF_GESTURE_INPUT, Settings.PREF_GESTURE_PREVIEW_TRAIL, Settings.PREF_GESTURE_TRAIL_THICKNESS,
    Settings.PREF_GESTURE_TRAIL_WHOLE, Settings.PREF_GESTURE_TRAIL_WHOLE_LINGER, Settings.PREF_DELETE_SWIPE, Settings.PREF_DELETE_SWIPE_SPEED, Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN,
    Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, Settings.PREF_GESTURE_SPACE_AWARE, Settings.PREF_GESTURE_CAPS_SWIPE,
    Settings.PREF_GESTURE_CAPS_HEIGHT, Settings.PREF_GESTURE_APOSTROPHE_VIA_PERIOD, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE,
    Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD, Settings.PREF_SPACE_HORIZONTAL_SWIPE, Settings.PREF_SPACE_VERTICAL_SWIPE,
    Settings.PREF_LANGUAGE_SWIPE_DISTANCE, Settings.PREF_TOUCHPAD_SENSITIVITY,
)

/** The "Swipe tuning" group's decoder weights. */
private val swipeTuningKeys = listOf(
    Settings.PREF_GESTURE_TURN_WEIGHT, Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Settings.PREF_GESTURE_KUSHLER_WEIGHT,
    Settings.PREF_GESTURE_HISTORY_BOOST, Settings.PREF_GESTURE_FAST_COMMON_WORDS, Settings.PREF_GESTURE_CORNER_MISS,
)

@Composable
private fun GroupTitle(titleId: Int) = Column {
    // the same heading as everywhere: a line above, flush at the edge, the rows indented under it
    androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(stringResource(titleId), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp))
}

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
    // every change applies at once; the top bar's tick keeps the changes since the screen opened, the cross undoes them
    val draft = helium314.keyboard.settings.rememberPrefsDraft("swipe", swipeSettingKeys + swipeTuningKeys, onClickBack)
    var statsGeneration by remember { mutableIntStateOf(0) }
    var askReset by remember { mutableStateOf<String?>(null) } // the tuning whose results the reset question is about
    val rows = remember(statsGeneration, b?.value) { GestureStats.read(ctx.realPrefs()) }
    val current = OwnGestureDecoder.Tuning.read(prefs)
    val recommended = GestureStats.recommended(rows)
    // a try-it bar to swipe in, and the dialogs keep the keyboard up (like Appearance and Layout & Typing)
    val tryIt = remember { TryItState() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the row tapped last moves above where the preview keyboard will end (as on Layout & Typing and Appearance)
    val listScroll = rememberScrollState()
    val view = androidx.compose.ui.platform.LocalView.current
    val tapReveal = remember { helium314.keyboard.settings.ListTapReveal(listScroll, scope, ctx, view) }
    val preview = remember { PreviewKeyboard(tryIt, scope, showIme = { softKeyboard?.show() }, reveal = tapReveal::reveal) {
        focusManager.clearFocus(force = true); softKeyboard?.hide() } }
    var bottomBarTop by remember { mutableIntStateOf(-1) }
    // a swipe setting changed (a switch; the dialogs bring the keyboard up themselves) brings it up for a moment to
    // swipe on, as on Layout & Typing; the decoder weights below don't
    fun swipeShape() = swipeSettingKeys.map { prefs.all[it] }
    var lastSwipeShape by remember { mutableStateOf(swipeShape()) }
    androidx.compose.runtime.LaunchedEffect(b?.value) {
        val now = swipeShape()
        if (now != lastSwipeShape) { lastSwipeShape = now; preview.changed(emoji = false) }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = Int.MAX_VALUE } }
    androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.dialogs.LocalKeepKeyboard provides true,
        helium314.keyboard.settings.dialogs.LocalPreviewKeyboard provides preview,
        helium314.keyboard.settings.dialogs.LocalBottomBarTop provides bottomBarTop) {
    SearchSettingsScreen(
        onClickBack = draft.leave,
        title = stringResource(R.string.swipe_screen),
        topActions = draft.topActions,
        settings = emptyList(),
    ) {
        // (a screen with its own content draws its own try-it bar: SearchSettingsScreen's bottomBar is for its list)
        androidx.compose.material3.Scaffold(contentWindowInsets = WindowInsets(0), bottomBar = {
            androidx.compose.foundation.layout.Box(Modifier.onGloballyPositioned {
                bottomBarTop = it.positionInWindow().y.toInt()
                tapReveal.onBarPlaced(bottomBarTop)
                (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = bottomBarTop
            }) { TryItBar(keyboard, tryIt, onFocus = preview::onFocus, onUsed = preview::onUsed) }
        }) { innerPadding ->
        // the content is taller than a screen now that the gesture typing items are here
        Column(Modifier.padding(innerPadding).then(tapReveal.list).verticalScroll(listScroll)) {
          androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalRowStart provides 22.dp) {
            val advanced by SettingsMode.state(ctx)
            val gestureOn = prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
            val capsOn = prefs.getBoolean(Settings.PREF_GESTURE_CAPS_SWIPE, Defaults.PREF_GESTURE_CAPS_SWIPE)
            // a row in italics while it has a change not yet kept with the tick
            @Composable fun Pref(key: String) = androidx.compose.runtime.CompositionLocalProvider(
                helium314.keyboard.settings.preferences.LocalPendingChange provides (key in draft.pending)) {
                SettingsActivity.settingsContainer[key]?.Preference()
            }
            // ---- the main switch: off folds away everything about swiped words (here and the tuning); the other
            // swipes (spacebar, backspace, suggestion strip) work when tapping too and stay, and the results stay as history
            val wholeTrail = prefs.getBoolean(Settings.PREF_GESTURE_TRAIL_WHOLE, Defaults.PREF_GESTURE_TRAIL_WHOLE)
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 8.dp)) { Pref(Settings.PREF_GESTURE_INPUT) }
            GroupTitle(R.string.swipe_settings)
            helium314.keyboard.settings.AdvancedReveal(gestureOn) { Column {
                Pref(Settings.PREF_GESTURE_TRAIL_THICKNESS)
                Pref(Settings.PREF_GESTURE_TRAIL_WHOLE)
                // under the switch, indented: off, how long each point of the trail stays (it fades on the way); on,
                // how long the whole trail stays after the lift
                androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalRowStart provides 38.dp) {
                    helium314.keyboard.settings.AdvancedReveal(!wholeTrail) { Pref(Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION) }
                    helium314.keyboard.settings.AdvancedReveal(wholeTrail) { Pref(Settings.PREF_GESTURE_TRAIL_WHOLE_LINGER) }
                }
                // a backspace tap right after a swipe takes the whole swiped word (moved from Layout & Typing)
                Pref(Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD)
                // the same setting as on Text correction (one preference, so both always agree)
                Pref(Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING)
            } }
            // from backspace: the same setting as on Layout & Typing (one preference, so both always agree)
            Pref(Settings.PREF_DELETE_SWIPE)
            // on = move cursor, off = nothing; the other spacebar swipe actions are below (advanced)
            val moveCursor = Settings.readHorizontalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.MOVE_CURSOR
            val moveCursorPending = Settings.PREF_SPACE_HORIZONTAL_SWIPE in draft.pending
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.space_swipe_move_cursor), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.let {
                    if (moveCursorPending) it.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else it })
                Switch(checked = moveCursor, onCheckedChange = { on ->
                    prefs.edit { putString(Settings.PREF_SPACE_HORIZONTAL_SWIPE,
                        (if (on) KeyboardActionListener.SwipeAction.MOVE_CURSOR else KeyboardActionListener.SwipeAction.NONE).name) }
                })
            }
            // advanced, last in the group: the swiped-word details (only while swiping is on; the capitalizing height
            // under its switch, indented), then the swipes that work when tapping too: down on the suggestion strip
            // (moved from the toolbar settings), and every space bar swipe (moved from Advanced) with the distance /
            // sensitivity their actions use
            AdvancedTint(advanced) {
                helium314.keyboard.settings.AdvancedReveal(gestureOn) { Column {
                    Pref(Settings.PREF_GESTURE_APOSTROPHE_VIA_PERIOD)
                    Pref(Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN)
                    Pref(Settings.PREF_GESTURE_CAPS_SWIPE)
                    helium314.keyboard.settings.AdvancedReveal(capsOn) {
                        androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalRowStart provides 38.dp) {
                            Pref(Settings.PREF_GESTURE_CAPS_HEIGHT)
                        }
                    }
                } }
                Pref(Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE)
                listOfNotNull(Settings.PREF_SPACE_HORIZONTAL_SWIPE, Settings.PREF_SPACE_VERTICAL_SWIPE,
                    if (Settings.readHorizontalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE
                        || Settings.readVerticalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE)
                        Settings.PREF_LANGUAGE_SWIPE_DISTANCE else null,
                    if (Settings.readVerticalSpaceSwipe(prefs) == KeyboardActionListener.SwipeAction.TOUCHPAD_MODE)
                        Settings.PREF_TOUCHPAD_SENSITIVITY else null,
                ).forEach { Pref(it) }
            }

            // ---- how the decoder weighs a swipe (only while swiping is on), and how each weighting did
            helium314.keyboard.settings.AdvancedReveal(gestureOn) { Column {
                GroupTitle(R.string.swipe_tuning)
                // (the group's explanation, R.string.swipe_tuning_summary, is kept but not shown: each slider says what it
                // does and its scale instead)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_TURN_WEIGHT, Defaults.PREF_GESTURE_TURN_WEIGHT, R.string.swipe_tuning_turns, 0f..1.5f,
                    R.string.swipe_tuning_turns_summary)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Defaults.PREF_GESTURE_SLOWDOWN_WEIGHT, R.string.swipe_tuning_slowdowns, 0f..1f,
                    R.string.swipe_tuning_slowdowns_summary)
                WeightSlider(draft.pending, Settings.PREF_GESTURE_KUSHLER_WEIGHT, Defaults.PREF_GESTURE_KUSHLER_WEIGHT, R.string.swipe_tuning_blend, 0f..1f,
                    R.string.swipe_tuning_blend_summary)
                BoostSlider(draft.pending)
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
                        Text(key, Modifier.weight(1f), fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
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
                        Text(saved.tuningKey, Modifier.weight(1f))
                        Text(statsDates(row), style = MaterialTheme.typography.labelMedium)
                    }
                    fun pct(n: Int) = if (row.swipes == 0) 0 else (100f * n / row.swipes).roundToInt()
                    Text(stringResource(R.string.swipe_tuning_row, row.swipes, pct(row.kept), pct(row.pickedSecond),
                        pct(row.pickedThird + row.pickedLater), pct(row.deleted)), style = MaterialTheme.typography.bodySmall)
                    if (row.timed > 0)
                        Text(stringResource(R.string.swipe_tuning_time, row.averageMs, row.slowestMs), style = MaterialTheme.typography.bodySmall)
                }
            }
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

/** The learned-word boost: whole numbers, shown as the summary explains. */
@Composable
private fun BoostSlider(pending: Set<String>) = androidx.compose.runtime.CompositionLocalProvider(
    helium314.keyboard.settings.preferences.LocalPendingChange provides (Settings.PREF_GESTURE_HISTORY_BOOST in pending)) {
    SliderPreference(
        name = stringResource(R.string.swipe_tuning_history_boost),
        key = Settings.PREF_GESTURE_HISTORY_BOOST,
        description = { value: Int -> "$value" },
        default = Defaults.PREF_GESTURE_HISTORY_BOOST,
        range = 0f..128f,
        stepSize = 8,
        valueOnRight = true,
        summary = stringResource(R.string.swipe_tuning_history_boost_summary),
    )
}

/** A weight with one decimal (or [decimals]); the stored value is rounded so the statistics key stays readable. */
@Composable
private fun WeightSlider(pending: Set<String>, key: String, default: Float, title: Int, range: ClosedFloatingPointRange<Float>,
                         summary: Int? = null, decimals: Int = 1) = androidx.compose.runtime.CompositionLocalProvider(
    helium314.keyboard.settings.preferences.LocalPendingChange provides (key in pending)) {
    val prefs = LocalContext.current.prefs()
    val scale = if (decimals == 2) 100f else 10f
    SliderPreference(
        name = stringResource(title),
        key = key,
        description = { value: Float -> String.format(java.util.Locale.ROOT, "%.${decimals}f", value) },
        default = default,
        range = range,
        onConfirmed = { value: Float -> prefs.edit { putFloat(key, (value * scale).roundToInt() / scale) } },
        valueOnRight = true,
        summary = summary?.let { stringResource(it) },
    )
}

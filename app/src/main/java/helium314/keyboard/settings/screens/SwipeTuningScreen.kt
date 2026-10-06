// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import helium314.keyboard.settings.AdvancedTint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
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
internal val swipeSettingKeys = listOf(
    Settings.PREF_GESTURE_INPUT, Settings.PREF_GESTURE_PREVIEW_TRAIL, Settings.PREF_GESTURE_TRAIL_THICKNESS,
    Settings.PREF_GESTURE_TRAIL_WHOLE, Settings.PREF_GESTURE_TRAIL_WHOLE_LINGER, Settings.PREF_DELETE_SWIPE, Settings.PREF_DELETE_SWIPE_SPEED, Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING, Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN,
    Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION, Settings.PREF_GESTURE_CAPS_SWIPE,
    Settings.PREF_GESTURE_CAPS_HEIGHT, Settings.PREF_GESTURE_APOSTROPHE_VIA_PERIOD, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE,
    Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD, Settings.PREF_SPACE_HORIZONTAL_SWIPE, Settings.PREF_SPACE_VERTICAL_SWIPE,
    Settings.PREF_LANGUAGE_SWIPE_DISTANCE, Settings.PREF_TOUCHPAD_SENSITIVITY,
)

@Composable
internal fun GroupTitle(titleId: Int) = Column {
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
    val draft = helium314.keyboard.settings.rememberPrefsDraft("swipe", swipeSettingKeys, onClickBack)
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
            // the main switch as a heading of its own (a line above it, the headings' text), its switch at the right
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 8.dp))
            val setGesture = { on: Boolean -> prefs.edit { putBoolean(Settings.PREF_GESTURE_INPUT, on) } }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { setGesture(!gestureOn) }
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.gesture_input), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.let {
                    if (Settings.PREF_GESTURE_INPUT in draft.pending) it.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else it },
                    color = MaterialTheme.colorScheme.secondary)
                Switch(checked = gestureOn, onCheckedChange = { setGesture(it) })
            }
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

            // (the decoder's tuning, its statistics and the swipe logs: on "Refine swipe and learning", 2026-10-04)
        }
      }
        }
        }
    }
    draft.dialogs()
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.settings.preferences.LocalPendingChange

import kotlin.math.roundToInt

import androidx.compose.ui.layout.positionInWindow

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.layout.LayoutCoordinates

import androidx.compose.foundation.gestures.animateScrollBy

import android.os.SystemClock

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.ui.input.pointer.PointerEventPass

import kotlinx.coroutines.delay

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.layout.ime

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.foundation.relocation.bringIntoViewRequester

import androidx.compose.foundation.relocation.BringIntoViewRequester

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.latin.utils.SearchIcon
import helium314.keyboard.settings.preferences.PreferenceCategory

@Composable
fun SearchSettingsScreen(
    onClickBack: () -> Unit,
    title: String,
    settings: List<Any?>,
    simpleModeKeys: Set<String>? = null, // when set, only these are shown while the settings menu is in simple mode
    bottomBar: @Composable () -> Unit = {}, // pinned under the list, e.g. a try-it field
    topActions: @Composable RowScope.() -> Unit = {}, // top bar buttons before the advanced switch
    revealer: TapRevealer? = null, // a screen with a preview keyboard: moves the tapped row above where the keyboard will end
    isPending: ((String) -> Boolean)? = null, // rows with a change not yet accepted, drawn with an italic title
    content: @Composable (ColumnScope.() -> Unit)? = null, // overrides settings if not null; LAST: callers pass it as the trailing lambda
) {
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    val shownSettings = SettingsMode.filter(settings, simpleModeKeys, advanced)
    // the setting last tapped is kept out from under a preview keyboard: moved above where the keyboard will end
    // when the screen raises it ([revealer]), and brought into view again if the list then shrinks further
    val lastTapped = remember { mutableStateOf<TappedRow?>(null) }
    val tapScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    // the row stays the one to protect until another row is tapped or the list is scrolled by hand (then it's the
    // user's position): a slider dragged much later still changes the keyboard's height
    fun recentTap() = lastTapped.value
    revealer?.reveal = { lineY ->
        val coords = recentTap()?.coords?.takeIf { it.isAttached }
        if (coords != null) {
            val bottom = (coords.positionInWindow().y + coords.size.height).roundToInt()
            if (bottom > lineY) tapScope.launch { scrollState.animateScrollBy((bottom - lineY).toFloat()) }
        }
    }
    var viewportHeight by remember { mutableIntStateOf(0) }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(title) },
        leadingActions = topActions,
        content = {
            if (content != null) content()
            else {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                    bottomBar = bottomBar,
                ) { innerPadding ->
                    Column(
                        // the padding (try-it bar, keyboard) outside the scroll: the visible area ends above them, so a row
                        // scrolled "into view" really is visible
                        Modifier.padding(innerPadding)
                            .onSizeChanged {
                                // the list got shorter (the keyboard ended taller than expected): the tapped row into view
                                if (it.height < viewportHeight) recentTap()?.let { row -> tapScope.launch { row.requester.bringIntoView() } }
                                viewportHeight = it.height
                            }
                            .verticalScroll(scrollState)
                    ) {
                        // advanced-only items (and headings whose items all are) on the advanced tint; not on screens
                        // that are advanced as a whole (empty simple set), whose entry carries the tint instead
                        // (the tint stays while an item folds away, so not tied to the mode itself)
                        val tinting = !simpleModeKeys.isNullOrEmpty()
                        fun isAdvanced(item: Any?) = tinting && item is String && item !in simpleModeKeys!!
                        // every item is composed, the ones the mode hides folded away: the advanced switch unfolds and
                        // folds them (AdvancedReveal) instead of the list jumping
                        val shown = shownSettings.toSet()
                        // rows indented under the flush headings (Preference reads it), like Layout & Typing
                        androidx.compose.runtime.CompositionLocalProvider(helium314.keyboard.settings.preferences.LocalRowStart provides 22.dp) {
                        settings.forEachIndexed { index, it ->
                            if (it is Int) {
                                val categoryItems = settings.drop(index + 1).takeWhile { next -> next !is Int }.filterIsInstance<String>()
                                AdvancedReveal(it in shown) {
                                    if (categoryItems.isNotEmpty() && categoryItems.all { item -> isAdvanced(item) })
                                        AdvancedTint { PreferenceCategory(stringResource(it)) }
                                    else PreferenceCategory(stringResource(it))
                                }
                            } else {
                                // a line across the list
                                if (it == SettingsWithoutKey.DIVIDER) AdvancedReveal(it in shown) { androidx.compose.material3.HorizontalDivider() }
                                // a pref that comes with another's setting appears with the same animation
                                else AdvancedReveal(it != null && it in shown) {
                                    if (it != null) {
                                        val row = remember { TappedRow(BringIntoViewRequester()) }
                                        Box(Modifier.bringIntoViewRequester(row.requester).onGloballyPositioned { row.coords = it }.pointerInput(Unit) {
                                            awaitEachGesture {
                                                // a tap only, watched before the row handles it (the row consumes the release):
                                                // a press that moves beyond the touch slop is a scroll and doesn't count
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                var moved = false
                                                while (true) {
                                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                                        .firstOrNull { c -> c.id == down.id } ?: break
                                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                                    if (!change.pressed) break
                                                }
                                                if (!moved) { row.time = SystemClock.uptimeMillis(); lastTapped.value = row } else lastTapped.value = null
                                            }
                                        }) {
                                            CompositionLocalProvider(LocalPendingChange provides ((it as? String)?.let { key -> isPending?.invoke(key) } == true)) {
                                                if (isAdvanced(it)) AdvancedTint { SettingsActivity.settingsContainer[it]?.Preference() }
                                                else SettingsActivity.settingsContainer[it]?.Preference()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                    // lazyColumn has janky scroll for a while (not sure why compose gets smoother after a while)
                    // maybe related to unnecessary recompositions? but even for just displaying text it's there
                    // didn't manage to improve things with @Immutable list wrapper and other lazy list hints
                    // so for now: just use "normal" Column
                    //  even though it takes up to ~50% longer to load it's much better UX
                    //  and the missing appear animations could be added
    //                LazyColumn {
    //                    items(prefs.filterNotNull(), key = { it }) {
    //                        Box(Modifier.animateItem()) {
    //                            if (it is Int)
    //                                PreferenceCategory(stringResource(it))
    //                            else
    //                                SettingsActivity.settingsContainer[it]!!.Preference()
    //                        }
    //                    }
    //                }
                }
            }
        },
        filteredItems = { SettingsActivity.settingsContainer.filter(it) },
        itemContent = { it.Preference() }
    )
}

/** The simple / advanced settings menu switch, shown in the top bar of every settings screen. */
@Composable
private fun AdvancedModeSwitch(advanced: Boolean, onChange: (Boolean) -> Unit) {
    // the word above a small switch, together no taller than the bar's icons
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.settings_mode_advanced), style = MaterialTheme.typography.labelSmall)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Box(Modifier.requiredHeight(24.dp).wrapContentHeight(Alignment.CenterVertically, unbounded = true)) {
                Switch(checked = advanced, onCheckedChange = onChange, modifier = Modifier.scale(0.65f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T: Any?> SearchScreen(
    onClickBack: () -> Unit,
    title: @Composable () -> Unit,
    filteredItems: (String) -> List<T>,
    itemContent: @Composable (T) -> Unit,
    icon: @Composable (() -> Unit)? = null,
    menu: List<Pair<String, () -> Unit>>? = null,
    showAdvancedSwitch: Boolean = true, // the simple / advanced settings switch, on every screen
    leadingActions: @Composable RowScope.() -> Unit = {}, // top bar buttons before the advanced switch
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    val switchCtx = LocalContext.current
    val advancedMode by SettingsMode.state(switchCtx)
    // searchText and showSearch should have the same remember or rememberSaveable
    // saveable survives orientation changes and switching between screens, but shows the
    // keyboard in unexpected situations such as going back from another screen, which is rather annoying
    var searchText by remember { mutableStateOf(TextFieldValue()) }
    var showSearch by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
    { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

            fun setShowSearch(value: Boolean) {
                showSearch = value
                if (!value) searchText = TextFieldValue()
            }
            BackHandler {
                if (showSearch || searchText.text.isNotEmpty()) setShowSearch(false)
                else onClickBack()
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column {
                    TopAppBar(
                        // with separate settings per keyboard: the keyboard these settings belong to, under the title
                        title = {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val keyboard = remember { helium314.keyboard.latin.settings.KeyboardProfiles.editingKeyboard(
                                helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)) }
                            if (keyboard == null) title()
                            else Column {
                                title()
                                Text(helium314.keyboard.settings.screens.keyboardName(keyboard, ctx),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        windowInsets = WindowInsets(0),
                        navigationIcon = {
                            BackButton {
                                if (showSearch) setShowSearch(false)
                                else onClickBack()
                            }
                        },
                        actions = {
                            leadingActions()
                            if (showAdvancedSwitch) AdvancedModeSwitch(advancedMode) { SettingsMode.set(switchCtx, it) }
                            if (icon == null)
                                IconButton(onClick = { setShowSearch(!showSearch) }) { SearchIcon() }
                            else
                                icon()
                            if (menu != null)
                                Box {
                                    var showMenu by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = { showMenu = true }
                                    ) { Icon(painterResource(R.drawable.ic_arrow_left), "menu", Modifier.rotate(-90f)) }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        menu.forEach {
                                            DropdownMenuItem(
                                                text = { Text(it.first) },
                                                onClick = { showMenu = false; it.second() }
                                            )
                                        }
                                    }
                                }
                        },
                    )
                    ExpandableSearchField(
                        expanded = showSearch,
                        onDismiss = { setShowSearch(false) },
                        search = searchText,
                        onSearchChange = { searchText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                if (searchText.text.isBlank() && content != null) {
                    Column {
                        content()
                    }
                } else {
                    val items = filteredItems(searchText.text)
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    ) { innerPadding ->
                        LazyColumn(contentPadding = innerPadding) {
                            items(items) {
                                itemContent(it)
                            }
                        }
                    }
                }
            }
        }
    }
}

// from StreetComplete
/** Expandable text field that can be dismissed and requests focus when it is expanded */
@Composable
fun ExpandableSearchField(
    expanded: Boolean,
    onDismiss: () -> Unit,
    search: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
    }
    AnimatedVisibility(visible = expanded, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = modifier.focusRequester(focusRequester),
            leadingIcon = { SearchIcon() },
            trailingIcon = { IconButton(onClick = {
                if (search.text.isBlank()) onDismiss()
                else onSearchChange(TextFieldValue())
            }) { CloseIcon(android.R.string.cancel) } },
            singleLine = true,
            colors = colors,
            textStyle = contentTextDirectionStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}

/** A settings row as last tapped: where it is, to move it clear of the keyboard. */
class TappedRow(val requester: BringIntoViewRequester) {
    var coords: LayoutCoordinates? = null
    var time = 0L
}

/** Lets a screen with a preview keyboard move the tapped row above the line (window y) where the keyboard will end. */
class TapRevealer {
    internal var reveal: ((Int) -> Unit)? = null
    fun revealAbove(lineY: Int) = reveal?.invoke(lineY)
}

/**
 * For screens with their own scrolling list and a preview keyboard (Layout & Typing, Swiping): remembers where the
 * list was last tapped and, when a setting brings the preview up, scrolls that spot above where the keyboard will
 * end. [list] goes on the list (before its verticalScroll), [bar] on the try-it bar's box; pass [reveal] to
 * PreviewKeyboard. (Appearance does the same through SearchSettingsScreen's rows.)
 */
class ListTapReveal(private val scroll: androidx.compose.foundation.ScrollState, private val scope: kotlinx.coroutines.CoroutineScope,
                    private val ctx: android.content.Context, private val view: android.view.View) {
    private var tapY = -1
    private var listTop = 0f
    private var hiddenBarTop = -1
    private var shownBarTop = -1

    private fun keyboardLine(): Int {
        if (shownBarTop > 0) return shownBarTop
        if (hiddenBarTop <= 0) return Int.MAX_VALUE
        val strip = ctx.resources.getDimensionPixelSize(helium314.keyboard.latin.R.dimen.config_suggestions_strip_height)
        return hiddenBarTop - helium314.keyboard.latin.utils.ResourceUtils.getKeyboardHeight(ctx.resources,
            helium314.keyboard.latin.settings.Settings.getValues()) - strip
    }

    fun reveal() {
        val clearance = (40 * ctx.resources.displayMetrics.density).roundToInt() // the rest of the row below the finger
        val line = keyboardLine()
        if (tapY > 0 && tapY + clearance > line) {
            val by = tapY + clearance - line
            tapY -= by
            scope.launch { scroll.animateScrollBy(by.toFloat()) }
        }
    }

    val list: Modifier = Modifier
        .pointerInput(Unit) { awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            tapY = (down.position.y + listTop).roundToInt()
        } }
        .onGloballyPositioned { listTop = it.positionInWindow().y }

    fun onBarPlaced(barTop: Int) {
        val imeUp = androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (imeUp) shownBarTop = barTop else hiddenBarTop = barTop
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import helium314.keyboard.latin.utils.prefs
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.delay

/** The setup, always in Curmudgeon's Black with orange (2026-10-10; it had the phone's Dynamic colors, blue before
 *  Android 12), whatever colors the keyboard has. */
@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) = MaterialTheme(colorScheme = helium314.keyboard.latin.utils.AppLook.curmudgeonScheme, typography = MaterialTheme.typography) {
    WelcomeWizardSteps(close, finish)
}

/** The setup's checklist, the same rows on every screen (owner 2026-10-14: one list, the step's text in a card above).
 *  THEME is the third step's tiles (2026-10-24: Pick a theme, Pick a layout, All settings; the "Configure the keyboard"
 *  row went into the last tile). */
internal enum class SetupRow { ENABLE, SWITCH, THEME, FINISH }

/** How a row of the checklist looks: done (faded orange with a check), the step to take now (orange, framed), later
 *  (faint), open (orange, as a step can be taken again), or the filled Finished. */
internal enum class SetupRowLook { DONE, CURRENT, LATER, OPEN, FILLED }

internal class SetupRowState(val look: SetupRowLook, val clickable: Boolean)

/**
 * Row [row] of the checklist on screen [step]: 1 the keyboard isn't enabled, 2 enabled but not the one in use, 3 in use.
 * The tiles (THEME) and Finished only on 3. (Configure looked faint until 3 but could be tapped from 2 on, owner
 * 2026-10-14; its action is the All settings tile's since 2026-10-24, on 3.)
 */
internal fun setupRowState(row: SetupRow, step: Int): SetupRowState = when (row) {
    SetupRow.ENABLE -> if (step <= 1) SetupRowState(SetupRowLook.CURRENT, true) else SetupRowState(SetupRowLook.DONE, false)
    SetupRow.SWITCH -> when {
        step <= 1 -> SetupRowState(SetupRowLook.LATER, false)
        step == 2 -> SetupRowState(SetupRowLook.CURRENT, true)
        else -> SetupRowState(SetupRowLook.DONE, false)
    }
    SetupRow.THEME -> if (step >= 3) SetupRowState(SetupRowLook.OPEN, true) else SetupRowState(SetupRowLook.LATER, false)
    SetupRow.FINISH -> if (step >= 3) SetupRowState(SetupRowLook.FILLED, true) else SetupRowState(SetupRowLook.LATER, false)
}

@Composable
private fun WelcomeWizardSteps(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // (no Welcome picture screen since 2026-10-14, owner: "Perhaps we don't even need the first screen at all? Start with
    // Enable in settings as screen 1")
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 1
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    // the keyboard picker is a window over this screen: on 2, looked at until the keyboard is the one in use (closed
    // without a switch, nothing changes)
    LaunchedEffect(step) {
        if (step != 2) return@LaunchedEffect
        while (!UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) delay(50)
        step = 3
    }
    // Android's keyboard settings opened from Enable in Settings: looked at every 0.2 seconds (owner 2026-10-14: "Keep
    // 0.2 seconds"), and once the keyboard is enabled there this screen comes back on its own, on 2 (as AOSP's setup
    // did). The look goes on while the settings cover this screen (the composition stays, so its effects run), ends when
    // the keyboard is enabled, the screen is no longer 1, or the activity is gone.
    var waitingForEnable by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step, waitingForEnable) {
        if (step != 1 || !waitingForEnable) return@LaunchedEffect
        while (!UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)) delay(200)
        waitingForEnable = false
        step = 2
        bringBack(ctx)
    }
    // back from Android's settings (by hand, or brought back): where the setup is now, and no more looking
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            waitingForEnable = false
            if (step < 3) step = determineStep()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val useWideLayout = isWideScreen()
    // black, the rows a shade lighter, the title and what to tap in orange; the card's text a dimmer gray (owner
    // 2026-10-14: "White text is too bright. It can be dimmer gray")
    val stepBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = MaterialTheme.colorScheme.primary
    val titleColor = MaterialTheme.colorScheme.primary
    val cardTitleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val cardTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val appName = stringResource(ctx.applicationInfo.labelRes)
    @Composable fun bigText() {
        // "Welcome to" on the first screen, "Setting up" on the later ones (owner 2026-10-14)
        val resource = if (step <= 1) R.string.setup_welcome_title else R.string.setup_steps_title
        Column(Modifier.padding(bottom = 36.dp)) {
            Text(
                stringResource(resource, appName),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
                color = titleColor,
            )
        }
    }
    @Composable fun card(title: String, text: String?) {
        Column(Modifier.fillMaxWidth().background(color = stepBackgroundColor).padding(16.dp)) {
            Text(title, color = cardTitleColor)
            if (text != null)
                Text(text, style = MaterialTheme.typography.bodyLarge.merge(color = cardTextColor))
        }
    }
    @Composable fun row(row: SetupRow, text: String, icon: Painter, action: () -> Unit) {
        val state = setupRowState(row, step)
        val primary = MaterialTheme.colorScheme.primary
        val color = when (state.look) {
            SetupRowLook.DONE -> primary.copy(alpha = 0.45f) // (owner 2026-10-14: "faded orange instead of faded gray")
            SetupRowLook.CURRENT, SetupRowLook.OPEN -> primary
            SetupRowLook.LATER -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            SetupRowLook.FILLED -> MaterialTheme.colorScheme.onPrimary
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth()
                .then(if (state.clickable) Modifier.clickable { action() } else Modifier)
                .background(color = if (state.look == SetupRowLook.FILLED) primary else stepBackgroundColor)
                .then(if (state.look == SetupRowLook.CURRENT) Modifier.border(2.dp, primary) else Modifier)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shown = if (state.look == SetupRowLook.DONE) painterResource(R.drawable.ic_setup_check) else icon
            Icon(shown, null, Modifier.padding(end = 6.dp).size(32.dp), tint = color)
            Text(text, Modifier.weight(1f), color = color)
        }
    }
    @Composable fun steps() {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (step) {
                1 -> card(stringResource(R.string.setup_enable_card, appName), null)
                2 -> card(stringResource(R.string.setup_switch_card), null)
                else -> card(stringResource(R.string.setup_done_title), stringResource(R.string.setup_done_hint))
            }
            row(SetupRow.ENABLE, stringResource(R.string.setup_step1_action), painterResource(R.drawable.ic_setup_key)) {
                val intent = Intent()
                intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                // (with a request code of our own, so the settings can be closed again: bringBack; the return is looked
                // at on resume, above)
                @Suppress("DEPRECATION")
                ctx.getActivity()?.startActivityForResult(intent, SETUP_ENABLE_REQUEST)
                waitingForEnable = true
            }
            row(SetupRow.SWITCH, stringResource(R.string.setup_step2_action), painterResource(R.drawable.ic_setup_select),
                imm::showInputMethodPicker)
            // a faint row until the keyboard is in use, then the tiles to swipe through (2026-10-24): Pick a theme
            // (Android 12 and up: no Dynamic colors before, the keyboard is Curmudgeon's), Pick a layout, All settings
            // (the "Configure the keyboard" row until then; no "(optional)": owner 2026-10-14)
            if (setupRowState(SetupRow.THEME, step).look == SetupRowLook.OPEN) SetupTiles(stepBackgroundColor, close)
            else row(SetupRow.THEME, stringResource(R.string.setup_theme_layout_title), painterResource(R.drawable.ic_settings_appearance)) { }
            if (step >= 3) DictionaryDownloads(stepBackgroundColor, textColor)
            // on every screen, faint until the keyboard is in use
            // (a checkered flag, not the check of the steps done: owner 2026-10-14)
            row(SetupRow.FINISH, stringResource(R.string.setup_finish_action), painterResource(R.drawable.ic_setup_finish), finish)
        }
    }
    Surface {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides MaterialTheme.typography.titleLarge.merge(color = textColor),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (useWideLayout)
                    Row {
                        Box(Modifier.weight(0.4f)) {
                            bigText()
                        }
                        Box(Modifier.weight(0.6f)) {
                            steps()
                        }
                    }
                else
                    Column {
                        bigText()
                        steps()
                    }
            }
        }
    }
}

/** The request code Android's keyboard settings are opened with from the setup. */
private const val SETUP_ENABLE_REQUEST = 0x5e7

/**
 * The setup's activity on top again, over Android's keyboard settings opened from it: those settings are closed
 * ([Activity.finishActivity] needs no start from the background, which Android 10 and up hold back, and 15 and up even
 * from under another app's screen in the same task); should they be another task or a screen opened from them, the
 * activity is also brought to the front as it is (CLEAR_TOP with SINGLE_TOP: the same instance, no new one, so the setup
 * keeps its state; an Android that holds that start back just logs it).
 */
private fun bringBack(ctx: Context) {
    val activity = ctx.getActivity() ?: return
    activity.finishActivity(SETUP_ENABLE_REQUEST)
    runCatching {
        activity.startActivity(Intent(activity, activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(
    // content cut off on real device, but not here... great?
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

/** The third step's tiles (2026-10-24), in their order; no theme tile before Android 12 (no Dynamic colors to pick). */
internal enum class SetupTile { THEME, LAYOUT, SETTINGS }
internal fun setupTiles(sdk: Int = Build.VERSION.SDK_INT): List<SetupTile> =
    listOfNotNull(if (sdk >= Build.VERSION_CODES.S) SetupTile.THEME else null, SetupTile.LAYOUT, SetupTile.SETTINGS)

internal const val SETUP_TILES_TAG = "setup_tiles"
internal fun setupTileTag(tile: SetupTile) = "setup_tile_" + tile.name.lowercase()
internal fun setupLayoutWordTag(pick: SetupLayout.Pick) = "setup_layout_" + pick.name.lowercase()

/**
 * The third step's tiles, swiped through, one at a time, no wrap (2026-10-24): Pick a theme (as before), Pick a layout
 * (a preview of the keyboard in the picked theme's colors, Minimal · Classic · Dense under it; [SetupLayout]), All
 * settings (what the "Configure the keyboard" row did: [close]). All as tall as the tallest, the theme tile's; a thin
 * chevron just outside each edge that has a neighbor, the tiles a little narrower for it; no dots, no hint.
 */
@Composable
internal fun SetupTiles(background: androidx.compose.ui.graphics.Color, close: () -> Unit,
                        state: PagerState = rememberPagerState { setupTiles().size }) {
    val ctx = LocalContext.current
    val tiles = setupTiles()
    var curmudgeon by rememberSaveable { mutableStateOf(SetupTheme.isCurmudgeon(ctx)) }
    // the keyboards' Appearance / Layout & Typing settings before the first pick here (null: none yet)
    var themeBefore by rememberSaveable { mutableStateOf<String?>(null) }
    var layoutPick by rememberSaveable { mutableStateOf(SetupLayout.current(ctx)) }
    var layoutBefore by rememberSaveable { mutableStateOf<String?>(null) }
    // every tile as tall as the tallest one measured (the theme tile's height)
    var tileHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val chevronColor = MaterialTheme.colorScheme.surfaceContainerHighest // (a shade lighter than the tile)
    // the preview keyboard's colors: the theme picked on the first tile (before Android 12 the keyboard is Curmudgeon's)
    val colors = remember(curmudgeon) { SetupTheme.colors(ctx,
        if (curmudgeon || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) KeyboardTheme.THEME_CURMUDGEON else KeyboardTheme.THEME_DYNAMIC) }
    Spacer(Modifier.height(4.dp))
    HorizontalPager(state, Modifier.fillMaxWidth().testTag(SETUP_TILES_TAG), beyondViewportPageCount = tiles.size,
        verticalAlignment = Alignment.Top) { index ->
        val tile = tiles[index]
        val rowHeight = if (tileHeight > 0) Modifier.height(with(density) { tileHeight.toDp() }) else Modifier.height(IntrinsicSize.Min)
        Row(Modifier.fillMaxWidth().then(rowHeight)) {
            Chevron(left = true, shown = index > 0, chevronColor)
            val tileModifier = Modifier.weight(1f).fillMaxHeight().testTag(setupTileTag(tile))
                .onSizeChanged { if (it.height > tileHeight) tileHeight = it.height }
            when (tile) {
                SetupTile.THEME -> ThemePick(background, curmudgeon, tileModifier,
                    pickDynamic = { SetupTheme.pickDynamic(ctx, themeBefore); curmudgeon = false },
                    pickCurmudgeon = {
                        // (only the first time: a keyboard that came with Curmudgeon has nothing to go back to, see pickDynamic)
                        if (themeBefore == null && !SetupTheme.isCurmudgeon(ctx)) themeBefore = SetupTheme.before(ctx)
                        SetupTheme.pickCurmudgeon(ctx)
                        curmudgeon = true
                    })
                SetupTile.LAYOUT -> LayoutPick(background, colors, layoutPick, tileModifier) { pick ->
                    if (pick == SetupLayout.Pick.DENSE) SetupLayout.pickDense(ctx, layoutBefore)
                    else {
                        // (only the first time: a keyboard not on Dense when the setup opened has nothing to go back to)
                        if (layoutBefore == null && SetupLayout.current(ctx) == SetupLayout.Pick.DENSE) layoutBefore = SetupLayout.before(ctx)
                        SetupLayout.pick(ctx, pick)
                    }
                    layoutPick = pick
                }
                SetupTile.SETTINGS -> AllSettingsTile(background, close, tileModifier)
            }
            Chevron(left = false, shown = index < tiles.size - 1, chevronColor)
        }
    }
}

private val CHEVRON_SLOT = 14.dp

/** One hairline, a wide angle pointing away from the tile, no arrowhead, two thirds of the tile's height; or nothing
 *  ([shown] false: no neighbor on that side, the slot stays so every tile has the same width). */
@Composable
private fun Chevron(left: Boolean, shown: Boolean, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.width(CHEVRON_SLOT).fillMaxHeight()) {
        if (!shown) return@Canvas
        val span = size.height * 2f / 3f
        val top = (size.height - span) / 2f
        val depth = size.width * 0.4f
        val edge = if (left) size.width * 0.7f else size.width * 0.3f
        val tip = if (left) edge - depth else edge + depth
        val path = Path().apply { moveTo(edge, top); lineTo(tip, top + span / 2f); lineTo(edge, top + span) }
        drawPath(path, color, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * "Pick a theme": Dynamic (the default, picked at first) or Curmudgeon, each a picture of the keyboard in its real
 * colors; the whole choice is tapped, the picked one's picture ringed in orange (owner 2026-10-14: "can you remove the
 * radio button, and let user tap the thumbnail which can be, sort of, encircled?"). See [SetupTheme]; the picks are
 * [SetupTiles]'s (the layout tile's preview follows the theme).
 */
@Composable
private fun ThemePick(background: androidx.compose.ui.graphics.Color, curmudgeon: Boolean, modifier: Modifier,
                      pickDynamic: () -> Unit, pickCurmudgeon: () -> Unit) {
    val ctx = LocalContext.current
    val dynamicColors = remember { SetupTheme.colors(ctx, KeyboardTheme.THEME_DYNAMIC) }
    val curmudgeonColors = remember { SetupTheme.colors(ctx, KeyboardTheme.THEME_CURMUDGEON) }
    Column(modifier.background(color = background).padding(16.dp)) {
        Text(stringResource(R.string.setup_theme_title))
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeChoice(dynamicColors, stringResource(R.string.theme_preset_dynamic), stringResource(R.string.setup_theme_dynamic_hint),
                selected = !curmudgeon) { if (curmudgeon) pickDynamic() }
            ThemeChoice(curmudgeonColors, stringResource(R.string.theme_preset_curmudgeon), stringResource(R.string.setup_theme_curmudgeon_hint),
                selected = curmudgeon) { if (!curmudgeon) pickCurmudgeon() }
        }
    }
}

/** "Pick a layout": a small preview of the keyboard as [pick] has it, in [colors], and Minimal · Classic · Dense under
 *  it in bold, the picked one orange; a tap on a word is the pick ([onPick], not for the one picked already). */
@Composable
private fun LayoutPick(background: androidx.compose.ui.graphics.Color, colors: Colors, pick: SetupLayout.Pick, modifier: Modifier,
                       onPick: (SetupLayout.Pick) -> Unit) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Column(modifier.background(color = background).padding(16.dp)) {
        Text(stringResource(R.string.setup_layout_title))
        LayoutPreview(colors, pick, SetupLayout.wordPadding(ctx, pick),
            Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(2.4f).clip(RoundedCornerShape(4.dp)))
        Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val words = listOf(SetupLayout.Pick.MINIMAL to stringResource(R.string.setup_layout_minimal),
                SetupLayout.Pick.CLASSIC to stringResource(R.string.layout_preset_classic), SetupLayout.Pick.DENSE to stringResource(R.string.layout_preset_dense))
            words.forEachIndexed { i, (p, name) ->
                if (i > 0) Text("·", color = dim, style = MaterialTheme.typography.bodyLarge)
                Text(name, Modifier.clickable { if (p != pick) onPick(p) }.testTag(setupLayoutWordTag(p)).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    color = if (p == pick) MaterialTheme.colorScheme.primary else dim)
            }
        }
    }
}

/** The sample words on the preview's strip (nothing is typed yet). */
internal val SETUP_SAMPLE_WORDS = listOf("wor", "WOR", "work", "works", "worked", "wo")

/** The symbols HeliBoard's layout puts on the letters when the number row is shown (Classic's and Dense's hints; read
 *  off the keyboard on 2026-10-24), by letter. */
private val numberRowSymbolHints: Map<Char, String> = "q` w\\ e| r= t[ y] u< i> o{ p} a€ s§ d$ f– g— h- j+ k« l» z× x\" c' v: b; n÷ m?"
    .split(' ').associate { it[0] to it.substring(1) }

/**
 * The keyboard as [pick] lays it out, small, in [colors]: a suggestion strip with [SETUP_SAMPLE_WORDS] spaced
 * [wordPadding] dp (at the preview's scale: the keyboard is drawn as wide as the device's screen, shrunk), the number
 * row (not Minimal), the letters with their symbols at the top right (not Minimal: Classic the Curmudgeon map's, Dense
 * accents), shift and backspace, the bottom row. A drawing, not the keyboard view itself (2026-10-24).
 */
@Composable
private fun LayoutPreview(colors: Colors, pick: SetupLayout.Pick, wordPadding: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val measurer = rememberTextMeasurer()
    val screenWidth = ctx.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
    val wordColor = androidx.compose.ui.graphics.Color(helium314.keyboard.latin.utils.suggestionTextColor(ctx.prefs(), colors))
    fun c(type: ColorType) = androidx.compose.ui.graphics.Color(colors.get(type))
    val background = c(ColorType.MAIN_BACKGROUND); val strip = c(ColorType.STRIP_BACKGROUND)
    val key = c(ColorType.KEY_BACKGROUND); val functional = c(ColorType.FUNCTIONAL_KEY_BACKGROUND); val space = c(ColorType.SPACE_BAR_BACKGROUND)
    val keyText = c(ColorType.KEY_TEXT); val hintText = c(ColorType.KEY_HINT_TEXT)
    val numberRow = pick != SetupLayout.Pick.MINIMAL
    val hints: Map<Char, String> = when (pick) {
        SetupLayout.Pick.MINIMAL -> emptyMap(); SetupLayout.Pick.CLASSIC, SetupLayout.Pick.DENSE -> numberRowSymbolHints }
    Canvas(modifier) {
        drawRect(background)
        val scale = size.width / screenWidth
        // the strip
        val stripHeight = size.height * 0.17f
        drawRect(strip, size = Size(size.width, stripHeight))
        val padPx = wordPadding.dp.toPx() * scale
        val wordStyle = TextStyle(color = wordColor, fontSize = (stripHeight * 0.42f).toSp())
        val laidOut = SETUP_SAMPLE_WORDS.map { measurer.measure(it, wordStyle, maxLines = 1) }
        val total = laidOut.sumOf { it.size.width } + padPx * 2 * laidOut.size
        var x = ((size.width - total) / 2f).coerceAtLeast(0f)
        for (word in laidOut) {
            x += padPx
            drawText(word, topLeft = Offset(x, (stripHeight - word.size.height) / 2f))
            x += word.size.width + padPx
        }
        // the keys
        val letterRows = listOfNotNull(if (numberRow) "1234567890" else null, "qwertyuiop", "asdfghjkl", "zxcvbnm")
        val rowCount = letterRows.size + 1
        val pad = size.height * 0.03f
        val gap = size.height * 0.022f
        val rowHeight = (size.height - stripHeight - 2 * pad - (rowCount - 1) * gap) / rowCount
        val corner = CornerRadius(rowHeight * 0.15f)
        val unit = (size.width - 2 * pad - gap * 9) / 10f // ten keys across
        val labelStyle = TextStyle(color = keyText, fontSize = (rowHeight * 0.5f).toSp())
        val hintStyle = TextStyle(color = hintText, fontSize = (rowHeight * 0.3f).toSp())
        fun drawKey(x: Float, y: Float, width: Float, fill: androidx.compose.ui.graphics.Color, label: Char?) {
            drawRoundRect(fill, Offset(x, y), Size(width, rowHeight), corner)
            if (label == null) return
            val text = measurer.measure(label.toString(), labelStyle, maxLines = 1)
            drawText(text, topLeft = Offset(x + (width - text.size.width) / 2f, y + (rowHeight - text.size.height) / 2f))
            val hint = hints[label] ?: return
            val h = measurer.measure(hint, hintStyle, maxLines = 1)
            drawText(h, topLeft = Offset(x + width - h.size.width - rowHeight * 0.08f, y + rowHeight * 0.04f))
        }
        letterRows.forEachIndexed { row, letters ->
            val y = stripHeight + pad + row * (rowHeight + gap)
            if (letters.length == 7) { // shift, the letters, backspace
                val side = (size.width - 2 * pad - 7 * unit - 8 * gap) / 2f
                drawKey(pad, y, side, functional, null)
                letters.forEachIndexed { i, ch -> drawKey(pad + side + gap + i * (unit + gap), y, unit, key, ch) }
                drawKey(size.width - pad - side, y, side, functional, null)
            } else {
                val start = pad + (size.width - 2 * pad - letters.length * unit - (letters.length - 1) * gap) / 2f
                letters.forEachIndexed { i, ch -> drawKey(start + i * (unit + gap), y, unit, key, ch) }
            }
        }
        // the bottom row: two function keys, the space bar, two more
        val y = stripHeight + pad + letterRows.size * (rowHeight + gap)
        val widths = listOf(1.5f, 1f, 5f, 1f, 1.5f)
        val bottomUnit = (size.width - 2 * pad - gap * 4) / widths.sum()
        var bx = pad
        widths.forEachIndexed { i, w -> drawKey(bx, y, w * bottomUnit, if (i == 2) space else functional, null); bx += w * bottomUnit + gap }
    }
}

/** "All settings": the gear in orange, the bold title, a small line of what is there; the whole tile is tapped and does
 *  what the "Configure the keyboard" row did ([close]: the setup closes, the settings open, with the Switch bar when the
 *  keyboard isn't the one in use yet). */
@Composable
private fun AllSettingsTile(background: androidx.compose.ui.graphics.Color, close: () -> Unit, modifier: Modifier) {
    Column(modifier.background(color = background).clickable { close() }.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.sym_keyboard_language_switch), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.setup_all_settings), Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.setup_all_settings_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun RowScope.ThemeChoice(colors: Colors, name: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier
            .border(2.5.dp, if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(8.dp))
            .padding(5.dp)
        ) {
            KeyboardThumbnail(colors, Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(4.dp)))
        }
        Text(name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), textAlign = TextAlign.Center)
    }
}

/** A small keyboard in [colors]: its background, four rows of keys (the space bar between two function keys) and a
 *  swipe trail across the letters. */
@Composable
private fun KeyboardThumbnail(colors: Colors, modifier: Modifier) {
    val background = androidx.compose.ui.graphics.Color(colors.get(ColorType.MAIN_BACKGROUND))
    val key = androidx.compose.ui.graphics.Color(colors.get(ColorType.KEY_BACKGROUND))
    val functional = androidx.compose.ui.graphics.Color(colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
    val space = androidx.compose.ui.graphics.Color(colors.get(ColorType.SPACE_BAR_BACKGROUND))
    val trail = androidx.compose.ui.graphics.Color(colors.get(ColorType.GESTURE_TRAIL))
    Canvas(modifier) {
        drawRect(background)
        val pad = size.height * 0.06f
        val gap = size.height * 0.04f
        val rowHeight = (size.height - 2 * pad - 3 * gap) / 4
        val corner = CornerRadius(rowHeight * 0.15f)
        // (columns of keys in each row, by width: ten, nine, seven; the bottom row's space bar five wide)
        fun keyRow(row: Int, widths: List<Float>, fill: (Int) -> androidx.compose.ui.graphics.Color) {
            val total = widths.sum()
            val unit = (size.width - 2 * pad - gap * (widths.size - 1)) / total
            var x = pad
            val y = pad + row * (rowHeight + gap)
            widths.forEachIndexed { i, w ->
                drawRoundRect(fill(i), Offset(x, y), Size(w * unit, rowHeight), corner)
                x += w * unit + gap
            }
        }
        keyRow(0, List(10) { 1f }) { key }
        keyRow(1, List(9) { 1f }) { key }
        keyRow(2, listOf(1.5f) + List(7) { 1f } + 1.5f) { if (it == 0 || it == 8) functional else key }
        keyRow(3, listOf(1.5f, 1f, 5f, 1f, 1.5f)) { if (it == 2) space else functional }
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.62f)
            cubicTo(size.width * 0.3f, size.height * 0.15f, size.width * 0.55f, size.height * 0.2f, size.width * 0.62f, size.height * 0.45f)
            cubicTo(size.width * 0.66f, size.height * 0.58f, size.width * 0.72f, size.height * 0.62f, size.width * 0.8f, size.height * 0.3f)
        }
        drawPath(path, trail, style = Stroke(width = size.height * 0.05f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * In the last step: the phone's languages that have a dictionary to download and none here yet, each a link to it
 * (owner 2026-10-10: "Should think about making downloads a part of keyboard onboarding flow"). Nothing when there are none.
 */
@Composable
private fun DictionaryDownloads(background: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color) {
    val ctx = LocalContext.current
    val downloads = androidx.compose.runtime.remember {
        val system = androidx.core.os.ConfigurationCompat.getLocales(android.content.res.Resources.getSystem().configuration)
        (0 until system.size()).mapNotNull { system[it] }
            .filter { !helium314.keyboard.settings.screens.getUserAndInternalDictionaries(ctx, it).let { (own, internal) -> internal || own.isNotEmpty() } }
            .mapNotNull { locale -> helium314.keyboard.latin.utils.OnDemandDictionaries.forLocale(ctx, locale)?.let { locale to it } }
            .distinctBy { it.second.tag }
    }
    if (downloads.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Column(Modifier.background(color = background).padding(16.dp)) {
        Text(stringResource(R.string.setup_dictionaries_title))
        for ((locale, entry) in downloads) {
            val label = stringResource(R.string.dictionary_get_ours, entry.name,
                android.text.format.Formatter.formatShortFileSize(ctx, entry.bytes))
            Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(helium314.keyboard.latin.utils.OnDemandDictionaries.pageFor(locale)))) }
                })
        }
    }
}

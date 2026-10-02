// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A slider's value, drawn as plain text; tapping it turns it into a small number box typed on the same keyboard
 * that previews the setting. The number is in the units the text shows ("120%", "250 ms", "18 dp"): [display] is
 * read at two points of [range] and the typed number mapped back along that line, so callers need not know how their
 * value is shown; out-of-range numbers are clamped. Applied on Done or when the box is left.
 *
 * A previewing dialog normally takes no keyboard input (so the preview keyboard stays with the try-it box under it).
 * While the number box is open the dialog accepts input, so the keyboard types into the box; tapping the try-it box
 * moves the keyboard there, tapping the number box brings it back.
 */
@Composable
fun SliderValueText(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: @Composable (Float) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    @Suppress("UNUSED_PARAMETER") title: String? = null,
    onTyped: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val text = display(value)
    // two points of the shown scale (the ends, else the middle: an end may read as words, e.g. "Always start instantly")
    val samples = listOf(range.start, (range.start + range.endInclusive) / 2, range.endInclusive)
        .map { it to numberIn(display(it)) }.filter { it.second != null }
    if (!editing) {
        Text(text, modifier.clickable { editing = true }, style = style) // looks like plain text: a quiet extra
        return
    }
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val preview = LocalPreviewKeyboard.current
    var typed by remember { mutableStateOf(numberIn(text)?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "") }
    val unit = text.replace(NUMBER, "").trim()
    val focus = remember { FocusRequester() }
    fun apply() {
        val n = numberIn(typed) ?: return
        val (a, b) = if (samples.size >= 2) samples.first() to samples.last() else null to null
        val v = if (a != null && b != null && b.second != a.second)
            a.first + (n - a.second!!) * (b.first - a.first) / (b.second!! - a.second!!)
        else n
        onTyped(v.coerceIn(range))
    }
    // the dialog takes keyboard input while the box is open; when it closes the preview goes back to the try-it box
    val latestApply = androidx.compose.runtime.rememberUpdatedState(::apply)
    DisposableEffect(window) {
        window?.let { KeepKeyboardWindows.typing.add(it) }
        val end: () -> Unit = { editing = false }
        KeepKeyboardWindows.endTyping = end
        window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        onDispose {
            latestApply.value() // OK pressed (or the box left) without Done: what was typed still counts
            window?.let { KeepKeyboardWindows.typing.remove(it) }
            if (KeepKeyboardWindows.endTyping === end) KeepKeyboardWindows.endTyping = null
            window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            preview?.restore()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = typed,
            // numbers only (one decimal point at most)
            onValueChange = { new ->
                val digits = new.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
                typed = if (digits.count { it == '.' } > 1) typed else digits.take(6)
            },
            singleLine = true,
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply(); editing = false }),
            modifier = Modifier
                .focusRequester(focus)
                .onFocusChanged { if (!it.isFocused) apply() } // left for the try-it box: take what was typed so far
                .widthIn(min = 40.dp)
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (unit.isNotEmpty()) Text(" $unit", style = style)
    }
}

private val NUMBER = Regex("-?\\d+(?:[.,]\\d+)?")

private fun numberIn(text: String): Float? = NUMBER.find(text)?.value?.replace(',', '.')?.toFloatOrNull()

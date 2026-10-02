// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType

/**
 * A slider's value text that can be tapped to type the number instead of dragging. The number is typed in the units
 * the text shows ("120%", "250 ms", "18 dp"): [display] is read at two points of [range] and the typed number mapped
 * back along that line, so callers need not know how their value is shown. A typed number outside the range is
 * clamped to it.
 */
@Composable
fun SliderValueText(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: @Composable (Float) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    title: String? = null,
    onTyped: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val preview = LocalPreviewKeyboard.current // the number field takes the keyboard; the preview comes back after
    val text = display(value)
    // two points of the shown scale (the ends, else the middle: an end may read as words, e.g. "Always start instantly")
    val samples = listOf(range.start, (range.start + range.endInclusive) / 2, range.endInclusive)
        .map { it to numberIn(display(it)) }.filter { it.second != null }
    // looks like plain text: typing the number is a quiet extra for whoever taps it
    Text(text, modifier.clickable { editing = true }, style = style)
    if (!editing) return
    val (a, b) = if (samples.size >= 2) samples.first() to samples.last() else null to null
    TextInputDialog(
        onDismissRequest = { editing = false; preview?.restore() },
        onConfirmed = { typed ->
            val n = numberIn(typed) ?: return@TextInputDialog
            val v = if (a != null && b != null && b.second != a.second)
                a.first + (n - a.second!!) * (b.first - a.first) / (b.second!! - a.second!!)
            else n
            onTyped(v.coerceIn(range))
        },
        title = title?.let { { Text(it) } },
        initialText = numberIn(text)?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "",
        keyboardType = KeyboardType.Decimal,
        checkTextValid = { numberIn(it) != null },
    )
}

private val NUMBER = Regex("-?\\d+(?:[.,]\\d+)?")

private fun numberIn(text: String): Float? = NUMBER.find(text)?.value?.replace(',', '.')?.toFloatOrNull()

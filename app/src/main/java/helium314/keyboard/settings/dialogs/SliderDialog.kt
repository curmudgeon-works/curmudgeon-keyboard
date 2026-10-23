// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark

/** A second slider in a [SliderDialog], under the first one and its checkbox (the backspace's repeat under its first
 *  delete, 2026-10-21). With it, each slider's value line is above the slider. [onDone] gets the value it was moved
 *  to, or null after Default (not moved since); untouched, it isn't called (stored = picked). */
class SecondSlider(
    val initialValue: Float,
    val range: ClosedFloatingPointRange<Float>,
    val defaultValue: Float,
    val positionString: @Composable (Float) -> String,
    val onDone: (Float?) -> Unit,
)

@Composable
fun SliderDialog(
    onDismissRequest: () -> Unit,
    onDone: (Float) -> Unit,
    initialValue: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    showDefault: Boolean = false,
    onDefault: () -> Unit = { },
    defaultValue: Float? = null, // Default moves the slider here and previews it; OK then calls [onDefault]
    onValueChanged: (Float) -> Unit = { },
    title: (@Composable () -> Unit)? = null,
    intermediateSteps: Int? = null,
    positionString: (@Composable (Float) -> String) = { it.toString() },
    live: Boolean = false, // the keyboard stays up and [onValueChanged] follows the drag; Cancel reports [initialValue] again
    applyOnRelease: Boolean = false, // with [live]: only when the slider is let go, not during the drag
    offLabel: String? = null, // a checkbox under the slider: ticked, the value is [offValue] and the slider is greyed out
    offValue: Float = -1f,
    second: SecondSlider? = null,
) {
    // (saveable: a rotation or dark-mode switch keeps the dialog's state, so Cancel still puts back the opening value)
    var off by rememberSaveable { mutableStateOf(offLabel != null && initialValue == offValue) }
    // (ticked: the slider waits where it would start, the default if there is one)
    var sliderPosition by rememberSaveable { mutableFloatStateOf(if (off) (defaultValue ?: range.start).coerceIn(range) else initialValue) }
    var touched by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var atDefault by rememberSaveable { mutableStateOf(false) } // Default was pressed and the slider not moved since
    var secondPosition by rememberSaveable { mutableFloatStateOf(second?.initialValue ?: 0f) }
    var secondMoved by rememberSaveable { mutableStateOf(false) }
    var secondAtDefault by rememberSaveable { mutableStateOf(false) }
    // (live sliders apply when the finger leaves the slider, not during the drag: each step would rebuild the preview)
    val dismiss = { if (live && touched && !confirmed) onValueChanged(initialValue); onDismissRequest() }

    ThreeButtonAlertDialog(
        onDismissRequest = dismiss,
        neutralButtonText = if (showDefault) stringResource(R.string.button_default) else null,
        // Default doesn't close: the slider shows the default (previewed like a drag), OK or Cancel decide
        onNeutral = {
            if (defaultValue == null) { confirmed = true; onDismissRequest(); onDefault(); second?.onDone(null) }
            else {
                sliderPosition = defaultValue; off = false; atDefault = true; touched = true; onValueChanged(defaultValue)
                if (second != null) { secondPosition = second.defaultValue; secondMoved = false; secondAtDefault = true }
            }
        },
        onConfirmed = {
            confirmed = true
            // (with a second slider, an untouched first one stores nothing: stored = picked)
            if (atDefault) onDefault() else if (touched || second == null) onDone(if (off) offValue else sliderPosition)
            if (secondMoved) second?.onDone(secondPosition) else if (secondAtDefault) second?.onDone(null)
        },
        modifier = modifier,
        title = title,
        // live sliders preview on the keyboard; so do sliders on a screen that keeps it up (the key sound / vibration ones)
        keepKeyboard = live || LocalKeepKeyboard.current,
        content = {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge
            ) {
                Column {
                    // (with a second slider each value line is the slider's label, above it; greyed with the checkbox)
                    if (second != null) Text(positionString(if (off) offValue else sliderPosition),
                        color = if (off) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else androidx.compose.ui.graphics.Color.Unspecified)
                    if (intermediateSteps == null)
                        Slider(
                            value = sliderPosition,
                            onValueChange = { sliderPosition = it; touched = true; atDefault = false },
                            onValueChangeFinished = { onValueChanged(sliderPosition) },
                            valueRange = range,
                            enabled = !off,
                        )
                    else
                        Slider(
                            value = sliderPosition,
                            onValueChange = { sliderPosition = it; touched = true; atDefault = false },
                            onValueChangeFinished = { onValueChanged(sliderPosition) },
                            valueRange = range,
                            steps = intermediateSteps,
                            enabled = !off,
                        )
                    if (second == null) Text(positionString(if (off) offValue else sliderPosition))
                    if (offLabel != null) {
                        fun toggle(on: Boolean) { off = on; touched = true; atDefault = false; onValueChanged(if (on) offValue else sliderPosition) }
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp).clickable { toggle(!off) }
                        ) {
                            androidx.compose.material3.Checkbox(checked = off, onCheckedChange = { toggle(it) })
                            Text(offLabel)
                        }
                    }
                    if (second != null) {
                        Text(second.positionString(secondPosition), modifier = Modifier.padding(top = 16.dp))
                        Slider(
                            value = secondPosition,
                            onValueChange = { secondPosition = it; secondMoved = true; secondAtDefault = false },
                            valueRange = second.range,
                        )
                    }
                }
            }
        },
    )
}

@Preview
@Composable
private fun PreviewSliderDialog() {
    Theme(previewDark) {
        SliderDialog(
            onDismissRequest = { },
            onDone = { },
            initialValue = 100f,
            range = 0f..500f,
            title = { Text("move it") },
            showDefault = true
        )
    }
}

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
) {
    // (saveable: a rotation or dark-mode switch keeps the dialog's state, so Cancel still puts back the opening value)
    var off by rememberSaveable { mutableStateOf(offLabel != null && initialValue == offValue) }
    // (ticked: the slider waits where it would start, the default if there is one)
    var sliderPosition by rememberSaveable { mutableFloatStateOf(if (off) (defaultValue ?: range.start).coerceIn(range) else initialValue) }
    var touched by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var atDefault by rememberSaveable { mutableStateOf(false) } // Default was pressed and the slider not moved since
    // (live sliders apply when the finger leaves the slider, not during the drag: each step would rebuild the preview)
    val dismiss = { if (live && touched && !confirmed) onValueChanged(initialValue); onDismissRequest() }

    ThreeButtonAlertDialog(
        onDismissRequest = dismiss,
        neutralButtonText = if (showDefault) stringResource(R.string.button_default) else null,
        // Default doesn't close: the slider shows the default (previewed like a drag), OK or Cancel decide
        onNeutral = {
            if (defaultValue == null) { confirmed = true; onDismissRequest(); onDefault() }
            else { sliderPosition = defaultValue; off = false; atDefault = true; touched = true; onValueChanged(defaultValue) }
        },
        onConfirmed = { confirmed = true; if (atDefault) onDefault() else onDone(if (off) offValue else sliderPosition) },
        modifier = modifier,
        title = title,
        // live sliders preview on the keyboard; so do sliders on a screen that keeps it up (the key sound / vibration ones)
        keepKeyboard = live || LocalKeepKeyboard.current,
        content = {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge
            ) {
                Column {
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
                    Text(positionString(if (off) offValue else sliderPosition))
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

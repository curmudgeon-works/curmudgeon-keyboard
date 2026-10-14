@file:OptIn(kotlinx.coroutines.FlowPreview::class)
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.toColorInt
import com.github.skydoves.colorpicker.compose.AlphaSlider
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.isWideScreen

@Composable
fun ColorPickerDialog(
    onDismissRequest: () -> Unit,
    initialColor: Int,
    title: String,
    showDefault: Boolean,
    onDefault: () -> Unit,
    // set: the colour shows on the preview keyboard as it's picked (a moment after the finger stops), the keyboard stays
    // up and the dialog stays clear of it (smaller wheel; the hex box types on that keyboard)
    onPreview: ((Int) -> Unit)? = null,
    onConfirmed: (Int) -> Unit,
) {
    val controller = rememberColorPickerController()
    val wheelPaint = Paint().apply {
        alpha = 0.5f
        style = PaintingStyle.Stroke
        strokeWidth = 5f
        color = Color.White
    }
    controller.wheelPaint = wheelPaint
    val barHeight = 35.dp
    val initialString = initialColor.toUInt().toString(16)
    var textValue by remember { mutableStateOf(TextFieldValue(initialString, TextRange(initialString.length))) }
    var currentColor by remember { mutableStateOf(Color(initialColor)) }
    val useWideLayout = isWideScreen()
    // while previewing, the dialog leaves the keyboard to the preview; a tap on the hex box gives it to the box
    var hexFocused by remember { mutableStateOf(false) }
    val softKeyboard = LocalSoftwareKeyboardController.current
    if (onPreview != null && hexFocused) LaunchedEffect(Unit) { withFrameNanos { }; softKeyboard?.show() }
    @Composable fun topBar() {
        Row {
            Surface(
                color = Color(initialColor),
                modifier = Modifier.fillMaxWidth(0.5f)
                    .padding(start = 10.dp)
                    .height(barHeight)
            ) { }
            Surface(
                color = currentColor,
                modifier = Modifier.fillMaxWidth()
                    .padding(end = 10.dp)
                    .height(barHeight)
            ) { }
        }
    }
    if (onPreview != null) LaunchedEffect(Unit) {
        snapshotFlow { currentColor }.drop(1).debounce(250).collect { onPreview(it.toArgb()) }
    }
    @Composable fun picker() {
        HsvColorPicker(
            modifier = Modifier
                .size(if (onPreview != null) 180.dp else 300.dp)
                .padding(10.dp),
            controller = controller,
            onColorChanged = {
                if (it.fromUser)
                    textValue = TextFieldValue(it.hexCode, selection = TextRange(it.hexCode.length))
                currentColor = it.color
            },
            initialColor = Color(initialColor),
        )
    }
    @Composable fun slidersAndTextField() {
        // the hex box on every picker (2026-10-10, palettes too), right-aligned above the transparency bar, as far above
        // it as the brightness bar is below it (20 dp: this row's 10 dp under it + the bar's own 10 dp)
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
            TextField(
                value = textValue,
                singleLine = true,
                modifier = Modifier.width(150.dp).onFocusChanged { hexFocused = it.isFocused },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password, // todo: KeyboardType.Password is a crappy way of avoiding suggestions... is there really no way in compose?
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDismissRequest(); onConfirmed(controller.selectedColor.value.toArgb()) }),
                onValueChange = {
                    textValue = it
                    val androidColor = runCatching { "#${it.text}".toColorInt() }.getOrNull()
                    if (androidColor != null)
                        controller.selectByColor(Color(androidColor), false)
                }
            )
        }
        AlphaSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .height(barHeight),
            controller = controller,
            initialColor = Color(initialColor),
            wheelPaint = wheelPaint
        )
        BrightnessSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .height(barHeight),
            controller = controller,
            initialColor = Color(initialColor),
            wheelPaint = wheelPaint
        )
    }
    ThreeButtonAlertDialog(
        keepKeyboard = onPreview != null, // the preview keyboard stays up (the hex box types on it)
        fieldTyping = hexFocused,
        onDismissRequest = onDismissRequest,
        onConfirmed = { onConfirmed(controller.selectedColor.value.toArgb()) },
        title = { Text(title) },
        content = {
            if (useWideLayout)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    picker()
                    Column {
                        topBar()
                        slidersAndTextField()
                    }
                }
            else
                Column {
                    topBar()
                    picker()
                    slidersAndTextField()
                }
        },
        neutralButtonText = if (showDefault) stringResource(R.string.button_default) else null,
        onNeutral = onDefault,
        properties = DialogProperties(usePlatformDefaultWidth = !useWideLayout)
    )
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        ColorPickerDialog({}, -0x0f4488aa, "color name", true, {}, onConfirmed = {})
    }
}

// for some reason this is cut of while both previews are shown
@Preview(device = "spec:orientation=landscape,width=400dp,height=780dp")
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        ColorPickerDialog({}, -0x0f4488aa, "color name", true, {}, onConfirmed = {})
    }
}

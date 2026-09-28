// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.SliderDialog
import androidx.core.content.edit

@Suppress("UNCHECKED_CAST") // it's sort of checked
@Composable
/** Slider preference for Int or Float (weird casting stuff, but should be fine) */
fun <T: Number> SliderPreference(
    name: String,
    modifier: Modifier = Modifier,
    key: String,
    description: @Composable (T) -> String,
    default: T,
    range: ClosedFloatingPointRange<Float>,
    stepSize: Int? = null,
    onValueChanged: (Float?) -> Unit = { },
    live: Boolean = false, // the value is written while dragging (the live keyboard shows it); Cancel puts the old one back
    applyOnRelease: Boolean = false, // with [live]: written when the slider is let go
    onConfirmed: (T) -> Unit = { },
) {
    helium314.keyboard.settings.KnownDefaults.note(key, default)
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val initialValue = if (default is Int || default is Float)
        getPrefOfType(prefs, key, default)
    else throw IllegalArgumentException("only float and int are supported")

    var showDialog by rememberSaveable { mutableStateOf(false) }
    val hadValue = remember(showDialog) { prefs.contains(key) }
    @Suppress("UNCHECKED_CAST")
    fun write(value: Float) {
        if (live && !hadValue && value == initialValue.toFloat()) prefs.edit { remove(key) } // Cancel: unset stays unset
        else if (default is Int) prefs.edit { putInt(key, value.toInt()) }
        else prefs.edit { putFloat(key, value) }
        onConfirmed((if (default is Int) value.toInt() else value) as T)
    }
    Preference(
        name = name,
        onClick = { showDialog = true },
        modifier = modifier,
        description = description(initialValue)
    )
    if (showDialog)
        SliderDialog(
            onDismissRequest = { showDialog = false },
            onDone = {
                if (default is Int) {
                    prefs.edit { putInt(key, it.toInt()) }
                    onConfirmed(it.toInt() as T)
                } else {
                    prefs.edit { putFloat(key, it) }
                    onConfirmed(it as T)
                }
            },
            initialValue = initialValue.toFloat(),
            range = range,
            positionString = {
                @Suppress("UNCHECKED_CAST")
                description((if (default is Int) it.toInt() else it) as T)
            },
            onValueChanged = { if (live && it != null) write(it); onValueChanged(it) },
            showDefault = true,
            live = live,
            applyOnRelease = applyOnRelease,
            onDefault = { prefs.edit { remove(key) }; onConfirmed(default) },
            defaultValue = default.toFloat(),
            intermediateSteps = stepSize?.let {
                // this is not nice, but slider wants it like this...
                ((range.endInclusive - range.start) / it - 1).toInt()
            }
        )
}

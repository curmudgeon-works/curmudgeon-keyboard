// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import helium314.keyboard.settings.rememberPrefSnapshot
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.WithSmallTitle
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit

// actual key for each setting is baseKey with one _true/_false appended per dimension (need to keep order!)
// should dimension checkboxes have any other effect than just showing / hiding sliders?
//  one could argue that e.g. when disabling the split checkbox, then split mode should not affect the setting
/** One setting shown in a [KeyboardScalePreference] dialog: its sliders, one per variant, under [title]. */
class ScalePart(
    val title: String?,
    val baseKey: String,
    val defaults: Array<Float>,
    val range: ClosedFloatingPointRange<Float>,
    val description: (Float) -> String,
)

@Composable
fun KeyboardScalePreference(
    name: String,
    baseKey: String,
    dimensions: List<String>,
    defaults: Array<Float>,
    range:  ClosedFloatingPointRange<Float>,
    description: (Float) -> String,
    live: Boolean = false, // values are written while dragging (the live keyboard shows them); Cancel puts the old ones back
    alwaysShown: Set<String> = emptySet(), // dimensions without a checkbox: their sliders are always there (landscape always is)
    baseVariantName: String? = null, // the name of the plain slider (the one without any dimension); "Portrait" if null
    firstTitle: String? = null, // with [more]: the heading over this setting's sliders
    more: List<ScalePart> = emptyList(), // further settings in the same dialog (same dimensions)
    onDone: () -> Unit
) {
    val parts = listOf(ScalePart(firstTitle, baseKey, defaults, range, description)) + more
    parts.forEach {
        if (it.defaults.size != 1.shl(dimensions.size))
            throw ArithmeticException("defaults size does not match with dimensions, expected ${1.shl(dimensions.size)}, got ${it.defaults.size}")
    }
    var showDialog by remember { mutableStateOf(false) }
    Preference(
        name = name,
        onClick = { showDialog = true },
        // no description because it can easily take up too much space
    )
    if (showDialog)
        KeyboardScaleDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(name) },
            parts = parts,
            onDone = onDone,
            dimensions = dimensions,
            live = live,
            alwaysShown = alwaysShown + stringResource(R.string.landscape),
            baseVariantName = baseVariantName ?: stringResource(R.string.portrait),
        )
}

// SliderDialog specialized for keyboard scale settings: per setting ([parts]) one slider per variant (portrait,
// landscape, split, folded ...), each variant stored under its own key
@Composable
private fun KeyboardScaleDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    parts: List<ScalePart>,
    onDone: () -> Unit,
    dimensions: List<String>,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    alwaysShown: Set<String> = emptySet(),
    baseVariantName: String,
) {
    val variantsAndKeys = parts.map { createVariantsAndKeys(dimensions, it.baseKey) }
    val variants = variantsAndKeys.first().first
    val allKeys = variantsAndKeys.flatMap { it.second }
    val foldedString = stringResource(R.string.folded) // we want to hide foldable settings for non-foldable phones
    val ctx = LocalContext.current
    var checked by remember { mutableStateOf(dimensions.map { it in alwaysShown || FoldableUtils.isFoldable || !it.contains(foldedString) }) }
    val prefs = ctx.prefs()
    val done = remember { mutableMapOf<String, () -> Unit>() }
    // what the keys held when the dialog opened, so Cancel can put it back in live mode
    val snapshot = rememberPrefSnapshot(prefs, allKeys)
    var confirmed by remember { mutableStateOf(false) }
    fun write(key: String, value: Float?) = prefs.edit { if (value == null) remove(key) else putFloat(key, value) }
    val dismiss = {
        if (live && !confirmed && snapshot.restore()) onDone()
        onDismissRequest()
    }

    ThreeButtonAlertDialog(
        onDismissRequest = dismiss,
        onConfirmed = { confirmed = true; done.values.forEach { it.invoke() }; onDone() },
        modifier = modifier,
        title = title,
        keepKeyboard = live,
        content = {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge
            ) {
                val state = rememberScrollState()
                Column(Modifier.verticalScroll(state)) {
                    if (dimensions.size > 1) {
                        dimensions.forEachIndexed { i, dimension ->
                            // hide "folded" box for non-foldables, and no box for a dimension that is always shown
                            if (dimension !in alwaysShown && (FoldableUtils.isFoldable || !dimension.contains(foldedString)))
                                DimensionCheckbox(checked[i], dimension) {
                                    checked = checked.mapIndexed { j, c -> if (i == j) it else c }
                                }
                        }
                    }
                    parts.forEachIndexed { p, part ->
                        val keys = variantsAndKeys[p].second
                        if (parts.size > 1 && part.title != null)
                            Text(part.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = if (p == 0) 0.dp else 12.dp, bottom = 4.dp))
                        variants.forEachIndexed { i, variant ->
                            val key = keys[i]
                            val default = part.defaults[i]
                            var sliderPosition by remember(key) { mutableFloatStateOf(prefs.getFloat(key, default)) }
                            var touched by remember(key) { mutableStateOf(false) }
                            // live: written when the finger leaves the slider (or Default is tapped), not during the drag
                            fun applyLive() { if (live && touched) { write(key, if (sliderPosition == default) null else sliderPosition); onDone() } }
                            done[key] = {
                                if (sliderPosition == default) prefs.edit { remove(key) }
                                else prefs.edit { putFloat(key, sliderPosition) }
                            }
                            val forbiddenDimensions = dimensions.filterIndexed { index, _ -> !checked[index] }
                            val visible = variant.split(SPLIT).none { it in forbiddenDimensions }
                            // default animations make the dialog flash (see also DictionaryDialog)
                            AnimatedVisibility(visible, exit = fadeOut(), enter = fadeIn()) {
                                WithSmallTitle(variant.ifEmpty { baseVariantName }) {
                                    Slider(
                                        value = sliderPosition,
                                        onValueChange = { sliderPosition = it; touched = true },
                                        onValueChangeFinished = { applyLive() },
                                        valueRange = part.range,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(part.description(sliderPosition))
                                        TextButton({ sliderPosition = default; touched = true; applyLive() }) { Text(stringResource(R.string.button_default)) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun DimensionCheckbox(checked: Boolean, dimension: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange(it) }
        )
        Text(dimension)
    }
}

private fun createVariantsAndKeys(dimensions: List<String>, baseKey: String): Pair<List<String>, List<String>> {
    val variants = mutableListOf("")
    val keys = mutableListOf(createPrefKeyForBooleanSettings(baseKey, 0, dimensions.size))
    var i = 1
    dimensions.forEach { dimension ->
        variants.toList().forEach { variant ->
            if (variant.isEmpty()) variants.add(dimension)
            else variants.add(variant + SPLIT + dimension)
            keys.add(createPrefKeyForBooleanSettings(baseKey, i, dimensions.size))
            i++
        }
    }
    return variants to keys
}

private const val SPLIT = " / "

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        KeyboardScaleDialog(
            onDismissRequest = { },
            onDone = { },
            parts = listOf(ScalePart(null, "", Array(8) { 100f - it % 2 * 50f }, 0f..500f) { "${it.toInt()}%" }),
            title = { Text("bottom padding scale") },
            dimensions = listOf("landscape", "split", "folded"),
            baseVariantName = "Portrait",
        )
    }
}

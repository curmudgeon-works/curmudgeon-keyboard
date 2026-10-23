// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.contentTextDirectionStyle
import helium314.keyboard.settings.initPreview
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun LayoutEditDialog(
    onDismissRequest: () -> Unit,
    layoutType: LayoutType,
    initialLayoutName: String,
    startContent: String? = null,
    locale: Locale? = null,
    onEdited: (newLayoutName: String) -> Unit = { },
    // the keyboard being edited (the others using this layout are asked about), null: every keyboard is "other"
    keyboard: helium314.keyboard.latin.settings.SettingsSubtype? = null,
    isNameValid: ((String) -> Boolean)?
) {
    val ctx = LocalContext.current
    // saving changed keys of a named layout other keyboards use: which of them take the change (Overwrite / back to editing)
    var askOthers: Pair<String, List<helium314.keyboard.latin.settings.SettingsSubtype>>? by androidx.compose.runtime.remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    val startIsCustom = LayoutUtilsCustom.isCustomLayout(initialLayoutName)
    var displayNameValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(
            if (startIsCustom) LayoutUtilsCustom.getDisplayName(initialLayoutName)
            else initialLayoutName.getStringResourceOrName("layout_", ctx)
        ))
    }
    val nameValid = displayNameValue.text.isNotBlank()
            && (
                (startIsCustom && LayoutUtilsCustom.getLayoutName(displayNameValue.text, layoutType, locale) == initialLayoutName)
                || isNameValid?.let { it(LayoutUtilsCustom.getLayoutName(displayNameValue.text, layoutType, locale)) } == true
            )

    val startDisplayName = androidx.compose.runtime.remember { displayNameValue.text }
    fun write(text: String) {
            // a keyboard's unsaved layout, its name untouched: stays its own copy
            if (startIsCustom && LayoutUtilsCustom.isPrivateLayout(initialLayoutName) && displayNameValue.text == startDisplayName) {
                helium314.keyboard.latin.utils.AtomicFiles.write(LayoutUtilsCustom.getLayoutFile(initialLayoutName, layoutType, ctx), text)
                LayoutUtilsCustom.onLayoutFileChanged()
                onEdited(initialLayoutName)
                (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
                return
            }
            val newLayoutName = LayoutUtilsCustom.getLayoutName(displayNameValue.text, layoutType, locale)
            if (startIsCustom && initialLayoutName != newLayoutName) {
                LayoutUtilsCustom.getLayoutFile(initialLayoutName, layoutType, ctx).delete()
                SubtypeSettings.onRenameLayout(layoutType, initialLayoutName, newLayoutName, ctx)
            }
            helium314.keyboard.latin.utils.AtomicFiles.write(LayoutUtilsCustom.getLayoutFile(newLayoutName, layoutType, ctx), text)
            LayoutUtilsCustom.onLayoutFileChanged()
            onEdited(newLayoutName)
            (ctx.getActivity() as? SettingsActivity)?.prefChanged()
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }
    var pendingText by androidx.compose.runtime.remember { mutableStateOf("") }
    TextInputDialog(
        onDismissRequest = {
            errorJob?.cancel()
            if (askOthers == null) onDismissRequest() // (asking about the other keyboards: this stays underneath)
        },
        onConfirmed = { text ->
            // a named layout's keys changed while other keyboards use it: asked first
            val others = if (startIsCustom && !LayoutUtilsCustom.isPrivateLayout(initialLayoutName)
                    && LayoutUtilsCustom.getLayoutFile(initialLayoutName, layoutType, ctx).let { it.isFile && it.readText() != text })
                LayoutUtilsCustom.keyboardsUsing(ctx, layoutType, initialLayoutName, keyboard) else emptyList()
            if (others.isEmpty()) write(text)
            else { pendingText = text; askOthers = initialLayoutName to others }
        },
        confirmButtonText = stringResource(R.string.save),
        initialText = startContent ?: LayoutUtilsCustom.getLayoutFile(initialLayoutName, layoutType, ctx).readText(),
        singleLine = false,
        title = {
            if (isNameValid == null)
                Text(displayNameValue.text)
            else
                TextField(
                    value = displayNameValue,
                    onValueChange = { displayNameValue = it },
                    isError = !nameValid,
                    supportingText = { if (!nameValid) Text(stringResource(R.string.name_invalid)) },
                    trailingIcon = { if (!nameValid) CloseIcon(R.string.name_invalid) },
                    textStyle = contentTextDirectionStyle,
                )
        },
        checkTextValid = { text ->
            val valid = LayoutUtilsCustom.checkLayout(text, ctx)
            errorJob?.cancel()
            if (!valid) {
                errorJob = scope.launch {
                    val message = Log.getLog(10)
                        .lastOrNull { it.tag == "LayoutUtilsCustom" }?.message
                        ?.split("\n")?.take(2)?.joinToString("\n")
                    delay(3000)
                    Toast.makeText(ctx, ctx.getString(R.string.layout_error, message), Toast.LENGTH_LONG).show()
                }
            }
            valid && nameValid // don't allow saving with invalid name, but inform user about issues with layout content
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = false),
        modifier = Modifier.windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))
            .padding(horizontal = 16.dp), // dialog is rather wide, but shouldn't go all the way to the screen edges
        reducePadding = true,
    )
    askOthers?.let { (name, others) ->
        // the others as every dialog changing a saved item lists them (owner 2026-10-18: "select which each one takes,
        // else they retain their current setting named Warm 2"; "all should come selected yes"): the selected ones take
        // the new keys, the others keep their keys of now under the next free name, "My keys 2" (was a Yes / No
        // question for all of them, then a name to type)
        val shown = LayoutUtilsCustom.getDisplayName(name)
        val names = androidx.compose.runtime.remember(others) { helium314.keyboard.settings.screens.userNames(ctx, others) }
        val keeper = androidx.compose.runtime.remember(name) { LayoutUtilsCustom.keeperLayoutName(name, layoutType, ctx) }
        val selected = rememberAllSelected(names)
        ThreeButtonAlertDialog(
            onDismissRequest = { askOthers = null }, // back to editing
            title = { Text(stringResource(R.string.layout_overwrite_title, shown)) },
            scrollContent = true,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectOthersList(stringResource(R.string.layout_overwrite_select, LayoutUtilsCustom.getDisplayName(keeper)), names, selected)
                }
            },
            // (the button answers the title's question)
            confirmButtonText = stringResource(R.string.layout_overwrite_confirm),
            onConfirmed = {
                // the ones not selected keep their keys, under the keeper's name; then this keyboard's change is written
                val keeping = others.filterIndexed { i, _ -> i !in selected }
                if (keeping.isNotEmpty()) LayoutUtilsCustom.keepOldKeys(name, keeper, layoutType, keeping, ctx)
                write(pendingText)
                askOthers = null
                onDismissRequest()
            },
        )
    }
}

// the job is here (outside the composable to make sure old jobs are canceled
private var errorJob: Job? = null

@Preview
@Composable
private fun Preview() {
    val content = LocalContext.current.assets.open("layouts/main/dvorak.json").reader().readText()
    initPreview(LocalContext.current)
    Theme(previewDark) {
        LayoutEditDialog({}, LayoutType.MAIN, "qwerty", locale = Locale.ENGLISH, startContent = content) { true }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults.default
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.displayNameId
import helium314.keyboard.latin.utils.LayoutUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import org.json.JSONObject

/** The layouts edited here, one tab each: everything but the letters (own picker) and the symbols pages (popup editor). */
private val LAYOUT_FILE_TYPES = listOf(
    LayoutType.NUMBER_ROW, LayoutType.NUMBER, LayoutType.NUMPAD, LayoutType.NUMPAD_LANDSCAPE, LayoutType.PHONE,
    LayoutType.PHONE_SYMBOLS, LayoutType.MORE_SYMBOLS, LayoutType.FUNCTIONAL, LayoutType.EMOJI_BOTTOM, LayoutType.CLIPBOARD_BOTTOM,
)
private const val FILE_FORMAT = "curmudgeon-layouts"

/**
 * The secondary layouts in one editor, a tab each, saved to and loaded from one file. Saving writes each changed
 * layout as the user's own (one custom layout per kind) and makes it the default for all keyboards; a layout
 * edited back to the built-in one returns to the built-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutFilesScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val ownName = stringResource(R.string.layout_files_own_name)
    fun builtIn(type: LayoutType) = LayoutUtils.getContent(type, type.default, ctx)
    fun inUse(type: LayoutType): String {
        val name = Settings.readDefaultLayoutName(type, prefs)
        return if (!LayoutUtilsCustom.isCustomLayout(name)) LayoutUtils.getContent(type, name, ctx)
            else LayoutUtilsCustom.getLayoutFile(name, type, ctx).takeIf { it.isFile }?.readText() ?: builtIn(type)
    }
    val saved = remember { mutableStateMapOf<LayoutType, String>().apply { LAYOUT_FILE_TYPES.forEach { put(it, inUse(it)) } } }
    val texts = remember { mutableStateMapOf<LayoutType, String>().apply { putAll(saved) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val type = LAYOUT_FILE_TYPES[tab]
    // checked as typed; the tabs show which layouts are changed (•) or broken (!)
    val invalid = LAYOUT_FILE_TYPES.filter { t -> texts[t] != saved[t] && !LayoutUtilsCustom.checkLayout(texts[t]!!, ctx) }.toSet()
    val changed = LAYOUT_FILE_TYPES.filter { texts[it] != saved[it] }

    fun save() {
        for (t in changed) {
            val text = texts[t]!!
            val name = LayoutUtilsCustom.getLayoutName(ownName, t)
            if (text == builtIn(t)) {
                Settings.writeDefaultLayoutName(null, t, prefs)
                if (LayoutUtilsCustom.getLayoutFile(name, t, ctx).isFile) LayoutUtilsCustom.deleteLayout(name, t, ctx)
            } else {
                LayoutUtilsCustom.getLayoutFile(name, t, ctx).writeText(text)
                Settings.writeDefaultLayoutName(name, t, prefs)
            }
            saved[t] = text
        }
        LayoutUtilsCustom.onLayoutFileChanged()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
        // saved for good: Layout & Typing (this screen's parent) takes it as its new starting point
        helium314.keyboard.settings.LayoutDraft.rebaseOpen(ctx, setOf(Settings.PREF_LAYOUT_PREFIX + "*"),
            LAYOUT_FILE_TYPES.map { it.name.lowercase() }.toSet())
    }

    // (no files: layouts go in and out by copy and paste; the one file the app writes is the backup)

    // Built-in over a tab's own text asks first; leaving with unsaved tabs asks Save / Discard
    var askBuiltIn by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var askLeave by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var askReject by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var askAccept by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // cross: every tab back to its saved text (the current default for its type); tick: every changed tab saved
    if (askReject) helium314.keyboard.settings.dialogs.DiscardChangesDialog({ askReject = false }) { saved.forEach { (t, text) -> texts[t] = text } }
    if (askAccept) helium314.keyboard.settings.dialogs.SaveChangesDialog({ askAccept = false }) { if (invalid.isEmpty()) save() }
    fun leave() { if (changed.isNotEmpty()) askLeave = true else onClickBack() }
    androidx.activity.compose.BackHandler(enabled = changed.isNotEmpty()) { leave() }
    if (askBuiltIn) helium314.keyboard.settings.dialogs.ConfirmationDialog(
        onDismissRequest = { askBuiltIn = false },
        title = { Text(stringResource(R.string.layout_files_builtin)) },
        content = { Text(stringResource(R.string.layout_files_builtin_confirm)) },
        onConfirmed = { texts[type] = builtIn(type) },
    )
    if (askLeave) helium314.keyboard.settings.dialogs.UnsavedChangesDialog(
        onKeepWorking = { askLeave = false },
        onDiscardAndExit = { askLeave = false; onClickBack() },
        // a broken tab (!) can't be saved: back to the editor, where the tabs show it
        onSaveAndExit = { askLeave = false; if (invalid.isEmpty()) { save(); onClickBack() } },
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { TopAppBar(
            title = { Text(stringResource(R.string.layout_files)) },
            navigationIcon = { BackButton(::leave) },
            // cross and tick, like the other screens: only while a tab has changes; the tick waits for broken tabs (!)
            actions = { if (changed.isNotEmpty()) {
                androidx.compose.material3.IconButton({ askReject = true }) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_close), stringResource(R.string.appearance_reject)) }
                androidx.compose.material3.IconButton({ askAccept = true }, enabled = invalid.isEmpty()) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_check), stringResource(R.string.appearance_accept)) }
            } },
        ) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                LAYOUT_FILE_TYPES.forEachIndexed { i, t ->
                    val mark = if (t in invalid) " !" else if (t in changed) " •" else ""
                    Tab(selected = i == tab, onClick = { tab = i }, text = { Text(stringResource(t.displayNameId) + mark) })
                }
            }
            OutlinedTextField(
                value = texts[type]!!,
                onValueChange = { texts[type] = it },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                isError = type in invalid,
                supportingText = { if (type in invalid) Text(stringResource(R.string.layout_files_invalid)) },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                // (only asks when the tab's text would be lost: already the built-in one, nothing to do)
                TextButton(onClick = { if (texts[type] != builtIn(type)) askBuiltIn = true }) { Text(stringResource(R.string.layout_files_builtin)) }
            }
        }
    }
}

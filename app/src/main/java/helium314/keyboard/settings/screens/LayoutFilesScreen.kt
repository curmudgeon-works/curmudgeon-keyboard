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
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.displayNameId
import helium314.keyboard.latin.utils.LayoutUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.mainLayoutNameOrQwerty
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import org.json.JSONObject

/** The layout files screen's Save: the layouts stay, and Layout & Typing (the screen it was opened from) still holds
 *  them; its own Save or Discard decides them, with everything else done there (owner 2026-10-11, option 1: "every
 *  action is held until the screen's Save; Discard undoes it everywhere it reached"). Until then this rebased Layout &
 *  Typing's snapshot of every keyboard's definition and every "layout_" key, the saved Layouts among them: a popup set
 *  deleted there came back on its Discard, but the saved Layouts lost its name for good (same as review C2). */
internal fun layoutFilesSaved() {
    helium314.keyboard.latin.utils.SettingsEventLog.log("Layout files saved: held until Layout & Typing's Save")
    helium314.keyboard.settings.LayoutDraft.subScreenSavedOpen(helium314.keyboard.settings.LayoutDraft.SubScreen.LAYOUT_FILES) // (named in its Discard question)
}

/** The layouts edited here, one tab each: all of them. The letters first (owner 2026-10-13, JSON-L: the keyboard's one
 *  set of letter keys, as the Keys row also picks it); the symbols page too (JSON-S): the popup editor doesn't edit it,
 *  it only chooses one through its presets (e.g. Arabic's). */
private val LAYOUT_FILE_TYPES = listOf(
    LayoutType.MAIN, LayoutType.NUMBER_ROW, LayoutType.NUMBER, LayoutType.NUMPAD, LayoutType.NUMPAD_LANDSCAPE, LayoutType.PHONE,
    LayoutType.PHONE_SYMBOLS, LayoutType.SYMBOLS, LayoutType.MORE_SYMBOLS, LayoutType.FUNCTIONAL, LayoutType.EMOJI_BOTTOM, LayoutType.CLIPBOARD_BOTTOM,
)
private const val FILE_FORMAT = "curmudgeon-layouts"

/** The default letters layout of [locale]'s language: its first built-in keyboard's (QWERTY when it has none). */
internal fun languageDefaultLayout(locale: java.util.Locale): String =
    helium314.keyboard.latin.utils.SubtypeSettings.getResourceSubtypesForLocale(locale).firstOrNull()?.mainLayoutNameOrQwerty()
        ?: helium314.keyboard.latin.utils.SubtypeLocaleUtils.QWERTY

/** A tab's name: the letters' as the Keys row's (its own type name is "No language"). */
private val LayoutType.tabNameId get() = if (this == LayoutType.MAIN) R.string.layout_keys else displayNameId

/**
 * The layouts of one keyboard in one editor, a tab each: what that keyboard uses (its own pick, else its default; the
 * letters: its main layout, a built-in one with its language's extra keys). Saving changes that keyboard only: each
 * changed layout becomes its own unnamed copy, or the built-in one when edited back to it (2026-10-03: a layout is the
 * keyboard's, like its other settings; no shared file is written). The letters' built-in one is the language's default
 * letters layout (JSON-L), and their copy is scoped to the keyboard's language, as the Keys row's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutFilesScreen(keyboard: SettingsSubtype, onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    // what is there as the screen opens: what it changes from here is kept by its Save when the app is left (owner
    // 2026-10-11, S2: "yes let's keep saved subscreen changes")
    rememberSaveable { helium314.keyboard.settings.LayoutDraft.subScreenOpenedOpen(ctx); true }
    // the type's default built-in; the letters: the main language's default letters layout, with its extra keys, as the
    // Keys row's editor shows a built-in one (JSON-L); the symbols page: the one the keyboard's popup preset chose, e.g.
    // Arabic's (JSON-S2), as the screen opened
    val symbols = remember { presetSymbols(ctx, prefs, helium314.keyboard.latin.utils.SubtypeUtilsAdditional.current(keyboard.toPref()).toSettingsSubtype()) }
    fun builtInName(type: LayoutType) = when (type) {
        LayoutType.MAIN -> languageDefaultLayout(keyboard.locale)
        LayoutType.SYMBOLS -> symbols.layout ?: type.default
        else -> type.default
    }
    // the keyboard with [type]'s built-in: the symbols page stored as its preset stores it (none for the default one, so
    // the Default preset still matches, JSON-S2)
    fun withBuiltIn(kb: SettingsSubtype, type: LayoutType) =
        if (type == LayoutType.SYMBOLS && symbols.layout == null) kb.withoutLayout(type) else kb.withLayout(type, builtInName(type))
    fun builtInContent(type: LayoutType, name: String) =
        if (type == LayoutType.MAIN) LayoutUtils.getContentWithPlus(name, keyboard.locale, ctx) else LayoutUtils.getContent(type, name, ctx)
    fun builtIn(type: LayoutType) = builtInContent(type, builtInName(type))
    // the keyboard as it is now (a save here changes its definition)
    fun kb() = helium314.keyboard.latin.utils.SubtypeUtilsAdditional.current(keyboard.toPref()).toSettingsSubtype()
    // what this keyboard uses for [type]: its own pick (or its own copy), else its default (the letters: its main layout)
    fun usedName(type: LayoutType) = if (type == LayoutType.MAIN) kb().mainLayoutName() ?: builtInName(type)
        else kb().layoutName(type) ?: Settings.readDefaultLayoutName(type, prefs)
    fun inUse(type: LayoutType): String {
        val name = usedName(type)
        return if (!LayoutUtilsCustom.isCustomLayout(name)) builtInContent(type, name)
            else LayoutUtilsCustom.getLayoutFile(name, type, ctx).takeIf { it.isFile }?.readText() ?: builtIn(type)
    }
    // a copy's scope: the letters' is their language's (LayoutUtilsCustom.scopeFor), the other types have none
    fun scope(type: LayoutType) = if (type == LayoutType.MAIN) LayoutUtilsCustom.scopeFor(kb().locale) else ""
    val saved = remember { mutableStateMapOf<LayoutType, String>().apply { LAYOUT_FILE_TYPES.forEach { put(it, inUse(it)) } } }
    val texts = remember { mutableStateMapOf<LayoutType, String>().apply { putAll(saved) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val type = LAYOUT_FILE_TYPES[tab]
    // checked as typed; the tabs show which layouts are changed (•) or broken (!)
    val invalid = LAYOUT_FILE_TYPES.filter { t -> texts[t] != saved[t] && !LayoutUtilsCustom.checkLayout(texts[t]!!, ctx) }.toSet()
    val changed = LAYOUT_FILE_TYPES.filter { texts[it] != saved[it] }

    // saving changes this keyboard only: each changed type becomes its own unnamed copy (or the built-in keys, when
    // that's what the text is); no shared layout file is written or deleted, so no other keyboard changes
    fun save() {
        val before = kb()
        var after = before
        for (t in changed) {
            val text = texts[t]!!
            val current = after.layoutName(t)
            after = when {
                text == builtIn(t) -> withBuiltIn(after, t)
                // (its own copy, rewritten in place: of the letters, only one scoped to its language)
                current != null && LayoutUtilsCustom.isPrivateLayout(current) && LayoutUtilsCustom.scopeOf(current, t) == scope(t) -> {
                    helium314.keyboard.latin.utils.AtomicFiles.write(LayoutUtilsCustom.getLayoutFile(current, t, ctx), text); after }
                else -> after.withLayout(t, LayoutUtilsCustom.makePrivateLayout(text, t, scope(t), ctx))
            }
            saved[t] = text
        }
        if (after != before) helium314.keyboard.latin.utils.SubtypeUtilsAdditional.changeAdditionalSubtype(before, after, ctx)
        LayoutUtilsCustom.onLayoutFileChanged()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
        layoutFilesSaved()
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
        // (the letters: which built-in keys, for which language, JSON-L; the symbols page a preset chose: by the preset's
        // name, JSON-S2)
        content = { Text(when {
            type == LayoutType.MAIN -> stringResource(R.string.layout_files_builtin_confirm_letters,
                helium314.keyboard.latin.utils.SubtypeLocaleUtils.getLayoutDisplayNameInSystemLocale(builtInName(type), keyboard.locale),
                keyboard.locale.localizedDisplayName(ctx.resources))
            type == LayoutType.SYMBOLS && symbols.layout != null && symbols.presetName != null ->
                stringResource(R.string.layout_files_builtin_confirm_symbols, symbols.presetName!!)
            else -> stringResource(R.string.layout_files_builtin_confirm)
        }) },
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
                    Tab(selected = i == tab, onClick = { tab = i }, text = { Text(stringResource(t.tabNameId) + mark) })
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
                TextButton(onClick = { if (texts[type] != builtIn(type)) askBuiltIn = true }) { Text(stringResource(R.string.layout_files_builtin_button)) }
            }
        }
    }
}

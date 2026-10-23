// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.realPrefs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.keyboard.ColorSetting
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.default
import helium314.keyboard.latin.common.encodeBase36
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.contentTextDirectionStyle
import helium314.keyboard.settings.dialogs.ColorPickerDialog
import helium314.keyboard.latin.utils.previewDark
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The palette editor's "Save and exit" ([opened] the palette's name when the editor opened, [now] its name now): the
 *  change stays and the editor goes back to Appearance, which still holds it; Appearance's own Save or Discard decides
 *  it, with everything else done there (owner 2026-10-11, option 1: "every action is held until the screen's Save;
 *  Discard undoes it everywhere it reached"). Until then this rebased Appearance's snapshot, which took all it reaches:
 *  a theme deleted on Appearance stayed deleted after its Discard (review B1). */
internal fun paletteEditorSaved(opened: String, now: String) {
    helium314.keyboard.latin.utils.SettingsEventLog.log("Palette editor saved ($opened -> $now): held until Appearance's Save")
    helium314.keyboard.settings.AppearanceDraft.paletteSavedOpen(opened, now) // (named in its Discard question)
}

/** The other keyboards and the saved themes that use palette [openedName] (saved as [newName]), for the edit and delete
 *  questions (out of the screen for the tests): the keyboards whose own set chose it, enabled then switched-off, shown
 *  or hidden ([otherUsers]), the same ones with one set of settings or Appearance shared (owner 2026-10-12: "our saving
 *  policies should not be affected by the common/separate toggle at all."), then the saved themes. Palettes are one
 *  app-wide list (owner 2026-10-11), so whoever names it sees an edit. [prefs]: the editor's view (the keyboard edited
 *  is the draft's). */
internal fun paletteUsers(ctx: Context, @Suppress("UNUSED_PARAMETER") prefs: SharedPreferences, openedName: String, newName: String): Pair<List<helium314.keyboard.latin.settings.SettingsSubtype>, List<String>> {
    val real = ctx.realPrefs()
    val names = setOf(openedName, newName)
    val themes = helium314.keyboard.settings.Palettes.themesUsing(real, names)
    val editingId = helium314.keyboard.settings.PrefsDraft.currentSetId(ctx)
    val edited = helium314.keyboard.latin.settings.KeyboardProfiles.editingKeyboard(real, editingId)
    // (one that only reads the common settings' choice reads the edited set: not listed, it has the edit; one without
    // an id has no set to take the default or the old colors in (owner 2026-10-11: lookups never create records), so it
    // isn't either, review 2026-10-11 C8 as it stands since 2026-10-12. The switched-off ones whose own set chose it come
    // last: owner 2026-10-11, "<name> (switched off)")
    val keyboards = otherUsers(ctx, helium314.keyboard.settings.Palettes.slots, edited, helium314.keyboard.latin.settings.KeyboardProfiles.Group.APPEARANCE) { _, v -> v in names }
    return keyboards to themes
}

@Composable
fun ColorsScreen(
    isNight: Boolean,
    theme: String?,
    made: String? = null, // the palette was just made by "Add your own", these colors were selected before
    onClickBack: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    val themeName = theme ?: if (isNight) prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)!!
        else prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)!!
    var newThemeName by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(themeName)) }

    // Back with changes asks Keep working / Discard and exit / Save and exit (2026-10-10, it saved silently): every
    // change is live, so this is what the palette and the colors selections were when the editor opened; a palette
    // just made counts as a change, its Discard deletes it and selects the colors from before
    val prefKey = if (isNight) Settings.PREF_THEME_COLORS_NIGHT else Settings.PREF_THEME_COLORS
    val openedName = rememberSaveable { themeName }
    // what is there as the editor opens (a palette just made: before "Add your own" made it, see AddColorRow): what it
    // changes from here is kept by its Save when the app is left (owner 2026-10-11, S2: "yes let's keep saved subscreen
    // changes")
    rememberSaveable { if (made == null) helium314.keyboard.settings.AppearanceDraft.paletteEditorOpenedOpen(ctx); true }
    fun paletteKeys(name: String) = listOf(Settings.PREF_USER_COLORS_PREFIX + name, Settings.PREF_USER_MORE_COLORS_PREFIX + name,
        Settings.PREF_USER_ALL_COLORS_PREFIX + name)
    val opened = rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<helium314.keyboard.settings.PrefSnapshot, HashMap<String, Any?>>(
        save = { it.saved() },
        restore = { @Suppress("UNCHECKED_CAST") helium314.keyboard.settings.PrefSnapshot(prefs, it as Map<String, Any?>) },
    )) {
        val keys = paletteKeys(openedName) + Settings.PREF_THEME_COLORS + Settings.PREF_THEME_COLORS_NIGHT
        val now = prefs.all.let { all -> keys.associateWith { all[it] } }
        helium314.keyboard.settings.PrefSnapshot(prefs,
            if (made == null) now else now + paletteKeys(openedName).associateWith { null } + (prefKey to made))
    }
    // checked when it's asked, not taken from a composition: SearchScreen's back arrow and back gesture keep the
    // [leave] they got first (a local function reference compares equal whatever it captured), so a value read here
    // would stay what it was before the first change
    fun changed() = made != null || newThemeName.text != openedName || opened.changed()
    fun putBack() {
        // only what changed goes back: a keyboard following the shared colors choice keeps following it (writing an
        // unchanged value through the keyboard's view would make it its own, review 2026-10-07 finding 10)
        // a rename goes back first, everyone naming it following back (palettes are one app-wide list, owner 2026-10-11)
        if (newThemeName.text != openedName && !KeyboardTheme.renameUserColors(newThemeName.text, openedName, prefs))
            prefs.edit().apply { paletteKeys(newThemeName.text).forEach { remove(it) } }.apply()
        val all = prefs.all
        prefs.edit().apply {
            for (key in paletteKeys(openedName) + Settings.PREF_THEME_COLORS + Settings.PREF_THEME_COLORS_NIGHT) {
                val value = opened[key]
                if (all[key] == value) continue
                if (value == null) remove(key) else helium314.keyboard.latin.settings.KeyboardProfiles.put(this, key, value)
            }
        }.apply()
        helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }
    // leaving the settings with the editor open undoes its unsaved changes, whether Appearance's are kept or undone then
    // (owner 2026-10-14: "Sub-items should still discard as per usual?"); what its Save kept stays. The editor stays
    // open, on the palette as it was opened
    helium314.keyboard.settings.UndoUnsavedOnLeave("palette editor ($openedName)") {
        changed().also { if (it) { putBack(); newThemeName = TextFieldValue(openedName) } }
    }
    var askLeave by rememberSaveable { mutableStateOf(false) }
    fun leave() { if (changed()) askLeave = true else onClickBack() }
    var askUpdate by rememberSaveable { mutableStateOf(false) }
    /** Kept, and back to Appearance, whose Save or Discard decides it ([paletteEditorSaved]). */
    fun saveAndBack() {
        paletteEditorSaved(openedName, newThemeName.text)
        onClickBack()
    }
    fun paletteUsers() = helium314.keyboard.settings.screens.paletteUsers(ctx, prefs, openedName, newThemeName.text)

    // is there really no better way of only setting forceOpposite while the screen is shown (and not paused)?
    // lifecycle stuff is weird, there is no pause and similar when activity is paused
    DisposableEffect(isNight) {
        onDispose { // works on pressing back
            (ctx.getActivity() as? SettingsActivity)?.setForceTheme(null, null)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            (ctx.getActivity() as? SettingsActivity)?.setForceTheme(newThemeName.text, isNight)
        }
    }

    val moreColors = KeyboardTheme.readUserMoreColors(prefs, newThemeName.text)
    val userColors = KeyboardTheme.readUserColors(prefs, newThemeName.text)
    val shownColors = if (moreColors == 2) {
        val fallbackColors = KeyboardTheme.readUserColorTheme(
            prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)!!,
            prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS),
        userColors, ctx, isNight, null
        )
        val allColors = KeyboardTheme.readUserAllColors(prefs, newThemeName.text, fallbackColors)
        ColorType.entries.map {
            ColorSetting(it.name, null, allColors[it] ?: it.default())
        }
    } else {
        val toDisplay = colorPrefsAndResIds.map { (colorName, resId) ->
            val cs = userColors.firstOrNull { it.name == colorName } ?: ColorSetting(colorName, true, null)
            cs.displayName = stringResource(resId)
            cs
        }
        val colorsToHide = getColorPrefsToHideInitially(prefs)
        if (moreColors == 1) toDisplay
        else toDisplay.filter { it.color != null || it.name !in colorsToHide }
    }
    fun ColorSetting.displayColor() = if (auto == true) KeyboardTheme.determineUserColor(userColors, ctx, name, isNight)
        else color ?: KeyboardTheme.determineUserColor(userColors, ctx, name, isNight)

    var chosenColorString: String by rememberSaveable { mutableStateOf("") }
    val chosenColor = runCatching { Json.decodeFromString<ColorSetting?>(chosenColorString) }.getOrNull()
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        ctx.getActivity()?.contentResolver?.openOutputStream(uri)?.writer()?.use { it.write(getColorString(prefs, newThemeName.text)) }
    }
    // a try-it box at the bottom, like Appearance's: the keyboard shows the colors being edited (forced while this
    // screen is open) and comes up for a moment after each change
    val tryIt = remember { TryItState() }
    val scope = rememberCoroutineScope()
    val softKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val preview = remember { PreviewKeyboard(tryIt, scope, showIme = { softKeyboard?.show() }, reveal = { }) {
        focusManager.clearFocus(force = true); softKeyboard?.hide() } }
    val previewKeyboard = helium314.keyboard.latin.settings.KeyboardProfiles.editingKeyboard(ctx.realPrefs(),
        helium314.keyboard.latin.settings.KeyboardProfiles.editingId(ctx))
        ?: helium314.keyboard.latin.utils.SubtypeSettings.getSelectedSubtype(prefs).toSettingsSubtype()
    androidx.compose.material3.Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        bottomBar = { TryItBar(previewKeyboard, tryIt, onFocus = preview::onFocus, onUsed = preview::onUsed) },
    ) { inner ->
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(inner)) {
    CompositionLocalProvider(helium314.keyboard.settings.dialogs.LocalPreviewKeyboard provides preview) {
            SearchScreen(
                title = {
                    var nameValid by rememberSaveable { mutableStateOf(true) }
                    var nameField by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(newThemeName) }
                    TextField(
                        value = nameField,
                        onValueChange = {
                            nameValid = KeyboardTheme.renameUserColors(newThemeName.text, it.text, prefs)
                            if (nameValid) {
                                newThemeName = it
                                SettingsActivity.forceTheme = newThemeName.text
                            }
                            nameField = it
                        },
                        isError = !nameValid,
        //                supportingText = { if (!nameValid) Text(stringResource(R.string.name_invalid)) } // todo: this is cutting off bottom half of the actual text...
                        trailingIcon = { if (!nameValid) CloseIcon(R.string.name_invalid) },
                        singleLine = true,
                        textStyle = contentTextDirectionStyle,
                    )
                },
                menu = listOf(
                    stringResource(R.string.main_colors) to { KeyboardTheme.writeUserMoreColors(prefs, newThemeName.text, 0) },
                    stringResource(R.string.more_colors) to { KeyboardTheme.writeUserMoreColors(prefs, newThemeName.text, 1) },
                    stringResource(R.string.all_colors) to { KeyboardTheme.writeUserMoreColors(prefs, newThemeName.text, 2) },
                    stringResource(R.string.button_save_file) to {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE,"${newThemeName.text}.json")
                            .setType("application/json")
                        saveLauncher.launch(intent)
                    },
                    stringResource(R.string.copy_to_clipboard) to {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Curmudgeon theme", getColorString(prefs, newThemeName.text)))
                    },
                ),
                onClickBack = ::leave,
                filteredItems = { search ->
                    val result = shownColors.filter { color ->
                        color.displayName.split(" ", "_").any { it.startsWith(search, true) }
                    }
                    if (moreColors == 2) result.toMutableList<ColorSetting?>().apply { add(0, null) }
                    else result
                },
                itemContent = { colorSetting ->
                    if (colorSetting == null)
                        Text( // not a colorSetting, but still best done as part of the list
                            stringResource(R.string.all_colors_warning),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    else
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { chosenColorString = Json.encodeToString(colorSetting) }
                        ) {
                            Spacer(
                                modifier = Modifier
                                    .background(Color(colorSetting.displayColor()), shape = CircleShape)
                                    .size(50.dp)
                            )
                            Column(Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)) {
                                Text(colorSetting.displayName)
                                if (colorSetting.auto == true)
                                    CompositionLocalProvider(
                                        LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                                    ) {
                                        Text(stringResource(R.string.auto_user_color))
                                    }
                            }
                            if (colorSetting.auto != null)
                                Switch(colorSetting.auto, onCheckedChange = { checked ->
                                    val oldUserColors = KeyboardTheme.readUserColors(prefs, newThemeName.text)
                                    val newUserColors = (oldUserColors + ColorSetting(colorSetting.name, checked, colorSetting.color))
                                        .reversed().distinctBy { it.displayName }
                                    KeyboardTheme.writeUserColors(prefs, newThemeName.text, newUserColors)
                                    preview.changed(emoji = false)
                                })
                        }
                }
            )
    }
    }
    }
    if (askLeave) helium314.keyboard.settings.dialogs.UnsavedChangesDialog(
        onKeepWorking = { askLeave = false },
        onDiscardAndExit = { askLeave = false; putBack(); onClickBack() },
        // kept and back: Appearance's Save or Discard decides it (owner 2026-10-11, see paletteEditorSaved)
        onSaveAndExit = {
            askLeave = false
            // other keyboards or saved themes using it: which get the new colors (2026-10-11), else saved at once
            if (made == null && paletteUsers().let { it.first.isNotEmpty() || it.second.isNotEmpty() }) askUpdate = true
            else saveAndBack()
        },
    )
    if (askUpdate) {
        val (keyboards, themes) = remember { paletteUsers() }
        val keeper = remember { helium314.keyboard.settings.Palettes.keeperName(ctx.realPrefs(), newThemeName.text) }
        // each other keyboard and saved theme using it: selected, the new colors; else the old ones as "Warm 2" (owner
        // 2026-10-18: "select which each one takes, else they retain their current setting named Warm 2")
        val common = remember { commonFrom(ctx, helium314.keyboard.latin.settings.KeyboardProfiles.Group.APPEARANCE) }
        helium314.keyboard.settings.dialogs.PaletteEditDialog(newThemeName.text, keeper,
            updateNames(ctx, keyboards, common) + themes.map { ctx.getString(R.string.update_item_theme, it) },
            onDismissRequest = { askUpdate = false }, commonFrom = common, anyHidden = keyboards.isNotEmpty(),
        ) { choices ->
            val old = helium314.keyboard.settings.Palettes.prefixes.mapNotNull { p -> opened[p + openedName]?.let { p to it } }.toMap()
            helium314.keyboard.settings.Palettes.afterEdit(ctx.realPrefs(), newThemeName.text, old,
                keyboards.indices.associate { keyboards[it] to choices[it] },
                themes.indices.associate { themes[it] to choices[it + keyboards.size] })
            askUpdate = false
            saveAndBack()
        }
    }
    if (chosenColor != null) CompositionLocalProvider(helium314.keyboard.settings.dialogs.LocalPreviewKeyboard provides preview) {
        val oldAllColors = KeyboardTheme.readUserAllColors(prefs, newThemeName.text, null)
        // the color shows on the keyboard while it's picked; Cancel puts back what was there
        val opened = helium314.keyboard.settings.rememberPrefSnapshot(prefs, listOf(Settings.PREF_USER_COLORS_PREFIX + newThemeName.text,
            Settings.PREF_USER_ALL_COLORS_PREFIX + newThemeName.text), chosenColorString)
        var confirmed by remember(chosenColorString) { mutableStateOf(false) }
        fun write(color: Int) {
            if (moreColors == 2) {
                val all = KeyboardTheme.readUserAllColors(prefs, newThemeName.text, null)
                all[ColorType.valueOf(chosenColor.name)] = color
                KeyboardTheme.writeUserAllColors(prefs, newThemeName.text, all)
            } else {
                val oldUserColors = KeyboardTheme.readUserColors(prefs, newThemeName.text)
                val newUserColors = (oldUserColors + ColorSetting(chosenColor.name, false, color))
                    .reversed().distinctBy { it.displayName }
                KeyboardTheme.writeUserColors(prefs, newThemeName.text, newUserColors)
            }
            helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
        ColorPickerDialog(
            onDismissRequest = {
                if (!confirmed && opened.restore()) helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
                chosenColorString = ""
            },
            onPreview = { write(it) },
            initialColor = chosenColor.displayColor(),
            title = chosenColor.displayName,
            showDefault = moreColors == 2 && oldAllColors.contains(ColorType.valueOf(chosenColor.name)),
            onDefault = {
                // clear the color
                confirmed = true
                oldAllColors.remove(ColorType.valueOf(chosenColor.name))
                KeyboardTheme.writeUserAllColors(prefs, newThemeName.text, oldAllColors)
                preview.changed(emoji = false)
            }
        ) { color ->
            confirmed = true
            write(color)
            preview.changed(emoji = false) // the new color on the keyboard for a moment
        }
    }
}

private fun getColorString(prefs: SharedPreferences, themeName: String): String {
    val moreColors = KeyboardTheme.readUserMoreColors(prefs, themeName)
    if (moreColors == 2) {
        val colors = KeyboardTheme.readUserAllColors(prefs, themeName, null).map { it.key.name to it.value }
        return Json.encodeToString((colors + (encodeBase36(themeName) to 0)).toMap()) // put theme name in here too
    }
    val colors = KeyboardTheme.readUserColors(prefs, themeName).associate { it.name to (it.color to (it.auto == true)) }
    return Json.encodeToString(SaveThoseColors(themeName, moreColors, colors))
}

@Serializable
data class SaveThoseColors(val name: String? = null, val moreColors: Int, val colors: Map<String, Pair<Int?, Boolean>>)

val colorPrefsAndResIds = listOf(
    KeyboardTheme.COLOR_BACKGROUND to R.string.select_color_background,
    KeyboardTheme.COLOR_KEYS to R.string.select_color_key_background,
    KeyboardTheme.COLOR_FUNCTIONAL_KEYS to R.string.select_color_functional_key_background,
    KeyboardTheme.COLOR_SPACEBAR to R.string.select_color_spacebar_background,
    KeyboardTheme.COLOR_TEXT to R.string.select_color_key,
    KeyboardTheme.COLOR_HINT_TEXT to R.string.select_color_key_hint,
    KeyboardTheme.COLOR_SUGGESTION_TEXT to R.string.select_color_suggestion,
    KeyboardTheme.COLOR_SPACEBAR_TEXT to R.string.select_color_spacebar_text,
    KeyboardTheme.COLOR_ACCENT to R.string.select_color_accent,
    KeyboardTheme.COLOR_GESTURE to R.string.select_color_gesture,
)

private fun getColorPrefsToHideInitially(prefs: SharedPreferences): List<String> {
    return listOf(KeyboardTheme.COLOR_SUGGESTION_TEXT, KeyboardTheme.COLOR_SPACEBAR_TEXT, KeyboardTheme.COLOR_GESTURE) +
            if (prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)) listOf(KeyboardTheme.COLOR_SPACEBAR_TEXT)
            else listOf(KeyboardTheme.COLOR_FUNCTIONAL_KEYS)
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            ColorsScreen(false, null) { }
        }
    }
}

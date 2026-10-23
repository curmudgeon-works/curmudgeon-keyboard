// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AdvancedTint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.settings.SettingsDestination
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import helium314.keyboard.latin.settings.SettingsSubtype
import android.content.Context
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.gesture.OwnGestureDecoder
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.previewDark

@Composable
fun MainSettingsScreen(
    keyboard: SettingsSubtype,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = keyboardName(keyboard, ctx),
        settings = emptyList(),
        showKeyboardName = false, // (the title is its name)
        // its search finds its own menu entries only; every setting only from the first screen (owner 2026-10-13: "Keyboard
        // screen search bar should only search items in that screen too", "Whole app search should only be from the
        // main screen of Curmedgeon Keyboard Settings")
        searchSettings = remember(keyboard, advanced) { keyboardMenuSearch(ctx, keyboard) },
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                KeyboardSettingsEntries(keyboard,
                    showAdvanced = !helium314.keyboard.latin.settings.KeyboardProfiles.isSeparate(LocalContext.current.realPrefs()))
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen(SettingsSubtype(java.util.Locale.ENGLISH, "")) {}
        }
    }
}

/** "English (US) + Hinglish" */
/** The name the user gave [keyboard] (long-press > Rename), or null. */
fun customKeyboardName(keyboard: SettingsSubtype): String? =
    keyboard.getExtraValueOf(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_NAME)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { it.isNotBlank() }

/** [keyboard] named [name] (blank: its own name taken off, back to its languages). */
fun withKeyboardName(keyboard: SettingsSubtype, name: String): SettingsSubtype {
    val key = helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_NAME
    return if (name.isBlank()) keyboard.without(key) else keyboard.with(key, java.net.URLEncoder.encode(name.trim(), "UTF-8"))
}

fun keyboardName(keyboard: SettingsSubtype, ctx: Context): String =
    customKeyboardName(keyboard) ?: (listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)).joinToString(" + ") { it.localizedDisplayName(ctx.resources) } +
        (keyboard.getExtraValueOf(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_COPY)?.let { " ($it)" } ?: "")

/** [keyboards] as the update and delete questions name them: a switched-off one "<name> (switched off)". */
fun userNames(ctx: Context, keyboards: List<SettingsSubtype>): List<String> {
    val enabled = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
    return keyboards.map { kb -> if (enabled.any { it.sameKeyboard(kb) }) keyboardName(kb, ctx) else ctx.getString(R.string.keyboard_switched_off, keyboardName(kb, ctx)) }
}

/** The first keyboard in the list, whose settings are the common ones, by name, when [group]'s settings are common to
 *  all keyboards (separate settings off, or the menu shared) and there is more than one keyboard (switched-off ones
 *  counting); else null. The update questions then say "the common settings" instead of "this keyboard", and mark the
 *  keyboards whose own sets the change goes into, as the restore's note on common menus does (owner 2026-10-18: "yes,
 *  makes sense to follow established pattern from restore"). */
fun commonFrom(ctx: Context, group: helium314.keyboard.latin.settings.KeyboardProfiles.Group): String? {
    val real = ctx.realPrefs()
    if (helium314.keyboard.latin.settings.KeyboardProfiles.isSeparate(real) && !helium314.keyboard.latin.settings.KeyboardProfiles.isShared(real, group)) return null
    if (helium314.keyboard.latin.settings.KeyboardIds.phoneKeyboards(real).size < 2) return null
    return keyboardName(helium314.keyboard.latin.settings.KeyboardProfiles.mainKeyboard(real), ctx)
}


/** The keyboards, in the Keyboards screen's order, that use a saved item (a Layout, theme, popup set or font) through
 *  [keys] ([helium314.keyboard.latin.settings.KeyboardProfiles.keyboardsUsing]), then the switched-off ones
 *  ([switchedOffUsing]): the ones its delete warning names. */
fun keyboardsUsing(ctx: Context, keys: Collection<String>, edited: SettingsSubtype? = null,
                   matches: (key: String, value: String?) -> Boolean): List<SettingsSubtype> {
    // [edited]: the keyboard the screen edits, which reads the set being edited
    val editingId = helium314.keyboard.latin.settings.KeyboardProfiles.editingId(ctx)
    return helium314.keyboard.latin.settings.KeyboardProfiles.keyboardsUsing(ctx.realPrefs(),
        SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }, keys,
        edited?.takeIf { editingId != helium314.keyboard.latin.settings.KeyboardProfiles.SHARED }?.let { it to editingId }, matches) +
        switchedOffUsing(ctx, keys, edited, matches)
}

/** The keyboards switched off on the Languages screen (an id of their own, not enabled) whose own set chose a saved
 *  item through [keys], whether or not separate settings is on (owner 2026-10-11: switched-off keyboards are asked
 *  too): their own set's choice only, one following the shared choice reads what the enabled ones read. Not the set
 *  being edited, nor [edited]. */
fun switchedOffUsing(ctx: Context, keys: Collection<String>, edited: SettingsSubtype? = null,
                     matches: (key: String, value: String?) -> Boolean): List<SettingsSubtype> {
    val real = ctx.realPrefs()
    val editingId = helium314.keyboard.latin.settings.KeyboardProfiles.editingId(ctx)
    // (which keyboards are switched off: the one helper, [SwitchedOffKeyboards.list]; 2026-10-11)
    val off = helium314.keyboard.latin.settings.SwitchedOffKeyboards.list(real)
    return off.mapNotNull { keyboard ->
        val id = keyboard.keyboardId ?: return@mapNotNull null
        if (id == editingId || keyboard.sameKeyboard(edited)) return@mapNotNull null
        keyboard.takeIf { keys.any { key -> val own = helium314.keyboard.latin.settings.KeyboardProfiles.ownKey(id, key)
            real.contains(own) && matches(key, real.getString(own, null)) } }
    }
}

/** The other keyboards whose own set chose a saved item through [keys] ([matches]), for the update questions' ticks
 *  (palettes, themes, Layouts, popup sets): the enabled ones in the Keyboards screen's order, then the switched-off ones
 *  (owner 2026-10-11: they are asked too), each by what its own stored set names, shown or hidden
 *  ([helium314.keyboard.latin.settings.KeyboardProfiles.ownSetUsers]). The same ones whatever separate settings and
 *  [group]'s sharing (owner 2026-10-12: "our saving policies should not be affected by the common/separate toggle at
 *  all."; until then a shared menu asked none and one set of settings only the switched-off ones). Not the set being
 *  edited nor [edited]; one that reads the item only through the common settings reads the edited set: not asked.
 *  [group]: not read (kept for the callers, the menu the item is on). */
fun otherUsers(ctx: Context, keys: Collection<String>, edited: SettingsSubtype?,
               @Suppress("UNUSED_PARAMETER") group: helium314.keyboard.latin.settings.KeyboardProfiles.Group,
               matches: (key: String, value: String?) -> Boolean): List<SettingsSubtype> {
    val real = ctx.realPrefs()
    val enabled = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
    // (which keyboards are switched off: the one helper, [SwitchedOffKeyboards.list]; 2026-10-11)
    val off = helium314.keyboard.latin.settings.SwitchedOffKeyboards.list(real).filter { kb -> enabled.none { it.sameKeyboard(kb) } }
    return helium314.keyboard.latin.settings.KeyboardProfiles.ownSetUsers(real, enabled + off, keys,
        helium314.keyboard.latin.settings.KeyboardProfiles.editingId(ctx), edited, matches)
}

/** [otherUsers] of the saved item [name] chosen under [key]. */
fun otherUsers(ctx: Context, key: String, name: String, edited: SettingsSubtype?,
               group: helium314.keyboard.latin.settings.KeyboardProfiles.Group): List<SettingsSubtype> =
    otherUsers(ctx, listOf(key), edited, group) { _, value -> value == name }

/** A delete warning's line: plural [id] with the keyboards' [names] one per line (never "all keyboards", 2026-10-08);
 *  null when no keyboard uses the item (the title alone). */
fun keepersText(ctx: Context, @androidx.annotation.PluralsRes id: Int, keyboards: List<SettingsSubtype>,
                names: List<String> = keyboards.map { keyboardName(it, ctx) }): String? =
    if (keyboards.isEmpty()) null
    else ctx.resources.getQuantityString(id, keyboards.size, names.joinToString("\n"))

/** The delete warning of a saved item chosen by name under [key] (Layout, theme, popup set): the keyboards whose own
 *  set chose it ([keyboardsUsing]), then the line on the common settings ([deleteWarningNames]). */
fun deleteKeepersText(ctx: Context, @androidx.annotation.PluralsRes id: Int, key: String, name: String,
                      edited: SettingsSubtype? = null): String? {
    val keyboards = keyboardsUsing(ctx, listOf(key), edited) { _, value -> value == name }
    val (names, note) = deleteWarningNames(ctx, keyboards, listOf(key))
    return keepersText(ctx, id, keyboards, names)?.let { listOfNotNull(it, note).joinToString("\n\n") }
}

/** The first keyboard in the list by name when a saved item chosen through [keys] is read from the common settings
 *  (separate settings off, or its menu shared: [helium314.keyboard.latin.settings.KeyboardProfiles.isCommon]) and
 *  there is more than one keyboard (switched-off ones counting); else null. [commonFrom] by the item's keys. */
fun commonFromKeys(ctx: Context, keys: Collection<String>): String? {
    val real = ctx.realPrefs()
    if (keys.none { helium314.keyboard.latin.settings.KeyboardProfiles.isCommon(real, it) }) return null
    if (helium314.keyboard.latin.settings.KeyboardIds.phoneKeyboards(real).size < 2) return null
    return keyboardName(helium314.keyboard.latin.settings.KeyboardProfiles.mainKeyboard(real), ctx)
}

/**
 * A delete warning's [keyboards] by name ([userNames]) and the line under them (owner 2026-10-18, review SP10: "Only
 * show those that actually go back to default (as they would with separate settings). But should clarify as per usual
 * pattern that keyboards are using common settings of primary."). With the item's [keys] read from the common settings
 * ([commonFromKeys]) the line is the update questions' ([HiddenSetsNote]'s) when one of [throughSet] but the first
 * keyboard (whose own set is the common one) is listed, else its first sentence: every keyboard follows the first one.
 * No "(hidden)" marks (owner 2026-10-18: "Don't mention '(hidden)' in parentheses, that seems confusing. Broader
 * messaging on common settings should address this"). No line with separate settings, nor when none is listed through
 * its set. [throughSet]: the ones of [keyboards] listed by their own set's choice (not by a layout picked in their
 * definition, whose settings are never hidden).
 */
fun deleteWarningNames(ctx: Context, keyboards: List<SettingsSubtype>, keys: Collection<String>,
                       throughSet: List<SettingsSubtype> = keyboards): Pair<List<String>, String?> {
    val names = userNames(ctx, keyboards)
    val viaSet = keyboards.filter { kb -> throughSet.any { it.sameKeyboard(kb) } }
    val common = viaSet.takeIf { it.isNotEmpty() }?.let { commonFromKeys(ctx, keys) } ?: return names to null
    val first = helium314.keyboard.latin.settings.KeyboardProfiles.mainKeyboard(ctx.realPrefs())
    val note = if (viaSet.any { !it.sameKeyboard(first) }) ctx.getString(R.string.update_item_hidden_note, common)
        else ctx.getString(R.string.delete_common_note, common)
    return names to note
}

/** [KeyboardSettingsEntries] of the keyboard's own screen, one search result each ([KeyboardSettingsEntries]' only);
 *  App settings only where the screen shows it (not with separate settings). */
internal fun keyboardMenuSearch(ctx: Context, keyboard: SettingsSubtype): List<Setting> = listOf(
    "languages" to R.string.languages_title, "layout" to R.string.settings_screen_preferences,
    "appearance" to R.string.settings_screen_appearance, "swipe" to R.string.swipe_screen,
    "correction" to R.string.settings_screen_correction, "advanced" to R.string.settings_screen_advanced,
).filter { (entry, _) -> entry != "advanced" || !helium314.keyboard.latin.settings.KeyboardProfiles.isSeparate(ctx.realPrefs()) }
    .map { (entry, title) -> Setting(ctx, "keyboard_menu_$entry", title) {
    KeyboardSettingsEntries(keyboard, showAdvanced = !helium314.keyboard.latin.settings.KeyboardProfiles.isSeparate(LocalContext.current.realPrefs()), only = entry)
} }

/** The sections of one keyboard's settings, as menu entries. Used by the keyboard's own screen and inline under the keyboards list.
 *  [setId]: the keyboard set the opened screens edit, asked at the tap (null: this screen's own, see KeyboardScopeContext). */
@Composable
fun KeyboardSettingsEntries(keyboard: SettingsSubtype, modifier: Modifier = Modifier, showLanguages: Boolean = true,
    showAdvanced: Boolean = true, setId: () -> String? = { null },
    // which menus (with separate settings: a keyboard's own ones, or the shared ones once below the keyboards); null: all
    groups: ((helium314.keyboard.latin.settings.KeyboardProfiles.Group) -> Boolean)? = null, showRefine: Boolean = false,
    // one entry only (the keyboard screen's search result, [keyboardMenuSearch]); null: all
    only: String? = null) {
    fun shows(group: helium314.keyboard.latin.settings.KeyboardProfiles.Group) = groups?.invoke(group) ?: true
    fun shows(entry: String) = only == null || only == entry
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    // the screen opens with the keyboard in its route: it edits that keyboard for as long as it is there
    fun open(route: String) = setId()?.let { SettingsDestination.navigateTo(SettingsDestination.inSet(route, it)) }
        ?: SettingsDestination.navigateTo(route, ctx)
    Column(modifier) {
    // this keyboard's languages, layout, dictionaries, popup order ...
    if (showLanguages && shows("languages")) Preference(
        name = stringResource(R.string.languages_title),
        description = keyboardName(keyboard, ctx),
        onClick = { open(SettingsDestination.withKeyboard(SettingsDestination.Languages, keyboard)) },
        icon = R.drawable.ic_settings_languages
    ) { NextScreenIcon() }
    // the keyboard's input settings, popups, number row and hints, layout (on the main screen: the keyboard in use);
    // named Preferences since the Preferences screen's groups moved in
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.LAYOUT) && shows("layout")) Preference(
        name = stringResource(R.string.settings_screen_preferences),
        onClick = { open(SettingsDestination.withKeyboard(SettingsDestination.Subtype, keyboard)) },
        icon = R.drawable.ic_settings_layout // the keyboard (owner 2026-10-14: "Layout and typing should use the keyboard")
    ) { NextScreenIcon() }
    // (no Preferences: its input and clipboard history groups are on the Layout screen)
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.APPEARANCE) && shows("appearance")) Preference(
        name = stringResource(R.string.settings_screen_appearance),
        onClick = { open(SettingsDestination.Appearance) },
        icon = R.drawable.ic_settings_appearance
    ) { NextScreenIcon() }
    // gesture typing, the swipe extras and the tuning in one screen
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.SWIPE) && shows("swipe")) Preference(
            name = stringResource(R.string.swipe_screen),
            onClick = { open(SettingsDestination.withKeyboard(SettingsDestination.SwipeTuning, keyboard)) },
            icon = R.drawable.ic_settings_gesture
        ) { NextScreenIcon() }
    // (the toolbar is a group on Layout & Typing)
    if (shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.TEXT_CORRECTION) && shows("correction")) Preference(
        name = stringResource(R.string.settings_screen_correction),
        onClick = { open(SettingsDestination.TextCorrection) },
        icon = R.drawable.ic_settings_correction
    ) { NextScreenIcon() }
    // (no Dictionaries: tapping a language in the keyboard's Languages list manages its dictionaries)
    // the settings few need (advanced only): Advanced; app-wide, so with separate settings it's one entry outside the
    // keyboards (AdvancedEntry), not one per keyboard
    // "Fine-tune suggestions" when the keyboard keeps its own (advanced only)
    if (showRefine && only == null) RefineEntry(setId)
    // "Fine-tune learning" under a keyboard that keeps it to itself (with separate settings: [groups] picks this
    // keyboard's own menus); shared, it is once in [AdvancedEntry]
    val real = ctx.realPrefs()
    if (groups != null && only == null && shows(helium314.keyboard.latin.settings.KeyboardProfiles.Group.LEARNING)
            && !helium314.keyboard.latin.settings.KeyboardProfiles.isShared(real, helium314.keyboard.latin.settings.KeyboardProfiles.Group.LEARNING))
        LearningEntry(setId)
    if (showAdvanced && shows("advanced")) AdvancedEntry(setId)
    }
}

/** "Fine-tune suggestions" (advanced only): once for all keyboards, or under each keyboard when it keeps its own. */
@Composable
fun RefineEntry(setId: () -> String? = { null }) {
    if (!helium314.keyboard.settings.REFINE_MENU_SHOWN) return // (not in the public build yet)
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    AdvancedTint(advanced) {
        Preference(
            name = stringResource(R.string.learning_swiping_screen),
            onClick = { setId()?.let { SettingsDestination.navigateTo(SettingsDestination.inSet(SettingsDestination.LearningSwiping, it)) }
                ?: SettingsDestination.navigateTo(SettingsDestination.LearningSwiping, ctx) },
            icon = R.drawable.ic_settings_refine // "tune" (2026-10-06): its own icon, not Swiping's
        ) { NextScreenIcon() }
    }
}

/** "Fine-tune learning" (advanced only, 2026-10-10): once for all keyboards, or under each keyboard when it keeps its own. */
@Composable
fun LearningEntry(setId: () -> String? = { null }) {
    if (!helium314.keyboard.settings.REFINE_MENU_SHOWN) return // (not in the public build yet, like Fine-tune suggestions)
    val ctx = LocalContext.current
    val advanced by SettingsMode.state(ctx)
    AdvancedTint(advanced) {
        Preference(
            name = stringResource(R.string.fine_tune_learning_screen),
            onClick = { setId()?.let { SettingsDestination.navigateTo(SettingsDestination.inSet(SettingsDestination.FineTuneLearning, it)) }
                ?: SettingsDestination.navigateTo(SettingsDestination.FineTuneLearning, ctx) },
            icon = R.drawable.ic_settings_learning // "school"
        ) { NextScreenIcon() }
    }
}

/** The entries the same for every keyboard: "Fine-tune suggestions" and "Fine-tune learning" (advanced only, each when
 *  shared) and the app's own settings (App settings, once named Advanced; shown in simple mode too since 2026-10-06). */
@Composable
fun AdvancedEntry(setId: () -> String? = { null }, showRefine: Boolean = true) {
    if (showRefine) RefineEntry(setId)
    val ctx = LocalContext.current
    // (Fine-tune learning decides by its own group, so the callers' showRefine is about Fine-tune suggestions only)
    val real = ctx.realPrefs()
    if (!helium314.keyboard.latin.settings.KeyboardProfiles.isSeparate(real)
            || helium314.keyboard.latin.settings.KeyboardProfiles.isShared(real, helium314.keyboard.latin.settings.KeyboardProfiles.Group.LEARNING))
        LearningEntry(setId)
    Preference(
        name = stringResource(R.string.settings_screen_advanced),
        onClick = { setId()?.let { SettingsDestination.navigateTo(SettingsDestination.inSet(SettingsDestination.Advanced, it)) }
            ?: SettingsDestination.navigateTo(SettingsDestination.Advanced, ctx) },
        icon = R.drawable.ic_settings_advanced
    ) { NextScreenIcon() }
}

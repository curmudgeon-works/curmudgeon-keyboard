// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.settings.screens.createAboutSettings
import helium314.keyboard.settings.screens.createAdvancedSettings
import helium314.keyboard.settings.screens.createAppearanceSettings
import helium314.keyboard.settings.screens.createCorrectionSettings
import helium314.keyboard.settings.screens.createGestureTypingSettings
import helium314.keyboard.settings.screens.createLayoutSettings
import helium314.keyboard.settings.screens.createPreferencesSettings
import helium314.keyboard.settings.screens.createToolbarSettings
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.KeyboardProfiles.Group as Menu
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.screens.aboutItems
import helium314.keyboard.settings.screens.appSettingsItems
import helium314.keyboard.settings.screens.appearanceItems
import helium314.keyboard.settings.screens.correctionItems
import helium314.keyboard.settings.screens.fineTuneLearningKeys
import helium314.keyboard.settings.screens.fineTuneSuggestionsKeys
import helium314.keyboard.settings.screens.layoutScreenKeys
import helium314.keyboard.settings.screens.swipeScreenKeys

class SettingsContainer(context: Context) {
    private val list = createSettings(context)
    private val map: Map<String, Setting> = HashMap<String, Setting>(list.size).apply {
        list.forEach {
            if (put(it.key, it) != null)
                throw IllegalArgumentException("key $it added twice")
        }
    }

    operator fun get(key: Any): Setting? = map[key]

    /** The main screen's search: every setting some screen shows now ([shown]), not every setting there is. Owner
     *  2026-10-14: "in main home screen, searching for "Color" with the toggle off also shows "Colors (dark)" -- wrong
     *  behavior"; "same thing happens with the separate settings toggle off". [ctx]: the main screen's, so the
     *  conditions are read from the settings its results edit (KeyboardProfiles.mainEditingId). */
    fun filter(searchTerm: String, ctx: Context): List<Setting> = SettingsSearch.search(searchTerm, shown(ctx))

    /** Every setting on some screen's current list ([shownScreenLists]), as that screen's own search finds it
     *  ([screenSettings]: not what no screen shows, [hiddenFromSearch], nor what the screen's menu leaves to another). */
    fun shown(ctx: Context): List<Setting> {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        return shownScreenLists(ctx).flatMap { screenSettings(it.keys, real, it.menu) { key -> map[key] } }.distinctBy { it.key }
    }
}

/** A screen's items as it shows them now (keys, headings, nulls), and the menu it is on (null: none). */
class ScreenList(val keys: List<Any?>, val menu: Menu?)

/**
 * What each screen with settings shows now, read from [ctx]'s settings: the lists their own searches use (each screen
 * draws from the same function), for the main screen's search ("draw each screen from its contents", owner 2026-10-14:
 * "Agree with this recommendation"). A row's parts that are settings of their own count as on its screen ([partsOfRows]).
 * Not the Debug screen's (its settings were never in the main screen's search: the user should know the context), nor
 * Fine-tune suggestions / learning while their entries aren't shown ([REFINE_MENU_SHOWN]); the Preferences, Toolbar and
 * Secondary layouts screens no entry opens any more.
 */
fun shownScreenLists(ctx: Context): List<ScreenList> {
    val prefs = ctx.prefs()
    val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
    return listOfNotNull(
        ScreenList(layoutScreenKeys(prefs, ctx), Menu.LAYOUT), // Layout & Typing
        ScreenList(appearanceItems(ctx, prefs), Menu.APPEARANCE),
        ScreenList(swipeScreenKeys(prefs), Menu.SWIPE),
        ScreenList(correctionItems(prefs), Menu.TEXT_CORRECTION),
        if (REFINE_MENU_SHOWN) ScreenList(fineTuneSuggestionsKeys(prefs), Menu.REFINE) else null,
        if (REFINE_MENU_SHOWN) ScreenList(fineTuneLearningKeys, Menu.LEARNING) else null,
        ScreenList(appSettingsItems(prefs, real), null),
        ScreenList(aboutItems, null),
        // a keyboard's Languages screen: its Learned words entry
        ScreenList(listOf(SettingsWithoutKey.LEARNED_WORDS), null),
    ).map { screen -> ScreenList(screen.keys + screen.keys.filterIsInstance<String>().flatMap { partsOfRows[it].orEmpty() }, screen.menu) }
}

/** Settings of their own that a row holds or opens, found by searching (they were in the main screen's search before
 *  2026-10-14, each with its own row): the toolbar's three key lists (the toolbar keys row), the four "back to letters
 *  after…" switches (its dialog), the personal dictionary (from Learned words). */
private val partsOfRows: Map<String, List<String>> = mapOf(
    SettingsWithoutKey.TOOLBAR_KEYS_ALL to listOf(Settings.PREF_TOOLBAR_KEYS, Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS),
    SettingsWithoutKey.ABC_AFTER to listOf(Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
        Settings.PREF_ABC_AFTER_EMOJI, Settings.PREF_ABC_AFTER_CLIP),
    SettingsWithoutKey.LEARNED_WORDS to listOf(SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY),
)

@Immutable
class Setting(
    context: Context,
    val key: String,
    @StringRes titleId: Int,
    @StringRes descriptionId: Int? = null,
    private val content: @Composable (Setting) -> Unit
) {
    val title = context.getString(titleId)
    val description = descriptionId?.let { context.getString(it) }

    @Composable
    fun Preference() {
        // on a menu shared by all keyboards, a setting that another menu keeps per keyboard is that menu's (not shown here)
        val menu = LocalSettingsMenu.current
        if (menu != null && helium314.keyboard.latin.settings.KeyboardProfiles.hiddenOn(
                helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(androidx.compose.ui.platform.LocalContext.current), menu, key))
            return
        content(this)
    }
}

/** The top-level menu a screen belongs to (for [Setting.Preference]); null outside them (search, App settings). */
val LocalSettingsMenu = androidx.compose.runtime.compositionLocalOf<helium314.keyboard.latin.settings.KeyboardProfiles.Group?> { null }

// intentionally not putting individual debug settings in here so user knows the context
private fun createSettings(context: Context) = createAboutSettings(context) + createAppearanceSettings(context) +
        createCorrectionSettings(context) + createPreferencesSettings(context) + createToolbarSettings(context) +
        createLayoutSettings(context) + createAdvancedSettings(context) +
        createGestureTypingSettings(context)

object SettingsWithoutKey {
    /** Not a setting: a line across the list (e.g. around a screen's saved themes / layouts, which cover all of it). */
    const val DIVIDER = "divider"
    const val EDIT_PERSONAL_DICTIONARY = "edit_personal_dictionary"
    const val LEARNED_WORDS = "learned_words"
    const val APP = "app"
    const val VERSION = "version"
    const val LICENSE = "license"
    const val DICTIONARY_SOURCES = "dictionary_sources"
    const val BASED_ON = "based_on"
    const val HIDDEN_FEATURES = "hidden_features"
    const val GITHUB = "github"
    const val GITHUB_WIKI = "github_wiki"
    const val RATE = "rate"
    const val SHARE = "share"
    const val SAVE_LOG = "save_log"
    const val SEPARATE_SETTINGS = "separate_settings_row" // the switch itself is KeyboardProfiles' own key
    const val BACKUP_RESTORE = "backup_restore"
    const val FACTORY_RESET = "factory_reset"
    const val DEBUG_SETTINGS = "screen_debug"
    const val ABOUT_SCREEN = "screen_about_entry"
    const val HOW_SAVING_WORKS = "screen_how_saving_works"
    const val BACKGROUND_IMAGE = "background_image"
    const val BACKGROUND_IMAGE_LANDSCAPE = "background_image_landscape"
    const val CUSTOM_FONT = "custom_font"
    const val CUSTOM_EMOJI_FONT = "custom_emoji_font"
    const val CUSTOM_HINT_FONT = "custom_hint_font"
    const val KEY_TEXT_STYLE = "key_text_style"
    const val HINT_TEXT_STYLE = "hint_text_style"
    const val SUGGESTION_TEXT_STYLE = "suggestion_text_style"
    const val SYMBOLS_SUGGESTIONS_FONTS = "symbols_suggestions_fonts" // (2026-10-01, briefly; replaced by FONTS)
    const val FONTS = "fonts" // one dialog for the keys', symbols' and suggestions' text
    const val APPEARANCE_LOOKS = "appearance_looks_list"
    const val HIDE_ALL_SYMBOLS = "hide_all_symbols"
    const val ABC_AFTER = "abc_after"
    const val TOOLBAR_KEYS_ALL = "toolbar_keys_all"
}

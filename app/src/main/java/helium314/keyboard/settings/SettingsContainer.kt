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

class SettingsContainer(context: Context) {
    private val list = createSettings(context)
    private val map: Map<String, Setting> = HashMap<String, Setting>(list.size).apply {
        list.forEach {
            if (put(it.key, it) != null)
                throw IllegalArgumentException("key $it added twice")
        }
    }

    operator fun get(key: Any): Setting? = map[key]

    // always have all settings in search, because:
    //  don't show disabled settings -> users confused
    //  show as disabled (i.e. no interaction possible) -> users confused
    //  show, but change will not do anything because another setting needs to be enabled first -> probably best
    fun filter(searchTerm: String): List<Setting> = SettingsSearch.search(searchTerm, list)
}

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

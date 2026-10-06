// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings

/**
 * Every setting's default, read from [Defaults] by the setting's name in [Settings] (PREF_X: key and default), so a
 * backup can hold the actual value of every setting, not only the changed ones: restored on a phone (or a version)
 * whose defaults differ, the keyboard still works as it did. Only fixed defaults: one that depends on the phone (a
 * `val` in Defaults, e.g. the system's day / night) isn't written down.
 */
object SettingDefaults {
    /** Settings whose absence the code reads ("never set": e.g. the key text bold follows the key style until touched). */
    private val presenceMatters = setOf(
        Settings.PREF_PUNCTUATION_SUGGESTIONS, Settings.PREF_SHOW_SETUP_WIZARD_ICON, Settings.PREF_ADDITIONAL_SUBTYPES,
        Settings.PREF_FONT_FOLLOWS_KEY_TEXT, Settings.PREF_KEY_HORIZONTAL_GAP, Settings.PREF_KEY_TEXT_BOLD,
        Settings.PREF_KEY_VERTICAL_GAP, Settings.PREF_LANGUAGE_SWITCH_KEY, Settings.PREF_SOUND_ON,
        Settings.PREF_SPACE_HORIZONTAL_SWIPE, Settings.PREF_SPACE_VERTICAL_SWIPE, Settings.PREF_TOOLBAR_MODE,
        Settings.PREF_TOOLBAR_VISIBILITY, Settings.PREF_VIBRATE_ON,
        // not set = the swipe trail's colour (0.3.008): a theme or a backup must not write the fallback orange down
        Settings.PREF_SUGGESTION_TEXT_COLOR,
    )

    /** Key -> fixed default, for the settings that are per keyboard (or shared) and whose absence means nothing. */
    val all: Map<String, Any> by lazy {
        val map = HashMap<String, Any>()
        for (field in Settings::class.java.fields) {
            if (!field.name.startsWith("PREF_") || field.name.endsWith("_PREFIX") || field.type != String::class.java) continue
            val key = runCatching { field.get(null) as? String }.getOrNull() ?: continue
            if (key in presenceMatters || KeyboardProfiles.isGlobal(key)) continue
            val default = runCatching {
                val d = Defaults::class.java.getField(field.name)
                if (java.lang.reflect.Modifier.isStatic(d.modifiers)) d.get(null) else null
            }.getOrNull() ?: continue
            if (default is Boolean || default is Int || default is Long || default is Float || default is String) map[key] = default
        }
        // the sizes, one key per screen state: PREF_X_PREFIX with the Defaults array PREF_X (2^n entries for n conditions,
        // as Settings reads them)
        for (field in Settings::class.java.fields) {
            if (!field.name.startsWith("PREF_") || !field.name.endsWith("_PREFIX")) continue
            val prefix = runCatching { field.get(null) as? String }.getOrNull() ?: continue
            val array = runCatching { Defaults::class.java.getField(field.name.removeSuffix("_PREFIX")).get(null) as? Array<*> }
                .getOrNull() ?: continue
            val number = Integer.numberOfTrailingZeros(array.size)
            if (array.isEmpty() || 1 shl number != array.size) continue
            array.forEachIndexed { i, default -> if (default is Float) map[createPrefKeyForBooleanSettings(prefix, i, number)] = default }
        }
        map
    }

    /** What a saved Layout or theme stores for [key] at its default: the fixed default, or null (unset again when
     *  applied) for one whose absence means something or whose default isn't fixed. */
    fun of(key: String): Any? = all[key]

}

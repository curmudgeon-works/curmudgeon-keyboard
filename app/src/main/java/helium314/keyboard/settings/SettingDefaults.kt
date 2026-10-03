// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings

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
        map
    }

    private val ownMark = Regex("^p(\\d+)/${Regex.escape(KeyboardProfiles.TOMBSTONE)}(.+)$")

    /** [stored] (the raw preference file) with every setting at its default written out: the shared set gets the
     *  defaults it lacks, and a keyboard's "default" marks become its own default values. */
    fun explicit(stored: Map<String, Any?>): Map<String, Any?> {
        val out = HashMap(stored)
        for ((key, default) in all) if (key !in out) out[key] = default
        for ((key, value) in stored) {
            val m = ownMark.matchEntire(key) ?: continue
            val plain = m.groupValues[2]
            val default = all[plain] ?: continue
            if (value != true) continue
            out.remove(key)
            out.putIfAbsent("p${m.groupValues[1]}/$plain", default)
        }
        return out
    }
}

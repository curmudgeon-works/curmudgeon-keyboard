// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What a saved Layout, theme or popup set does with the settings it holds at their default (not picked when saved),
 * owner 2026-10-11: each remembers the value it had then, and the App settings switch "Saved items take new defaults"
 * decides which applies. On (the default): today's default, also after an update or a restore. Off: the remembered
 * value, as saved; an update then changes no keyboard. The built-in Layouts and themes always follow the defaults. On a
 * restore the phone's switch stays ("Yes, the phone's").
 */
object SavedItemDefaults {
    const val PREF = "saved_items_take_new_defaults" // app-wide (KeyboardProfiles.isAppWide)
    const val DEFAULT = true

    fun takeNewDefaults(real: SharedPreferences) = real.getBoolean(PREF, DEFAULT)

    /** The values at save time of the settings [values] holds at their default (null): the fixed defaults then. A
     *  setting whose "not set" means something, or whose default is the phone's, has none: it always follows. */
    fun remembered(values: Map<String, Any?>): Map<String, Any> =
        values.mapNotNull { (key, value) -> if (value == null) SettingDefaults.of(key)?.let { key to it } else null }.toMap()

    /** What a saved item with [values] and [remembered] applies: its unpicked settings (null) at today's default
     *  ([takeNew]), or at the value they had when saved. */
    fun applied(values: Map<String, Any?>, remembered: Map<String, Any>, takeNew: Boolean): Map<String, Any?> =
        if (takeNew) values else values.mapValues { (key, value) -> value ?: remembered[key] }

    /** Set once [rememberToday] ran. */
    const val REMEMBERED_DONE = "saved_item_defaults_remembered_done"

    /**
     * Once: saved Layouts and themes from before kept no value for their unpicked settings; they remember today's (what
     * they show now), so turning the switch off keeps them as they look today. A fresh install has none.
     */
    fun rememberToday(real: SharedPreferences) {
        if (real.getBoolean(REMEMBERED_DONE, false)) return
        val presets = LayoutPresets.load(real)
        if (presets.isNotEmpty()) LayoutPresets.save(real, presets.map {
            LayoutPresets.Preset(it.name, it.values, it.builtIn, remembered(it.values) + it.remembered) })
        val looks = AppearanceLooks.load(real)
        if (looks.isNotEmpty()) AppearanceLooks.save(real, looks.map {
            AppearanceLooks.Look(it.name, it.values, remembered(it.values) + it.remembered) }, now = true)
        real.edit { putBoolean(REMEMBERED_DONE, true) }
    }
}

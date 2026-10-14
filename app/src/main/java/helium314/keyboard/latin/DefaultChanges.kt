// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets

/**
 * A default we changed: [key] read [old] before version [since]. Saved items (Layouts, themes, popup sets) store a
 * setting at its default as "default" (null), so without this they'd show the new value; with it they keep [old]
 * (owner 2026-10-11: "saved items should treat their defaults as if they were picked").
 */
internal class DefaultChange(val key: String, val old: Any, val since: String) {
    /** Set once its step ran (a backup from before carries none: its full restore runs it again). */
    val flag get() = "default_change_${key}_${since}_done"
}

/**
 * Changing a default = edit Defaults.kt and add a row here, nothing else ([protectSavedItems] does the rest; the guard
 * test SavedItemDefaultsGuardTest fails a default change that has no row). Oldest first.
 */
internal object DefaultChanges {
    // (empty: the 2026-10-07 and 0.3.012 changes came before this table; saved items that held "default" for them have
    // followed since, and stay so (owner 2026-10-11); 0.3.012's symbol map has its own step, keepUserPopupSetsSymbolMap)
    val rows: List<DefaultChange> = emptyList()
}

/**
 * For each row of [rows] not run yet: every saved Layout, theme and popup set holding "default" for its key gets the
 * old value written in, and every keyboard's set (the shared one and each own one, hidden or not) that chose such an
 * item and reads no value of its own gets the item's value, so it shows what it showed. Sets that chose no saved
 * item keep following the default (not picked). A fresh install has nothing to keep: the flags are only set.
 */
internal fun protectSavedItems(real: SharedPreferences, freshInstall: Boolean, rows: List<DefaultChange> = DefaultChanges.rows) {
    for (row in rows) {
        if (real.getBoolean(row.flag, false)) continue
        if (!freshInstall) {
            fillSavedItems(real, row)
            fillSets(real, row)
        }
        real.edit { putBoolean(row.flag, true) }
    }
}

private fun fillSavedItems(real: SharedPreferences, row: DefaultChange) {
    val presets = LayoutPresets.load(real)
    if (presets.any { it.holdsDefault(row.key) })
        LayoutPresets.save(real, presets.map { if (it.holdsDefault(row.key)) LayoutPresets.Preset(it.name, it.values + (row.key to row.old), it.builtIn) else it })
    val looks = AppearanceLooks.load(real)
    if (looks.any { it.values.containsKey(row.key) && it.values[row.key] == null })
        AppearanceLooks.save(real, looks.map { look ->
            if (look.values.containsKey(row.key) && look.values[row.key] == null) AppearanceLooks.Look(look.name, look.values + (row.key to row.old)) else look
        }, now = true)
    // a popup set without a map or order showed the keyboard's, the default's where it had none
    val old = row.old as? String ?: return
    val sets = KeyPopupOverrides.loadSets(real)
    val filled = sets.map { s -> when {
        row.key == Settings.PREF_SYMBOL_POPUP_MAP && s.symbolMap == null -> KeyPopupOverrides.UserSet(s.name, s.morePopups, s.symbolsLayout, s.overrides, old, s.popupOrder)
        row.key == Settings.PREF_POPUP_KEYS_ORDER && s.popupOrder == null -> KeyPopupOverrides.UserSet(s.name, s.morePopups, s.symbolsLayout, s.overrides, s.symbolMap, old)
        else -> s
    } }
    if (filled.indices.any { filled[it] !== sets[it] }) KeyPopupOverrides.saveSets(real, filled)
}

private fun LayoutPresets.Preset.holdsDefault(key: String) = !builtIn && values.containsKey(key) && values[key] == null && LayoutPresets.inScope(key)

/** The saved item a set chose, by the pref holding its name, that has a value for the key. Popup sets first (their
 *  map and order), then Layouts (a Layout's popups only on a keyboard of the script it was saved from), then themes. */
private fun valueOfChosen(real: SharedPreferences, key: String, chosen: (String) -> String?, script: String?): Any? {
    if (key == Settings.PREF_SYMBOL_POPUP_MAP || key == Settings.PREF_POPUP_KEYS_ORDER) {
        val name = chosen(KeyPopupOverrides.PREF_SELECTED_SET)
        val set = name?.let { n -> KeyPopupOverrides.loadSets(real).firstOrNull { it.name == n } }
        if (set != null) return if (key == Settings.PREF_SYMBOL_POPUP_MAP) set.symbolMap else set.popupOrder
    }
    chosen(LayoutPresets.PREF_SELECTED)?.let { name ->
        val preset = LayoutPresets.load(real).firstOrNull { it.name == name }
        if (preset != null && LayoutPresets.inScope(key) && (key !in LayoutPresets.popupKeys || preset.values[LayoutPresets.SCRIPT] == script))
            preset.values[key]?.let { return it }
    }
    chosen(AppearanceLooks.PREF_SELECTED)?.let { name ->
        AppearanceLooks.load(real).firstOrNull { it.name == name }?.values?.get(key)?.let { return it }
    }
    return null
}

private fun fillSets(real: SharedPreferences, row: DefaultChange) {
    val key = row.key
    val all = real.all
    val tomb = KeyboardProfiles.TOMBSTONE
    fun scriptOf(id: Int) = (KeyboardProfiles.editingKeyboard(real, id) ?: KeyboardProfiles.selectedKeyboard(real)).locale.script()
    // the shared set
    val sharedValue = if (all.containsKey(key)) null else valueOfChosen(real, key, { all[it] as? String }, scriptOf(KeyboardProfiles.SHARED))
    real.edit {
        if (sharedValue != null) KeyboardProfiles.put(this, key, sharedValue)
        for (id in KeyboardProfiles.idEntries(real).values.toSet()) {
            if (id == KeyboardProfiles.SHARED || !KeyboardProfiles.hasOwnEntries(real, id)) continue
            fun own(plain: String) = KeyboardProfiles.ownKey(id, plain)
            if (all.containsKey(own(key))) continue // its own pick
            val atDefault = all.containsKey(own(tomb + key))
            if (!atDefault && all.containsKey(key)) continue // it reads the shared value someone picked
            // what it chose: its own choice, none where marked "at the default", else the shared set's
            val chosen = { pref: String -> when {
                all.containsKey(own(pref)) -> all[own(pref)] as? String
                all.containsKey(own(tomb + pref)) -> null
                else -> all[pref] as? String
            } }
            val value = valueOfChosen(real, key, chosen, scriptOf(id))
            when {
                // it reads the shared set, which just got the same value: nothing to write
                value != null && !atDefault && value == sharedValue -> {}
                value != null -> { remove(own(tomb + key)); KeyboardProfiles.put(this, own(key), value) }
                // it chose nothing that holds one, and reads the shared set, which just got a value: it stays at the default
                !atDefault && sharedValue != null -> putBoolean(own(tomb + key), true)
            }
        }
    }
}

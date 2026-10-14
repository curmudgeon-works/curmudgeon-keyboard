// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional

/**
 * What a saved Layout, theme or popup set does with the settings it holds at their default (not picked when saved),
 * owner 2026-10-11: each remembers the value it had then, and the App settings switch "Saved items take new defaults"
 * decides which applies. On (the default): today's default, also after an update or a restore. Off: the remembered
 * value, as saved; an update then changes no keyboard. The built-in Layouts and themes always follow the defaults. On a
 * restore the phone's switch stays ("Yes, the phone's"). Flipping it acts on the keyboards at once ([applySwitch]; review
 * 2026-10-11 A2/A3: it only acted when an item was chosen, so values written while off stayed after it was turned on,
 * and a keyboard chosen while on took a new default after it was turned off).
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

    /** What a saved item saves (Save as new, Update): [values] as on the keyboard, [chosen] the values the item chosen
     *  on it remembered for the settings it holds at their default. With the switch off a setting still at that value
     *  stays at its default, remembered as it was (review 2026-10-11 A1: Update made it a pick, and it no longer
     *  followed the switch); on ([takeNew]) such a value on a keyboard was picked by hand (the switch took the others
     *  off), and is saved as one. The values and what they remember. */
    fun forSave(values: Map<String, Any?>, chosen: Map<String, Any>, takeNew: Boolean): Pair<Map<String, Any?>, Map<String, Any>> {
        val kept = if (takeNew) values else values.mapValues { (key, value) -> if (atChosenDefault(key, value, chosen)) null else value }
        return kept to (remembered(kept) + chosen.filterKeys { values[it] != null && kept[it] == null })
    }

    /** Whether [value] of [key] is what the chosen item remembered for it ([chosen]), so not a pick. */
    fun atChosenDefault(key: String, value: Any?, chosen: Map<String, Any>): Boolean =
        value != null && chosen[key]?.let { KnownDefaults.same(key, value, it) } == true

    /** What user popup set [set] holds at its default with the value it kept, by setting (the map, the order; the
     *  accents level is the keyboard's, see [applySwitch]). */
    fun remembered(set: KeyPopupOverrides.UserSet): Map<String, Any> = listOfNotNull(
        set.symbolMap?.takeIf { KeyPopupOverrides.MAP in set.atDefault }?.let { Settings.PREF_SYMBOL_POPUP_MAP to it },
        set.popupOrder?.takeIf { KeyPopupOverrides.ORDER in set.atDefault }?.let { Settings.PREF_POPUP_KEYS_ORDER to it }).toMap()

    /**
     * The switch flipped to [takeNew]: every keyboard set (the shared one and each keyboard's own, hidden ones too) gets
     * it at once for the user's Layout, theme and popup set it chose (owner 2026-10-11, review A2/A3). For each setting
     * such an item holds at its default with a remembered value: off, a set storing no value of its own for it gets the
     * remembered one (a keyboard's own set that reads a value the shared set picked keeps reading that); on, a set
     * storing just that value has it taken off, a keyboard's own then reads the default (a mark) where the shared set
     * still holds a value, else it holds nothing, as before it was turned off (review 2026-10-11 S4: it was marked
     * either way, so a value picked in the shared set later no longer reached it).
     * By value, no notes per keyboard (owner: a value set by hand to exactly the remembered one moves with the switch).
     * A popup set's accents level is the keyboard's (in its identity): the keyboards in use get it.
     */
    fun applySwitch(ctx: Context, real: SharedPreferences, takeNew: Boolean) {
        val before = real.all
        val layouts = LayoutPresets.load(real)
        val looks = AppearanceLooks.load(real)
        val sets = KeyPopupOverrides.loadSets(real)
        // (the built-ins always follow; a name of theirs is theirs, as their lists find it first)
        val builtInLayouts = LayoutPresets.builtIn(ctx).mapTo(HashSet()) { it.name }
        val builtInLooks = AppearanceLooks.builtIn(ctx).mapTo(HashSet()) { it.name }
        val ids = KeyboardProfiles.idEntries(real)
        val enabled = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ENABLED_SUBTYPES, "")!!)
        val editor = real.edit()
        var changed = false
        val sharedDropped = HashSet<String>() // (taken off the shared set in this pass: it goes first)
        for (id in listOf(KeyboardProfiles.SHARED) + before.keys.mapNotNull { KeyboardProfiles.splitOwnKey(it)?.first }.distinct().sorted()) {
            // what set [id] chose for [key]: its own entry, nothing where marked, else the shared one; a shared menu's
            // own entries too, kept hidden until the menu is per keyboard again (owner 2026-10-11: "Deleting should
            // not happen. Behavior should be same")
            fun chosen(key: String): String? = when {
                id == KeyboardProfiles.SHARED -> before[key] as? String
                KeyboardProfiles.isAppWide(key) -> null
                before.containsKey(KeyboardProfiles.ownKey(id, key)) -> before[KeyboardProfiles.ownKey(id, key)] as? String
                before.containsKey(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + key)) -> null
                KeyboardProfiles.isGlobal(key) -> null // (a shared menu, nothing of its own: the shared set's choice)
                else -> before[key] as? String
            }
            // (a Layout's popups only go on a keyboard of its script: the keyboards of this set)
            val keyboards: List<SettingsSubtype> = if (id == KeyboardProfiles.SHARED) enabled + ids.keys.map { it.toSettingsSubtype() }
                else ids.filterValues { it == id }.keys.map { it.toSettingsSubtype() }
            val held = HashMap<String, Any>()
            chosen(LayoutPresets.PREF_SELECTED)?.takeIf { it !in builtInLayouts }?.let { name -> layouts.firstOrNull { it.name == name } }
                ?.let { held.putAll(LayoutPresets.rememberedFor(it, keyboards)) }
            chosen(AppearanceLooks.PREF_SELECTED)?.takeIf { it !in builtInLooks }?.let { name -> looks.firstOrNull { it.name == name } }
                ?.let { held.putAll(AppearanceLooks.rememberedOf(it)) }
            chosen(KeyPopupOverrides.PREF_SELECTED_SET)?.let { name -> sets.firstOrNull { it.name == name } }
                ?.let { held.putAll(remembered(it)) } // (the set's map and order over a Layout's: the set is chosen on its own)
            for ((key, value) in held) {
                if (id == KeyboardProfiles.SHARED) {
                    if (!takeNew && !before.containsKey(key)) { KeyboardProfiles.put(editor, key, value); changed = true }
                    else if (takeNew && atChosenDefault(key, before[key], held)) { editor.remove(key); sharedDropped.add(key); changed = true }
                    continue
                }
                if (KeyboardProfiles.isAppWide(key)) continue // (the shared set's)
                val own = KeyboardProfiles.ownKey(id, key)
                val mark = KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + key)
                if (!takeNew) {
                    if (before.containsKey(own)) continue
                    if (!before.containsKey(mark) && before.containsKey(key)) continue // (it reads what the shared set picked)
                    KeyboardProfiles.put(editor, own, value)
                    editor.remove(mark)
                    changed = true
                } else if (before.containsKey(own) && atChosenDefault(key, before[own], held)) {
                    editor.remove(own)
                    if (before.containsKey(key) && key !in sharedDropped) editor.putBoolean(mark, true)
                    changed = true
                }
            }
        }
        editor.commit()
        // a popup set's accents level: in the keyboard's identity (each keyboard in use, by the set it reads now)
        for (keyboard in enabled) {
            val id = if (KeyboardProfiles.isSeparate(real)) KeyboardProfiles.anyIdIn(before, keyboard) ?: KeyboardProfiles.SHARED
                else KeyboardProfiles.SHARED
            val set = ProfilePreferences(real) { id }.getString(KeyPopupOverrides.PREF_SELECTED_SET, null)
                ?.let { name -> sets.firstOrNull { it.name == name } }?.takeIf { KeyPopupOverrides.ACCENTS in it.atDefault } ?: continue
            val accents = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)
            val to = when {
                !takeNew && accents == null -> keyboard.with(ExtraValue.MORE_POPUPS, set.morePopups)
                takeNew && accents == set.morePopups -> keyboard.without(ExtraValue.MORE_POPUPS)
                else -> continue
            }
            SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, to, ctx)
            changed = true
        }
        if (changed) AppearanceLooks.reload(ctx)
    }

    /** Whether two saved items hold the same (review C6 2026-10-11): the same settings, each picked alike, and each
     *  one both leave unpicked (null) remembered alike, as with the switch off they apply that (Preset / Look equals
     *  leave the remembered out: list identity). None remembered is
     *  today's default, as [applied] gives then (an item from before [rememberToday] matches its migrated copy). */
    fun sameContent(values: Map<String, Any?>, remembered: Map<String, Any>,
                    otherValues: Map<String, Any?>, otherRemembered: Map<String, Any>): Boolean =
        values.keys == otherValues.keys && values.all { (key, value) ->
            val other = otherValues[key]
            if (value == null && other == null) KnownDefaults.same(key, remembered[key], otherRemembered[key])
            else value != null && other != null && KnownDefaults.same(key, value, other)
        }

    /** Set once [rememberToday] ran. */
    const val REMEMBERED_DONE = "saved_item_defaults_remembered_done"

    /**
     * Once: saved Layouts and themes from before kept no value for their unpicked settings; they remember today's (what
     * they show now), so turning the switch off keeps them as they look today. A fresh install has none.
     * (review 2026-10-11: "today's" is [SettingDefaults.of] in the release that runs this, not what the item showed when
     * saved. If a release changes a default and is also the first to run this step on a phone, the items remember the
     * new default, not the old one they showed: remember the old value with a one-time row for that default instead.)
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

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Custom": the label of a keyboard whose Layout, theme or popups differ from the saved item it chose (or whose item
 * was deleted): a label on the keyboard that holds the copy, not an entry in the saved list (owner 2026-10-11). Shown
 * as "Custom · from Sunset" while the item it came from (the keyboard's choice) still exists. A flag per kind, set
 * from the first time it's shown until the keyboard chooses an item again (owner 2026-10-11: "Yes, repeats are fine."
 * … "Ok, call them just Custom without number."; before, each label had a number unique on the phone). Stored as an
 * Int: any value > 0 (older ones are numbers) means "has a label"; written as [SET].
 * The item deleted: its name at that moment is stored next to the label ([Kind.fromKey]) and still shown, "Custom · from
 * Work", until the copy goes (owner 2026-10-11: "I see, so reference that name with rename. Hardcode with delete?"):
 * while the item exists the label references it, a rename shows the new name. That stored name alone tells a deleted
 * item ([deleted]); a keyboard that never chose one is a copy of the built-in default, "Custom · from Dense" (review
 * E2, [row]).
 */
object CustomLabels {
    enum class Kind(val key: String) { LAYOUT("custom_label_layout"), THEME("custom_label_theme"), POPUPS("custom_label_popups");
        /** The deleted origin's name (String), in the same set as the label: it follows the label everywhere. */
        val fromKey get() = key + "_from"
    }

    const val SET = 1

    /** The keyboard ([prefs]: its settings view) has a label for [kind] from now on. */
    fun mark(prefs: SharedPreferences, kind: Kind) { if (!has(prefs, kind)) prefs.edit { putInt(kind.key, SET) } }

    /** Saved item [name] (chosen under [selectedKey]) is deleted: every set that chose it, in use or hidden, keeps its
     *  copy and gets its label now (2026-10-11: "other keyboards should keep the popups/palette now called Custom").
     *  Call before the name is forgotten. */
    fun detach(real: SharedPreferences, selectedKey: String, name: String, kind: Kind) {
        val ids = real.all.mapNotNull { (stored, value) ->
            if (value != name) null
            else KeyboardProfiles.splitOwnKey(stored)?.takeIf { it.second == selectedKey }?.first
        }.toSet()
        // each set its own label and origin, in its own set (a shared menu's label: the main keyboard's set has it for
        // all, the others' hidden ones come back; owner 2026-10-11: no separate common set); labelled before too: "from" it now
        for (id in ids) markOwn(real, id, kind, name)
    }

    /** Set [id]'s own label for [kind]: in its own set also while the menu is shared, hidden then (owner 2026-10-11:
     *  "Deleting should not happen. Behavior should be same": it comes back when the menu is per keyboard again),
     *  never the shared set's. [from]: the deleted origin's name, stored with it. */
    fun markOwn(real: SharedPreferences, id: String, kind: Kind, from: String? = null) {
        val own = KeyboardProfiles.ownKey(id, kind.key)
        if (real.getInt(own, 0) > 0 && from == null) return
        real.edit(commit = true) {
            if (real.getInt(own, 0) <= 0) putInt(own, SET)
            if (from != null) putString(KeyboardProfiles.ownKey(id, kind.fromKey), from)
        }
    }

    /** The stored keys of every set's label of [kinds] and its origin (plain or "p<id>/"). */
    fun stored(real: SharedPreferences, kinds: Collection<Kind>): Set<String> {
        val keys = kinds.flatMapTo(HashSet()) { listOf(it.key, it.fromKey) }
        return real.all.keys.filterTo(HashSet()) { (KeyboardProfiles.splitOwnKey(it)?.second ?: it) in keys }
    }

    /** Every set's label of [kinds] and its origin as stored (raw key -> value): what an editing screen records when it
     *  opens, so its Discard puts them back exactly ([putBack]). */
    fun storedValues(real: SharedPreferences, kinds: Collection<Kind>): Map<String, Any?> {
        val keys = stored(real, kinds)
        return real.all.filterKeys { it in keys }
    }

    /** Discard: the labels of [kinds] and their origins exactly as in [before] ([storedValues] at the open): those added
     *  since go (review D2), those changed or cleared since come back with their origin, "from Work" too (review E3:
     *  before, a label or origin cleared on the screen stayed cleared). */
    fun putBack(real: SharedPreferences, kinds: Collection<Kind>, before: Map<String, Any?>) {
        val now = storedValues(real, kinds)
        if (now == before) return
        real.edit(commit = true) {
            for (key in now.keys) if (key !in before) remove(key)
            for ((key, value) in before) if (now[key] != value) { if (value == null) remove(key) else KeyboardProfiles.put(this, key, value) }
        }
    }

    /** The labels of [kinds] as an editing screen found them when it opened, for its Discard ([putBack]); kept on disk
     *  with its snapshot ([toJson], [fromJson]). */
    class Opened private constructor(private val values: Map<String, Any?>?, private val keys: Set<String>) {
        /** Discard: the labels and origins as they were ([CustomLabels.putBack]); a snapshot of before review E3 has
         *  only the keys: the labels added since go ([removeNew]). */
        fun putBack(real: SharedPreferences, kinds: Collection<Kind>) {
            if (values != null) CustomLabels.putBack(real, kinds, values) else CustomLabels.removeNew(real, kinds, keys)
        }

        /** Into the snapshot's [json]: "labelValues" (raw key -> typed value), and "labels" (the keys) as before. */
        fun toJson(json: JSONObject) {
            json.put("labels", JSONArray(keys.toList()))
            values?.let { v -> json.put(VALUES, JSONObject().also { o -> v.forEach { (k, x) -> AppearanceLooks.toJson(x)?.let { o.put(k, it) } } }) }
        }

        companion object {
            private const val VALUES = "labelValues"
            fun of(real: SharedPreferences, kinds: Collection<Kind>): Opened = CustomLabels.storedValues(real, kinds).let { Opened(it, it.keys) }

            /** From a snapshot's [json]; null: one from before review D2 without labels (they stay as they are). */
            fun fromJson(json: JSONObject): Opened? {
                json.optJSONObject(VALUES)?.let { o ->
                    val values = o.keys().asSequence().associateWith { AppearanceLooks.fromJson(o.getJSONObject(it)) }
                    return Opened(values, values.keys)
                }
                return json.optJSONArray("labels")?.let { a -> Opened(null, (0 until a.length()).mapTo(HashSet()) { a.getString(it) }) }
            }
        }
    }

    /** Discard with a snapshot of before review E3 (only the keys): the labels of [kinds] stored now but not in [before]
     *  go; those there before stay as they are. */
    fun removeNew(real: SharedPreferences, kinds: Collection<Kind>, before: Set<String>) {
        val added = stored(real, kinds) - before
        if (added.isNotEmpty()) real.edit(commit = true) { added.forEach { remove(it) } }
    }

    /** The keyboard ([prefs]) has a label for [kind]: its copy, whatever it chose. */
    fun has(prefs: SharedPreferences, kind: Kind) = prefs.getInt(kind.key, 0) > 0

    /** The deleted item the keyboard's copy of [kind] came from, while it has the label. */
    fun origin(prefs: SharedPreferences, kind: Kind): String? = if (has(prefs, kind)) prefs.getString(kind.fromKey, null) else null

    /** The item of [kind] the keyboard ([prefs]) had chosen was deleted (its choice at [selectedKey] gone with it): only
     *  a delete stores the origin's name ([detach]), so a label without one is a copy of an item that exists, the
     *  built-in default included for a keyboard that never chose one (review E2: "deleted" was the label alone, and a
     *  never-chosen keyboard's first change showed plain "Custom" for good). */
    fun deleted(prefs: SharedPreferences, kind: Kind, selectedKey: String): Boolean =
        prefs.getString(selectedKey, null) == null && origin(prefs, kind) != null

    /** What a saved-item row shows: [text] (null: nothing), and whether it's the keyboard's copy, "Custom" ([custom]). */
    class Row(val text: String?, val custom: Boolean)

    /** The row of [kind] for the keyboard ([prefs], its choice at [selectedKey]). [chosen]: the item it reads, the one it
     *  chose while that exists, or the built-in default when it never chose one (null: none to name). [differs]: the
     *  keyboard's settings differ from [chosen]. Its chosen item deleted: "Custom · from Work" until it chooses again;
     *  else differing: "Custom · from Dense" (labelled from now on, [mark]), and the item's plain name again once
     *  it matches (review E2, the same for a never-chosen keyboard as for a chosen built-in or saved item). */
    fun row(ctx: Context, prefs: SharedPreferences, kind: Kind, selectedKey: String, chosen: String?, differs: () -> Boolean): Row {
        if (deleted(prefs, kind, selectedKey)) return Row(text(ctx, origin(prefs, kind)), true)
        if (chosen == null || !differs()) return Row(chosen, false)
        mark(prefs, kind)
        return Row(text(ctx, chosen), true)
    }

    /** The Themes row for the keyboard ([prefs]: its settings view; [ctx]: the screen editing it), [builtIn] and [looks]
     *  the built-in and saved themes: its chosen theme, none chosen the default look ([AppearanceLooks.defaultLook]:
     *  Dynamic, older than Android 12 Curmudgeon). */
    fun themeRow(ctx: Context, prefs: SharedPreferences, builtIn: List<AppearanceLooks.Look>, looks: List<AppearanceLooks.Look>): Row {
        val chosen = prefs.getString(AppearanceLooks.PREF_SELECTED, null)?.let { name -> (builtIn + looks).firstOrNull { it.name == name } }
            ?: AppearanceLooks.defaultLook(ctx, builtIn).takeIf { !prefs.contains(AppearanceLooks.PREF_SELECTED) }
        return row(ctx, prefs, Kind.THEME, AppearanceLooks.PREF_SELECTED, chosen?.name) { AppearanceLooks.isTweaked(ctx, chosen!!) }
    }

    /** The Layouts row for [keyboard] ([prefs]: its settings view; [ctx]: the screen editing it), [chosen] the Layout it
     *  reads: its chosen one, none chosen the default, Dense. */
    fun layoutRow(ctx: Context, prefs: SharedPreferences, keyboard: helium314.keyboard.latin.settings.SettingsSubtype,
                  chosen: LayoutPresets.Preset): Row =
        row(ctx, prefs, Kind.LAYOUT, LayoutPresets.PREF_SELECTED, chosen.name) { LayoutPresets.isTweaked(ctx, keyboard, chosen) }

    /** The keyboard chose an item again (or its copy was saved as one): no label, no stored origin. */
    fun clear(prefs: SharedPreferences, kind: Kind) {
        val keys = listOf(kind.key, kind.fromKey).filter { prefs.contains(it) }
        if (keys.isNotEmpty()) prefs.edit { keys.forEach { remove(it) } }
    }

    /** "Custom", or "Custom · from Sunset" while [from] is the saved item it came from. */
    fun text(ctx: Context, from: String?): String {
        val custom = ctx.getString(R.string.custom_label)
        return if (from == null) custom else ctx.getString(R.string.custom_from, custom, from)
    }

    /** The row's label of [kind] on the keyboard ([prefs]): "from" the deleted origin stored with it, else [current] (the
     *  item it chose, while that exists: a rename shows the new name), else plain "Custom". */
    fun text(ctx: Context, prefs: SharedPreferences, kind: Kind, current: String?): String =
        text(ctx, current ?: origin(prefs, kind))
}

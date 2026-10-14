// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles

/**
 * "Custom": the label of a keyboard whose Layout, theme or popups differ from the saved item it chose (or whose item
 * was deleted): a label on the keyboard that holds the copy, not an entry in the saved list (owner 2026-10-11). Shown
 * as "Custom · from Sunset" while the item it came from (the keyboard's choice) still exists. A flag per kind, set
 * from the first time it's shown until the keyboard chooses an item again (owner 2026-10-11: "Yes, repeats are fine."
 * … "Ok, call them just Custom without number."; before, each label had a number unique on the phone). Stored as an
 * Int: any value > 0 (older ones are numbers) means "has a label"; written as [SET].
 * The item deleted: its name at that moment is stored next to the label ([Kind.fromKey]) and still shown, "Custom · from
 * Work", until the copy goes (owner 2026-10-11: "I see, so reference that name with rename. Hardcode with delete?"):
 * while the item exists the label references it, a rename shows the new name.
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
    fun markOwn(real: SharedPreferences, id: Int, kind: Kind, from: String? = null) {
        val own = KeyboardProfiles.ownKey(id, kind.key)
        if (real.getInt(own, 0) > 0 && from == null) return
        real.edit(commit = true) {
            if (real.getInt(own, 0) <= 0) putInt(own, SET)
            remove(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + kind.key))
            if (from != null) {
                putString(KeyboardProfiles.ownKey(id, kind.fromKey), from)
                remove(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + kind.fromKey))
            }
        }
    }

    /** The stored keys of every set's label of [kinds] and its origin (plain or "p<id>/"): what an editing screen
     *  records when it opens, so its Discard can drop the labels added meanwhile ([removeNew]). */
    fun stored(real: SharedPreferences, kinds: Collection<Kind>): Set<String> {
        val keys = kinds.flatMapTo(HashSet()) { listOf(it.key, it.fromKey) }
        return real.all.keys.filterTo(HashSet()) { (KeyboardProfiles.splitOwnKey(it)?.second ?: it) in keys }
    }

    /** Discard: the labels of [kinds] stored now but not in [before] go, so a Discard leaves no stray "Custom" (review
     *  D2); those there before stay as they are. */
    fun removeNew(real: SharedPreferences, kinds: Collection<Kind>, before: Set<String>) {
        val added = stored(real, kinds) - before
        if (added.isNotEmpty()) real.edit(commit = true) { added.forEach { remove(it) } }
    }

    /** The keyboard ([prefs]) has a label for [kind]: its copy, whatever it chose. */
    fun has(prefs: SharedPreferences, kind: Kind) = prefs.getInt(kind.key, 0) > 0

    /** The deleted item the keyboard's copy of [kind] came from, while it has the label. */
    fun origin(prefs: SharedPreferences, kind: Kind): String? = if (has(prefs, kind)) prefs.getString(kind.fromKey, null) else null

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

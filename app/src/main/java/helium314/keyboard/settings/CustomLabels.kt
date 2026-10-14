// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles

/**
 * "Custom 1", "Custom 2" …: the label of a keyboard whose Layout, theme or popups differ from the saved item it chose
 * (or whose item was deleted): a label on the keyboard that holds the copy, not an entry in the saved list (owner
 * 2026-10-11). Shown with "from Sunset" while the item it came from still exists. Each kind numbers on its own; the
 * number is the keyboard's (its set's) from the first time it's shown until it chooses an item again.
 */
object CustomLabels {
    enum class Kind(val key: String) { LAYOUT("custom_label_layout"), THEME("custom_label_theme"), POPUPS("custom_label_popups") }

    /** The keyboard's number for [kind] ([prefs]: the keyboard's settings view), given the next free one if it had none. */
    fun number(real: SharedPreferences, prefs: SharedPreferences, kind: Kind): Int {
        prefs.getInt(kind.key, 0).takeIf { it > 0 }?.let { return it }
        val next = nextFree(real, kind)
        prefs.edit { putInt(kind.key, next) }
        return next
    }

    // (every set's label counts, hidden ones too: they come back)
    private fun nextFree(real: SharedPreferences, kind: Kind): Int {
        val taken = real.all.filterKeys { (KeyboardProfiles.splitOwnKey(it)?.second ?: it) == kind.key }.values.filterIsInstance<Int>().toSet()
        return generateSequence(1) { it + 1 }.first { it !in taken }
    }

    /** Saved item [name] (chosen under [selectedKey]) is deleted: every set that chose it, in use or hidden, keeps its
     *  copy and gets its Custom number now (2026-10-11: "other keyboards should keep the popups/palette now called
     *  Custom ... Custom1, Custom2, etc., indexing"). Call before the name is forgotten. */
    fun detach(real: SharedPreferences, selectedKey: String, name: String, kind: Kind) {
        val ids = real.all.mapNotNull { (stored, value) ->
            if (value != name) null
            else if (stored == selectedKey) KeyboardProfiles.SHARED
            else KeyboardProfiles.splitOwnKey(stored)?.takeIf { it.second == selectedKey }?.first
        }.toSet()
        for (id in ids) {
            if (id == KeyboardProfiles.SHARED || !KeyboardProfiles.isGlobal(kind.key))
                number(real, helium314.keyboard.latin.settings.ProfilePreferences(real) { id }, kind)
            else numberOwn(real, id, kind) // (a keyboard's own set hidden by a shared menu: its own label)
        }
    }

    /** Set [id]'s own number for [kind], given the next free one if it had none: in its own set also while the menu is
     *  shared, hidden then (owner 2026-10-11: "Deleting should not happen. Behavior should be same": it comes back when
     *  the menu is per keyboard again), never the shared set's. */
    fun numberOwn(real: SharedPreferences, id: Int, kind: Kind) {
        val own = KeyboardProfiles.ownKey(id, kind.key)
        if (real.getInt(own, 0) > 0) return
        val next = nextFree(real, kind)
        real.edit(commit = true) { putInt(own, next); remove(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + kind.key)) }
    }

    /** The stored keys of every set's label of [kinds] (plain or "p<id>/"): what an editing screen records when it
     *  opens, so its Discard can take back the numbers handed out meanwhile ([removeNew]). */
    fun stored(real: SharedPreferences, kinds: Collection<Kind>): Set<String> {
        val keys = kinds.mapTo(HashSet()) { it.key }
        return real.all.keys.filterTo(HashSet()) { (KeyboardProfiles.splitOwnKey(it)?.second ?: it) in keys }
    }

    /** Discard: the labels of [kinds] stored now but not in [before] go (review D2: they took numbers, so a keyboard
     *  needing one later skipped to "Custom 2"); those there before stay as they are. */
    fun removeNew(real: SharedPreferences, kinds: Collection<Kind>, before: Set<String>) {
        val added = stored(real, kinds) - before
        if (added.isNotEmpty()) real.edit(commit = true) { added.forEach { remove(it) } }
    }

    /** The keyboard ([prefs]) has a Custom number for [kind]: its copy, whatever it chose. */
    fun has(prefs: SharedPreferences, kind: Kind) = prefs.getInt(kind.key, 0) > 0

    /** The keyboard chose an item again: no label. */
    fun clear(prefs: SharedPreferences, kind: Kind) { if (prefs.contains(kind.key)) prefs.edit { remove(kind.key) } }

    /** "Custom 1", or "Custom 1 · from Sunset" while [from] is the saved item it came from. */
    fun text(ctx: Context, number: Int, from: String?): String {
        val custom = ctx.getString(R.string.custom_n, number)
        return if (from == null) custom else ctx.getString(R.string.custom_from, custom, from)
    }
}

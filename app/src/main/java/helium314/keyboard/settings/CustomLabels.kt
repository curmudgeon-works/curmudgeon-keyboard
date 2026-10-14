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
        val taken = real.all.filterKeys { (KeyboardProfiles.splitOwnKey(it)?.second ?: it) == kind.key }.values.filterIsInstance<Int>().toSet()
        val next = generateSequence(1) { it + 1 }.first { it !in taken }
        prefs.edit { putInt(kind.key, next) }
        return next
    }

    /** The keyboard chose an item again: no label. */
    fun clear(prefs: SharedPreferences, kind: Kind) { if (prefs.contains(kind.key)) prefs.edit { remove(kind.key) } }

    /** "Custom 1", or "Custom 1 · from Sunset" while [from] is the saved item it came from. */
    fun text(ctx: Context, number: Int, from: String?): String {
        val custom = ctx.getString(R.string.custom_n, number)
        return if (from == null) custom else ctx.getString(R.string.custom_from, custom, from)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.prefs

/**
 * The switched-off keyboards: neither in the list nor deleted. Switched off before 2026-09-22 (the Languages screen with
 * its switches is gone since) or brought back by an old backup, they keep their id with a settings set and/or a
 * learned-words pool. Owner 2026-10-11: "Any leftover switched off keyboard should appear in main keyboards list when
 * advanced is toggled -- this can be a permanent feature that prevents such stragglers lurking."
 * Since the keyboards have ids (owner 2026-10-11): the keyboards known by id (a set or a pool) that aren't in the list,
 * their definitions from the added list.
 */
object SwitchedOffKeyboards {
    private const val TAG = "SwitchedOffKeyboards"

    /** The keyboards of the added list with a set or a pool (by id) that aren't in the list. An id with a set or a pool
     *  but no definition is skipped (logged): there is nothing to show of it. */
    fun list(real: SharedPreferences): List<SettingsSubtype> {
        val listed = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() } +
            SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
        val all = real.all
        val known = KeyboardProfiles.setIds(all) + LearnedKeyboards.poolEntries(all).keys
        val added = KeyboardIds.phoneKeyboards(real).filter { kb -> listed.none { it.sameKeyboard(kb) } }
        val off = added.filter { it.keyboardId in known }.distinctBy { it.keyboardId }
        val lost = known - off.mapNotNullTo(HashSet()) { it.keyboardId } - listed.mapNotNullTo(HashSet()) { it.keyboardId }
        if (lost.isNotEmpty()) Log.w(TAG, "skipped ${lost.size} ids with records but no keyboard")
        return off
    }

    /** [keyboard] back in the list with its own settings and words as they are (nothing started from another set, as
     *  Add keyboard does), registered as an added keyboard. @return false: it's in the list already, nothing done */
    fun switchOn(context: Context, keyboard: SettingsSubtype): Boolean {
        if (SubtypeSettings.getEnabledSubtypes(true).any { it.toSettingsSubtype().sameKeyboard(keyboard) }) return false
        SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, keyboard, context) // (its set and pool stay: its id)
        SubtypeSettings.addEnabledSubtype(context.prefs(), keyboard.toAdditionalSubtype())
        helium314.keyboard.latin.utils.SettingsEventLog.keyboard(context, "switched on", keyboard) // (owner 2026-10-12 K1)
        return true
    }
}

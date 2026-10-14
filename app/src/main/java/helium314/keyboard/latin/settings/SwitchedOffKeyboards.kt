// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.prefs

/**
 * The switched-off keyboards: neither in the list nor deleted. Switched off before 2026-09-22 (the Languages screen with
 * its switches is gone since) or brought back by an old backup, they keep a set id and/or a learned-words pool, maybe
 * settings of their own. Owner 2026-10-11: "Any leftover switched off keyboard should appear in main keyboards list when
 * advanced is toggled -- this can be a permanent feature that prevents such stragglers lurking."
 */
object SwitchedOffKeyboards {
    private const val TAG = "SwitchedOffKeyboards"

    /** The identities with a set id (not the shared set) or a pool that aren't in the list, ids first. One that doesn't
     *  read as a keyboard is skipped (logged). */
    fun list(real: SharedPreferences): List<SettingsSubtype> {
        val listed = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }.toHashSet()
        val identities = KeyboardProfiles.idEntries(real).filterValues { it != KeyboardProfiles.SHARED }.keys +
            LearnedKeyboards.poolEntries(real).keys
        return identities.mapNotNull { parse(it) }.distinct().filter { it !in listed }
    }

    private fun parse(identity: String): SettingsSubtype? {
        val keyboard = if (Separators.SET in identity) runCatching { identity.toSettingsSubtype() }.getOrNull() else null
        if (keyboard == null || keyboard.locale.toLanguageTag() == "und") {
            Log.w(TAG, "skipped an identity that isn't a keyboard: $identity")
            return null
        }
        return keyboard
    }

    /** [keyboard] back in the list with its own settings and words as they are (nothing started from another set, as
     *  Add keyboard does), registered as an added keyboard unless it's a built-in one (a custom layout, a name, a copy
     *  number need it). @return false: it's in the list already, nothing done */
    fun switchOn(context: Context, keyboard: SettingsSubtype): Boolean {
        if (SubtypeSettings.getEnabledSubtypes(true).any { it.toSettingsSubtype() == keyboard }) return false
        SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, keyboard, context) // (its set and pool stay: same identity)
        SubtypeSettings.addEnabledSubtype(context.prefs(), keyboard.toAdditionalSubtype())
        return true
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype

/**
 * Which pool of learned words and blacklists (see LearnedStores) a keyboard has (2026-10-10): the one place every
 * lookup goes through.
 */
object LearnedKeyboards {
    /** The pool of [keyboard]: the shared one while "Share learned & blacklisted words across keyboards" is on, else the
     *  keyboard's own, numbered by its profile id. [real]: the preferences as stored. */
    fun poolFor(real: SharedPreferences, keyboard: SettingsSubtype): Int =
        if (LearnedStores.isShared(real)) LearnedStores.SHARED else KeyboardProfiles.idFor(real, keyboard)
}

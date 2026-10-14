// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owner 2026-10-12 "DM22 STRIP REMOVE": the settings' delete against Android's personal dictionary (the strip's Remove
 *  never touches it, owner 2026-10-12: StripRemoveKeepsPersonalDictionaryTest). */
class PersonalDictionaryChoiceTest {
    @Test fun `the settings delete box is ticked by default`() {
        assertTrue(PersonalDictionaryChoice.SETTINGS_DEFAULT)
        assertTrue(PersonalDictionaryChoice.deletesFromPersonal(asked = true, ticked = PersonalDictionaryChoice.SETTINGS_DEFAULT))
        assertFalse(PersonalDictionaryChoice.deletesFromPersonal(asked = true, ticked = false))
    }

    @Test fun `not asked (no selected word in Android's dictionary) deletes as before`() {
        assertTrue(PersonalDictionaryChoice.deletesFromPersonal(asked = false, ticked = false))
    }
}

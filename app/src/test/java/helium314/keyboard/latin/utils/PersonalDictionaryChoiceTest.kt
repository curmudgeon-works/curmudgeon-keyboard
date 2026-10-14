// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owner 2026-10-12 "DM22 STRIP REMOVE": Remove and delete against Android's personal dictionary. */
class PersonalDictionaryChoiceTest {
    @Test fun `strip Remove of a word in Android's dictionary asks with the box unticked`() {
        assertTrue(PersonalDictionaryChoice.stripAsks(inPersonalDictionary = true))
        assertFalse(PersonalDictionaryChoice.STRIP_DEFAULT)
        // unticked: blocked on this keyboard only, Android's copy stays
        assertFalse(PersonalDictionaryChoice.deletesFromPersonal(asked = true, ticked = PersonalDictionaryChoice.STRIP_DEFAULT))
        // ticked: deleted device-wide, as before
        assertTrue(PersonalDictionaryChoice.deletesFromPersonal(asked = true, ticked = true))
    }

    @Test fun `strip Remove of any other word asks nothing and goes as before`() {
        assertFalse(PersonalDictionaryChoice.stripAsks(inPersonalDictionary = false))
        assertTrue(PersonalDictionaryChoice.deletesFromPersonal(asked = false, ticked = false))
    }

    @Test fun `the settings delete box is ticked by default`() {
        assertTrue(PersonalDictionaryChoice.SETTINGS_DEFAULT)
        assertTrue(PersonalDictionaryChoice.deletesFromPersonal(asked = true, ticked = PersonalDictionaryChoice.SETTINGS_DEFAULT))
        assertFalse(PersonalDictionaryChoice.deletesFromPersonal(asked = true, ticked = false))
    }
}

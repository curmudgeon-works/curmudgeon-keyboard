// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun sameWordBeatsEverything() = assertEquals(1f, SettingsSearch.similarity("theme", "theme"))

    @Test fun beginningOfWordMatchesForSearchAsYouType() = assertTrue(SettingsSearch.similarity("vib", "vibration") > 0f)

    @Test fun sameStemMatches() {
        assertTrue(SettingsSearch.similarity("capitalize", "capitalization") > 0f)
        assertTrue(SettingsSearch.similarity("suggested", "suggestions") > 0f)
    }

    @Test fun singleTypoMatches() {
        assertTrue(SettingsSearch.similarity("vibartion", "vibration") > 0f) // swapped
        assertTrue(SettingsSearch.similarity("clipbord", "clipboard") > 0f) // missing
        assertTrue(SettingsSearch.similarity("themme", "theme") > 0f) // extra
    }

    @Test fun unrelatedWordsDoNotMatch() {
        assertEquals(0f, SettingsSearch.similarity("sound", "round"))  // one letter apart, but not a typo anyone makes
        assertEquals(0f, SettingsSearch.similarity("gesture", "clipboard"))
    }
}

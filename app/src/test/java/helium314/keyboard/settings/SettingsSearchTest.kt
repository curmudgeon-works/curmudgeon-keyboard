// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.settings.Settings
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

    // re-review 2026-10-07 (M14): the public build's search found Refine-only rows and the retired middle-suggestion switch
    @Test fun hiddenRowsStayOutOfSearch() {
        assertTrue(hiddenFromSearch(Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER))
        assertEquals(!REFINE_MENU_SHOWN, hiddenFromSearch(Settings.PREF_SUGGESTION_RULES))
        assertEquals(!REFINE_MENU_SHOWN, hiddenFromSearch(Settings.PREF_GESTURE_TURN_WEIGHT))
        assertEquals(false, hiddenFromSearch(Settings.PREF_SUGGESTION_COUNT)) // on Text correction while Refine isn't shown
        assertEquals(false, hiddenFromSearch(Settings.PREF_SHOW_NUMBER_ROW))
    }

    @Test fun unrelatedWordsDoNotMatch() {
        assertEquals(0f, SettingsSearch.similarity("sound", "round"))  // one letter apart, but not a typo anyone makes
        assertEquals(0f, SettingsSearch.similarity("gesture", "clipboard"))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.KeyboardParser
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.POPUP_KEYS_ORDER_DEFAULT
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The defaults of the first production release (0.3.008): HeliBoard's popups, long-press ?123 opens the settings. */
class LaunchDefaultsTest {
    // HeliBoard's in 0.3.008; the Curmudgeon popups again since 2026-10-07, with the Curmudgeon Layout = the defaults
    @Test fun popupsAreCurmudgeons() {
        assertEquals("all", Defaults.PREF_MORE_POPUP_KEYS)
        assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP)
        assertEquals(POPUP_KEYS_ORDER_DEFAULT, Defaults.PREF_POPUP_KEYS_ORDER)
    }

    @Test fun curmudgeonMapStaysAvailable() {
        assertTrue(KeyboardParser.isValidSymbolPopupMap(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP))
    }

    @Test fun longPressSymbolsOpensSettings() {
        assertEquals("settings", Defaults.PREF_LONG_PRESS_SYMBOL_ACTION)
    }
}

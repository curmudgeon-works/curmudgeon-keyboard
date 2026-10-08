// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** The HeliBoard Layouts' backspace waits as long as the key long press; the Curmudgeon one has its own 200 ms (2026-10-07). */
class BackspaceDelayTest {
    @Test fun `follow means the key long press, else the stored delay`() {
        assertEquals(420, SettingsValues.backspaceDelay(Defaults.BACKSPACE_DELAY_FOLLOWS, 420))
        assertEquals(200, SettingsValues.backspaceDelay(Defaults.PREF_BACKSPACE_LONGPRESS_DELAY, 150))
    }
}

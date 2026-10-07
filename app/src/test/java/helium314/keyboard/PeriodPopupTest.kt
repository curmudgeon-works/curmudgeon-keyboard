// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** Press and hold on the period key types the apostrophe (it was the comma, which has its own key), in every list. */
@RunWith(RobolectricTestRunner::class)
class PeriodPopupTest {
    @Test fun `the default list swaps comma and apostrophe`() {
        val default = listOf("!autoColumnOrder!8", "\\,", "?", "!", "#", ")", "(", "/", ";", "'", "@", ":")
        assertEquals(listOf("!autoColumnOrder!8", "'", "?", "!", "#", ")", "(", "/", ";", "\\,", "@", ":"), KeyData.apostropheFirst(default))
    }

    @Test fun `the all accents list too`() {
        val all = "!autoColumnOrder!10 \\, ? ! # ) ( / ; ' @ : - \" + \\% & · ¡ ¿".split(" ")
        val swapped = KeyData.apostropheFirst(all)
        assertEquals("'", swapped[1])
        assertEquals("\\,", swapped[all.indexOf("'")])
        assertEquals(all.sorted(), swapped.sorted())
    }

    @Test fun `without a comma key the comma stays first, the apostrophe right next to it`() {
        val default = listOf("!autoColumnOrder!8", "\\,", "?", "!", "#", ")", "(", "/", ";", "'", "@", ":")
        assertEquals(listOf("!autoColumnOrder!8", "\\,", "'", "?", "!", "#", ")", "(", "/", ";", "@", ":"),
            KeyData.apostropheFirst(default, hasCommaKey = false))
    }

    @Test fun `a list without an apostrophe or not starting with the comma stays`() {
        val bengali = "!autoColumnOrder!8 \\, ॥ ? ! # @ ( ) / ; : - + \\%".split(" ")
        assertEquals(bengali, KeyData.apostropheFirst(bengali))
        val burmese = "!autoColumnOrder!9 ၊ . ? ! # ) ( / ; ... ' @ : - \" + \\% &".split(" ")
        assertEquals(burmese, KeyData.apostropheFirst(burmese))
    }
}

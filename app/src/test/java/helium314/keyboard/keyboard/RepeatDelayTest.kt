// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A held key's repeat timing (2026-10-21, owner: "300/50 for heli / 300/200 for curm"): backspace deletes again every
 * "if you keep holding" ms, no longer every key long press delay; arrow / word / page keys repeat at that same interval.
 */
@RunWith(RobolectricTestRunner::class) // (PointerTracker's static parts need Android)
class RepeatDelayTest {
    // the Curmudgeon Layout: first delete 300, then 200, speed-up after 2 s to 50; the key long press (150) plays no part
    private fun curm(count: Int, delete: Boolean, held: Long = 0) =
        PointerTracker.repeatDelay(count, delete, 300, 200, 200, true, 2000, 50, held)

    @Test fun `backspace - first delete after its own delay, then every repeat interval`() {
        assertEquals(300, curm(1, true))
        assertEquals(200, curm(2, true, 500))
        assertEquals(200, curm(5, true, 1900))
    }

    @Test fun `backspace speeds up from the repeat interval, not from the long press`() {
        assertEquals(125, curm(9, true, 2500)) // halfway through the 1 s ramp: 200 → 50
        assertEquals(50, curm(12, true, 3500))
    }

    @Test fun `the HeliBoard Layouts - a letter every 50 ms, no speed-up`() {
        assertEquals(300, PointerTracker.repeatDelay(1, true, 300, 200, 50, false, 2000, 50, 0))
        assertEquals(50, PointerTracker.repeatDelay(3, true, 300, 200, 50, false, 2000, 50, 5000))
    }

    @Test fun `arrow keys - first move after the key repeat start, then every backspace repeat interval`() {
        assertEquals(200, curm(1, false))
        assertEquals(200, curm(2, false, 400))
        assertEquals(50, PointerTracker.repeatDelay(2, false, 300, 200, 50, false, 2000, 50, 400))
    }

    @Test fun `no speed-up when the top speed isn't faster`() {
        assertEquals(40, PointerTracker.backspaceInterval(40, true, 2000, 50, 9000))
    }
}

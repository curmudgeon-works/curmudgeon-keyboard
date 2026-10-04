// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import helium314.keyboard.latin.utils.LearnedDecay.STEP_SECONDS
import helium314.keyboard.latin.utils.LearnedDecay.TRUST_STEP_SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fading rule of learned words: steps by the time since last use, floors, no fading without a valid time. */
class LearnedDecayTest {
    private val day = 24L * 60 * 60
    private val now = 1_800_000_000L

    @Test fun `steps at the boundaries`() {
        assertEquals(0, LearnedDecay.steps(STEP_SECONDS - 1, 1, STEP_SECONDS))
        assertEquals(1, LearnedDecay.steps(STEP_SECONDS, 1, STEP_SECONDS))
        assertEquals(1, LearnedDecay.steps(2 * STEP_SECONDS - 1, 1, STEP_SECONDS))
        assertEquals(2, LearnedDecay.steps(2 * STEP_SECONDS, 1, STEP_SECONDS))
        assertEquals(3, LearnedDecay.steps(3 * STEP_SECONDS, 1, STEP_SECONDS))
        assertEquals(90 * day, STEP_SECONDS)
        assertEquals(360 * day, TRUST_STEP_SECONDS)
    }

    @Test fun `floors - 12,5 percent, 50 percent from a count of 5`() {
        assertEquals(3, LearnedDecay.steps(10 * 365 * day, 0, STEP_SECONDS))
        assertEquals(3, LearnedDecay.steps(10 * 365 * day, 4, STEP_SECONDS))
        assertEquals(1, LearnedDecay.steps(10 * 365 * day, 5, STEP_SECONDS))
        assertEquals(1, LearnedDecay.steps(10 * 365 * day, 1000, STEP_SECONDS))
        assertEquals(0, LearnedDecay.steps(STEP_SECONDS - 1, 1000, STEP_SECONDS))
    }

    @Test fun `no time or a time in the future fades nothing`() {
        assertEquals(0, LearnedDecay.steps(0L, 1, STEP_SECONDS))
        assertEquals(0, LearnedDecay.steps(-5 * STEP_SECONDS, 1, STEP_SECONDS))
        assertEquals(0, LearnedDecay.steps(0, now, 1, STEP_SECONDS)) // timestamp 0
        assertEquals(0, LearnedDecay.steps(-1, now, 1, STEP_SECONDS)) // NOT_A_TIMESTAMP
        assertEquals(0, LearnedDecay.steps((now + 1000 * day).toInt(), now, 1, STEP_SECONDS))
        assertEquals(3.0, LearnedDecay.effectiveCount(3, -1, now))
    }

    @Test fun `trust fades four times slower`() {
        val ts = { ageDays: Long -> (now - ageDays * day).toInt() }
        assertEquals(4.0, LearnedDecay.effectiveCount(4, ts(359), now))
        assertEquals(2.0, LearnedDecay.effectiveCount(4, ts(360), now))
        assertEquals(1.0, LearnedDecay.effectiveCount(4, ts(720), now))
        assertEquals(0.5, LearnedDecay.effectiveCount(4, ts(3000), now))
        assertEquals(5.0 / 2, LearnedDecay.effectiveCount(5, ts(3000), now)) // floor 50 %
    }

    @Test fun `trusted - count over 2^steps at least typed count - 1`() {
        val ts = { ageDays: Long -> (now - ageDays * day).toInt() }
        // trust at 3 uses: stored count 2
        assertTrue(LearnedDecay.isTrusted(2, ts(10), now, 3))
        assertFalse(LearnedDecay.isTrusted(1, ts(10), now, 3))
        assertFalse(LearnedDecay.isTrusted(2, ts(360), now, 3)) // 2 / 2 = 1 < 2
        assertTrue(LearnedDecay.isTrusted(4, ts(360), now, 3)) // 4 / 2 = 2
        assertFalse(LearnedDecay.isTrusted(4, ts(720), now, 3)) // 4 / 4 = 1
        assertTrue(LearnedDecay.isTrusted(5, ts(3000), now, 3)) // floor: 5 / 2
        assertFalse(LearnedDecay.isTrusted(5, ts(3000), now, 4)) // 5 / 2 < 3
        assertTrue(LearnedDecay.isTrusted(4, ts(-30), now, 5)) // used "in the future": not faded
        // trust at 1 use: any stored word, never one that isn't stored
        assertTrue(LearnedDecay.isTrusted(0, ts(3000), now, 1))
        assertFalse(LearnedDecay.isTrusted(-1, ts(10), now, 1))
    }
}

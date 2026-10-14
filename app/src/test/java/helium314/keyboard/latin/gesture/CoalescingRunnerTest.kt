// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The swipe lists' rebuild with the learned words (review round 5 M6, 2026-10-12): one at a time, requests while one
 *  runs make it run once more, not once each. */
@RunWith(RobolectricTestRunner::class)
class CoalescingRunnerTest {
    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            assertTrue(System.currentTimeMillis() < end, what)
            Thread.sleep(10)
        }
    }

    @Test fun `requests while a run is going make one more run`() {
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = CoalescingRunner("test") {
            if (runs.incrementAndGet() == 1) { started.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        runner.request()
        assertTrue(started.await(10, TimeUnit.SECONDS), "never ran")
        repeat(300) { runner.request() }
        release.countDown()
        waitFor("no second run") { runs.get() >= 2 }
        Thread.sleep(200)
        assertEquals(2, runs.get())
        // idle again: a request runs at once
        runner.request()
        waitFor("no run after idle") { runs.get() >= 3 }
        Thread.sleep(200)
        assertEquals(3, runs.get())
    }

    @Test fun `a run that fails doesn't stop the next request`() {
        val runs = AtomicInteger()
        val runner = CoalescingRunner("test") {
            if (runs.incrementAndGet() == 1) throw IllegalStateException("a rebuild that fails")
        }
        runner.request()
        waitFor("never ran") { runs.get() >= 1 }
        // (the failed run has ended once it's idle: a request then starts a new run)
        waitFor("no run after the failed one") { runner.request(); runs.get() >= 2 }
    }
}

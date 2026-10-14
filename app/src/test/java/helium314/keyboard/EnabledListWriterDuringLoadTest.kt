// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Review 2026-10-12 B2: since M1 a load reads the stored list, builds the new one aside and publishes it at the end,
 * replacing the list in use whatever it was by then. A keyboard removed or switched on in between (the stored list
 * right, the list in use changed directly) was undone in memory: the removed keyboard was back in the switching order,
 * the switched-on one missing, until the next load. Now the load holds the writers' lock from the read to the publish:
 * the writer's change waits and is made on the published list.
 *
 * Each test stops a load right after its read (the test hook) while another thread removes / switches on a keyboard,
 * and lets the load go on once that writer is done (before the fix) or waiting for the lock (with it).
 */
@RunWith(RobolectricTestRunner::class)
class EnabledListWriterDuringLoadTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a by lazy { available("en-US").withId(1) }
    private val b by lazy { available("de-DE").withId(2) }
    private val c by lazy { available("fr").withId(3) }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
    }
    @After fun tearDown() {
        SubtypeSettings.afterListReadForTest = null
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun listed(list: List<InputMethodSubtype>) = list.map { it.toSettingsSubtype() }

    /** A load (on this thread) that, once it has read the stored list, runs [write] on another thread and goes on when
     *  that thread is done or waits for the list's lock. @return after both are done */
    private fun loadWithWriterInBetween(write: () -> Unit) {
        val failures = mutableListOf<Throwable>()
        val writer = Thread {
            try { write() } catch (t: Throwable) { synchronized(failures) { failures.add(t) } }
        }
        val once = AtomicBoolean(false)
        SubtypeSettings.afterListReadForTest = {
            if (once.compareAndSet(false, true)) {
                writer.start()
                waitUntilDoneOrWaitingForTheList(writer)
            }
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        SubtypeSettings.afterListReadForTest = null
        writer.join(TimeUnit.SECONDS.toMillis(30))
        assertTrue(once.get(), "the load never read the list")
        assertTrue(!writer.isAlive, "the writer never finished")
        assertTrue(failures.isEmpty(), "the writer failed: ${failures.firstOrNull()}")
    }

    private fun waitUntilDoneOrWaitingForTheList(thread: Thread) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < end) {
            val state = thread.state
            if (state == Thread.State.TERMINATED) return
            if (state == Thread.State.BLOCKED && thread.stackTrace.any { it.methodName.contains("changeEnabledSubtypes") }) return
            Thread.sleep(2)
        }
        fail("the writer neither finished nor waited for the list")
    }

    @Test fun `a keyboard removed while the list loads stays removed`() {
        storeKeyboards(ctx, real, listOf(a, b, c))
        loadWithWriterInBetween { SubtypeSettings.removeEnabledSubtype(ctx, b.toAdditionalSubtype()) }
        assertEquals(listOf(a, c), storedEnabled(real))
        // (before: a, b, c; b back in the list in use until the next load)
        assertEquals(listOf(a, c), listed(SubtypeSettings.getEnabledSubtypes(true)))
    }

    @Test fun `a keyboard switched on while the list loads stays on`() {
        // c switched off: in the added list, not in the list
        storeKeyboards(ctx, real, listOf(a, b))
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b, c))).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(listOf(a, b), listed(SubtypeSettings.getEnabledSubtypes(true)))
        loadWithWriterInBetween { assertTrue(SwitchedOffKeyboards.switchOn(ctx, c)) }
        assertEquals(listOf(a, b, c), storedEnabled(real))
        // (before: a, b; c listed but missing from the list in use until the next load)
        assertEquals(listOf(a, b, c), listed(SubtypeSettings.getEnabledSubtypes(true)))
    }
}

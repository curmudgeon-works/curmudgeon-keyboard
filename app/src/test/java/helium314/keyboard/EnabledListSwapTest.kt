// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review 2026-10-12 M1: the enabled list was one live list, cleared and filled again keyboard by keyboard at each reload
 * (a restore reloads it on a background thread). The language key / space bar slide in another app read it in steps
 * (indexOf, size, [i]) and could find it empty or half built: % 0 and index crashes. Now a reload builds a new list
 * aside and swaps it in at once: a list handed out never changes, a reader sees the whole old one or the whole new one.
 */
@RunWith(RobolectricTestRunner::class)
class EnabledListSwapTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a by lazy { available("en-US").withId(1) }
    private val b by lazy { available("de-DE").withId(2) }
    private val c by lazy { available("fr").withId(3) }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
    }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    private fun listed(list: List<InputMethodSubtype>) = list.map { it.toSettingsSubtype() }

    @Test fun `a list handed out stays whole through a reload`() {
        storeKeyboards(ctx, real, listOf(a, b))
        val before = SubtypeSettings.getEnabledSubtypes(true)
        assertEquals(listOf(a, b), listed(before))
        // a restore writes another list and reloads (before: the same live list, now holding only c)
        storeKeyboards(ctx, real, listOf(c))
        assertEquals(listOf(a, b), listed(before))
        assertEquals(listOf(c), listed(SubtypeSettings.getEnabledSubtypes(true)))
    }

    @Test fun `the language key's steps never find the list empty or half built while it reloads`() {
        storeKeyboards(ctx, real, listOf(a, b, c))
        val failures = mutableListOf<Throwable>()
        val reloader = Thread {
            try { repeat(200) { SubtypeSettings.reloadEnabledSubtypes(ctx) } }
            catch (t: Throwable) { synchronized(failures) { failures.add(t) } }
        }
        reloader.start()
        var reads = 0
        while (reloader.isAlive || reads < 1000) {
            try {
                // as getNextSubtypeInThisIme and the space bar slide do it: one list, then indexOf, size, [i]
                val list = SubtypeSettings.getEnabledSubtypes(true)
                assertEquals(3, list.size)
                val current = list.last()
                val next = list[(list.indexOf(current) + 1) % list.size]
                assertEquals(a, next.toSettingsSubtype())
            } catch (t: Throwable) { synchronized(failures) { failures.add(t) }; break }
            reads++
        }
        reloader.join(TimeUnit.SECONDS.toMillis(60))
        assertTrue(failures.isEmpty(), "failed: ${failures.firstOrNull()}")
    }
}

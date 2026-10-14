// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review 2026-10-12 B6: the space bar's copy of the enabled list (LanguageOnSpacebarUtils) was updated in the input
 * method's background job, after the list swap; a keyboard just added could show the old space bar label. It now
 * changes in the same step as the list.
 */
@RunWith(RobolectricTestRunner::class)
class SpacebarListSameStepTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a by lazy { available("en-US").withId(1) }
    private val b by lazy { available("de-DE").withId(2) }
    private val added by lazy { available("fr").withId(3) }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        useKeyboards(ctx, real, a, b)
        RichInputMethodManager.getInstance().refreshSubtypeCaches()
        joinImeJobs()
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx)
        joinImeJobs()
    }

    private fun joinImeJobs() {
        val imeScope = RichInputMethodManager::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(RichInputMethodManager.getInstance()) as CoroutineScope
        runBlocking { imeScope.coroutineContext[Job]?.children?.forEach { it.join() } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun spacebarList() = (LanguageOnSpacebarUtils::class.java.getDeclaredField("sEnabledSubtypes")
        .apply { isAccessible = true }.get(null) as List<InputMethodSubtype>).map { it.toSettingsSubtype() }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test fun `the space bar's list holds a keyboard as soon as it is added`() {
        assertEquals(listOf(a, b), spacebarList())
        // every background thread busy: work left to the background can't run before the check below
        val threads = maxOf(2, Runtime.getRuntime().availableProcessors())
        val started = CountDownLatch(threads)
        val release = CountDownLatch(1)
        val blockers = (1..threads).map {
            GlobalScope.launch(Dispatchers.Default) { started.countDown(); release.await() }
        }
        started.await(10, TimeUnit.SECONDS)
        try {
            SubtypeSettings.addEnabledSubtype(real, added.toAdditionalSubtype())
            assertEquals(listOf(a, b, added), spacebarList())
        } finally {
            release.countDown()
            runBlocking { blockers.forEach { it.join() } }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.LearningEventLog
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.storeOneKeyboard
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Owner 2026-10-18: "Learning list should have a similar slider yes": the learning log is kept to the newest "Learning
 * entries kept" lines, trimmed once it holds a tenth more; its far end keeps all; its own switch stays off by default.
 * Fail before: no setting, nothing trimmed (compile: no PREF_LEARNING_LOG_KEPT, KEPT_ALL, keptLimit).
 */
@RunWith(RobolectricTestRunner::class)
class LearningLogKeptTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "learning_events.tsv")

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); storeOneKeyboard(ctx, real)
        file.delete()
        LearningEventLog.init(ctx)
        ctx.prefs().edit().putBoolean(Settings.PREF_LEARNING_LOG, true).commit()
    }

    @After fun tearDown() {
        file.delete()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun kept(n: Int) = ctx.prefs().edit().putInt(Settings.PREF_LEARNING_LOG_KEPT, n).commit()

    /** Entries [from]..[to] through the log, the word before saying which; waits until they are written. */
    private fun log(from: Int, to: Int) {
        for (i in from..to)
            LearningEventLog.log(LearningEventLog.ACCEPTED, LearningEventLog.TYPED, "w$i", "", "Latn", { -1 })
        Thread.sleep(1500) // (written a moment later, on the log's own thread)
    }

    private fun words() = file.readLines().map { it.split('\t')[3] }

    @Test fun `the newest entries are kept, only once a tenth over`() {
        kept(1000)
        log(1, 1100)
        assertEquals(1100, words().size) // (a tenth over: not yet)
        log(1101, 1101)
        assertEquals((102..1101).map { "w$it" }, words())
    }

    @Test fun `all at the far end never trims`() {
        kept(LearningEventLog.KEPT_ALL)
        log(1, 1500)
        assertEquals(1500, words().size)
    }

    @Test fun `a lowered setting trims at once`() {
        kept(LearningEventLog.KEPT_ALL)
        log(1, 1500)
        kept(1000)
        LearningEventLog.onKeptChanged()
        Thread.sleep(500)
        assertEquals((501..1500).map { "w$it" }, words())
    }

    @Test fun `10000 entries kept by default, the log itself still off by default`() {
        assertEquals(10_000, LearningEventLog.keptLimit())
        assertFalse(Defaults.PREF_LEARNING_LOG)
    }
}

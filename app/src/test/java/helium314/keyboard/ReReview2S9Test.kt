// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.idleUntil
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.personalization.writeList
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Re-review 2 S9 (2026-10-11): a deleted keyboard's blacklist with no words in it (only empty lines) merges as an
 * empty list, so its files go ("empty doesn't register as error"); only a list that can't be read keeps them.
 */
@RunWith(RobolectricTestRunner::class)
class ReReview2S9Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private val io = BodyStoreIo()
    private val a by lazy { available("en-US").withId(1) }
    private val b by lazy { available("de-DE").withId(2) }

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
    }
    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
    }

    /** A on [pool], B on 6 (own pools: no other test's background task logs about [pool]). */
    private fun twoKeyboards(pool: Int) {
        useKeyboards(ctx, real, a, b)
        real.edit().putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_POOLS, poolsJson(mapOf(a to pool, b to 6)))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, pool + 1).commit()
        io.store(ce, "Latn", pool, word("alpha", 2, 100))
        io.store(ce, "Latn", 6, word("beta", 1, 50))
    }

    /** Deletes A merging into B, and waits for the task's last word about [pool] (its files deleted, or kept). */
    private fun deleteAndWait(pool: Int) {
        ShadowLog.reset()
        LearnedKeyboards.deleteKeyboard(ctx, a, mergeInto = b)
        idleUntil { ShadowLog.getLogsForTag("LearnedKeyboards").any { "pool $pool" in it.msg } }
    }

    @Test fun `a blacklist of only empty lines merges as empty, and the deleted keyboard's files go`() {
        twoKeyboards(21)
        writeList(ce, "Latn", 21, "\n\n\t2\n")
        deleteAndWait(21)
        assertFalse(LearnedStores.storeFile(ce, "Latn", 21).exists())
        assertFalse(LearnedStores.blacklistFile(ce, "Latn", 21).exists())
        assertEquals(mapOf("alpha" to (2 to 100), "beta" to (1 to 50)), io.read(ce, "Latn", 6))
    }

    @Test fun `a blacklist that can't be read keeps the deleted keyboard's files`() {
        twoKeyboards(22)
        writeList(ce, "Latn", 22, "bad\n")
        val list = LearnedStores.blacklistFile(ce, "Latn", 22)
        assertTrue(list.setReadable(false))
        try {
            deleteAndWait(22)
            assertTrue(LearnedStores.storeFile(ce, "Latn", 22).exists())
            assertTrue(list.exists())
        } finally { list.setReadable(true) }
    }
}

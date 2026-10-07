// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.RemovedWords.Entry
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Share learned & blacklisted words across keyboards" switched: off copies, on puts together by the highest count. */
@RunWith(RobolectricTestRunner::class)
class LearnedPoolsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private val io = FakeLearnedStoreIo()

    private fun store(script: String, pool: Int) = LearnedStores.storeFile(dir, script, pool)
    private fun list(script: String, pool: Int) = LearnedStores.blacklistFile(dir, script, pool)
    private fun entries(script: String, pool: Int) = RemovedWords.parseAll(list(script, pool).readLines())

    @Before fun setUp() {
        // (a folder of its own per test: the list objects are kept per file for the whole process)
        dir = File(ctx.filesDir, "pools_test_${System.nanoTime()}").apply { mkdirs() }
        FakeLearnedStoreIo.store(store("Latn", 0), word("hai", 9, 100), after("ok", word = "hai", count = 4, time = 100), word("ok", 5, 90))
        FakeLearnedStoreIo.store(store("Deva", 0), word("नमस्ते", 2, 50))
        list("Latn", 0).apply { parentFile!!.mkdirs() }.writeText("teh\t2\n")
    }

    @Test fun `off - each keyboard starts with a copy of the shared words and lists`() {
        // a keyboard's own from before, of a script the shared ones don't have: not part of the copy
        FakeLearnedStoreIo.store(store("Cyrl", 2), word("да", 1, 1))
        assertTrue(LearnedPools.separate(dir, io, listOf(1, 2)))
        for (pool in listOf(1, 2)) {
            assertEquals(FakeLearnedStoreIo.read(store("Latn", 0)), FakeLearnedStoreIo.read(store("Latn", pool)))
            assertEquals(FakeLearnedStoreIo.read(store("Deva", 0)), FakeLearnedStoreIo.read(store("Deva", pool)))
            assertEquals(mapOf("teh" to Entry(2)), entries("Latn", pool))
        }
        assertFalse(store("Cyrl", 2).exists())
        // the shared ones stay as they are
        assertEquals(9 to 100, FakeLearnedStoreIo.read(store("Latn", 0))["hai"])
    }

    // re-review 2026-10-07 (M2): a keyboard added after sharing went off started with nothing
    @Test fun `a keyboard added after sharing went off starts with a copy of the shared words`() {
        assertTrue(LearnedPools.seedIfNew(dir, io, 3))
        assertEquals(FakeLearnedStoreIo.read(store("Latn", 0)), FakeLearnedStoreIo.read(store("Latn", 3)))
        assertEquals(FakeLearnedStoreIo.read(store("Deva", 0)), FakeLearnedStoreIo.read(store("Deva", 3)))
        assertEquals(mapOf("teh" to Entry(2)), entries("Latn", 3))
        // a pool with files of its own is left as it is; the shared pool is never seeded
        FakeLearnedStoreIo.store(store("Latn", 3), word("yaar", 1, 300))
        assertTrue(LearnedPools.seedIfNew(dir, io, 3))
        assertEquals(mapOf("yaar" to (1 to 300)), FakeLearnedStoreIo.read(store("Latn", 3)))
        assertTrue(LearnedPools.seedIfNew(dir, io, LearnedStores.SHARED))
        assertEquals(9 to 100, FakeLearnedStoreIo.read(store("Latn", 0))["hai"])
    }

    // reviewer 2026-10-07: a pool with files but no marker (a copy the process died in) is copied again; pools from before
    // the markers are marked once at start
    @Test fun `a half-copied pool is copied again, pools from before the markers count as seeded`() {
        FakeLearnedStoreIo.store(store("Latn", 4), word("partial", 1, 1))
        assertFalse(LearnedPools.isSeeded(dir, 4))
        assertTrue(LearnedPools.seedIfNew(dir, io, 4))
        assertTrue(LearnedPools.isSeeded(dir, 4))
        assertEquals(FakeLearnedStoreIo.read(store("Latn", 0)), FakeLearnedStoreIo.read(store("Latn", 4)))
        FakeLearnedStoreIo.store(store("Latn", 5), word("mine", 1, 1))
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        real.edit().remove("learned_pools_marked").commit()
        LearnedPools.markExistingPools(dir, real)
        assertTrue(LearnedPools.isSeeded(dir, 5))
        assertEquals(mapOf("mine" to (1 to 1)), FakeLearnedStoreIo.read(store("Latn", 5)))
        real.edit().remove("learned_pools_marked").commit()
        // sharing on again empties the pools and their markers go with them; a reset of the keyboards forgets them all
        assertTrue(LearnedPools.share(dir, io))
        assertFalse(LearnedPools.isSeeded(dir, 4))
        FakeLearnedStoreIo.store(store("Latn", 6), word("x", 1, 1)); assertTrue(LearnedPools.seedIfNew(dir, io, 6)); assertTrue(LearnedPools.isSeeded(dir, 6))
        LearnedPools.forgetSeeded(dir)
        assertFalse(LearnedPools.isSeeded(dir, 6))
    }

    @Test fun `on again - the keyboards' words put together by the highest count, not the sum, then emptied`() {
        LearnedPools.separate(dir, io, listOf(1, 2))
        // learned on since: keyboard 1 typed "hai" twice more, keyboard 2 learned "yaar" and removed "teh" once more
        FakeLearnedStoreIo.store(store("Latn", 1), word("hai", 11, 200), after("ok", word = "hai", count = 4, time = 100), word("ok", 5, 90))
        FakeLearnedStoreIo.store(store("Latn", 2), word("hai", 9, 100), after("ok", word = "hai", count = 4, time = 100), word("ok", 5, 90),
            word("yaar", 1, 300))
        RemovedWords.forFile(list("Latn", 2)).strike("teh")
        RemovedWords.forFile(list("Latn", 2)).strike("bad")

        assertTrue(LearnedPools.share(dir, io))
        assertEquals(mapOf("hai" to (11 to 200), "ok hai" to (4 to 100), "ok" to (5 to 90), "yaar" to (1 to 300)),
            FakeLearnedStoreIo.read(store("Latn", 0)))
        assertEquals(mapOf("नमस्ते" to (2 to 50)), FakeLearnedStoreIo.read(store("Deva", 0)))
        assertEquals(mapOf("teh" to Entry(3), "bad" to Entry(1)), entries("Latn", 0))
        // the keyboards' own are gone: all of it is in the shared ones
        assertTrue(LearnedStores.keyboardPoolsOnDisk(dir).isEmpty())
        assertFalse(store("Latn", 1).exists())
        assertFalse(list("Latn", 2).exists())
    }

    @Test fun `on again - a deleted keyboard's words are kept too`() {
        FakeLearnedStoreIo.store(store("Latn", 7), word("orphan", 3, 400))
        assertTrue(LearnedPools.share(dir, io))
        assertEquals(3 to 400, FakeLearnedStoreIo.read(store("Latn", 0))["orphan"])
    }

    @Test fun `a failed write leaves the setting's words as they were`() {
        io.failWrites = true
        assertFalse(LearnedPools.separate(dir, io, listOf(1)))
        assertFalse(store("Latn", 1).exists())
    }
}

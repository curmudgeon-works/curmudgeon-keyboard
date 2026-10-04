// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.RemovedWords
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The move from learned words and blacklists per language to per script, on the files as a real phone has them. */
@RunWith(RobolectricTestRunner::class)
class LearnedStoreMigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private val io = FakeLearnedStoreIo()

    private fun store(name: String) = File(dir, "UserHistoryDictionary.$name.dict")
    private fun list(name: String) = File(dir, "blacklists/$name.txt")

    @Before fun setUp() {
        dir = File(ctx.filesDir, "migration_test").apply { deleteRecursively(); mkdirs() }
        // English learned a Hinglish word typed after English ones, and one Devanagari word
        FakeLearnedStoreIo.store(store("en-US"),
            word("the", 40, 1000), word("hai", 2, 900), after("the", word = "hai", count = 1, time = 900),
            word("नमस्ते", 1, 500))
        FakeLearnedStoreIo.store(store("hi-Latn"),
            word("hai", 7, 1200), after("the", word = "hai", count = 2, time = 1100), word("the", 3, 1100), word("nahi", 4, 800))
        FakeLearnedStoreIo.store(store("hi")) // empty
        list("en-US").apply { parentFile!!.mkdirs() }.writeText("teh\t2\nboth\n")
        list("hi-Latn").writeText("both\t3\tconfirmed\nyaar\n")
    }

    @Test fun `words and pairs go to their script's store, counts added, latest use kept, old files set aside`() {
        assertTrue(LearnedStoreMigration.isNeeded(dir))
        assertTrue(LearnedStoreMigration.run(dir, io))

        assertEquals(mapOf("the" to (43 to 1100), "hai" to (9 to 1200), "the hai" to (3 to 1100), "nahi" to (4 to 800)),
            FakeLearnedStoreIo.read(store("Latn")))
        assertEquals(mapOf("नमस्ते" to (1 to 500)), FakeLearnedStoreIo.read(store("Deva")))
        assertEquals(mapOf("both" to RemovedWords.Entry(3, true), "teh" to RemovedWords.Entry(2), "yaar" to RemovedWords.Entry(1)),
            RemovedWords.parseAll(list("Latn").readLines()))

        // the old files: not deleted, set aside as they were (rollback: move them back)
        for (name in listOf("en-US", "hi-Latn", "hi")) {
            assertFalse(store(name).exists())
            assertTrue(File(dir, "${LearnedStoreMigration.PREMERGE_DIR}/UserHistoryDictionary.$name.dict").exists())
        }
        assertEquals(listOf("teh\t2", "both"), File(dir, "${LearnedStoreMigration.PREMERGE_DIR}/blacklists/en-US.txt").readLines())
        assertFalse(list("en-US").exists())
        assertFalse(File(dir, LearnedStoreFiles.WORK_DIR).exists())
        assertFalse(LearnedStoreMigration.isNeeded(dir))
    }

    @Test fun `running again changes nothing`() {
        LearnedStoreMigration.run(dir, io)
        val latin = FakeLearnedStoreIo.read(store("Latn"))
        val lists = list("Latn").readText()
        assertTrue(LearnedStoreMigration.run(dir, io))
        assertEquals(latin, FakeLearnedStoreIo.read(store("Latn")))
        assertEquals(lists, list("Latn").readText())
    }

    @Test fun `a store of the script that exists already is merged in`() {
        // e.g. learned while the phone was still locked at the first start
        FakeLearnedStoreIo.store(store("Latn"), word("hai", 1, 2000), word("fresh", 1, 2000))
        list("Latn").writeText("teh\t3\n")
        LearnedStoreMigration.run(dir, io)
        val latin = FakeLearnedStoreIo.read(store("Latn"))
        assertEquals(10 to 2000, latin["hai"])
        assertEquals(1 to 2000, latin["fresh"])
        assertEquals(RemovedWords.Entry(3), RemovedWords.parseAll(list("Latn").readLines())["teh"])
    }

    @Test fun `stopped after the journal - the next start finishes without counting twice`() {
        assertTrue(LearnedStoreMigration.prepare(dir, io))
        // (the process dies here: nothing moved yet, the merged stores wait aside)
        assertTrue(store("en-US").exists())
        assertFalse(store("Latn").exists())
        assertTrue(LearnedStoreMigration.isNeeded(dir))
        LearnedStoreMigration.run(dir, io)
        assertEquals(9 to 1200, FakeLearnedStoreIo.read(store("Latn"))["hai"])
        assertEquals(43 to 1100, FakeLearnedStoreIo.read(store("Latn"))["the"])
        assertFalse(store("en-US").exists())
        assertFalse(LearnedStoreMigration.isNeeded(dir))
    }

    @Test fun `stopped before the journal - what was written aside is thrown away and it starts over`() {
        // a merged store half written aside, no journal
        FakeLearnedStoreIo.store(File(dir, "${LearnedStoreFiles.WORK_DIR}/UserHistoryDictionary.Latn.dict"), word("hai", 999, 1))
        LearnedStoreMigration.run(dir, io)
        assertEquals(9 to 1200, FakeLearnedStoreIo.read(store("Latn"))["hai"])
    }

    @Test fun `a failed write changes nothing`() {
        io.failWrites = true
        assertFalse(LearnedStoreMigration.run(dir, io))
        assertTrue(store("en-US").exists())
        assertTrue(list("en-US").exists())
        assertFalse(store("Latn").exists())
        assertFalse(File(dir, LearnedStoreFiles.WORK_DIR).exists())
        io.failWrites = false
        assertTrue(LearnedStoreMigration.run(dir, io))
        assertEquals(9 to 1200, FakeLearnedStoreIo.read(store("Latn"))["hai"])
    }

    @Test fun `an unreadable store of the script is set aside, not in the way`() {
        store("Latn").mkdirs() // (no readable store in it)
        assertTrue(LearnedStoreMigration.run(dir, io))
        assertEquals(9 to 1200, FakeLearnedStoreIo.read(store("Latn"))["hai"])
        assertTrue(File(dir, "${LearnedStoreMigration.PREMERGE_DIR}/UserHistoryDictionary.Latn.dict").exists())
    }

    @Test fun `a store set aside before is kept, under another name`() {
        File(dir, "${LearnedStoreMigration.PREMERGE_DIR}/UserHistoryDictionary.hi.dict").mkdirs()
        LearnedStoreMigration.run(dir, io)
        assertTrue(File(dir, "${LearnedStoreMigration.PREMERGE_DIR}/UserHistoryDictionary.hi.dict.1").exists())
    }
}

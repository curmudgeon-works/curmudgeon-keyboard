// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.LearnedImport
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.utils.RemovedWords.Entry
import helium314.keyboard.settings.screens.buildWordLists
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11 "DM21 ALLOWED HERE": borrowing another keyboard's blacklist is read-only. A word blocked only through
 * a borrowed list that comes back on this keyboard gets an allowed row "word<TAB>allowed<TAB>N" on this keyboard's own
 * list; the source list is never edited. (Until then RemovedWords.Lists.takeOffBorrowed took the word off the source.)
 */
@RunWith(RobolectricTestRunner::class)
class AllowedHereTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val script = "Zzzz" // (a script code no keyboard has: the files are the test's own)
    private val poolA = 41 // the keyboard whose list is borrowed
    private val poolB = 42 // the keyboard in use, which borrows it
    private val fileA get() = LearnedStores.blacklistFile(ctx.filesDir, script, poolA)
    private val fileB get() = LearnedStores.blacklistFile(ctx.filesDir, script, poolB)
    private val a get() = RemovedWords.blacklist(ctx, script, poolA)
    private val b get() = RemovedWords.blacklist(ctx, script, poolB)
    private val lists get() = RemovedWords.Lists(b, listOf(a))

    private fun clean() {
        for (pool in listOf(poolA, poolB, 43)) {
            val file = LearnedStores.blacklistFile(ctx.filesDir, script, pool)
            file.delete()
            RemovedWords.forFile(file).reload()
        }
    }

    @Before fun setUp() = clean()
    @After fun tearDown() = clean()

    // what DictionaryFacilitatorImpl.addWordToUserHistory does once the word is typed often enough on B
    private fun typedEnoughOnB(word: String, learnedCount: Int): Boolean =
        !RemovedWords.isRemoved(lists.entryFor(word), learnedCount) && lists.allowHere(word)

    // fail before (L4.1): allow() on a list not read yet wrote the new row alone, the file's other rows were lost
    @Test fun `an allowed row on a list not read yet keeps the file's other rows`() {
        val file = java.io.File(ctx.filesDir, "blacklists${java.io.File.separator}allow-unread-${System.nanoTime()}.txt")
        file.parentFile!!.mkdirs()
        file.writeText("keep\t2\nother\n")
        val list = RemovedWords.forFile(file) // (a list object nothing read yet)
        try {
            assertTrue(list.allow("zorp", 1))
            list.reload()
            assertEquals(mapOf("keep" to Entry(2), "other" to Entry(1)), list.entries())
            assertEquals(mapOf("zorp" to 1), list.allowedEntries())
            assertEquals(setOf("keep\t2", "other", "zorp\tallowed\t1"), file.readLines().toSet())
        } finally {
            file.delete()
        }
    }

    @Test fun `a borrowed block typed enough on B becomes an allowed row on B and the source stays untouched`() {
        a.strike("zorp")
        a.reload()
        assertTrue(lists.isRemoved("zorp") { -1 }, "A's block applies on B")
        assertTrue(lists.contains("zorp"))
        // typed once on B (count 0): one strike's rule says it's back
        assertTrue(typedEnoughOnB("zorp", 0))
        b.reload(); a.reload()
        assertFalse(lists.isRemoved("zorp") { 0 }, "still blocked on B")
        assertFalse(lists.contains("zorp"), "the dictionary copy stays out on B")
        assertNull(lists.entryFor("zorp"))
        assertTrue(lists.isAllowedHere("zorp"))
        // (owner 2026-10-11: borrowing is read-only) A keeps its row
        assertEquals(mapOf("zorp" to Entry(1)), a.entries())
        assertEquals(listOf("zorp"), fileA.readLines())
        assertTrue(RemovedWords.Lists(a).isRemoved("zorp") { -1 }, "A itself must still have it blocked")
        // B's file: the allowed row, and it isn't a block
        assertEquals(listOf("zorp\tallowed\t1"), fileB.readLines())
        assertEquals(emptyMap(), b.entries())
        assertEquals(mapOf("zorp" to 1), b.allowedEntries())
        // once only
        assertFalse(lists.allowHere("zorp"))
    }

    @Test fun `more strikes on the source than the allowed row records make the exception lapse`() {
        a.strike("zorp")
        assertTrue(typedEnoughOnB("zorp", 0))
        assertFalse(lists.isRemoved("zorp") { 0 })
        a.strike("zorp") // removed once more on A
        assertTrue(lists.isRemoved("zorp") { 0 }, "the exception didn't lapse")
        assertEquals(Entry(2), lists.activeBorrowedEntry("zorp"))
        assertFalse(lists.isAllowedHere("zorp"))
        assertTrue(lists.contains("zorp"))
        // typed enough again by the 2-strike rule (3 uses: count 2): allowed at the new level
        assertFalse(typedEnoughOnB("zorp", 1))
        assertTrue(typedEnoughOnB("zorp", 2))
        b.reload()
        assertEquals(mapOf("zorp" to 2), b.allowedEntries())
        assertEquals(Entry(2), a.entry("zorp"))
    }

    @Test fun `the strip's plus on a 3rd-strike borrowed word allows it here`() {
        repeat(3) { a.strike("zorp") }
        // typed 3 times: waits for the "+"
        assertTrue(RemovedWords.awaitsConfirmation(lists.entryFor("zorp"), 2))
        assertFalse(typedEnoughOnB("zorp", 2))
        // the "+" (DictionaryFacilitatorImpl.confirmRemovedWord): allowed here, A not confirmed
        assertTrue(lists.activeBorrowedEntry("zorp") != null && lists.allowHere("zorp"))
        assertFalse(lists.isRemoved("zorp") { 2 })
        assertEquals(Entry(3, false), a.entry("zorp"))
        b.reload()
        assertEquals(mapOf("zorp" to 3), b.allowedEntries())
    }

    @Test fun `Remove on B turns the allowed row into B's own strike row combined with the borrowed strikes`() {
        a.strike("zorp"); a.strike("zorp")
        assertTrue(typedEnoughOnB("zorp", 2))
        assertEquals(2, lists.strike("zorp"), "own strikes: at least the borrowed ones (Entry.max)")
        b.reload()
        assertEquals(mapOf("zorp" to Entry(2)), b.entries())
        assertEquals(emptyMap(), b.allowedEntries())
        assertEquals(listOf("zorp\t2"), fileB.readLines())
        assertTrue(lists.isRemoved("zorp") { 0 })
        assertEquals(Entry(2), a.entry("zorp"))
        // a further Remove: one more
        assertEquals(3, lists.strike("zorp"))
        // a word not allowed here: strikes as before, the borrowed ones not added in
        a.strike("other"); a.strike("other")
        assertEquals(1, lists.strike("other"))
    }

    @Test fun `entries and the Blacklisted words screen leave allowed rows out`() {
        fileB.parentFile!!.mkdirs()
        fileB.writeText("teh\t2\nzorp\tallowed\t3\n")
        b.reload()
        assertEquals(mapOf("teh" to Entry(2)), b.entries())
        assertEquals(listOf("teh"), b.words())
        assertEquals(mapOf("zorp" to 3), b.allowedEntries())
        assertFalse(b.contains("zorp"))
        assertNull(b.entryFor("zorp"))
        val shown = buildWordLists(script, emptyList(), emptyList(), b.entries())
        assertEquals(listOf("teh"), shown.blacklisted.map { it.word })
        // the line formats
        assertNull(RemovedWords.parse("zorp\tallowed\t3"))
        assertEquals("zorp" to 3, RemovedWords.parseAllowed("zorp\tallowed\t3"))
        assertNull(RemovedWords.parseAllowed("zorp\t3"))
        assertEquals("zorp\tallowed\t3", RemovedWords.formatAllowed("zorp", 3))
        assertEquals(mapOf("teh" to Entry(2)), RemovedWords.parseAll(fileB.readLines()))
        // a word at most once: blocked wins over an allowed row of the same word
        assertEquals(emptyMap(), RemovedWords.parseAllAllowed(listOf("zorp\tallowed\t1", "zorp\t2")))
        assertEquals("teh\t2\nzorp\tallowed\t3\n", RemovedWords.formatAll(mapOf("teh" to Entry(2)), mapOf("zorp" to 3, "teh" to 1)))
    }

    @Test fun `turning borrowing off keeps the allowed rows unread`() {
        a.strike("zorp")
        assertTrue(typedEnoughOnB("zorp", 0))
        val alone = RemovedWords.Lists(b) // B without A's list
        assertFalse(alone.isRemoved("zorp") { -1 })
        assertNull(alone.entryFor("zorp"))
        b.reload()
        assertEquals(mapOf("zorp" to 1), b.allowedEntries())
        // and on again: still allowed
        assertFalse(lists.isRemoved("zorp") { -1 })
    }

    @Test fun `a restore carries allowed rows and a copy or an emptied list takes them along`() {
        val backup = listOf("teh", "zorp\tallowed\t2")
        b.combine(backup)
        assertEquals(mapOf("zorp" to 2), b.allowedEntries())
        assertEquals(mapOf("teh" to Entry(1)), b.entries())
        // a restore with a lower level keeps the higher; a blocked row stays blocked
        b.combine(listOf("zorp\tallowed\t1", "teh\tallowed\t5"))
        assertEquals(mapOf("zorp" to 2), b.allowedEntries())
        assertEquals(mapOf("teh" to Entry(1)), b.entries())
        // a copy (LearnedKeyboards.copyShared)
        val c = RemovedWords.blacklist(ctx, script, 43)
        c.replaceAll(b.entries(), b.allowedEntries())
        assertEquals(mapOf("zorp" to 2), c.allowedEntries())
        assertEquals(listOf("teh", "zorp\tallowed\t2"), LearnedStores.blacklistFile(ctx.filesDir, script, 43).readLines())
        // emptied (factory reset, a deleted keyboard)
        c.replaceAll(emptyMap())
        assertEquals(emptyMap(), c.allowedEntries())
        assertEquals(emptyList(), LearnedStores.blacklistFile(ctx.filesDir, script, 43).readLines())
        // a re-read keeps them
        b.reload()
        assertEquals(mapOf("zorp" to 2), b.allowedEntries())
    }

    @Test fun `a keyboard deleted with merge-into drops its allowed rows`() {
        fileA.parentFile!!.mkdirs()
        fileA.writeText("teh\t2\nzorp\tallowed\t1\n")
        a.reload()
        val result = LearnedImport.importNow(ctx, poolB, emptyList(), listOf(poolA))
        assertTrue(result?.complete == true)
        b.reload()
        assertEquals(mapOf("teh" to Entry(2)), b.entries())
        assertEquals(emptyMap(), b.allowedEntries())
        assertEquals(listOf("teh\t2"), fileB.readLines())
    }

    @Test fun `an allowed row replaces the own blocked row of that spelling`() {
        a.strike("zorp")
        b.strike("zorp")
        assertTrue(lists.isRemoved("zorp") { -1 })
        assertTrue(typedEnoughOnB("zorp", 0))
        b.reload()
        assertEquals(emptyMap(), b.entries())
        assertEquals(mapOf("zorp" to 1), b.allowedEntries())
        assertFalse(lists.isRemoved("zorp") { 0 })
    }
}

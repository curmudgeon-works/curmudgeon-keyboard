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

    // fail before: a Remove's disallow while a full restore ran wasn't one of the changes made again after it; its
    // pending row (the old list's own strikes) stayed and was put on top of the restored file at its next read
    @Test fun `a Remove during a full restore takes the allowed part off the restored list, its strikes from the file`() {
        val file = java.io.File(ctx.filesDir, "blacklists${java.io.File.separator}gated-${System.nanoTime()}.txt")
        file.parentFile!!.mkdirs()
        file.writeText("foo\t1\tallowed\t2\n")
        val list = RemovedWords.forFile(file)
        try {
            list.reload()
            helium314.keyboard.latin.personalization.RestoreGate.setForTest(Thread.currentThread())
            list.strike("Foo"); list.disallow("Foo") // (as DictionaryFacilitatorImpl.removeWords does)
            val ops = list.takeGated()
            file.writeText("foo\t3\tallowed\t2\n") // (the restored list of the same keyboard)
            list.reload() // (the gate still set: the writes queued meanwhile wrote nothing)
            helium314.keyboard.latin.personalization.RestoreGate.setForTest(null)
            list.redo(ops)
            assertNull(list.allowedFor("foo"))
            assertEquals(3, list.entries()["foo"]?.strikes) // (the restored file's, not the old list's 1)
        } finally {
            helium314.keyboard.latin.personalization.RestoreGate.setForTest(null)
            file.delete()
            list.reload()
        }
    }

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

    // fail before (L4.2): Remove of "Zorp" left the allowed row of "zorp", so the borrowed block stayed lifted
    @Test fun `Remove of a capitalized word clears the allowed rows of both spellings`() {
        a.strike("zorp")
        assertTrue(lists.allowHere("Zorp")) // (the row of the spelling A has: "zorp")
        assertEquals(mapOf("zorp" to 1), b.allowedEntries())
        lists.strike("Zorp")
        b.reload()
        assertEquals(emptyMap(), b.allowedEntries())
        assertNull(b.allowedFor("Zorp"))
        assertFalse(lists.isAllowedHere("zorp"))
        assertEquals(listOf("Zorp\t2"), fileB.readLines()) // (borrowed 1, allowed here: one more, M5 "Yes +1")
        // (the struck spelling another than the word removed: disallow takes the removed word's rows off)
        b.allow("Quux", 1); b.allow("quux", 1)
        b.strike("quux")
        b.disallow("Quux")
        b.reload()
        assertEquals(emptyMap(), b.allowedEntries())
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

    // owner 2026-10-12 (M5): no own row, a borrowed 2, allowed here, then Remove: 3 (until then 2, the borrowed count)
    @Test fun `Remove on B of a word allowed here without own strikes is one more than the borrowed strikes`() {
        a.strike("zorp"); a.strike("zorp")
        assertTrue(typedEnoughOnB("zorp", 2))
        assertEquals(3, lists.strike("zorp"), "the borrowed strikes + 1")
        b.reload()
        assertEquals(mapOf("zorp" to Entry(3)), b.entries())
        assertEquals(emptyMap(), b.allowedEntries())
        assertEquals(listOf("zorp\t3"), fileB.readLines())
        assertTrue(lists.isRemoved("zorp") { 0 })
        assertEquals(Entry(2), a.entry("zorp"))
        // a further Remove: one more
        assertEquals(4, lists.strike("zorp"))
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
        // owner 2026-10-12 "one row holds both": a word's own strikes and its allowed part in one row
        assertEquals(mapOf("zorp" to 1), RemovedWords.parseAllAllowed(listOf("zorp\tallowed\t1", "zorp\t2")))
        assertEquals("teh\t2\tallowed\t1\nzorp\tallowed\t3\n",
            RemovedWords.formatAll(mapOf("teh" to Entry(2)), mapOf("zorp" to 3, "teh" to 1)))
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
        // a restore with a lower level keeps the higher; a blocked row keeps its strikes, the allowed part added
        // (owner 2026-10-12: one row holds both)
        b.combine(listOf("zorp\tallowed\t1", "teh\tallowed\t5"))
        assertEquals(mapOf("zorp" to 2, "teh" to 5), b.allowedEntries())
        assertEquals(mapOf("teh" to Entry(1)), b.entries())
        // a copy (LearnedKeyboards.copyShared)
        val c = RemovedWords.blacklist(ctx, script, 43)
        c.replaceAll(b.entries(), b.allowedEntries())
        assertEquals(mapOf("zorp" to 2, "teh" to 5), c.allowedEntries())
        assertEquals(mapOf("teh" to Entry(1)), c.entries())
        assertEquals(listOf("teh\t1\tallowed\t5", "zorp\tallowed\t2"), LearnedStores.blacklistFile(ctx.filesDir, script, 43).readLines())
        // emptied (factory reset, a deleted keyboard)
        c.replaceAll(emptyMap())
        assertEquals(emptyMap(), c.allowedEntries())
        assertEquals(emptyList(), LearnedStores.blacklistFile(ctx.filesDir, script, 43).readLines())
        // a re-read keeps them
        b.reload()
        assertEquals(mapOf("zorp" to 2, "teh" to 5), b.allowedEntries())
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

    // owner 2026-10-12 "one row holds both" (until then the allowed row replaced the own one)
    @Test fun `an allowed part joins the own blocked row of that spelling`() {
        a.strike("zorp")
        b.strike("zorp")
        assertTrue(lists.isRemoved("zorp") { -1 })
        assertTrue(typedEnoughOnB("zorp", 0))
        b.reload()
        assertEquals(mapOf("zorp" to Entry(1)), b.entries())
        assertEquals(mapOf("zorp" to 1), b.allowedEntries())
        assertEquals(listOf("zorp\t1\tallowed\t1"), fileB.readLines())
        assertFalse(lists.isRemoved("zorp") { 0 })
    }

    // M5: own 3 + confirmed, a borrowed 2, allowed here (typed back), then Remove: 4 (until then the allow wiped the own
    // row and Remove gave 2)
    @Test fun `allowing a word here keeps its own strikes and Remove counts on from them`() {
        fileA.parentFile!!.mkdirs(); fileB.parentFile!!.mkdirs()
        fileA.writeText("zorp\t2\n")
        fileB.writeText("zorp\t3\tconfirmed\n")
        a.reload(); b.reload()
        assertTrue(lists.isRemoved("zorp") { 2 }, "A's 2 strikes block it on B")
        // typed 3 times on B (count 2): back by both rules
        assertTrue(typedEnoughOnB("zorp", 2))
        b.reload()
        assertEquals(mapOf("zorp" to Entry(3, true)), b.entries(), "own history kept")
        assertEquals(mapOf("zorp" to 2), b.allowedEntries())
        assertEquals(listOf("zorp\t3\tconfirmed\tallowed\t2"), fileB.readLines())
        assertFalse(lists.isRemoved("zorp") { 2 })
        assertTrue(lists.isAllowedHere("zorp"))
        // the Blacklisted words screen lists its own strikes, as before the allow
        val shown = buildWordLists(script, emptyList(), emptyList(), b.entries())
        assertEquals(listOf("zorp" to 3), shown.blacklisted.map { it.word to it.strikes })
        // Remove: own 3 + 1 (more than the borrowed 2), the allowed part gone
        assertEquals(4, lists.strike("zorp"))
        b.reload()
        assertEquals(mapOf("zorp" to Entry(4)), b.entries())
        assertEquals(emptyMap(), b.allowedEntries())
        assertEquals(listOf("zorp\t4"), fileB.readLines())
        assertTrue(lists.isRemoved("zorp") { 100 })
        assertEquals(Entry(2), a.entry("zorp"), "A's list read only")
    }

    @Test fun `Un-blacklist of a combined row keeps its allowed part`() {
        fileA.parentFile!!.mkdirs(); fileB.parentFile!!.mkdirs()
        fileA.writeText("zorp\t2\n")
        fileB.writeText("zorp\t3\tconfirmed\tallowed\t2\n")
        a.reload(); b.reload()
        assertTrue(b.remove("zorp"))
        b.reload()
        assertEquals(emptyMap(), b.entries())
        assertEquals(mapOf("zorp" to 2), b.allowedEntries())
        assertEquals(listOf("zorp\tallowed\t2"), fileB.readLines())
        assertFalse(lists.isRemoved("zorp") { -1 }, "the borrowed block stays lifted")
    }

    @Test fun `old lines still parse and a combined row round-trips and combines`() {
        // the lines before (plain, strikes, confirmed, allowed-only)
        assertEquals("teh" to Entry(1), RemovedWords.parse("teh"))
        assertEquals("teh" to Entry(2), RemovedWords.parse("teh\t2"))
        assertEquals("teh" to Entry(3, true), RemovedWords.parse("teh\t3\tconfirmed"))
        assertNull(RemovedWords.parseAllowed("teh\t3\tconfirmed"))
        assertNull(RemovedWords.parse("zorp\tallowed\t3"))
        assertEquals("zorp" to 3, RemovedWords.parseAllowed("zorp\tallowed\t3"))
        // combined rows
        for ((entry, line) in listOf(Entry(3, true) to "foo\t3\tconfirmed\tallowed\t2", Entry(1) to "foo\t1\tallowed\t2",
                Entry(4) to "foo\t4\tallowed\t2")) {
            assertEquals(line, RemovedWords.formatRow("foo", entry, 2))
            assertEquals("foo" to entry, RemovedWords.parse(line))
            assertEquals("foo" to 2, RemovedWords.parseAllowed(line))
        }
        assertEquals("foo\t2", RemovedWords.formatRow("foo", Entry(2), null))
        assertEquals("foo\tallowed\t2", RemovedWords.formatRow("foo", null, 2))
        // through the file
        fileB.parentFile!!.mkdirs()
        fileB.writeText("bar\nfoo\t3\tconfirmed\tallowed\t2\nzorp\tallowed\t1\n")
        b.reload()
        assertEquals(mapOf("bar" to Entry(1), "foo" to Entry(3, true)), b.entries())
        assertEquals(mapOf("foo" to 2, "zorp" to 1), b.allowedEntries())
        // a restore (combine): the most strikes (confirmed with equal strikes), the higher allowed level, each part
        b.combine(listOf("foo\t3\tallowed\t4", "bar\t2\tallowed\t1", "zorp\t2\tconfirmed\tallowed\t1", "new\t1\tallowed\t3"))
        b.reload()
        assertEquals(mapOf("bar" to Entry(2), "foo" to Entry(3, true), "zorp" to Entry(2, true), "new" to Entry(1)), b.entries())
        assertEquals(mapOf("foo" to 4, "bar" to 1, "zorp" to 1, "new" to 3), b.allowedEntries())
        assertEquals(listOf("bar\t2\tallowed\t1", "foo\t3\tconfirmed\tallowed\t4", "new\t1\tallowed\t3",
            "zorp\t2\tconfirmed\tallowed\t1"), fileB.readLines())
    }
}

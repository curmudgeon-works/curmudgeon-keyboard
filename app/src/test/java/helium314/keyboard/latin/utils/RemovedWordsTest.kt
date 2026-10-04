// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.RemovedWords.Entry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The blacklists are one object per file (keyboard and settings share them) and really written; the strikes rule. */
@RunWith(RobolectricTestRunner::class)
class RemovedWordsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val locale = Locale.forLanguageTag("xx-YY")
    private val file get() = File(ctx.filesDir, "blacklists/xx-YY.txt")

    @Test fun `an edit is seen by every holder and lands in the file`() {
        file.delete()
        val list = RemovedWords.blacklist(ctx, locale)
        list.reload()
        assertSame(list, RemovedWords.blacklist(ctx, locale))
        assertEquals(1, list.strike("pyramidar"))
        assertTrue(RemovedWords.blacklist(ctx, locale).contains("pyramidar"))
        list.reload() // waits for the write; the word stays
        assertEquals(listOf("pyramidar"), file.readLines())
        assertEquals(1, list.strike("other"))
        assertTrue(list.remove("pyramidar"))
        assertFalse(list.remove("pyramidar"))
        list.reload()
        assertFalse(list.contains("pyramidar"))
        assertEquals(listOf("other"), file.readLines())
        // a file replaced from outside is what counts after a re-read
        file.writeText("restored\n")
        list.reload()
        assertEquals(listOf("restored"), list.words())
    }

    @Test fun `strikes count up, are written and read back, and Un-blacklist clears them`() {
        file.delete()
        val list = RemovedWords.blacklist(ctx, locale)
        list.reload()
        assertEquals(1, list.strike("teh"))
        assertEquals(2, list.strike("teh"))
        assertEquals(3, list.strike("teh"))
        assertFalse(list.confirm("other"))
        assertTrue(list.confirm("teh"))
        assertFalse(list.confirm("teh")) // once
        list.reload()
        assertEquals(listOf("teh\t3\tconfirmed"), file.readLines())
        assertEquals(Entry(3, true), list.entry("teh"))
        // removed again: a 4th strike, and the confirmation is gone
        assertEquals(4, list.strike("teh"))
        list.reload()
        assertEquals(Entry(4, false), list.entry("teh"))
        assertEquals(listOf("teh\t4"), file.readLines())
        assertTrue(list.remove("teh"))
        assertEquals(1, list.strike("teh")) // starts over
    }

    @Test fun `old lines are one strike, and a single strike is still written as a plain word`() {
        assertEquals("word" to Entry(1), RemovedWords.parse("word"))
        assertEquals("word" to Entry(2), RemovedWords.parse("word\t2"))
        assertEquals("word" to Entry(3, true), RemovedWords.parse("word\t3\tconfirmed"))
        assertEquals("word" to Entry(1), RemovedWords.parse("word\tjunk"))
        assertEquals("word" to Entry(1), RemovedWords.parse("word\t0"))
        assertNull(RemovedWords.parse(""))
        assertEquals("word", RemovedWords.format("word", Entry(1)))
        assertEquals("word\t2", RemovedWords.format("word", Entry(2)))
        assertEquals("word\t3\tconfirmed", RemovedWords.format("word", Entry(3, true)))
        for (entry in listOf(Entry(1), Entry(2), Entry(3), Entry(3, true), Entry(7)))
            assertEquals("Hello" to entry, RemovedWords.parse(RemovedWords.format("Hello", entry)))
        // a word listed twice (e.g. two lists appended): the most strikes
        assertEquals(mapOf("a" to Entry(4), "b" to Entry(1)), RemovedWords.parseAll(listOf("a", "a\t4", "", "b", "a\t2")))
    }

    @Test fun `a restored backup adds its words and keeps the most strikes`() {
        file.delete()
        val list = RemovedWords.blacklist(ctx, locale)
        list.reload()
        list.strike("both"); list.strike("both") // 2 on the phone
        list.strike("phone")
        list.strike("more"); list.strike("more"); list.strike("more"); list.confirm("more")
        list.combine(listOf("both\t3", "backup", "phone", "more\t3"))
        assertEquals(mapOf("both" to Entry(3), "phone" to Entry(1), "backup" to Entry(1), "more" to Entry(3, true)), list.entries())
        list.reload() // as written
        assertEquals(mapOf("both" to Entry(3), "phone" to Entry(1), "backup" to Entry(1), "more" to Entry(3, true)), list.entries())
    }

    @Test fun `entryFor finds the word as typed or in lowercase`() {
        file.delete()
        val list = RemovedWords.blacklist(ctx, locale)
        list.reload()
        list.strike("hello")
        assertEquals(Entry(1), list.entryFor("Hello"))
        assertEquals(Entry(1), list.entryFor("HELLO"))
        list.strike("Hello"); list.strike("Hello")
        assertEquals(Entry(2), list.entryFor("Hello")) // the most strikes of both spellings
        assertNull(list.entryFor("world"))
    }

    // learnedCount as ExpandableBinaryDictionary.getLearnedCount gives it for a removed word: -1 not typed since,
    // 0 after the 1st use, 1 after the 2nd, 2 after the 3rd
    @Test fun `the rule`() {
        assertEquals(1, RemovedWords.requiredUses(1))
        assertEquals(3, RemovedWords.requiredUses(2))
        assertEquals(3, RemovedWords.requiredUses(3))
        assertNull(RemovedWords.requiredUses(4))
        assertNull(RemovedWords.requiredUses(10))

        assertFalse(RemovedWords.isRemoved(null, -1)) // not removed at all

        // strike 1: back with one use
        assertTrue(RemovedWords.isRemoved(Entry(1), -1))
        assertFalse(RemovedWords.isRemoved(Entry(1), 0))

        // strike 2: back with the 3rd use, no confirmation
        assertTrue(RemovedWords.isRemoved(Entry(2), -1))
        assertTrue(RemovedWords.isRemoved(Entry(2), 0))
        assertTrue(RemovedWords.isRemoved(Entry(2), 1))
        assertFalse(RemovedWords.isRemoved(Entry(2), 2))
        assertFalse(RemovedWords.awaitsConfirmation(Entry(2), 2))

        // strike 3: 3 uses, then the "+"
        assertTrue(RemovedWords.isRemoved(Entry(3), 1))
        assertFalse(RemovedWords.awaitsConfirmation(Entry(3), 1))
        assertTrue(RemovedWords.isRemoved(Entry(3), 2))
        assertTrue(RemovedWords.awaitsConfirmation(Entry(3), 2))
        assertTrue(RemovedWords.awaitsConfirmation(Entry(3), 9))
        assertFalse(RemovedWords.isRemoved(Entry(3, confirmed = true), 2))
        assertFalse(RemovedWords.awaitsConfirmation(Entry(3, confirmed = true), 2))

        // strike 4 and up: never by typing
        assertTrue(RemovedWords.isRemoved(Entry(4), 1000))
        assertTrue(RemovedWords.isRemoved(Entry(5, confirmed = true), 1000))
        assertFalse(RemovedWords.awaitsConfirmation(Entry(4), 1000))
    }

    @Test fun `the same word in two lists counts with its most strikes`() {
        assertEquals(Entry(3), Entry(3).max(Entry(2)))
        assertEquals(Entry(3), Entry(2).max(Entry(3)))
        assertEquals(Entry(3, true), Entry(3).max(Entry(3, true)))
        assertEquals(Entry(2), Entry(2).max(null))
    }
}

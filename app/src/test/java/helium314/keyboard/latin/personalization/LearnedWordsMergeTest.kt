// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.SCRIPT_DEVANAGARI
import helium314.keyboard.latin.utils.ScriptUtils.SCRIPT_LATIN
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Learned words per script: which store a word goes to, and how stores are put together. */
class LearnedWordsMergeTest {
    @Test fun `a word goes to the store of its own letters`() {
        assertEquals(SCRIPT_LATIN, ScriptUtils.scriptOfWord("hello", SCRIPT_DEVANAGARI))
        assertEquals(SCRIPT_LATIN, ScriptUtils.scriptOfWord("nahi", SCRIPT_DEVANAGARI)) // Hinglish is Latin
        assertEquals(SCRIPT_DEVANAGARI, ScriptUtils.scriptOfWord("नहीं", SCRIPT_LATIN))
        assertEquals("Cyrl", ScriptUtils.scriptOfWord("привет", SCRIPT_LATIN))
        assertEquals(SCRIPT_LATIN, ScriptUtils.scriptOfWord("Größe", SCRIPT_DEVANAGARI))
        // the first letter decides; what isn't a letter doesn't count
        assertEquals(SCRIPT_LATIN, ScriptUtils.scriptOfWord("'til", SCRIPT_DEVANAGARI))
        assertEquals(SCRIPT_DEVANAGARI, ScriptUtils.scriptOfWord("2नहीं", SCRIPT_LATIN))
        // no letters: the keyboard's
        assertEquals(SCRIPT_DEVANAGARI, ScriptUtils.scriptOfWord("123", SCRIPT_DEVANAGARI))
        assertEquals(SCRIPT_LATIN, ScriptUtils.scriptOfWord("😀", SCRIPT_LATIN))
        assertEquals(SCRIPT_LATIN, LearnedStores.scriptOf("hai", Locale.forLanguageTag("hi")))
        assertEquals(SCRIPT_DEVANAGARI, LearnedStores.scriptOf("42", Locale.forLanguageTag("hi")))
        assertEquals(SCRIPT_LATIN, LearnedStores.scriptOf("42", Locale.forLanguageTag("hi-Latn")))
    }

    @Test fun `store and list names per script and pool`() {
        assertEquals("UserHistoryDictionary.Latn", LearnedStores.storeName("Latn", LearnedStores.SHARED))
        assertEquals("UserHistoryDictionary.Deva.k4", LearnedStores.storeName("Deva", 4))
        assertEquals("Latn" to 0, LearnedStores.parseStoreFileName("UserHistoryDictionary.Latn.dict"))
        assertEquals("Deva" to 4, LearnedStores.parseStoreFileName("UserHistoryDictionary.Deva.k4.dict"))
        assertNull(LearnedStores.parseStoreFileName("UserHistoryDictionary.en-US.dict"))
        // the stores of before, per language: never taken for a script's
        assertEquals("en-US", LearnedStores.parseLanguageStoreFileName("UserHistoryDictionary.en-US.dict"))
        assertEquals("hi-Latn", LearnedStores.parseLanguageStoreFileName("UserHistoryDictionary.hi-Latn.dict"))
        assertEquals("hi", LearnedStores.parseLanguageStoreFileName("UserHistoryDictionary.hi.dict"))
        assertNull(LearnedStores.parseLanguageStoreFileName("UserHistoryDictionary.Latn.dict"))
        assertNull(LearnedStores.parseLanguageStoreFileName("UserHistoryDictionary.en-US.dict.tmp"))
        assertEquals("Latn", LearnedStores.storeLocale("Latn").script)
        assertEquals(File("/f/blacklists/Latn.txt"), LearnedStores.blacklistFile(File("/f"), "Latn", 0))
        assertEquals(File("/f/blacklists/k2/Latn.txt"), LearnedStores.blacklistFile(File("/f"), "Latn", 2))
        assertEquals("Latn", LearnedStores.label("Latn", 0))
        assertEquals("Latn/k2", LearnedStores.label("Latn", 2))
    }

    @Test fun `entries go by the script of their word, word pairs too`() {
        val entries = listOf(word("hello", 3, 1), word("नमस्ते", 2, 1), after("hello", word = "नमस्ते", count = 1, time = 1),
            word("42", 1, 1))
        val routed = byScript(entries, SCRIPT_LATIN)
        assertEquals(listOf("hello", "42"), routed[SCRIPT_LATIN]!!.map { it.word })
        assertEquals(listOf("नमस्ते", "नमस्ते"), routed[SCRIPT_DEVANAGARI]!!.map { it.word })
    }

    @Test fun `adding - counts add up per word and per pair, the latest use stays`() {
        val english = listOf(word("hai", 2, 100), word("ok", 5, 300), after("ok", word = "hai", count = 1, time = 90))
        val hinglish = listOf(word("hai", 7, 200), after("ok", word = "hai", count = 3, time = 250), word("nahi", 4, 50))
        val merged = mergeAdding(listOf(english, hinglish)).associate { FakeLearnedStoreIo.key(it) to (it.count to it.time) }
        assertEquals(mapOf("hai" to (9 to 200), "ok" to (5 to 300), "ok hai" to (4 to 250), "nahi" to (4 to 50)), merged)
    }

    @Test fun `highest - copies of one store keep the highest count, not the sum`() {
        val keyboard1 = listOf(word("hai", 9, 100), after("ok", word = "hai", count = 4, time = 100))
        val keyboard2 = listOf(word("hai", 11, 150), after("ok", word = "hai", count = 2, time = 300), word("new", 1, 10))
        val merged = mergeHighest(listOf(keyboard1, keyboard2)).associate { FakeLearnedStoreIo.key(it) to (it.count to it.time) }
        assertEquals(mapOf("hai" to (11 to 150), "ok hai" to (4 to 300), "new" to (1 to 10)), merged)
    }

    /** The native store as far as the replay needs it: counts add up for the word and each pair whose words it knows,
     *  a negative count takes uses from the word alone, the time is that of the latest update. */
    private class SimulatedStore {
        val entries = LinkedHashMap<String, Pair<Int, Int>>()
        fun update(context: List<String>, word: String, count: Int, time: Int) {
            val old = entries[word]
            if (count < 0) {
                if (old != null) entries[word] = maxOf(0, old.first + count) to old.second
                return
            }
            entries[word] = ((old?.first ?: 0) + count) to time
            for (n in 1..context.size) {
                val words = context.take(n)
                if (words.any { it != LearnedEntry.SENTENCE_START && it !in entries }) break
                val key = (words.reversed() + word).joinToString(" ")
                entries[key] = ((entries[key]?.first ?: 0) + count) to time
            }
        }
    }

    private fun replayed(entries: List<LearnedEntry>) = SimulatedStore().apply { replay(entries) { c, w, n, t -> update(c, w, n, t) } }.entries

    @Test fun `replay gives back every count and last use`() {
        val entries = listOf(
            word("thank", 7, 100), after("thank", word = "you", count = 6, time = 120),
            after("and", "thank", word = "you", count = 4, time = 110), word("you", 10, 130),
            word("and", 3, 90), after("see", word = "you", count = 2, time = 80), word("see", 2, 80),
            word("solo", 0, 50), // typed once, no dictionary word: stored at 0
        )
        val expected = entries.associate { FakeLearnedStoreIo.key(it) to (it.count to it.time) }
        assertEquals(expected, replayed(entries))
    }

    @Test fun `replay takes back what pairs counted beyond the word's own count`() {
        // a use taken back from the word alone (a reverted auto-correction): the pair still has it
        val entries = listOf(word("ok", 2, 10), word("teh", 0, 20), after("ok", word = "teh", count = 1, time = 20))
        val result = replayed(entries)
        assertEquals(0 to 20, result["teh"])
        assertEquals(1 to 20, result["ok teh"])
        // highest counts of two stores: the pairs together can be more than the word's highest count
        val highest = mergeHighest(listOf(
            listOf(word("hai", 3, 10), after("ok", word = "hai", count = 3, time = 10), word("ok", 3, 10)),
            listOf(word("hai", 3, 20), after("na", word = "hai", count = 3, time = 20), word("na", 3, 20)),
        ))
        assertEquals(3, replayed(highest)["hai"]!!.first)
    }

    @Test fun `a pair whose word before went to another script's store still counts its word`() {
        val result = replayed(listOf(word("नमस्ते", 2, 10), after("hello", word = "नमस्ते", count = 1, time = 10)))
        assertEquals(2 to 10, result["नमस्ते"])
        assertNull(result["hello नमस्ते"])
    }
}

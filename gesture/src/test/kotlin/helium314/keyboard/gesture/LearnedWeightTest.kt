// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The learned-word boost and "suggest learned words" are applied when a swipe is scored, not baked into the list. */
class LearnedWeightTest {
    private val geometry = QwertyFixture.geometry
    private val scorer = HybridScorer()

    private fun node(v: Vocabulary, word: String) =
        v.wordsByEnds(word.first().lowercaseChar(), word.last().lowercaseChar()).single { it.word.equals(word, ignoreCase = true) }

    @Test fun `a word's weight and spelling come from the settings at scoring`() {
        val v = Vocabulary(listOf("the" to 222, "iphone" to 100))
        v.addLearned("iPhone", 80)
        v.addLearned("curmudgeon", 150)
        val curmudgeon = node(v, "curmudgeon")
        assertEquals(214, curmudgeon.weight(64, true))
        assertEquals(150, curmudgeon.weight(0, true))
        assertEquals(0, curmudgeon.weight(64, false)) // learned only, learned words off: not a word
        assertNull(curmudgeon.wordFor(64, false))
        assertEquals("curmudgeon", curmudgeon.wordFor(64, true))
        val iphone = node(v, "iphone")
        assertEquals(100, iphone.weight(0, true)) // the dictionary's weight wins
        assertEquals("iphone", iphone.wordFor(0, true))
        assertEquals(144, iphone.weight(64, true)) // the boosted learned weight wins, with its spelling
        assertEquals("iPhone", iphone.wordFor(64, true))
        assertEquals(100, iphone.weight(64, false))
        assertEquals("iphone", iphone.wordFor(64, false))
        v.addLearned("zebra", 250)
        assertEquals(255, node(v, "zebra").weight(64, true)) // capped as before
        assertEquals(255, v.maxWeight(64, true))
        assertEquals(222, v.maxWeight(64, false))
    }

    @Test fun `a learned word joins the first and last letter index and leaves with remove`() {
        val v = Vocabulary(listOf("the" to 222))
        v.firstChars() // index built first: adding must keep it up to date
        v.addLearned("curmudgeon", 150)
        assertEquals(2, v.size)
        assertEquals(150, node(v, "curmudgeon").learned)
        assertTrue(v.remove("curmudgeon"))
        assertTrue(v.wordsByEnds('c', 'n').isEmpty())
        assertEquals(1, v.size)
    }

    @Test fun `the same results as the list with the boost baked in`() {
        val learned = listOf("hello" to 120, "help" to 200, "hell" to 30, "jelly" to 90)
        val baked = Vocabulary(TestVocabulary.entries).apply { for ((w, x) in learned) add(w, (x + 64).coerceIn(1, 255)) }
        val apart = Vocabulary(TestVocabulary.entries).apply { for ((w, x) in learned) addLearned(w, x) }
        for (word in listOf("hello", "help", "hell", "jelly", "the")) for (seed in listOf(1L, 7L)) {
            val path = SyntheticPathGenerator.noisyPath(word, geometry, seed)
            val decoder = GestureDecoder(scorer, DecoderConfig(learnedBoost = 64))
            assertEquals(decoder.decode(path, geometry, baked, maxResults = 10), decoder.decode(path, geometry, apart, maxResults = 10), "$word/$seed")
        }
    }

    @Test fun `changing the boost or the switch needs no rebuild`() {
        val v = Vocabulary(TestVocabulary.entries).apply { addLearned("curmudgeon", 150) }
        val path = SyntheticPathGenerator.idealPath("curmudgeon", geometry)
        fun top(boost: Int, on: Boolean) = GestureDecoder(scorer, DecoderConfig(learnedBoost = boost, learnedWords = on))
            .decode(path, geometry, v, maxResults = 10)
        assertEquals("curmudgeon", top(64, true).first().word)
        assertEquals(214, top(64, true).first().frequency)
        assertEquals(250, top(100, true).first { it.word == "curmudgeon" }.frequency) // same list, other boost
        assertTrue(top(64, false).none { it.word == "curmudgeon" }) // same list, learned words off
    }
}

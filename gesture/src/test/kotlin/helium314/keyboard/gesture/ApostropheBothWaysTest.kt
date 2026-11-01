// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The apostrophe setting on means both ways (owner 2026-10-31: "on means both ways"): an apostrophe word is scored
 * with its period-key detour and without it, and keeps the better score. A swipe through the period key favors the
 * contraction over the same letters without it; a swipe that skips the apostrophe still gives the contraction, at a
 * small soft cost ([DecoderConfig.apostropheSkipPenalty]). Off is unchanged: the apostrophe is skipped, only.
 * Fail before: the setting on meant the detour only, so "youre" swiped without it did not give "you're".
 */
class ApostropheBothWaysTest {
    private val vocab = TestVocabulary.vocabulary
    private val keys = QwertyFixture.geometry.keys
    /** The setting's old meaning: through the period key, only. */
    private val periodOnly = QwertyFixture.geometry
    /** The setting off: the apostrophe is skipped. */
    private val skip = KeyboardGeometry(keys, apostropheViaPeriod = false)
    /** The setting on: both ways. */
    private val both = KeyboardGeometry(keys, apostropheViaPeriod = true, apostropheBothWays = true)
    private val scorers = listOf(KushlerScorer(), LocationScorer(), HybridScorer())

    private fun decode(scorer: Scorer, path: List<GesturePoint>, geometry: KeyboardGeometry, config: DecoderConfig = DecoderConfig()) =
        GestureDecoder(scorer, config).decode(path, geometry, vocab, maxResults = 10)

    @Test
    fun `on - only the setting on has a second way`() {
        assertNull(skip.apostropheSkipView)
        assertNull(periodOnly.apostropheSkipView)
        val view = both.apostropheSkipView!!
        assertEquals(false, view.apostropheViaPeriod)
        assertNull(view.apostropheSkipView)
        assertEquals(listOf('d', 'o', 'n', 't'), SokgraphBuilder.build("don't", view)!!.points.map { it.char })
        assertEquals(5, SokgraphBuilder.build("don't", both)!!.points.size)
    }

    @Test
    fun `on - a swipe through the period key puts the contraction above the same letters without it`() {
        for ((word, twin) in listOf("we're" to "were", "it's" to "its")) {
            val path = SyntheticPathGenerator.idealPath(word, periodOnly) // dips to the period key
            for (scorer in scorers) {
                val words = decode(scorer, path, both).map { it.word.lowercase() }
                assertEquals(word, words.firstOrNull(), "${scorer.name}/$word: got ${words.take(4)}, $twin at ${words.indexOf(twin)}")
            }
        }
    }

    @Test
    fun `on - a swipe that skips the apostrophe still gives the contraction, listed once`() {
        for ((swiped, word) in listOf("dont" to "don't", "youre" to "you're", "didnt" to "didn't", "isnt" to "isn't")) {
            val path = SyntheticPathGenerator.idealPath(swiped, skip)
            for (scorer in scorers) {
                val words = decode(scorer, path, both).map { it.word.lowercase() }
                assertEquals(word, words.firstOrNull(), "${scorer.name}/$swiped: got ${words.take(4)}")
                assertEquals(1, words.count { it == word }, "${scorer.name}/$swiped: $word listed ${words.count { it == word }} times")
            }
        }
    }

    @Test
    fun `on - the skipped way carries the soft cost, the detour none`() {
        val path = SyntheticPathGenerator.idealPath("youre", skip)
        val scorer = HybridScorer()
        val costed = decode(scorer, path, both).first { it.word == "you're" }
        val free = decode(scorer, path, both, DecoderConfig(apostropheSkipPenalty = 0f)).first { it.word == "you're" }
        val asOff = decode(scorer, path, skip).first { it.word == "you're" }
        assertEquals(free.rawScore, costed.rawScore) // the same match; the cost is on the rank
        assertEquals(free.score * 1.05f, costed.score, 1e-4f) // the default, 0.05
        assertEquals(asOff.score, free.score, 1e-5f) // without the cost, as with the setting off
        assertEquals(free.score * 1.2f, decode(scorer, path, both, DecoderConfig(apostropheSkipPenalty = 0.2f)).first { it.word == "you're" }.score, 1e-4f)
        // the detour way is not charged
        val detour = SyntheticPathGenerator.idealPath("you're", periodOnly)
        assertEquals(decode(scorer, detour, periodOnly).first { it.word == "you're" }.score,
            decode(scorer, detour, both, DecoderConfig(apostropheSkipPenalty = 0.5f)).first { it.word == "you're" }.score, 1e-5f)
    }

    @Test
    fun `off - unchanged, the apostrophe is skipped only`() {
        // a swipe through the period key: the detour counts against the contraction, as before
        val detour = SyntheticPathGenerator.idealPath("we're", periodOnly)
        for (scorer in scorers) {
            val words = decode(scorer, detour, skip).map { it.word.lowercase() }
            assertTrue(words.firstOrNull() != "we're", "${scorer.name}: got ${words.take(4)}")
            if ("were" in words && "we're" in words) assertTrue(words.indexOf("were") < words.indexOf("we're"), "${scorer.name}: $words")
        }
        // a swipe without it gives the contraction, as before, and the same as with the setting on (no cost off)
        val plain = SyntheticPathGenerator.idealPath("youre", skip)
        for (scorer in scorers) {
            val off = decode(scorer, plain, skip)
            assertEquals("you're", off.firstOrNull()?.word, "${scorer.name}: got ${off.take(4).map { it.word }}")
            assertEquals(off.map { it.word }, decode(scorer, plain, both, DecoderConfig(apostropheSkipPenalty = 0f)).map { it.word }, scorer.name)
        }
    }

    @Test
    fun `plain words are the same both ways`() {
        for (word in listOf("in", "on", "hello", "water", "people", "keyboard")) {
            val path = SyntheticPathGenerator.idealPath(word, skip)
            assertEquals(word, decode(HybridScorer(), path, both).firstOrNull()?.word)
            // (without the cost: an apostrophe word in the list scores as with the setting off)
            assertEquals(decode(HybridScorer(), path, skip).map { it.word to it.score },
                decode(HybridScorer(), path, both, DecoderConfig(apostropheSkipPenalty = 0f)).map { it.word to it.score }, word)
        }
    }
}

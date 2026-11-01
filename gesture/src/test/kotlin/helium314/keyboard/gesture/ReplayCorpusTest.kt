// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The replay scores apostrophe words the way the device did for each swipe: with the apostrophe setting the corpus
 * recorded for it (on = both ways), else skipped, the device's default before the setting was recorded (2026-10-31).
 * Fail before: one setting for every swipe, skipped unless GESTURE_REPLAY_APOSTROPHE=period, so a "what's" swiped
 * through the period key on a device with the setting on counted as a miss (or, with the variable, every "whats"
 * swiped without it did).
 */
class ReplayCorpusTest {
    private val keys = QwertyFixture.geometry.keys
    private val vocab = Vocabulary(TestVocabulary.entries + listOf("what's" to 150, "whats" to 10))
    private val skip = KeyboardGeometry(keys, apostropheViaPeriod = false)

    /** The "phone" variant's decoder, as ReplayTest builds it: the device's. */
    private fun phone() = ReplaySpec.decoder("phone")

    private fun rank(s: ReplayCorpus.Swipe) = phone().decode(s.points, s.geometry, vocab, 50).indexOfFirst { it.word.equals(s.label, ignoreCase = true) }

    @Test
    fun `each swipe is scored with the apostrophe setting the device decoded it with`() {
        // recorded with the setting off: "what's" swiped as w-h-a-t-s
        val off = ReplayCorpus.line(1, "what's", "what's", keys, SyntheticPathGenerator.idealPath("whats", skip), apostrophe = ReplayCorpus.SKIP)
        // recorded with the setting on: swiped through the period key
        val on = ReplayCorpus.line(2, "what's", "what's", keys, SyntheticPathGenerator.idealPath("what's", QwertyFixture.geometry), apostrophe = ReplayCorpus.PERIOD)
        // and on, swiped without the detour: both ways
        val onPlain = ReplayCorpus.line(3, "what's", "what's", keys, SyntheticPathGenerator.idealPath("whats", skip), apostrophe = ReplayCorpus.PERIOD)
        val swipes = listOf(off, on, onPlain).map { ReplayCorpus.parse(it, null) }
        assertEquals(listOf(ReplayCorpus.SKIP, ReplayCorpus.PERIOD, ReplayCorpus.PERIOD), swipes.map { it.apostrophe })
        assertEquals(false, swipes[0].geometry.apostropheViaPeriod)
        assertNull(swipes[0].geometry.apostropheSkipView)
        assertTrue(swipes[1].geometry.apostropheViaPeriod && swipes[1].geometry.apostropheSkipView != null)
        for (s in swipes) assertEquals(0, rank(s), "#${s.id} (${s.apostrophe}): ${phone().decode(s.points, s.geometry, vocab, 5).map { it.word }}")
    }

    @Test
    fun `a swipe without a recorded setting is scored skipped, as the device did before it was recorded`() {
        val plain = ReplayCorpus.parse(ReplayCorpus.line(1, "what's", "what's", keys, SyntheticPathGenerator.idealPath("whats", skip)), null)
        assertEquals("", plain.apostrophe)
        assertEquals(false, plain.geometry.apostropheViaPeriod)
        assertEquals(0, rank(plain))
        // an older corpus line without the two trailing columns parses the same
        val old = ReplayCorpus.line(1, "what's", "what's", keys, SyntheticPathGenerator.idealPath("whats", skip)).split('\t').take(8).joinToString("\t")
        assertEquals("", ReplayCorpus.parse(old, null).session)
        assertEquals(false, ReplayCorpus.parse(old, null).geometry.apostropheViaPeriod)
    }

    @Test
    fun `the environment variable still forces one setting on every swipe`() {
        val line = ReplayCorpus.line(1, "what's", "what's", keys, SyntheticPathGenerator.idealPath("whats", skip), apostrophe = ReplayCorpus.SKIP)
        assertTrue(ReplayCorpus.parse(line, ReplayCorpus.PERIOD).geometry.apostropheViaPeriod)
        assertTrue(ReplayCorpus.parse(line, ReplayCorpus.BOTH).geometry.apostropheSkipView != null)
        assertEquals(false, ReplayCorpus.parse(line.replace("\t${ReplayCorpus.SKIP}", "\t${ReplayCorpus.PERIOD}"), ReplayCorpus.SKIP).geometry.apostropheViaPeriod)
    }
}

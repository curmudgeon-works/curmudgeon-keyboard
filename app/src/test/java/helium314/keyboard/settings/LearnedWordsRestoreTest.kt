// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.settings.preferences.LearnedEntry
import helium314.keyboard.settings.preferences.LearnedEntry.Companion.SENTENCE_START
import helium314.keyboard.settings.preferences.ownCounts
import kotlin.test.Test
import kotlin.test.assertEquals

/** Restoring learned words: each entry replays its own uses, the longer entries containing it replay the rest. */
class LearnedWordsRestoreTest {
    @Test fun `own counts - word minus pairs, pair minus 3 words`() {
        val entries = listOf(
            LearnedEntry("you", emptyList(), 10, 1),
            LearnedEntry("you", listOf("thank"), 6, 1),
            LearnedEntry("you", listOf("see"), 2, 1),
            LearnedEntry("you", listOf("thank", "and"), 4, 1),
            LearnedEntry("you", listOf("thank", SENTENCE_START), 1, 1),
            LearnedEntry("thank", emptyList(), 7, 1),
            LearnedEntry("thank", listOf(SENTENCE_START), 1, 1),
            LearnedEntry("solo", emptyList(), 0, 1), // typed once, no dictionary word: stored at 0
        )
        // replayed, they give back the stored counts: "you" 2 + 1 + 2 + 4 + 1 = 10, "you" after "thank" 1 + 4 + 1 = 6
        assertEquals(listOf(2, 1, 2, 4, 1, 6, 1, 0), ownCounts(entries).toList())
    }

    @Test fun `own counts never go negative`() {
        val entries = listOf(LearnedEntry("a", emptyList(), 1, 1), LearnedEntry("a", listOf("b"), 3, 1))
        assertEquals(listOf(0, 3), ownCounts(entries).toList())
    }
}

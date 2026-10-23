// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.gesture.GestureCorpusRecorder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The word before a swipe, as the swipe corpus records it (2026-10-18, to measure context for ties like its / it's). */
class SwipePreviousWordTest {
    @Test fun `a word before the swipe is recorded as it is`() =
        assertEquals("the", GestureCorpusRecorder.previousWord(NgramContext(NgramContext.WordInfo("the"))))

    @Test fun `the start of a sentence is recorded as a marker`() =
        assertEquals("<s>", GestureCorpusRecorder.previousWord(NgramContext.BEGINNING_OF_SENTENCE))

    @Test fun `no word before the swipe is recorded as empty`() =
        assertEquals("", GestureCorpusRecorder.previousWord(NgramContext.EMPTY_PREV_WORDS_INFO))
}

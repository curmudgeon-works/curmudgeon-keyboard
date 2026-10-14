// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.gesture.Vocabulary
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary.LocaleSpec
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-10-10, review 2026-10-08 area 5 lows: a broken dictionary held up a multilingual keyboard's swipes (#5); a
 *  language with an empty word list first on a keyboard made the merge index reference[-1]. */
@RunWith(RobolectricTestRunner::class)
class SwipeDictionaryLowsTest {
    private val en = Locale.US
    private val broken = Locale.forLanguageTag("xq") // (a tag no other test uses: the failed-build note is global)

    @Test fun `a language whose dictionary failed to build doesn't hold up the keyboard's swipe list`() {
        val specs = listOf(LocaleSpec(en, 1f), LocaleSpec(broken, 0.85f))
        fun listFor(l: Locale): Vocabulary? = if (l == en) Vocabulary(listOf("the" to 222)) else null
        assertFalse(GestureDecoderVocabulary.mergeable(specs, ::listFor, GestureDecoderVocabulary::nothingToWaitFor))
        GestureDecoderVocabulary.noteBuildFailed("xq") // (what loadOrBuild does for a file with no words)
        assertTrue(GestureDecoderVocabulary.mergeable(specs, ::listFor, GestureDecoderVocabulary::nothingToWaitFor))
        // the rule before: only "no dictionary at all" counted, so the keyboard waited for ever
        assertFalse(GestureDecoderVocabulary.mergeable(specs, ::listFor) { false })
    }

    @Test fun `an empty first word list doesn't break the merge`() {
        val vocab = Vocabulary(emptyList())
        val specs = listOf(LocaleSpec(Locale.forLanguageTag("hi-Latn"), 1f), LocaleSpec(en, 0.85f))
        val entries = mapOf("hi-Latn" to emptyList<Pair<String, Int>>(), "en-US" to listOf("the" to 222, "of" to 214))
        GestureDecoderVocabulary.mergeInto(vocab, specs, reference = emptyList(), entriesFor = { entries[it] },
            withoutExcluded = { _, e -> e }, learnedFor = { emptyList() })
        assertEquals(2, vocab.size)
    }
}

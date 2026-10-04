// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.ContextWrapper
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VocabularyExclusionTest {
    private val context = ContextWrapper(null)
    private val default = GestureDecoderVocabulary.exclusion
    private val entries = listOf("hello" to 200, "Pyramidar" to 10, "world" to 190)

    @AfterTest fun restore() { GestureDecoderVocabulary.exclusion = default }

    @Test fun removedWordKeepsOnlyItsLearnedCopy() {
        val removed = setOf("hello")
        GestureDecoderVocabulary.exclusion = GestureDecoderVocabulary.WordExclusion { _, _, word, learned ->
            !learned && word.lowercase() in removed
        }
        assertEquals(listOf("Pyramidar", "world"),
            GestureDecoderVocabulary.withoutExcluded(context, Locale.US, entries, learned = false).map { it.first })
        assertEquals(entries, GestureDecoderVocabulary.withoutExcluded(context, Locale.US, entries, learned = true))
    }

    @Test fun deletedWordStaysOutEvenLearned() {
        val removed = setOf("hello")
        val deleted = setOf("pyramidar")
        GestureDecoderVocabulary.exclusion = GestureDecoderVocabulary.WordExclusion { _, _, word, learned ->
            (!learned && word.lowercase() in removed) || word.lowercase() in deleted
        }
        assertEquals(listOf("world"),
            GestureDecoderVocabulary.withoutExcluded(context, Locale.US, entries, learned = false).map { it.first })
        assertEquals(listOf("hello", "world"),
            GestureDecoderVocabulary.withoutExcluded(context, Locale.US, entries, learned = true).map { it.first })
    }
}

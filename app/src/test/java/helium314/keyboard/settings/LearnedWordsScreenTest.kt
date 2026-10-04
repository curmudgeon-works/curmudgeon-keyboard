// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.settings.screens.PersonalEntry
import helium314.keyboard.settings.screens.Word
import helium314.keyboard.settings.screens.buildWordLists
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Learned & blacklisted words": what one script's two lists show, read from its store and list. */
class LearnedWordsScreenTest {
    private fun personal(word: String, locale: String?) =
        PersonalEntry(Word(word, null, null), locale?.let { Locale.forLanguageTag(it) })

    @Test fun `the script's learned words with the personal dictionary's of that script, each word once`() {
        val lists = buildWordLists("Latn",
            learned = listOf("Hai" to 2, "hai" to 7, "nahi" to 1),
            personal = listOf(personal("hai", "hi-Latn") to "Latn", personal("Ravi", null) to "Latn",
                personal("नमस्ते", "hi") to "Deva", personal("yaar", "hi") to "Deva"), // (a Latin word in Hindi's: Latin)
            blacklist = emptyMap())
        val yours = lists.yours.associateBy { it.word }
        assertEquals(listOf("hai", "nahi", "ravi", "yaar"), lists.yours.map { it.word.lowercase() })
        // the most typed spelling shows, the counts add up
        assertEquals(9, yours.getValue("hai").typed)
        assertEquals(setOf("Hai", "hai"), yours.getValue("hai").spellings)
        assertEquals(1, yours.getValue("hai").personal.size)
        assertEquals(0, yours.getValue("Ravi").typed)
        assertEquals(0, yours.getValue("yaar").typed)
    }

    @Test fun `the blacklist with each word's strikes, spellings together`() {
        val lists = buildWordLists("Latn", emptyList(), emptyList(),
            mapOf("teh" to RemovedWords.Entry(2), "Teh" to RemovedWords.Entry(1), "yaar" to RemovedWords.Entry(4)))
        assertEquals(listOf("teh", "yaar"), lists.blacklisted.map { it.word.lowercase() })
        val teh = lists.blacklisted.first()
        assertEquals(2, teh.strikes)
        assertEquals(setOf("teh", "Teh"), teh.listed.toSet())
        assertEquals(4, lists.blacklisted.last().strikes)
    }
}

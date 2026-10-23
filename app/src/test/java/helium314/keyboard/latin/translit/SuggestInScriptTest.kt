// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Suggest in हिंदी: the table as the app reads it, and the strip it makes. */
@RunWith(RobolectricTestRunner::class)
class SuggestInScriptTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val table: TranslitTable by lazy { assertNotNull(Transliteration.load(ctx, Transliteration.specFor("hi-Latn")!!)) }

    private fun typed(word: String) = SuggestedWordInfo(word, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED,
        Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
    private fun suggestion(word: String, score: Int = 100) = SuggestedWordInfo(word, "", score, SuggestedWordInfo.KIND_CORRECTION,
        Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

    // as Suggest makes them: the typed word first, the auto-correction (if any) second
    private fun typing(vararg infos: SuggestedWordInfo, autoCorrect: Boolean = false) =
        SuggestedWords(ArrayList(infos.toList()), null, infos[0], false, autoCorrect, false, SuggestedWords.INPUT_STYLE_TYPING, 1)
    private fun swiping(vararg infos: SuggestedWordInfo) =
        SuggestedWords(ArrayList(infos.toList()), null, infos[0], true, false, false, SuggestedWords.INPUT_STYLE_TAIL_BATCH, 1)

    private fun SuggestedWords.words() = (0 until size()).map { getWord(it) }
    private fun SuggestedWords.chips() = (0 until size()).filter { getInfo(it).isOtherScript }.map { getWord(it) }
    private val noOtherLanguage: (String) -> Boolean = { false }

    // ---- the table ----

    @Test fun `a word's Devanagari come best first, in the table's score order`() {
        // hi_translit.top100k.tsv: bohot बहुत -5.014, बोहोत -6.489, बहोत -7.084
        assertEquals(listOf("बहुत", "बोहोत", "बहोत"), table.lookup("bohot"))
        assertEquals("है", table.lookup("hai").first())
        assertEquals(listOf("मीटिंग"), table.lookup("meeting"))
    }

    @Test fun `at most three, capitals looked up in lowercase`() {
        assertEquals(3, table.lookup("hai").size) // the table has 23
        assertEquals(table.lookup("bohot"), table.lookup("Bohot"))
        assertEquals(table.lookup("bohot"), table.lookup("BOHOT"))
    }

    @Test fun `a word the table doesn't have, or not of a to z, has none`() {
        assertEquals(emptyList(), table.lookup("zzzzqx"))
        assertEquals(emptyList(), table.lookup("bohot!"))
        assertEquals(emptyList(), table.lookup("don't"))
        assertEquals(emptyList(), table.lookup("bohot hai"))
        assertEquals(emptyList(), table.lookup(""))
        // the first and the last key of the table are found, nothing after the last
        assertTrue(table.lookup("a").isNotEmpty())
        assertTrue(table.lookup("zz").isNotEmpty())
        assertEquals(emptyList(), table.lookup("zzzzzz"))
    }

    // ---- Some ----

    @Test fun `Some puts the Devanagari right after the word space types, on a chip`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohot"), suggestion("bahut")), Transliteration.SOME, table, true, noOtherLanguage)
        assertEquals(listOf("bohot", "बहुत", "bahut"), strip.words())
        assertEquals(listOf("बहुत"), strip.chips())
        assertFalse(strip.mWillAutoCorrect) // space still types "bohot"
        assertEquals("bohot", (strip.getInfo(1) as TranslitWordInfo).source)
    }

    @Test fun `Some with an auto-correction looks up the auto-correction`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohat"), suggestion("bohot"), suggestion("bahut"), autoCorrect = true),
            Transliteration.SOME, table, true, noOtherLanguage)
        assertEquals(listOf("bohat", "bohot", "बहुत", "bahut"), strip.words())
        assertTrue(strip.mWillAutoCorrect)
        assertEquals("bohot", strip.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION).mWord) // space types it
    }

    @Test fun `Some for a swipe`() {
        val strip = SuggestInScript.forStrip(swiping(suggestion("bohot"), suggestion("bahut")), Transliteration.SOME, table, true, noOtherLanguage)
        assertEquals(listOf("bohot", "बहुत", "bahut"), strip.words())
        assertEquals("bohot", strip.mTypedWordInfo?.mWord) // the swipe puts in the Latin word
    }

    @Test fun `a word the table doesn't have leaves the strip as it is`() {
        val words = typing(typed("zzzzqx"), suggestion("bahut"))
        assertEquals(words, SuggestInScript.forStrip(words, Transliteration.SOME, table, true, noOtherLanguage))
        assertEquals(words, SuggestInScript.forStrip(words, Transliteration.OFF, table, true, noOtherLanguage))
    }

    @Test fun `predictions are left as they are`() {
        val predictions = SuggestedWords(arrayListOf(suggestion("hai")), null, null, false, false, false,
            SuggestedWords.INPUT_STYLE_PREDICTION, 1)
        assertEquals(predictions, SuggestInScript.forStrip(predictions, Transliteration.ALL, table, true, noOtherLanguage))
    }

    @Test fun `after a pick of another word its Devanagari is offered once, to put in place`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohot"), suggestion("bahut")), Transliteration.SOME, table, true, noOtherLanguage)
        val offered = assertNotNull(SuggestInScript.afterPick("nahi", strip, Transliteration.SOME, table))
        assertEquals("नहीं", offered.mWord)
        assertTrue(offered.inPlace)
        assertTrue(offered.isOtherScript)
        // the word whose Devanagari the strip showed: nothing more
        assertNull(SuggestInScript.afterPick("bohot", strip, Transliteration.SOME, table))
        assertNull(SuggestInScript.afterPick("zzzzqx", strip, Transliteration.SOME, table))
        assertNull(SuggestInScript.afterPick("nahi", strip, Transliteration.ALL, table))
        val predictions = SuggestedWords(arrayListOf(suggestion("hai")), null, null, false, false, false,
            SuggestedWords.INPUT_STYLE_PREDICTION, 1)
        assertEquals(listOf("नहीं", "hai"), SuggestInScript.withFirst(predictions, offered).words())
    }

    // ---- All ----

    @Test fun `All makes the Devanagari what space types, the typed Latin word one tap away`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohot"), suggestion("bahut")), Transliteration.ALL, table, true, noOtherLanguage)
        assertEquals(listOf("bohot", "बहुत", "बोहोत", "बहोत", "bahut"), strip.words())
        assertTrue(strip.mWillAutoCorrect)
        assertEquals("बहुत", strip.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION).mWord)
        assertEquals(listOf("bohot"), strip.chips())
        assertTrue(strip.getInfo(0).isKindOf(SuggestedWordInfo.KIND_TYPED)) // still the typed word, first
    }

    @Test fun `All with an auto-correction puts the corrected Latin word on the chip`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohat"), suggestion("bohot"), suggestion("bahut"), autoCorrect = true),
            Transliteration.ALL, table, true, noOtherLanguage)
        assertEquals(listOf("bohat", "बहुत", "bohot", "बोहोत", "बहोत", "bahut"), strip.words())
        assertEquals(listOf("bohot"), strip.chips())
        assertTrue(strip.mWillAutoCorrect)
    }

    @Test fun `All leaves a word of another language of the keyboard in Latin`() {
        val strip = SuggestInScript.forStrip(typing(typed("meeting")), Transliteration.ALL, table, true) { it == "meeting" }
        assertEquals(listOf("meeting", "मीटिंग"), strip.words())
        assertEquals(listOf("मीटिंग"), strip.chips())
        assertFalse(strip.mWillAutoCorrect)
    }

    @Test fun `All with auto-correction off - space types what was typed`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohot")), Transliteration.ALL, table, false, noOtherLanguage)
        assertEquals(listOf("bohot", "बहुत"), strip.words())
        assertFalse(strip.mWillAutoCorrect)
    }

    @Test fun `All for a swipe puts in the Devanagari`() {
        val strip = SuggestInScript.forStrip(swiping(suggestion("bohot"), suggestion("bahut")), Transliteration.ALL, table, true, noOtherLanguage)
        assertEquals(listOf("बहुत", "bohot", "बोहोत", "बहोत", "bahut"), strip.words())
        assertEquals("बहुत", strip.mTypedWordInfo?.mWord)
        assertEquals(listOf("bohot"), strip.chips())
    }

    @Test fun `the Devanagari is learned as its Latin word`() {
        val strip = SuggestInScript.forStrip(swiping(suggestion("bohot")), Transliteration.ALL, table, true, noOtherLanguage)
        assertEquals("bohot", SuggestInScript.learnedAs("बहुत", strip))
        assertEquals("bohot", SuggestInScript.learnedAs("बोहोत", strip))
        assertEquals("bahut", SuggestInScript.learnedAs("bahut", strip))
    }
}

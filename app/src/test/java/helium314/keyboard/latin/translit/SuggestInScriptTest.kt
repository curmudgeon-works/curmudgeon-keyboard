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

    // ---- the reverse index (owner 2026-10-25: "Let's go the reverse index apparch after all, with one-time build
    // instead of shipping. Agree that the natural place/time is when you first load it from apk") ----

    private val spec get() = Transliteration.specFor("hi-Latn")!!
    private val reverse: TranslitTable by lazy { table; assertNotNull(Transliteration.reverse(spec), "the index built right after the table") }
    private fun reverseFile() = java.io.File(ctx.noBackupFilesDir, "translit/hi-Latn.bin.rev")
    private fun headerInts(file: java.io.File) = java.nio.ByteBuffer.wrap(file.readBytes().copyOf(8)).let { it.getInt(0) to it.getInt(4) }

    @Test fun `the reverse index - a Devanagari word's Latin spellings, each with the word's rank in its list`() {
        // the table: haan [हां, हैं, हाँ], haa [हां, …], han [हैं, हां, है], haam [हैं, हां, हम], ham [हम, हैं, हां]
        val spellings = reverse.spellings("हां").associate { it.latin to it.rank }
        assertEquals(mapOf("haa" to 0, "haan" to 0, "haam" to 1, "han" to 1, "ham" to 2), spellings)
        assertEquals(listOf(0, 0, 1, 1, 2), reverse.spellings("हां").map { it.rank }) // rank order
        for (s in reverse.spellings("बहुत")) assertEquals("बहुत", table.lookup(s.latin)[s.rank], s.latin)
        assertEquals(14, reverse.spellings("बहुत").size)
    }

    @Test fun `the reverse index - nothing for a Latin word, a word it has none for, or a word of another script`() {
        assertEquals(emptyList(), reverse.spellings("haan"))
        assertEquals(emptyList(), reverse.spellings("ज़्ज़्ज़"))
        assertEquals(emptyList(), reverse.spellings(""))
        assertEquals(emptyList(), reverse.spellings("हां!"))
        assertEquals(emptyList(), reverse.lookup("haan")) // the index answers no Latin lookups
        assertEquals(emptyList(), table.spellings("हां")) // the table no reverse ones
    }

    @Test fun `the reverse index - a file beside the table's copy, built again when the table changes`() {
        // (the table is read once per JVM: an earlier test class may have built the index under its own files)
        assertNotNull(TranslitTable.loadReverse(ctx, spec.asset, table))
        val file = reverseFile()
        assertTrue(file.isFile && file.length() > 1_000_000, "the index file: ${file.length()} bytes")
        assertEquals(0x48545231 to table.crc, headerInts(file)) // "HTR1" + the table's checksum
        // the file of another table (the checksum differs): built afresh
        val stale = file.readBytes().also { java.nio.ByteBuffer.wrap(it).putInt(4, table.crc + 1) }
        file.writeBytes(stale)
        val rebuilt = assertNotNull(TranslitTable.loadReverse(ctx, spec.asset, table))
        assertEquals(0x48545231 to table.crc, headerInts(file))
        assertEquals(reverse.spellings("हां").map { it.latin }, rebuilt.spellings("हां").map { it.latin })
        // the file as it is: read as it is (not built again: its bytes stay)
        val before = file.lastModified()
        assertNotNull(TranslitTable.loadReverse(ctx, spec.asset, table))
        assertEquals(before, file.lastModified())
    }

    @Test fun `the strip rebuilt from the index - a learned spelling first, then the dictionary's most frequent, then the table's rank`() {
        val restored = mutableListOf(typed("हां"))
        val learned = mapOf("ham" to 1)
        val frequency = mapOf("haan" to 100, "haa" to 5)
        val best = SuggestInScript.rebuilt(restored, reverse.spellings("हां"), { learned[it] ?: -1 }, { frequency[it] ?: -1 })
        assertEquals("ham", best)
        // two spellings only (owner 2026-10-25: "this should be 4th+ word. So हां, haan, haa, and then onwards" the Latin strip)
        assertEquals(listOf("हां", "ham", "haan"), restored.map { it.mWord })
        assertTrue(restored.drop(1).all { it.isKindOf(SuggestedWordInfo.KIND_RESUMED) })
        // nothing learned, nothing in the dictionary: the table's rank, then the spelling
        val plain = mutableListOf(typed("हां"))
        assertEquals("haa", SuggestInScript.rebuilt(plain, reverse.spellings("हां"), { -1 }, { -1 }))
        assertEquals(listOf("हां", "haa", "haan"), plain.map { it.mWord })
        // no spellings: nothing added, no Latin word
        val none = mutableListOf(typed("ज़्ज़्ज़"))
        assertNull(SuggestInScript.rebuilt(none, emptyList(), { -1 }, { -1 }))
        assertEquals(1, none.size)
    }

    @Test fun `the strip rebuilt from the index - from the fourth word on, the Latin strip of the first spelling`() {
        val restored = mutableListOf(typed("हां"))
        val frequency = mapOf("haan" to 100, "haa" to 5)
        SuggestInScript.rebuilt(restored, reverse.spellings("हां"), { -1 }, { frequency[it] ?: -1 })
        val rebuilt = SuggestedWords(ArrayList(restored), null, restored[0], false, false, false, SuggestedWords.INPUT_STYLE_RECORRECTION, 0)
        // typing haan: haan, then the dictionary's words for it (haa and Haan among them: already there, or new)
        val latin = typing(typed("haan"), suggestion("jaan"), suggestion("haa"), suggestion("vaan"), suggestion("Haan"), suggestion("naam"))
        val strip = SuggestInScript.withLatinStrip(rebuilt, latin)
        assertEquals(listOf("हां", "haan", "haa", "jaan", "vaan", "Haan", "naam"), strip.words())
        assertEquals("हां", strip.mTypedWordInfo?.mWord)
        assertEquals(SuggestedWords.INPUT_STYLE_RECORRECTION, strip.mInputStyle)
        assertFalse(strip.mWillAutoCorrect)
        // nothing from the dictionary: the strip as it was
        assertEquals(listOf("हां", "haan", "haa"), SuggestInScript.withLatinStrip(rebuilt, SuggestedWords.getEmptyInstance()).words())
    }

    // ---- the table ----

    @Test fun `a word's Devanagari come best first, in the table's score order`() {
        // hi_translit.top100k.tsv: bohot बहुत -5.014, बोहोत -6.489, बहोत -7.084
        assertEquals(listOf("बहुत", "बोहोत", "बहोत"), table.lookup("bohot"))
        assertEquals("है", table.lookup("hai").first())
        assertEquals(listOf("मीटिंग"), table.lookup("meeting"))
    }

    // the gold list of the dictionaries' translit/gold.tsv (owner 2026-10-25: "kya should give क्या and actually never
    // give किया . nai should defo prioritize नहीं over नई / short forms should defo be included"). Fail before: the
    // table's own answers (किया first, नई, ही, टीम)
    @Test fun `the gold list - chat spellings answered as meant, kya never as किया`() {
        assertEquals("क्या", table.lookup("kya").first())
        assertFalse("किया" in table.lookup("kya"))
        assertEquals(listOf("नहीं", "नई"), table.lookup("nai").take(2))
        assertEquals("है", table.lookup("h").first())
        assertEquals("तुम", table.lookup("tm").first())
        assertEquals(listOf("हाँ", "हां"), table.lookup("han").take(2))
        assertEquals("क्योंकि", table.lookup("kyuki").first())
        assertEquals("कुछ", table.lookup("kch").first())
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
        val strip = SuggestInScript.forStrip(typing(typed("bohot"), suggestion("bahut")), Transliteration.SOME, table, noOtherLanguage)
        assertEquals(listOf("bohot", "बहुत", "bahut"), strip.words())
        assertEquals(listOf("बहुत"), strip.chips())
        assertFalse(strip.mWillAutoCorrect) // space still types "bohot"
        assertEquals("bohot", (strip.getInfo(1) as TranslitWordInfo).source)
    }

    @Test fun `Some with an auto-correction looks up the auto-correction`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohat"), suggestion("bohot"), suggestion("bahut"), autoCorrect = true),
            Transliteration.SOME, table, noOtherLanguage)
        assertEquals(listOf("bohat", "bohot", "बहुत", "bahut"), strip.words())
        assertTrue(strip.mWillAutoCorrect)
        assertEquals("bohot", strip.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION).mWord) // space types it
    }

    @Test fun `Some for a swipe`() {
        val strip = SuggestInScript.forStrip(swiping(suggestion("bohot"), suggestion("bahut")), Transliteration.SOME, table, noOtherLanguage)
        assertEquals(listOf("bohot", "बहुत", "bahut"), strip.words())
        assertEquals("bohot", strip.mTypedWordInfo?.mWord) // the swipe puts in the Latin word
    }

    @Test fun `a word the table doesn't have leaves the strip as it is`() {
        val words = typing(typed("zzzzqx"), suggestion("bahut"))
        assertEquals(words, SuggestInScript.forStrip(words, Transliteration.SOME, table, noOtherLanguage))
        assertEquals(words, SuggestInScript.forStrip(words, Transliteration.OFF, table, noOtherLanguage))
    }

    @Test fun `predictions are left as they are`() {
        val predictions = SuggestedWords(arrayListOf(suggestion("hai")), null, null, false, false, false,
            SuggestedWords.INPUT_STYLE_PREDICTION, 1)
        assertEquals(predictions, SuggestInScript.forStrip(predictions, Transliteration.ALL, table, noOtherLanguage))
    }

    @Test fun `after a pick of another word its Devanagari is offered once, to put in place`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohot"), suggestion("bahut")), Transliteration.SOME, table, noOtherLanguage)
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
        val strip = SuggestInScript.forStrip(typing(typed("bohot"), suggestion("bahut")), Transliteration.ALL, table, noOtherLanguage)
        assertEquals(listOf("bohot", "बहुत", "बोहोत", "बहोत", "bahut"), strip.words())
        assertTrue(strip.mWillAutoCorrect)
        assertEquals("बहुत", strip.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION).mWord)
        assertEquals(listOf("bohot"), strip.chips())
        assertTrue(strip.getInfo(0).isKindOf(SuggestedWordInfo.KIND_TYPED)) // still the typed word, first
    }

    @Test fun `All with an auto-correction puts the corrected Latin word on the chip`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohat"), suggestion("bohot"), suggestion("bahut"), autoCorrect = true),
            Transliteration.ALL, table, noOtherLanguage)
        assertEquals(listOf("bohat", "बहुत", "bohot", "बोहोत", "बहोत", "bahut"), strip.words())
        assertEquals(listOf("bohot"), strip.chips())
        assertTrue(strip.mWillAutoCorrect)
    }

    @Test fun `All leaves a word of another language of the keyboard in Latin`() {
        val strip = SuggestInScript.forStrip(typing(typed("meeting")), Transliteration.ALL, table) { it == "meeting" }
        assertEquals(listOf("meeting", "मीटिंग"), strip.words())
        assertEquals(listOf("मीटिंग"), strip.chips())
        assertFalse(strip.mWillAutoCorrect)
    }

    @Test fun `All for a typed word that is the top word - the Devanagari is the auto-correction`() {
        val strip = SuggestInScript.forStrip(typing(typed("bohot")), Transliteration.ALL, table, noOtherLanguage)
        assertEquals("बहुत", strip.getInfo(SuggestedWords.INDEX_OF_AUTO_CORRECTION).mWord)
        assertTrue(strip.mWillAutoCorrect)
    }

    @Test fun `All for a swipe puts in the Devanagari`() {
        val strip = SuggestInScript.forStrip(swiping(suggestion("bohot"), suggestion("bahut")), Transliteration.ALL, table, noOtherLanguage)
        assertEquals(listOf("बहुत", "bohot", "बोहोत", "बहोत", "bahut"), strip.words())
        assertEquals("बहुत", strip.mTypedWordInfo?.mWord)
        assertEquals(listOf("bohot"), strip.chips())
    }

    @Test fun `the Devanagari is learned as its Latin word`() {
        val strip = SuggestInScript.forStrip(swiping(suggestion("bohot")), Transliteration.ALL, table, noOtherLanguage)
        assertEquals("bohot", SuggestInScript.learnedAs("बहुत", strip))
        assertEquals("bohot", SuggestInScript.learnedAs("बोहोत", strip))
        assertEquals("bahut", SuggestInScript.learnedAs("bahut", strip))
    }
}

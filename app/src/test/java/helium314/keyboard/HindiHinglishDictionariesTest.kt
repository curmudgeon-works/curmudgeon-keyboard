// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2026-10-10, tester bug L: Hindi + Hinglish dictionaries bundled; each keyboard must get its own script's list. */
class HindiHinglishDictionariesTest {
    @Test fun `each keyboard gets the bundled dictionary of its own script`() {
        val bundled = File("src/main/assets/dicts").list()!!.filter { it.startsWith("main_") }
        assertTrue("main_hi.dict" in bundled && "main_hi-Latn.dict" in bundled)
        fun pick(tag: String) =
            LocaleUtils.getBestMatch(tag.constructLocale(), bundled) { DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it) }
        assertEquals("main_hi-Latn.dict", pick("hi-Latn"))
        assertEquals("main_hi.dict", pick("hi"))
        // the swipe list's old rule (same language code, first file listed) can't tell them apart: both files match
        assertEquals(listOf("main_hi-Latn.dict", "main_hi.dict"),
            bundled.filter { it.substringAfter("_").substringBefore(".dict").constructLocale().language == "hi" }.sorted())
    }
}

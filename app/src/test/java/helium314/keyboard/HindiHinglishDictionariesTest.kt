// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-10-10, tester bug L: Hinglish bundled (Hindi a download since the same day); each keyboard gets its own script's list. */
class HindiHinglishDictionariesTest {
    @Test fun `each keyboard gets the bundled dictionary of its own script`() {
        val bundled = File("src/main/assets/dicts").list()!!.filter { it.startsWith("main_") }
        assertTrue("main_hi-Latn.dict" in bundled)
        fun pick(tag: String) =
            LocaleUtils.getBestMatch(tag.constructLocale(), bundled) { DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it) }
        assertEquals("main_hi-Latn.dict", pick("hi-Latn"))
        assertNull(pick("hi")) // (Hindi in Devanagari never takes the Latin list)
    }
}

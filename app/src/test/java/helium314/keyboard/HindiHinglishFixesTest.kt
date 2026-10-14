// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2026-10-10: tester bug M (spacebar said "Hindi" for Hinglish), bug L (Hindi + Hinglish dictionaries bundled; each
 *  keyboard must get its own script's list), and the Hindi phonetic layout typing nukta letters the dictionary lacks. */
@RunWith(RobolectricTestRunner::class)
class HindiHinglishFixesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val hinglish = "hi-Latn".constructLocale()
    private val hindi = "hi".constructLocale()

    @Before fun setUp() { SubtypeLocaleUtils.init(ctx) }

    @Test fun `a second language on the spacebar is called Hinglish, not Hindi`() {
        // what the spacebar used before: Java's own name
        assertEquals("Hindi", hinglish.getDisplayLanguage(java.util.Locale.ENGLISH))
        val kept = helium314.keyboard.keyboard.MainKeyboardViewAccess.withoutDuplicateLanguages(listOf(hinglish), "English")
        assertEquals(listOf("Hinglish"), kept.map { SubtypeLocaleUtils.getSubtypeLanguageDisplayName(it) })
    }

    @Test fun `Hindi plus Hinglish shows both, English twice shows once`() {
        val hindiName = SubtypeLocaleUtils.getSubtypeLanguageDisplayName(hindi)
        assertEquals(listOf(hinglish),
            helium314.keyboard.keyboard.MainKeyboardViewAccess.withoutDuplicateLanguages(listOf(hinglish), hindiName))
        val english = SubtypeLocaleUtils.getSubtypeLanguageDisplayName("en-US".constructLocale())
        assertEquals(emptyList(),
            helium314.keyboard.keyboard.MainKeyboardViewAccess.withoutDuplicateLanguages(listOf("en-GB".constructLocale()), english))
    }

    @Test fun `the short spacebar form names Hinglish, not HI`() {
        assertEquals("EN", helium314.keyboard.keyboard.MainKeyboardViewAccess.shortLanguageName("en-US".constructLocale()))
        assertEquals("Hinglish", helium314.keyboard.keyboard.MainKeyboardViewAccess.shortLanguageName(hinglish))
    }

    @Test fun `second languages in the language list's order - priority, then the order added`() {
        val prefs = ctx.getSharedPreferences("order_test", Context.MODE_PRIVATE)
        val de = "de".constructLocale(); val fr = "fr".constructLocale()
        helium314.keyboard.latin.utils.LanguagePriority.set(prefs, de, helium314.keyboard.latin.utils.LanguagePriority.LOW)
        helium314.keyboard.latin.utils.LanguagePriority.set(prefs, fr, helium314.keyboard.latin.utils.LanguagePriority.HIGH)
        assertEquals(listOf(fr, de), helium314.keyboard.latin.utils.LanguagePriority.inListOrder(prefs, listOf(de, fr)))
    }

    @Test fun `layouts type nukta letters in the dictionary's form`() {
        // NFC splits क़ (U+0958) into क + ़; the Hindi dictionary is NFC, so a key typing U+0958 never matches it
        val bad = File("src/main/assets/layouts").walk().filter { it.isFile }
            .filter { f -> f.readText().let { it != Normalizer.normalize(it, Normalizer.Form.NFC) && it.any { c -> c in 'क़'..'य़' } } }
            .map { it.name }.toList()
        assertEquals(emptyList(), bad)
    }
}

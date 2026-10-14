// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.dictionary.DictionarySelection
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.dictionaryDeleteBlockers
import helium314.keyboard.settings.dialogs.dictionaryDisplayName
import helium314.keyboard.settings.dialogs.deleteDictionary
import helium314.keyboard.settings.dialogs.dictionaryInUse
import helium314.keyboard.settings.dialogs.removeDictionaryQuestion
import helium314.keyboard.settings.dialogs.replaceAndDelete
import helium314.keyboard.settings.screens.keyboardName
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Round-5 review B6, owner 2026-10-13 (20261013_L12): the "in use" dialog when the trash can is tapped on a dictionary a
 * keyboard uses names the dictionary and the keyboards using it, and says what frees it by case; the only button besides
 * Cancel/OK replaces it with the language's next dictionary and deletes it, offered only when the keyboard asking is the
 * only one using it and there is another. Fail before (compile: one text for every case, "turn the language off on this
 * keyboard", wrong with no keyboard, a main language or several keyboards; the raw kind "main" as the title; no Replace).
 */
@RunWith(RobolectricTestRunner::class)
class DictionaryInUseDialogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val en by lazy { available("en-US").withId(1) }
    private val en2 by lazy { available("en-US").withId(3) }
    private val da by lazy { available("da").withId(2) } // (no built-in Danish dictionary)
    private val danish = Locale.forLanguageTag("da")
    private fun dir(locale: Locale) = File(DictionaryInfoUtils.getCacheDirectoryForLocale(locale, ctx)!!)

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        for (l in listOf(Locale.US, danish)) { dir(l).deleteRecursively(); dir(l).mkdirs() }
    }
    @After fun tearDown() {
        for (l in listOf(Locale.US, danish)) dir(l).deleteRecursively()
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun name(kb: SettingsSubtype) = keyboardName(kb, ctx)
    private fun dialog(locale: Locale, dict: File, keyboard: SettingsSubtype?) =
        dictionaryInUse(ctx, locale, dict, keyboard, dictionaryDeleteBlockers(ctx, locale, dict))

    @Test fun `case 1 - the only keyboard using it, the built-in there - replaced with it and deleted`() {
        useKeyboards(ctx, real, en)
        val main = File(dir(Locale.US), "main_user.dict").apply { writeText("x") } // (over the built-in English one)
        val d = dialog(Locale.US, main, en)
        assertEquals("“Main dictionary” is in use", d.title)
        assertEquals("Used by: ${name(en)}. To delete it, select another version first.", d.message)
        assertEquals("Replace with Internal main dictionary and delete this one", d.replaceButton)
        replaceAndDelete(ctx, Locale.US, main, d.replaceWith!!)
        assertFalse(main.exists())
        assertEquals(DictionarySelection.INTERNAL, DictionarySelection.selected(ctx, Locale.US))
    }

    @Test fun `case 1 - no built-in, the next added one of the kind replaces it`() {
        useKeyboards(ctx, real, da)
        val main = File(dir(danish), "main_user.dict").apply { writeText("x") }
        val prev = File(dir(danish), "main_prev_user.dict").apply { writeText("y") }
        File(dir(danish), "emoji_user.dict").writeText("e") // (another kind: never the replacement)
        val d = dialog(danish, main, da)
        assertEquals("Replace with Main dictionary (main_prev) and delete this one", d.replaceButton)
        replaceAndDelete(ctx, danish, main, d.replaceWith!!)
        assertFalse(main.exists())
        assertEquals(prev.name, DictionarySelection.selected(ctx, danish))
        // the one now in use, with the other one gone: the only one
        val again = dialog(danish, prev, da)
        assertEquals("“Main dictionary (main_prev)” is in use", again.title)
        assertNull(again.replaceButton)
    }

    @Test fun `case 2 - the only keyboard using it, no other dictionary - OK only`() {
        useKeyboards(ctx, real, da)
        val main = File(dir(danish), "main_user.dict").apply { writeText("x") }
        val d = dialog(danish, main, da)
        assertEquals("Used by: ${name(da)}. It is the only dictionary for this language, so it can't be deleted while a keyboard uses the language.", d.message)
        assertNull(d.replaceButton)
        assertNull(d.replaceWith)
    }

    // 20261013_L12-NAMES (owner: "yep"): one name for a dictionary everywhere, its rows too. Fail before: an added main
    // one other than the default was its raw file name ("main_prev2") here and "Main dictionary" on its row, the
    // emoji one "emoji"
    @Test fun `names - built-in, the default main, another main, another kind`() {
        assertEquals("Internal main dictionary", dictionaryDisplayName(ctx, DictionarySelection.INTERNAL))
        assertEquals("Main dictionary", dictionaryDisplayName(ctx, "main_user.dict"))
        assertEquals("Main dictionary (main_prev2)", dictionaryDisplayName(ctx, "main_prev2_user.dict"))
        assertEquals("Emoji dictionary (emoji)", dictionaryDisplayName(ctx, "emoji_user.dict"))
        assertEquals("Emoji dictionary (emoji_prev)", dictionaryDisplayName(ctx, "emoji_prev_user.dict"))
        useKeyboards(ctx, real, da)
        File(dir(danish), "main_user.dict").writeText("x")
        val emoji = File(dir(danish), "emoji_user.dict").apply { writeText("e") }
        val prev = File(dir(danish), "emoji_prev_user.dict").apply { writeText("f") }
        val d = dialog(danish, emoji, da)
        assertEquals("“Emoji dictionary (emoji)” is in use", d.title)
        assertEquals("Replace with Emoji dictionary (emoji_prev) and delete this one", d.replaceButton)
        prev.delete()
        // 20261013_F4 (owner: "4. A"): case 2 of another kind names the kind. Fail before: "the only dictionary"
        assertEquals("Used by: ${name(da)}. It is the only emoji dictionary for this language, so it can't be deleted while a keyboard uses the language.",
            dialog(danish, emoji, da).message)
    }

    // round-6 review 20261013_L3_2: the plain delete's question names the dictionary as everywhere else. Fail before
    // (compile: the question was inline): "main" for both added main ones
    @Test fun `the plain delete's question names the dictionary`() {
        assertEquals("Really remove user-added dictionary \"Main dictionary\"?",
            removeDictionaryQuestion(ctx, File(dir(danish), "main_user.dict")))
        assertEquals("Really remove user-added dictionary \"Main dictionary (main_prev)\"?",
            removeDictionaryQuestion(ctx, File(dir(danish), "main_prev_user.dict")))
    }

    // 20261013_F6 (owner: "6. A"): a plain delete (not in use) redraws the dialog's list, through the callback Replace
    // uses. Fail before (compile: the delete was inline in the dialog and called nothing; its row stayed)
    @Test fun `a plain delete redraws the list`() {
        useKeyboards(ctx, real, da)
        File(dir(danish), "main_user.dict").writeText("x")
        val prev = File(dir(danish), "main_prev_user.dict").apply { writeText("y") }
        assertEquals(emptyList(), dictionaryDeleteBlockers(ctx, danish, prev)) // (not in use: the plain delete)
        var redrawn = 0
        deleteDictionary(ctx, danish, prev) { redrawn++ }
        assertFalse(prev.exists())
        assertEquals(1, redrawn)
    }

    @Test fun `case 3 - other keyboards use it too - those keyboards, OK only`() {
        useKeyboards(ctx, real, en, en2)
        val main = File(dir(Locale.US), "main_user.dict").apply { writeText("x") }
        val d = dialog(Locale.US, main, en)
        assertEquals("Used by: ${name(en)}, ${name(en2)}. To delete it, select another version for those keyboards first.", d.message)
        assertNull(d.replaceButton)
    }

    // round-6 review 20261013_L2_2 (owner 2026-10-13: "don't offer to delete/replace for multiple keyboards"): several
    // keyboards and no other version: the "only one" text, no Replace. Fail before: "select another version for those
    // keyboards first", though there is none
    @Test fun `case 3 - other keyboards use it too, no other version - the only one, OK only`() {
        val da2 = available("da").withId(4)
        useKeyboards(ctx, real, da, da2)
        val main = File(dir(danish), "main_user.dict").apply { writeText("x") }
        val d = dialog(danish, main, da)
        assertEquals("Used by: ${name(da)}, ${name(da2)}. It is the only dictionary for this language, so it can't be deleted while a keyboard uses the language.", d.message)
        assertNull(d.replaceButton)
        assertNull(d.replaceWith)
        // another version there: "select another version for those keyboards", still no Replace
        File(dir(danish), "main_prev_user.dict").writeText("y")
        val again = dialog(danish, main, da)
        assertEquals("Used by: ${name(da)}, ${name(da2)}. To delete it, select another version for those keyboards first.", again.message)
        assertNull(again.replaceButton)
    }

    @Test fun `case 4 - from the Dictionaries screen - that keyboard or those keyboards, OK only`() {
        useKeyboards(ctx, real, en)
        val main = File(dir(Locale.US), "main_user.dict").apply { writeText("x") }
        val one = dialog(Locale.US, main, null)
        assertEquals("Used by: ${name(en)}. To delete it, select another version for that keyboard first.", one.message)
        assertNull(one.replaceButton)
        useKeyboards(ctx, real, en, en2)
        assertEquals("Used by: ${name(en)}, ${name(en2)}. To delete it, select another version for those keyboards first.",
            dialog(Locale.US, main, null).message)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.dictionary.DictionarySelection
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.settings.screens.getUserAndInternalDictionaries
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "DICTIONARY CHOICE" (2026-10-12): a language's main dictionary is the one selected in its dictionary dialog (the
 *  internal one or an added file), for every keyboard; with none selected "main_user.dict", else the internal one,
 *  never a restore's "_prev" file. The engine's file choice ([DictionaryFactory.dictionaryFilesInOrder]) is tested
 *  without opening the files (native code). */
@RunWith(RobolectricTestRunner::class)
class DictionaryChoiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val hi = Locale.forLanguageTag("hi")
    private val en = Locale.forLanguageTag("en-US")
    private fun dir(locale: Locale) = File(DictionaryInfoUtils.getCacheDirectoryForLocale(locale, ctx)!!)
    private fun add(locale: Locale, name: String) = File(dir(locale), name).apply { writeText(name) }

    @Before fun clean() {
        listOf(hi, en).forEach { dir(it).deleteRecursively(); DictionarySelection.clear(ctx, it) }
    }

    /** The main dictionary the engine loads (the first main one it tries): an added file's name, or "internal". */
    private fun engineMain(locale: Locale, emoji: Boolean = false): String {
        val (extracted, nonExtracted) = DictionaryFactory.dictionaryFilesInOrder(ctx, locale, emoji)
        val first = extracted.firstOrNull { DictionarySelection.typeOf(it) == Dictionary.TYPE_MAIN }
        if (first != null) return if (DictionarySelection.isAdded(first)) first.name else DictionarySelection.INTERNAL
        assertTrue(nonExtracted.any { it.startsWith("main_") }, "the internal one extracted then")
        return DictionarySelection.INTERNAL
    }

    /** What the dialog marks in use (its rows' source, as the dialog reads them). */
    private fun dialogInUse(locale: Locale): String {
        val (files, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
        return DictionarySelection.inUse(ctx, locale, files, hasInternal)
    }

    private fun withRestoredFiles(locale: Locale) {
        add(locale, "main_prev.user.dict"); add(locale, "main_user.dict"); add(locale, "main_prev2.user.dict")
    }

    @Test fun `no selection uses main_user dict and never a prev file`() {
        withRestoredFiles(hi)
        assertEquals("main_user.dict", engineMain(hi))
        val (extracted, _) = DictionaryFactory.dictionaryFilesInOrder(ctx, hi, false)
        assertFalse(extracted.any { it.name.contains("_prev") }, "no _prev file is tried: $extracted")
        assertEquals("main_user.dict", dialogInUse(hi))
    }

    @Test fun `no selection and only prev files uses the internal one`() {
        add(hi, "main_prev.user.dict")
        assertEquals(DictionarySelection.INTERNAL, engineMain(hi))
        assertEquals(DictionarySelection.INTERNAL, dialogInUse(hi))
    }

    @Test fun `tapping internal makes the engine use the internal one`() {
        withRestoredFiles(hi)
        DictionarySelection.select(ctx, hi, DictionarySelection.INTERNAL) // (the dialog's tap)
        assertEquals(DictionarySelection.INTERNAL, engineMain(hi))
        val (extracted, _) = DictionaryFactory.dictionaryFilesInOrder(ctx, hi, false)
        assertFalse(extracted.any { DictionarySelection.isAdded(it) && DictionarySelection.typeOf(it) == Dictionary.TYPE_MAIN })
        assertEquals(DictionarySelection.INTERNAL, dialogInUse(hi))
        // an extracted internal one there: that one
        add(hi, DictionaryInfoUtils.MAIN_DICT_FILE_NAME)
        assertEquals(DictionarySelection.INTERNAL, engineMain(hi))
    }

    @Test fun `tapping a prev file makes the engine use it`() {
        withRestoredFiles(hi)
        add(hi, DictionaryInfoUtils.MAIN_DICT_FILE_NAME)
        DictionarySelection.select(ctx, hi, "main_prev2.user.dict")
        assertEquals("main_prev2.user.dict", engineMain(hi))
        val (extracted, _) = DictionaryFactory.dictionaryFilesInOrder(ctx, hi, false)
        assertEquals(listOf("main_prev2.user.dict"), extracted.filter { DictionarySelection.isAdded(it) }.map { it.name })
        assertEquals("main_prev2.user.dict", dialogInUse(hi))
        // the selected file gone (deleted): back to main_user.dict
        File(dir(hi), "main_prev2.user.dict").delete()
        assertEquals("main_user.dict", engineMain(hi))
    }

    @Test fun `the choice is per language and app-wide`() {
        withRestoredFiles(hi)
        withRestoredFiles(en)
        DictionarySelection.select(ctx, hi, "main_prev.user.dict")
        assertEquals("main_prev.user.dict", engineMain(hi))
        assertEquals("main_user.dict", engineMain(en), "another language is not changed")
        val key = DictionarySelection.prefKey(hi, Dictionary.TYPE_MAIN)
        assertEquals("dict_selected_hi_main", key)
        assertTrue(KeyboardProfiles.isAppWide(key), "one choice for every keyboard")
    }

    @Test fun `the choice survives a reload`() {
        withRestoredFiles(hi)
        DictionarySelection.select(ctx, hi, "main_prev.user.dict")
        // stored in the app's own settings, as the restore writes it
        assertEquals("main_prev.user.dict", DeviceProtectedUtils.getRealSharedPreferences(ctx).getString("dict_selected_hi_main", null))
        // a fresh read of the folder and the setting (the keyboard reloading its dictionaries) picks the same
        assertEquals("main_prev.user.dict", engineMain(hi))
        assertEquals("main_prev.user.dict", engineMain(hi))
        assertEquals("main_prev.user.dict", dialogInUse(hi))
    }

    @Test fun `other kinds are unchanged`() {
        add(hi, "emoji_user.dict")
        DictionarySelection.select(ctx, hi, DictionarySelection.INTERNAL)
        val (extracted, _) = DictionaryFactory.dictionaryFilesInOrder(ctx, hi, true)
        assertTrue(extracted.any { it.name == "emoji_user.dict" })
    }
}

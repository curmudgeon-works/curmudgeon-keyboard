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
import helium314.keyboard.settings.preferences.RestoreDictionaries
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
        listOf(hi, en).forEach {
            dir(it).deleteRecursively()
            DictionarySelection.clear(ctx, it)
            DictionarySelection.clear(ctx, it, Dictionary.TYPE_EMOJI)
        }
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

    @Test fun `the main choice leaves other kinds alone`() {
        add(hi, "emoji_user.dict")
        DictionarySelection.select(ctx, hi, DictionarySelection.INTERNAL)
        val (extracted, _) = DictionaryFactory.dictionaryFilesInOrder(ctx, hi, true)
        assertTrue(extracted.any { it.name == "emoji_user.dict" })
    }

    // emoji as main (review M3, owner 2026-10-12: "empji dict restore behavior should match main. _prev, _prev2 should
    // happen, and should be de-selected in favor of the backup's version. User should still be able to tap on the older
    // versions to activate just like main"); before, every added emoji file was tried, the first listed winning

    /** The added emoji files the engine tries (the first loading one is used). */
    private fun engineEmoji(locale: Locale): List<String> =
        DictionaryFactory.dictionaryFilesInOrder(ctx, locale, true).first
            .filter { DictionarySelection.isAdded(it) && DictionarySelection.typeOf(it) == Dictionary.TYPE_EMOJI }.map { it.name }

    @Test fun `of two added emoji files only the selected one is used, whichever is listed first`() {
        add(hi, "emoji_prev_user.dict"); add(hi, "emoji_user.dict"); add(hi, "main_user.dict")
        assertEquals(listOf("emoji_user.dict"), engineEmoji(hi), "no selection: emoji_user.dict")
        DictionarySelection.select(ctx, hi, "emoji_prev_user.dict", Dictionary.TYPE_EMOJI)
        assertEquals(listOf("emoji_prev_user.dict"), engineEmoji(hi))
        assertEquals("main_user.dict", engineMain(hi, emoji = true), "main is not changed by the emoji choice")
        DictionarySelection.select(ctx, hi, "emoji_user.dict", Dictionary.TYPE_EMOJI)
        assertEquals(listOf("emoji_user.dict"), engineEmoji(hi))
        // what the dialog marks in use for emoji (no internal emoji dictionary)
        val (files, _) = getUserAndInternalDictionaries(ctx, hi)
        assertEquals("emoji_user.dict", DictionarySelection.inUse(ctx, hi, files, false, Dictionary.TYPE_EMOJI))
        // the selected one deleted: back to the other one
        File(dir(hi), "emoji_user.dict").delete()
        assertEquals(listOf("emoji_prev_user.dict"), engineEmoji(hi))
    }

    /** A partial restore of the backup's [content] as "emoji_user.dict" of [locale], through the restore's plan and apply. */
    private fun restoreEmoji(locale: Locale, content: String): RestoreDictionaries.Step {
        val source = File(ctx.cacheDir, "backup_emoji_user.dict").apply { writeText(content) }
        val tag = dir(locale).name
        val step = RestoreDictionaries.plan(tag, "emoji_user.dict", source.inputStream().use { RestoreDictionaries.digest(it) },
            RestoreDictionaries.deviceDigests(dir(locale)))
        RestoreDictionaries.apply(step, dir(locale), source, DeviceProtectedUtils.getRealSharedPreferences(ctx))
        return step
    }

    @Test fun `a clashing emoji restore keeps the device's as prev and uses the backup's`() {
        add(hi, "emoji_user.dict") // (the device's: its content is its name)
        val step = restoreEmoji(hi, "backup 1")
        assertEquals(RestoreDictionaries.Action.CLASH, step.action)
        assertEquals("emoji_user.dict", File(dir(hi), "emoji_prev_user.dict").readText(), "the device's kept aside")
        assertEquals("backup 1", File(dir(hi), "emoji_user.dict").readText(), "the backup's in")
        assertEquals("emoji_user.dict", DictionarySelection.selected(ctx, hi, Dictionary.TYPE_EMOJI), "the backup's selected")
        assertEquals(listOf("emoji_user.dict"), engineEmoji(hi), "the engine uses the backup's only")
        // a second clash: the next free name
        restoreEmoji(hi, "backup 2")
        assertEquals("backup 1", File(dir(hi), "emoji_prev2_user.dict").readText())
        assertEquals("emoji_user.dict", File(dir(hi), "emoji_prev_user.dict").readText())
        assertEquals("backup 2", File(dir(hi), "emoji_user.dict").readText())
        assertEquals(listOf("emoji_user.dict"), engineEmoji(hi))
    }

    // owner 2026-10-12 B4: the backup's emoji dictionary is the device's kept-aside copy; fail before: nothing happened,
    // emoji_user.dict (other content) stayed in use and the backup's version wasn't
    @Test fun `a restore matching the device's prev emoji file renames it to the backup's name and uses it`() {
        add(hi, "emoji_user.dict"); add(hi, "emoji_prev_user.dict") // (the device's: each one's content is its name)
        val step = restoreEmoji(hi, "emoji_prev_user.dict") // (the backup's: the same as the device's prev)
        assertEquals(RestoreDictionaries.Action.SAME, step.action)
        assertEquals("emoji_prev_user.dict", File(dir(hi), "emoji_user.dict").readText(), "the match took the backup's name")
        // (into the prev the match left: no gap, review B3b)
        assertEquals("emoji_user.dict", File(dir(hi), "emoji_prev_user.dict").readText(), "the device's of that name moved aside")
        assertFalse(File(dir(hi), "emoji_prev2_user.dict").exists())
        assertEquals("emoji_user.dict", DictionarySelection.selected(ctx, hi, Dictionary.TYPE_EMOJI), "selected")
        assertEquals(listOf("emoji_user.dict"), engineEmoji(hi), "the engine uses the backup's version")
    }

    @Test fun `a restore matching the main dictionary in use under the backup's name selects it, nothing renamed`() {
        add(hi, "main_user.dict"); add(hi, "main_prev_user.dict")
        DictionarySelection.select(ctx, hi, "main_prev_user.dict")
        val source = File(ctx.cacheDir, "backup_main_user.dict").apply { writeText("main_user.dict") }
        val step = RestoreDictionaries.plan(dir(hi).name, "main_user.dict", source.inputStream().use { RestoreDictionaries.digest(it) },
            RestoreDictionaries.deviceDigests(dir(hi)))
        RestoreDictionaries.apply(step, dir(hi), source, DeviceProtectedUtils.getRealSharedPreferences(ctx))
        assertEquals("main_user.dict", File(dir(hi), "main_user.dict").readText())
        assertEquals("main_prev_user.dict", File(dir(hi), "main_prev_user.dict").readText())
        assertEquals("main_user.dict", DictionarySelection.selected(ctx, hi))
        assertEquals("main_user.dict", engineMain(hi))
    }

    @Test fun `tapping a restore's prev emoji file makes the engine use it`() {
        add(hi, "emoji_user.dict")
        restoreEmoji(hi, "backup")
        DictionarySelection.select(ctx, hi, "emoji_prev_user.dict", Dictionary.TYPE_EMOJI) // (the dialog's tap)
        assertEquals(listOf("emoji_prev_user.dict"), engineEmoji(hi))
        val (files, _) = getUserAndInternalDictionaries(ctx, hi)
        assertEquals("emoji_prev_user.dict", DictionarySelection.inUse(ctx, hi, files, false, Dictionary.TYPE_EMOJI))
        // the selection key the restore writes is the one the engine reads
        assertEquals(RestoreDictionaries.selectedKey(dir(hi).name, Dictionary.TYPE_EMOJI), DictionarySelection.prefKey(hi, Dictionary.TYPE_EMOJI))
    }
}

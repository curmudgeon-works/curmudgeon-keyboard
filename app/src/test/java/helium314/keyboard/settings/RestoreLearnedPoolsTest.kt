// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.countingIds
import helium314.keyboard.kid
import helium314.keyboard.randomIds
import helium314.keyboard.storedEnabled
import helium314.keyboard.withId
import helium314.keyboard.latin.personalization.FakeLearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A restore with all settings gives the backup's keyboards their own learned words of the backup, and leaves the
 *  phone's keyboards' as they are (review 2026-10-07; every keyboard its own since 2026-10-10). */
@RunWith(RobolectricTestRunner::class)
class RestoreLearnedPoolsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val zip = File(ctx.cacheDir, "backup-pools.zip")
    private val filesDirBefore = KeyboardProfiles.filesDir

    /** The test stores (FakeLearnedStoreIo's), in a file named as the backup takes a store's files. */
    private val io = object : LearnedStoreIo {
        private fun file(dir: File) = File(dir, dir.name + ".body")
        override fun readFile(dir: File, locale: Locale): List<LearnedEntry>? =
            file(dir).takeIf { it.isFile }?.readLines()?.filter { it.isNotEmpty() }?.map { FakeLearnedStoreIo.parse(it) }
        override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>): Boolean {
            dir.mkdirs()
            file(dir).writeText(entries.joinToString("") { FakeLearnedStoreIo.format(it) + "\n" })
            return true
        }
        override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? = null
    }
    private fun words(pool: Int) = io.readFile(LearnedStores.storeFile(ctx.filesDir, "Latn", pool), Locale.ROOT).orEmpty().map { it.word }.toSet()

    private fun cleanUp() {
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("UserHistoryDictionary") || it.name.startsWith("learned_")
            || it.name == LearnedStores.BLACKLIST_DIR }?.forEach { it.deleteRecursively() }
        real.edit().clear().commit()
    }

    // (no copies in the background here: the pool refresh finds no folder to copy in)
    @Before fun setUp() { cleanUp(); KeyboardProfiles.filesDir = null }
    @After fun tearDown() {
        cleanUp(); KeyboardProfiles.filesDir = filesDirBefore; zip.delete(); randomIds()
        SubtypeSettings.reloadEnabledSubtypes(ctx); LearnedStores.refresh(real) // (back to the shared pool)
    }

    private fun available(language: String) =
        SubtypeSettings.getAllAvailableSubtypes().map { it.toSettingsSubtype() }.first { it.locale == Locale.forLanguageTag(language) }

    // (owner 2026-10-11: keyboards have unique ids) the phone of before gets its ids by the one-time upgrade (which, with
    // sharing off, also does the learned words' update: pool = old set number); the old backup's keyboards get fresh ids
    // when it is read (its pools likewise). Ids counted: A k1, B k2, then C k3, D k4. The old id map is gone: the check
    // that the phone's keyboards keep theirs is now on the keyboards' ids
    @Test fun `the backup's keyboards get their own words, the phone's keep theirs`() {
        // the phone: keyboards A and B, learned words per keyboard, the shared ones with x
        SubtypeSettings.init(ctx)
        val a0 = available("en-US"); val b0 = available("de-DE")
        // (a phone of before: only the enabled list written, the upgrade before the keyboards are loaded, as at app start)
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a0, b0)))
            .putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false)
            .putString(KeyboardIdUpgrade.OLD_IDS, JSONObject(mapOf(a0.toPref() to 5, b0.toPref() to 6) as Map<*, *>).toString())
            .putInt("keyboard_profile_next_id", 7).putString(Settings.PREF_SELECTED_SUBTYPE, a0.toPref()).commit()
        countingIds(1)
        assertTrue(KeyboardIdUpgrade.upgrade(real, null))
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        val a = a0.withId(1); val b = b0.withId(2)
        assertEquals(listOf(a, b), SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() })
        io.writeFile(LearnedStores.storeFile(ctx.filesDir, "Latn", LearnedStores.SHARED), Locale.ROOT, listOf(word("x", 3, 100)))
        io.writeFile(LearnedStores.storeFile(ctx.filesDir, "Latn", 5), Locale.ROOT, listOf(word("x", 3, 100)))

        // the backup: keyboards C and D, learned words per keyboard, C's own words in it, none of D's
        val c0 = available("fr"); val d0 = available("es")
        val backup: Map<String?, Any?> = mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c0, d0)),
            Settings.PREF_SHARE_LEARNED_WORDS to false, Settings.PREF_SELECTED_SUBTYPE to c0.toPref(),
            KeyboardIdUpgrade.OLD_IDS to JSONObject(mapOf(c0.toPref() to 1, d0.toPref() to 2) as Map<*, *>).toString(), "keyboard_profile_next_id" to 3)
        val c = c0.withId(3); val d = d0.withId(4) // (as read from the backup)
        val store = LearnedStores.storeName("Latn", 1) + LearnedStores.DICT_EXTENSION
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json")); settingsToJsonStream(backup, z); z.closeEntry()
            z.putNextEntry(ZipEntry("$store/$store.body")); z.write((FakeLearnedStoreIo.format(word("c", 2, 200)) + "\n").toByteArray()); z.closeEntry()
        }

        LearnedKeyboards.storeIo = io
        try { restoreChosenFrom(ctx, zip, listOf(c, d), settings = true, learnedWords = true, io = io) }
        finally { LearnedKeyboards.storeIo = LearnedStoreIo.Native }

        // (the phone's keyboards stay, with their ids; (owner 2026-10-11: a keyboard restore never wipes; only the full restore does))
        assertEquals(setOf(kid(1), kid(2), kid(3), kid(4)), storedEnabled(real).map { it.keyboardId }.toSet())
        // the phone's keyboards kept their pools (sharing was off there), the backup's got their own
        val pools = JSONObject(real.getString(LearnedKeyboards.PREF_POOLS, "{}")!!)
        assertEquals(5, pools.getInt(kid(1)))
        assertEquals(6, pools.getInt(kid(2)))
        assertEquals(setOf("x"), words(5), "A's own words")
        val poolC = LearnedKeyboards.poolOrNull(real, c)!!
        val poolD = LearnedKeyboards.poolOrNull(real, d)!!
        assertTrue(poolC !in setOf(0, 5, 6) && poolD !in setOf(0, 5, 6, poolC), "pools $poolC $poolD")
        assertEquals(setOf("c"), words(poolC), "C: its own words in the backup")
        assertEquals(emptySet(), words(poolD), "D: it had none")
        assertEquals(setOf("x"), words(LearnedStores.SHARED), "the phone's shared words of before, untouched")
    }
}

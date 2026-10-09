// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.FakeLearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.word
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

/** A restore with all settings prepares the learned words of the backup's keyboards, not those of the keyboards the
 *  phone had before (review 2026-10-07). */
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

    // (no copies in the background here: the pool refresh only notes the copies it asks for; the pictures' folder is
    // another one than the learned words', as on the phones)
    private val runnerBefore = LearnedStores.seedRunner
    private val de = File(ctx.cacheDir.parentFile, "restore_pools_de")
    private val asked = mutableListOf<(Boolean) -> Unit>()
    @Before fun setUp() {
        cleanUp(); KeyboardProfiles.filesDir = de.apply { mkdirs() }
        LearnedStores.seedRunner = { _, _, done -> asked.add(done) }
    }
    @After fun tearDown() {
        cleanUp(); KeyboardProfiles.filesDir = filesDirBefore; zip.delete(); de.deleteRecursively()
        asked.forEach { it(true) } // (ended: a pool still being copied isn't asked for again)
        SubtypeSettings.reloadEnabledSubtypes(ctx); LearnedStores.refresh(real) // (back to the shared pool)
        LearnedStores.seedRunner = runnerBefore
    }

    private fun available(language: String) =
        SubtypeSettings.getAllAvailableSubtypes().map { it.toSettingsSubtype() }.first { it.locale == Locale.forLanguageTag(language) }

    @Test fun `the backup's keyboards get the shared words, the phone's old ones nothing`() {
        // the phone: keyboards A and B, learned words per keyboard, the shared ones with x
        SubtypeSettings.init(ctx)
        val a = available("en-US"); val b = available("de-DE")
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b))).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(listOf(a, b), SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() })
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false)
            .putString("keyboard_profile_ids", JSONObject(mapOf(a.toPref() to 5, b.toPref() to 6)).toString())
            .putInt("keyboard_profile_next_id", 7).putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref()).commit()
        io.writeFile(LearnedStores.storeFile(ctx.filesDir, "Latn", LearnedStores.SHARED), Locale.ROOT, listOf(word("x", 3, 100)))
        io.writeFile(LearnedStores.storeFile(ctx.filesDir, "Latn", 5), Locale.ROOT, listOf(word("x", 3, 100)))

        // the backup: keyboards C and D, learned words per keyboard, C's own words in it, none of D's
        val c = available("fr"); val d = available("es")
        val backup: Map<String?, Any?> = mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c, d)),
            Settings.PREF_SHARE_LEARNED_WORDS to false, Settings.PREF_SELECTED_SUBTYPE to c.toPref(),
            "keyboard_profile_ids" to JSONObject(mapOf(c.toPref() to 1, d.toPref() to 2)).toString(), "keyboard_profile_next_id" to 3)
        val store = LearnedStores.storeName("Latn", 1) + LearnedStores.DICT_EXTENSION
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json")); settingsToJsonStream(backup, z); z.closeEntry()
            z.putNextEntry(ZipEntry("$store/$store.body")); z.write((FakeLearnedStoreIo.format(word("c", 2, 200)) + "\n").toByteArray()); z.closeEntry()
        }

        restoreChosenFrom(ctx, zip, listOf(c, d), settings = true, learnedWords = true, io = io)

        val ids = JSONObject(real.getString("keyboard_profile_ids", "{}")!!)
        assertEquals(setOf(c.toPref(), d.toPref()), ids.keys().asSequence().toSet(), "the phone's old keyboards got ids")
        val pools = LearnedStores.keyboardPoolsOnDisk(ctx.filesDir)
        assertTrue(pools.all { it in setOf(1, 2) }, "learned words of keyboards not in the backup: $pools")
        assertEquals(setOf("x", "c"), words(1), "C: the shared words and its own")
        assertEquals(setOf("x"), words(2), "D: the shared words")
    }
}

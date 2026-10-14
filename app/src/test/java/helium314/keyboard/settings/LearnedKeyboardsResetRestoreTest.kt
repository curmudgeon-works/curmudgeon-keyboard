// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.idleUntil
import helium314.keyboard.latin.personalization.profileIds
import helium314.keyboard.latin.personalization.readList
import helium314.keyboard.latin.personalization.snapshot
import helium314.keyboard.latin.personalization.storedButton
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.personalization.writeList
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.preferences.startFactoryReset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every keyboard its own learned words (2026-10-10), through a factory reset (startFactoryReset, what the dialog calls)
 * and a restore (the restore dialog's functions): each keyboard's words stay its own, whatever happens to the ids.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedKeyboardsResetRestoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private lateinit var de: File
    private val io = BodyStoreIo()
    private val zip by lazy { File(ctx.cacheDir, "backup-learned-${System.nanoTime()}.zip") }
    private val filesDirBefore = KeyboardProfiles.filesDir

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        de = File(ctx.cacheDir, "de_${System.nanoTime()}").apply { mkdirs() }
        KeyboardProfiles.filesDir = de
        assertNotEquals(ce.canonicalPath, de.canonicalPath)
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
    }

    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.filesDir = filesDirBefore
        zip.delete()
    }

    /** The phone after the update: [pools] each keyboard's, words "<pool>" in each, both buttons on for all. */
    private fun updatedPhone(pools: Map<SettingsSubtype, Int>, next: Int) {
        real.edit().putString(LearnedKeyboards.PREF_POOLS, JSONObject(pools.mapKeys { it.key.toPref() }).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, next).putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, JSONArray(pools.keys.map { it.toPref() }).toString())
            .putString(LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, JSONArray(pools.keys.map { it.toPref() }).toString())
            .commit()
        for (pool in pools.values) {
            io.store(ce, "Latn", pool, word("phone$pool", 2, 100))
            writeList(ce, "Latn", pool, "bad$pool\n")
        }
    }

    private fun reset(keyboards: Boolean, learnedWords: Boolean) {
        var done = false
        startFactoryReset(ctx, keyboards = keyboards, learnedWords = learnedWords, clipboard = false, custom = false) { done = true }
        idleUntil { done }
        assertTrue(done, "the reset never ran")
    }

    /** The keyboard the list falls back to (what a reset of the keyboards leaves). */
    private fun defaultKeyboard(): SettingsSubtype {
        real.edit().remove(Settings.PREF_ENABLED_SUBTYPES).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        return SubtypeSettings.getEnabledSubtypes(true).first().toSettingsSubtype()
    }

    @Test fun `a reset of the keyboards - the keyboard left keeps its own words, the others' go, buttons off`() {
        val kept = defaultKeyboard()
        val other = available("de-DE")
        useKeyboards(ctx, real, kept, other)
        updatedPhone(mapOf(kept to 5, other to 6), next = 9)

        reset(keyboards = true, learnedWords = false)

        assertEquals(setOf("phone5"), io.words(ce, "Latn", 5))
        assertEquals(mapOf("bad5" to RemovedWords.Entry(1)), readList(ce, "Latn", 5))
        assertFalse(LearnedStores.storeFile(ce, "Latn", 6).exists(), "the other keyboard's words are left")
        assertFalse(File(ce, "${LearnedStores.BLACKLIST_DIR}/k6").exists(), "the other keyboard's blacklist is left")
        assertEquals(5, storedPools(real)[kept.toPref()])
        assertFalse(other.toPref() in storedPools(real))
        for (pref in LearnedKeyboards.buttonPrefs) assertFalse(real.contains(pref), pref)
        assertTrue(real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0) >= 9)
        assertTrue(LearnedKeyboards.isDone(real))
    }

    @Test fun `a reset of the learned words - every keyboard's words and blacklists go, the pools stay`() {
        val a = available("en-US"); val b = available("de-DE")
        useKeyboards(ctx, real, a, b)
        updatedPhone(mapOf(a to 5, b to 6), next = 9)
        writeList(ce, "Latn", 0, "old\n")

        reset(keyboards = false, learnedWords = true)

        assertTrue(LearnedStores.keyboardPoolsOnDisk(ce).isEmpty(), "left: ${LearnedStores.keyboardPoolsOnDisk(ce)}")
        assertFalse(LearnedStores.storeFile(ce, "Latn", 5).exists())
        assertFalse(File(ce, LearnedStores.BLACKLIST_DIR).exists(), "blacklists left: ${snapshot(ce).keys}")
        assertEquals(mapOf(a.toPref() to 5, b.toPref() to 6), storedPools(real))
        assertEquals(9, real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0))
    }

    private fun backup(prefs: Map<String?, Any?>, stores: Map<Int, String>, lists: Map<Int, String> = emptyMap()) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json")); settingsToJsonStream(prefs, z); z.closeEntry()
            for ((pool, w) in stores) {
                val store = LearnedStores.storeName("Latn", pool) + LearnedStores.DICT_EXTENSION
                z.putNextEntry(ZipEntry("$store/$store.body"))
                z.write((helium314.keyboard.latin.personalization.FakeLearnedStoreIo.format(word(w, 3, 200)) + "\n").toByteArray())
                z.closeEntry()
            }
            for ((pool, w) in lists) {
                z.putNextEntry(ZipEntry(LearnedStores.blacklistFile(File(""), "Latn", pool).path.removePrefix(File.separator)))
                z.write("$w\n".toByteArray()); z.closeEntry()
            }
        }
    }

    @Test fun `a full restore from a phone with other ids - each keyboard keeps its own words and gets the backup's`() {
        val c = available("fr"); val d = available("es")
        useKeyboards(ctx, real, c, d)
        profileIds(real, mapOf(c to 1, d to 2), next = 3)
        updatedPhone(mapOf(c to 3, d to 4), next = 5)
        // the backup: other profile ids, other pools
        backup(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c, d)),
            Settings.PREF_SELECTED_SUBTYPE to c.toPref(), LearnedKeyboards.PREF_DONE to true,
            LearnedKeyboards.PREF_POOLS to JSONObject(mapOf(c.toPref() to 1, d.toPref() to 2)).toString(),
            LearnedKeyboards.PREF_POOL_NEXT to 3,
            "keyboard_profile_ids" to JSONObject(mapOf(c.toPref() to 7, d.toPref() to 8)).toString(), "keyboard_profile_next_id" to 9),
            stores = mapOf(1 to "backc", 2 to "backd"))

        restoreChosenFrom(ctx, zip, listOf(c, d), settings = true, learnedWords = true, io = io)

        assertEquals(mapOf(c.toPref() to 3, d.toPref() to 4), storedPools(real), "the phone's pools")
        assertEquals(5, real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0))
        assertEquals(setOf("phone3", "backc"), io.words(ce, "Latn", 3))
        assertEquals(setOf("phone4", "backd"), io.words(ce, "Latn", 4))
        assertFalse(LearnedStores.storeFile(ce, "Latn", 1).exists() || LearnedStores.storeFile(ce, "Latn", 2).exists(),
            "the backup's pool numbers written: ${LearnedStores.keyboardPoolsOnDisk(ce)}")
        // (the backup had no buttons on)
        for (pref in LearnedKeyboards.buttonPrefs) assertTrue(storedButton(real, pref).isEmpty(), pref)
    }

    @Test fun `an old backup that shared its words - each restored keyboard gets them, buttons on`() {
        val c = available("fr"); val d = available("es")
        useKeyboards(ctx, real, c)
        updatedPhone(mapOf(c to 3), next = 4)
        backup(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c, d)),
            Settings.PREF_SELECTED_SUBTYPE to c.toPref()), stores = mapOf(0 to "shared"), lists = mapOf(0 to "teh"))

        restoreChosenFrom(ctx, zip, listOf(c, d), settings = true, learnedWords = true, io = io)

        val pools = storedPools(real)
        assertEquals(3, pools[c.toPref()])
        val poolD = pools.getValue(d.toPref())
        assertNotEquals(0, poolD)
        assertEquals(setOf("phone3", "shared"), io.words(ce, "Latn", 3))
        assertEquals(setOf("shared"), io.words(ce, "Latn", poolD))
        assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", poolD)?.get("teh"))
        assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", 3)?.get("teh"))
        for (pref in LearnedKeyboards.buttonPrefs) assertEquals(setOf(c.toPref(), d.toPref()), storedButton(real, pref), pref)
    }

    @Test fun `an old backup restored whole is updated as the phone was`() {
        val c = available("fr"); val d = available("es")
        useKeyboards(ctx, real, c)
        updatedPhone(mapOf(c to 3), next = 4)
        backup(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c, d)),
            Settings.PREF_SELECTED_SUBTYPE to c.toPref(),
            "keyboard_profile_ids" to JSONObject(mapOf(c.toPref() to 1, d.toPref() to 2)).toString(), "keyboard_profile_next_id" to 3),
            stores = mapOf(0 to "shared"), lists = mapOf(0 to "teh"))

        restoreEverythingFrom(ctx, zip)

        assertTrue(LearnedKeyboards.isDone(real))
        val pools = storedPools(real)
        assertEquals(setOf(c.toPref(), d.toPref()), pools.keys)
        for (pool in pools.values) {
            assertTrue(pool >= 3, "$pools")
            assertEquals(setOf("shared"), io.words(ce, "Latn", pool))
            assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", pool)?.get("teh"))
        }
        for (pref in LearnedKeyboards.buttonPrefs) assertEquals(pools.keys, storedButton(real, pref), pref)
        assertNull(real.getString(LearnedKeyboards.PREF_PLAN, null))
    }
}

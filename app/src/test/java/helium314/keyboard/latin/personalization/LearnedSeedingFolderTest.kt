// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.os.Looper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.restoreFollowUp
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.preferences.startFactoryReset
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 3010 #2 (second attempt): a keyboard's own learned words start as a copy of the shared ones in the folder the learned
 * words are in. On the phones (Android 7+) that is the credential-encrypted ctx.filesDir, not the device-protected folder
 * of the pictures (KeyboardProfiles.filesDir), and it can't be read before the first unlock. So here the two are always
 * different folders, as on the phones; the learned words' folder is the one the app itself sets up.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedSeedingFolderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce get() = ctx.filesDir // the learned words' folder (credential-encrypted)
    private lateinit var de: File // the pictures' folder (device-protected)
    private val filesDirBefore = KeyboardProfiles.filesDir
    private val runs = mutableListOf<Pair<Int, (Boolean) -> Unit>>() // the copies asked for, with their callbacks
    private val zip get() = File(ctx.cacheDir, "backup-seeding.zip")

    /** Stores as text in a file named as the backup takes a store's files; stores in [live] are open in the keyboard
     *  (emptied, not deleted, when their words go). [beforeWrite]: called before a store of a keyboard's own is written. */
    private inner class Io : LearnedStoreIo {
        val live = HashSet<Pair<String, Int>>()
        @Volatile var beforeWrite: (File) -> Unit = {}
        var failWrites = false
        private fun file(dir: File) = File(dir, dir.name + ".body")
        override fun readFile(dir: File, locale: Locale): List<LearnedEntry>? =
            file(dir).takeIf { it.isFile }?.readLines()?.filter { it.isNotEmpty() }?.map { FakeLearnedStoreIo.parse(it) }
        override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>): Boolean {
            if (failWrites) return false
            if (dir.name.contains(".k")) beforeWrite(dir)
            dir.mkdirs()
            file(dir).writeText(entries.joinToString("") { FakeLearnedStoreIo.format(it) + "\n" })
            return true
        }
        override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? {
            if (script to pool !in live) return null
            val target = LearnedStores.storeFile(ce, script, pool)
            return object : LearnedStoreIo.LiveStore {
                override fun readAll() = readFile(target, Locale.ROOT).orEmpty()
                override fun replaceWith(dir: File): Boolean { target.deleteRecursively(); return dir.renameTo(target) }
            }
        }
    }
    private val io = Io()

    private fun put(dir: File, pool: Int, vararg words: String) =
        io.writeFile(LearnedStores.storeFile(dir, "Latn", pool), Locale.ROOT, words.map { word(it, 1, 100) })
    private fun words(dir: File, pool: Int) =
        io.readFile(LearnedStores.storeFile(dir, "Latn", pool), Locale.ROOT).orEmpty().map { it.word }.toSet()
    private fun marked(dir: File, pool: Int) = File(dir, "learned_seeded_k$pool").isFile
    private fun markers(dir: File) = dir.list().orEmpty().filter { it.startsWith("learned_seeded_k") }
    private fun unlocked(on: Boolean) = shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(on)
    private fun available(language: String) =
        SubtypeSettings.getAllAvailableSubtypes().map { it.toSettingsSubtype() }.first { it.locale == Locale.forLanguageTag(language) }

    private fun cleanUp() {
        ce.listFiles()?.filter { it.name.startsWith("UserHistoryDictionary") || it.name.startsWith("learned_")
            || it.name == LearnedStores.BLACKLIST_DIR }?.forEach { it.deleteRecursively() }
        real.edit().clear().commit()
    }

    @Before fun setUp() {
        cleanUp()
        // (a keyboard id no other test uses: a pool whose copy failed in another test is used without one for the process)
        real.edit().putInt("keyboard_profile_next_id", 41).commit()
        de = File(ctx.cacheDir.parentFile, "user_de_${System.nanoTime()}").apply { mkdirs() }
        assertNotEquals(ce.canonicalPath, de.canonicalPath)
        KeyboardProfiles.filesDir = de // (as the app does)
        LearnedStores.seedRunner = { _, pool, done -> runs.add(pool to done) }
        LearnedStores.seedIo = io
        LearnedStores.refresh(real)
        assertEquals(LearnedStores.SHARED, LearnedStores.currentPool)
    }

    @After fun tearDown() {
        unlocked(true)
        cleanUp()
        runs.forEach { it.second(true) } // (the copies asked for end: a pool still being copied isn't asked for again)
        LearnedStores.refresh(real) // (back to the shared pool)
        KeyboardProfiles.filesDir = filesDirBefore
        LearnedStores.seedIo = LearnedStoreIo.Native
        zip.delete()
        de.deleteRecursively()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    // 1: the copy looked for the shared words in the pictures' folder (nothing there), and marked the pool there
    @Test fun `a keyboard added with sharing off starts with a copy of the shared words, in the learned words' folder`() {
        put(ce, LearnedStores.SHARED, "hai", "yaar")
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).commit()
        val own = KeyboardProfiles.idFor(real, KeyboardProfiles.selectedKeyboard(real))
        LearnedStores.refresh(real)
        assertEquals(listOf(own), runs.map { it.first }, "the copy wasn't asked for")
        assertEquals(LearnedStores.SHARED, LearnedStores.currentPool)
        assertTrue(LearnedStores.seedNow(real, own))
        runs.single().second(true)
        assertEquals(own, LearnedStores.currentPool)
        assertEquals(setOf("hai", "yaar"), words(ce, own))
        assertTrue(marked(ce, own))
        assertEquals(emptyList(), markers(de))
    }

    // 2: before the first unlock the learned words can't be read: the keyboard stays on the shared pool, nothing copied
    @Test fun `before the first unlock the keyboard stays on the shared pool and nothing is copied`() {
        put(ce, LearnedStores.SHARED, "hai")
        unlocked(false)
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).commit()
        val own = KeyboardProfiles.idFor(real, KeyboardProfiles.selectedKeyboard(real))
        LearnedStores.refresh(real)
        assertEquals(LearnedStores.SHARED, LearnedStores.currentPool)
        assertEquals(emptyList(), runs.map { it.first })
        assertEquals(emptyList(), markers(ce))
        assertEquals(emptyList(), markers(de))
        // unlocked: the next refresh asks for the copy
        unlocked(true)
        LearnedStores.refresh(real)
        assertEquals(listOf(own), runs.map { it.first })
    }

    // 3 (original #2): after a factory reset a keyboard on a reused id got no copy (its emptied pool counted as filled)
    @Test fun `after a factory reset a keyboard on a reused id starts with a copy of the shared words`() {
        put(ce, LearnedStores.SHARED, "hai")
        put(ce, 5, "hai", "mine")
        io.live.add("Latn" to 5) // (the keyboard has it open: emptied, not deleted)
        File(ce, "learned_seeded_k5").writeText("")
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).commit()
        var done = false
        startFactoryReset(ctx, keyboards = true, learnedWords = false, clipboard = false, custom = false, io = io) { done = true }
        val until = System.currentTimeMillis() + 10_000
        while (!done && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        assertTrue(done, "the reset never ran")
        assertTrue(LearnedStores.isShared(real))
        assertEquals(setOf("hai", "mine"), words(ce, LearnedStores.SHARED))
        assertTrue(LearnedStores.storeFile(ce, "Latn", 5).exists(), "the open store is emptied, not deleted")
        assertEquals(emptySet(), words(ce, 5))
        // sharing off again, a keyboard added on id 5
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).commit()
        assertTrue(LearnedStores.seedNow(real, 5))
        assertEquals(setOf("hai", "mine"), words(ce, 5))
        assertTrue(marked(ce, 5))
    }

    // 4 (original #2): after a restore of every keyboard with the settings, a pool of the phone's that the backup doesn't
    // list was emptied but marked as filled by the follow-up
    @Test fun `after a full restore an emptied pool the backup doesn't list gets the copy`() {
        SubtypeSettings.init(ctx)
        val a = available("en-US"); val b = available("de-DE"); val c = available("fr"); val d = available("es")
        phone(listOf(a, b), mapOf(a to 5, b to 6), next = 8) // (and 7: a keyboard the phone had once, its pool still there)
        put(ce, LearnedStores.SHARED, "x"); put(ce, 5, "x"); put(ce, 6, "x"); put(ce, 7, "x", "seven")
        io.live.add("Latn" to 7)
        for (pool in listOf(5, 6, 7)) File(ce, "learned_seeded_k$pool").writeText("")
        backup(listOf(c, d), mapOf(c to 1, d to 2), next = 3, ownWords = 1 to "c")

        restoreChosenFrom(ctx, zip, listOf(c, d), settings = true, learnedWords = true, io = io)
        restoreFollowUp(ctx)
        LearnedStores.refresh(real)

        assertEquals(setOf("x", "seven", "c"), words(ce, 1), "C: the shared words and its own")
        assertEquals(setOf("x", "seven"), words(ce, 2), "D: the shared words")
        assertTrue(marked(ce, 1) && marked(ce, 2))
        assertEquals(emptySet(), words(ce, 7))
        assertTrue(LearnedStores.seedNow(real, 7))
        assertEquals(setOf("x", "seven"), words(ce, 7))
        assertEquals(emptyList(), markers(de))
    }

    // 5: a keyboard new to the phone restored on its own gets the backup's words, marked at once (a copy can't run over
    // them), also when the copy for it was already running
    @Test fun `a keyboard restored on its own keeps the backup's words`() {
        val (c, pool) = partialRestoreSetUp()
        restoreChosenFrom(ctx, zip, listOf(c), settings = false, learnedWords = true, io = io)
        assertTrue(marked(ce, pool), "not marked by the restore itself")
        assertEquals(setOf("c"), words(ce, pool))
        restoreFollowUp(ctx)
        assertTrue(LearnedStores.seedNow(real, pool))
        assertEquals(setOf("c"), words(ce, pool))
        assertEquals(emptyList(), markers(de))
    }

    @Test fun `a keyboard restored on its own while its copy runs keeps the backup's words`() {
        val (c, pool) = partialRestoreSetUp()
        val copying = CountDownLatch(1); val go = CountDownLatch(1)
        io.beforeWrite = { dir -> if (dir.name.contains(".k$pool")) { copying.countDown(); go.await(10, TimeUnit.SECONDS) } }
        var seeded = false
        val seed = Thread { seeded = LearnedStores.seedNow(real, pool) }.apply { start() }
        assertTrue(copying.await(5, TimeUnit.SECONDS), "the copy never started")
        io.beforeWrite = {}
        var failure: Throwable? = null
        val restore = Thread {
            try { restoreChosenFrom(ctx, zip, listOf(c), settings = false, learnedWords = true, io = io) } catch (t: Throwable) { failure = t }
        }.apply { start() }
        // the restore runs as far as it can (to its end without a lock, else up to the copy's)
        val until = System.currentTimeMillis() + 5_000
        while (restore.isAlive && restore.state != Thread.State.BLOCKED && System.currentTimeMillis() < until) Thread.sleep(10)
        go.countDown()
        seed.join(10_000); restore.join(10_000)
        failure?.let { throw it }
        assertTrue(seeded)
        assertTrue("c" in words(ce, pool), "the restored words were copied over: ${words(ce, pool)}")
        assertTrue(marked(ce, pool))
    }

    // 6: the pools of before the markers (0.3.007/0.3.008), and those learned in since without a marker, hold words of
    // their own: marked before any copy, never copied over
    @Test fun `pools with words but no marker are kept, empty ones get the copy`() {
        put(ce, LearnedStores.SHARED, "x")
        put(ce, 3, "mine")
        File(de, "learned_seeded_k3").writeText("") // (a marker of before, in the wrong folder)
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).remove(MARKED_FLAG).commit()
        assertTrue(LearnedStores.seedNow(real, 3))
        assertEquals(setOf("mine"), words(ce, 3))
        assertTrue(marked(ce, 3))
        assertTrue(real.getBoolean(MARKED_FLAG, false))
        assertTrue(LearnedStores.seedNow(real, 4))
        assertEquals(setOf("x"), words(ce, 4))
        assertTrue(marked(ce, 4))
    }

    // 7: a copy cut short leaves no marker: done again; the one-time marking isn't repeated
    @Test fun `a copy that didn't finish is made again`() {
        put(ce, LearnedStores.SHARED, "x")
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).remove(MARKED_FLAG).commit()
        io.failWrites = true
        assertFalse(LearnedStores.seedNow(real, 4))
        assertFalse(marked(ce, 4))
        assertTrue(real.getBoolean(MARKED_FLAG, false))
        io.failWrites = false
        assertTrue(LearnedStores.seedNow(real, 4))
        assertEquals(setOf("x"), words(ce, 4))
        assertTrue(marked(ce, 4))
        assertTrue(LearnedStores.seedNow(real, 4))
        assertEquals(setOf("x"), words(ce, 4))
    }

    /** The phone for a partial restore: keyboard A (5, seeded), sharing off; the backup: C (1, with its own words) and D.
     *  Returns C and the pool it gets on the phone. */
    private fun partialRestoreSetUp(): Pair<SettingsSubtype, Int> {
        SubtypeSettings.init(ctx)
        val a = available("en-US"); val c = available("fr"); val d = available("es")
        phone(listOf(a), mapOf(a to 5), next = 6)
        real.edit().putBoolean(MARKED_FLAG, true).commit()
        put(ce, LearnedStores.SHARED, "x"); put(ce, 5, "x"); File(ce, "learned_seeded_k5").writeText("")
        backup(listOf(c, d), mapOf(c to 1, d to 2), next = 3, ownWords = 1 to "c")
        val pool = KeyboardProfiles.idFor(real, c) // (the id C gets on the phone)
        assertEquals(6, pool)
        return c to pool
    }

    private fun phone(keyboards: List<SettingsSubtype>, ids: Map<SettingsSubtype, Int>, next: Int) {
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards)).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false)
            .putString("keyboard_profile_ids", JSONObject(ids.mapKeys { it.key.toPref() }).toString())
            .putInt("keyboard_profile_next_id", next).putString(Settings.PREF_SELECTED_SUBTYPE, keyboards.first().toPref()).commit()
    }

    private fun backup(keyboards: List<SettingsSubtype>, ids: Map<SettingsSubtype, Int>, next: Int, ownWords: Pair<Int, String>) {
        val prefs: Map<String?, Any?> = mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(keyboards),
            Settings.PREF_SHARE_LEARNED_WORDS to false, Settings.PREF_SELECTED_SUBTYPE to keyboards.first().toPref(),
            "keyboard_profile_ids" to JSONObject(ids.mapKeys { it.key.toPref() }).toString(), "keyboard_profile_next_id" to next)
        val store = LearnedStores.storeName("Latn", ownWords.first) + LearnedStores.DICT_EXTENSION
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json")); settingsToJsonStream(prefs, z); z.closeEntry()
            z.putNextEntry(ZipEntry("$store/$store.body")); z.write((FakeLearnedStoreIo.format(word(ownWords.second, 2, 200)) + "\n").toByteArray()); z.closeEntry()
        }
    }

    companion object {
        private const val MARKED_FLAG = "learned_pools_seeded_marked_done"
    }
}

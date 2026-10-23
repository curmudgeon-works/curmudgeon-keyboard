// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.latin.R
import helium314.keyboard.latin.gesture.GestureStats
import helium314.keyboard.latin.personalization.LearnedStoreMigration
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.LoadCountingHistory
import helium314.keyboard.latin.personalization.RestoreGate
import helium314.keyboard.latin.personalization.putStoreForTest
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.FullRestore
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A full restore runs while the keyboard keeps running (owner 2026-10-12: "A + B + swipe stats makes sense"): what the
 * keyboard still holds of the old device (learned words, blacklists, swipe statistics) mustn't be saved over the
 * restored files. [RestoreGate] is set for the restore's file copies, the open stores are closed before the wipe, and
 * the gate goes after the restore, after a failing one too.
 */
@RunWith(RobolectricTestRunner::class)
class FullRestoreRaceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val zip = File(ctx.cacheDir, "full-restore-race.zip")
    private lateinit var store: LoadCountingHistory
    // (the swipe statistics' store as the app has it: a test below points it at these preferences)
    private val statsPrefs = GestureStats.prefsProvider

    @Before fun setUp() {
        // (another class's work still running on the keyboard executor, or posted to the main thread, would run into this
        // test's restore: waited for, as the other restore tests' tearDown wait for their job)
        idleUntil { !BackupRestoreJob.isRunning }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit { }.get()
        shadowOf(Looper.getMainLooper()).idle()
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        real.edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 444).commit()
        File(ctx.filesDir, "custom_background_image").writeText("device picture")
        // the keyboard's open store of learned words (no native library in tests: it never really opens)
        store = LoadCountingHistory(ctx, "Latn", LearnedStores.SHARED)
        putStoreForTest("Latn", LearnedStores.SHARED, store)
    }

    @After fun tearDown() {
        FullRestore.afterStep = null
        FullRestore.afterCheck = null
        LearnedStoreMigration.holdForTest(null)
        idleUntil { !BackupRestoreJob.isRunning }
        putStoreForTest("Latn", LearnedStores.SHARED, null)
        RestoreGate.setForTest(null)
        GestureStats.prefsProvider = statsPrefs
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        ctx.filesDir.deleteRecursively()
        DeviceProtectedUtils.getFilesDir(ctx).deleteRecursively()
        RemovedWords.cachedIn(ctx.filesDir).forEach { it.reload() } // (the lists held for the process: empty again)
        zip.delete()
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    /** A good backup: a blacklist, then the settings ([extra] too), then the protected settings. */
    private fun backup(blacklist: String = "backupword\n", extra: Map<String?, Any?> = emptyMap()) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(BLACKLIST)); z.write(blacklist.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_KEY_LONGPRESS_TIMEOUT to 222) + extra, z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("protected_preferences.json"))
            settingsToJsonStream(emptyMap<String?, Any?>(), z)
            z.closeEntry()
        }
    }

    /** The full restore of [zip] as the restore dialog runs it, its follow-up too; @return its error, null for none. */
    private fun fullRestoreJob(): String? {
        var error: String? = null
        assertTrue(runRestore(ctx, { error = it }, R.string.backup_restored, 1, fullRestore = true) { restoreEverythingFrom(ctx, zip) })
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the job never ended")
        return error
    }

    @Test fun `the gate is set from before the wipe, the open stores closed by then`() {
        backup()
        var setAtWipe = false
        var closedAtWipe = 0
        var setAtEntry = false
        FullRestore.afterStep = {
            if (it == null) { setAtWipe = RestoreGate.isSet; closedAtWipe = store.closes.get() }
            if (it == BLACKLIST) setAtEntry = RestoreGate.isSet
        }
        restoreEverythingFrom(ctx, zip)
        assertFalse(File(ctx.filesDir, "custom_background_image").exists(), "the wipe didn't run")
        assertTrue(setAtWipe, "no gate at the wipe")
        assertTrue(setAtEntry, "no gate while the files were copied")
        assertTrue(closedAtWipe >= 1, "the open store wasn't closed before the wipe: its old words could be saved over the restored ones")
        assertFalse(RestoreGate.isSet, "the gate stayed after the restore")
    }

    @Test fun `the gate goes after a failing restore`() {
        backup()
        FullRestore.afterStep = { if (it == null) throw IllegalStateException("injected after the wipe") }
        assertFailsWith<IllegalStateException> { restoreEverythingFrom(ctx, zip) }
        assertFalse(RestoreGate.isSet, "the gate stuck after a failure")
        // through the restore's job too (on the keyboard executor)
        FullRestore.afterStep = { if (it == BLACKLIST) throw IllegalStateException("injected at $it") }
        assertEquals("rinjected at $BLACKLIST", fullRestoreJob())
        assertFalse(RestoreGate.isSet, "the gate stuck after a failure in the job")
    }

    @Test fun `a check that fails before the wipe never sets the gate nor closes the stores`() {
        zip.writeText("not a backup")
        assertFailsWith<Exception> { restoreEverythingFrom(ctx, zip) }
        assertFalse(RestoreGate.isSet)
        assertEquals(0, store.closes.get(), "unsaved words dropped although nothing was restored")
    }

    @Test fun `the keyboard's dictionaries are told to reload with no settings screen open`() {
        backup()
        val app = ctx as Application
        // (Robolectric's list of sent broadcasts is a synchronized list handed out as it is: gone through under its lock,
        // a broadcast sent meanwhile on another thread made it a ConcurrentModificationException)
        fun told(): Int {
            val sent = shadowOf(app).broadcastIntents
            return synchronized(sent) { sent.count { it.action == DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION } }
        }
        val before = told()
        assertEquals(null, fullRestoreJob())
        assertEquals(before + 1, told(), "no new-dictionary broadcast without an activity: the keyboard kept its old dictionaries")
    }

    // owner 2026-10-13 (C, "It's a silly edge case tbh -- removal when restore is running. Who even cares"): a Remove
    // while a full restore runs isn't kept; the restored list is the backup's, and a word's count is the restored one.
    // Fail before: the Remove was held and made again on the restored list ("newstrike" in it, "zorp" 3)
    @Test fun `a Remove during the restore is not on the restored list, and nothing is counted twice`() {
        backup(blacklist = "backupword\nzorp\t2\n")
        val file = LearnedStores.blacklistFile(ctx.filesDir, "Latn", LearnedStores.SHARED)
        file.parentFile!!.mkdirs()
        file.writeText("oldword\nzorp\t2\n")
        val list = RemovedWords.forFile(file)
        list.reload() // (the keyboard holds the device's list, read)
        assertEquals(setOf("oldword", "zorp"), list.entries().keys)
        // the keyboard's Removes right after the restore copied the list (a write would be the whole old list)
        FullRestore.afterStep = { if (it == BLACKLIST) { list.strike("newstrike"); list.strike("zorp"); list.reload() } }
        restoreEverythingFrom(ctx, zip)
        RemovedWords.awaitWrites()
        assertEquals(setOf("backupword", "zorp\t2"), file.readLines().filter { it.isNotEmpty() }.toSet(), "the restored list")
        assertEquals(mapOf("backupword" to RemovedWords.Entry(1), "zorp" to RemovedWords.Entry(2)), list.entries(),
            "the keyboard's list isn't the restored one")
        // after the restore a Remove counts on from the restored count, once
        assertEquals(3, list.strike("zorp"))
        RemovedWords.awaitWrites()
        // (the strikes; a Remove's time, "<TAB>@<seconds>", left out)
        assertEquals(setOf("backupword", "zorp\t3"), file.readLines().filter { it.isNotEmpty() }.map { it.replace(Regex("\t@\\d+"), "") }.toSet())
    }

    /**
     * Round-5 review B1, 20261013_L7: a Remove just before a full restore, its write queued but not run when the gate
     * comes. Fail before: the write found the gate set and wrote nothing, the change stayed in memory as no restore change,
     * and the re-read after the restore put the old list's count ("zorp" 3) on top of the restored file. (Written before
     * the wipe until 2026-10-13; since, dropped at the restore's start: the restore replaces the file anyway.)
     */
    @Test fun `a strike whose write is still queued when the gate comes - the restored file as in the backup`() {
        backup(blacklist = "backupword\n")
        val file = LearnedStores.blacklistFile(ctx.filesDir, "Latn", LearnedStores.SHARED)
        file.parentFile!!.mkdirs()
        file.writeText("oldword\nzorp\t2\n")
        val list = RemovedWords.forFile(file)
        list.reload() // (the keyboard holds the device's list, read)
        val held = CountDownLatch(1)
        FullRestore.afterCheck = {
            // the list writer busy (a re-read waiting), so the Remove's write is queued behind it, not run, before the gate
            LearnedStoreMigration.holdForTest(held)
            list.reloadAsync()
            assertEquals(3, list.strike("zorp"))
            assertFalse(RestoreGate.isSet, "the Remove must come before the gate")
            // the writer goes on once the gate is set (the restore waits for it before the wipe)
            Thread {
                while (!RestoreGate.isSet) Thread.sleep(1)
                held.countDown(); LearnedStoreMigration.holdForTest(null)
            }.start()
        }
        restoreEverythingFrom(ctx, zip)
        val lines = file.readLines().filter { it.isNotEmpty() }.toSet()
        assertEquals(setOf("backupword"), lines, "the old list's count was put on top of the restored file")
        assertEquals(setOf("backupword"), list.entries().keys, "the keyboard's list isn't the restored one")
    }

    @Test fun `the swipe statistics after a full restore are the backup's, not the old ones in memory`() {
        GestureStats.prefsProvider = { real }
        real.edit().putString(GestureStats.PREF_KEY, "{\"oldtuning\":{\"n\":40,\"kept\":30}}").commit()
        GestureStats.reload()
        assertEquals(setOf("oldtuning"), GestureStats.read(real).keys) // (the keyboard holds the device's rows)
        backup(extra = mapOf(GestureStats.PREF_KEY to "{\"backuptuning\":{\"n\":50,\"kept\":45}}"))
        // a swipe right after the restore put the settings in place, and its write 2 s later (the old rows, with it)
        FullRestore.afterStep = { if (it == "preferences.json") { GestureStats.onSwipe("swipedtuning", 10); GestureStats.flush(real) } }
        assertEquals(null, fullRestoreJob())
        val stored = org.json.JSONObject(real.getString(GestureStats.PREF_KEY, "{}")!!).keys().asSequence().toSet()
        assertEquals(setOf("backuptuning"), stored, "the old rows were written over the restored ones")
        assertEquals(setOf("backuptuning"), GestureStats.read(real).keys, "the keyboard's rows aren't the restored ones")
    }

    private companion object {
        val BLACKLIST = "blacklists${File.separator}Latn.txt"
    }
}

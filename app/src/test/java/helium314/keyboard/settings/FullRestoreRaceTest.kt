// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.LoadCountingHistory
import helium314.keyboard.latin.personalization.RestoreGate
import helium314.keyboard.latin.personalization.putStoreForTest
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
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

    @Before fun setUp() {
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
        idleUntil { !BackupRestoreJob.isRunning }
        putStoreForTest("Latn", LearnedStores.SHARED, null)
        RestoreGate.setForTest(null)
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        ctx.filesDir.deleteRecursively()
        DeviceProtectedUtils.getFilesDir(ctx).deleteRecursively()
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
        fun told() = shadowOf(app).broadcastIntents.count { it.action == DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION }
        val before = told()
        assertEquals(null, fullRestoreJob())
        assertEquals(before + 1, told(), "no new-dictionary broadcast without an activity: the keyboard kept its old dictionaries")
    }

    private companion object {
        val BLACKLIST = "blacklists${File.separator}Latn.txt"
    }
}

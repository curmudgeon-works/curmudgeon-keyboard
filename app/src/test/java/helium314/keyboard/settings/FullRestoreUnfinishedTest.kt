// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.FullRestore
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.runBackup
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A full restore that fails after its wipe leaves a note on the main settings screen, "The full restore didn't finish."
 * with Restore again, until a full restore finishes (owner 2026-10-12: "Yes add this"). The flag behind it is the
 * device's: app-wide, never backed up, never restored.
 */
@RunWith(RobolectricTestRunner::class)
class FullRestoreUnfinishedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val zip = File(ctx.cacheDir, "full-restore-unfinished.zip")
    private val written = File(ctx.cacheDir, "full-restore-written.zip")

    @Before fun setUp() {
        // (no native library in tests: the learned words' stores through the test stores, as RestoreMergeItemsTest)
        helium314.keyboard.latin.personalization.LearnedKeyboards.storeIo = helium314.keyboard.latin.personalization.BodyStoreIo()
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        real.edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 444).commit()
        File(ctx.filesDir, "custom_background_image").writeText("device picture")
    }

    @After fun tearDown() {
        helium314.keyboard.latin.personalization.LearnedKeyboards.storeIo = helium314.keyboard.latin.personalization.LearnedStoreIo.Native
        FullRestore.afterStep = null
        idleUntil { !BackupRestoreJob.isRunning }
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        ctx.filesDir.deleteRecursively()
        DeviceProtectedUtils.getFilesDir(ctx).deleteRecursively()
        zip.delete()
        written.delete()
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    /** A good backup: a learned-words file, then the settings ([extra] too), then the protected settings. */
    private fun backup(extra: Map<String?, Any?> = emptyMap()) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("blacklists/en-US.txt")); z.write("backupwords".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_KEY_LONGPRESS_TIMEOUT to 222) + extra, z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("protected_preferences.json"))
            settingsToJsonStream(emptyMap<String?, Any?>(), z)
            z.closeEntry()
        }
    }

    /** A failure partway through the full restore, after the wipe: thrown by the restore's step hook at [step] (null:
     *  right after the wipe; else after that entry was restored). */
    private fun failAt(step: String?) {
        FullRestore.afterStep = { if (it == step) throw IllegalStateException("injected at $step") }
    }

    /** The full restore of [zip] as the restore dialog runs it, its follow-up too; @return its error, null for none. */
    private fun fullRestoreJob(): String? {
        var error: String? = null
        assertTrue(runRestore(ctx, { error = it }, R.string.backup_restored, 1, fullRestore = true) { restoreEverythingFrom(ctx, zip) })
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the job never ended")
        return error
    }

    @Test fun `a failure right after the wipe leaves the flag set, a full restore that finishes clears it`() {
        backup()
        failAt(null)
        assertFailsWith<IllegalStateException> { restoreEverythingFrom(ctx, zip) }
        assertFalse(File(ctx.filesDir, "custom_background_image").exists(), "the wipe didn't run")
        assertTrue(FullRestore.isUnfinished(real), "no flag after a failure past the wipe")
        FullRestore.afterStep = null
        // (the restore alone, without its follow-up, hasn't finished: owner 2026-10-12 "yes", the flag goes after it)
        restoreEverythingFrom(ctx, zip)
        assertTrue(FullRestore.isUnfinished(real), "cleared before the follow-up")
        assertEquals(null, fullRestoreJob())
        assertFalse(real.contains(FullRestore.PREF_UNFINISHED), "the flag stayed after a full restore that finished")
        assertEquals(222, real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
    }

    @Test fun `the settings' clear keeps the flag - a failure after the settings entry leaves it set`() {
        backup()
        failAt("preferences.json")
        assertFailsWith<IllegalStateException> { restoreEverythingFrom(ctx, zip) }
        assertEquals(222, real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT], "the settings weren't restored before the failure")
        assertTrue(FullRestore.isUnfinished(real), "the settings' clear took the flag")
    }

    @Test fun `through the restore's job too - its follow-up keeps the flag, the next full restore clears it`() {
        backup()
        failAt("blacklists/en-US.txt")
        assertEquals("rinjected at blacklists/en-US.txt", fullRestoreJob())
        assertTrue(FullRestore.isUnfinished(real))
        FullRestore.afterStep = null
        assertEquals(null, fullRestoreJob())
        assertFalse(FullRestore.isUnfinished(real))
    }

    @Test fun `a failure in the follow-up leaves the flag set`() {
        backup()
        failAt(FullRestore.FOLLOW_UP)
        // (a follow-up failure is only logged, as before: the restore itself finished)
        assertEquals(null, fullRestoreJob())
        assertEquals(222, real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT], "not restored")
        assertTrue(FullRestore.isUnfinished(real), "cleared although the follow-up failed")
        FullRestore.afterStep = null
        assertEquals(null, fullRestoreJob())
        assertFalse(FullRestore.isUnfinished(real))
    }

    @Test fun `a restore that isn't the full one never clears the flag`() {
        backup()
        FullRestore.mark(real, true)
        var error: String? = null
        assertTrue(runRestore(ctx, { error = it }, R.string.backup_restored, 1) { })
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the job never ended")
        assertEquals(null, error)
        assertTrue(FullRestore.isUnfinished(real))
    }

    @Test fun `a check that fails before the wipe leaves the flag as it was`() {
        zip.writeText("not a backup")
        assertFailsWith<Exception> { restoreEverythingFrom(ctx, zip) }
        assertFalse(real.contains(FullRestore.PREF_UNFINISHED), "set although nothing was deleted")
        FullRestore.mark(real, true) // (an earlier restore that didn't finish: still not finished)
        assertFailsWith<Exception> { restoreEverythingFrom(ctx, zip) }
        assertTrue(FullRestore.isUnfinished(real))
    }

    @Test fun `the flag is the device's - app-wide, not in a backup written while it's set, never brought by one`() {
        assertTrue(KeyboardProfiles.isAppWide(FullRestore.PREF_UNFINISHED), "in a keyboard's set")
        FullRestore.mark(real, true)
        assertTrue(runBackup(ctx, {}) { FileOutputStream(written) })
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the backup never ended")
        val prefs = ZipFile(written).use { z -> z.getInputStream(z.getEntry("preferences.json")).reader().readText() }
        assertTrue(Settings.PREF_KEY_LONGPRESS_TIMEOUT in prefs, "not a real backup: $prefs")
        assertFalse(FullRestore.PREF_UNFINISHED in prefs, "the flag was backed up: $prefs")
        // a backup that has it all the same (by hand) doesn't bring it
        real.edit().remove(FullRestore.PREF_UNFINISHED).commit()
        backup(mapOf(FullRestore.PREF_UNFINISHED to true))
        assertEquals(null, fullRestoreJob())
        assertFalse(real.contains(FullRestore.PREF_UNFINISHED), "the backup brought the flag")
    }
}

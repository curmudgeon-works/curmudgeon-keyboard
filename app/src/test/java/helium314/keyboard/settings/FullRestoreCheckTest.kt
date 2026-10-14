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
import helium314.keyboard.settings.preferences.UnreadableBackupException
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
import kotlin.test.assertTrue

/**
 * The full restore ("Delete app data and restore from backup") reads the whole backup before it deletes anything (owner
 * 2026-10-12: "Yes, check backup before wipe"): a backup that can't be read changes nothing, and says so ("This backup
 * can't be read; nothing was changed."). Before, the wipe ran first and a cut-off or broken backup left the device
 * half restored.
 */
@RunWith(RobolectricTestRunner::class)
class FullRestoreCheckTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val zip = File(ctx.cacheDir, "full-restore-check.zip")
    private val protectedDir get() = DeviceProtectedUtils.getFilesDir(ctx)

    @Before fun setUp() {
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        // the device's own data: settings, a learned-words file and a picture, both kinds of files folder
        real.edit().putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true).putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 444).commit()
        ctx.protectedPrefs().edit().putString("lib_checksum", "device").commit()
        File(ctx.filesDir, "blacklists/en-US.txt").apply { parentFile?.mkdirs(); writeText("devicewords") }
        File(ctx.filesDir, "custom_background_image").writeText("device picture")
        File(protectedDir, "custom_font").writeText("device font")
    }

    @After fun tearDown() {
        idleUntil { !BackupRestoreJob.isRunning }
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        ctx.filesDir.deleteRecursively()
        protectedDir.deleteRecursively()
        zip.delete()
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    /** The device as it is: both settings files and both files folders, with what's in them. */
    private fun device(): List<Any> = listOf(real.all.toMap(), ctx.protectedPrefs().all.toMap(), files(ctx.filesDir), files(protectedDir))

    private fun files(dir: File) = dir.walk().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

    /** A good backup: other settings, another learned-words file, the protected settings. */
    private fun goodBackup(): ByteArray {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("blacklists/en-US.txt")); z.write("backupwords\n".repeat(2000).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_KEY_LONGPRESS_TIMEOUT to 222), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("protected_preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>("lib_checksum" to "backup"), z)
            z.closeEntry()
        }
        return zip.readBytes()
    }

    private fun assertUnreadableChangesNothing(what: String) {
        val before = device()
        assertFailsWith<UnreadableBackupException>(what) { restoreEverythingFrom(ctx, zip) }
        assertEquals(before, device(), "$what: the device changed")
        // and through the restore's job, as the dialog runs it: the new message, still nothing changed
        var error: String? = null
        assertTrue(runRestore(ctx, { error = it }, R.string.backup_restored, 1) { restoreEverythingFrom(ctx, zip) })
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the job never ended")
        assertEquals("u", error, what)
        assertEquals(before, device(), "$what: the device changed after the job")
    }

    @Test fun `a backup cut short changes nothing`() {
        val bytes = goodBackup()
        // (the entries read to the end: only the zip's list at its end is gone, which the restore's reader never sees)
        zip.writeBytes(bytes.copyOf(bytes.size - 30))
        assertUnreadableChangesNothing("cut at the end")
        zip.writeBytes(bytes.copyOf(bytes.size / 2))
        assertUnreadableChangesNothing("cut in half")
    }

    @Test fun `a settings file that doesn't parse changes nothing`() {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("blacklists/en-US.txt")); z.write("backupwords".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("preferences.json")); z.write("boolean settings\n{not json".toByteArray()); z.closeEntry()
        }
        assertUnreadableChangesNothing("broken settings")
    }

    @Test fun `a backup without a settings file changes nothing`() {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("blacklists/en-US.txt")); z.write("backupwords".toByteArray()); z.closeEntry()
        }
        assertUnreadableChangesNothing("no settings file")
    }

    @Test fun `a file that isn't a zip changes nothing`() {
        zip.writeText("not a backup")
        assertUnreadableChangesNothing("not a zip")
    }

    @Test fun `a good backup still restores whole`() {
        goodBackup()
        restoreEverythingFrom(ctx, zip)
        assertEquals(222, real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        assertEquals(null, real.all[Settings.PREF_SHOW_NUMBER_ROW], "the device's settings weren't replaced")
        assertEquals("backupwords\n".repeat(2000), File(ctx.filesDir, "blacklists/en-US.txt").readText())
        assertEquals(false, File(ctx.filesDir, "custom_background_image").exists(), "the device's files weren't deleted")
    }
}

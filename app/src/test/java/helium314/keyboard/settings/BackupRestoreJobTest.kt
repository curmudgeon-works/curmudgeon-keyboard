// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.runBackup
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backup and restore run off the main thread with a progress dialog (review 2026-10-08 M2, 2026-10-10): the screen
 *  thread waited for the whole job, and a touch meanwhile was an ANR that could leave half a restore. */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreJobTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zip = File(ctx.cacheDir, "job-test.zip")
    private val restored get() = ctx.getString(R.string.backup_restored)

    @After fun tearDown() {
        idleUntil(10_000) { !BackupRestoreJob.isRunning } // (a job of a failed test mustn't block the next)
        BackupRestoreJob.onProgress = null
        zip.delete()
        ctx.realPrefs().edit().clear().commit()
    }

    // the test thread is the main thread: what the worker posts runs when its looper is idled
    private fun idleUntil(ms: Long, done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    @Test fun `a restore returns before its job is done, and is applied after the main looper runs`() {
        val gate = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        runRestore(ctx, {}, R.string.backup_restored) {
            gate.await(5, TimeUnit.SECONDS)
            finished.set(true)
        }
        assertFalse(finished.get(), "the main thread waited for the restore")
        gate.countDown()
        val until = System.currentTimeMillis() + 10_000
        while (!finished.get() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(finished.get())
        assertFalse(ShadowToast.showedToast(restored), "the toast came from the worker, not the main thread")
        assertTrue(idleUntil(10_000) { ShadowToast.showedToast(restored) }, "the restore wasn't finished on the main thread")
    }

    @Test fun `a real restore's progress fills to its last step, then the dialog closes`() {
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("preferences.json"))
            // (an app-wide key: an old backup's keyboard settings are dropped by the follow-up, owner 2026-10-11, no separate
            // common set; the first keyboard's own settings are the common ones)
            settingsToJsonStream(mapOf<String?, Any?>(helium314.keyboard.latin.settings.Settings.PREF_ADVANCED_SETTINGS to true), out)
            out.closeEntry()
            out.putNextEntry(ZipEntry("protected_preferences.json"))
            settingsToJsonStream(emptyMap<String?, Any?>(), out)
            out.closeEntry()
        }
        val shown = mutableListOf<BackupRestoreJob.Progress>()
        BackupRestoreJob.onProgress = { shown.add(it) }
        val gate = CountDownLatch(1)
        // two entries and the learned words, as the restore dialog counts them
        assertTrue(runRestore(ctx, {}, R.string.backup_restored, 3) { gate.await(5, TimeUnit.SECONDS); restoreEverythingFrom(ctx, zip) })
        assertTrue(BackupRestoreJob.progress != null, "no progress dialog")
        assertFalse(runRestore(ctx, {}, R.string.backup_restored) { }, "a second job started while one runs")
        gate.countDown()
        assertTrue(idleUntil(10_000) { !BackupRestoreJob.isRunning }, "the job never ended")
        assertNull(BackupRestoreJob.progress, "the progress dialog stayed up")
        val last = shown.last()
        assertEquals(4, last.total)
        assertEquals(4, last.done, "the bar didn't fill: ${shown.map { "${it.done}/${it.total}" }}")
        assertTrue(ctx.realPrefs().getBoolean(helium314.keyboard.latin.settings.Settings.PREF_ADVANCED_SETTINGS, false), "the backup wasn't restored")
        assertTrue(ShadowToast.showedToast(restored))
    }

    @Test fun `a failed restore reports on the main thread and closes the dialog`() {
        var error: String? = null
        var errorOnMain = false
        runRestore(ctx, { error = it; errorOnMain = Looper.myLooper() == Looper.getMainLooper() }, R.string.backup_restored, 2) {
            throw IllegalStateException("broken entry")
        }
        assertTrue(idleUntil(10_000) { !BackupRestoreJob.isRunning }, "the job never ended")
        assertNull(BackupRestoreJob.progress, "the progress dialog stayed up")
        assertEquals("rbroken entry", error)
        assertTrue(errorOnMain)
        assertFalse(ShadowToast.showedToast(restored))
    }

    @Test fun `a backup runs off the main thread, fills its progress and writes the zip`() {
        ctx.realPrefs().edit().putBoolean("backed_up_key", true).commit()
        val shown = mutableListOf<BackupRestoreJob.Progress>()
        BackupRestoreJob.onProgress = { shown.add(it) }
        val gate = CountDownLatch(1)
        assertTrue(runBackup(ctx, {}) { gate.await(5, TimeUnit.SECONDS); FileOutputStream(zip) })
        assertTrue(BackupRestoreJob.isRunning, "the main thread waited for the backup")
        gate.countDown()
        assertTrue(idleUntil(10_000) { !BackupRestoreJob.isRunning }, "the job never ended")
        assertNull(BackupRestoreJob.progress, "the progress dialog stayed up")
        val last = shown.last()
        assertEquals(last.total, last.done, "the bar didn't fill: ${shown.map { "${it.done}/${it.total}" }}")
        ZipFile(zip).use { z ->
            val prefs = z.getInputStream(z.getEntry("preferences.json")).reader().readText()
            assertTrue("backed_up_key" in prefs, prefs)
        }
    }
}

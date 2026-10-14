// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.Database
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.runBackup
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/** Review 2026-10-12 L2 (4): the backup copies the clipboard database's file alone; clips still in its write-ahead log
 *  were missing from the copy. It is checkpointed first now. */
@RunWith(RobolectricTestRunner::class)
class BackupClipboardWalTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zip = File(ctx.cacheDir, "backup-wal.zip")
    private val copy = File(ctx.cacheDir, "backup-wal-copy.db")

    private fun resetClipboard() =
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)

    @Before fun setUp() { resetClipboard() }

    @After fun tearDown() {
        idleUntil { !BackupRestoreJob.isRunning }
        ClipboardDao.getInstance(ctx)?.clear()
        Database.getInstance(ctx).writableDatabase.disableWriteAheadLogging()
        resetClipboard()
        zip.delete(); copy.delete()
        File(copy.path + "-wal").delete(); File(copy.path + "-shm").delete()
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    @Test fun `a backup's clipboard database holds the clips still in the write-ahead log`() {
        // the database as the app has it on a device: with a write-ahead log, the newest rows not yet in the file
        val db = Database.getInstance(ctx).writableDatabase
        db.enableWriteAheadLogging()
        db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
        ClipboardDao.getInstance(ctx)!!.addClip(1L, false, "clip in the log")

        var written: Boolean? = null
        assertTrue(runBackup(ctx, {}, onDone = { written = it }) { FileOutputStream(zip) })
        assertTrue(idleUntil { written != null && !BackupRestoreJob.isRunning }, "the backup never ended")
        assertTrue(written == true, "not written")

        ZipFile(zip).use { z -> z.getInputStream(z.getEntry(Database.NAME)).use { input -> copy.outputStream().use { input.copyTo(it) } } }
        // (the copy alone, as a restore reads it: no log beside it)
        val texts = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { other ->
            other.rawQuery("SELECT TEXT FROM CLIPBOARD", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }
        assertTrue("clip in the log" in texts, "the backup's database lacks the clip: $texts")
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.idKey
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.FullRestore
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No clipboard problem may break a restore (review 2026-10-12 M8, owner 2026-10-12 "makes senes to do B"): the backup's
 * clipboard database is read whatever its version (one from a newer build threw "can't downgrade" after the full
 * restore's wipe, which stopped it there), and one that can't be read is skipped, the device's clips kept, and the done
 * message says so.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreClipboardSkipTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val zip = File(ctx.cacheDir, "restore-clipboard-skip.zip")
    private val restoredDb get() = ctx.getDatabasePath(Database.NAME + "_restored")
    private val keyboard = SettingsSubtype(Locale.US, "").withId(1)

    private fun resetClipboard() {
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        Database::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Before fun setUp() {
        resetClipboard()
        // (no native library in tests: the learned words' stores through the test stores, as RestoreMergeItemsTest)
        helium314.keyboard.latin.personalization.LearnedKeyboards.storeIo = helium314.keyboard.latin.personalization.BodyStoreIo()
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
    }

    @After fun tearDown() {
        helium314.keyboard.latin.personalization.LearnedKeyboards.storeIo = helium314.keyboard.latin.personalization.LearnedStoreIo.Native
        idleUntil { !BackupRestoreJob.isRunning }
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        ctx.filesDir.deleteRecursively()
        DeviceProtectedUtils.getFilesDir(ctx).deleteRecursively()
        zip.delete()
        restoredDb.delete()
        resetClipboard()
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    /** The device's clips "a" and "b" (b pinned). */
    private fun deviceClips(): ClipboardDao {
        val dao = ClipboardDao.getInstance(ctx)!!
        dao.addClip(10, false, "a"); dao.addClip(20, true, "b")
        return dao
    }

    private fun texts(dao: ClipboardDao) = (0 until dao.count()).map { dao.getAt(it).text }.toSet()

    /** A clipboard database of [version] with the clips "x" and "y" (y pinned), as bytes. */
    private fun clipboardDb(version: Int): ByteArray {
        val db = File(ctx.cacheDir, "clips-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use { d ->
            d.execSQL(ClipboardDao.CREATE_TABLE)
            d.version = version
            for ((t, pinned, text) in listOf(Triple(30L, false, "x"), Triple(40L, true, "y")))
                d.insert("CLIPBOARD", null, ContentValues().apply { put("TIMESTAMP", t); put("PINNED", if (pinned) 1 else 0); put("TEXT", text) })
        }
        return db.readBytes().also { db.delete() }
    }

    /** A good backup of this build ([keyboard] listed, its delay 222) with [clipboard] as its clipboard database. */
    private fun backup(clipboard: ByteArray) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_ENABLED_SUBTYPES to keyboard.toPref(),
                Settings.PREF_ADDITIONAL_SUBTYPES to keyboard.toPref(), Settings.PREF_SELECTED_SUBTYPE to keyboard.toPref(),
                KeyboardIdUpgrade.DONE to true, Settings.PREF_VERSION_CODE to helium314.keyboard.latin.BuildConfig.MIGRATION_VERSION,
                idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT) to 222), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry(Database.NAME)); z.write(clipboard); z.closeEntry()
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
        shadowOf(Looper.getMainLooper()).idle()
        return error
    }

    // fail before: "Can't downgrade database from version 3 to 2" after the wipe, the restore stopped there
    @Test fun `a clipboard from a newer build - the full restore finishes and its clips come in`() {
        val dao = deviceClips()
        backup(clipboardDb(version = 3))
        assertEquals(null, fullRestoreJob())
        assertFalse(FullRestore.isUnfinished(real), "the full restore didn't finish")
        assertEquals(222, real.all[idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT)])
        assertEquals(setOf("x", "y"), texts(dao), "the backup's clips didn't replace the device's")
        assertFalse(BackupRestoreJob.clipboardSkipped)
        assertEquals(ctx.getString(R.string.backup_restored), ShadowToast.getTextOfLatestToast())
        assertFalse(restoredDb.exists(), "the backup's database was left behind")
    }

    // fail before: the damaged database was replaced by an empty one (or threw), the device's clips cleared
    @Test fun `a damaged clipboard - the full restore finishes, the device's clips kept, and it says so`() {
        val dao = deviceClips()
        backup("not a database at all, just some bytes".toByteArray())
        assertEquals(null, fullRestoreJob())
        assertFalse(FullRestore.isUnfinished(real), "the full restore didn't finish")
        assertEquals(222, real.all[idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT)])
        assertEquals(setOf("a", "b"), texts(dao), "the device's clips weren't kept")
        assertTrue(BackupRestoreJob.clipboardSkipped)
        assertEquals(ctx.getString(R.string.backup_restored) + "\n" + ctx.getString(R.string.restore_clipboard_skipped),
            ShadowToast.getTextOfLatestToast())
        assertFalse(restoredDb.exists(), "the backup's database was left behind")
    }

    // fail before: copyFromDb returned nothing, and cleared the device's clips for a damaged file
    @Test fun `copyFromDb - a damaged file is skipped, the device's clips unchanged, both ways`() {
        val dao = deviceClips()
        for (merge in listOf(null, 25)) {
            restoredDb.parentFile?.mkdirs()
            restoredDb.writeBytes("garbage".toByteArray())
            assertFalse(Database.copyFromDb(restoredDb, ctx, merge), "said restored (merge: $merge)")
            assertEquals(setOf("a", "b"), texts(dao), "the device's clips changed (merge: $merge)")
            assertTrue(dao.isPinned((0 until dao.count()).first { dao.getAt(it).text == "b" }), "a pin was lost")
            assertFalse(restoredDb.exists(), "not deleted (merge: $merge)")
        }
    }

    /** The texts in the device's clipboard table itself. */
    private fun tableTexts() = Database.getInstance(ctx).readableDatabase.rawQuery("SELECT TEXT FROM CLIPBOARD", null).use {
        val out = HashSet<String>(); while (it.moveToNext()) out += it.getString(0); out
    }

    // fail before (owner 2026-10-13, 20261013_L1): the full path cleared the device's clips, then added the backup's one by
    // one outside a transaction; an add that failed went unnoticed ("restored"), the device's clips gone
    @Test fun `copyFromDb full - an add that fails rolls it all back, the device's clips kept, not restored`() {
        val dao = deviceClips()
        restoredDb.parentFile?.mkdirs()
        restoredDb.writeBytes(clipboardDb(version = 2))
        // (the device's table refuses the backup's "y", as a full disk would)
        val w = Database.getInstance(ctx).writableDatabase
        w.execSQL("CREATE TRIGGER refuse_y BEFORE INSERT ON CLIPBOARD WHEN NEW.TEXT = 'y' BEGIN SELECT RAISE(ABORT, 'refused'); END")
        try {
            assertFalse(Database.copyFromDb(restoredDb, ctx), "said restored")
            assertEquals(setOf("a", "b"), texts(dao), "the device's clips weren't kept (cache)")
            assertEquals(setOf("a", "b"), tableTexts(), "the device's clips weren't kept (table)")
            assertTrue(dao.isPinned((0 until dao.count()).first { dao.getAt(it).text == "b" }), "a pin was lost")
            assertFalse(restoredDb.exists(), "not deleted")
        } finally {
            w.execSQL("DROP TRIGGER IF EXISTS refuse_y")
        }
    }
}

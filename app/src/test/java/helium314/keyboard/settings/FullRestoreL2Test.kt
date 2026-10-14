// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.RemovedWords
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
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review 2026-10-12 L2, the full restore: its flag "didn't finish" and its failures before the wipe. */
@RunWith(RobolectricTestRunner::class)
class FullRestoreL2Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val zip = File(ctx.cacheDir, "full-restore-l2.zip")
    private val keyboard = SettingsSubtype(Locale.US, "").withId(1)

    @Before fun setUp() {
        helium314.keyboard.latin.personalization.LearnedKeyboards.storeIo = helium314.keyboard.latin.personalization.BodyStoreIo()
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        real.edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 444).commit()
        File(ctx.filesDir, "custom_background_image").writeText("device picture")
    }

    @After fun tearDown() {
        helium314.keyboard.latin.personalization.LearnedKeyboards.storeIo = helium314.keyboard.latin.personalization.LearnedStoreIo.Native
        FullRestore.afterStep = null
        FullRestore.afterCheck = null
        idleUntil { !BackupRestoreJob.isRunning }
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

    /** A good backup of this build: a blacklist, then the settings (222 in [keyboard]'s set), then the protected ones. */
    private fun backup() {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("blacklists/en-US.txt")); z.write("backupwords".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_ENABLED_SUBTYPES to keyboard.toPref(),
                Settings.PREF_ADDITIONAL_SUBTYPES to keyboard.toPref(), Settings.PREF_SELECTED_SUBTYPE to keyboard.toPref(),
                KeyboardIdUpgrade.DONE to true, Settings.PREF_VERSION_CODE to helium314.keyboard.latin.BuildConfig.MIGRATION_VERSION,
                idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT) to 222), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("protected_preferences.json"))
            settingsToJsonStream(emptyMap<String?, Any?>(), z)
            z.closeEntry()
        }
    }

    // L2 (1): before, the settings were cleared in one edit and the flag written again after the backup's settings: a
    // crash in between lost it (the main screen said nothing of a restore that didn't finish)
    @Test fun `the settings' wipe and the flag are one edit - no change is seen with the settings wiped and no flag`() {
        backup()
        val seen = ArrayList<Pair<String?, Boolean>>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            seen.add(key to prefs.getBoolean(FullRestore.PREF_UNFINISHED, false))
        }
        FullRestore.afterStep = { if (it == "blacklists/en-US.txt") real.registerOnSharedPreferenceChangeListener(listener) }
        try { restoreEverythingFrom(ctx, zip) } finally { real.unregisterOnSharedPreferenceChangeListener(listener) }
        assertTrue(seen.isNotEmpty(), "the settings entry changed nothing")
        assertEquals(emptyList(), seen.filter { !it.second }, "changes seen without the flag")
        assertEquals(222, real.all[idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT)])
        assertTrue(FullRestore.isUnfinished(real))
    }

    /** The full restore of [zip] as the restore dialog runs it, its follow-up too; @return its error, null for none. */
    private fun fullRestoreJob(): String? {
        var error: String? = null
        assertTrue(runRestore(ctx, { error = it }, R.string.backup_restored, 1, fullRestore = true) { restoreEverythingFrom(ctx, zip) })
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the job never ended")
        return error
    }

    /** Nothing deleted or changed, no follow-up ran ([followedUp]), no flag. */
    private fun assertUnchanged(followedUp: Boolean) {
        assertTrue(File(ctx.filesDir, "custom_background_image").exists(), "wiped")
        assertEquals(444, real.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 0), "the settings changed")
        assertFalse(followedUp, "the follow-up ran")
        assertFalse(FullRestore.isUnfinished(real), "flagged as unfinished although nothing changed")
    }

    // L2 (2): the backup read after its check, before the wipe, fails: before, a file gone was a plain error ("r") and
    // the follow-up ran; one that read as no entries was wiped over with nothing
    @Test fun `the backup gone after its check - the unreadable error, nothing wiped, no follow-up`() {
        backup()
        var followedUp = false
        FullRestore.afterStep = { if (it == FullRestore.FOLLOW_UP) followedUp = true }
        FullRestore.afterCheck = { zip.delete() }
        assertEquals("u", fullRestoreJob())
        assertUnchanged(followedUp)
    }

    @Test fun `the backup no zip any more after its check - the unreadable error, nothing wiped, no follow-up`() {
        backup()
        var followedUp = false
        FullRestore.afterStep = { if (it == FullRestore.FOLLOW_UP) followedUp = true }
        FullRestore.afterCheck = { zip.writeText("not a zip any more") }
        assertEquals("u", fullRestoreJob())
        assertUnchanged(followedUp)
    }

    // L2 (3): a blacklist change made while the restore ran was put on top of the restored file of the same pool number
    // (its file name), whichever keyboard has that number in the backup, with the count it made on the old list. Since
    // 2026-10-13 (owner: "It's a silly edge case tbh -- removal when restore is running. Who even cares") such a change
    // isn't kept at all (until then made again on its keyboard's restored list, by id): no list gets it
    @Test fun `a strike during the restore is kept on no restored list, neither its keyboard's nor the pool number's`() {
        // the device: keyboard k1 learns into pool 11, "word" removed twice
        real.edit().putString(LearnedKeyboards.PREF_POOLS, "{\"k1\":11}").putInt(LearnedKeyboards.PREF_POOL_NEXT, 12)
            .putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
        val deviceFile = LearnedStores.blacklistFile(ctx.filesDir, "Latn", 11)
        deviceFile.parentFile!!.mkdirs()
        deviceFile.writeText("word\t2\n")
        val list = RemovedWords.forFile(deviceFile)
        list.reload() // (the keyboard holds k1's list, read)
        // the backup: k1 is pool 5 there, "word" removed 4 times; pool 11 is another keyboard's, k2
        val k2 = SettingsSubtype(Locale.GERMANY, "").withId(2)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("blacklists/k5/Latn.txt")); z.write("word\t4\n".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("blacklists/k11/Latn.txt")); z.write("other\n".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("preferences.json"))
            val both = keyboard.toPref() + helium314.keyboard.latin.common.Constants.Separators.SETS + k2.toPref()
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_ENABLED_SUBTYPES to both,
                Settings.PREF_ADDITIONAL_SUBTYPES to both, Settings.PREF_SELECTED_SUBTYPE to keyboard.toPref(),
                KeyboardIdUpgrade.DONE to true, Settings.PREF_VERSION_CODE to helium314.keyboard.latin.BuildConfig.MIGRATION_VERSION,
                LearnedKeyboards.PREF_POOLS to "{\"k1\":5,\"k2\":11}", LearnedKeyboards.PREF_POOL_NEXT to 12,
                LearnedKeyboards.PREF_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("protected_preferences.json"))
            settingsToJsonStream(emptyMap<String?, Any?>(), z)
            z.closeEntry()
        }
        // the keyboard's Remove of "word" (on k1's list as it holds it) while the restore copies the files
        FullRestore.afterStep = { if (it == "preferences.json") list.strike("word") }
        restoreEverythingFrom(ctx, zip)

        val k1File = LearnedStores.blacklistFile(ctx.filesDir, "Latn", 5)
        assertEquals(listOf("word\t4"), k1File.readLines().filter { it.isNotEmpty() }, "k1's restored list: as in the backup")
        assertEquals(RemovedWords.Entry(4), RemovedWords.forFile(k1File).entries()["word"])
        assertEquals(listOf("other"), deviceFile.readLines().filter { it.isNotEmpty() }, "k2's list got k1's strike")
        assertEquals(setOf("other"), list.entries().keys)
    }
}

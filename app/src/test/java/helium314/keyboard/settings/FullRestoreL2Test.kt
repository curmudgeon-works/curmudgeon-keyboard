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
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
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
        idleUntil { !BackupRestoreJob.isRunning }
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
}

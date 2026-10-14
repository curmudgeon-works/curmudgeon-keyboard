// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.randomIds
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.withKeyboardName
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

/**
 * A keyboard by keyboard restore that fails after it changed something on the device (A3): nothing is undone (owner
 * 2026-10-12: no rollback), and the restore says "Restore stopped partway; some items may have been renamed or only
 * partly restored." instead of the plain error.
 */
@RunWith(RobolectricTestRunner::class)
class RestorePartwayTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "partway-${System.nanoTime()}.zip") }
    private lateinit var qwerty: SettingsSubtype
    private lateinit var dvorak: SettingsSubtype
    private lateinit var english: String

    /** The learned words' stores, failing when the restore reads the backup's: after the keyboards were restored. */
    private val failingIo = object : LearnedStoreIo {
        private val body = BodyStoreIo()
        override fun readFile(dir: File, locale: Locale): List<LearnedEntry>? = throw IllegalStateException("injected: reading ${dir.name}")
        override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>) = body.writeFile(dir, locale, entries)
        override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? = null
    }

    @Before fun setUp() {
        LearnedKeyboards.storeIo = BodyStoreIo()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        deleteLearnedFiles(ctx.filesDir)
        SubtypeSettings.init(ctx)
        qwerty = available("en-US").withId(1)
        dvorak = available("en-US").withLayout(LayoutType.MAIN, "dvorak").withId(2)
        english = keyboardName(qwerty, ctx)
    }

    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        idleUntil { !BackupRestoreJob.isRunning }
        randomIds(); zip.delete(); deleteLearnedFiles(ctx.filesDir)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    /** The device: QWERTY ("English (US)"); the backup: Dvorak, also "English (US)", with learned words of before. */
    private fun deviceAndBackup() {
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(qwerty)))
            .putBoolean("separate_settings_per_keyboard", true).commit()
        useKeyboards(ctx, real, qwerty)
        val store = "UserHistoryDictionary.en-US.dict"
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(HashMap<String?, Any?>(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(dvorak)),
                Settings.PREF_ADDITIONAL_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(dvorak)),
                Settings.PREF_SELECTED_SUBTYPE to dvorak.toPref(), KeyboardIdUpgrade.DONE to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true)), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("$store/$store.body")); z.write("words\n".toByteArray()); z.closeEntry()
        }
    }

    @Test fun `a failure after the device keyboard was renamed says the restore stopped partway, the rename stays`() {
        deviceAndBackup()
        var error: String? = null
        // (Keep both: the device's QWERTY becomes "English (US) 2" first, then the learned words fail)
        assertTrue(runRestore(ctx, { error = it }, R.string.backup_restored, 1) {
            restoreChosenFrom(ctx, zip, listOf(dvorak), settings = true, learnedWords = true, io = failingIo,
                decisions = mapOf(dvorak to RestoreDecision.Separate(english)))
        })
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the job never ended")
        val renamed = withKeyboardName(qwerty, "$english 2").toPref().toSettingsSubtype()
        assertTrue(renamed in SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }, "the rename didn't happen before the failure")
        assertEquals("p", error, "not reported as a restore stopped partway")
        // (the dialog's text for "p"; by name, so this test builds without it too)
        val text = ctx.resources.getIdentifier("restore_partway", "string", R::class.java.`package`!!.name)
        assertEquals("Restore stopped partway; some items may have been renamed or only partly restored.", ctx.getString(text))
    }
}

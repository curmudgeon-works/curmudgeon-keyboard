// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.translit.Transliteration
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.ResetKeep
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.preferences.startFactoryReset
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Suggest in हिंदी is a keyboard's own setting, kept and copied as its languages' priorities are. */
@RunWith(RobolectricTestRunner::class)
class SuggestInScriptKeptTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val hinglish = Locale.forLanguageTag("hi-Latn")
    private val key = Transliteration.key(hinglish)
    private val keyboard = SettingsSubtype(Locale.US, "").with(ExtraValue.SECONDARY_LOCALES, "hi-Latn").withId(1)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        // (a keyboard on the device, the main one: a write for no keyboard of the device is dropped)
        storeKeyboards(ctx, real, listOf(keyboard), reload = false)
    }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun idle(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `it is a language key of the keyboard's own`() {
        assertTrue(key in LanguagePriority.keys(hinglish))
        assertTrue(LanguagePriority.isKeyboardsOwnKey(key))
        assertTrue(LanguagePriority.isLanguageKey(key))
        assertFalse(LanguagePriority.isPriorityKey(key))
        assertFalse(KeyboardProfiles.isAppWide(key))
    }

    @Test fun `a copy of a keyboard's settings takes it`() {
        real.edit().putInt(idKey(1, key), Transliteration.ALL).commit()
        KeyboardProfiles.copy(real, kid(1), kid(2))
        assertEquals(Transliteration.ALL, real.getInt(idKey(2, key), Transliteration.OFF))
    }

    @Test fun `a whole restore brings it back`() {
        val zip = File(ctx.cacheDir, "suggest_in_script.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(helium314.keyboard.latin.settings.Settings.PREF_ENABLED_SUBTYPES to keyboard.toPref(),
                helium314.keyboard.latin.settings.Settings.PREF_ADDITIONAL_SUBTYPES to keyboard.toPref(),
                helium314.keyboard.latin.settings.KeyboardIdUpgrade.DONE to true,
                helium314.keyboard.latin.settings.Settings.PREF_VERSION_CODE to helium314.keyboard.latin.BuildConfig.MIGRATION_VERSION,
                idKey(1, key) to Transliteration.SOME), out)
            out.closeEntry()
        }
        try {
            assertTrue(runRestore(ctx, {}, R.string.backup_restored, 1) { restoreEverythingFrom(ctx, zip) })
            idle { !BackupRestoreJob.isRunning }
        } finally { zip.delete() }
        assertEquals(Transliteration.SOME, Transliteration.get(ctx.prefs(), hinglish), "not restored")
    }

    @Test fun `a factory reset keeping the keyboards keeps it, a full one takes it back to Off`() {
        ctx.prefs().edit().putInt(key, Transliteration.ALL).commit() // (the first keyboard's set)
        var done = false
        startFactoryReset(ctx, ResetKeep(keyboards = true)) { done = true }
        idle { done }
        assertTrue(done, "the reset never ran")
        assertEquals(Transliteration.ALL, Transliteration.get(ctx.prefs(), hinglish), "not kept with the keyboards")

        done = false
        startFactoryReset(ctx, ResetKeep()) { done = true }
        idle { done }
        assertTrue(done, "the reset never ran")
        assertFalse(ctx.prefs().contains(key), "not reset")
        assertEquals(Transliteration.OFF, Transliteration.get(ctx.prefs(), hinglish))
    }
}

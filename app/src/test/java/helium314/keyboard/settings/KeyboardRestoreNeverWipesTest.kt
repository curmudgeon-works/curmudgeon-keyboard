// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.kid
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A keyboard restore never wipes (owner 2026-10-11: "User should be given the option to do a full restore which wipes,
 * or partial keyboard by keyboard where all keyboards are just another case"): every keyboard of the backup with its
 * settings is restored keyboard by keyboard too; only the full restore replaces everything.
 * (owner 2026-10-11: keyboards have unique ids) The phone's keyboards are k1, k2 (k4), the backup's k6, k7: one added
 * keeps the backup's id, one merged the phone keyboard's.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardRestoreNeverWipesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "never-wipes-${System.nanoTime()}.zip") }
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val appWide = Settings.PREF_CLIPBOARD_HISTORY_SIZE
    private lateinit var en: SettingsSubtype
    private lateinit var hi: SettingsSubtype
    private lateinit var fr: SettingsSubtype
    private fun view(id: String) = ProfilePreferences(real) { id }
    private fun own(id: String, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        en = available("en-US").withId(1); hi = available("hi").withId(2); fr = available("fr").withId(3)
        // the phone: English (k1) and Hindi (k2), separate settings, gaps 1 and 4, clipboard history 10
        useKeyboards(ctx, real, en, hi)
        real.edit().putBoolean("separate_settings_per_keyboard", true).putFloat(own(kid(1), gap), 1f).putFloat(own(kid(2), gap), 4f)
            .putInt(appWide, 10).commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** The backup (of another phone): [keyboards] as its keyboards k6, k7, ..., each with its own set (gap 5 + its
     *  index), clipboard history 99. @return its keyboards as in the backup */
    private fun backup(vararg keyboards: SettingsSubtype): List<SettingsSubtype> {
        val inBackup = keyboards.mapIndexed { i, kb -> kb.withoutIds().withId(6 + i) }
        val values = HashMap<String, Any?>()
        inBackup.forEachIndexed { i, kb -> values[own(kb.keyboardId!!, gap)] = 5f + i }
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(inBackup),
                Settings.PREF_ADDITIONAL_SUBTYPES to SubtypeSettings.createPrefSubtypes(inBackup),
                Settings.PREF_SELECTED_SUBTYPE to inBackup.first().toPref(), "separate_settings_per_keyboard" to true,
                KeyboardIdUpgrade.DONE to true, appWide to 99,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) + values, z)
            z.closeEntry()
        }
        return inBackup
    }

    @Test fun `the backup's only keyboard with settings is added, the phone's keyboards stay with theirs`() {
        val backupFr = backup(fr).single()
        restoreChosenFrom(ctx, zip, listOf(backupFr), settings = true, learnedWords = false, io = LearnedStoreIo.Native)
        assertEquals(listOf(en, hi, backupFr), enabled()) // (added: with the backup's id)
        assertEquals(1f, view(kid(1)).getFloat(gap, 0f)); assertEquals(4f, view(kid(2)).getFloat(gap, 0f))
        assertEquals(5f, view(kid(6)).getFloat(gap, 0f))
        assertEquals(10, real.getInt(appWide, 0), "an app-wide setting came from the backup")
    }

    @Test fun `every backup keyboard merged whole is still restored keyboard by keyboard`() {
        val de = available("de-DE").withId(4)
        useKeyboards(ctx, real, en, hi, de)
        real.edit().putFloat(own(kid(4), gap), 8f).commit()
        val (backupEn, backupHi) = backup(en, hi)
        restoreChosenFrom(ctx, zip, listOf(backupEn, backupHi), settings = true, learnedWords = false, io = LearnedStoreIo.Native,
            decisions = mapOf(backupEn to RestoreDecision.Merge(en), backupHi to RestoreDecision.Merge(hi)))
        assertEquals(listOf(en, hi, de), enabled()) // (merged: the phone keyboards' ids)
        assertEquals(5f, view(kid(1)).getFloat(gap, 0f)); assertEquals(6f, view(kid(2)).getFloat(gap, 0f))
        assertEquals(8f, view(kid(4)).getFloat(gap, 0f))
        assertEquals(10, real.getInt(appWide, 0))
    }
}

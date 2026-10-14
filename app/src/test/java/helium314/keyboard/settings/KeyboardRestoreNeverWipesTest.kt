// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.profileIds
import helium314.keyboard.latin.personalization.useKeyboards
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
import org.json.JSONObject
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
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        en = available("en-US"); hi = available("hi"); fr = available("fr")
        // the phone: English (1) and Hindi (2), separate settings, gaps 1 and 4, clipboard history 10
        useKeyboards(ctx, real, en, hi)
        profileIds(real, mapOf(en to 1, hi to 2), 3)
        real.edit().putBoolean("separate_settings_per_keyboard", true).putFloat(own(1, gap), 1f).putFloat(own(2, gap), 4f)
            .putInt(appWide, 10).commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** The backup: [keyboards], each with its own set (gap 5 + its index), clipboard history 99. */
    private fun backup(vararg keyboards: SettingsSubtype) {
        val ids = JSONObject(); val values = HashMap<String, Any?>()
        keyboards.forEachIndexed { i, kb -> ids.put(kb.toPref(), 6 + i); values[own(6 + i, gap)] = 5f + i }
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(keyboards.toList()),
                Settings.PREF_SELECTED_SUBTYPE to keyboards.first().toPref(), "separate_settings_per_keyboard" to true,
                "keyboard_profile_ids" to ids.toString(), "keyboard_profile_next_id" to 6 + keyboards.size, appWide to 99,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) + values, z)
            z.closeEntry()
        }
    }

    @Test fun `the backup's only keyboard with settings is added, the phone's keyboards stay with theirs`() {
        backup(fr)
        restoreChosenFrom(ctx, zip, listOf(fr), settings = true, learnedWords = false, io = LearnedStoreIo.Native)
        assertEquals(listOf(en, hi, fr), enabled())
        assertEquals(1f, view(1).getFloat(gap, 0f)); assertEquals(4f, view(2).getFloat(gap, 0f))
        assertEquals(5f, view(KeyboardProfiles.idFor(real, fr)).getFloat(gap, 0f))
        assertEquals(10, real.getInt(appWide, 0), "an app-wide setting came from the backup")
    }

    @Test fun `every backup keyboard merged whole is still restored keyboard by keyboard`() {
        val de = available("de-DE")
        useKeyboards(ctx, real, en, hi, de)
        val deId = KeyboardProfiles.idFor(real, de)
        real.edit().putFloat(own(deId, gap), 8f).commit()
        backup(en, hi)
        restoreChosenFrom(ctx, zip, listOf(en, hi), settings = true, learnedWords = false, io = LearnedStoreIo.Native,
            decisions = mapOf(en to RestoreDecision.Merge(en), hi to RestoreDecision.Merge(hi)))
        assertEquals(listOf(en, hi, de), enabled())
        assertEquals(5f, view(1).getFloat(gap, 0f)); assertEquals(6f, view(2).getFloat(gap, 0f))
        assertEquals(1, KeyboardProfiles.idFor(real, en)); assertEquals(2, KeyboardProfiles.idFor(real, hi))
        assertEquals(8f, view(deId).getFloat(gap, 0f))
        assertEquals(10, real.getInt(appWide, 0))
    }
}

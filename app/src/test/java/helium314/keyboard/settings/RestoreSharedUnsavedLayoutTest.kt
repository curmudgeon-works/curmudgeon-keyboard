// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.kid
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.restoreClock
import helium314.keyboard.settings.preferences.restoreDifferencesFrom
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Owner 2026-10-13, 20261013_L6 (round-5 review A7): an unsaved key layout two device keyboards use, both merged in the
 * same restore with rows that switch them away from it, is saved once as "temp_MMDD" with the usual warning: a keyboard
 * counts as a user only when it still names the layout after the restore. Fail before: each counted the other as a
 * user, neither saved it, and the cleanup deleted the file (the keys were lost).
 */
@RunWith(RobolectricTestRunner::class)
class RestoreSharedUnsavedLayoutTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "shared-unsaved-${System.nanoTime()}.zip") }
    private val type = LayoutType.NUMBER_ROW
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private fun folder() = file("x").parentFile!!
    private val temp get() = LayoutUtilsCustom.getLayoutName("temp_1012", type)
    private val temp2 get() = LayoutUtilsCustom.getLayoutName("temp_1012 2", type)
    private lateinit var hindi: SettingsSubtype
    private lateinit var english: SettingsSubtype

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        folder().deleteRecursively(); folder().mkdirs(); LayoutUtilsCustom.onLayoutFileChanged()
        hindi = available("hi").withId(1); english = available("en-US").withId(2)
        // (the device's date: 12 October)
        restoreClock = { Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 12, 12, 0) }.timeInMillis }
    }
    @After fun tearDown() {
        restoreClock = { System.currentTimeMillis() }
        zip.delete(); folder().deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun unsaved(keys: String) = LayoutUtilsCustom.makePrivateLayout(keys, type, "", ctx)
    private fun listed(id: Int) = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }.single { it.keyboardId == kid(id) }

    /** The device: [phones] (separate settings on); the backup: [inBackup] (the device's keyboards themselves, their
     *  ids), each with the saved number row [name] holding [keys]. */
    private fun deviceAndBackup(phones: List<SettingsSubtype>, inBackup: List<SettingsSubtype>, name: String, keys: String) {
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        useKeyboards(ctx, real, *phones.toTypedArray())
        val list = SubtypeSettings.createPrefSubtypes(inBackup)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                KeyboardIdUpgrade.DONE to true, KeyboardProfiles.NO_COMMON_SET_DONE to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$name")); z.write(keys.toByteArray()); z.closeEntry()
        }
    }

    /** The backup's [keyboard] merged whole into the device's [phone] (every row ticked). */
    private fun wholeMerge(keyboard: SettingsSubtype, phone: SettingsSubtype): RestoreDecision {
        val differences = restoreDifferencesFrom(ctx, zip, keyboard, phone)
        return restorePlanFrom(ctx, zip).mergeDecision(phone, differences, differences, learnedRow = false, learnedTicked = false, name = null)
    }

    private val warningText = "Warning: Unsaved number row on the device will be saved as \"temp_1012\" to prevent loss of unsaved data"

    @Test fun `two keyboards on one unsaved number row, both switched away - saved once as temp_1012, the warning once the other is answered`() {
        val x = unsaved("A")
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        val hindiPhone = hindi.withLayout(type, x).withId(1)
        val englishPhone = english.withLayout(type, x).withId(2)
        val hindiBackup = hindi.withLayout(type, numrow).withId(1)
        val englishBackup = english.withLayout(type, numrow).withId(2)
        deviceAndBackup(listOf(hindiPhone, englishPhone), listOf(hindiBackup, englishBackup), numrow, "B")
        val englishMerge = wholeMerge(englishBackup, englishPhone)
        val hindiMerge = wholeMerge(hindiBackup, hindiPhone)

        // the dialog: Hindi's number row row, with English not answered yet (it still uses x) and with English's answer
        val row = restoreDifferencesFrom(ctx, zip, hindiBackup, hindiPhone).filterIsInstance<RestoreMatch.Difference.Definition>()
            .single { it.part == RestoreMatch.Part.Layout(type) }
        val plan = restorePlanFrom(ctx, zip)
        assertNull(RestoreMatch.unsavedWarning(ctx, row, plan.unsavedSaves(hindiPhone)), "English still uses it: no warning yet")
        assertEquals(warningText, RestoreMatch.unsavedWarning(ctx, row, plan.unsavedSaves(hindiPhone, mapOf(englishBackup to englishMerge))))

        restoreKeyboardsFrom(ctx, zip, listOf(hindiBackup, englishBackup), withSettings = true,
            decisions = mapOf(hindiBackup to hindiMerge, englishBackup to englishMerge))
        assertEquals(numrow, listed(1).layoutName(type))
        assertEquals(numrow, listed(2).layoutName(type))
        assertEquals("A", file(temp).readText(), "the device's keys, saved")
        assertFalse(LayoutUtilsCustom.isPrivateLayout(temp))
        assertEquals(setOf(numrow, temp), folder().list()!!.toSet(), "saved once; the unused unsaved one gone")
    }

    @Test fun `two keyboards on two unsaved number rows, both switched away - each saved under its own name`() {
        val x = unsaved("A")
        val y = unsaved("C")
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        val hindiPhone = hindi.withLayout(type, x).withId(1)
        val englishPhone = english.withLayout(type, y).withId(2)
        val hindiBackup = hindi.withLayout(type, numrow).withId(1)
        val englishBackup = english.withLayout(type, numrow).withId(2)
        deviceAndBackup(listOf(hindiPhone, englishPhone), listOf(hindiBackup, englishBackup), numrow, "B")
        restoreKeyboardsFrom(ctx, zip, listOf(hindiBackup, englishBackup), withSettings = true,
            decisions = mapOf(hindiBackup to wholeMerge(hindiBackup, hindiPhone), englishBackup to wholeMerge(englishBackup, englishPhone)))
        assertEquals(setOf(numrow, temp, temp2), folder().list()!!.toSet())
        assertEquals(setOf("A", "C"), setOf(file(temp).readText(), file(temp2).readText()))
    }
}

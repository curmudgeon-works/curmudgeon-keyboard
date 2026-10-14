// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
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
import kotlin.test.assertTrue

/**
 * Owner 2026-10-13, 20261013_M1 (round-5 review A1): an unsaved key layout a device keyboard reaches through its own set's
 * default (not its own pick) is treated exactly like an own pick: a ticked menu row replacing that default warns, and its
 * keys are saved as "temp_MMDD". Fail before: no warning, the backup's settings replaced the default, and the cleanup
 * deleted the unused file (other name), or the device's file was renamed to a new unsaved name and then deleted (same
 * name, other keys: no row either).
 */
@RunWith(RobolectricTestRunner::class)
class RestoreDefaultLayoutTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "default-layout-${System.nanoTime()}.zip") }
    private val type = LayoutType.NUMBER_ROW
    private val key = Settings.PREF_LAYOUT_PREFIX + LayoutType.NUMBER_ROW.name
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private fun folder() = file("x").parentFile!!
    private val temp get() = LayoutUtilsCustom.getLayoutName("temp_1012", type)
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
    private fun default(keyboard: SettingsSubtype) = real.getString(KeyboardProfiles.ownKey(keyboard.keyboardId!!, key), null)

    /** The device: [keyboards] ([separate] settings or not), the first one's own set has the number row default
     *  [deviceDefault]; the backup: the first keyboard itself (its id), its own set's number row default [backupDefault],
     *  that file holding [keys], and a gap of 3 (another menu's row). */
    private fun deviceAndBackup(keyboards: List<SettingsSubtype>, separate: Boolean, deviceDefault: String, backupDefault: String, keys: String) {
        real.edit().putBoolean("separate_settings_per_keyboard", separate).commit()
        useKeyboards(ctx, real, *keyboards.toTypedArray())
        val phone = keyboards.first()
        real.edit().putString(KeyboardProfiles.ownKey(phone.keyboardId!!, key), deviceDefault)
            .putFloat(KeyboardProfiles.ownKey(phone.keyboardId!!, Settings.PREF_KEY_HORIZONTAL_GAP), 1f).commit()
        val list = SubtypeSettings.createPrefSubtypes(listOf(phone))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                KeyboardIdUpgrade.DONE to true, KeyboardProfiles.NO_COMMON_SET_DONE to true,
                KeyboardProfiles.ownKey(phone.keyboardId!!, Settings.PREF_KEY_HORIZONTAL_GAP) to 3f,
                KeyboardProfiles.ownKey(phone.keyboardId!!, key) to backupDefault,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$backupDefault")); z.write(keys.toByteArray()); z.closeEntry()
        }
    }

    private fun layoutRow(differences: List<RestoreMatch.Difference>) =
        differences.filterIsInstance<RestoreMatch.Difference.Menu>().singleOrNull { it.group == KeyboardProfiles.Group.LAYOUT }

    /** Restores [keyboard] merged into itself on the device, every row ticked (the whole keyboard). */
    private fun restoreWhole(keyboard: SettingsSubtype) {
        val differences = restoreDifferencesFrom(ctx, zip, keyboard, keyboard)
        val decision = restorePlanFrom(ctx, zip).mergeDecision(keyboard, differences, differences,
            learnedRow = false, learnedTicked = false, name = null)
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true, decisions = mapOf(keyboard to decision))
    }

    private val warningText = "Warning: Unsaved number row on the device will be saved as \"temp_1012\" to prevent loss of unsaved data"

    @Test fun `an unsaved default replaced by another - the menu row warns, the run saves the device's keys as temp_1012`() {
        val a = unsaved("A")
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        deviceAndBackup(listOf(hindi), separate = true, deviceDefault = a, backupDefault = numrow, keys = "B")
        val row = layoutRow(restoreDifferencesFrom(ctx, zip, hindi, hindi))
        assertTrue(row != null, "the Layout & Typing settings row")
        val plan = restorePlanFrom(ctx, zip)
        assertEquals(warningText, RestoreMatch.unsavedDefaultsWarning(ctx, row!!, plan.unsavedDefaultSaves(hindi, hindi)))
        assertEquals(setOf(a), folder().list()!!.toSet(), "the dialog writes nothing")
        restoreWhole(hindi)
        assertEquals(numrow, default(hindi), "the backup's default")
        assertEquals("B", file(numrow).readText())
        assertEquals("A", file(temp).readText(), "the device's keys, saved")
        assertFalse(LayoutUtilsCustom.isPrivateLayout(temp))
        assertEquals(setOf(numrow, temp), folder().list()!!.toSet(), "the unused unsaved one gone, its keys kept")
    }

    @Test fun `one set for all - the main keyboard's unsaved default is still saved, the others reading it are no users of their own`() {
        val a = unsaved("A")
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        deviceAndBackup(listOf(hindi, english), separate = false, deviceDefault = a, backupDefault = numrow, keys = "B")
        restoreWhole(hindi)
        assertEquals(numrow, default(hindi))
        assertEquals("A", file(temp).readText(), "the device's keys, saved")
    }

    @Test fun `an unsaved default of one name with other keys - a row and its warning, the device's keys saved as temp_1012`() {
        val a = unsaved("A")
        deviceAndBackup(listOf(hindi), separate = true, deviceDefault = a, backupDefault = a, keys = "B")
        val row = layoutRow(restoreDifferencesFrom(ctx, zip, hindi, hindi))
        assertTrue(row != null, "one name, other keys: the Layout & Typing settings row")
        assertEquals(warningText, RestoreMatch.unsavedDefaultsWarning(ctx, row!!, restorePlanFrom(ctx, zip).unsavedDefaultSaves(hindi, hindi)))
        restoreWhole(hindi)
        assertEquals(a, default(hindi), "the backup's keys, under its name")
        assertEquals("B", file(a).readText())
        assertEquals("A", file(temp).readText(), "the device's keys, saved")
        assertEquals(setOf(a, temp), folder().list()!!.toSet())
    }

    @Test fun `the Layout & Typing row unticked - the default and its keys stay, nothing saved`() {
        val a = unsaved("A")
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        deviceAndBackup(listOf(hindi), separate = true, deviceDefault = a, backupDefault = numrow, keys = "B")
        val differences = restoreDifferencesFrom(ctx, zip, hindi, hindi)
        val decision = restorePlanFrom(ctx, zip).mergeDecision(hindi, differences, differences - layoutRow(differences)!!,
            learnedRow = false, learnedTicked = false, name = null)
        restoreKeyboardsFrom(ctx, zip, listOf(hindi), withSettings = true, decisions = mapOf(hindi to decision))
        assertEquals(a, default(hindi))
        assertEquals("A", file(a).readText())
        assertFalse(file(temp).exists())
    }
}

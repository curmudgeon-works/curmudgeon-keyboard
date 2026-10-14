// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
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
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.restoreClock
import helium314.keyboard.settings.preferences.restoreDifferencesFrom
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.kid
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
import kotlin.test.assertTrue

/**
 * Review 2026-10-12 A1: a key layout of one name on the device and in the backup with other keys. Fail before: no "What
 * to merge" row (one name was the same layout), the backup's keys came in and the keyboard switched silently; an unsaved
 * one of the device was renamed to a new unsaved name, unused then, and deleted. Now (owner 2026-10-12): a row for every
 * layout type; a saved one "Number row: Backup's My numrow; yours becomes My numrow 2"; an unsaved one "Number row:
 * device and backup differ", and ticked "Unsaved number row on the device will be saved as "temp_MMDD" to prevent loss
 * of unsaved data". Unticked: the device's keys stay, nothing renamed, saved or brought in.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreSameNameLayoutTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "same-name-${System.nanoTime()}.zip") }
    private fun file(name: String, type: LayoutType) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private fun folder(type: LayoutType) = file("x", type).parentFile!!
    private val types = listOf(LayoutType.NUMBER_ROW, LayoutType.SYMBOLS)
    private lateinit var hindi: SettingsSubtype
    private lateinit var english: SettingsSubtype

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        types.forEach { folder(it).deleteRecursively(); folder(it).mkdirs() }; LayoutUtilsCustom.onLayoutFileChanged()
        hindi = available("hi").withId(1); english = available("en-US").withId(2)
        // (the device's date: 12 October)
        restoreClock = { Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 12, 12, 0) }.timeInMillis }
    }
    @After fun tearDown() {
        restoreClock = { System.currentTimeMillis() }
        zip.delete(); types.forEach { folder(it).deleteRecursively() }; LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun unsaved(keys: String, type: LayoutType) = LayoutUtilsCustom.makePrivateLayout(keys, type, "", ctx)
    private fun named(name: String, keys: String, type: LayoutType) = LayoutUtilsCustom.getLayoutName(name, type).also {
        file(it, type).writeText(keys); LayoutUtilsCustom.onLayoutFileChanged()
    }
    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    private fun listed(id: Int) = enabled().single { it.keyboardId == kid(id) }
    private fun gap(id: Int) = KeyboardProfiles.effectiveSettingsIn(real.all, listed(id))[Settings.PREF_KEY_HORIZONTAL_GAP]

    /** The device: [keyboards] (separate settings on, gaps 1); the backup: [inBackup] (the device's first keyboard itself,
     *  its id) with the [type] file [name] holding [keys], gap 3 (a row of its own: a merge in parts restores it). */
    private fun deviceAndBackup(keyboards: List<SettingsSubtype>, inBackup: SettingsSubtype, type: LayoutType, name: String, keys: String) {
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        useKeyboards(ctx, real, *keyboards.toTypedArray())
        real.edit().apply { for (k in keyboards) putFloat(KeyboardProfiles.ownKey(k.keyboardId!!, Settings.PREF_KEY_HORIZONTAL_GAP), 1f) }.commit()
        val list = SubtypeSettings.createPrefSubtypes(listOf(inBackup))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                KeyboardIdUpgrade.DONE to true, KeyboardProfiles.NO_COMMON_SET_DONE to true,
                KeyboardProfiles.ownKey(inBackup.keyboardId!!, Settings.PREF_KEY_HORIZONTAL_GAP) to 3f,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$name")); z.write(keys.toByteArray()); z.closeEntry()
        }
    }

    private fun row(differences: List<RestoreMatch.Difference>, type: LayoutType) =
        differences.filterIsInstance<RestoreMatch.Difference.Definition>().singleOrNull { it.part == RestoreMatch.Part.Layout(type) }

    /** Restores [keyboard] merged into itself on the device, the [type] row ticked or not (every other row ticked). */
    private fun restore(keyboard: SettingsSubtype, type: LayoutType, ticked: Boolean): RestoreMatch.Difference {
        val differences = restoreDifferencesFrom(ctx, zip, keyboard, keyboard)
        val row = row(differences, type)
        assertTrue(row != null, "no row for $type: $differences")
        val plan = restorePlanFrom(ctx, zip)
        val decision = plan.mergeDecision(keyboard, differences, if (ticked) differences else differences - row,
            learnedRow = false, learnedTicked = false, name = null)
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true, decisions = mapOf(keyboard to decision))
        return row
    }

    // ---- (a) a saved key layout ----

    @Test fun `(a) a saved number row of one name with other keys - a row, ticked the backup's keys and the device's as My numrow 2`() {
        val type = LayoutType.NUMBER_ROW
        val numrow = named("My numrow", "A", type)
        val numrow2 = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
        val phone = hindi.withLayout(type, numrow).withId(1)
        deviceAndBackup(listOf(phone), phone, type, numrow, "B")
        val plan = restorePlanFrom(ctx, zip)
        val row = row(restoreDifferencesFrom(ctx, zip, phone, phone), type)!!
        assertEquals("Number row: Backup's My numrow; yours becomes My numrow 2",
            RestoreMatch.rowText(ctx, row, phone, plan.items.keyLayouts::deviceRename))
        assertNull(RestoreMatch.unsavedWarning(ctx, row, plan.items.keyLayouts::deviceSave), "a saved one: no warning")
        restore(phone, type, ticked = true)
        assertEquals(numrow, listed(1).layoutName(type))
        assertEquals("B", file(numrow, type).readText())
        assertEquals("A", file(numrow2, type).readText(), "the device's keys, renamed")
    }

    @Test fun `(a) unticked the device's saved number row keeps its keys, nothing renamed`() {
        val type = LayoutType.NUMBER_ROW
        val numrow = named("My numrow", "A", type)
        val phone = hindi.withLayout(type, numrow).withId(1)
        deviceAndBackup(listOf(phone), phone, type, numrow, "B")
        restore(phone, type, ticked = false)
        assertEquals(3f, gap(1), "the rest of the merge came")
        assertEquals(numrow, listed(1).layoutName(type))
        assertEquals("A", file(numrow, type).readText())
        assertEquals(setOf(numrow), folder(type).list()!!.toSet())
    }

    // ---- (b) an unsaved key layout ----

    @Test fun `(b) an unsaved number row of one name with other keys - the row and its warning say what it is saved as`() {
        val type = LayoutType.NUMBER_ROW
        val a = unsaved("A", type)
        val phone = hindi.withLayout(type, a).withId(1)
        deviceAndBackup(listOf(phone), phone, type, a, "B")
        val plan = restorePlanFrom(ctx, zip)
        val row = row(restoreDifferencesFrom(ctx, zip, phone, phone), type)!!
        assertEquals("Number row: device and backup differ", RestoreMatch.rowText(ctx, row, phone, plan.items.keyLayouts::deviceRename))
        assertEquals(ctx.getString(R.string.restore_differs_unsaved, "Number row"), RestoreMatch.rowText(ctx, row, phone, plan.items.keyLayouts::deviceRename))
        assertEquals("Warning: Unsaved number row on the device will be saved as \"temp_1012\" to prevent loss of unsaved data",
            RestoreMatch.unsavedWarning(ctx, row, plan.items.keyLayouts::deviceSave))
        assertEquals(LayoutUtilsCustom.getLayoutName("temp_1012", type), plan.items.keyLayouts.deviceSave(type, a))
        assertEquals(setOf(a), folder(type).list()!!.toSet(), "the dialog writes nothing")
    }

    @Test fun `(b) ticked the keyboard gets the backup's keys and the device's are saved as temp_1012, its other user following`() {
        val type = LayoutType.NUMBER_ROW
        val a = unsaved("A", type)
        val phone = hindi.withLayout(type, a).withId(1)
        val other = english.withLayout(type, a).withId(2) // (another device keyboard on the same unsaved file)
        deviceAndBackup(listOf(phone, other), phone, type, a, "B")
        restore(phone, type, ticked = true)
        val temp = LayoutUtilsCustom.getLayoutName("temp_1012", type)
        assertEquals(a, listed(1).layoutName(type), "the backup's keys, under its name")
        assertEquals("B", file(a, type).readText())
        assertEquals("A", file(temp, type).readText(), "the device's keys, saved")
        assertEquals("temp_1012", LayoutUtilsCustom.getDisplayName(temp))
        assertFalse(LayoutUtilsCustom.isPrivateLayout(temp))
        assertEquals(temp, listed(2).layoutName(type), "the other keyboard keeps its keys")
        LayoutUtilsCustom.removeUnusedPrivateLayouts(ctx)
        assertEquals(setOf(a, temp), folder(type).list()!!.toSet(), "nothing deleted")
    }

    @Test fun `(b) a taken temp_1012 - the device's are saved as temp_1012 2, as the warning said`() {
        val type = LayoutType.NUMBER_ROW
        val taken = named("temp_1012", "T", type)
        val a = unsaved("A", type)
        val phone = hindi.withLayout(type, a).withId(1)
        deviceAndBackup(listOf(phone), phone, type, a, "B")
        val plan = restorePlanFrom(ctx, zip)
        val row = row(restoreDifferencesFrom(ctx, zip, phone, phone), type)!!
        assertEquals("Warning: Unsaved number row on the device will be saved as \"temp_1012 2\" to prevent loss of unsaved data",
            RestoreMatch.unsavedWarning(ctx, row, plan.items.keyLayouts::deviceSave))
        restore(phone, type, ticked = true)
        val temp2 = LayoutUtilsCustom.getLayoutName("temp_1012 2", type)
        assertEquals("A", file(temp2, type).readText())
        assertEquals("T", file(taken, type).readText(), "the one there, untouched")
    }

    @Test fun `(b) unticked the device's unsaved number row keeps its keys, nothing saved`() {
        val type = LayoutType.NUMBER_ROW
        val a = unsaved("A", type)
        val phone = hindi.withLayout(type, a).withId(1)
        deviceAndBackup(listOf(phone), phone, type, a, "B")
        restore(phone, type, ticked = false)
        assertEquals(3f, gap(1), "the rest of the merge came")
        assertEquals(a, listed(1).layoutName(type))
        assertEquals("A", file(a, type).readText())
        assertEquals(setOf(a), folder(type).list()!!.toSet())
    }

    // ---- (c) another layout type ----

    @Test fun `(c) unsaved symbols of one name with other keys - a row, ticked saved as temp_1012, unticked kept`() {
        val type = LayoutType.SYMBOLS
        val a = unsaved("A", type)
        val phone = hindi.withLayout(type, a).withId(1)
        deviceAndBackup(listOf(phone), phone, type, a, "B")
        val plan = restorePlanFrom(ctx, zip)
        val row = row(restoreDifferencesFrom(ctx, zip, phone, phone), type)!!
        assertEquals("Symbols: device and backup differ", RestoreMatch.rowText(ctx, row, phone, plan.items.keyLayouts::deviceRename))
        assertEquals("Warning: Unsaved symbols on the device will be saved as \"temp_1012\" to prevent loss of unsaved data",
            RestoreMatch.unsavedWarning(ctx, row, plan.items.keyLayouts::deviceSave))
        restore(phone, type, ticked = true)
        val temp = LayoutUtilsCustom.getLayoutName("temp_1012", type)
        assertEquals(a, listed(1).layoutName(type))
        assertEquals("B", file(a, type).readText())
        assertEquals("A", file(temp, type).readText())
    }

    @Test fun `(c) a saved symbols layout of one name with other keys - a row, unticked kept`() {
        val type = LayoutType.SYMBOLS
        val mine = named("My symbols", "A", type)
        val phone = hindi.withLayout(type, mine).withId(1)
        deviceAndBackup(listOf(phone), phone, type, mine, "B")
        val plan = restorePlanFrom(ctx, zip)
        val row = row(restoreDifferencesFrom(ctx, zip, phone, phone), type)!!
        assertEquals("Symbols: Backup's My symbols; yours becomes My symbols 2",
            RestoreMatch.rowText(ctx, row, phone, plan.items.keyLayouts::deviceRename))
        restore(phone, type, ticked = false)
        assertEquals(3f, gap(1), "the rest of the merge came")
        assertEquals(mine, listed(1).layoutName(type))
        assertEquals("A", file(mine, type).readText())
        assertEquals(setOf(mine), folder(type).list()!!.toSet())
    }
}

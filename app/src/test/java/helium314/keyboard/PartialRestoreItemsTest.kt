// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.preferences.SavedItemsMerge
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11, restoring some keyboards: "keep everything existing and also add whatever was in the backup", by
 * content ("Items inside items must be renamed together"), a taken name numbered ("Next free number"), and the
 * restored keyboard pointing at what its items became; a second restore of the same backup adds nothing.
 */
@RunWith(RobolectricTestRunner::class)
class PartialRestoreItemsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val keyboard = SettingsSubtype(Locale.US, "")
    private val zip by lazy { File(ctx.cacheDir, "partial-items-${System.nanoTime()}.zip") }

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun set(name: String, a: String) = JSONObject().put("name", name).put("morePopups", "all").put("symbolsLayout", "")
        .put("overrides", JSONObject().put("a", JSONArray(listOf(a))))

    /** The backup: popup set "Mine" (á), Layout "Work" naming it, theme "Sunset" (gap 3), the keyboard on all three. */
    private fun backup() {
        val work = JSONObject().put("name", "Work").put("values", JSONObject()
            .put(KeyPopupOverrides.PREF_SELECTED_SET, JSONObject().put("s", "Mine"))
            .put(Settings.PREF_SHOW_NUMBER_ROW, JSONObject().put("b", true)))
        val sunset = JSONObject().put("name", "Sunset").put("values", JSONObject().put(Settings.PREF_KEY_HORIZONTAL_GAP, JSONObject().put("f", 3.0)))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to keyboard.toPref(),
                KeyPopupOverrides.PREF_SETS to JSONArray(listOf(set("Mine", "á"))).toString(),
                LayoutPresets.PREF to JSONArray(listOf(work)).toString(),
                AppearanceLooks.PREF to JSONArray(listOf(sunset)).toString(),
                KeyPopupOverrides.PREF_SELECTED_SET to "Mine", LayoutPresets.PREF_SELECTED to "Work",
                AppearanceLooks.PREF_SELECTED to "Sunset", Settings.PREF_KEY_HORIZONTAL_GAP to 3f, // (the keyboard on Sunset's look)
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
        }
    }
    private fun own() = ProfilePreferences(real) { KeyboardProfiles.idFor(real, keyboard) }
    private fun ownChoice(key: String) = real.getString(KeyboardProfiles.prefixedKey(KeyboardProfiles.idFor(real, keyboard), key), null)

    @Test fun `new items come in under their names, and a second restore adds nothing`() {
        backup()
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals(listOf("Work"), LayoutPresets.load(real).map { it.name })
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name })
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        assertEquals(1, KeyPopupOverrides.loadSets(real).size)
        assertEquals(1, LayoutPresets.load(real).size)
        assertEquals(1, AppearanceLooks.load(real).size)
    }

    @Test fun `a taken name with other content comes in numbered, the Layout inside follows, the keyboard points at both`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        backup()
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        assertEquals(listOf("Mine", "Mine 2"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals(listOf("á"), KeyPopupOverrides.loadSets(real).last().overrides["a"])
        assertEquals("Mine 2", LayoutPresets.load(real).single().values[KeyPopupOverrides.PREF_SELECTED_SET])
        assertEquals("Mine 2", ownChoice(KeyPopupOverrides.PREF_SELECTED_SET))
        assertEquals("Work", ownChoice(LayoutPresets.PREF_SELECTED))
    }

    @Test fun `the same content under another name is the phone's, and with copies off a taken name is the phone's`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Ours", "all", null, mapOf("a" to listOf("á")))))
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_KEY_HORIZONTAL_GAP to 1f))), now = true)
        backup()
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true, asCopies = false)
        assertEquals(listOf("Ours"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals("Ours", ownChoice(KeyPopupOverrides.PREF_SELECTED_SET))
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name }) // (the phone's Sunset)
        assertEquals(1f, AppearanceLooks.load(real).single().values[Settings.PREF_KEY_HORIZONTAL_GAP])
        // the taken name with other content: the keyboard keeps the backup's look but names no theme, so it shows a
        // plain "Custom N", not "Custom N · from Sunset" (owner 2026-10-11 chose option 2: names never point at other
        // content; before, it named the phone's Sunset)
        assertEquals(3f, own().getFloat(Settings.PREF_KEY_HORIZONTAL_GAP, 0f))
        assertNull(own().getString(AppearanceLooks.PREF_SELECTED, null))
        assertTrue(CustomLabels.has(own(), CustomLabels.Kind.THEME))
    }

    @Test fun `with copies off a popup set whose name the phone uses for other content is not brought in, and nothing names it`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        backup()
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true, asCopies = false)
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name }) // (the phone's Mine, unchanged)
        assertEquals(listOf("à"), KeyPopupOverrides.loadSets(real).single().overrides["a"])
        // (owner 2026-10-11, option 2: the keyboard names no set and shows "Custom N"; the Layout brought in keeps its
        // popups but names no set either)
        assertNull(own().getString(KeyPopupOverrides.PREF_SELECTED_SET, null))
        assertTrue(CustomLabels.has(own(), CustomLabels.Kind.POPUPS))
        val work = LayoutPresets.load(real).single()
        assertEquals("Work", work.name)
        assertFalse(work.values.containsKey(KeyPopupOverrides.PREF_SELECTED_SET))
        assertEquals("Work", ownChoice(LayoutPresets.PREF_SELECTED))
        assertFalse(CustomLabels.has(own(), CustomLabels.Kind.LAYOUT))
    }

    @Test fun `a built-in name is taken too`() {
        assertEquals("Curmudgeon 2", SavedItemsMerge.freeName("Curmudgeon", setOf("Curmudgeon")))
        assertEquals("Sunset 3", SavedItemsMerge.freeName("Sunset", setOf("Sunset", "Sunset 2")))
    }

    @Test fun `a font whose name the phone uses for another file comes in numbered, and the keyboard names that one`() {
        val fonts = File(DeviceProtectedUtils.getFilesDir(ctx), "fonts").apply { mkdirs() }
        File(fonts, "Mine.ttf").writeText("the phone's")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to keyboard.toPref(), Settings.PREF_KEY_FONT to "font:Mine.ttf",
                AppearanceLooks.PREF to JSONArray(listOf(JSONObject().put("name", "Sunset").put("values",
                    JSONObject().put(Settings.PREF_KEY_FONT, JSONObject().put("s", "font:Mine.ttf"))))).toString(),
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/fonts/Mine.ttf")); z.write("the backup's".toByteArray()); z.closeEntry()
        }
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        assertEquals("the phone's", File(fonts, "Mine.ttf").readText())
        assertEquals("the backup's", File(fonts, "Mine 2.ttf").readText())
        assertEquals("font:Mine 2.ttf", ownChoice(Settings.PREF_KEY_FONT))
        assertEquals("font:Mine 2.ttf", AppearanceLooks.load(real).single().values[Settings.PREF_KEY_FONT])
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true) // (again: nothing new)
        assertEquals(setOf("Mine.ttf", "Mine 2.ttf"), fonts.list()!!.toSet())
        assertEquals(1, AppearanceLooks.load(real).size)
        fonts.deleteRecursively()
    }
}

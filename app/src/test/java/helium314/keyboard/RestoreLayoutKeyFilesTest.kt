// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.SavedItemsMerge
import helium314.keyboard.settings.preferences.SavedItemsMerge.Clash
import helium314.keyboard.settings.preferences.SavedItemsMerge.FileClash
import helium314.keyboard.settings.preferences.SavedItemsMerge.Kind
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
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
import kotlin.test.assertTrue

/**
 * (owner 2026-10-11, R4: "should fix R4 too I think, bringing in the names of key groups if any") A restored saved
 * Layout brings the custom key layouts it names ("layout:<type>", its keys "layoutText:<type>"), even when no restored
 * keyboard uses them. None on the phone: it comes in as it is. The phone's under that name with the same keys: the
 * phone's. Other keys: the Layout's keys take the name and the phone's file gets the next number, every keyboard and
 * saved Layout using it following (owner 2026-10-12: the backup's keeps its name, "yours becomes My numrow 2"; nothing
 * asked about the file itself); the Layout unticked, it doesn't come in and nothing changes. Before: the Layout kept the
 * name, which pointed at the phone's other keys.
 * (owner 2026-10-11: keyboards have unique ids) The phone's German is k1, the backup's US k2; a layout rename keeps the
 * keyboard's id (nothing moves); a restore again merges into the keyboard the first one brought.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreLayoutKeyFilesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val type = LayoutType.NUMBER_ROW
    private val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
    private val numrow2 = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
    private val zip by lazy { File(ctx.cacheDir, "layout-key-files-${System.nanoTime()}.zip") }
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private val folder get() = file(numrow).parentFile!!
    private val layoutKey = "layout:${type.name}"

    // the phone's keyboard on My numrow; the restored keyboard uses none (its Layout names it)
    private val phoneKeyboard = SettingsSubtype(Locale.GERMANY, "").withLayout(type, numrow).withId(1)
    private val restored = SettingsSubtype(Locale.US, "").withId(2)

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        folder.deleteRecursively(); folder.mkdirs()
        LayoutUtilsCustom.onLayoutFileChanged()
    }
    @After fun tearDown() {
        zip.delete(); folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The phone: [phoneKeyboard] (k1), its list and registration in one write; its saved Layout "Home" on My
     *  numrow, holding [homeKeys]. */
    private fun phone(homeKeys: String) {
        val home = LayoutPresets.Preset("Home", mapOf(layoutKey to numrow, "layoutText:${type.name}" to homeKeys))
        real.edit()
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, phoneKeyboard.toPref())
            .putString(Settings.PREF_ENABLED_SUBTYPES, phoneKeyboard.toPref())
            .putString(Settings.PREF_SELECTED_SUBTYPE, phoneKeyboard.toPref())
            .putBoolean("separate_settings_per_keyboard", true)
            .commit()
        LayoutPresets.save(real, listOf(home))
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The backup: [restored] on its Layout "Work", which names My numrow holding [keys]. */
    private fun backup(keys: String) {
        val work = JSONObject().put("name", "Work").put("values", JSONObject()
            .put(layoutKey, JSONObject().put("s", numrow))
            .put("layoutText:${type.name}", JSONObject().put("s", keys)))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to restored.toPref(),
                LayoutPresets.PREF to JSONArray(listOf(work)).toString(), idKey(2, LayoutPresets.PREF_SELECTED) to "Work",
                KeyboardIdUpgrade.DONE to true, KeyboardProfiles.NO_COMMON_SET_DONE to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$numrow")); z.write(keys.toByteArray()); z.closeEntry()
        }
    }

    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    private fun preset(name: String) = LayoutPresets.load(real).single { it.name == name }
    private fun restore(names: SavedItemsMerge.Choices = SavedItemsMerge.Choices()) {
        // (again: merged into the keyboard the first restore brought, as it is on the phone)
        val decisions = listedById(real, restored.keyboardId!!)?.let { mapOf(restored to RestoreDecision.Merge(it)) } ?: emptyMap()
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true, names = names, decisions = decisions)
    }

    @Test fun `no clash - the Layout's key layout comes in as it is`() {
        phone("A")
        file(numrow).delete() // (the phone's Home holds it, the file is gone)
        backup("B")
        assertEquals(emptyList(), restorePlanFrom(ctx, zip).clashes)
        restore()
        assertEquals("B", file(numrow).readText())
        assertEquals(numrow, preset("Work").values[layoutKey])
        assertFalse(file(numrow2).exists())
    }

    @Test fun `the same keys under the name - the phone's file, nothing asked`() {
        phone("A")
        file(numrow).writeText("B")
        backup("B")
        assertEquals(emptyList(), restorePlanFrom(ctx, zip).clashes)
        restore()
        assertEquals(setOf(numrow), folder.list()!!.toSet())
        assertTrue(phoneKeyboard in enabled())
    }

    @Test fun `other keys - the Layout's keys take the name, the phone's file is numbered and its users follow`() {
        phone("A")
        file(numrow).writeText("A")
        backup("B")
        // (owner 2026-10-12: no question for the file; the plan has the device's new name)
        val plan = restorePlanFrom(ctx, zip)
        assertEquals(emptyList(), plan.clashes)
        assertEquals(numrow2, plan.items.keyLayouts.deviceRename(type, numrow))
        assertEquals("A", file(numrow).readText()) // (the dry run wrote nothing)
        restore()
        assertEquals("B", file(numrow).readText())
        assertEquals("A", file(numrow2).readText())
        assertEquals(numrow, preset("Work").values[layoutKey])
        // the phone's keyboard and saved Layout on the phone's keys, under their new name
        val renamed = phoneKeyboard.withLayout(type, numrow2).toPref().toSettingsSubtype()
        assertTrue(renamed in enabled(), "${enabled()}")
        assertFalse(phoneKeyboard in enabled())
        assertEquals(renamed, listedById(real, kid(1))) // (its id kept: its set is still its own, nothing moved)
        assertEquals(numrow2, preset("Home").values[layoutKey])

        // again: nothing new
        restore()
        assertEquals(setOf(numrow, numrow2), folder.list()!!.toSet())
        assertEquals(listOf("Home", "Work"), LayoutPresets.load(real).map { it.name })
        assertEquals("B", file(numrow).readText())
    }

    // (owner 2026-10-12: an unticked item doesn't come in; the dialog keeps one a ticked keyboard uses ticked)
    @Test fun `the Layout unticked - it doesn't come in, nothing of the phone's changes`() {
        phone("A")
        file(numrow).writeText("A")
        backup("B")
        restore(SavedItemsMerge.Choices(setOf(Kind.LAYOUTS to "Work")))
        assertEquals(setOf(numrow), folder.list()!!.toSet())
        assertEquals("A", file(numrow).readText())
        assertTrue(phoneKeyboard in enabled())
        assertEquals(numrow, preset("Home").values[layoutKey])
        assertEquals(listOf("Home"), LayoutPresets.load(real).map { it.name })
    }
}

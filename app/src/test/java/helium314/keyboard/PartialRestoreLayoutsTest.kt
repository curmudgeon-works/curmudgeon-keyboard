// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.LayoutPresets
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
import kotlin.test.assertTrue

/**
 * Restoring some keyboards with a custom key layout the phone has under the same name (owner 2026-10-11: "Yes", the
 * pattern of fonts and pictures): the same keys are the phone's file; other keys come in as "My numrow 2" and the
 * restored keyboard, its default for the type and the restored Layouts name that one. The phone's file, and every other
 * keyboard using it, keep their keys (before: the backup's file was written over the phone's).
 */
@RunWith(RobolectricTestRunner::class)
class PartialRestoreLayoutsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val type = LayoutType.NUMBER_ROW
    private val defaultKey = Settings.PREF_LAYOUT_PREFIX + type.name
    private val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
    private val numrow2 = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
    private val zip by lazy { File(ctx.cacheDir, "partial-layouts-${System.nanoTime()}.zip") }
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private val folder get() = file(numrow).parentFile!!

    private val phoneKeyboard = SettingsSubtype(Locale.GERMANY, "").withLayout(type, numrow)
    private val backupKeyboard = SettingsSubtype(Locale.US, "").withLayout(type, numrow)

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        folder.deleteRecursively(); folder.mkdirs()
        phoneKeyboards(phoneKeyboard)
        LayoutUtilsCustom.onLayoutFileChanged()
    }
    /** The phone's keyboards, the first one in use. */
    private fun phoneKeyboards(vararg keyboards: SettingsSubtype) {
        real.edit()
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards.toList()))
            .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards.toList()))
            .putString(Settings.PREF_SELECTED_SUBTYPE, keyboards.first().toPref())
            .commit()
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        zip.delete(); folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The backup: its keyboard picks "My numrow" ([keys]), which is also its number row default and in its Layout "Work". */
    private fun backup(keys: String) {
        val work = JSONObject().put("name", "Work").put("values", JSONObject()
            .put("layout:${type.name}", JSONObject().put("s", numrow))
            .put("layoutText:${type.name}", JSONObject().put("s", keys))
            .put(defaultKey, JSONObject().put("s", numrow)))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to backupKeyboard.toPref(),
                Settings.PREF_ADDITIONAL_SUBTYPES to backupKeyboard.toPref(),
                LayoutPresets.PREF to JSONArray(listOf(work)).toString(), defaultKey to numrow,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/${type.folderName()}/$numrow")); z.write(keys.toByteArray()); z.closeEntry()
        }
    }
    private fun LayoutType.folderName() = "layouts/${name.lowercase()}"

    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    private fun ownDefault(keyboard: SettingsSubtype) = real.getString(KeyboardProfiles.prefixedKey(real, KeyboardProfiles.idFor(real, keyboard), defaultKey), null)

    @Test fun `other keys under a name the phone has come in numbered, and only the restored keyboard uses them`() {
        file(numrow).writeText("A")
        backup("B")
        restoreKeyboardsFrom(ctx, zip, listOf(SettingsSubtype(Locale.US, "").withLayout(type, numrow)), withSettings = true)
        assertEquals("A", file(numrow).readText()) // (the phone's file kept its keys)
        assertEquals("B", file(numrow2).readText())
        assertEquals("My numrow 2", LayoutUtilsCustom.getDisplayName(numrow2))
        val restored = backupKeyboard.withLayout(type, numrow2)
        assertTrue(restored in enabled(), "the restored keyboard uses My numrow 2: ${enabled()}")
        assertTrue(backupKeyboard !in enabled(), "no keyboard of the backup's on the phone's My numrow")
        assertTrue(phoneKeyboard in enabled()) // (the other keyboard still on My numrow, keys A)
        assertEquals(numrow2, ownDefault(restored))
        val preset = LayoutPresets.load(real).single()
        assertEquals(numrow2, preset.values["layout:${type.name}"])
        assertEquals(numrow2, preset.values[defaultKey])

        // again: nothing new
        restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true)
        assertEquals(setOf(numrow, numrow2), folder.list()!!.toSet())
        assertEquals("A", file(numrow).readText())
        assertEquals(listOf(phoneKeyboard, restored), enabled().sortedBy { it.locale.toLanguageTag() })
        assertEquals(1, LayoutPresets.load(real).size)
        assertEquals(numrow2, ownDefault(restored))
    }

    @Test fun `the same keys under the same name are the phone's file`() {
        file(numrow).writeText("A")
        backup("A")
        restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true)
        assertEquals(setOf(numrow), folder.list()!!.toSet())
        assertTrue(backupKeyboard in enabled())
        assertEquals(numrow, ownDefault(backupKeyboard))
        assertEquals(numrow, LayoutPresets.load(real).single().values["layout:${type.name}"])
    }

    @Test fun `the phone's same keyboard is replaced, in its place, and the other keyboard on the name keeps its keys`() {
        file(numrow).writeText("A")
        phoneKeyboards(backupKeyboard, phoneKeyboard)
        backup("B")
        // (owner 2026-10-11: restore matches by language and asks: answered "merge into the phone's", all ticked)
        restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true,
            decisions = mapOf(backupKeyboard to helium314.keyboard.settings.preferences.RestoreDecision.Merge(backupKeyboard)))
        assertEquals("A", file(numrow).readText())
        assertEquals("B", file(numrow2).readText())
        assertEquals(listOf(backupKeyboard.withLayout(type, numrow2), phoneKeyboard), enabled())
    }

    @Test fun `a name the phone doesn't have comes in as it is`() {
        backup("B")
        file(numrow).delete()
        restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true)
        assertEquals("B", file(numrow).readText())
        assertEquals(setOf(numrow), folder.list()!!.toSet())
        assertTrue(backupKeyboard in enabled())
    }

    @Test fun `an unsaved layout with other keys under its name gets a new unsaved one, reused on a second restore`() {
        val private = "custom.~abc."
        file(private).writeText("A")
        phoneKeyboards(phoneKeyboard, SettingsSubtype(Locale.UK, "").withLayout(type, private)) // (a phone keyboard's own)
        val kb = SettingsSubtype(Locale.US, "").withLayout(type, private)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to kb.toPref(), Settings.PREF_ADDITIONAL_SUBTYPES to kb.toPref(),
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/${type.folderName()}/$private")); z.write("B".toByteArray()); z.closeEntry()
        }
        restoreKeyboardsFrom(ctx, zip, listOf(kb), withSettings = true)
        assertEquals("A", file(private).readText())
        val restored = enabled().single { it.locale == Locale.US }
        val name = restored.layoutName(type)!!
        assertTrue(LayoutUtilsCustom.isPrivateLayout(name) && name != private, name)
        assertEquals("B", file(name).readText())
        restoreKeyboardsFrom(ctx, zip, listOf(kb), withSettings = true) // (again: the same keyboard, no new file)
        assertEquals(listOf(restored), enabled().filter { it.locale == Locale.US })
        assertEquals(setOf(private, name), folder.list()!!.toSet())
    }
}

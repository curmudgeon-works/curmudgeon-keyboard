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
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
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

/**
 * (review 2026-10-11 R6) The restore question names the backup's key layouts as the restore does. Owner 2026-10-12 (one
 * plan): the backup's keep their names, and the phone's "My numrow" with other keys gets a name neither the phone nor the
 * backup has, "My numrow 3" (the backup has a "My numrow 2"), planned once and done by the run.
 */
@RunWith(RobolectricTestRunner::class)
class ReReview2R6Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val type = LayoutType.NUMBER_ROW
    private val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
    private val numrow2 = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
    private val zip by lazy { File(ctx.cacheDir, "r6-${System.nanoTime()}.zip") }
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private val folder get() = file(numrow).parentFile!!

    private val phoneKeyboard = SettingsSubtype(Locale.GERMANY, "").withLayout(type, numrow).withId(3)
    private val first = SettingsSubtype(Locale.US, "").withLayout(type, numrow).withId(1)
    private val second = SettingsSubtype(Locale.UK, "").withLayout(type, numrow2).withId(2)

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        folder.deleteRecursively(); folder.mkdirs()
        val phone = SubtypeSettings.createPrefSubtypes(listOf(phoneKeyboard))
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, phone).putString(Settings.PREF_ENABLED_SUBTYPES, phone)
            .putString(Settings.PREF_SELECTED_SUBTYPE, phoneKeyboard.toPref()).commit()
        KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LayoutUtilsCustom.onLayoutFileChanged()
    }
    @After fun tearDown() {
        zip.delete(); folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The backup: [first] on "My numrow" (keys B), [second] on "My numrow 2" (keys C); a backup with ids (owner
     *  2026-10-11: keyboards have unique ids), so the keyboards come in as these. */
    private fun backup() {
        val keyboards = SubtypeSettings.createPrefSubtypes(listOf(first, second))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to keyboards, Settings.PREF_ADDITIONAL_SUBTYPES to keyboards,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true, helium314.keyboard.latin.settings.KeyboardIdUpgrade.DONE to true), z)
            z.closeEntry()
            val dir = "unprotected/layouts/${type.name.lowercase()}"
            z.putNextEntry(ZipEntry("$dir/$numrow")); z.write("B".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("$dir/$numrow2")); z.write("C".toByteArray()); z.closeEntry()
        }
    }

    @Test fun `the dry run numbers the backup's layouts as the restore does, and writes nothing`() {
        file(numrow).writeText("A")
        backup()
        val dry = restorePlanFrom(ctx, zip).incoming.mapValues { it.value.layoutName(type) }
        assertEquals(setOf(numrow), folder.list()!!.toSet()) // (the dry run wrote nothing)

        restoreKeyboardsFrom(ctx, zip, listOf(first, second), withSettings = false)
        val restored = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
        val wet = mapOf(first to restored.single { it.locale == Locale.US }.layoutName(type),
            second to restored.single { it.locale == Locale.UK }.layoutName(type))
        assertEquals(numrow, wet[first])
        assertEquals(numrow2, wet[second])
        assertEquals("B", file(numrow).readText()); assertEquals("C", file(numrow2).readText())
        val numrow3 = LayoutUtilsCustom.getLayoutName("My numrow 3", type)
        assertEquals("A", file(numrow3).readText()) // (the phone's keys, renamed first)
        assertEquals(numrow3, restored.single { it.locale == Locale.GERMANY }.layoutName(type))
        assertEquals(wet, dry)
    }
}

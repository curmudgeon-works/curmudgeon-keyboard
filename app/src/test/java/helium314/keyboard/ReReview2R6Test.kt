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
 * (review 2026-10-11 R6) The restore question's dry run names the backup's key layouts as the restore does: a name it
 * numbered earlier in the same run counts as taken (before: the dry run saw only the files on the phone, so the backup's
 * "My numrow 2" kept its name though the backup's "My numrow" had just come in under it).
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

    private val phoneKeyboard = SettingsSubtype(Locale.GERMANY, "").withLayout(type, numrow)
    private val first = SettingsSubtype(Locale.US, "").withLayout(type, numrow)
    private val second = SettingsSubtype(Locale.UK, "").withLayout(type, numrow2)

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

    /** The backup: [first] on "My numrow" (keys B), [second] on "My numrow 2" (keys C). */
    private fun backup() {
        val keyboards = SubtypeSettings.createPrefSubtypes(listOf(first, second))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to keyboards, Settings.PREF_ADDITIONAL_SUBTYPES to keyboards,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
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
        assertEquals(numrow2, wet[first]) // (My numrow -> My numrow 2)
        assertEquals("C", file(wet[second]!!).readText()) // (My numrow 2 -> a free numbered name, keys C)
        assertEquals("A", file(numrow).readText()); assertEquals("B", file(numrow2).readText())
        assertEquals(wet, dry)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceLooks
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

/**
 * Review SP26 (owner 2026-10-18: "yes, should fix this"): a saved theme can own its picture (a copy in looks/own, made
 * when the picture left the list and the theme kept it). A restore of some keyboards brought the theme but not that
 * copy, so the theme named a missing picture; now the copies the restored themes name come too (only those), one with
 * the same bytes on the device is reused, and one whose name another picture has here lands as "x 2", the theme naming
 * it. (A keyboard's own background pictures came already: KeyboardProfiles.restoreFiles.)
 */
@RunWith(RobolectricTestRunner::class)
class PartialRestoreThemePicturesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val keyboard = SettingsSubtype(Locale.US, "").withId(1)
    private val zip by lazy { File(ctx.cacheDir, "partial-own-${System.nanoTime()}.zip") }
    private val ownDir get() = File(ctx.filesDir, "looks${File.separator}own")
    private val live by lazy { File(ctx.cacheDir, "custom_background_image") } // (a keyboard's background, as applying shows it)

    @Before fun setUp() {
        real.edit().clear().putBoolean("separate_settings_per_keyboard", true).commit(); KeyboardProfiles.loadGroups(real)
        File(ctx.filesDir, "looks").deleteRecursively()
    }
    @After fun tearDown() {
        zip.delete(); live.delete(); File(ctx.cacheDir, "custom_background_image.framing").delete()
        File(ctx.filesDir, "looks").deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    /** The backup: theme "Sunset" owning picture [own] ([bytes]), the keyboard on it; another own picture nobody names. */
    private fun backup(own: String, bytes: String) {
        val pictures = "lib:" + JSONObject().put("custom_background_image", JSONObject().put("t", own))
        val sunset = JSONObject().put("name", "Sunset").put("values", JSONObject()
            .put(Settings.PREF_KEY_HORIZONTAL_GAP, JSONObject().put("f", 3.0))
            .put(AppearanceLooks.PICTURES, JSONObject().put("s", pictures)))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to keyboard.toPref(), KeyboardIdUpgrade.DONE to true,
                AppearanceLooks.PREF to JSONArray(listOf(sunset)).toString(),
                idKey(1, AppearanceLooks.PREF_SELECTED) to "Sunset",
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("looks${File.separator}own${File.separator}$own")); z.write(bytes.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("looks${File.separator}own${File.separator}unused")); z.write("nobody's".toByteArray()); z.closeEntry()
        }
    }

    private fun sunset() = AppearanceLooks.load(real).single { it.name == "Sunset" }
    /** Sunset applied: the picture the keyboard shows. */
    private fun shown(): String {
        AppearanceLooks.applyPictures(ctx, sunset(), listOf(live, File(ctx.cacheDir, "custom_background_image.framing")))
        return live.readText()
    }

    // Fail before: no looks/own file, the theme's picture missing (applying it left the background as it was)
    @Test fun `the theme's own picture comes with it, only the ones a theme names`() {
        backup("abc", "the picture")
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        assertEquals(setOf("abc"), AppearanceLooks.ownPictureNames(sunset()))
        assertEquals("the picture", File(ownDir, "abc").readText())
        assertFalse(File(ownDir, "unused").exists())
        assertEquals("the picture", shown())
    }

    // Fail before: the device's "abc" kept, the theme named it, showing the device's picture
    @Test fun `another picture under its name here - it lands as abc 2, the theme naming it`() {
        ownDir.mkdirs(); File(ownDir, "abc").writeText("the device's")
        backup("abc", "the picture")
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        assertEquals("the device's", File(ownDir, "abc").readText())
        assertEquals("the picture", File(ownDir, "abc 2").readText())
        assertEquals(setOf("abc 2"), AppearanceLooks.ownPictureNames(sunset()))
        assertEquals("the picture", shown())
    }

    // Fail before: the theme named "abc", missing here
    @Test fun `the same bytes here under another name - that one, no copy`() {
        ownDir.mkdirs(); File(ownDir, "xyz").writeText("the picture")
        backup("abc", "the picture")
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        assertEquals(listOf("xyz"), ownDir.list()!!.toList())
        assertEquals(setOf("xyz"), AppearanceLooks.ownPictureNames(sunset()))
        assertEquals("the picture", shown())
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.PictureFraming
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardScopeContext
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceLooks
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review 2026-10-08 M5: a saved theme named its pictures by the saving keyboard's files (custom_background_image_p1),
 *  so on another keyboard none matched and applying it deleted that keyboard's pictures. Since 2026-10-10 the slots
 *  are the plain names; themes saved before (with the _p<id> names) still apply. Keyboard A = set 1, B = set 2. */
@RunWith(RobolectricTestRunner::class)
class ThemePicturesOtherKeyboardTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val a = SettingsSubtype(Locale.US, "")
    private val b = SettingsSubtype(Locale.GERMANY, "")
    private val screenA = KeyboardScopeContext(ctx, 1) // (the screens editing each keyboard)
    private val screenB = KeyboardScopeContext(ctx, 2)

    private fun live(c: Context) = Settings.getCustomBackgroundFile(c, false, false)
    private fun clearPictures() = DeviceProtectedUtils.getFilesDir(ctx).listFiles { f -> f.name.startsWith("custom_background_image") }
        ?.forEach { it.delete() }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        assertEquals(1, KeyboardProfiles.idFor(real, a))
        assertEquals(2, KeyboardProfiles.idFor(real, b))
        real.edit { putBoolean("separate_settings_per_keyboard", true) } // (Appearance per keyboard by default)
        clearPictures()
    }
    @After fun tearDown() {
        clearPictures()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
    }

    @Test fun `a theme saved on one keyboard puts its picture on another and leaves it untweaked`() {
        assertEquals("custom_background_image_p1", live(screenA).name)
        live(screenA).apply { parentFile?.mkdirs(); writeText("picture A") }
        PictureFraming.fileFor(live(screenA)).writeText("framing A")
        val look = AppearanceLooks.Look("Photo", mapOf(AppearanceLooks.PICTURES to AppearanceLooks.savePictures(screenA)))
        live(screenB).writeText("picture B")
        AppearanceLooks.applyPictures(screenB, look)
        assertEquals("picture A", live(screenB).readText())
        assertEquals("framing A", PictureFraming.fileFor(live(screenB)).readText())
        assertFalse(AppearanceLooks.isTweaked(screenB, look))
        assertEquals("picture A", live(screenA).readText()) // A's own untouched
        // and with separate settings off (the shared files): the same theme applies there too
        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        assertEquals("custom_background_image", live(ctx).name)
        AppearanceLooks.applyPictures(ctx, look)
        assertEquals("picture A", live(ctx).readText())
        assertFalse(AppearanceLooks.isTweaked(ctx, look))
    }

    @Test fun `a theme saved before with the saving keyboard's file names still applies on any keyboard`() {
        live(screenA).apply { parentFile?.mkdirs(); writeText("picture A") }
        val libName = helium314.keyboard.latin.common.PictureLibrary.nameOf(ctx, live(screenA))!!
        // as 0.3.010 saved it on keyboard 1
        val old = "lib:" + org.json.JSONObject().put("custom_background_image_p1",
            org.json.JSONObject().put("p", libName).put("f", "framing old")).toString()
        val look = AppearanceLooks.Look("Old photo", mapOf(AppearanceLooks.PICTURES to old))
        live(screenB).writeText("picture B")
        AppearanceLooks.applyPictures(screenB, look)
        assertEquals("picture A", live(screenB).readText())
        assertEquals("framing old", PictureFraming.fileFor(live(screenB)).readText())
        assertFalse(AppearanceLooks.isTweaked(screenB, look))
        live(screenB).writeText("changed")
        assertTrue(AppearanceLooks.isTweaked(screenB, look))
    }

    @Test fun `a picture folder of a theme saved on a keyboard moves to the library under the plain slot names`() {
        val looksDir = File(ctx.filesDir, "looks")
        File(looksDir, "kb-uuid").apply { mkdirs()
            File(this, "custom_background_image_night_p1").writeText("night"); File(this, "custom_background_image_night_p1.framing").writeText("fr") }
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Night", mapOf(AppearanceLooks.PICTURES to "kb-uuid"))))
        AppearanceLooks.migratePictureFolders(ctx, real)
        val id = AppearanceLooks.load(real).single().values[AppearanceLooks.PICTURES] as String
        val slot = org.json.JSONObject(id.removePrefix("lib:")).getJSONObject("custom_background_image_night")
        assertEquals("fr", slot.getString("f"))
        val night = Settings.getCustomBackgroundFile(screenB, true, false)
        AppearanceLooks.applyPictures(screenB, AppearanceLooks.load(real).single())
        assertEquals("night", night.readText())
    }
}

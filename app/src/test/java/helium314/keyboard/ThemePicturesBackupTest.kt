// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.preferences.isSettingsFile
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Re-review item 3 (2026-10-07): a theme saved without a picture must say so (an empty folder is dropped by a backup,
 *  and a missing folder means "leave the background alone"), and a restore of every keyboard with its settings brings
 *  the themes' pictures too. */
@RunWith(RobolectricTestRunner::class)
class ThemePicturesBackupTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val looksDir = File(ctx.filesDir, "looks")

    private fun live() = Settings.getCustomBackgroundFile(ctx, false, false)

    @Test fun `a theme saved without a picture stores no folder`() {
        live().delete()
        val id = AppearanceLooks.savePictures(ctx)
        assertEquals("", id)
        assertTrue(looksDir.listFiles().isNullOrEmpty(), "no folder for a theme without pictures")
        // and applying it clears a picture set since
        live().apply { parentFile?.mkdirs(); writeText("picture") }
        AppearanceLooks.applyPictures(ctx, AppearanceLooks.Look("Plain", mapOf(AppearanceLooks.PICTURES to id)))
        assertFalse(live().exists())
    }

    @Test fun `a theme saved with a picture names it in the library and puts it back`() {
        live().apply { parentFile?.mkdirs(); writeText("picture") }
        helium314.keyboard.latin.common.PictureFraming.fileFor(live()).writeText("framing-text")
        val id = AppearanceLooks.savePictures(ctx)
        assertTrue(id.startsWith("lib:"), id)
        assertTrue(looksDir.listFiles().isNullOrEmpty(), "no folder of copies")
        val libName = org.json.JSONObject(id.removePrefix("lib:")).getJSONObject(live().name).getString("p")
        assertEquals("picture", helium314.keyboard.latin.common.PictureLibrary.file(ctx, libName).readText())
        val look = AppearanceLooks.Look("Photo", mapOf(AppearanceLooks.PICTURES to id))
        assertFalse(AppearanceLooks.isTweaked(ctx, look))
        // the live picture replaced, the theme puts its own back with its framing
        live().writeText("other"); helium314.keyboard.latin.common.PictureFraming.fileFor(live()).delete()
        assertTrue(AppearanceLooks.isTweaked(ctx, look))
        AppearanceLooks.applyPictures(ctx, look)
        assertEquals("picture", live().readText())
        assertEquals("framing-text", helium314.keyboard.latin.common.PictureFraming.fileFor(live()).readText())
        AppearanceLooks.save(ctx.prefs(), listOf(look))
        assertEquals(1, AppearanceLooks.looksUsingPicture(ctx.prefs(), libName))
        // the picture forgotten from the library: applying the theme leaves the slot as it is
        helium314.keyboard.latin.common.PictureLibrary.file(ctx, libName).delete()
        live().writeText("kept")
        AppearanceLooks.applyPictures(ctx, look)
        assertEquals("kept", live().readText())
    }

    @Test fun `themes saved before with a folder of copies are moved to the library at app start`() {
        val prefs = ctx.prefs()
        val empty = File(looksDir, "empty-uuid").apply { mkdirs() }
        val full = File(looksDir, "full-uuid").apply { mkdirs(); File(this, "custom_background_image").writeText("p"); File(this, "custom_background_image.framing").writeText("fr") }
        AppearanceLooks.save(prefs, listOf(
            AppearanceLooks.Look("Plain", mapOf(AppearanceLooks.PICTURES to "empty-uuid")),
            AppearanceLooks.Look("Photo", mapOf(AppearanceLooks.PICTURES to "full-uuid")),
            AppearanceLooks.Look("Old", mapOf(Settings.PREF_THEME_STYLE to "Holo")), // from before pictures were in themes
            AppearanceLooks.Look("Lost", mapOf(AppearanceLooks.PICTURES to "no-such-folder")), // restored without its folder
        ))
        AppearanceLooks.migratePictureFolders(ctx, prefs)
        val looks = AppearanceLooks.load(prefs).associate { it.name to it.values[AppearanceLooks.PICTURES] as? String }
        assertEquals("", looks["Plain"])
        assertTrue(looks["Photo"]!!.startsWith("lib:"), looks["Photo"])
        assertEquals("fr", org.json.JSONObject(looks["Photo"]!!.removePrefix("lib:")).getJSONObject("custom_background_image").getString("f"))
        assertEquals(null, looks["Old"])
        assertEquals("no-such-folder", looks["Lost"])
        assertFalse(empty.exists()); assertFalse(full.exists())
    }

    @Test fun `a restore of every keyboard with its settings takes the themes' pictures`() {
        val sep = File.separator
        assertTrue(isSettingsFile("looks${sep}0f2c4e5a-uuid${sep}custom_background_image"))
        assertTrue(isSettingsFile("looks${sep}0f2c4e5a-uuid${sep}custom_background_image_night.framing"))
        assertTrue(isSettingsFile("pictures${sep}picture_1"))
        assertFalse(isSettingsFile("gesture_corpus.jsonl"))
    }
}

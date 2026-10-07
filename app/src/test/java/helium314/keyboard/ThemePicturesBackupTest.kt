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

    @Test fun `a theme saved with a picture still keeps a copy`() {
        live().apply { parentFile?.mkdirs(); writeText("picture") }
        val id = AppearanceLooks.savePictures(ctx)
        assertTrue(id.isNotEmpty())
        assertEquals("picture", File(looksDir, id + File.separator + live().name).readText())
    }

    @Test fun `themes saved before with an empty folder are cleaned up at app start`() {
        val prefs = ctx.prefs()
        val empty = File(looksDir, "empty-uuid").apply { mkdirs() }
        val full = File(looksDir, "full-uuid").apply { mkdirs(); File(this, "custom_background_image").writeText("p") }
        AppearanceLooks.save(prefs, listOf(
            AppearanceLooks.Look("Plain", mapOf(AppearanceLooks.PICTURES to "empty-uuid")),
            AppearanceLooks.Look("Photo", mapOf(AppearanceLooks.PICTURES to "full-uuid")),
            AppearanceLooks.Look("Old", mapOf(Settings.PREF_THEME_STYLE to "Holo")), // from before pictures were in themes
        ))
        AppearanceLooks.dropEmptyPictureFolders(ctx, prefs)
        val looks = AppearanceLooks.load(prefs).associate { it.name to it.values[AppearanceLooks.PICTURES] }
        assertEquals("", looks["Plain"])
        assertEquals("full-uuid", looks["Photo"])
        assertFalse(looks.containsKey("Old") && looks["Old"] != null)
        assertFalse(empty.exists())
        assertTrue(full.isDirectory)
    }

    @Test fun `a restore of every keyboard with its settings takes the themes' pictures`() {
        val sep = File.separator
        assertTrue(isSettingsFile("looks${sep}0f2c4e5a-uuid${sep}custom_background_image"))
        assertTrue(isSettingsFile("looks${sep}0f2c4e5a-uuid${sep}custom_background_image_night.framing"))
        assertTrue(isSettingsFile("pictures${sep}picture_1"))
        assertFalse(isSettingsFile("gesture_corpus.jsonl"))
    }
}

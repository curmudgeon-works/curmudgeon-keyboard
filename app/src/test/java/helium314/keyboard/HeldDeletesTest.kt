// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.common.PictureLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 2026-10-10 (owner: "so discard should bring the font back"): a font deleted with Appearance open is deleted for good
 *  only when its changes are kept; Discard (or leaving the app, or a crash) brings it back. Review H1: a picture deleted
 *  from "Choose a picture" too, with the saved themes as they were. */
@RunWith(RobolectricTestRunner::class)
class HeldDeletesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val font get() = File(FontLibrary.dir(ctx), "Mine.ttf")
    private val looksDir = File(ctx.filesDir, "looks")
    private val slot get() = AppearanceLooks.slotOf(Settings.getCustomBackgroundFile(ctx, false, false).name)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        // (a keyboard on the phone, the main one: a write for no keyboard of the phone is dropped, 2026-10-12)
        storeKeyboards(ctx, real, listOf(helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "").withId(1)), reload = false)
        FontLibrary.dir(ctx).mkdirs(); font.writeText("not really a font")
        ctx.prefs().edit().putString(Settings.PREF_KEY_FONT, FontLibrary.PREFIX + "Mine.ttf").commit()
    }
    @After fun tearDown() {
        AppearanceDraft.close(); font.delete()
        looksDir.deleteRecursively(); PictureLibrary.dir(ctx).deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    @Test fun `Discard brings a deleted font back, with its choice`() {
        AppearanceDraft.of(ctx) // the Appearance screen opens
        FontLibrary.delete(ctx, "Mine.ttf")
        assertFalse(font.exists()) // out of the font list while the screen is open
        assertEquals("default", ctx.prefs().getString(Settings.PREF_KEY_FONT, null)) // the preview lets go of it
        assertTrue(AppearanceDraft.activeOrNull()!!.hasChanges(ctx))
        AppearanceDraft.rejectOpen(ctx) // the cross, or leaving the app
        assertTrue(font.exists())
        assertEquals(FontLibrary.PREFIX + "Mine.ttf", ctx.prefs().getString(Settings.PREF_KEY_FONT, null))
    }

    @Test fun `Keep deletes the font for good`() {
        val draft = AppearanceDraft.of(ctx)
        FontLibrary.delete(ctx, "Mine.ttf")
        draft.accept(ctx)
        assertFalse(font.exists())
        assertEquals("default", ctx.prefs().getString(Settings.PREF_KEY_FONT, null))
        AppearanceDraft.recoverAfterCrash(ctx) // nothing left to put back
        assertFalse(font.exists())
    }

    @Test fun `a crash with the screen open brings the font back at the next start`() {
        AppearanceDraft.of(ctx)
        FontLibrary.delete(ctx, "Mine.ttf")
        AppearanceDraft.forgetActiveForTest() // the process dies: the draft object is gone, its folder stays
        AppearanceDraft.recoverAfterCrash(ctx)
        assertTrue(font.exists())
        assertEquals(FontLibrary.PREFIX + "Mine.ttf", ctx.prefs().getString(Settings.PREF_KEY_FONT, null))
    }

    @Test fun `without the screen open a delete is at once, as before`() {
        FontLibrary.delete(ctx, "Mine.ttf")
        assertFalse(font.exists())
        assertEquals("default", ctx.prefs().getString(Settings.PREF_KEY_FONT, null))
    }

    // ---- a picture deleted from "Choose a picture" (review H1)

    /** A picture in the list, named by the saved theme "Sunset"; nothing else in the list or the looks folder. */
    private fun pictureInATheme(): File {
        looksDir.deleteRecursively(); PictureLibrary.dir(ctx).deleteRecursively()
        val src = File(ctx.cacheDir, "src_sunset").apply { writeText("sunset") }
        val picture = PictureLibrary.file(ctx, PictureLibrary.nameOf(ctx, src)!!)
        AppearanceLooks.save(ctx.prefs(), listOf(AppearanceLooks.Look("Sunset", mapOf(AppearanceLooks.PICTURES to
            "lib:" + JSONObject().put(slot, JSONObject().put("p", picture.name).put("f", "fr"))))), now = true)
        return picture
    }
    private fun themes() = ctx.prefs().getString(AppearanceLooks.PREF, null)
    private fun ownCopies() = File(looksDir, "own").listFiles()?.toList() ?: emptyList()

    @Test fun `Discard brings a picture deleted from the list back, and the themes naming it`() {
        for (keepInThemes in listOf(true, false)) {
            val picture = pictureInATheme()
            val before = themes()
            AppearanceDraft.of(ctx) // the Appearance screen opens
            assertTrue(AppearanceLooks.deleteFromList(ctx, picture, keepInThemes))
            assertFalse(picture.exists(), "keep in themes: $keepInThemes")
            assertTrue(PictureLibrary.list(ctx).isEmpty(), "out of the list while the screen is open")
            assertNotEquals(before, themes(), "the themes let go of it at once")
            assertTrue(AppearanceDraft.activeOrNull()!!.hasChanges(ctx))
            AppearanceDraft.rejectOpen(ctx) // the cross, or leaving the app
            assertEquals("sunset", picture.readText(), "back in the list (keep in themes: $keepInThemes)")
            assertEquals(listOf(picture), PictureLibrary.list(ctx))
            assertEquals(before, themes())
            // the next app start: the copies the delete made are named by nothing now and go; the picture stays
            AppearanceLooks.pruneOwnedPictures(ctx, real)
            assertTrue(ownCopies().isEmpty())
            assertEquals("sunset", picture.readText())
            assertEquals(listOf("Sunset"), AppearanceLooks.namesUsingPicture(ctx.prefs(), picture.name))
        }
    }

    @Test fun `Keep deletes the picture from the list for good, the themes as changed`() {
        for (keepInThemes in listOf(true, false)) {
            val picture = pictureInATheme()
            val draft = AppearanceDraft.of(ctx)
            assertTrue(AppearanceLooks.deleteFromList(ctx, picture, keepInThemes))
            val changed = themes()
            draft.accept(ctx)
            assertFalse(picture.exists())
            assertTrue(PictureLibrary.list(ctx).isEmpty())
            assertEquals(changed, themes())
            assertTrue(AppearanceLooks.namesUsingPicture(ctx.prefs(), picture.name).isEmpty())
            AppearanceDraft.recoverAfterCrash(ctx) // nothing left to put back
            assertFalse(picture.exists())
            AppearanceLooks.pruneOwnedPictures(ctx, real) // the theme's own copy is named: it stays
            assertEquals(if (keepInThemes) listOf("sunset") else emptyList(), ownCopies().map { it.readText() })
        }
    }

    @Test fun `a crash with the screen open brings the deleted picture back at the next start`() {
        for (keepInThemes in listOf(true, false)) {
            val picture = pictureInATheme()
            val before = themes()
            AppearanceDraft.of(ctx)
            assertTrue(AppearanceLooks.deleteFromList(ctx, picture, keepInThemes))
            AppearanceDraft.forgetActiveForTest() // the process dies: the draft object is gone, its folder stays
            AppearanceDraft.recoverAfterCrash(ctx) // the next app start, in App's order: first this,
            AppearanceLooks.pruneOwnedPictures(ctx, real) // then the own pictures no theme names
            assertEquals("sunset", picture.readText(), "keep in themes: $keepInThemes")
            assertEquals(before, themes())
            assertTrue(ownCopies().isEmpty())
            assertEquals(listOf("Sunset"), AppearanceLooks.namesUsingPicture(ctx.prefs(), picture.name))
        }
    }

    // owner 2026-10-12 B4: a snapshot whose draft_prefs.json can't be read; fail before: the draft folder was deleted
    // before the held files went back, the deleted picture and font lost for good
    @Test fun `an unreadable snapshot still brings the held picture and font back at the next start`() {
        val picture = pictureInATheme()
        AppearanceDraft.of(ctx)
        assertTrue(AppearanceLooks.deleteFromList(ctx, picture, false))
        assertTrue(AppearanceDraft.activeOrNull()!!.holdFontDelete(ctx, "Mine.ttf"))
        assertFalse(picture.exists()); assertFalse(font.exists())
        val dir = File(ctx.filesDir, "appearance_draft")
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, "the lists written through a temporary file, none left")
        AppearanceDraft.forgetActiveForTest() // the process dies
        File(dir, "draft_prefs.json").writeText("{\"set\": ") // (cut off mid-write)
        AppearanceDraft.recoverAfterCrash(ctx)
        assertEquals("sunset", picture.readText(), "the held picture back in its list")
        assertEquals("not really a font", font.readText(), "the held font back")
        assertFalse(dir.exists(), "the folder goes after")
    }
}

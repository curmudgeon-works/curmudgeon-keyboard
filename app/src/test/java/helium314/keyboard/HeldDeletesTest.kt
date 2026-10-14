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
        val dir = File(DeviceProtectedUtils.getFilesDir(ctx), "appearance_draft")
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, "the lists written through a temporary file, none left")
        AppearanceDraft.forgetActiveForTest() // the process dies
        File(dir, "draft_prefs.json").writeText("{\"set\": ") // (cut off mid-write)
        AppearanceDraft.recoverAfterCrash(ctx)
        assertEquals("sunset", picture.readText(), "the held picture back in its list")
        assertEquals("not really a font", font.readText(), "the held font back")
        assertFalse(dir.exists(), "the folder goes after")
    }

    // round-5 review 20261013_L17 (owner: "Yes, drop the list"): the held ones are the files in the held folders, no
    // list of them. Fail before: the process died after the move and before the list was written (here: the list
    // gone), the snapshot came back without the font, and the folder went with the font's file in it
    @Test fun `a held font comes back after a crash from its folder alone`() {
        AppearanceDraft.of(ctx)
        FontLibrary.delete(ctx, "Mine.ttf")
        assertFalse(font.exists())
        val dir = File(DeviceProtectedUtils.getFilesDir(ctx), "appearance_draft")
        File(dir, "held_deletes.json").delete() // (no list, or one the move came before)
        AppearanceDraft.forgetActiveForTest() // the process dies
        AppearanceDraft.recoverAfterCrash(ctx)
        assertEquals("not really a font", font.readText(), "the held font back")
        assertEquals(FontLibrary.PREFIX + "Mine.ttf", ctx.prefs().getString(Settings.PREF_KEY_FONT, null))
        assertFalse(dir.exists())
    }

    // round-6 review 20261013_L5_2, case a (owner 2026-10-13): only a font in the list when the screen opened, and not
    // held already, is held; one added during the visit is deleted at once. Fail before: deleting a font added under the
    // held one's name overwrote the held original, and Discard brought back the added one
    @Test fun `a font added during the visit is deleted at once, the held original stays`() {
        AppearanceDraft.of(ctx) // the Appearance screen opens
        FontLibrary.delete(ctx, "Mine.ttf") // held
        font.writeText("another font") // another one added under the free name
        FontLibrary.delete(ctx, "Mine.ttf")
        assertFalse(font.exists())
        val other = File(FontLibrary.dir(ctx), "Other.ttf").apply { writeText("other") } // a new one
        FontLibrary.delete(ctx, "Other.ttf")
        assertFalse(other.exists())
        val held = File(DeviceProtectedUtils.getFilesDir(ctx), "appearance_draft${File.separator}held_fonts")
        assertEquals(listOf("Mine.ttf"), held.list()!!.toList(), "only the font of the open held")
        assertEquals("not really a font", File(held, "Mine.ttf").readText(), "the held original overwritten")
        AppearanceDraft.rejectOpen(ctx) // Discard
        assertEquals("not really a font", font.readText(), "the original back")
        assertFalse(other.exists())
    }

    // round-6 review 20261013_M1_2 (owner 2026-10-13): the snapshot folder is in the device-protected storage, with the
    // font and picture lists, so a held delete is a rename within one storage (on an encrypted device a rename between
    // the two storages is refused, and the file was deleted at once). Fail before: the folder was in the other storage
    @Test fun `a held delete stays in the storage of the font and picture lists, and Discard puts it back`() {
        val de = DeviceProtectedUtils.getFilesDir(ctx)
        assertNotEquals(ctx.filesDir.absoluteFile, de.absoluteFile) // (two storages here too, as on a device)
        val picture = pictureInATheme()
        AppearanceDraft.of(ctx) // the Appearance screen opens
        assertTrue(AppearanceLooks.deleteFromList(ctx, picture, false))
        FontLibrary.delete(ctx, "Mine.ttf")
        val draftDir = File(de, "appearance_draft")
        val heldFont = File(draftDir, "held_fonts${File.separator}Mine.ttf")
        val heldPicture = File(draftDir, "held_pictures${File.separator}${picture.name}")
        assertEquals("not really a font", heldFont.readText(), "the font held: moved, not deleted")
        assertEquals("sunset", heldPicture.readText(), "the picture held: moved, not deleted")
        assertFalse(File(ctx.filesDir, "appearance_draft").exists(), "a snapshot folder in the other storage")
        // the lists and the snapshot folder: one storage root
        for (dir in listOf(FontLibrary.dir(ctx), PictureLibrary.dir(ctx), draftDir))
            assertTrue(dir.absolutePath.startsWith(de.absolutePath + File.separator), "$dir")
        AppearanceDraft.rejectOpen(ctx) // the cross, or leaving the app
        assertEquals("not really a font", font.readText(), "the font back in its list")
        assertEquals("sunset", picture.readText(), "the picture back in its list")
        assertFalse(draftDir.exists())
    }
}

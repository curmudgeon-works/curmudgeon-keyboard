// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.PictureLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.dialogs.appearanceAlsoUndoes
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 2026-10-12 B5: a picture or font added to its list with Appearance open stayed in the list after Discard,
 * and adding one alone was no change (leaving didn't ask). Now Discard (the cross, or "Discard and exit") removes every
 * font and picture added during the edit, but one something outside the edit uses; the Discard question names them;
 * an addition is a change; leaving the app or a crash keeps them, like the palette editor's saves.
 */
@RunWith(RobolectricTestRunner::class)
class AppearanceAddedFilesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val font get() = File(FontLibrary.dir(ctx), "Added.ttf")
    private val looksDir = File(ctx.filesDir, "looks")

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, listOf(helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "").withId(1)), reload = false)
        FontLibrary.dir(ctx).deleteRecursively(); PictureLibrary.dir(ctx).deleteRecursively(); looksDir.deleteRecursively()
    }
    @After fun tearDown() {
        AppearanceDraft.close()
        FontLibrary.dir(ctx).deleteRecursively(); PictureLibrary.dir(ctx).deleteRecursively(); looksDir.deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    /** A font loaded in a text style dialog and chosen there (OK), as the dialog does it. */
    private fun addFont() {
        val src = File(ctx.cacheDir, "src_font").apply { writeText("a font") }
        val chosen = FontLibrary.commit(ctx, FontLibrary.addPending(ctx, src, "Added.ttf"))
        ctx.prefs().edit().putString(Settings.PREF_KEY_FONT, chosen).commit()
    }

    /** A picture loaded from the gallery: into the list (the keyboard's own copy aside). */
    private fun addPicture(): File {
        val src = File(ctx.cacheDir, "src_picture").apply { writeText("a picture") }
        PictureLibrary.add(ctx, src)
        return PictureLibrary.list(ctx).single()
    }

    @Test fun `Discard removes a picture and a font added during the edit`() {
        val draft = AppearanceDraft.of(ctx) // the Appearance screen opens
        addFont()
        val picture = addPicture()
        assertEquals(listOf("Added.ttf"), draft.addedFonts(ctx))
        assertEquals(listOf(picture.name), draft.addedPictures(ctx))
        draft.reject(ctx) // the cross, or "Discard and exit"
        assertFalse(font.exists(), "the added font is still in the list")
        assertFalse(picture.exists(), "the added picture is still in the list")
        assertEquals(emptyList(), FontLibrary.names(ctx))
        assertEquals(emptyList(), PictureLibrary.list(ctx))
    }

    @Test fun `a picture or font there when the screen opened stays after Discard`() {
        addFont()
        val picture = addPicture()
        val draft = AppearanceDraft.of(ctx)
        ctx.prefs().edit().putString(Settings.PREF_KEY_FONT, "default").commit()
        draft.reject(ctx)
        assertTrue(font.exists()); assertTrue(picture.exists())
    }

    @Test fun `adding a picture alone is a change, a font alone too`() {
        var draft = AppearanceDraft.of(ctx)
        assertFalse(draft.hasChanges(ctx))
        addPicture()
        assertTrue(draft.hasChanges(ctx))
        draft.reject(ctx)
        draft = AppearanceDraft.of(ctx)
        val src = File(ctx.cacheDir, "src_font").apply { writeText("a font") }
        FontLibrary.commit(ctx, FontLibrary.addPending(ctx, src, "Added.ttf")) // (in the list, nothing chosen)
        assertTrue(draft.hasChanges(ctx))
    }

    @Test fun `leaving the app or a crash keeps the added picture and font`() {
        AppearanceDraft.of(ctx)
        addFont()
        val picture = addPicture()
        AppearanceDraft.rejectOpen(ctx) // the app left with the screen open
        assertTrue(font.exists()); assertTrue(picture.exists())
        assertEquals("default", ctx.prefs().getString(Settings.PREF_KEY_FONT, "default"), "the choice itself is undone")

        font.delete(); picture.delete()
        AppearanceDraft.of(ctx)
        addFont()
        val again = addPicture()
        AppearanceDraft.forgetActiveForTest() // the process dies
        AppearanceDraft.recoverAfterCrash(ctx)
        assertTrue(font.exists()); assertTrue(again.exists())
    }

    @Test fun `the Discard question names the added font and the added pictures`() {
        val draft = AppearanceDraft.of(ctx)
        assertEquals(emptyList(), appearanceAlsoUndoes(ctx, draft))
        addFont()
        addPicture()
        assertEquals(listOf(ctx.getString(R.string.discard_also_undoes_font, "Added.ttf"),
            ctx.resources.getQuantityString(R.plurals.discard_also_undoes_pictures, 1, 1)), appearanceAlsoUndoes(ctx, draft))
        assertEquals("added font \"Added.ttf\"", appearanceAlsoUndoes(ctx, draft).first())
    }

    @Test fun `a font something outside the edit uses survives Discard, an unused picture goes`() {
        val draft = AppearanceDraft.of(ctx)
        addFont()
        val picture = addPicture()
        // a stored choice the edit doesn't put back names the font (a plain key, in no keyboard's set: no part of the
        // snapshot, as a use outside the edit is)
        real.edit().putString(Settings.PREF_KEY_FONT, FontLibrary.PREFIX + "Added.ttf").commit()
        draft.reject(ctx)
        assertTrue(font.exists(), "a font still chosen outside the edit went")
        assertEquals(FontLibrary.PREFIX + "Added.ttf", real.getString(Settings.PREF_KEY_FONT, null))
        assertFalse(picture.exists())
    }

    @Test fun `a font deleted and added again under its name - Discard brings the old one back`() {
        val src = File(ctx.cacheDir, "src_old").apply { writeText("the old font") }
        FontLibrary.commit(ctx, FontLibrary.addPending(ctx, src, "Added.ttf"))
        val draft = AppearanceDraft.of(ctx)
        FontLibrary.delete(ctx, "Added.ttf") // held
        addFont() // the new one takes the name
        assertEquals(listOf("Added.ttf"), draft.addedFonts(ctx))
        draft.reject(ctx)
        assertEquals("the old font", font.readText())
    }
}

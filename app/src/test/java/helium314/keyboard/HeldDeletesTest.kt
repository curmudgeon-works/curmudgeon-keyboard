// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceDraft
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-10-10 (owner: "so discard should bring the font back"): a font deleted with Appearance open is deleted for good
 *  only when its changes are kept; Discard (or leaving the app, or a crash) brings it back. */
@RunWith(RobolectricTestRunner::class)
class HeldDeletesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val font get() = File(FontLibrary.dir(ctx), "Mine.ttf")

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        FontLibrary.dir(ctx).mkdirs(); font.writeText("not really a font")
        ctx.prefs().edit().putString(Settings.PREF_KEY_FONT, FontLibrary.PREFIX + "Mine.ttf").commit()
    }
    @After fun tearDown() {
        AppearanceDraft.close(); font.delete()
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
}

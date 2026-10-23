// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review SP19 (owner 2026-10-18: "yes, use numbering without parentheses everywhere. Good to have a standard
 * pattern"): a font loaded under a name another font has gets the next free number, "Mine 2.ttf", as saved Layouts,
 * themes and palettes are numbered ("Warm 2"); a new or imported palette too ("User-Defined 2"). Fonts are loaded as
 * the text style dialog does it: the picked file pending, then OK.
 */
@RunWith(RobolectricTestRunner::class)
class FontNumberingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        FontLibrary.dir(ctx).deleteRecursively()
    }
    @After fun tearDown() {
        FontLibrary.dir(ctx).deleteRecursively()
        File(ctx.cacheDir, "src_font").delete()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    /** A font file with [content] loaded as [name] in a text style dialog, and its OK: the choice stored. */
    private fun load(name: String, content: String): String {
        val src = File(ctx.cacheDir, "src_font").apply { writeText(content) }
        return FontLibrary.commit(ctx, FontLibrary.addPending(ctx, src, name))
    }

    // Fail before: "Mine (2).ttf", "Mine (3).ttf"
    @Test fun `another font under a name in the list - the next free number, no parentheses`() {
        assertEquals(FontLibrary.PREFIX + "Mine.ttf", load("Mine.ttf", "one"))
        assertEquals(FontLibrary.PREFIX + "Mine 2.ttf", load("Mine.ttf", "two"))
        assertEquals(FontLibrary.PREFIX + "Mine 3.ttf", load("Mine.ttf", "three"))
        assertEquals(listOf("Mine 2.ttf", "Mine 3.ttf", "Mine.ttf"), FontLibrary.names(ctx))
        assertEquals("three", File(FontLibrary.dir(ctx), "Mine 3.ttf").readText())
    }

    @Test fun `a name ending in a number counts up, a name without an extension too`() {
        load("Font 2.otf", "one")
        assertEquals(FontLibrary.PREFIX + "Font 3.otf", load("Font 2.otf", "two"))
        load("plain", "one")
        assertEquals(FontLibrary.PREFIX + "plain 2", load("plain", "two"))
    }

    // Fail before: "User-Defined1", "User-Defined2"
    @Test fun `a new palette under a name in the list - the next free number, with a space`() {
        assertEquals("User-Defined", KeyboardTheme.getUnusedThemeName("User-Defined", real))
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "User-Defined", "[]").commit()
        assertEquals("User-Defined 2", KeyboardTheme.getUnusedThemeName("User-Defined", real))
        real.edit().putString(Settings.PREF_USER_ALL_COLORS_PREFIX + "User-Defined 2", "{}").commit()
        assertEquals("User-Defined 3", KeyboardTheme.getUnusedThemeName("User-Defined", real))
    }
}

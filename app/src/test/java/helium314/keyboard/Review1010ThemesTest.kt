// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.PictureFraming
import helium314.keyboard.latin.common.PictureLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.renamePopupSet
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review 2026-10-08 area 4 (themes, palettes, popup sets, pictures) #3..#7. */
@RunWith(RobolectricTestRunner::class)
class Review1010ThemesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun set(id: Int) = ProfilePreferences(real) { id }
    private val colors = Settings.PREF_THEME_COLORS
    private val night = Settings.PREF_THEME_COLORS_NIGHT

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    // ---- #3: palette rename and delete in the shared set reach the keyboards' hidden own sets ----

    // separate settings off; keyboard 1's own set (kept from earlier) names the shared palette "My theme" with no colours
    // of its own, keyboard 2's own set has its own "My theme"
    private fun hiddenSetsNamingMyTheme() {
        real.edit()
            .putString("keyboard_profile_ids", """{"en_US:":1,"de_DE:":2}""")
            .putString(Settings.PREF_USER_COLORS_PREFIX + "My theme", "[]")
            .putString(colors, "My theme")
            .putString("p1/$colors", "My theme").putString("p1/$night", "My theme")
            .putString("p2/$colors", "My theme").putString("p2/${Settings.PREF_USER_COLORS_PREFIX}My theme", "[]")
            .commit()
    }

    // (2026-10-11: delete keeps copies, "other keyboards should keep the popups/palette now called Custom"; before, the
    // hidden set forgot it and went back to the default colours)
    @Test fun `a palette deleted in the shared set stays with a hidden set that read it, as Custom N`() {
        hiddenSetsNamingMyTheme()
        KeyboardTheme.deleteUserColors(ctx, set(1), "My theme") // (separate off: the shared set)
        val kept = real.getString("p1/$colors", null)!!
        assertTrue(KeyboardTheme.isHiddenPalette(kept))
        assertTrue(real.contains(Settings.PREF_USER_COLORS_PREFIX + kept)) // (its colours, under the kept name)
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "My theme")) // (the shared palette's old name is gone)
        // a new palette that takes the free name later is not picked up by keyboard 1 when separate settings come back
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "My theme", "[]").putBoolean("separate_settings_per_keyboard", true).commit()
        assertEquals(kept, set(1).getString(colors, null))
        // keyboard 2's own palette of that name stays its choice
        assertEquals("My theme", real.getString("p2/$colors", null))
    }

    @Test fun `a palette renamed in the shared set is renamed in a hidden set that read it`() {
        hiddenSetsNamingMyTheme()
        assertTrue(KeyboardTheme.renameUserColors("My theme", "Work", set(1)))
        assertEquals("Work", real.getString(colors, null))
        assertEquals("Work", real.getString("p1/$colors", null))
        assertEquals("Work", real.getString("p1/$night", null))
        assertEquals("My theme", real.getString("p2/$colors", null))
    }

    @Test fun `the palette editor's Discard after a rename takes the hidden sets back too`() {
        hiddenSetsNamingMyTheme()
        KeyboardTheme.renameUserColors("My theme", "Work", set(1))
        KeyboardTheme.followInHiddenSets(set(1), "Work", "My theme") // (what the editor's putBack does)
        assertEquals("My theme", real.getString("p1/$colors", null))
        assertEquals("My theme", real.getString("p1/$night", null))
    }

    @Test fun `a palette deleted in a keyboard's own set leaves the other keyboards' choices`() {
        hiddenSetsNamingMyTheme()
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        KeyboardTheme.deleteUserColors(ctx, set(2), "My theme")
        assertEquals("My theme", real.getString("p1/$colors", null))
        assertEquals("My theme", real.getString(colors, null))
    }

    // ---- #4: saved Layouts and themes follow a renamed or deleted name they carry ----

    @Test fun `a saved Layout follows its popup set's rename, so applying it doesn't bring the old set back`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("S", "main", null, mapOf("a" to listOf("b")))))
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(KeyPopupOverrides.PREF_SELECTED_SET to "S"))))
        renamePopupSet(ctx, "S", "T")
        assertEquals(listOf("T"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals("T", LayoutPresets.load(real).single().values[KeyPopupOverrides.PREF_SELECTED_SET])
    }

    private val numberRow = Settings.PREF_LAYOUT_PREFIX + LayoutType.NUMBER_ROW.name
    private fun custom(name: String) = LayoutUtilsCustom.getLayoutName(name, LayoutType.NUMBER_ROW)
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, LayoutType.NUMBER_ROW, ctx)
    @After fun removeLayoutFiles() { file("x").parentFile?.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged() }

    @Test fun `a saved Layout follows a custom default layout's rename and forgets it when deleted`() {
        val x = custom("x"); val y = custom("y"); val z = custom("z")
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(numberRow to x)), LayoutPresets.Preset("Home", mapOf(numberRow to z))))
        SubtypeSettings.onRenameLayout(LayoutType.NUMBER_ROW, x, y, ctx)
        SubtypeSettings.onRenameLayout(LayoutType.NUMBER_ROW, z, null, ctx)
        val presets = LayoutPresets.load(real).associateBy { it.name }
        assertEquals(y, presets["Work"]!!.values[numberRow])
        assertTrue(presets["Home"]!!.values.containsKey(numberRow))
        assertNull(presets["Home"]!!.values[numberRow], "the deleted layout's name comes back with the Layout")
    }

    @Test fun `a saved theme forgets a deleted font, so applying it doesn't name the font again`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Mine", mapOf(Settings.PREF_KEY_FONT to "font:X",
            Settings.PREF_EMOJI_FONT to "font:Y"))))
        FontLibrary.delete(ctx, "X")
        val look = AppearanceLooks.load(real).single()
        assertEquals("default", look.values[Settings.PREF_KEY_FONT])
        assertEquals("font:Y", look.values[Settings.PREF_EMOJI_FONT])
    }

    // ---- #6: the Themes list's Cancel copy of the pictures stays out of the library ----

    private fun live() = Settings.getCustomBackgroundFile(ctx, false, false)
    private fun clearPictures() {
        DeviceProtectedUtils.getFilesDir(ctx).listFiles { f -> f.name.startsWith("custom_background_image") }?.forEach { it.delete() }
        PictureLibrary.dir(ctx).deleteRecursively()
    }
    @After fun removePictures() = clearPictures()

    @Test fun `opening the Themes list doesn't put a forgotten picture back in the library, and Cancel still puts it back`() {
        clearPictures()
        live().apply { parentFile?.mkdirs(); writeText("picture P") }
        PictureFraming.fileFor(live()).writeText("framing P")
        // P was in the library and the user forgot it there; the keyboard keeps its own copy
        assertTrue(PictureLibrary.list(ctx).isEmpty())
        val held = AppearanceLooks.holdPictures(ctx) // the list opens
        assertTrue(PictureLibrary.list(ctx).isEmpty(), "the forgotten picture is back in the library")
        // a theme is previewed (another picture, no framing), then Cancel
        live().writeText("picture Q"); PictureFraming.fileFor(live()).delete()
        val look = AppearanceLooks.Look("", mapOf(AppearanceLooks.PICTURES to held))
        AppearanceLooks.applyPictures(ctx, look)
        assertEquals("picture P", live().readText())
        assertEquals("framing P", PictureFraming.fileFor(live()).readText())
        AppearanceLooks.deletePictures(ctx, look)
        assertFalse(java.io.File(ctx.filesDir, "looks").listFiles().orEmpty().any { it.listFiles().orEmpty().isNotEmpty() })
    }

    @Test fun `Cancel after opening the Themes list with no picture takes a previewed picture off again`() {
        clearPictures()
        val held = AppearanceLooks.holdPictures(ctx)
        live().apply { parentFile?.mkdirs(); writeText("picture Q") }
        AppearanceLooks.applyPictures(ctx, AppearanceLooks.Look("", mapOf(AppearanceLooks.PICTURES to held)))
        assertFalse(live().exists())
    }

    // ---- #7: the old picture folders go only after the looks naming the library are written ----

    // the process dies before an apply() lands: only commit() writes
    private class DiesBeforeApply(private val r: SharedPreferences) : SharedPreferences by r {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by r.edit() { override fun apply() {} }
    }

    @Test fun `a look's old picture folder isn't gone before the look names the library`() {
        clearPictures()
        val folder = java.io.File(ctx.filesDir, "looks/old-uuid").apply { mkdirs() }
        java.io.File(folder, "custom_background_image").writeText("picture O")
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Old", mapOf(AppearanceLooks.PICTURES to "old-uuid"))))
        AppearanceLooks.migratePictureFolders(ctx, DiesBeforeApply(real))
        val pictures = AppearanceLooks.load(real).single().values[AppearanceLooks.PICTURES] as String
        assertTrue(pictures.startsWith("lib:") || folder.isDirectory, "the look names a folder that is gone: its pictures are lost")
        folder.deleteRecursively()
    }
}

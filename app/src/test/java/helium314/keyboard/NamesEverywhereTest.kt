// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
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
import helium314.keyboard.settings.screens.deletePopupSet
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A saved name (Layout, popup set, font, theme, custom key layout) renamed or deleted from one keyboard is followed by
 *  every keyboard's set (2026-10-07). Keyboard A = set 1 (being edited), B = set 2, C = set 3 (nothing of its own). */
@RunWith(RobolectricTestRunner::class)
class NamesEverywhereTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun set(id: Int) = ProfilePreferences(real) { id }
    private fun mark(id: Int, key: String) = KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + key)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        KeyboardProfiles.editingId = 1
        // (the test's context is no activity: its prefs() is the keyboard in use's, the first set made: 1)
        KeyboardProfiles.refreshImeId(real)
        assertEquals(1, KeyboardProfiles.imeId)
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.editingId = KeyboardProfiles.SHARED
        KeyboardProfiles.refreshImeId(real)
    }

    // ---- #4: deleting a Layout, popup set, font or theme ----

    @Test fun `a deleted Layout is forgotten by every keyboard, not only the one being edited`() {
        val key = LayoutPresets.PREF_SELECTED
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("X", mapOf("a" to 1)), LayoutPresets.Preset("Y", mapOf("a" to 2))))
        real.edit().putString(key, "Y").putString("p1/$key", "X").putString("p2/$key", "X").commit()
        LayoutPresets.delete(real, "X") // from A's Layouts list
        assertEquals(listOf("Y"), LayoutPresets.load(real).map { it.name })
        assertFalse(real.contains("p2/$key"))
        assertTrue(real.getBoolean(mark(2, key), false))
        assertNull(set(2).getString(key, null), "B reads the shared Layout instead of none")
        assertEquals("Y", set(3).getString(key, null)) // C chose nothing of its own: the shared one, untouched
        LayoutPresets.save(real, LayoutPresets.load(real) + LayoutPresets.Preset("X", mapOf("a" to 3))) // a new "X" later
        assertNull(set(2).getString(key, null))
        assertNull(set(1).getString(key, null))
    }

    @Test fun `a deleted Layout chosen in the shared set is forgotten by the keyboards reading it`() {
        val key = LayoutPresets.PREF_SELECTED
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("X", mapOf("a" to 1)), LayoutPresets.Preset("Y", mapOf("a" to 2))))
        real.edit().putString(key, "X").putString("p2/$key", "Y").commit()
        LayoutPresets.delete(real, "X")
        assertFalse(real.contains(key))
        assertNull(set(3).getString(key, null))
        assertEquals("Y", set(2).getString(key, null))
    }

    @Test fun `a deleted popup set is forgotten by every keyboard, not only the one being edited`() {
        val key = KeyPopupOverrides.PREF_SELECTED_SET
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("S", "main", null, mapOf("a" to listOf("b"))),
            KeyPopupOverrides.UserSet("T", "main", null, emptyMap())))
        real.edit().putString(key, "T").putString("p1/$key", "S").putString("p2/$key", "S")
            .putString("p1/${KeyPopupOverrides.PREF}", "{\"a\":[\"b\"]}").putString("p2/${KeyPopupOverrides.PREF}", "{\"a\":[\"b\"]}").commit()
        assertTrue(deletePopupSet(ctx, "S")) // from A's popup list: A had it
        assertEquals(listOf("T"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertNull(set(1).getString(KeyPopupOverrides.PREF, null)) // A falls back to the built-in arrangement (as before)
        assertNull(set(1).getString(key, null))
        assertFalse(real.contains("p2/$key"))
        assertTrue(real.getBoolean(mark(2, key), false))
        assertNull(set(2).getString(key, null), "B reads the shared set instead of none")
        assertEquals("T", set(3).getString(key, null))
        KeyPopupOverrides.saveSets(real, KeyPopupOverrides.loadSets(real) + KeyPopupOverrides.UserSet("S", "main", null, emptyMap()))
        assertNull(set(2).getString(key, null))
    }

    @Test fun `a deleted font goes back to the default in every keyboard, not only the one being edited`() {
        real.edit().putString(Settings.PREF_KEY_FONT, "font:Y").putString("p1/${Settings.PREF_KEY_FONT}", "font:X")
            .putString("p2/${Settings.PREF_KEY_FONT}", "font:X").putString("p2/${Settings.PREF_EMOJI_FONT}", "font:X").commit()
        FontLibrary.delete(ctx, "X") // from A's text style dialog
        assertEquals("default", set(1).getString(Settings.PREF_KEY_FONT, null))
        assertEquals("default", set(2).getString(Settings.PREF_KEY_FONT, null), "B kept the deleted font")
        assertEquals("default", set(2).getString(Settings.PREF_EMOJI_FONT, null))
        assertEquals("font:Y", set(3).getString(Settings.PREF_KEY_FONT, null))
    }

    @Test fun `a deleted theme leaves a keyboard that had it at none, not the shared theme`() {
        val key = AppearanceLooks.PREF_SELECTED
        real.edit().putString(key, "Other").putString("p2/$key", "Mine").commit()
        KeyboardProfiles.forgetValueEverywhere(real, key, "Mine")
        assertNull(set(2).getString(key, null))
        assertEquals("Other", set(3).getString(key, null))
    }

    // ---- #5: renaming or deleting a custom key layout (the default for its type) ----

    private val numberRow = Settings.PREF_LAYOUT_PREFIX + LayoutType.NUMBER_ROW.name
    private fun custom(name: String) = LayoutUtilsCustom.getLayoutName(name, LayoutType.NUMBER_ROW)
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, LayoutType.NUMBER_ROW, ctx)
    private fun hasFile(name: String) = LayoutUtilsCustom.getLayoutFiles(LayoutType.NUMBER_ROW, ctx).any { it.name.startsWith(name) }

    @After fun removeLayoutFiles() { file("x").parentFile?.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged() }

    @Test fun `a custom key layout renamed from one keyboard is renamed in every keyboard's set`() {
        val x = custom("x"); val y = custom("y")
        file(x).writeText("1 2 3"); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().putString("p1/$numberRow", x).putString("p2/$numberRow", x).commit()
        // as the layout editor renames it (from A): the old file goes, the names follow, the new file is written
        file(x).delete()
        SubtypeSettings.onRenameLayout(LayoutType.NUMBER_ROW, x, y, ctx)
        file(y).writeText("1 2 3"); LayoutUtilsCustom.onLayoutFileChanged()
        assertEquals(y, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(1)))
        assertEquals(y, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(2)), "B lost the renamed layout")
        assertTrue(hasFile(Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(2))))
    }

    @Test fun `a custom key layout without its file is forgotten in every keyboard's set`() {
        val x = custom("x")
        real.edit().putString(numberRow, x).putString("p2/$numberRow", x).putString("p3/$numberRow", "number_row").commit()
        LayoutUtilsCustom.removeMissingLayouts(ctx) // (A, set 1, reads the shared x)
        assertFalse(real.contains(numberRow))
        assertFalse(real.contains("p2/$numberRow"))
        assertTrue(real.getBoolean(mark(2, numberRow), false))
        val builtIn = Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, ProfilePreferences(real) { 9 })
        assertEquals(builtIn, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(2)))
        assertEquals(builtIn, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(1)))
        assertEquals("number_row", real.getString("p3/$numberRow", null))
        file(x).writeText("4 5 6"); LayoutUtilsCustom.onLayoutFileChanged() // a layout saved later under that name
        assertEquals(builtIn, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(2)))
    }

    @Test fun `a custom key layout only another keyboard names is found missing too`() {
        val x = custom("x")
        real.edit().putString("p2/$numberRow", x).commit()
        LayoutUtilsCustom.removeMissingLayouts(ctx) // A (set 1) names none: before, only A's own was checked
        assertFalse(real.contains("p2/$numberRow"), "B still names a layout without a file")
        assertTrue(real.getBoolean(mark(2, numberRow), false))
    }
}

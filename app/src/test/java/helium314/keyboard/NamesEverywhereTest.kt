// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.currentPopupPreset
import helium314.keyboard.settings.screens.popupPresets
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
 *  every keyboard's set (2026-10-07). Keyboard A = k1 (in use, being edited), B = k2, C = k3, the first in the list:
 *  its own set is the common settings (owner 2026-10-11: no separate common set; the first keyboard's own settings are
 *  the common ones), what the tests called the shared set. (owner 2026-10-11: keyboards have unique ids) Sets are
 *  "pk<n>/", the ids in the definitions; no id map. */
@RunWith(RobolectricTestRunner::class)
class NamesEverywhereTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun set(n: Int) = ProfilePreferences(real) { kid(n) }
    private fun mark(n: Int, key: String) = KeyboardProfiles.ownKey(kid(n), KeyboardProfiles.TOMBSTONE + key)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        // (the test's context is no activity: its prefs() is the keyboard in use's, A's)
        val a = keyboard.withId(1)
        val b = SettingsSubtype(java.util.Locale.FRANCE, "").withId(2)
        val c = SettingsSubtype(java.util.Locale.GERMANY, "").withId(3)
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, c.toPref())
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(c, a, b)))
            .putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref()).commit()
        KeyboardProfiles.refreshImeId(real)
        assertEquals(kid(1), KeyboardProfiles.imeId)
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
    }

    // ---- #4: deleting a Layout, popup set, font or theme ----

    @Test fun `a deleted Layout is forgotten by every keyboard, not only the one being edited`() {
        val key = LayoutPresets.PREF_SELECTED
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("X", mapOf("a" to 1)), LayoutPresets.Preset("Y", mapOf("a" to 2))))
        real.edit().putString("pk3/$key", "Y").putString("pk1/$key", "X").putString("pk2/$key", "X").commit()
        LayoutPresets.delete(real, "X") // from A's Layouts list
        assertEquals(listOf("Y"), LayoutPresets.load(real).map { it.name })
        assertFalse(real.contains("pk2/$key"))
        assertFalse(real.contains(mark(2, key))) // (owner 2026-10-11: no default marks; nothing stored = the default)
        assertNull(set(2).getString(key, null), "B reads the shared Layout instead of none")
        assertEquals("Y", set(3).getString(key, null)) // C's, the common one, untouched
        LayoutPresets.save(real, LayoutPresets.load(real) + LayoutPresets.Preset("X", mapOf("a" to 3))) // a new "X" later
        assertNull(set(2).getString(key, null))
        assertNull(set(1).getString(key, null))
    }

    @Test fun `a deleted Layout chosen in the shared set is forgotten by the keyboards reading it`() {
        val key = LayoutPresets.PREF_SELECTED
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("X", mapOf("a" to 1)), LayoutPresets.Preset("Y", mapOf("a" to 2))))
        real.edit().putString("pk3/$key", "X").putString("pk2/$key", "Y").commit()
        LayoutPresets.delete(real, "X")
        assertFalse(real.contains("pk3/$key"))
        assertNull(set(3).getString(key, null))
        assertEquals("Y", set(2).getString(key, null))
    }

    // (popup sets: S = {"a": ["b"]}, T = none of its own; the presets row as the Layout & Typing screen names it)
    private val keyboard = SettingsSubtype(java.util.Locale.US, "")
    private val popups = "{\"a\":[\"b\"]}"
    private fun saveSetsST() = KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("S", "main", null, mapOf("a" to listOf("b"))),
        KeyPopupOverrides.UserSet("T", "main", null, emptyMap())))
    private fun row(prefs: SharedPreferences) =
        currentPopupPreset(prefs, keyboard, popupPresets(keyboard, KeyPopupOverrides.loadSets(real)), KeyPopupOverrides.load(prefs))

    @Test fun `a deleted popup set is forgotten by every keyboard, and each keeps its popups shown as Custom`() {
        val key = KeyPopupOverrides.PREF_SELECTED_SET
        saveSetsST()
        real.edit().putString("pk3/$key", "T").putString("pk1/$key", "S").putString("pk2/$key", "S")
            .putString("pk1/${KeyPopupOverrides.PREF}", popups).putString("pk2/${KeyPopupOverrides.PREF}", popups).commit()
        assertEquals("S", row(set(1))?.userName)
        deletePopupSet(ctx, "S") // from A's popup list: A had it
        assertEquals(listOf("T"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals(popups, set(1).getString(KeyPopupOverrides.PREF, null), "A lost its popups")
        assertEquals(popups, set(2).getString(KeyPopupOverrides.PREF, null))
        assertNull(set(1).getString(key, null))
        assertFalse(real.contains("pk2/$key"))
        assertFalse(real.contains(mark(2, key))) // (owner 2026-10-11: no default marks; nothing stored = the default)
        assertNull(set(2).getString(key, null), "B reads the shared set instead of none")
        assertEquals("T", set(3).getString(key, null))
        assertNull(row(set(1)), "A's row names ${row(set(1))?.name}, not Custom")
        assertNull(row(set(2)), "B's row names ${row(set(2))?.name}, not Custom")
        KeyPopupOverrides.saveSets(real, KeyPopupOverrides.loadSets(real) + KeyPopupOverrides.UserSet("S", "main", null, emptyMap()))
        assertNull(set(2).getString(key, null))
    }

    @Test fun `a keyboard with no popups of its own and no set still shows the built-in it matches`() {
        assertEquals(R.string.key_popups_preset_heliboard_packed, row(set(3))?.name) // the defaults: the HeliBoard extra set
        real.edit().putString("pk3/${KeyPopupOverrides.PREF}", popups).commit()
        assertNull(row(set(3))) // its own popups: Custom
    }

    @Test fun `a deleted popup set leaves the shared popups with separate settings off, and a hidden set's too`() {
        val key = KeyPopupOverrides.PREF_SELECTED_SET
        val hidden = "{\"c\":[\"d\"]}"
        saveSetsST()
        real.edit().putBoolean("separate_settings_per_keyboard", false)
            .putString("pk3/$key", "S").putString("pk3/${KeyPopupOverrides.PREF}", popups)
            .putString("pk2/$key", "S").putString("pk2/${KeyPopupOverrides.PREF}", hidden).commit() // B's hidden set
        deletePopupSet(ctx, "S") // every keyboard reads the shared set
        assertEquals(popups, real.getString("pk3/${KeyPopupOverrides.PREF}", null), "every keyboard lost its popups")
        assertFalse(real.contains("pk3/$key"))
        assertEquals(hidden, real.getString("pk2/${KeyPopupOverrides.PREF}", null))
        assertFalse(real.contains("pk2/$key"))
        assertNull(row(ctx.prefs()))
        assertNull(row(set(1)))
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit() // B's set shown again: its popups, Custom
        assertEquals(hidden, set(2).getString(KeyPopupOverrides.PREF, null))
        assertNull(row(set(2)))
    }

    @Test fun `a deleted popup set leaves the shared popups with the Layout menu shared`() {
        val key = KeyPopupOverrides.PREF_SELECTED_SET
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.LAYOUT, true)
        saveSetsST()
        real.edit().putString("pk3/$key", "S").putString("pk3/${KeyPopupOverrides.PREF}", popups).commit()
        deletePopupSet(ctx, "S")
        assertEquals(popups, set(1).getString(KeyPopupOverrides.PREF, null), "A lost its popups")
        assertEquals(popups, set(2).getString(KeyPopupOverrides.PREF, null), "B lost its popups")
        assertNull(row(set(1)))
        assertNull(row(set(2)))
    }

    @Test fun `Discard on Layout and Typing after a popup set delete puts back the list and the names`() {
        val key = KeyPopupOverrides.PREF_SELECTED_SET
        saveSetsST()
        real.edit().putString("pk3/$key", "T").putString("pk1/$key", "S").putString("pk2/$key", "S")
            .putString("pk1/${KeyPopupOverrides.PREF}", popups).putString("pk2/${KeyPopupOverrides.PREF}", popups).commit()
        val draft = LayoutDraft.of(ctx, keyboard.withId(1).toPref()) // (A's)
        try {
            deletePopupSet(ctx, "S")
            assertTrue(draft.changedKeys(ctx).isNotEmpty())
            draft.reject(ctx) // Discard
        } finally { LayoutDraft.close() }
        assertEquals(listOf("S", "T"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals("S", set(1).getString(key, null))
        assertEquals("S", set(2).getString(key, null))
        assertFalse(real.contains(mark(2, key)))
        assertEquals("T", set(3).getString(key, null))
        assertEquals(popups, set(1).getString(KeyPopupOverrides.PREF, null))
        assertEquals(popups, set(2).getString(KeyPopupOverrides.PREF, null))
        assertEquals("S", row(set(1))?.userName)
    }

    @Test fun `a deleted font goes back to the default in every keyboard, not only the one being edited`() {
        real.edit().putString("pk3/${Settings.PREF_KEY_FONT}", "font:Y").putString("pk1/${Settings.PREF_KEY_FONT}", "font:X")
            .putString("pk2/${Settings.PREF_KEY_FONT}", "font:X").putString("pk2/${Settings.PREF_EMOJI_FONT}", "font:X").commit()
        FontLibrary.delete(ctx, "X") // from A's text style dialog
        assertEquals("default", set(1).getString(Settings.PREF_KEY_FONT, null))
        assertEquals("default", set(2).getString(Settings.PREF_KEY_FONT, null), "B kept the deleted font")
        assertEquals("default", set(2).getString(Settings.PREF_EMOJI_FONT, null))
        assertEquals("font:Y", set(3).getString(Settings.PREF_KEY_FONT, null))
    }

    @Test fun `a deleted theme leaves a keyboard that had it at none, not the shared theme`() {
        val key = AppearanceLooks.PREF_SELECTED
        real.edit().putString("pk3/$key", "Other").putString("pk2/$key", "Mine").commit()

        KeyboardProfiles.replaceValueEverywhere(real, key, "Mine", null)
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
        real.edit().putString("pk1/$numberRow", x).putString("pk2/$numberRow", x).commit()
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
        real.edit().putString(numberRow, x).putString("pk2/$numberRow", x).putString("pk3/$numberRow", "number_row").commit()
        LayoutUtilsCustom.removeMissingLayouts(ctx) // (A, k1, reads the shared x)
        assertFalse(real.contains(numberRow))
        assertFalse(real.contains("pk2/$numberRow"))
        assertFalse(real.contains(mark(2, numberRow))) // (owner 2026-10-11: no default marks; nothing stored = the default)
        val builtIn = Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, ProfilePreferences(real) { kid(9) })
        assertEquals(builtIn, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(2)))
        assertEquals(builtIn, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(1)))
        assertEquals("number_row", real.getString("pk3/$numberRow", null))
        file(x).writeText("4 5 6"); LayoutUtilsCustom.onLayoutFileChanged() // a layout saved later under that name
        assertEquals(builtIn, Settings.readDefaultLayoutName(LayoutType.NUMBER_ROW, set(2)))
    }

    @Test fun `a custom key layout only another keyboard names is found missing too`() {
        val x = custom("x")
        real.edit().putString("pk2/$numberRow", x).commit()
        LayoutUtilsCustom.removeMissingLayouts(ctx) // A (k1) names none: before, only A's own was checked
        assertFalse(real.contains("pk2/$numberRow"), "B still names a layout without a file")
        assertFalse(real.contains(mark(2, numberRow))) // (owner 2026-10-11: no default marks; nothing stored = the default)
    }
}

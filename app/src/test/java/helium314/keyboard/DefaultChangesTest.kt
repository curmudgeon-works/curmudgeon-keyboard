// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.DefaultChange
import helium314.keyboard.latin.protectSavedItems
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A default changes (owner 2026-10-11: "saved items should treat their defaults as if they were picked"): saved
 *  Layouts, themes and popup sets keep the old value, and so do the keyboards that chose them; the rest follows. */
@RunWith(RobolectricTestRunner::class)
class DefaultChangesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val longPress = Settings.PREF_KEY_LONGPRESS_TIMEOUT
    private val row = DefaultChange(longPress, 300, "test")
    private fun stored(id: Int, key: String = longPress): Any? = real.all[KeyboardProfiles.ownKey(id, key)]
    private fun reads(id: Int): Int? = ProfilePreferences(real) { id }.getInt(longPress, -1).takeIf { it != -1 } // (null: the default)

    /** Separate settings; keyboard 1 chose the saved Layout "Work" (long press at its default), keyboard 2 none,
     *  keyboard 3 chose Work and picked 200 ms itself. */
    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).put("c", 3).toString())
            .putString(KeyboardProfiles.ownKey(1, LayoutPresets.PREF_SELECTED), "Work")
            .putBoolean(KeyboardProfiles.ownKey(2, KeyboardProfiles.TOMBSTONE + LayoutPresets.PREF_SELECTED), true)
            .putString(KeyboardProfiles.ownKey(3, LayoutPresets.PREF_SELECTED), "Work")
            .putInt(KeyboardProfiles.ownKey(3, longPress), 200)
            .commit()
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(longPress to null)),
            LayoutPresets.Preset("Slow", mapOf(longPress to 500))))
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    @Test fun `a saved Layout holding the default gets the old value, one with a value keeps it`() {
        protectSavedItems(real, freshInstall = false, rows = listOf(row))
        val presets = LayoutPresets.load(real).associateBy { it.name }
        assertEquals(300, presets.getValue("Work").values[longPress])
        assertEquals(500, presets.getValue("Slow").values[longPress])
    }

    @Test fun `a keyboard that chose it keeps what it showed, one that chose none follows the default, a pick stays`() {
        protectSavedItems(real, freshInstall = false, rows = listOf(row))
        assertEquals(300, stored(1))
        assertNull(stored(2))
        assertEquals(200, stored(3))
    }

    @Test fun `the shared set that chose it gets it, a keyboard reading the shared set too, one that chose none stays at the default`() {
        real.edit().putString(LayoutPresets.PREF_SELECTED, "Work")
            .remove(KeyboardProfiles.ownKey(1, LayoutPresets.PREF_SELECTED)) // (keyboard 1 reads the shared choice)
            .commit()
        protectSavedItems(real, freshInstall = false, rows = listOf(row))
        assertEquals(300, real.all[longPress])
        assertNull(stored(1)) // nothing of its own: it reads the shared 300
        assertEquals(300, reads(1))
        assertTrue(real.contains(KeyboardProfiles.ownKey(2, KeyboardProfiles.TOMBSTONE + longPress))) // marked at the default
        assertNull(reads(2))
    }

    @Test fun `a saved theme holding the default gets the old value, and the keyboard on it`() {
        val gap = Settings.PREF_KEY_HORIZONTAL_GAP
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to null))), now = true)
        real.edit().putString(KeyboardProfiles.ownKey(2, AppearanceLooks.PREF_SELECTED), "Sunset").commit()
        protectSavedItems(real, freshInstall = false, rows = listOf(DefaultChange(gap, 0.5f, "test")))
        assertEquals(0.5f, AppearanceLooks.load(real).single().values[gap])
        assertEquals(0.5f, stored(2, gap))
        assertNull(stored(1, gap)) // (keyboard 1 is on no theme)
    }

    @Test fun `a popup set without a map gets the old map, and the keyboard on it`() {
        val map = Settings.PREF_SYMBOL_POPUP_MAP
        real.edit().putString(KeyPopupOverrides.PREF_SETS, """[{"name":"Mine","morePopups":"all","symbolsLayout":"","overrides":{}}]""")
            .putString(KeyboardProfiles.ownKey(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").commit()
        protectSavedItems(real, freshInstall = false, rows = listOf(DefaultChange(map, "q! w?", "test")))
        assertEquals("q! w?", KeyPopupOverrides.loadSets(real).single().symbolMap)
        assertEquals("q! w?", stored(2, map))
    }

    @Test fun `it runs once, and a fresh install only notes it`() {
        protectSavedItems(real, freshInstall = false, rows = listOf(row))
        real.edit().remove(KeyboardProfiles.ownKey(1, longPress)).commit() // (set back to the default since)
        protectSavedItems(real, freshInstall = false, rows = listOf(row))
        assertNull(stored(1))
        real.edit().remove(row.flag).commit()
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(longPress to null))))
        protectSavedItems(real, freshInstall = true, rows = listOf(row))
        assertNull(LayoutPresets.load(real).single().values[longPress])
        assertTrue(real.getBoolean(row.flag, false))
        assertFalse(KeyboardProfiles.isOneTimeFlag(KeyboardProfiles.ownKey(1, row.flag)))
        assertTrue(KeyboardProfiles.isOneTimeFlag(row.flag)) // (kept by a factory reset, never copied into a set)
    }
}

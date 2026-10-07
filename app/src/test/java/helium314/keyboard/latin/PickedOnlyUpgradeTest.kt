// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardProfiles.TOMBSTONE
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SettingDefaults
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.3.008: a stored setting is one you picked; the ones written down at their default (now or before 0.3.008) go, once. */
@RunWith(RobolectricTestRunner::class)
class PickedOnlyUpgradeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences
    private val popups = Settings.PREF_MORE_POPUP_KEYS
    private val map = Settings.PREF_SYMBOL_POPUP_MAP
    private val color = Settings.PREF_SUGGESTION_TEXT_COLOR
    private val symbolAction = Settings.PREF_LONG_PRESS_SYMBOL_ACTION
    private val numpad = Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun mark(id: Int, key: String) = KeyboardProfiles.ownKey(id, TOMBSTONE + key)

    @Before fun setUp() {
        prefs = ctx.getSharedPreferences("picked_only_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    }

    @Test fun `a new install only notes it ran`() {
        prefs.edit { putString(popups, "all") }
        pickedOnlyUpgrade(prefs, freshInstall = true)
        assertEquals("all", prefs.getString(popups, null))
        assertTrue(prefs.getBoolean(PICKED_ONLY_DONE, false))
    }

    @Test fun `shared settings at the old or the current default go, picks stay`() {
        prefs.edit {
            putString(popups, "all") // the default before 0.3.008
            putString(map, Defaults.CURMUDGEON_SYMBOL_POPUP_MAP)
            putInt(color, Defaults.PREF_SUGGESTION_TEXT_COLOR) // orange
            putString(symbolAction, "none")
            putBoolean(Settings.PREF_SHOW_NUMBER_ROW, SettingDefaults.of(Settings.PREF_SHOW_NUMBER_ROW) as Boolean) // the default now
        }
        pickedOnlyUpgrade(prefs, freshInstall = false)
        for (key in listOf(popups, map, color, symbolAction, Settings.PREF_SHOW_NUMBER_ROW)) assertFalse(prefs.contains(key), key)

        val picked = ctx.getSharedPreferences("picked_only_test_b_${System.nanoTime()}", Context.MODE_PRIVATE)
        picked.edit { putString(popups, "more"); putInt(color, 0xFF336699.toInt()); putString(symbolAction, "emoji") }
        pickedOnlyUpgrade(picked, freshInstall = false)
        assertEquals("more", picked.getString(popups, null))
        assertEquals(0xFF336699.toInt(), picked.getInt(color, 0))
        assertEquals("emoji", picked.getString(symbolAction, null))
    }

    @Test fun `every keyboard's own set, in use or not, gets a reset mark instead`() {
        prefs.edit {
            putString("keyboard_profile_ids", """{"en_US:":1,"hi_ZZ:":2}""")
            putBoolean("separate_settings_per_keyboard", false) // the sets are kept in the background
            putString(popups, "more") // the shared set: a pick
            putString(own(1, popups), "all") // keyboard 1: at the old default
            putString(own(2, popups), "main") // keyboard 2: at the default now
            putString(own(2, map), "q~") // a pick
            putBoolean(mark(2, color), true) // already reads the default
        }
        pickedOnlyUpgrade(prefs, freshInstall = false)
        assertEquals("more", prefs.getString(popups, null))
        assertFalse(prefs.contains(own(1, popups)))
        assertTrue(prefs.getBoolean(mark(1, popups), false))
        assertFalse(prefs.contains(own(2, popups)))
        assertTrue(prefs.getBoolean(mark(2, popups), false))
        assertEquals("q~", prefs.getString(own(2, map), null))
        assertTrue(prefs.getBoolean(mark(2, color), false))
    }

    @Test fun `long-press ?123 stays where the old numpad switch is on`() {
        prefs.edit {
            putString("keyboard_profile_ids", """{"en_US:":1,"hi_ZZ:":2}""")
            putBoolean(numpad, true) // shared: on
            putString(symbolAction, "none")
            putBoolean(own(1, numpad), false) // keyboard 1: off
            putString(own(1, symbolAction), "none")
            putBoolean(mark(2, numpad), true) // keyboard 2: at its default (off)
            putString(own(2, symbolAction), "none")
        }
        pickedOnlyUpgrade(prefs, freshInstall = false)
        assertEquals("none", prefs.getString(symbolAction, null)) // unset, it would read the numpad
        assertFalse(prefs.contains(own(1, symbolAction)))
        assertFalse(prefs.contains(own(2, symbolAction)))
    }

    @Test fun `once only, so a later pick of the old default stays`() {
        pickedOnlyUpgrade(prefs, freshInstall = false)
        prefs.edit { putString(popups, "all") }
        pickedOnlyUpgrade(prefs, freshInstall = false)
        assertEquals("all", prefs.getString(popups, null))
    }

    @Test fun `saved themes and Layouts lose their written-out defaults`() {
        val borders = Settings.PREF_THEME_KEY_BORDERS
        AppearanceLooks.save(prefs, listOf(AppearanceLooks.Look("Mine", mapOf(borders to SettingDefaults.of(borders),
            Settings.PREF_THEME_STYLE to "Rounded"))))
        LayoutPresets.save(prefs, listOf(LayoutPresets.Preset("Mine", mapOf(popups to "all", map to "q~"))))
        pickedOnlyUpgrade(prefs, freshInstall = false)
        val look = AppearanceLooks.load(prefs).single().values
        assertTrue(look.containsKey(borders))
        assertNull(look[borders])
        assertEquals("Rounded", look[Settings.PREF_THEME_STYLE])
        val layout = LayoutPresets.load(prefs).single().values
        assertTrue(layout.containsKey(popups))
        assertNull(layout[popups])
        assertEquals("q~", layout[map])
    }

    @Test fun `a keyboard restored from an old backup keeps only its picks`() {
        assertEquals(mapOf<String, Any?>(map to "q~"), withoutDefaults(mapOf(popups to "all", map to "q~", symbolAction to "none")))
        assertEquals(mapOf<String, Any?>(numpad to true, symbolAction to "none"), withoutDefaults(mapOf(numpad to true, symbolAction to "none")))
    }

    @Test fun `the older look and key sounds follow the new defaults too, saved themes and Layouts keep theirs`() {
        val black = helium314.keyboard.keyboard.KeyboardTheme.THEME_BLACK
        prefs.edit {
            putString("keyboard_profile_ids", """{"en_US:":1}""")
            putString(Settings.PREF_THEME_COLORS, black); putString(Settings.PREF_THEME_COLORS_NIGHT, black)
            putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
            putBoolean(Settings.PREF_VIBRATE_ON, true); putBoolean(Settings.PREF_SOUND_ON, false) // sound off: a pick (not the old default)
            putString(AppearanceLooks.PREF_SELECTED, "Midnight")
            putString(own(1, Settings.PREF_THEME_COLORS), black)
            putString(own(1, AppearanceLooks.PREF_SELECTED), "Midnight")
        }
        AppearanceLooks.save(prefs, listOf(AppearanceLooks.Look("Mine", mapOf(Settings.PREF_THEME_COLORS to black))))
        LayoutPresets.save(prefs, listOf(LayoutPresets.Preset("Mine", mapOf(Settings.PREF_VIBRATE_ON to true))))
        pickedOnlyUpgrade(prefs, freshInstall = false)
        for (key in listOf(Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT, Settings.PREF_THEME_DAY_NIGHT,
                Settings.PREF_VIBRATE_ON, AppearanceLooks.PREF_SELECTED)) assertFalse(prefs.contains(key), key)
        assertEquals(false, prefs.getBoolean(Settings.PREF_SOUND_ON, true))
        assertFalse(prefs.contains(own(1, Settings.PREF_THEME_COLORS)))
        assertTrue(prefs.getBoolean(mark(1, Settings.PREF_THEME_COLORS), false))
        assertFalse(prefs.contains(own(1, AppearanceLooks.PREF_SELECTED)))
        assertTrue(prefs.getBoolean(mark(1, AppearanceLooks.PREF_SELECTED), false))
        assertEquals(black, AppearanceLooks.load(prefs).single().values[Settings.PREF_THEME_COLORS])
        assertEquals(true, LayoutPresets.load(prefs).single().values[Settings.PREF_VIBRATE_ON])
    }

    @Test fun `undo 20, no emoji key and a 100 percent bottom row follow the Curmudgeon Layout's defaults`() {
        val bottom = helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, 0, 2)
        prefs.edit { putInt(Settings.PREF_UNDO_HISTORY_LENGTH, 20); putBoolean(Settings.PREF_SHOW_EMOJI_KEY, false); putFloat(bottom, 1f) }
        pickedOnlyUpgrade(prefs, freshInstall = false)
        for (key in listOf(Settings.PREF_UNDO_HISTORY_LENGTH, Settings.PREF_SHOW_EMOJI_KEY, bottom)) assertFalse(prefs.contains(key), key)
    }

    @Test fun `a theme saves what isn't picked as not set`() {
        val values = AppearanceLooks.snapshot(prefs)
        assertTrue(values.containsKey(Settings.PREF_THEME_KEY_BORDERS))
        assertNull(values[Settings.PREF_THEME_KEY_BORDERS])
    }
}

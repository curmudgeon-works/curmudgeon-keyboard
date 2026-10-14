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
            putInt(color, 0xFFFF8C00.toInt()) // the fixed orange of 0.3.007 (the constant itself moved on 2026-10-07)
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

    // (owner 2026-10-11: no default marks; nothing stored = the default)
    @Test fun `every keyboard's own set, in use or not, is unset too - no mark`() {
        prefs.edit {
            putString("keyboard_profile_ids", """{"en_US:":1,"hi_ZZ:":2}""")
            putBoolean("separate_settings_per_keyboard", false) // the sets are kept in the background
            putString(popups, "more") // the shared set: a pick
            putString(own(1, popups), "all") // keyboard 1: at the old default
            putString(own(2, popups), Defaults.PREF_MORE_POPUP_KEYS) // keyboard 2: at the default now ("all" since 2026-10-07; "main" is a pick again)
            putString(own(2, map), "q~") // a pick
        }
        pickedOnlyUpgrade(prefs, freshInstall = false)
        assertEquals("more", prefs.getString(popups, null))
        assertFalse(prefs.contains(own(1, popups)))
        assertFalse(prefs.contains(mark(1, popups)))
        assertFalse(prefs.contains(own(2, popups)))
        assertFalse(prefs.contains(mark(2, popups)))
        assertEquals("q~", prefs.getString(own(2, map), null))
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
        assertFalse(prefs.contains(mark(1, Settings.PREF_THEME_COLORS))) // (owner 2026-10-11: no default marks; nothing stored = the default)
        assertFalse(prefs.contains(own(1, AppearanceLooks.PREF_SELECTED)))
        assertFalse(prefs.contains(mark(1, AppearanceLooks.PREF_SELECTED)))
        assertEquals(black, AppearanceLooks.load(prefs).single().values[Settings.PREF_THEME_COLORS])
        assertEquals(true, LayoutPresets.load(prefs).single().values[Settings.PREF_VIBRATE_ON])
    }

    @Test fun `undo 20, no emoji key and a 100 percent bottom row follow the Curmudgeon Layout's defaults`() {
        val bottom = helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, 0, 2)
        prefs.edit { putInt(Settings.PREF_UNDO_HISTORY_LENGTH, 20); putBoolean(Settings.PREF_SHOW_EMOJI_KEY, false); putFloat(bottom, 1f) }
        pickedOnlyUpgrade(prefs, freshInstall = false)
        for (key in listOf(Settings.PREF_UNDO_HISTORY_LENGTH, Settings.PREF_SHOW_EMOJI_KEY, bottom)) assertFalse(prefs.contains(key), key)
    }

    // review 2026-10-08 M4: a 0.3.007 phone updating straight to now (or restoring a backup from then) kept HeliBoard's
    // long press, backspace and bottom padding, the defaults written down before 0.3.008 (2026-10-10)
    @Test fun `long press 300, no backspace speed-up and portrait bottom padding 100 percent follow the defaults from 0_3_007`() {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        fun padding(i: Int) = helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, i, 2)
        val old = mapOf<String, Any>(Settings.PREF_KEY_LONGPRESS_TIMEOUT to 300, Settings.PREF_BACKSPACE_SPEED_UP to false,
            padding(0) to 1f, padding(1) to 0f, padding(2) to 1f, padding(3) to 0f)
        fun store(e: SharedPreferences.Editor, key: String, value: Any) =
            if (value is Int) e.putInt(key, value) else if (value is Boolean) e.putBoolean(key, value) else e.putFloat(key, value as Float)
        try {
            // a 0.3.007 phone: every default written down, in the shared set and in keyboard 2's own
            real.edit().clear().commit()
            real.edit {
                putInt(Settings.PREF_VERSION_CODE, 3007)
                putString("keyboard_profile_ids", """{"en_US:":2}""")
                for ((key, value) in old) { store(this, key, value); store(this, own(2, key), value) }
            }
            checkVersionUpgrade(ctx)
            for (key in old.keys) {
                assertFalse(real.contains(key), key)
                assertFalse(real.contains(own(2, key)), "p2/$key")
            }
            for (key in listOf(Settings.PREF_KEY_LONGPRESS_TIMEOUT, Settings.PREF_BACKSPACE_SPEED_UP, padding(0), padding(2)))
                assertFalse(real.contains(mark(2, key)), "p2/~$key") // (owner 2026-10-11: no default marks; nothing stored = the default)

            // a phone that went through 0.3.008: 300 since then is a pick, and stays
            real.edit().clear().commit()
            real.edit { putInt(Settings.PREF_VERSION_CODE, 3008); putBoolean(PICKED_ONLY_DONE, true); for ((key, value) in old) store(this, key, value) }
            checkVersionUpgrade(ctx)
            // (in the first keyboard's set now, which had none: owner 2026-10-11, no separate common set; the first
            // keyboard's own settings are the common ones)
            val first = helium314.keyboard.latin.settings.ProfilePreferences(real) { KeyboardProfiles.SHARED }.all
            for ((key, value) in old.filterKeys { it != padding(1) && it != padding(3) }) assertEquals(value, first[key], key)
        } finally { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
        // one keyboard restored on its own from a 0.3.007 backup
        val keyboard = helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "")
        val restored = helium314.keyboard.settings.preferences.restoredSettings(ctx,
            old.mapKeys { "p1/${it.key}" } + ("keyboard_profile_ids" to """{"${keyboard.toPref()}":1}"""), keyboard)

        for (key in old.keys) assertNull(restored[key], key)
    }

    @Test fun `a theme saves what isn't picked as not set`() {
        val values = AppearanceLooks.snapshot(prefs)
        assertTrue(values.containsKey(Settings.PREF_THEME_KEY_BORDERS))
        assertNull(values[Settings.PREF_THEME_KEY_BORDERS])
    }

    @Test fun `key gaps at the defaults before 0_3_006 follow the defaults, picked ones and saved themes keep theirs - nothing written down`() {
        val gap = Settings.PREF_KEY_HORIZONTAL_GAP
        val vGap = Settings.PREF_KEY_VERTICAL_GAP
        // a phone (or backup) from 0.3.005: the old defaults written down, a keyboard's own set too; a saved theme
        prefs.edit { putFloat(gap, 0.5f); putFloat(vGap, 0.75f); putFloat(own(1, gap), 0.5f) }
        AppearanceLooks.save(prefs, listOf(AppearanceLooks.Look("Mine", mapOf(gap to 0.5f))), now = true)
        curmudgeonUpgrades(prefs, freshInstall = false)
        pickedOnlyUpgrade(prefs, freshInstall = false)
        assertFalse(prefs.contains(gap)); assertFalse(prefs.contains(vGap)); assertFalse(prefs.contains(own(1, gap)))
        assertFalse(prefs.contains(mark(1, gap))) // (owner 2026-10-11: no default marks; nothing stored = the default)
        assertEquals(0.5f, AppearanceLooks.load(prefs).single().values[gap])
        // from 0.3.006 on (its flag), 0.5 is a pick
        val later = ctx.getSharedPreferences("picked_only_test_gaps_${System.nanoTime()}", Context.MODE_PRIVATE)
        later.edit { putBoolean(KEY_GAP_DEFAULTS_DONE, true); putFloat(gap, 0.5f) }
        pickedOnlyUpgrade(later, freshInstall = false)
        assertEquals(0.5f, later.getFloat(gap, 0f))
        // a phone from before 0.3.002 with nothing stored: no step writes the gaps, vibration or sound down any more
        val older = ctx.getSharedPreferences("picked_only_test_old_${System.nanoTime()}", Context.MODE_PRIVATE)
        curmudgeonUpgrades(older, freshInstall = false)
        for (key in listOf(gap, vGap, Settings.PREF_VIBRATE_ON, Settings.PREF_SOUND_ON, Settings.PREF_THEME_DAY_NIGHT, "look_default_midnight"))
            assertFalse(older.contains(key), key)
    }
}

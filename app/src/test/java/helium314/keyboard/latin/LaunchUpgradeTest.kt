// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.AllColors
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.suggestionTextColor
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.EnumMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 0.3.008's new defaults reach new installs only: an update keeps what it had, in every keyboard's own set too. */
@RunWith(RobolectricTestRunner::class)
class LaunchUpgradeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = ctx.getSharedPreferences("launch_upgrade_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    }

    @Test fun `a new install gets the new defaults`() {
        curmudgeonUpgrades(prefs, freshInstall = true)
        assertNull(prefs.all[Settings.PREF_MORE_POPUP_KEYS])
        assertNull(prefs.all[Settings.PREF_SUGGESTION_TEXT_COLOR])
        assertNull(prefs.all[Settings.PREF_LONG_PRESS_SYMBOL_ACTION])
    }

    @Test fun `an update keeps the old ones`() {
        curmudgeonUpgrades(prefs, freshInstall = false)
        assertEquals("all", prefs.getString(Settings.PREF_MORE_POPUP_KEYS, null))
        assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, prefs.getString(Settings.PREF_SYMBOL_POPUP_MAP, null))
        assertEquals(Defaults.PREF_SUGGESTION_TEXT_COLOR, prefs.getInt(Settings.PREF_SUGGESTION_TEXT_COLOR, 0))
        assertEquals("none", prefs.getString(Settings.PREF_LONG_PRESS_SYMBOL_ACTION, null))
    }

    @Test fun `an update keeps what was chosen, and the numpad switch`() {
        prefs.edit {
            putString(Settings.PREF_MORE_POPUP_KEYS, "more")
            putBoolean(Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD, true)
        }
        curmudgeonUpgrades(prefs, freshInstall = false)
        assertEquals("more", prefs.getString(Settings.PREF_MORE_POPUP_KEYS, null))
        assertNull(prefs.all[Settings.PREF_LONG_PRESS_SYMBOL_ACTION])
    }

    @Test fun `a keyboard's own set that read the default keeps the old default`() {
        fun k(id: Int, key: String) = KeyboardProfiles.prefixedKey(id, key)
        prefs.edit {
            putString("keyboard_profile_ids", """{"en_US:":1,"hi_ZZ:":2}""")
            // keyboard 1 read the default (its mark); keyboard 2 has its own value; neither has the colour
            putBoolean(k(1, KeyboardProfiles.TOMBSTONE + Settings.PREF_MORE_POPUP_KEYS), true)
            putBoolean(k(1, KeyboardProfiles.TOMBSTONE + Settings.PREF_SUGGESTION_TEXT_COLOR), true)
            putString(k(2, Settings.PREF_MORE_POPUP_KEYS), "main")
        }
        ownSetUpgrades(prefs, freshInstall = false)
        assertEquals("all", prefs.getString(k(1, Settings.PREF_MORE_POPUP_KEYS), null))
        assertFalse(prefs.contains(k(1, KeyboardProfiles.TOMBSTONE + Settings.PREF_MORE_POPUP_KEYS)))
        assertEquals(Defaults.PREF_SUGGESTION_TEXT_COLOR, prefs.getInt(k(1, Settings.PREF_SUGGESTION_TEXT_COLOR), 0))
        assertEquals("main", prefs.getString(k(2, Settings.PREF_MORE_POPUP_KEYS), null))
        // no mark: it reads the shared value, which the shared step keeps
        assertFalse(prefs.contains(k(2, Settings.PREF_SUGGESTION_TEXT_COLOR)))
    }

    @Test fun `the suggestion colour follows the swipe trail unless one was picked`() {
        val trail = 0xFF336699.toInt()
        val colors = AllColors(EnumMap<ColorType, Int>(ColorType::class.java).apply { put(ColorType.GESTURE_TRAIL, trail) },
            "test", false, null)
        assertEquals(trail, suggestionTextColor(prefs, colors))
        assertEquals(Defaults.PREF_SUGGESTION_TEXT_COLOR, suggestionTextColor(prefs, null))
        prefs.edit { putInt(Settings.PREF_SUGGESTION_TEXT_COLOR, Defaults.PREF_SUGGESTION_TEXT_COLOR) }
        assertEquals(Defaults.PREF_SUGGESTION_TEXT_COLOR, suggestionTextColor(prefs, colors))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.AllColors
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.gestureTrailColor
import helium314.keyboard.latin.utils.suggestionTextColor
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.SettingDefaults
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.EnumMap
import kotlin.test.Test
import kotlin.test.assertEquals

/** The suggestion strip's colour (0.3.008): the swipe trail's unless one was picked. */
@RunWith(RobolectricTestRunner::class)
class SuggestionColorTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = ctx.getSharedPreferences("suggestion_color_test_${System.nanoTime()}", Context.MODE_PRIVATE)
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

    private fun themeWithTrail(trail: Int) = AllColors(EnumMap<ColorType, Int>(ColorType::class.java).apply { put(ColorType.GESTURE_TRAIL, trail) },
        "test", false, null)

    /** The swipe trail colour row (Appearance, 2026-10-07): picked for this keyboard, else the theme's. */
    @Test fun `the swipe trail colour is the one picked, else the theme's, else the orange`() {
        val trail = 0xFF336699.toInt()
        val picked = 0xFF00AA55.toInt()
        assertEquals(trail, gestureTrailColor(prefs, themeWithTrail(trail)))
        assertEquals(Defaults.PREF_GESTURE_TRAIL_COLOR, gestureTrailColor(prefs, null))
        prefs.edit { putInt(Settings.PREF_GESTURE_TRAIL_COLOR, picked) }
        assertEquals(picked, gestureTrailColor(prefs, themeWithTrail(trail)))
        assertEquals(picked, gestureTrailColor(prefs, null))
    }

    @Test fun `the suggestion colour follows the picked swipe trail colour before the theme's`() {
        val trail = 0xFF336699.toInt()
        val picked = 0xFF00AA55.toInt()
        prefs.edit { putInt(Settings.PREF_GESTURE_TRAIL_COLOR, picked) }
        assertEquals(picked, suggestionTextColor(prefs, themeWithTrail(trail)))
        prefs.edit { putInt(Settings.PREF_SUGGESTION_TEXT_COLOR, trail) }
        assertEquals(trail, suggestionTextColor(prefs, themeWithTrail(trail)))
    }

    /** Not set means the theme's colour: a theme or a backup must not write the fallback down; a saved theme carries it. */
    @Test fun `the swipe trail colour's absence matters and themes save it`() {
        assertNull(SettingDefaults.of(Settings.PREF_GESTURE_TRAIL_COLOR))
        assertTrue(Settings.PREF_GESTURE_TRAIL_COLOR in AppearanceLooks.keys)
    }
}

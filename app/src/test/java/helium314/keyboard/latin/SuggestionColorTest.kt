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
import helium314.keyboard.latin.utils.suggestionTextColor
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
}

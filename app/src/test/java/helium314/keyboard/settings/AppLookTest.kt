// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.AppLook
import helium314.keyboard.latin.withoutDefaults
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings app's look (2026-10-07): black and orange only under the Black with orange theme. */
@RunWith(RobolectricTestRunner::class)
class AppLookTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = ctx.getSharedPreferences("app_look_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    }

    @Test fun `the app is black and orange only when the colours in use are Black with orange`() {
        // nothing chosen: Dynamic
        assertFalse(AppLook.usesCurmudgeon(prefs, dark = true))
        assertFalse(AppLook.usesCurmudgeon(prefs, dark = false))
        // dark mode follows the phone: the dark colours count in the dark, the light ones in the light
        prefs.edit {
            putBoolean(Settings.PREF_THEME_DAY_NIGHT, true)
            putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_CURMUDGEON)
        }
        assertTrue(AppLook.usesCurmudgeon(prefs, dark = true))
        assertFalse(AppLook.usesCurmudgeon(prefs, dark = false))
        // not following the phone: the one colours setting counts, light or dark
        prefs.edit {
            putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
            putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_CURMUDGEON)
        }
        assertTrue(AppLook.usesCurmudgeon(prefs, dark = true))
        assertTrue(AppLook.usesCurmudgeon(prefs, dark = false))
    }

    @Test fun `one orange feeds the trail, the strip and the app`() {
        assertEquals(0xFFF5963A.toInt(), Defaults.CURMUDGEON_ORANGE)
        assertEquals(Defaults.CURMUDGEON_ORANGE, Defaults.PREF_SUGGESTION_TEXT_COLOR)
        assertEquals(Defaults.CURMUDGEON_ORANGE, Defaults.PREF_GESTURE_TRAIL_COLOR)
        assertEquals(Defaults.CURMUDGEON_ORANGE, AppLook.orange)
    }

    @Test fun `the old fixed orange still counts as the strip colour's old default on upgrade`() {
        assertTrue(withoutDefaults(mapOf(Settings.PREF_SUGGESTION_TEXT_COLOR to 0xFFFF8C00.toInt())).isEmpty())
        assertEquals(1, withoutDefaults(mapOf(Settings.PREF_SUGGESTION_TEXT_COLOR to 0xFF336699.toInt())).size)
    }
}

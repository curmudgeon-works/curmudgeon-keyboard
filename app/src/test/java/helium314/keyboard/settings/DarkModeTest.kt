// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Light or dark (2026-10-06): the phone's mode when following it, else light; a settings preview over both. */
@RunWith(RobolectricTestRunner::class)
class DarkModeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = ctx.getSharedPreferences("dark_mode_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    }

    @After fun tearDown() { SettingsActivity.forceNight = null }

    @Test fun `following the phone, the phone decides`() {
        prefs.edit { putBoolean(Settings.PREF_THEME_DAY_NIGHT, true) }
        assertFalse(KeyboardTheme.nightFor(phoneNight = false, prefs))
        assertTrue(KeyboardTheme.nightFor(phoneNight = true, prefs))
    }

    @Test fun `not following, the keyboard is light`() {
        prefs.edit { putBoolean(Settings.PREF_THEME_DAY_NIGHT, false) }
        assertFalse(KeyboardTheme.nightFor(phoneNight = true, prefs))
    }

    @Test fun `a preview in the settings shows over both`() {
        prefs.edit { putBoolean(Settings.PREF_THEME_DAY_NIGHT, true) }
        SettingsActivity.forceNight = true
        assertTrue(KeyboardTheme.isNight(ctx, prefs))
        SettingsActivity.forceNight = false
        assertFalse(KeyboardTheme.isNight(ctx, prefs))
    }

    // decision 2026-10-07: on a phone without wallpaper colours (Android 10, 11), Dynamic shows Midnight, not a white keyboard
    @Test fun `Dynamic falls back to Midnight below Android 12`() {
        assertEquals(KeyboardTheme.THEME_BLACK, KeyboardTheme.dynamicFallback(android.os.Build.VERSION_CODES.R))
        assertEquals(KeyboardTheme.THEME_BLACK, KeyboardTheme.dynamicFallback(android.os.Build.VERSION_CODES.Q))
        assertEquals(KeyboardTheme.THEME_DYNAMIC, KeyboardTheme.dynamicFallback(android.os.Build.VERSION_CODES.S))
    }

    @Test fun `the Dynamic theme follows the phone`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val dynamic = AppearanceLooks.builtIn(ctx).single { it.name == ctx.getString(R.string.theme_preset_dynamic) }
        assertEquals(true, dynamic.values[Settings.PREF_THEME_DAY_NIGHT])
    }
}

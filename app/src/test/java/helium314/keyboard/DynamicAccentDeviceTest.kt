// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.gestureTrailColor
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.suggestionTextColor
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owner 2026-10-14 (second thought): "let's just make them dynamic, instead of orange, for now". On the Dynamic colors
 * with no accent picked, the swipe trail, the suggestion words and the enter key take the device's accent again; a
 * picked color still wins.
 */
@RunWith(RobolectricTestRunner::class)
class DynamicAccentDeviceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, listOf(helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "").withId(1)), reload = false)
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun raw(name: String) = KeyboardTheme.getThemeColors(name, ctx.prefs().getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)!!,
        ctx, ctx.prefs(), KeyboardTheme.isNight(ctx))

    @Test fun `Dynamic colors with no accent picked - trail, suggestions and enter key are the device's accent`() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, "the Dynamic colors need Android 12")
        val prefs = ctx.prefs()
        assertEquals(KeyboardTheme.THEME_DYNAMIC, prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS))
        assertFalse(prefs.contains(Settings.PREF_GESTURE_TRAIL_COLOR))
        val colors = KeyboardTheme.getColorsForCurrentTheme(ctx)
        val device = raw(KeyboardTheme.THEME_DYNAMIC)
        assertEquals(device.get(ColorType.GESTURE_TRAIL), gestureTrailColor(prefs, colors))
        assertEquals(device.get(ColorType.GESTURE_TRAIL), suggestionTextColor(prefs, colors))
        assertEquals(device.get(ColorType.ACTION_KEY_BACKGROUND), colors.get(ColorType.ACTION_KEY_BACKGROUND))
        assertFalse(prefs.contains(Settings.PREF_GESTURE_TRAIL_COLOR), "nothing stored")
    }

    @Test fun `a picked accent still wins on the Dynamic colors`() {
        val prefs = ctx.prefs()
        val picked = 0xFF22AA66.toInt()
        prefs.edit { putInt(Settings.PREF_GESTURE_TRAIL_COLOR, picked) }
        val colors = KeyboardTheme.getColorsForCurrentTheme(ctx)
        assertEquals(picked, gestureTrailColor(prefs, colors))
        assertEquals(picked, suggestionTextColor(prefs, colors))
        assertEquals(picked, colors.get(ColorType.ACTION_KEY_BACKGROUND))
    }
}

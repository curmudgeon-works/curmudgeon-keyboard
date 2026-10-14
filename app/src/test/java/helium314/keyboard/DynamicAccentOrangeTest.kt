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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Owner 2026-10-14: "the accent color in appearance (swipe trail, suggestions, enter key) should ship as our orange --
 * only that". On the Dynamic colors with no accent picked, the three are the orange, the rest stays the device's;
 * other themes keep their own accent; a picked color wins; nothing is stored.
 */
@RunWith(RobolectricTestRunner::class)
class DynamicAccentOrangeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val orange = Defaults.PREF_GESTURE_TRAIL_COLOR

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, listOf(helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "").withId(1)), reload = false)
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun theme(name: String) = ctx.prefs().edit { putString(Settings.PREF_THEME_COLORS, name); putString(Settings.PREF_THEME_COLORS_NIGHT, name) }
    private fun raw(name: String) = KeyboardTheme.getThemeColors(name, ctx.prefs().getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)!!,
        ctx, ctx.prefs(), KeyboardTheme.isNight(ctx))

    @Test fun `Dynamic colors with no accent picked - trail, suggestions and enter key are the orange, the rest the device's`() {
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, "the Dynamic colors need Android 12")
        val prefs = ctx.prefs()
        assertEquals(KeyboardTheme.THEME_DYNAMIC, prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS))
        assertFalse(prefs.contains(Settings.PREF_GESTURE_TRAIL_COLOR))
        val colors = KeyboardTheme.getColorsForCurrentTheme(ctx)
        assertEquals(orange, gestureTrailColor(prefs, colors))
        assertEquals(orange, suggestionTextColor(prefs, colors))
        assertEquals(orange, colors.get(ColorType.ACTION_KEY_BACKGROUND))
        val device = raw(KeyboardTheme.THEME_DYNAMIC)
        for (type in listOf(ColorType.KEY_BACKGROUND, ColorType.FUNCTIONAL_KEY_BACKGROUND, ColorType.MAIN_BACKGROUND,
                ColorType.KEY_TEXT, ColorType.SHIFT_KEY_ICON))
            assertEquals(device.get(type), colors.get(type), "$type stays the device's")
        assertFalse(prefs.contains(Settings.PREF_GESTURE_TRAIL_COLOR), "nothing stored")
        assertFalse(real.contains(Settings.PREF_GESTURE_TRAIL_COLOR), "nothing stored")
    }

    @Test fun `another theme keeps its own accent`() {
        theme(KeyboardTheme.THEME_DARK)
        val prefs = ctx.prefs()
        val own = raw(KeyboardTheme.THEME_DARK)
        assertNotEquals(orange, own.get(ColorType.GESTURE_TRAIL))
        val colors = KeyboardTheme.getColorsForCurrentTheme(ctx)
        assertEquals(own.get(ColorType.GESTURE_TRAIL), gestureTrailColor(prefs, colors))
        assertEquals(own.get(ColorType.GESTURE_TRAIL), suggestionTextColor(prefs, colors))
        assertEquals(own.get(ColorType.ACTION_KEY_BACKGROUND), colors.get(ColorType.ACTION_KEY_BACKGROUND))
    }

    @Test fun `a picked accent wins over the orange on the Dynamic colors`() {
        val prefs = ctx.prefs()
        val picked = 0xFF22AA66.toInt()
        prefs.edit { putInt(Settings.PREF_GESTURE_TRAIL_COLOR, picked) }
        val colors = KeyboardTheme.getColorsForCurrentTheme(ctx)
        assertEquals(picked, gestureTrailColor(prefs, colors))
        assertEquals(picked, suggestionTextColor(prefs, colors))
        assertEquals(picked, colors.get(ColorType.ACTION_KEY_BACKGROUND))
        // Default (the key removed) brings the orange back
        prefs.edit { remove(Settings.PREF_GESTURE_TRAIL_COLOR) }
        assertEquals(orange, gestureTrailColor(prefs, KeyboardTheme.getColorsForCurrentTheme(ctx)))
    }
}

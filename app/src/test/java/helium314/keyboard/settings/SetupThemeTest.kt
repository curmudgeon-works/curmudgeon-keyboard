// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.storeKeyboards
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The setup's "Pick a theme" (owner 2026-10-14): Curmudgeon goes on every keyboard of the device, day and night colors
 *  both; Dynamic left picked writes nothing (stored = picked); Dynamic again after Curmudgeon puts back what was there. */
@RunWith(RobolectricTestRunner::class)
class SetupThemeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val us = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first().toSettingsSubtype().withId(1)
    private val de = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(2)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, listOf(us, de)) // (one set of settings: separate settings off, as a fresh install)
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun stored(n: Int, key: String) = real.all[idKey(n, key)]

    @Test fun `left on Dynamic, nothing is written`() {
        val before = real.all.toMap()
        assertFalse(SetupTheme.isCurmudgeon(ctx))
        SetupTheme.before(ctx) // (taken only on a pick; reading writes nothing either)
        assertEquals(before, real.all.toMap())
    }

    @Test fun `Curmudgeon goes on every keyboard, day and night`() {
        SetupTheme.pickCurmudgeon(ctx)
        for (n in 1..2) {
            assertEquals(KeyboardTheme.THEME_CURMUDGEON, stored(n, Settings.PREF_THEME_COLORS), "keyboard $n day")
            assertEquals(KeyboardTheme.THEME_CURMUDGEON, stored(n, Settings.PREF_THEME_COLORS_NIGHT), "keyboard $n night")
            assertEquals(ctx.getString(R.string.theme_preset_curmudgeon), stored(n, AppearanceLooks.PREF_SELECTED))
        }
        assertTrue(SetupTheme.isCurmudgeon(ctx))
        assertEquals(KeyboardTheme.THEME_CURMUDGEON, ctx.prefs().getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS))
    }

    @Test fun `Dynamic again after Curmudgeon puts back what each keyboard had`() {
        real.edit { putInt(idKey(2, Settings.PREF_SUGGESTION_WORD_PADDING), 7) }
        val before = real.all.toMap()
        val saved = SetupTheme.before(ctx)
        SetupTheme.pickCurmudgeon(ctx)
        SetupTheme.pickDynamic(ctx, saved)
        assertEquals(before, real.all.toMap())
        assertFalse(SetupTheme.isCurmudgeon(ctx))
    }

    @Test fun `a keyboard that came with Curmudgeon gets the Dynamic theme when Dynamic is picked`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // (no Dynamic, no theme pick)
        SetupTheme.pickCurmudgeon(ctx)
        SetupTheme.pickDynamic(ctx, null)
        assertEquals(KeyboardTheme.THEME_DYNAMIC, stored(1, Settings.PREF_THEME_COLORS))
        assertFalse(SetupTheme.isCurmudgeon(ctx))
    }

    @Test fun `the pictures are drawn in each theme's own colors`() {
        val curmudgeon = SetupTheme.colors(ctx, KeyboardTheme.THEME_CURMUDGEON)
        assertEquals(Defaults.CURMUDGEON_ORANGE, curmudgeon.get(ColorType.GESTURE_TRAIL))
        val dynamic = SetupTheme.colors(ctx, KeyboardTheme.THEME_DYNAMIC)
        val raw = KeyboardTheme.getThemeColors(KeyboardTheme.THEME_DYNAMIC, KeyboardTheme.STYLE_MATERIAL, ctx, ctx.prefs(), false)
        assertEquals(raw.get(ColorType.MAIN_BACKGROUND), dynamic.get(ColorType.MAIN_BACKGROUND))
        assertEquals(raw.get(ColorType.GESTURE_TRAIL), dynamic.get(ColorType.GESTURE_TRAIL))
    }
}

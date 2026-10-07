// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A built-in theme sets all of the Theme and Emoji groups (2026-10-06); Dynamic is the default look. */
@RunWith(RobolectricTestRunner::class)
class BuiltInThemesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `every built-in theme names every theme setting`() {
        for (look in AppearanceLooks.builtIn(ctx))
            for (key in AppearanceLooks.keys) assertTrue(look.values.containsKey(key), "${look.name} leaves $key as it was")
        assertTrue(Settings.PREF_EMOJI_MAX_SDK in AppearanceLooks.keys)
        assertTrue(Settings.PREF_KEY_HORIZONTAL_GAP in AppearanceLooks.keys)
    }

    @Test fun `Dynamic comes first and is the default look`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val dynamic = AppearanceLooks.builtIn(ctx).first()
        assertEquals(ctx.getString(R.string.theme_preset_dynamic), dynamic.name)
        assertEquals(KeyboardTheme.THEME_DYNAMIC, dynamic.values[Settings.PREF_THEME_COLORS])
        assertEquals(Defaults.PREF_THEME_COLORS, dynamic.values[Settings.PREF_THEME_COLORS])
        assertEquals(Defaults.PREF_THEME_COLORS_NIGHT, dynamic.values[Settings.PREF_THEME_COLORS_NIGHT])
        assertEquals(null, dynamic.values[Settings.PREF_KEY_HORIZONTAL_GAP]) // the default gaps
    }

    @Test fun `Holo classic keeps its own gaps and bold keys`() {
        val holo = AppearanceLooks.builtIn(ctx).single { it.name == ctx.getString(R.string.theme_preset_holo) }
        assertEquals(0f, holo.values[Settings.PREF_KEY_HORIZONTAL_GAP])
        assertEquals(0.75f, holo.values[Settings.PREF_KEY_VERTICAL_GAP])
        assertEquals(true, holo.values[Settings.PREF_KEY_TEXT_BOLD])
    }

    @Test fun `not set means the default the settings show`() {
        // gaps and bold no longer read their absence as "the key style decides"
        assertEquals(Defaults.PREF_KEY_HORIZONTAL_GAP, SettingDefaults.of(Settings.PREF_KEY_HORIZONTAL_GAP))
        assertEquals(Defaults.PREF_KEY_VERTICAL_GAP, SettingDefaults.of(Settings.PREF_KEY_VERTICAL_GAP))
        assertEquals(Defaults.PREF_KEY_TEXT_BOLD, SettingDefaults.of(Settings.PREF_KEY_TEXT_BOLD))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.RawPrefSnapshot
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The settings batch of 2026-10-07 afternoon: palettes (add seeded from the selected scheme, delete that sticks), the
 *  accent colour, the Curmudgeon Layout's defaults, and two review findings on the last commits (Cancel writing a
 *  shared value into a keyboard's own set; one slot for the keyboard-switch callback). */
@RunWith(RobolectricTestRunner::class)
class Batch1007Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.editingId = KeyboardProfiles.SHARED
    }

    // ---- palettes ----

    private fun palette(name: String) = ctx.prefs().all.keys.filter { it.startsWith("user_") && it.endsWith(name) }

    // decided 2026-10-07: a delete takes the palette away at once; the screen's cross or Discard brings it back, the tick keeps it gone
    @Test fun `a deleted palette goes at once and comes back with the cross`() {
        val prefs = ctx.prefs()
        val name = "Mine"
        KeyboardTheme.writeUserColors(prefs, name, KeyboardTheme.seedUserColors(ctx, prefs, KeyboardTheme.THEME_CURMUDGEON, false))
        prefs.edit { putString(Settings.PREF_THEME_COLORS, name); putString(Settings.PREF_THEME_COLORS_NIGHT, name) }
        AppearanceDraft.of(ctx) // the Appearance screen opens: its snapshot holds the palette
        KeyboardTheme.deleteUserColors(ctx, prefs, name)
        assertTrue(palette(name).isEmpty(), "still there after the delete: ${palette(name)}")
        assertNotEquals(name, prefs.getString(Settings.PREF_THEME_COLORS, null))
        assertNotEquals(name, prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, null), "the dark colours still named it")
        AppearanceDraft.rejectOpen(ctx) // the cross, or Discard and exit
        assertTrue(palette(name).isNotEmpty(), "the cross didn't bring it back")
        assertEquals(name, prefs.getString(Settings.PREF_THEME_COLORS, null))
        assertEquals(name, prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, null))
    }

    @Test fun `a deleted palette stays gone after the tick`() {
        val prefs = ctx.prefs()
        val name = "Mine"
        KeyboardTheme.writeUserColors(prefs, name, KeyboardTheme.seedUserColors(ctx, prefs, KeyboardTheme.THEME_CURMUDGEON, false))
        AppearanceDraft.of(ctx).let { draft ->
            KeyboardTheme.deleteUserColors(ctx, prefs, name)
            draft.accept() // the tick
        }
        assertTrue(palette(name).isEmpty())
    }

    @Test fun `a new palette starts from the colours of the scheme selected`() {
        val prefs = ctx.prefs()
        val from = KeyboardTheme.THEME_CURMUDGEON
        val seeded = KeyboardTheme.seedUserColors(ctx, prefs, from, false)
        KeyboardTheme.writeUserColors(prefs, "Seeded", seeded)
        val style = prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)!!
        val source = KeyboardTheme.getThemeColors(from, style, ctx, prefs, false)
        val copy = KeyboardTheme.getThemeColors("Seeded", style, ctx, prefs, false)
        for (type in listOf(ColorType.MAIN_BACKGROUND, ColorType.KEY_BACKGROUND, ColorType.KEY_TEXT, ColorType.KEY_HINT_TEXT,
                ColorType.SPACE_BAR_BACKGROUND, ColorType.GESTURE_TRAIL, ColorType.ACTION_KEY_BACKGROUND))
            assertEquals(source.get(type), copy.get(type), type.name)
        assertEquals(Defaults.CURMUDGEON_ORANGE, copy.get(ColorType.ACTION_KEY_BACKGROUND))
    }

    // ---- the accent colour ----

    @Test fun `the accent colour picked colours the trail and the enter key, else the theme's`() {
        val prefs = ctx.prefs()
        val theme = KeyboardTheme.getColorsForCurrentTheme(ctx)
        val picked = 0xFF22AA66.toInt()
        assertNotEquals(picked, theme.get(ColorType.ACTION_KEY_BACKGROUND))
        prefs.edit { putInt(Settings.PREF_GESTURE_TRAIL_COLOR, picked) }
        val now = KeyboardTheme.getColorsForCurrentTheme(ctx)
        assertEquals(picked, now.get(ColorType.GESTURE_TRAIL))
        assertEquals(picked, now.get(ColorType.ACTION_KEY_BACKGROUND))
        assertEquals(theme.get(ColorType.KEY_BACKGROUND), now.get(ColorType.KEY_BACKGROUND), "nothing else changes")
    }

    // ---- review finding 10: Cancel put the shared value into the keyboard's own set ----

    @Test fun `Cancel leaves a keyboard's own set as it was, the shared value not copied in`() {
        real.edit().putBoolean("separate_settings_per_keyboard", true).putInt(Settings.PREF_GESTURE_TRAIL_COLOR, 0xFF0000FF.toInt()).commit()
        val own = ProfilePreferences(real) { 1 }
        val key = Settings.PREF_GESTURE_TRAIL_COLOR
        val snapshot = RawPrefSnapshot(real, 1, listOf(key))
        own.edit { putInt(key, 0xFF00FF00.toInt()) } // the picker previews a colour
        snapshot.restore() // Cancel
        assertFalse(real.contains("p1/$key"), "the shared colour was written into keyboard 1's own set")
        assertFalse(real.contains("p1/${KeyboardProfiles.TOMBSTONE}$key"))
        assertEquals(0xFF0000FF.toInt(), own.getInt(key, 0), "keyboard 1 still follows the shared value")
        // and a keyboard's own value comes back as its own
        own.edit { putInt(key, 0xFF111111.toInt()) }
        val again = RawPrefSnapshot(real, 1, listOf(key))
        own.edit { remove(key) }
        again.restore()
        assertEquals(0xFF111111.toInt(), real.getInt("p1/$key", 0))
        assertFalse(real.contains("p1/${KeyboardProfiles.TOMBSTONE}$key"))
    }

    // ---- review finding 11: one slot for the keyboard-switch callback ----

    @Test fun `every open settings screen hears that another keyboard is edited`() {
        var a = 0; var b = 0
        val la: () -> Unit = { a++ }; val lb: () -> Unit = { b++ }
        KeyboardProfiles.addEditingListener(la); KeyboardProfiles.addEditingListener(lb)
        KeyboardProfiles.editingId = 2
        KeyboardProfiles.removeEditingListener(la) // the first activity stops
        KeyboardProfiles.editingId = 3
        KeyboardProfiles.removeEditingListener(lb)
        assertEquals(1, a); assertEquals(2, b)
    }

    // ---- the Curmudgeon Layout = the defaults ----

    @Test fun `the defaults are the Curmudgeon Layout's`() {
        assertEquals(150, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)
        assertEquals(200, Defaults.PREF_BACKSPACE_LONGPRESS_DELAY, "a held backspace starts after 200 ms, not the keys' 150")
        assertEquals(200, Defaults.PREF_BACKSPACE_REPEAT_INTERVAL)
        assertTrue(Defaults.PREF_BACKSPACE_SPEED_UP)
        assertTrue(Defaults.PREF_BOTTOM_PADDING_SCALE.all { it == 0f }, "no space under the bottom row")
        assertEquals("all", Defaults.PREF_MORE_POPUP_KEYS)
        assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review 2026-10-08 area 4 (themes, palettes, popup sets, pictures) #3..#7. */
@RunWith(RobolectricTestRunner::class)
class Review1010ThemesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun set(id: Int) = ProfilePreferences(real) { id }
    private val colors = Settings.PREF_THEME_COLORS
    private val night = Settings.PREF_THEME_COLORS_NIGHT

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    // ---- #3: palette rename and delete in the shared set reach the keyboards' hidden own sets ----

    // separate settings off; keyboard 1's own set (kept from earlier) names the shared palette "My theme" with no colours
    // of its own, keyboard 2's own set has its own "My theme"
    private fun hiddenSetsNamingMyTheme() {
        real.edit()
            .putString("keyboard_profile_ids", """{"en_US:":1,"de_DE:":2}""")
            .putString(Settings.PREF_USER_COLORS_PREFIX + "My theme", "[]")
            .putString(colors, "My theme")
            .putString("p1/$colors", "My theme").putString("p1/$night", "My theme")
            .putString("p2/$colors", "My theme").putString("p2/${Settings.PREF_USER_COLORS_PREFIX}My theme", "[]")
            .commit()
    }

    @Test fun `a palette deleted in the shared set is forgotten by a hidden set that read it`() {
        hiddenSetsNamingMyTheme()
        KeyboardTheme.deleteUserColors(ctx, set(1), "My theme") // (separate off: the shared set)
        assertFalse(real.contains(colors))
        assertFalse(real.contains("p1/$colors"))
        assertFalse(real.contains("p1/$night"))
        assertTrue(real.getBoolean("p1/${KeyboardProfiles.TOMBSTONE}$colors", false))
        // a new palette that takes the free name later is not picked up by keyboard 1 when separate settings come back
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "My theme", "[]").putBoolean("separate_settings_per_keyboard", true).commit()
        assertNull(set(1).getString(colors, null))
        // keyboard 2's own palette of that name stays its choice
        assertEquals("My theme", real.getString("p2/$colors", null))
    }

    @Test fun `a palette renamed in the shared set is renamed in a hidden set that read it`() {
        hiddenSetsNamingMyTheme()
        assertTrue(KeyboardTheme.renameUserColors("My theme", "Work", set(1)))
        assertEquals("Work", real.getString(colors, null))
        assertEquals("Work", real.getString("p1/$colors", null))
        assertEquals("Work", real.getString("p1/$night", null))
        assertEquals("My theme", real.getString("p2/$colors", null))
    }

    @Test fun `the palette editor's Discard after a rename takes the hidden sets back too`() {
        hiddenSetsNamingMyTheme()
        KeyboardTheme.renameUserColors("My theme", "Work", set(1))
        KeyboardTheme.followInHiddenSets(set(1), "Work", "My theme") // (what the editor's putBack does)
        assertEquals("My theme", real.getString("p1/$colors", null))
        assertEquals("My theme", real.getString("p1/$night", null))
    }

    @Test fun `a palette deleted in a keyboard's own set leaves the other keyboards' choices`() {
        hiddenSetsNamingMyTheme()
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        KeyboardTheme.deleteUserColors(ctx, set(2), "My theme")
        assertEquals("My theme", real.getString("p1/$colors", null))
        assertEquals("My theme", real.getString(colors, null))
    }
}

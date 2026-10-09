// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.Defaults
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
import kotlin.test.assertNotEquals

/** The upgrade steps that rename or move a stored value reach every keyboard's own set, not the shared one only. */
@RunWith(RobolectricTestRunner::class)
class OwnSetRenamesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val colors = Settings.PREF_THEME_COLORS

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    // an upgrader from before 0.3.008 whose keyboard 1 has its own colours, "midnight" as both had then
    private fun midnightOnBoth(separate: Boolean) {
        real.edit {
            putInt(Settings.PREF_VERSION_CODE, 3901)
            putBoolean("separate_settings_per_keyboard", separate)
            putString("keyboard_profile_ids", """{"en_US:":1}""")
            putString(colors, "midnight")
            putString("p1/$colors", "midnight")
            putString("p1/${Settings.PREF_THEME_COLORS_NIGHT}", "midnight")
        }
        checkVersionUpgrade(ctx)
    }

    @Test fun `a keyboard's own midnight colours become black, then follow the defaults like the shared set`() {
        midnightOnBoth(separate = true)
        assertNotEquals("midnight", real.all["p1/$colors"])
        assertNotEquals("midnight", real.all["p1/${Settings.PREF_THEME_COLORS_NIGHT}"])
        val one = ProfilePreferences(real) { 1 }
        assertEquals(real.getString(colors, Defaults.PREF_THEME_COLORS), one.getString(colors, Defaults.PREF_THEME_COLORS))
        assertEquals(Defaults.PREF_THEME_COLORS, one.getString(colors, Defaults.PREF_THEME_COLORS))
    }

    @Test fun `a hidden set's own midnight colours become black too`() {
        midnightOnBoth(separate = false)
        assertNotEquals("midnight", real.all["p1/$colors"])
        assertNotEquals("midnight", real.all["p1/${Settings.PREF_THEME_COLORS_NIGHT}"])
    }

    // a set from 2026-09-22..28 with its own toolbar mode: it read the shared visibility and lost its own choice
    @Test fun `a keyboard's own toolbar mode becomes its own toolbar visibility and suggestions setting`() {
        real.edit {
            putInt(Settings.PREF_VERSION_CODE, 3901)
            putBoolean("separate_settings_per_keyboard", true)
            putString("keyboard_profile_ids", """{"en_US:":1,"de_DE:":2}""")
            putString(Settings.PREF_TOOLBAR_MODE, "EXPANDABLE")
            putString("p1/${Settings.PREF_TOOLBAR_MODE}", "HIDDEN")
            putString("p2/${Settings.PREF_TOOLBAR_MODE}", "EXPANDABLE")
            putBoolean("p2/${Settings.PREF_TOOLBAR_IN_STRIP_ROW}", true)
        }
        checkVersionUpgrade(ctx)
        val one = ProfilePreferences(real) { 1 }
        assertEquals(Settings.TOOLBAR_HIDDEN, Settings.readToolbarVisibility(one))
        assertEquals(false, one.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, Defaults.PREF_SHOW_SUGGESTIONS))
        val two = ProfilePreferences(real) { 2 }
        assertEquals(Settings.TOOLBAR_IN_PLACE, Settings.readToolbarVisibility(two))
        assertEquals(Defaults.PREF_SHOW_SUGGESTIONS, two.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, Defaults.PREF_SHOW_SUGGESTIONS))
        // the shared set as the plain step leaves it
        assertEquals(Settings.TOOLBAR_ABOVE, Settings.readToolbarVisibility(real))
    }
}

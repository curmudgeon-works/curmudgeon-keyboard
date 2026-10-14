// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.appSettingsItems
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owner 2026-10-14: "in the appearance screen with "dark mode follows phone" turned off search only shows its "Colors"
 * submenu and not "Colors (light)" / "Colors (dark)" which only show up when the toggle is on. -- correct behavior; but
 * in main home screen, searching for "Color" with the toggle off also shows "Colors (dark)" -- wrong behavior. ... same
 * thing happens with the separate settings toggle off, when comparing search in main screen vs search in app settings."
 * The main screen's search ([SettingsContainer.filter]) now searches what the screens show now ([shownScreenLists]).
 * Fail before: "color" found the dark colors with the toggle off, "appearance" the per-keyboard row with separate
 * settings off.
 */
@RunWith(RobolectricTestRunner::class)
class WholeAppSearchTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val container get() = SettingsActivity.settingsContainer

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        storeOneKeyboard(ctx, real) // (a keyboard for ctx.prefs() writes to land in; it turns separate settings on)
        SettingsActivity.settingsContainer = SettingsContainer(ctx)
    }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun found(term: String) = container.filter(term, ctx).map { it.key }

    @Test fun `dark colors are found from the main screen only while dark mode follows the device`() {
        KeyboardProfiles.disable(real)
        ctx.prefs().edit(commit = true) { putBoolean(Settings.PREF_THEME_DAY_NIGHT, false) }
        val off = found("color")
        assertFalse(Settings.PREF_THEME_COLORS_NIGHT in off, "$off")
        assertTrue(Settings.PREF_THEME_COLORS in off, "$off") // "Colors": shown either way
        ctx.prefs().edit(commit = true) { putBoolean(Settings.PREF_THEME_DAY_NIGHT, true) }
        val on = found("color")
        assertTrue(Settings.PREF_THEME_COLORS_NIGHT in on, "$on")
        assertTrue(Settings.PREF_THEME_COLORS in on, "$on")
    }

    @Test fun `the per-keyboard menu rows are found from the main screen only with separate settings`() {
        val appearanceRow = KeyboardProfiles.Group.APPEARANCE.prefKey // App settings > Per keyboard > Appearance
        val term = ctx.getString(R.string.settings_screen_appearance)
        KeyboardProfiles.disable(real)
        assertFalse(appearanceRow in found(term), "${found(term)}")
        // the same as App settings' own search
        val appSettings = screenSettings(appSettingsItems(ctx.prefs(), real), real, null)
        assertFalse(appearanceRow in screenSearch(term, appSettings).map { it.key })
        KeyboardProfiles.enable(real)
        assertTrue(appearanceRow in found(term), "${found(term)}")
    }

    @Test fun `every setting the main screen finds is on some screen's current list`() {
        fun check(state: String) {
            val lists = shownScreenLists(ctx)
            val onLists = lists.flatMap { it.keys }.filterIsInstance<String>().toSet()
            val shown = container.shown(ctx)
            assertTrue(shown.isNotEmpty(), state)
            shown.forEach { assertTrue(it.key in onLists, "$state: ${it.key} is on no screen's list") }
            // and each screen's own search finds nothing the main screen's doesn't
            val shownKeys = shown.map { it.key }.toSet()
            lists.forEach { list -> screenSettings(list.keys, real, list.menu).forEach {
                assertTrue(it.key in shownKeys, "$state: ${it.key} found on its screen, not from the main screen") } }
            // what no screen shows stays out
            assertFalse(Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER in shownKeys, state)
        }
        KeyboardProfiles.disable(real)
        check("defaults")
        ctx.prefs().edit(commit = true) {
            putBoolean(Settings.PREF_THEME_DAY_NIGHT, true); putBoolean(Settings.PREF_GESTURE_INPUT, false)
            putBoolean(Settings.PREF_AUTO_CORRECTION, false); putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true)
        }
        check("switches changed")
        KeyboardProfiles.enable(real)
        check("separate settings")
    }

    @Test fun `a setting under a switch that is off is not found, found again once it is on`() {
        KeyboardProfiles.disable(real)
        val term = ctx.getString(R.string.gesture_trail_thickness)
        ctx.prefs().edit(commit = true) { putBoolean(Settings.PREF_GESTURE_INPUT, false) }
        assertFalse(Settings.PREF_GESTURE_TRAIL_THICKNESS in found(term))
        ctx.prefs().edit(commit = true) { putBoolean(Settings.PREF_GESTURE_INPUT, true) }
        assertTrue(Settings.PREF_GESTURE_TRAIL_THICKNESS in found(term))
    }

    // kept: what was found before as a part of a row the screens show (a row's own lists, its dialog, what it opens)
    @Test fun `the parts of a shown row are still found`() {
        KeyboardProfiles.disable(real)
        assertTrue(Settings.PREF_PINNED_TOOLBAR_KEYS in found(ctx.getString(R.string.pinned_toolbar_keys)))
        assertTrue(SettingsWithoutKey.LEARNED_WORDS in found(ctx.getString(R.string.learned_words)))
        assertTrue(SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY in found(ctx.getString(R.string.edit_personal_dictionary)))
    }
}

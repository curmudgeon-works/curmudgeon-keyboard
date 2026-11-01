// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.correctionItems
import helium314.keyboard.settings.screens.correctionSimpleModeKeys
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-10-31 (suggestions audit follow-up): "Keep the shortcuts toggle as is too. All autocorrect sub-settings can be
 * advanced"; the confidence list: "Separate modest/aggressive is fine as per current … today's subtext showing Modest,
 * Aggressive, Very aggressive is ok". Text correction's four auto-correct sub-settings (overwrites app preference,
 * shortcuts, words with digits, confidence) are advanced-only rows: simple mode hides them, the advanced switch shows
 * them (on the advanced tint), only while auto-correction is on; the screen's search and the main screen's search find
 * them in either mode (tinted). A pin, not a fail-first test: the rows were advanced-only since the simple set came
 * (de3ed9dd4); this records the decision so a later edit to the simple set cannot undo it unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionAdvancedRowsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val subSettings = listOf(Settings.PREF_MORE_AUTO_CORRECTION, Settings.PREF_AUTOCORRECT_SHORTCUTS,
        Settings.PREF_AUTOCORRECT_WITH_DIGITS, Settings.PREF_AUTO_CORRECT_THRESHOLD)
    private var modeBefore = Defaults.PREF_ADVANCED_SETTINGS

    @Before fun setUp() {
        modeBefore = SettingsMode.isAdvanced(ctx)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SettingsActivity.settingsContainer = SettingsContainer(ctx)
    }
    @After fun tearDown() { SettingsMode.set(ctx, modeBefore); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun keys(found: List<Setting>) = found.map { it.key }

    @Test fun `the four auto-correct sub-settings are advanced-only, auto-correction itself is not`() {
        val simple = correctionSimpleModeKeys(real)
        for (key in subSettings) assertTrue(advancedOnly(key, simple), key)
        assertFalse(advancedOnly(Settings.PREF_AUTO_CORRECTION, simple))
        val items = correctionItems(real)
        for (key in subSettings) assertTrue(key in items, "shown with auto-correction on: $key")
        // simple mode: hidden; advanced mode: shown
        val inSimple = SettingsMode.filter(items, simple, advanced = false)
        for (key in subSettings) assertFalse(key in inSimple, "hidden in simple mode: $key")
        assertTrue(Settings.PREF_AUTO_CORRECTION in inSimple)
        val inAdvanced = SettingsMode.filter(items, simple, advanced = true)
        for (key in subSettings) assertTrue(key in inAdvanced, "shown in advanced mode: $key")
    }

    @Test fun `the rows still come only while auto-correction is on`() {
        real.edit().putBoolean(Settings.PREF_AUTO_CORRECTION, false).commit()
        val items = correctionItems(real)
        for (key in subSettings) assertFalse(key in items, "no row while auto-correction is off: $key")
        assertTrue(Settings.PREF_AUTO_CORRECTION in items)
        // nor in either mode
        val simple = correctionSimpleModeKeys(real)
        for (key in subSettings) assertFalse(key in SettingsMode.filter(items, simple, advanced = true), key)
    }

    @Test fun `the screen's search and the main screen's search find them in simple mode, for the advanced tint`() {
        SettingsMode.set(ctx, false)
        // the screen's own search runs over every row it lists, the ones simple mode hides too
        val own = screenSettings(correctionItems(real), real, null)
        for (key in subSettings) assertTrue(key in keys(own), "the screen's search: $key")
        assertTrue(Settings.PREF_AUTOCORRECT_WITH_DIGITS in keys(screenSearch("digits", own)))
        // the main screen's search: on Text correction's list, marked advanced (tinted)
        val correction = shownScreenLists(ctx).first { it.menu == KeyboardProfiles.Group.TEXT_CORRECTION }
        for (key in subSettings) {
            assertTrue(key in correction.keys, "the main search's list: $key")
            assertTrue(key in correction.advanced, "tinted as advanced-only: $key")
        }
        assertFalse(Settings.PREF_AUTO_CORRECTION in correction.advanced)
        assertTrue(Settings.PREF_AUTOCORRECT_WITH_DIGITS in keys(SettingsActivity.settingsContainer.filter("digits", ctx)))
    }

    @Test fun `the confidence list reads Modest, Aggressive, Very aggressive, as it does today`() {
        assertEquals("Auto-correction confidence", ctx.getString(R.string.auto_correction_confidence))
        assertEquals(listOf("Modest", "Aggressive", "Very aggressive"), listOf(
            ctx.getString(R.string.auto_correction_threshold_mode_modest),
            ctx.getString(R.string.auto_correction_threshold_mode_aggressive),
            ctx.getString(R.string.auto_correction_threshold_mode_very_aggressive)))
        assertEquals(0.185f, Defaults.PREF_AUTO_CORRECT_THRESHOLD) // (Modest)
        // the shortcuts toggle stays
        assertTrue(Settings.PREF_AUTOCORRECT_SHORTCUTS in correctionItems(real))
        assertTrue(Defaults.PREF_AUTOCORRECT_SHORTCUTS)
    }
}

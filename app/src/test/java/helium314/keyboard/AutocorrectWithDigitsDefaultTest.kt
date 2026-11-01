// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.correctionItems
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-10-31: "Auto correct words with digits should be on by default (if auto-correct is on of course)". The default
 * is on; a keyboard set that never touched the switch reads on (stored = picked: nothing is written for a default); the
 * row is still shown only while auto-correction is on; "More auto-correction" stays off.
 * Fail before: Defaults.PREF_AUTOCORRECT_WITH_DIGITS was false.
 */
@RunWith(RobolectricTestRunner::class)
class AutocorrectWithDigitsDefaultTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()

    @Before fun setUp() { real.edit().clear().commit() }
    @After fun tearDown() { real.edit().clear().commit() }

    @Test fun `auto-correct words with digits is on by default, overwriting the app preference is off`() {
        assertTrue(Defaults.PREF_AUTOCORRECT_WITH_DIGITS)
        assertFalse(Defaults.PREF_MORE_AUTO_CORRECTION)
        // nothing stored: the keyboard reads the default, as SettingsValues does
        assertFalse(real.contains(Settings.PREF_AUTOCORRECT_WITH_DIGITS))
        assertTrue(real.getBoolean(Settings.PREF_AUTOCORRECT_WITH_DIGITS, Defaults.PREF_AUTOCORRECT_WITH_DIGITS))
        // a set that switched it off before today keeps that (stored = picked)
        real.edit().putBoolean(Settings.PREF_AUTOCORRECT_WITH_DIGITS, false).commit()
        assertFalse(real.getBoolean(Settings.PREF_AUTOCORRECT_WITH_DIGITS, Defaults.PREF_AUTOCORRECT_WITH_DIGITS))
    }

    @Test fun `the row is shown only while auto-correction is on`() {
        assertTrue(Defaults.PREF_AUTO_CORRECTION) // (the gate is open by default)
        assertTrue(correctionItems(real).contains(Settings.PREF_AUTOCORRECT_WITH_DIGITS))
        real.edit().putBoolean(Settings.PREF_AUTO_CORRECTION, false).commit()
        assertFalse(correctionItems(real).contains(Settings.PREF_AUTOCORRECT_WITH_DIGITS))
    }

    @Test fun `the summary explains the off state, so it reads right with the switch on`() {
        val summary = ctx.getString(R.string.autocorrect_with_digits_summary)
        assertTrue(summary.startsWith("Off:"), summary)
        assertEquals("Auto-correct words with digits", ctx.getString(R.string.autocorrect_with_digits))
    }
}

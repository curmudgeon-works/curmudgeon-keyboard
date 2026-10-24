// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.kid
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.listedById
import helium314.keyboard.storeKeyboards
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The setup's "Pick a layout" (2026-10-24): Minimal is the Classic Layout with the number row off, the symbol map off and
 * the symbols hidden written on top, on every keyboard; Classic the Classic Layout; Dense left picked writes nothing
 * (stored = picked); Dense again after another puts back what was there. Fail before: no SetupLayout (compile).
 */
@RunWith(RobolectricTestRunner::class)
class SetupLayoutTest {
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
    private val classic get() = ctx.getString(R.string.layout_preset_classic)
    private val dense get() = ctx.getString(R.string.layout_preset_dense)

    @Test fun `a fresh install is on Dense, and reading writes nothing`() {
        val before = real.all.toMap()
        assertEquals(SetupLayout.Pick.DENSE, SetupLayout.current(ctx))
        SetupLayout.before(ctx)
        assertEquals(before, real.all.toMap())
        assertEquals(Defaults.PREF_SUGGESTION_WORD_PADDING, SetupLayout.wordPadding(ctx, SetupLayout.Pick.DENSE))
        assertEquals(10, SetupLayout.wordPadding(ctx, SetupLayout.Pick.CLASSIC))
    }

    @Test fun `Minimal writes Classic and its three values on every keyboard`() {
        SetupLayout.pick(ctx, SetupLayout.Pick.MINIMAL)
        for (n in 1..2) {
            assertEquals(classic, stored(n, LayoutPresets.PREF_SELECTED), "keyboard $n")
            assertEquals(false, stored(n, Settings.PREF_SHOW_NUMBER_ROW), "keyboard $n")
            assertEquals("", stored(n, Settings.PREF_SYMBOL_POPUP_MAP), "keyboard $n")
            assertEquals(false, stored(n, Settings.PREF_SHOW_HINTS), "keyboard $n")
            assertEquals(300, stored(n, Settings.PREF_KEY_LONGPRESS_TIMEOUT), "keyboard $n") // Classic's typing
            assertEquals("main", listedById(real, kid(n))?.getExtraValueOf(ExtraValue.MORE_POPUPS), "keyboard $n")
        }
        assertEquals(SetupLayout.Pick.MINIMAL, SetupLayout.current(ctx))
    }

    @Test fun `Classic applies the Classic Layout on every keyboard, nothing on top`() {
        SetupLayout.pick(ctx, SetupLayout.Pick.CLASSIC)
        for (n in 1..2) {
            assertEquals(classic, stored(n, LayoutPresets.PREF_SELECTED), "keyboard $n")
            assertEquals(true, stored(n, Settings.PREF_SHOW_NUMBER_ROW), "keyboard $n")
            assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, stored(n, Settings.PREF_SYMBOL_POPUP_MAP), "keyboard $n")
            assertNull(stored(n, Settings.PREF_SHOW_HINTS), "keyboard $n") // shown: the default
            assertEquals(10, stored(n, Settings.PREF_SUGGESTION_WORD_PADDING), "keyboard $n")
            assertEquals("main", listedById(real, kid(n))?.getExtraValueOf(ExtraValue.MORE_POPUPS), "keyboard $n")
        }
        assertEquals(SetupLayout.Pick.CLASSIC, SetupLayout.current(ctx))
        // the Layouts row names Classic, no Custom label
        assertNull(stored(1, CustomLabels.Kind.LAYOUT.key))
    }

    @Test fun `Dense again after Minimal puts back what each keyboard had, its definition too`() {
        real.edit().putInt(idKey(2, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 450).commit() // keyboard 2's own pick
        val before = real.all.toMap()
        val saved = SetupLayout.before(ctx)
        SetupLayout.pick(ctx, SetupLayout.Pick.MINIMAL)
        assertEquals("main", listedById(real, kid(1))?.getExtraValueOf(ExtraValue.MORE_POPUPS))
        SetupLayout.pickDense(ctx, saved)
        assertEquals(before, real.all.toMap())
        assertNull(listedById(real, kid(1))?.getExtraValueOf(ExtraValue.MORE_POPUPS))
        assertEquals(SetupLayout.Pick.DENSE, SetupLayout.current(ctx))
    }

    @Test fun `a keyboard that came on Classic gets the Dense Layout when Dense is picked`() {
        SetupLayout.pick(ctx, SetupLayout.Pick.MINIMAL)
        SetupLayout.pickDense(ctx, null)
        for (n in 1..2) {
            assertEquals(dense, stored(n, LayoutPresets.PREF_SELECTED), "keyboard $n")
            assertNull(stored(n, Settings.PREF_SHOW_NUMBER_ROW), "keyboard $n")
            assertNull(stored(n, Settings.PREF_SHOW_HINTS), "keyboard $n")
            assertNull(stored(n, Settings.PREF_KEY_LONGPRESS_TIMEOUT), "keyboard $n")
        }
        assertEquals(SetupLayout.Pick.DENSE, SetupLayout.current(ctx))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Layouts that ship with the app (2026-10-06; two since 2026-10-24): Dense (the default) and Classic. */
@RunWith(RobolectricTestRunner::class)
class BuiltInLayoutsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val builtIn by lazy { LayoutPresets.builtIn(ctx) }
    private fun named(id: Int) = builtIn.single { it.name == ctx.getString(id) }

    @Test fun `exactly two built-ins, Dense first, each setting the whole screen`() {
        assertEquals(listOf("Dense", "Classic"), builtIn.map { it.name })
        assertEquals(listOf(R.string.layout_preset_dense, R.string.layout_preset_classic).map { ctx.getString(it) }, builtIn.map { it.name })
        for (preset in builtIn) {
            assertTrue(preset.builtIn)
            for (key in LayoutDraft.keys.filter { LayoutPresets.inScope(it) })
                assertTrue(preset.values.containsKey(key), "${preset.name} leaves $key as it was")
        }
    }

    // re-review 2026-10-07 (M5): with the Layout menu shared by all keyboards, a saved Layout still carries its settings
    @Test fun `a Layout covers the screen's settings when the Layout menu is shared`() {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        real.edit().putBoolean(helium314.keyboard.latin.settings.KeyboardProfiles.Group.LAYOUT.prefKey, true).apply()
        helium314.keyboard.latin.settings.KeyboardProfiles.loadGroups(real)
        try {
            assertTrue(LayoutPresets.inScope(Settings.PREF_SHOW_NUMBER_ROW))
            assertTrue(LayoutPresets.inScope(Settings.PREF_LONG_PRESS_SYMBOL_ACTION))
            assertTrue(named(R.string.layout_preset_classic).values.containsKey(Settings.PREF_SHOW_NUMBER_ROW))
        } finally {
            real.edit().remove(helium314.keyboard.latin.settings.KeyboardProfiles.Group.LAYOUT.prefKey).apply()
            helium314.keyboard.latin.settings.KeyboardProfiles.loadGroups(real)
        }
    }

    @Test fun `Dense is everything at its default, and the defaults are its values`() {
        assertTrue(named(R.string.layout_preset_dense).values.values.all { it == null })
        assertEquals(true, Defaults.PREF_SHOW_NUMBER_ROW)
        assertTrue(Defaults.PREF_BOTTOM_ROW_SCALE.all { it == 1.1f })
        assertEquals(50, Defaults.PREF_UNDO_HISTORY_LENGTH)
        assertEquals(true, Defaults.PREF_SHOW_EMOJI_KEY)
    }

    @Test fun `Classic is HeliBoard's typing with the number row, the Curmudgeon symbol map and the main popups`() {
        val classic = named(R.string.layout_preset_classic).values
        // HeliBoard's typing (what the HeliBoard Layout set until 2026-10-24)
        assertEquals("none", classic[Settings.PREF_LONG_PRESS_SYMBOL_ACTION])
        assertEquals(false, classic[Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS])
        assertEquals(50, classic[Settings.PREF_BACKSPACE_REPEAT_INTERVAL])
        assertEquals(20, classic[Settings.PREF_UNDO_HISTORY_LENGTH])
        assertEquals(false, classic[Settings.PREF_SHOW_EMOJI_KEY])
        assertTrue(classic.filterKeys { it.startsWith(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX) }.values.all { it == 1f })
        assertEquals(300, classic[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        assertEquals(Defaults.BACKSPACE_DELAY_FOLLOWS, classic[Settings.PREF_BACKSPACE_LONGPRESS_DELAY]) // as the long press
        assertEquals(false, classic[Settings.PREF_BACKSPACE_SPEED_UP])
        assertEquals(listOf(1f, 0f, 1f, 0f), (0 until 4).map { // portrait, landscape, folded portrait, folded landscape
            classic[helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, it, 2)] })
        // and what 2026-10-24 put on top of it
        assertEquals(true, classic[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, classic[Settings.PREF_SYMBOL_POPUP_MAP])
        assertEquals("main", classic["morePopups"])
        // the hints as the default has them: shown
        assertNull(classic[Settings.PREF_SHOW_HINTS])
        val dense = named(R.string.layout_preset_dense).values
        assertNull(dense[Settings.PREF_KEY_LONGPRESS_TIMEOUT]) // the defaults
        assertNull(dense[Settings.PREF_SYMBOL_POPUP_MAP])
    }

    @Test fun `a built-in leaves the keys' arrangement, sets the accents and the popup order back`() {
        val keyboard = SettingsSubtype(Locale.US, "").withLayout(LayoutType.MAIN, "dvorak").with(ExtraValue.POPUP_ORDER, "number;true")
        val classic = LayoutPresets.keyboardWith(ctx, keyboard, named(R.string.layout_preset_classic))
        assertEquals("dvorak", classic.layoutName(LayoutType.MAIN))
        assertEquals("main", classic.getExtraValueOf(ExtraValue.MORE_POPUPS))
        assertNull(classic.getExtraValueOf(ExtraValue.POPUP_ORDER))
        val dense = LayoutPresets.keyboardWith(ctx, keyboard, named(R.string.layout_preset_dense))
        assertNull(dense.getExtraValueOf(ExtraValue.MORE_POPUPS)) // the general accents level
    }
}

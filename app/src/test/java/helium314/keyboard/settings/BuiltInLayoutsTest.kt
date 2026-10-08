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

/** The Layouts that ship with the app (2026-10-06): Curmudgeon (the default), HeliBoard, HeliBoard extra. */
@RunWith(RobolectricTestRunner::class)
class BuiltInLayoutsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val builtIn by lazy { LayoutPresets.builtIn(ctx) }
    private fun named(id: Int) = builtIn.single { it.name == ctx.getString(id) }

    @Test fun `three built-ins, Curmudgeon first, each setting the whole screen`() {
        assertEquals(listOf(R.string.layout_preset_curmudgeon, R.string.layout_preset_heliboard, R.string.layout_preset_heliboard_extra)
            .map { ctx.getString(it) }, builtIn.map { it.name })
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
            assertTrue(named(R.string.layout_preset_heliboard).values.containsKey(Settings.PREF_SHOW_NUMBER_ROW))
        } finally {
            real.edit().remove(helium314.keyboard.latin.settings.KeyboardProfiles.Group.LAYOUT.prefKey).apply()
            helium314.keyboard.latin.settings.KeyboardProfiles.loadGroups(real)
        }
    }

    @Test fun `Curmudgeon is everything at its default, and the defaults are its values`() {
        assertTrue(named(R.string.layout_preset_curmudgeon).values.values.all { it == null })
        assertEquals(true, Defaults.PREF_SHOW_NUMBER_ROW)
        assertTrue(Defaults.PREF_BOTTOM_ROW_SCALE.all { it == 1.1f })
        assertEquals(50, Defaults.PREF_UNDO_HISTORY_LENGTH)
        assertEquals(true, Defaults.PREF_SHOW_EMOJI_KEY)
    }

    @Test fun `HeliBoard is upstream's, HeliBoard extra adds every accent and the number row`() {
        val heli = named(R.string.layout_preset_heliboard).values
        assertEquals(false, heli[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals("none", heli[Settings.PREF_LONG_PRESS_SYMBOL_ACTION])
        assertEquals(false, heli[Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS])
        assertEquals(50, heli[Settings.PREF_BACKSPACE_REPEAT_INTERVAL])
        assertEquals(20, heli[Settings.PREF_UNDO_HISTORY_LENGTH])
        assertEquals(false, heli[Settings.PREF_SHOW_EMOJI_KEY])
        assertTrue(heli.filterKeys { it.startsWith(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX) }.values.all { it == 1f })
        val extra = named(R.string.layout_preset_heliboard_extra).values
        assertEquals("none", extra[Settings.PREF_LONG_PRESS_SYMBOL_ACTION])
        assertNull(extra[Settings.PREF_SHOW_NUMBER_ROW]) // the default: on
        assertNull(extra[Settings.PREF_UNDO_HISTORY_LENGTH]) // the default: 50
        // what the Curmudgeon Layout changed on 2026-10-07 (long-press 150 ms, faster backspace, nothing under the bottom
        // row, the Curmudgeon popups) stays as HeliBoard has it in both
        for (preset in listOf(heli, extra)) {
            assertEquals(300, preset[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
            assertEquals(300, preset[Settings.PREF_BACKSPACE_LONGPRESS_DELAY])
            assertEquals(false, preset[Settings.PREF_BACKSPACE_SPEED_UP])
            assertEquals("", preset[Settings.PREF_SYMBOL_POPUP_MAP])
            assertEquals(listOf(1f, 0f, 1f, 0f), (0 until 4).map { // portrait, landscape, folded portrait, folded landscape
                preset[helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, it, 2)] })
        }
        val curmudgeon = named(R.string.layout_preset_curmudgeon).values
        assertNull(curmudgeon[Settings.PREF_KEY_LONGPRESS_TIMEOUT]) // the defaults
        assertNull(curmudgeon[Settings.PREF_SYMBOL_POPUP_MAP])
    }

    @Test fun `a built-in leaves the keys' arrangement, sets the accents and the popup order back`() {
        val keyboard = SettingsSubtype(Locale.US, "").withLayout(LayoutType.MAIN, "dvorak").with(ExtraValue.POPUP_ORDER, "number;true")
        val extra = LayoutPresets.keyboardWith(ctx, keyboard, named(R.string.layout_preset_heliboard_extra))
        assertEquals("dvorak", extra.layoutName(LayoutType.MAIN))
        assertEquals("all", extra.getExtraValueOf(ExtraValue.MORE_POPUPS))
        assertNull(extra.getExtraValueOf(ExtraValue.POPUP_ORDER))
    }
}

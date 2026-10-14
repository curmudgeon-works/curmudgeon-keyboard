// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.keepUserPopupSetsSymbolMap
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.LayoutPresets
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 2026-10-11, the owner's "Revamped popups": a popup set saved before sets kept their symbol map showed the default
 *  (Curmudgeon's) map, and 0.3.012's new default (none) took it away. A picked set keeps what it showed; the rest follows the default. */
@RunWith(RobolectricTestRunner::class)
class UserPopupSetMapTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun map(id: Int) = ProfilePreferences(real) { id }.getString(Settings.PREF_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP)

    /** As on the owner's phone on 2026-10-10: separate settings, keyboard 1 on "Revamped popups" (no map in the set, none stored). */
    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).toString())
            .putString(KeyPopupOverrides.PREF_SETS, """[{"name":"Revamped popups","morePopups":"all","symbolsLayout":"","overrides":{}}]""")
            .putString(KeyboardProfiles.ownKey(1, KeyPopupOverrides.PREF_SELECTED_SET), "Revamped popups")
            .putBoolean(KeyboardProfiles.ownKey(1, "~key_popups"), true)
            .putBoolean(KeyboardProfiles.ownKey(2, "~key_popups"), true) // (keyboard 2: its own settings, no popup set)
            .commit()
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Mine", mapOf(KeyPopupOverrides.PREF_SELECTED_SET to "Revamped popups"))))
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    @Test fun `a picked old popup set keeps the symbol map it showed - the set, the keyboard, the saved Layout`() {
        assertEquals("", map(1)) // the bug: 0.3.012's default, not what the set showed
        keepUserPopupSetsSymbolMap(real)
        assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, map(1))
        assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, KeyPopupOverrides.loadSets(real).single().symbolMap)
        assertEquals(Defaults.CURMUDGEON_SYMBOL_POPUP_MAP, LayoutPresets.load(real).single().values[Settings.PREF_SYMBOL_POPUP_MAP])
    }

    @Test fun `a keyboard that never picked a set follows the default, and a picked map is never overwritten`() {
        real.edit().putString(KeyboardProfiles.ownKey(1, Settings.PREF_SYMBOL_POPUP_MAP), "q! w?").commit()
        keepUserPopupSetsSymbolMap(real)
        assertEquals("", map(2)) // not picked: the default
        assertNull(real.getString(KeyboardProfiles.ownKey(2, Settings.PREF_SYMBOL_POPUP_MAP), null))
        assertEquals("q! w?", map(1)) // its own pick stays
    }

    @Test fun `it runs once`() {
        keepUserPopupSetsSymbolMap(real)
        real.edit().remove(KeyboardProfiles.ownKey(1, Settings.PREF_SYMBOL_POPUP_MAP)).commit() // (the user cleared it since)
        keepUserPopupSetsSymbolMap(real)
        assertNull(real.getString(KeyboardProfiles.ownKey(1, Settings.PREF_SYMBOL_POPUP_MAP), null))
    }
}

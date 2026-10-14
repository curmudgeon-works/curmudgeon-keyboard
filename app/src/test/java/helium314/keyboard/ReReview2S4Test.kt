// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SavedItemDefaults
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 2026-10-11 S4: the switch "Saved items take new defaults" turned off and back on left a keyboard's own set a
 * "default" mark where it held nothing, so a value later picked in the shared set no longer reached it. Back on, the
 * mark goes only where the shared set (after the same pass) holds a value.
 */
@RunWith(RobolectricTestRunner::class)
class ReReview2S4Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val longPress = Settings.PREF_KEY_LONGPRESS_TIMEOUT
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val us = SettingsSubtype(Locale.US, "")

    @Before fun setUp() { real.edit().clear().commit() }
    @After fun tearDown() { AppearanceDraft.close(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun mark(id: Int, key: String) = KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + key)
    private fun view(id: Int) = ProfilePreferences(real) { id }

    /** Separate settings, keyboard 1 (US); saved Layout Work (long press at its default, 300 when saved) and theme
     *  Sunset (gap at its default, 0.5 when saved). */
    private fun separateWithWorkAndSunset() {
        real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put(us.toPref(), 1).toString())
            .commit()
        KeyboardProfiles.loadGroups(real)
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(longPress to null), remembered = mapOf(longPress to 300))))
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to null), mapOf(gap to 0.5f))), now = true)
    }
    private fun choose() {
        LayoutPresets.applyToOther(ctx, us, LayoutPresets.load(real).single())
        AppearanceLooks.applyToOther(ctx, us, AppearanceLooks.load(real).single())
    }
    private fun flip(on: Boolean) {
        real.edit().putBoolean(SavedItemDefaults.PREF, on).commit()
        SavedItemDefaults.applySwitch(ctx, real, on)
    }

    @Test fun `off then on - an own set holding nothing, the shared set nothing - holds nothing again`() {
        separateWithWorkAndSunset()
        choose()
        for (key in listOf(longPress, gap)) assertFalse(real.contains(own(1, key)) || real.contains(mark(1, key)))
        flip(false)
        assertEquals(300, real.getInt(own(1, longPress), 0))
        flip(true)
        for (key in listOf(longPress, gap)) {
            assertFalse(real.contains(own(1, key)), "own value of $key stayed")
            assertFalse(real.contains(mark(1, key)), "a default mark for $key where the set held nothing")
        }
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, view(1).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
        // a value picked later in the shared set reaches it
        real.edit().putInt(longPress, 200).commit()
        assertEquals(200, view(1).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
    }

    @Test fun `off then on - the shared set holds a value - the own set gets its mark back and reads the default`() {
        separateWithWorkAndSunset()
        real.edit().putInt(longPress, 200).putBoolean(mark(1, longPress), true).commit() // (it read the default, not 200)
        choose()
        flip(false)
        assertEquals(300, real.getInt(own(1, longPress), 0))
        assertFalse(real.contains(mark(1, longPress)))
        flip(true)
        assertFalse(real.contains(own(1, longPress)))
        assertTrue(real.contains(mark(1, longPress)))
        assertEquals(200, real.getInt(longPress, 0))
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, view(1).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
    }

    @Test fun `off then on - the shared set drops its value in the same pass - no mark on the own set`() {
        separateWithWorkAndSunset()
        real.edit().putString(LayoutPresets.PREF_SELECTED, "Work").commit() // (the shared set chose Work too)
        choose()
        flip(false)
        assertEquals(300, real.getInt(longPress, 0)) // (the shared set's own written)
        assertEquals(300, real.getInt(own(1, longPress), 0))
        flip(true)
        assertFalse(real.contains(longPress))
        assertFalse(real.contains(own(1, longPress)))
        assertFalse(real.contains(mark(1, longPress)), "a default mark though the shared set holds nothing")
        real.edit().putInt(longPress, 200).commit()
        assertEquals(200, view(1).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
    }
}

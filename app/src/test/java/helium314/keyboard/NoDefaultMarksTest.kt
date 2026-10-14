// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
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
 * Owner 2026-10-11 ("go now with the cleanup"): no default marks ("p<id>/~<key>"). With no common set and no fallback
 * read a mark meant what nothing stored means: the default. A remove just removes; a pick equal to the default is stored
 * (owner 2026-10-06, "stored = picked"); the one-time step removes the old marks. Keyboard 1 (US) is the first in the
 * list: its own set is the common settings; keyboard 2 (French).
 */
@RunWith(RobolectricTestRunner::class)
class NoDefaultMarksTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val longPress = Settings.PREF_KEY_LONGPRESS_TIMEOUT
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val us = SettingsSubtype(Locale.US, "")
    private val fr = SettingsSubtype(Locale.FRANCE, "")

    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun mark(id: Int, key: String) = KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + key)
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private fun marks() = real.all.keys.filter { KeyboardProfiles.splitOwnKey(it)?.second?.startsWith(KeyboardProfiles.TOMBSTONE) == true }

    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put(us.toPref(), 1).put(fr.toPref(), 2).toString())
            .putInt("keyboard_profile_next_id", 3)
            .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(us, fr)))
            .commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { AppearanceDraft.close(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    // (a)
    @Test fun `a reset writes no mark and reads the default`() {
        view(2).edit().putInt(longPress, 400).commit()
        view(2).edit().remove(longPress).commit() // "Default"
        assertFalse(real.contains(own(2, longPress)))
        assertFalse(real.contains(mark(2, longPress)), "a default mark was written")
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, view(2).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
        // a reset of a key never stored, and one with separate settings off (the main keyboard's set): no mark either
        view(2).edit().remove(gap).commit()
        real.edit().putBoolean("separate_settings_per_keyboard", false).commit()
        view(2).edit().putInt(longPress, 400).commit()
        assertEquals(400, real.getInt(own(1, longPress), 0))
        view(2).edit().remove(longPress).commit()
        assertFalse(real.contains(own(1, longPress)))
        assertEquals(emptyList(), marks())
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, view(2).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
        assertFalse(view(2).all.keys.any { it.startsWith(KeyboardProfiles.TOMBSTONE) })
    }

    // (b)
    @Test fun `a picked value equal to the default stays stored and wins over a changed code default`() {
        val default = Defaults.PREF_KEY_LONGPRESS_TIMEOUT
        view(2).edit().putInt(longPress, default).commit() // picked: exactly the default of today
        assertEquals(default, real.getInt(own(2, longPress), -1))
        val changedDefault = default + 100 // (a later release's default: what the reader passes)
        assertEquals(default, view(2).getInt(longPress, changedDefault), "the pick followed the new default")
        KeyboardProfiles.copy(real, 2, 1) // (a copy keeps it a pick)
        assertEquals(default, real.getInt(own(1, longPress), -1))
        // "Default" makes it follow again
        view(2).edit().remove(longPress).commit()
        assertEquals(changedDefault, view(2).getInt(longPress, changedDefault))
        assertEquals(emptyList(), marks())
    }

    // (c)
    @Test fun `the upgrade removes every old mark once, the second run changes nothing`() {
        real.edit()
            .putBoolean(mark(1, longPress), true).putBoolean(mark(2, gap), true).putInt(own(2, longPress), 400)
            .putString(own(2, Settings.PREF_USER_COLORS_PREFIX + "Warm"), "[w]") // (no mark: a palette copy, the move's)
            .putInt(Settings.PREF_VERSION_CODE, BuildConfig.MIGRATION_VERSION)
            .commit()
        checkVersionUpgrade(ctx)
        assertEquals(emptyList(), marks())
        assertEquals(400, real.getInt(own(2, longPress), 0))
        assertTrue(real.getBoolean(KeyboardProfiles.NO_DEFAULT_MARKS_DONE, false))
        val after = HashMap(real.all)
        checkVersionUpgrade(ctx)
        assertEquals(after, HashMap(real.all), "the second run changed something")
        // the step on its own: done once, so a mark brought in later (none is written) is left to it no more
        real.edit().putBoolean(mark(2, longPress), true).commit()
        KeyboardProfiles.removeDefaultMarks(real)
        assertTrue(real.contains(mark(2, longPress)))
        real.edit().remove(KeyboardProfiles.NO_DEFAULT_MARKS_DONE).commit() // (a restored backup from before)
        KeyboardProfiles.removeDefaultMarks(real)
        assertEquals(emptyList(), marks())
        assertEquals(400, real.getInt(own(2, longPress), 0))
    }

    // (d)
    @Test fun `who uses an item - a keyboard with nothing stored chose nothing`() {
        val key = AppearanceLooks.PREF_SELECTED
        fun using(name: String) = KeyboardProfiles.keyboardsUsing(real, listOf(us, fr), listOf(key)) { _, value -> value == name }
        real.edit().putString(own(1, key), "X").commit()
        assertEquals(listOf(us), using("X")) // (French: nothing stored, nothing chosen)
        view(2).edit().putString(key, "X").commit()
        view(2).edit().remove(key).commit() // ("Default" again: nothing stored)
        assertEquals(listOf(us), using("X"))
        real.edit().putBoolean("separate_settings_per_keyboard", false).commit() // French reads US's
        assertEquals(listOf(us, fr), using("X"))
        real.edit().putString(own(2, key), "Y").commit() // a hidden own choice still counts
        assertEquals(listOf(fr), using("Y"))
    }

    // (d)
    @Test fun `the saved-items switch - off writes the remembered value where nothing is stored, on removes it`() {
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(longPress to null), remembered = mapOf(longPress to 300))))
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to null), mapOf(gap to 0.5f))), now = true)
        LayoutPresets.applyToOther(ctx, us, LayoutPresets.load(real).single())
        AppearanceLooks.applyToOther(ctx, us, AppearanceLooks.load(real).single())
        LayoutPresets.applyToOther(ctx, fr, LayoutPresets.load(real).single())
        view(2).edit().putInt(longPress, 400).commit() // (French: a pick of its own)
        assertFalse(real.contains(own(1, longPress)))
        fun flip(on: Boolean) {
            real.edit().putBoolean(SavedItemDefaults.PREF, on).commit()
            SavedItemDefaults.applySwitch(ctx, real, on)
        }
        flip(false)
        assertEquals(300, real.getInt(own(1, longPress), 0))
        assertEquals(0.5f, real.getFloat(own(1, gap), 0f))
        assertEquals(400, real.getInt(own(2, longPress), 0)) // (stored: stays)
        assertEquals(emptyList(), marks())
        flip(true)
        assertFalse(real.contains(own(1, longPress)))
        assertFalse(real.contains(own(1, gap)))
        assertEquals(400, real.getInt(own(2, longPress), 0))
        assertEquals(emptyList(), marks())
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, view(1).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
        // by value: a hand-picked 300 (the remembered one) moves with the switch (owner 2026-10-11)
        view(2).edit().putInt(longPress, 300).commit()
        flip(true)
        assertFalse(real.contains(own(2, longPress)))
    }

    // (e)
    @Test fun `a keyboard restored from a backup lacking a key - the key leaves the target set, no mark`() {
        real.edit().putInt(own(2, longPress), 400).putFloat(own(2, gap), 3f).commit()
        // an older backup: French with its own gap, its long press marked at the default
        val backup = mapOf<String, Any?>("keyboard_profile_ids" to JSONObject().put(fr.toPref(), 7).toString(),
            own(7, gap) to 1f, mark(7, longPress) to true)
        val settings = KeyboardProfiles.effectiveSettingsIn(backup, fr)
        assertEquals(mapOf<String, Any?>(gap to 1f), settings)
        KeyboardProfiles.restoreSet(real, 2, settings)
        assertFalse(real.contains(own(2, longPress)), "the phone's long press stayed")
        assertEquals(1f, real.getFloat(own(2, gap), 0f))
        assertEquals(emptyList(), marks())
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, view(2).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
        // partly: only the menus taken; a key of another menu stays
        real.edit().putInt(own(2, longPress), 400).commit()
        KeyboardProfiles.restoreSet(real, 2, mapOf(gap to 2f)) { it == gap }
        assertEquals(400, real.getInt(own(2, longPress), 0))
        assertEquals(2f, real.getFloat(own(2, gap), 0f))
    }
}

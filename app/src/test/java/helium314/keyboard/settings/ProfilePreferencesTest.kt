// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A keyboard's own settings set: "Default" there means the default, and copying a set copies what it reads as. */
@RunWith(RobolectricTestRunner::class)
class ProfilePreferencesTest {
    private lateinit var real: android.content.SharedPreferences

    @Before fun setUp() {
        real = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("profile_test", Context.MODE_PRIVATE)
        real.edit().clear().putBoolean("separate_settings_per_keyboard", true).commit()
    }

    private fun set(id: Int) = ProfilePreferences(real) { id }

    // "Advanced learning and swiping" is the same for every keyboard: once, the keyboard in use gives its values
    @Test fun learningSwipingBecomesCommonWithTheKeyboardInUsesValues() {
        val turn = helium314.keyboard.latin.settings.Settings.PREF_GESTURE_TURN_WEIGHT
        val trust = helium314.keyboard.latin.settings.Settings.PREF_TRUST_TYPED_COUNT
        val inUse = KeyboardProfiles.idFor(real, KeyboardProfiles.selectedKeyboard(real))
        val other = inUse + 1
        real.edit().putFloat(turn, 0.5f).putFloat("p$inUse/$turn", 1.2f).putFloat("p$other/$turn", 0.1f)
            .putInt("p$inUse/$trust", 7).commit()
        KeyboardProfiles.migrateLearningSwiping(real)
        assertEquals(1.2f, real.getFloat(turn, 0f))
        assertEquals(7, real.getInt(trust, 0))
        assertFalse(real.contains("p$inUse/$turn"))
        assertFalse(real.contains("p$other/$turn"))
        // from now on every keyboard reads and writes the common value
        set(other).edit().putFloat(turn, 0.9f).commit()
        assertEquals(0.9f, set(inUse).getFloat(turn, 0f))
        assertEquals(0.9f, real.getFloat(turn, 0f))
    }

    @Test fun removedInOwnSetReadsDefaultNotShared() {
        real.edit().putInt("some_size", 5).commit() // the shared set
        val own = set(1)
        assertEquals(5, own.getInt("some_size", 1)) // nothing of its own: the shared value
        own.edit().putInt("some_size", 7).commit()
        assertEquals(7, own.getInt("some_size", 1))
        own.edit().remove("some_size").commit() // "Default"
        assertEquals(1, own.getInt("some_size", 1))
        assertFalse(own.contains("some_size"))
        assertFalse(own.all.containsKey("some_size"))
        own.edit().putInt("some_size", 3).commit() // set again
        assertEquals(3, own.getInt("some_size", 1))
        assertEquals(5, set(KeyboardProfiles.SHARED).getInt("some_size", 1)) // the shared set untouched
    }

    @Test fun restoredSetReadsBackupDefaultsNotPhoneShared() {
        // the phone's shared set has auto-correct off; the backup's keyboard left it at its default (not stored)
        real.edit().putBoolean("auto_correction", false).putInt("theme_size", 3).putBoolean("fonts_follow_migrated", true).commit()
        KeyboardProfiles.write(real, 1, mapOf("theme_size" to 5), markDefaults = true)
        val own = set(1)
        assertEquals(true, own.getBoolean("auto_correction", true)) // the default, not the phone's shared false
        assertEquals(5, own.getInt("theme_size", 0)) // the backup's value
        assertEquals(true, own.getBoolean("fonts_follow_migrated", false)) // an upgrade flag is never marked
        assertEquals(false, set(KeyboardProfiles.SHARED).getBoolean("auto_correction", true)) // the shared set untouched
    }

    @Test fun backupWritesDefaultsOut() {
        val defaults = SettingDefaults.all
        assert(defaults.size > 100) { "only ${defaults.size} defaults found" }
        assertEquals(helium314.keyboard.latin.settings.Defaults.PREF_AUTO_CORRECTION, defaults[helium314.keyboard.latin.settings.Settings.PREF_AUTO_CORRECTION])
        assertFalse(defaults.containsKey(helium314.keyboard.latin.settings.Settings.PREF_KEY_TEXT_BOLD)) // its absence means something
        // the sizes, one key per screen state, are written out too (saved Layouts and themes use the same table)
        val D = helium314.keyboard.latin.settings.Defaults
        for (i in 0 until 4) assertEquals(D.PREF_KEYBOARD_HEIGHT_SCALE[i],
            defaults[helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(helium314.keyboard.latin.settings.Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, i, 2)])
        for (i in 0 until 4) assertEquals(D.PREF_BOTTOM_PADDING_SCALE[i],
            defaults[helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(helium314.keyboard.latin.settings.Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, i, 2)])
        for (i in 0 until 8) assertEquals(D.PREF_SIDE_PADDING_SCALE[i],
            defaults[helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(helium314.keyboard.latin.settings.Settings.PREF_SIDE_PADDING_SCALE_PREFIX, i, 3)])
        // a renamed Layout is still the one tapped in the list
        val values = mapOf<String, Any?>(helium314.keyboard.latin.settings.Settings.PREF_SHOW_NUMBER_ROW to true)
        assertEquals(LayoutPresets.Preset("Old", values), LayoutPresets.Preset("New", HashMap(values)))
        val ac = helium314.keyboard.latin.settings.Settings.PREF_AUTO_CORRECTION
        val out = SettingDefaults.explicit(mapOf("p3/~$ac" to true))
        assertEquals(defaults[ac], out[ac]) // the shared set's default written out
        assertEquals(defaults[ac], out["p3/$ac"]) // keyboard 3's mark became its value
        assertFalse(out.containsKey("p3/~$ac"))
    }

    @Test fun layoutPresetsKeepTheirValuesAndSkipPopups() {
        assert(LayoutPresets.inScope(helium314.keyboard.latin.settings.Settings.PREF_SHOW_NUMBER_ROW))
        assert(LayoutPresets.inScope(helium314.keyboard.latin.settings.Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX + "_0"))
        assert(LayoutPresets.inScope(helium314.keyboard.latin.settings.Settings.PREF_SYMBOL_POPUP_MAP)) // popups are in Layouts too
        assertFalse(LayoutPresets.inScope("key_popup_sets")) // the saved popup sets stay app-wide
        // a Layout saved before popups were in Layouts (no script) leaves a keyboard's popups alone
        val en = helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "")
        val old = LayoutPresets.Preset("Old", mapOf("key_popups" to "{}", helium314.keyboard.latin.settings.Settings.PREF_SHOW_NUMBER_ROW to true))
        assertFalse(LayoutPresets.settingsFor(en, old).containsKey("key_popups"))
        assert(LayoutPresets.settingsFor(en, old).containsKey(helium314.keyboard.latin.settings.Settings.PREF_SHOW_NUMBER_ROW))
        assertFalse(LayoutPresets.inScope(helium314.keyboard.latin.settings.Settings.PREF_ENABLE_CLIPBOARD_HISTORY)) // app-wide
        val preset = LayoutPresets.Preset("Mine", mapOf(helium314.keyboard.latin.settings.Settings.PREF_SHOW_NUMBER_ROW to true, helium314.keyboard.latin.settings.Settings.PREF_VIBRATE_ON to null,
            "layout:MAIN" to "qwertz", "layoutText:SYMBOLS" to "a b\nc"))
        LayoutPresets.save(real, listOf(preset))
        val back = LayoutPresets.load(real).single()
        assertEquals("Mine", back.name)
        assertEquals(preset.values, back.values.filterKeys { it in preset.values }) // null (at its default) and the custom keys' text survive
        // a Layout without the sizes (saved before the defaults were written out) puts them back to their default
        assertEquals(helium314.keyboard.latin.settings.Defaults.PREF_KEYBOARD_HEIGHT_SCALE[0],
            back.values[helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(helium314.keyboard.latin.settings.Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, 0, 2)])
    }

    @Test fun keyboardBecomingAnotherKeepsBothSets() {
        val qwerty = helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "KeyboardLayoutSet=MAIN:qwerty")
        val colemak = helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "KeyboardLayoutSet=MAIN:colemak")
        val q = KeyboardProfiles.idFor(real, qwerty); val c = KeyboardProfiles.idFor(real, colemak)
        set(q).edit().putInt("theme_size", 1).commit(); set(c).edit().putInt("theme_size", 2).commit()
        KeyboardProfiles.onKeyboardChanged(real, qwerty, colemak) // previewing Colemak on the QWERTY keyboard
        KeyboardProfiles.onKeyboardChanged(real, colemak, qwerty) // Cancel
        assertEquals(q, KeyboardProfiles.idFor(real, qwerty)); assertEquals(c, KeyboardProfiles.idFor(real, colemak))
        assertEquals(1, set(q).getInt("theme_size", 0)); assertEquals(2, set(c).getInt("theme_size", 0)) // both sets kept
    }

    @Test fun copyTakesOwnValuesOverShared() {
        real.edit().putInt("a", 1).putInt("b", 2).putInt("c", 3).commit()
        set(1).edit().putInt("a", 10).remove("c").commit()
        KeyboardProfiles.copy(real, 1, 2)
        val copy = set(2)
        assertEquals(10, copy.getInt("a", 0)) // its own, not the shared 1 (before: either, by file order)
        assertEquals(2, copy.getInt("b", 0)) // the shared one it read
        assertEquals(0, copy.getInt("c", 0)) // at its default in the source: at its default in the copy
        KeyboardProfiles.copy(real, 1, KeyboardProfiles.SHARED) // separate settings off, keyboard 1's become shared
        val shared = set(KeyboardProfiles.SHARED)
        assertEquals(10, shared.getInt("a", 0))
        assertEquals(0, shared.getInt("c", 0))
        assertFalse(real.all.keys.any { it.startsWith(KeyboardProfiles.TOMBSTONE) }) // no mark in the shared set
    }

    @Test fun ownSetListsSharedFallbacksAndOwnValueBeatsStaleMark() {
        real.edit().putInt("x", 4).putInt("y", 9).commit()
        val own = set(1)
        assertEquals(4, own.all["x"]) // read through to the shared value, so a draft's snapshot has it
        own.edit().remove("y").commit()
        assertFalse(own.all.containsKey("y"))
        // a value written straight into the file (Layout & Typing's Discard does) next to a mark left behind
        real.edit().putInt("p1/y", 6).commit()
        assertEquals(6, own.getInt("y", 0))
        assertEquals(6, own.all["y"])
        KeyboardProfiles.copy(real, 1, 3)
        assertEquals(6, set(3).getInt("y", 0))
    }

    @Test fun hotWordKeepsCapitals() {
        val hw = helium314.keyboard.latin.utils.HotWords
        val dict = helium314.keyboard.latin.dictionary.Dictionary.DICTIONARY_USER_TYPED
        hw.clear()
        repeat(3) { hw.onWordCommitted("hello") }
        assertEquals("Hello", hw.matching("Hel", dict).first().mWord) // sentence start
        assertEquals("hello", hw.matching("hel", dict).first().mWord)
        assertEquals("HELLO", hw.matching("HEL", dict).first().mWord)
        hw.clear()
        repeat(3) { hw.onWordCommitted("Curmudgeon") }
        assertEquals("Curmudgeon", hw.matching("cur", dict).first().mWord) // a name keeps its capital
        hw.clear()
        repeat(3) { hw.onWordCommitted("i'm") }
        assertEquals("I'm", hw.matching("I'", dict).first().mWord) // one capital, not caps lock
        hw.clear()
    }
}

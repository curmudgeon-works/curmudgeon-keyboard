// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A keyboard's own settings set: "Default" there means the default, and copying a set copies what it reads as.
 *  (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones): keyboard 1 is the first in the list, the main one. */
@RunWith(RobolectricTestRunner::class)
class ProfilePreferencesTest {
    private lateinit var real: android.content.SharedPreferences

    @Before fun setUp() {
        real = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("profile_test", Context.MODE_PRIVATE)
        // keyboards 1, 2 and 3, in that order: 1 is the main keyboard
        val keyboards = listOf(java.util.Locale.US, java.util.Locale.FRANCE, java.util.Locale.GERMANY).map { helium314.keyboard.latin.settings.SettingsSubtype(it, "") }
        real.edit().clear().putBoolean("separate_settings_per_keyboard", true)
            .putString(helium314.keyboard.latin.settings.Settings.PREF_ENABLED_SUBTYPES, helium314.keyboard.latin.utils.SubtypeSettings.createPrefSubtypes(keyboards))
            .putString("keyboard_profile_ids", org.json.JSONObject(keyboards.withIndex().associate { (i, k) -> k.toPref() to i + 1 }).toString())
            .putInt("keyboard_profile_next_id", 4).commit()
        KeyboardProfiles.loadGroups(real) // the menus back to their defaults (only Refine swipe and learning shared)
    }

    @org.junit.After fun tearDown() {
        real.edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
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
        // from now on every keyboard reads and writes the common value: the main keyboard's (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones)
        set(other).edit().putFloat(turn, 0.9f).commit()
        assertEquals(0.9f, set(inUse).getFloat(turn, 0f))
        assertEquals(0.9f, real.getFloat("p1/$turn", 0f))
    }

    @Test fun removedInOwnSetReadsDefaultNotShared() {
        real.edit().putInt("p1/some_size", 5).commit() // the main keyboard's
        val own = set(2)
        assertEquals(1, own.getInt("some_size", 1)) // nothing of its own: the default (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones)
        own.edit().putInt("some_size", 7).commit()
        assertEquals(7, own.getInt("some_size", 1))
        own.edit().remove("some_size").commit() // "Default"
        assertEquals(1, own.getInt("some_size", 1))
        assertFalse(own.contains("some_size"))
        assertFalse(own.all.containsKey("some_size"))
        own.edit().putInt("some_size", 3).commit() // set again
        assertEquals(3, own.getInt("some_size", 1))
        assertEquals(5, set(KeyboardProfiles.SHARED).getInt("some_size", 1)) // the main keyboard's untouched
    }

    @Test fun restoredSetReadsBackupDefaultsNotPhoneShared() {
        // the main keyboard has auto-correct off; the backup's keyboard left it at its default (not stored)
        real.edit().putBoolean("p1/auto_correction", false).putInt("p1/theme_size", 3).putBoolean("fonts_follow_migrated", true).commit()
        KeyboardProfiles.restoreSet(real, 2, mapOf("theme_size" to 5))
        val own = set(2)
        assertEquals(true, own.getBoolean("auto_correction", true)) // the default, not the main keyboard's false
        assertEquals(5, own.getInt("theme_size", 0)) // the backup's value
        assertEquals(true, own.getBoolean("fonts_follow_migrated", false)) // an upgrade flag is never marked
        assertEquals(false, set(KeyboardProfiles.SHARED).getBoolean("auto_correction", true)) // the main keyboard's untouched
    }

    @Test fun defaultsTable() {
        val defaults = SettingDefaults.all
        assert(defaults.size > 100) { "only ${defaults.size} defaults found" }
        assertEquals(helium314.keyboard.latin.settings.Defaults.PREF_AUTO_CORRECTION, defaults[helium314.keyboard.latin.settings.Settings.PREF_AUTO_CORRECTION])
        assertFalse(defaults.containsKey(helium314.keyboard.latin.settings.Settings.PREF_SUGGESTION_TEXT_COLOR)) // its absence means something (the trail colour)
        assertEquals(false, defaults[helium314.keyboard.latin.settings.Settings.PREF_KEY_TEXT_BOLD]) // not set = not bold (2026-10-06)
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
        // a Layout without the sizes (saved before 2026-10-03) has them "not set": applying it puts them back to the default
        val height = helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings(helium314.keyboard.latin.settings.Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, 0, 2)
        assert(back.values.containsKey(height))
        assertEquals(null, back.values[height])
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
        // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones)
        real.edit().putInt("p1/a", 1).putInt("p1/b", 2).putInt("p1/c", 3).commit()
        real.edit().putInt("p2/a", 10).putInt("p2/b", 20).commit()
        set(2).edit().remove("c").commit()
        KeyboardProfiles.copy(real, 2, 3)
        val copy = set(3)
        assertEquals(10, copy.getInt("a", 0)) // its own, not the main keyboard's 1
        assertEquals(20, copy.getInt("b", 0))
        assertEquals(0, copy.getInt("c", 0)) // at its default in the source: at its default in the copy
        // a shared menu's settings: what the source reads, the main keyboard's
        real.edit().putBoolean("p1/$autoCap", false).putBoolean("p2/$autoCap", true).commit()
        KeyboardProfiles.setGroupShared(real, correction, true)
        KeyboardProfiles.copy(real, 2, 3)
        assertFalse(real.getBoolean("p3/$autoCap", true))
        KeyboardProfiles.copy(real, KeyboardProfiles.SHARED, 3) // the common settings: the main keyboard's
        assertEquals(1, set(3).getInt("a", 0))
        assertEquals(3, set(3).getInt("c", 0))
    }

    @Test fun ownSetListsSharedFallbacksAndOwnValueBeatsStaleMark() {
        real.edit().putInt("p1/x", 4).putInt("p2/y", 9).commit()
        val own = set(2)
        assertFalse(own.all.containsKey("x")) // another set's value is no fallback (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones)
        own.edit().remove("y").commit()
        assertFalse(own.all.containsKey("y"))
        // a value written straight into the file (Layout & Typing's Discard does) next to a mark left behind
        real.edit().putInt("p2/y", 6).commit()
        assertEquals(6, own.getInt("y", 0))
        assertEquals(6, own.all["y"])
        KeyboardProfiles.copy(real, 2, 3)
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

    // ---- per keyboard by menu (App settings > Per keyboard) ----
    private val autoCap = helium314.keyboard.latin.settings.Settings.PREF_AUTO_CAP // Text correction only
    private val spaceAfterSwipe = helium314.keyboard.latin.settings.Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING // Swipe and Text correction
    private val turn = helium314.keyboard.latin.settings.Settings.PREF_GESTURE_TURN_WEIGHT // Refine swipe and learning
    private val correction = KeyboardProfiles.Group.TEXT_CORRECTION

    // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones): a shared menu reads the main keyboard's set (keyboard 1), no keyboard is picked
    @Test fun aMenuMadeSharedTakesTheChosenKeyboardsValuesAndKeepsTheOwnCopies() {
        real.edit().putBoolean("p1/$autoCap", false).putBoolean("p2/$autoCap", true).commit()
        assertFalse(KeyboardProfiles.isGlobal(autoCap))
        KeyboardProfiles.setGroupShared(real, correction, true)
        assertTrue(KeyboardProfiles.isGlobal(autoCap))
        assertFalse(set(2).getBoolean(autoCap, true)) // keyboard 1's value
        // (owner 2026-10-11: sharing a menu keeps each keyboard's own) stored, unread while shared
        assertTrue(real.getBoolean("p2/$autoCap", false))
        assertFalse(real.contains(autoCap))
        set(3).edit().putBoolean(autoCap, true).commit() // one set for all now: the main keyboard's
        assertTrue(set(2).getBoolean(autoCap, false))
        assertTrue(real.getBoolean("p1/$autoCap", false))
        assertFalse(real.contains("p3/$autoCap"))
    }

    @Test fun theChosenKeyboardAtItsDefaultLeavesTheSharedValueAtItsDefault() {
        real.edit().putBoolean("p2/$autoCap", false).putBoolean("p1/~$autoCap", true).commit() // keyboard 1: "Default"
        KeyboardProfiles.setGroupShared(real, correction, true)
        assertTrue(set(2).getBoolean(autoCap, true)) // the main keyboard's: the default
        assertTrue(real.contains("p2/$autoCap")) // (owner 2026-10-11: sharing a menu keeps each keyboard's own)
    }

    @Test fun aMenuBackToPerKeyboardStartsEachKeyboardFromTheSharedValues() {
        KeyboardProfiles.setGroupShared(real, correction, true)
        set(2).edit().putBoolean(autoCap, false).commit() // (the main keyboard's set)
        KeyboardProfiles.setGroupShared(real, correction, false)
        assertFalse(KeyboardProfiles.isGlobal(autoCap))
        assertFalse(set(1).getBoolean(autoCap, true)) // the main keyboard keeps the edit: it was its own
        assertTrue(set(2).getBoolean(autoCap, true)) // keyboard 2 its own again: nothing, the default
    }

    @Test fun aSettingOnTwoMenusBelongsToThePerKeyboardOne() {
        KeyboardProfiles.setGroupShared(real, correction, true)
        // Swipe still per keyboard: the setting stays each keyboard's own, and shared Text correction doesn't show it
        assertFalse(KeyboardProfiles.isGlobal(spaceAfterSwipe))
        assertTrue(KeyboardProfiles.hiddenOn(real, correction, spaceAfterSwipe))
        assertFalse(KeyboardProfiles.hiddenOn(real, KeyboardProfiles.Group.SWIPE, spaceAfterSwipe))
        assertFalse(KeyboardProfiles.hiddenOn(real, correction, autoCap)) // only on Text correction: shown
        // both shared: one value, shown on both
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.SWIPE, true)
        assertTrue(KeyboardProfiles.isGlobal(spaceAfterSwipe))
        assertFalse(KeyboardProfiles.hiddenOn(real, correction, spaceAfterSwipe))
        // without separate settings nothing is hidden
        real.edit().putBoolean("separate_settings_per_keyboard", false).commit()
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.SWIPE, false)
        assertFalse(KeyboardProfiles.hiddenOn(real, correction, spaceAfterSwipe))
    }

    @Test fun refineSwipeAndLearningIsSharedUntilMadePerKeyboard() {
        assertTrue(KeyboardProfiles.isGlobal(turn))
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.REFINE, false)
        assertFalse(KeyboardProfiles.isGlobal(turn))
        set(1).edit().putFloat(turn, 0.3f).commit()
        assertEquals(0.3f, real.getFloat("p1/$turn", 0f))
    }
}

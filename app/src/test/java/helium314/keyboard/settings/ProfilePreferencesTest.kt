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

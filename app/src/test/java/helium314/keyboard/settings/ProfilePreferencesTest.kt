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
}

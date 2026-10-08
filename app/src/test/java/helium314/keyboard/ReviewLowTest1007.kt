// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.HotWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.SettingsMode
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Low items b to f of the partial review of 2026-10-07 (a is the screen's undo working as designed). */
@RunWith(RobolectricTestRunner::class)
class ReviewLowTest1007 {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); HotWords.usePool(KeyboardProfiles.SHARED); HotWords.clear() }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); HotWords.usePool(KeyboardProfiles.SHARED); HotWords.clear() }

    // b: a word typed three times on one keyboard isn't "hot" on a keyboard with its own learned words
    @Test fun `frequently typed words belong to the learned words in use`() {
        HotWords.usePool(1)
        repeat(3) { HotWords.onWordCommitted("namaste") }
        assertEquals(listOf("namaste"), HotWords.matching("nam", Dictionary.DICTIONARY_USER_TYPED).map { it.mWord })
        HotWords.usePool(2)
        assertTrue(HotWords.matching("nam", Dictionary.DICTIONARY_USER_TYPED).isEmpty())
        HotWords.usePool(1) // back: they are still there
        assertEquals(1, HotWords.matching("nam", Dictionary.DICTIONARY_USER_TYPED).size)
    }

    // c: a deleted keyboard isn't remembered for apps any more
    @Test fun `deleting a keyboard forgets it for each app`() {
        val gone = SettingsSubtype(Locale.US, "MainLayout=dvorak")
        val kept = SettingsSubtype(Locale.US, "")
        real.edit {
            putString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "com.a", gone.toPref())
            putString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "com.b", kept.toPref())
        }
        KeyboardProfiles.onKeyboardDeleted(real, gone)
        assertFalse(real.contains(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "com.a"))
        assertEquals(kept.toPref(), real.getString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "com.b", null))
    }

    // d: the keyboard in use deleted while the keyboard isn't running: the next one is selected, not a deleted one
    @Test fun `deleting the keyboard in use selects another even with the keyboard closed`() {
        val a = SettingsSubtype(Locale.US, "")
        val b = SettingsSubtype(Locale.GERMANY, "")
        real.edit {
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
        }
        SubtypeSettings.removeEnabledSubtypeFromPrefs(real, a)
        assertEquals(b.toPref(), real.getString(Settings.PREF_SELECTED_SUBTYPE, null))
    }

    // f: the Advanced switch follows the stored value (a restore or a reset writes it)
    @Test fun `the Advanced switch follows a restored value`() {
        val state = SettingsMode.state(ctx)
        real.edit { putBoolean(Settings.PREF_ADVANCED_SETTINGS, !state.value) }
        SettingsMode.sync(ctx)
        assertEquals(real.getBoolean(Settings.PREF_ADVANCED_SETTINGS, Defaults.PREF_ADVANCED_SETTINGS), state.value)
        real.edit { remove(Settings.PREF_ADVANCED_SETTINGS) } // a factory reset
        SettingsMode.sync(ctx)
        assertEquals(Defaults.PREF_ADVANCED_SETTINGS, state.value)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Fine-tune learning" split from Refine (2026-10-10): its own group, and phones that kept Refine per keyboard keep
 *  the learning settings per keyboard. */
@RunWith(RobolectricTestRunner::class)
class FineTuneLearningSplitTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    @Test fun `trusting your words is on Fine-tune learning, the swipe tuning stays on Fine-tune suggestions`() {
        for (key in listOf(Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT)) {
            assertEquals(setOf(KeyboardProfiles.Group.LEARNING), KeyboardProfiles.groupsOf(key))
        }
        for (key in listOf(Settings.PREF_GESTURE_TURN_WEIGHT, Settings.PREF_SUGGESTION_COUNT, Settings.PREF_SUGGESTION_RULES))
            assertEquals(setOf(KeyboardProfiles.Group.REFINE), KeyboardProfiles.groupsOf(key))
    }

    @Test fun `a keyboard that kept Refine to itself keeps its own trust count after the split`() {
        real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putBoolean(KeyboardProfiles.Group.REFINE.prefKey, false)
            .putInt(Settings.PREF_TRUST_TYPED_COUNT, 3) // the shared value
            .putInt("p2/" + Settings.PREF_TRUST_TYPED_COUNT, 7) // keyboard 2's own
            .commit()
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.splitLearningFromRefine(real)
        assertFalse(KeyboardProfiles.isShared(real, KeyboardProfiles.Group.LEARNING))
        assertEquals(7, ProfilePreferences(real) { 2 }.getInt(Settings.PREF_TRUST_TYPED_COUNT, 0))
        assertTrue(real.getBoolean("learning_group_split_done", false))
    }

    @Test fun `with Refine shared (or never chosen) Fine-tune learning is shared and nothing is written for it`() {
        KeyboardProfiles.splitLearningFromRefine(real)
        assertFalse(real.contains(KeyboardProfiles.Group.LEARNING.prefKey)) // stored = picked: no default written
        assertTrue(KeyboardProfiles.isShared(real, KeyboardProfiles.Group.LEARNING))
        real.edit().clear().putBoolean(KeyboardProfiles.Group.REFINE.prefKey, true).commit()
        KeyboardProfiles.splitLearningFromRefine(real)
        assertTrue(KeyboardProfiles.isShared(real, KeyboardProfiles.Group.LEARNING))
    }

    @Test fun `the split runs once, so a later choice for Fine-tune learning stays`() {
        real.edit().putBoolean(KeyboardProfiles.Group.REFINE.prefKey, false).commit()
        KeyboardProfiles.splitLearningFromRefine(real)
        real.edit().putBoolean(KeyboardProfiles.Group.LEARNING.prefKey, true).commit()
        KeyboardProfiles.splitLearningFromRefine(real)
        assertTrue(KeyboardProfiles.isShared(real, KeyboardProfiles.Group.LEARNING))
    }
}

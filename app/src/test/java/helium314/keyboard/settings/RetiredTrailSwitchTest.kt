// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.swipeSettingKeys
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-7 review 20261013_L5_3: the retired "Show full swipe trail" switch (replaced by trail thickness 0, AppUpgrade)
 * was still in the app's settings list and the Swipe screen's search scope, drawn by no screen, and still read: switched
 * off from a search, it turned the trail off. Now it's gone from both, and the trail is off at thickness 0 only.
 */
@RunWith(RobolectricTestRunner::class)
class RetiredTrailSwitchTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SettingsActivity.settingsContainer = SettingsContainer(ctx)
    }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    // fail before: the Swipe screen's search (and the main screen's) found it by its title
    @Test fun `the Swipe screen's search no longer finds the retired trail switch`() {
        val term = "full swipe trail"
        val swipe = screenSearch(term, screenSettings(swipeSettingKeys, real, null)).map { it.key }
        assertFalse(Settings.PREF_GESTURE_PREVIEW_TRAIL in swipe, "$swipe")
        assertFalse(Settings.PREF_GESTURE_PREVIEW_TRAIL in SettingsActivity.settingsContainer.filter(term, ctx).map { it.key })
        assertTrue(SettingsActivity.settingsContainer[Settings.PREF_GESTURE_PREVIEW_TRAIL] == null)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.reviveComposeMainDispatcher
import helium314.keyboard.settings.screens.AdvancedSettingsScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * K1 placement (owner 2026-10-13: "K1 log settings changes should be in app settings"): the "Log settings changes"
 * switch is on App settings, always shown; before, it was among the swipe logs on Learning & swiping, shown only while
 * swiping was on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp") // (tall: every row on screen)
class LogSettingsPlacementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setUp() { reviveComposeMainDispatcher() }
    @After fun tearDown() {
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // fail before: App settings had no such row
    @Test fun `App settings shows Log settings changes`() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { AdvancedSettingsScreen({}) } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(ctx.getString(R.string.log_settings_changes)).fetchSemanticsNodes().isNotEmpty())
    }
}

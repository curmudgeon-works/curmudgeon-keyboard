// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Owner 2026-10-25: "(1) can you sort of highlight the curmudgeon rows after screen 1 and screen 2, you know how android
 * 'blinks' some rows to draw attention? (2) clicking on third tile takes us to full settings screen, but going back
 * doesn't bring us back to the setup -- I want this to happen". (1) Enable in Settings opens Android's keyboard list
 * naming our row to highlight (the picker of screen 2 is a system dialog, nothing to name). (2) the All settings tile
 * opens the settings with the setup behind them; Back from their first screen is the setup again, not the end of the
 * activity. Fail before: (1) no row named; (2) the activity finishing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class WelcomeWizardSetupTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val ourId get() = ComponentName(ctx, LatinIME::class.java).flattenToString()

    @Before fun setUp() {
        helium314.keyboard.reviveComposeMainDispatcher()
        helium314.keyboard.ShadowInputMethodManager2.ownKeyboardEnabled = false
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, "")
    }
    @After fun tearDown() {
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun launch(): ActivityScenario<SettingsActivity> {
        val s = ActivityScenario.launch(SettingsActivity::class.java)
        scenario = s
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        return s
    }
    private fun text(id: Int, vararg args: Any) = ctx.getString(id, *args)
    private val appName get() = ctx.getString(ctx.applicationInfo.labelRes)

    @Test fun `screen 1 - Enable in Settings opens Android's keyboard list with our row named for its highlight`() {
        val s = launch()
        compose.onNodeWithText(text(R.string.setup_welcome_title, appName)).assertExists()
        compose.onNodeWithText(text(R.string.setup_step1_action)).performClick()
        compose.waitForIdle()
        var started: android.content.Intent? = null
        s.onActivity { started = shadowOf(it).nextStartedActivity }
        val intent = assertNotNull(started, "Android's keyboard settings opened")
        assertEquals(Settings.ACTION_INPUT_METHOD_SETTINGS, intent.action)
        assertEquals(ourId, intent.getStringExtra(":settings:fragment_args_key"), "our row named: ${intent.extras}")
    }

    @Test fun `screen 3 - All settings, then Back - the setup again, not the end of the activity`() {
        // the setup comes up on a first run (the keyboard not enabled); enabled and in use while it is open, it moves
        // on to 3 when the screen comes back (its resume look)
        val s = launch()
        compose.onNodeWithText(text(R.string.setup_welcome_title, appName)).assertExists()
        helium314.keyboard.ShadowInputMethodManager2.ownKeyboardEnabled = true
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, ourId)
        s.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        s.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        val setupTitle = text(R.string.setup_steps_title, appName)
        compose.onNodeWithText(setupTitle).assertExists()
        // (the third tile: swiped to)
        compose.onNodeWithText(text(R.string.setup_all_settings)).performScrollTo().performClick()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithText(setupTitle).assertDoesNotExist()
        // Back from the settings' first screen
        s.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        var finishing = true
        s.onActivity { finishing = it.isFinishing }
        assertFalse(finishing, "the activity stays")
        compose.onNodeWithText(setupTitle).assertExists()
        compose.onNodeWithText(text(R.string.setup_all_settings)).assertExists()
    }
}

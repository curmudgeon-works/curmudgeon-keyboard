// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The App settings switch "Leaving a screen keeps your changes" (owner 2026-10-14: "yes, do add switch. App starts with
 * switcch on yes. Advanced only."), on a screen with a [PrefsDraft] (Swipe, Text correction, ...): on, Back keeps the
 * changes without asking and the tick saves without asking; off, both ask as before. Appearance, Layout & typing and
 * the editors opened from them: SubScreenSaveRealPathTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowInputMethodManager2::class]) // (tall: every row on screen)
class LeavingKeepsChangesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val key = Settings.PREF_AUTO_CORRECTION
    private val name = "leavetest"
    private var left = false

    @Before fun setUp() {
        reviveComposeMainDispatcher()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        left = false
    }
    @After fun tearDown() {
        scenario?.close()
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        PrefsDraft.close(name)
        SettingsMode.set(ctx, Defaults.PREF_ADVANCED_SETTINGS)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun idle() = compose.waitForIdle()
    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // on by default, and the default is never written down (stored = picked)
    @Test fun `on by default, nothing stored`() {
        assertTrue(leavingKeepsChanges(ctx))
        assertFalse(real.contains(Settings.PREF_LEAVING_KEEPS_CHANGES))
        assertTrue(KeyboardProfiles.isAppWide(Settings.PREF_LEAVING_KEEPS_CHANGES)) // the app's, not a keyboard's
    }

    // fail before: App settings had no such row
    @Test fun `App settings shows the switch in advanced mode only`() {
        SettingsMode.set(ctx, false)
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { AdvancedSettingsScreen({}) } }
        idle()
        assertFalse(shows(ctx.getString(R.string.leaving_keeps_changes)), "shown in simple mode")
        assertTrue(shows(ctx.getString(R.string.log_settings_changes)), "the other App settings rows went with it")
        scenario!!.onActivity { SettingsMode.set(it, true) }
        idle()
        assertTrue(shows(ctx.getString(R.string.leaving_keeps_changes)), "not shown in advanced mode")
    }

    /** A screen with a [PrefsDraft] of [key]: its tick and cross, its dialogs; Back leaves through it. */
    private fun screen() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { act -> act.setContent {
            @Suppress("UNUSED_VARIABLE") val redraw = act.prefChanged.collectAsState().value // (redrawn on every setting change)
            val ui = rememberPrefsDraft(name, listOf(key)) { left = true }
            Column {
                Row { ui.topActions(this) }
                ui.dialogs()
            }
        } }
        idle()
    }
    private fun change() { ctx.prefs().edit().putBoolean(key, false).commit(); scenario!!.onActivity { it.prefChanged() }; idle() }
    private fun systemBack() { scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; idle() }
    private fun tick() { compose.onNodeWithContentDescription(ctx.getString(R.string.appearance_accept)).performClick(); idle() }

    @Test fun `switch on - Back keeps the change without asking`() {
        screen()
        change()
        systemBack()
        assertFalse(shows(ctx.getString(R.string.unsaved_changes_title)), "Back asked")
        assertTrue(left)
        assertFalse(PrefsDraft.anyOpen())
        assertEquals(false, ctx.prefs().getBoolean(key, true))
    }

    @Test fun `switch on - the tick saves without asking, the cross still asks`() {
        screen()
        change()
        compose.onNodeWithContentDescription(ctx.getString(R.string.appearance_reject)).performClick(); idle()
        assertTrue(shows(ctx.getString(R.string.appearance_reject_title)), "Discard didn't ask")
        compose.onNodeWithText(ctx.getString(R.string.appearance_keep_working)).performClick(); idle()
        tick()
        assertFalse(shows(ctx.getString(R.string.appearance_accept_title)), "Save asked")
        assertFalse(PrefsDraft.of(ctx, name, listOf(key)).hasChanges(ctx)) // (kept: the new starting point)
        assertEquals(false, ctx.prefs().getBoolean(key, true))
    }

    @Test fun `switch off - Back and the tick ask, as before`() {
        real.edit().putBoolean(Settings.PREF_LEAVING_KEEPS_CHANGES, false).commit()
        screen()
        change()
        tick()
        assertTrue(shows(ctx.getString(R.string.appearance_accept_title)), "Save didn't ask")
        compose.onNodeWithText(ctx.getString(R.string.appearance_keep_working)).performClick(); idle()
        systemBack()
        assertTrue(shows(ctx.getString(R.string.unsaved_changes_title)), "Back didn't ask")
        assertFalse(left)
    }
}

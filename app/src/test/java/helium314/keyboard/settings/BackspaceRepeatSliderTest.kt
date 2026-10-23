// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The backspace long press delay row and its dialog with the second slider (2026-10-21, owner: "backspace repeat slider
 * (to be in the same dialog box as the current backspace longpress) 200", "300 ms, 200 ms if you keep holding <- keep
 * this", "Same as longpress need only be for the first delete like we have now"; defaults "change the default to
 * 500/300 for curm").
 */
@RunWith(RobolectricTestRunner::class)
class BackspaceRepeatSliderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    // (the settings screens' view: the keyboard being edited, not the keyboard in use)
    private lateinit var prefs: android.content.SharedPreferences
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setUp() {
        ctx.realPrefs().edit().clear().commit(); KeyboardProfiles.loadGroups(ctx.realPrefs())
        storeOneKeyboard(ctx, ctx.realPrefs())
    }
    @After fun tearDown() { scenario?.close(); ctx.realPrefs().edit().clear().commit(); KeyboardProfiles.loadGroups(ctx.realPrefs()) }

    private fun idle() = compose.waitForIdle()
    private fun showRow(stored: android.content.SharedPreferences.Editor.() -> Unit = { }) {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            // (the view the row itself reads and writes, written before the row reads it)
            val view = androidx.compose.ui.platform.LocalContext.current.prefs()
            androidx.compose.runtime.remember { prefs = view; view.edit(commit = true) { stored() } }
            SettingsActivity.settingsContainer[Settings.PREF_BACKSPACE_LONGPRESS_DELAY]!!.Preference()
        } }
        idle()
    }
    private fun openDialog() { compose.onNodeWithText(ctx.getString(R.string.backspace_longpress_delay)).performClick(); idle() }
    private fun sliders() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(isDialog()))
    private fun ok() { compose.onNodeWithText(ctx.getString(android.R.string.ok)).performClick(); idle() }

    private val first = Defaults.PREF_BACKSPACE_LONGPRESS_DELAY // 500
    private val repeat = Defaults.PREF_BACKSPACE_REPEAT_INTERVAL // 300

    @Test fun `Curmudgeon - the row says 500 ms, 300 ms if you keep holding, the dialog has both sliders`() {
        showRow()
        compose.onNodeWithText("$first ms, $repeat ms if you keep holding").assertExists()
        openDialog()
        compose.onNodeWithText("$first ms for first delete").assertExists()
        compose.onNodeWithText("$repeat ms if you keep holding").assertExists()
        compose.onNodeWithText(ctx.getString(R.string.backspace_delay_follows)).assertExists()
        assertEquals(2, sliders().fetchSemanticsNodes().size)
    }

    @Test fun `moving the second slider stores the repeat`() {
        showRow(); openDialog()
        sliders()[1].performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }; idle()
        compose.onNodeWithText("120 ms if you keep holding").assertExists()
        ok()
        assertEquals(120, prefs.getInt(Settings.PREF_BACKSPACE_REPEAT_INTERVAL, 0))
        assertFalse(prefs.contains(Settings.PREF_BACKSPACE_LONGPRESS_DELAY), "the untouched first slider stores nothing")
        compose.onNodeWithText("$first ms, 120 ms if you keep holding").assertExists()
    }

    @Test fun `an untouched second slider stores nothing (stored = picked)`() {
        showRow(); openDialog()
        sliders()[0].performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }; idle()
        ok()
        assertEquals(400, prefs.getInt(Settings.PREF_BACKSPACE_LONGPRESS_DELAY, 0))
        assertFalse(prefs.contains(Settings.PREF_BACKSPACE_REPEAT_INTERVAL))
    }

    @Test fun `Default puts both back`() {
        showRow { putInt(Settings.PREF_BACKSPACE_LONGPRESS_DELAY, 250); putInt(Settings.PREF_BACKSPACE_REPEAT_INTERVAL, 90) }
        openDialog()
        compose.onNodeWithText(ctx.getString(R.string.button_default)).performClick(); idle()
        compose.onNodeWithText("${Defaults.PREF_BACKSPACE_LONGPRESS_DELAY} ms for first delete").assertExists()
        compose.onNodeWithText("${Defaults.PREF_BACKSPACE_REPEAT_INTERVAL} ms if you keep holding").assertExists()
        ok()
        assertFalse(prefs.contains(Settings.PREF_BACKSPACE_LONGPRESS_DELAY))
        assertFalse(prefs.contains(Settings.PREF_BACKSPACE_REPEAT_INTERVAL))
    }

    @Test fun `HeliBoard - same as long press, 50 ms if you keep holding, the first line shows the long press`() {
        showRow {
            putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 300)
            putInt(Settings.PREF_BACKSPACE_LONGPRESS_DELAY, Defaults.BACKSPACE_DELAY_FOLLOWS)
            putInt(Settings.PREF_BACKSPACE_REPEAT_INTERVAL, 50)
        }
        compose.onNodeWithText("Same as long press, 50 ms if you keep holding").assertExists()
        openDialog()
        compose.onNodeWithText("300 ms for first delete").assertExists() // (no "(same as long press)": the box says it)
        compose.onNodeWithText("50 ms if you keep holding").assertExists()
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.settings.screens.PreviewKeyboard
import helium314.keyboard.settings.screens.TryItBar
import helium314.keyboard.settings.screens.TryItMode
import helium314.keyboard.settings.screens.TryItState
import helium314.keyboard.settings.screens.showKeyboardPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner 2026-10-14: "Can we show this message, but also prevent preview from coming up? And try it here text should
 * change to something appropriate" / "Try it here should say Switch to curmudgeon to preview or some such" / "remove
 * the 5 buttons in the case". While another keyboard is selected in Android ([LocalImeSelected] false), the try-it
 * field brings up no keyboard (no focus, summons do nothing), reads "Switch to Curmudgeon to preview" across the whole
 * bar (no mode buttons), and a tap on it opens Android's keyboard picker; selected again, everything is as before.
 * Fail before: the field took the focus and the keyboard came (compile: no LocalImeSelected).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class TryItNotSelectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val pickerBefore = showKeyboardPicker
    private var pickerOpened = 0
    private var selected by mutableStateOf(false)
    private val state = TryItState()

    @Before fun setUp() {
        helium314.keyboard.reviveComposeMainDispatcher()
        showKeyboardPicker = { pickerOpened++ }
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalImeSelected provides selected) {
                TryItBar(SettingsSubtype(Locale.US, ""), state)
            }
        } }
        compose.waitForIdle()
    }
    @After fun tearDown() {
        showKeyboardPicker = pickerBefore
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val tryHere get() = ctx.getString(R.string.key_popups_try)
    private val switchLabel get() = ctx.getString(R.string.try_it_switch_to_preview)

    @Test fun `not selected - the switch label, no buttons, no keyboard, a tap opens the picker; selected - as before`() {
        assertEquals("Switch to Curmudgeon to preview", switchLabel)
        compose.onNodeWithText(switchLabel).assertExists()
        compose.onNodeWithText(tryHere).assertDoesNotExist()
        for (button in listOf("ABC", "123")) compose.onNodeWithText(button).assertDoesNotExist()
        // the summons: a pad asked for, a dialog of the screen opening (PreviewKeyboard)
        var revealed = 0
        var imeAsked = 0
        val preview = PreviewKeyboard(state, CoroutineScope(Dispatchers.Main), showIme = { imeAsked++ }, reveal = { revealed++ }) { }
        compose.runOnIdle { state.show(TryItMode.NUMBER); preview.dialogOpened(emoji = false, people = false) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        compose.onNode(isFocused()).assertDoesNotExist()
        assertEquals(TryItMode.TEXT, state.mode)
        assertEquals(0, revealed)
        assertEquals(0, imeAsked)
        // a tap on the field: Android's keyboard picker, as the bar's Switch
        compose.onNodeWithText(switchLabel).performClick()
        assertEquals(1, pickerOpened)
        compose.onNode(isFocused()).assertDoesNotExist()
        preview.dialogClosed()

        // back with this keyboard selected: the field, its buttons and its keyboard as today
        compose.runOnIdle { selected = true }
        compose.waitForIdle()
        compose.onNodeWithText(tryHere).assertExists()
        compose.onNodeWithText(switchLabel).assertDoesNotExist()
        compose.onNodeWithText("ABC").assertExists()
        compose.runOnIdle { state.show(TryItMode.NUMBER) }
        compose.waitForIdle()
        assertEquals(TryItMode.NUMBER, state.mode)
        compose.onNode(isFocused()).assertExists()
        assertTrue(pickerOpened == 1)
    }
}

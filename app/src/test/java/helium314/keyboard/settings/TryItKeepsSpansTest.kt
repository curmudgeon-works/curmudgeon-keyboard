// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.settings.screens.TryItBar
import helium314.keyboard.settings.screens.TryItMode
import helium314.keyboard.settings.screens.TryItState
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-24: in the settings' try-it field, haan → हां picked → cursor back in the word gave a strip of nonsense
 * (D1, v11, 1Weather…), while Android's own text boxes brought back haan, jaan, vaan. The field was a Compose text
 * field, and those hand the keyboard plain text: the suggestion span a pick leaves in the word is lost, so the strip
 * was computed afresh from हां on a Latin keyboard. Owner: "let's do edittext?" → the field is a classic EditText,
 * which keeps the span like the text boxes of most apps. Fail before: no EditText in the bar (a Compose field).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class TryItKeepsSpansTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val state = TryItState()

    @Before fun setUp() {
        helium314.keyboard.reviveComposeMainDispatcher()
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalImeSelected provides true) {
                TryItBar(SettingsSubtype(Locale.US, ""), state)
            }
        } }
        compose.waitForIdle()
    }
    @After fun tearDown() {
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun View.findEditText(): EditText? {
        if (this is EditText) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) children(i).findEditText()?.let { return it }
        return null
    }
    private fun ViewGroup.children(i: Int) = getChildAt(i)

    @Test fun `the try-it field is a classic text box that keeps a picked word's suggestion span`() {
        var edit: EditText? = null
        scenario!!.onActivity { edit = it.window.decorView.findEditText() }
        val field = assertNotNull(edit, "no EditText in the try-it bar: a Compose field, which drops suggestion spans")
        compose.runOnIdle { state.show(TryItMode.TEXT) }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(field.isFocused, "a summon focuses the field")
        // the keyboard commits the picked word with its strip as a suggestion span (InputLogic.commitChosenWord)
        val ic: InputConnection = assertNotNull(field.onCreateInputConnection(EditorInfo()))
        val picked = SpannableString("हां").apply {
            setSpan(SuggestionSpan(ctx, Locale.US, arrayOf("haan", "jaan", "vaan"), 0, null), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ic.commitText(picked, 1)
        ic.commitText(" ", 1)
        ic.setSelection(1, 1)
        // the cursor back in the word: the keyboard reads the text with its styles (RichInputConnection, GET_TEXT_WITH_STYLES)
        val back = ic.getTextBeforeCursor(10, InputConnection.GET_TEXT_WITH_STYLES)
        assertTrue(back is Spanned, "the field hands back plain text: $back")
        val spans = (back as Spanned).getSpans(0, back.length, SuggestionSpan::class.java)
        assertEquals(1, spans.size, "one suggestion span on the picked word")
        assertEquals(listOf("haan", "jaan", "vaan"), spans[0].suggestions.toList())
    }
}

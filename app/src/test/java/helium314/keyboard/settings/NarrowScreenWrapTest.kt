// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.KeyboardsScreen
import helium314.keyboard.settings.screens.LanguageListScreen
import helium314.keyboard.settings.screens.choicesFitBeside
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A narrow device (owner 2026-10-23: "off/l/m/h tabs in the langs screen can be narrowed/wrapped and not just the text
 * on the left?"; "defo do (a), but also use (b) as fallback if needed in case screen is too narrow / text too big. Also
 * in the copy"): a language's name stays whole, beside the narrowed Off / L / M / H or, too long for that, above them;
 * Add keyboard's copy dialog moves a choice to the next line instead of squeezing it. Fail before: "Hinglis / h", and
 * "Blacklisted" one letter per line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // (real text widths: the legacy mode measures no text)
class NarrowScreenWrapTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        LearnedStores.init(ctx)
    }
    @After fun tearDown() {
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
    }

    private fun idle() { compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle() }

    private fun SemanticsNodeInteraction.lines(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.single().lineCount
    }

    @Test fun `on a narrow device a language's name is not broken up by its buttons`() {
        // Hinglish fits beside the narrowed buttons, Luxembourgish goes above them; both on the keyboard (at the top)
        val keyboard = available("en-US").with(ExtraValue.SECONDARY_LOCALES, "hi-Latn:lb").withId(1)
        useKeyboards(ctx, real, keyboard)
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { KeyboardScope(keyboard.keyboardId) {
                LanguageListScreen(initialKeyboard = keyboard, onClickBack = { }) } } }
        idle()
        assertEquals(1, compose.onNodeWithText("Hinglish").lines())
        assertEquals(1, compose.onNodeWithText("Luxembourgish").lines())
        assertEquals(1, compose.onNodeWithText(ctx.getString(R.string.suggest_in_script, "हिंदी")).lines())
    }

    @Test fun `on a narrow device the copy dialog keeps Blacklisted whole`() {
        val a = available("en-US").withId(1)
        useKeyboards(ctx, real, a)
        real.edit().putBoolean(LearnedKeyboards.PREF_DONE, true).commit() // (the learned and blacklisted choices show)
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { KeyboardsScreen { } } }.use {
            compose.onNodeWithText(ctx.getString(R.string.add_keyboard)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.copy_of_keyboard, keyboardName(a, ctx))).performClick()
            idle()
            val blacklisted = compose.onNode(hasText(ctx.getString(R.string.learned_words_list_blacklisted)) and hasAnyAncestor(isDialog()))
            assertEquals(1, blacklisted.lines())
        }
    }

    @Test fun `the buttons stay beside the name while its longest word fits`() {
        assertTrue(choicesFitBeside(nameMinWidth = 100, choicesWidth = 192, width = 300, gap = 8))
        assertFalse(choicesFitBeside(nameMinWidth = 101, choicesWidth = 192, width = 300, gap = 8))
    }
}

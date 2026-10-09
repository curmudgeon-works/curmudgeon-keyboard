// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.AppLook
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.KeyboardsScreen
import helium314.keyboard.settings.screens.keyboardName
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Things to do are drawn in the action colour (2026-10-08: Rename / Delete on a keyboard's long-press menu were white
 *  under Black with orange, the list-text colour, while every other button there is orange). */
@RunWith(RobolectricTestRunner::class)
class ActionColorsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val scheme = AppLook.curmudgeonScheme

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Test fun `Black with orange - actions are orange, list text is not`() {
        assertEquals(Color(Defaults.CURMUDGEON_ORANGE), scheme.primary)
        assertNotEquals(scheme.primary, scheme.onSurface)
    }

    /** The colour the node's text is laid out with. */
    private fun SemanticsNodeInteraction.textColor(): Color {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.first().layoutInput.style.color
    }

    @Test fun `long-press on a keyboard name shows Rename in the action colour`() {
        val keyboard = SubtypeSettings.getEnabledSubtypes(true).first().toSettingsSubtype()
        compose.mainClock.autoAdvance = false // the screen never idles on its own under Robolectric: step the clock
        ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { MaterialTheme(colorScheme = scheme) { KeyboardsScreen(onClickBack = {}) } }
        }.use {
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithText(keyboardName(keyboard, ctx)).performTouchInput { longClick() }
            compose.mainClock.advanceTimeBy(1000)
            val rename = compose.onNodeWithText(ctx.getString(R.string.rename_keyboard))
            rename.assertExists()
            assertEquals(scheme.primary, rename.textColor())
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.LanguageListScreen
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A keyboard's language list without Advanced (owner 2026-10-18: "fine to show all langs even without dict in
 * non-advanced. "No dictionary: swipe and suggestions won't work" <- yes need this"): every language is listed; one
 * turned on with no dictionary says that swipe and suggestions won't work, one turned off keeps "No dictionary".
 * Afrikaans: a Latin keyboard language with no dictionary, built in or to download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class NoDictionaryLanguagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val onLine = "No dictionary: swipe and suggestions won't work"

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        SettingsMode.set(ctx, false) // (without Advanced)
    }
    @After fun tearDown() {
        scenario?.close()
        SettingsMode.set(ctx, helium314.keyboard.latin.settings.Defaults.PREF_ADVANCED_SETTINGS)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.loadGroups(real)
    }

    private fun show(keyboard: helium314.keyboard.latin.settings.SettingsSubtype) {
        useKeyboards(ctx, real, keyboard)
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { LanguageListScreen(initialKeyboard = keyboard, onClickBack = { }) }
        }
        compose.waitForIdle()
    }

    @Test fun `without Advanced a language with no dictionary is listed, turned off it keeps No dictionary`() {
        show(available("en-US").withId(1))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Afrikaans"))
        compose.onNodeWithText("Afrikaans").assertExists()
        compose.onNodeWithText(onLine).assertDoesNotExist() // (off: no such line)
    }

    @Test fun `turned on, a language with no dictionary says swipe and suggestions won't work`() {
        show(available("en-US").with(ExtraValue.SECONDARY_LOCALES, "af").withId(1))
        compose.onNodeWithText("Afrikaans").assertExists() // (on: among the keyboard's own, at the top)
        compose.onNodeWithText(onLine).assertExists()
    }
}

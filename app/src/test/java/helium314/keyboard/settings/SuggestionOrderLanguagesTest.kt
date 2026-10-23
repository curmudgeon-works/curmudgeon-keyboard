// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.withId
import helium314.keyboard.settings.preferences.CustomizeSuggestionsPreference
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import helium314.keyboard.latin.SuggestionRules.Kind
import helium314.keyboard.settings.preferences.ruleOptions
import kotlin.test.assertEquals

/** Order of suggestions names the languages by position (owner 2026-10-18: "Instead should say first, second, third,
 *  fourth lang"), whatever the keyboard's languages, and offers all four. */
@RunWith(RobolectricTestRunner::class)
class SuggestionOrderLanguagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val real = ctx.realPrefs()

    // one English keyboard, as the app has after its setup
    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        LearnedStores.init(ctx)
        useKeyboards(ctx, real, available("en-US").withId(1))
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
    }

    private fun idle() { compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun `stored rules read by position, not by the keyboard's language names`() {
        // the 2nd: a word of language 2, the 3rd: a common word of language 3 (on a one-language keyboard)
        ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.prefs().edit().putString(Settings.PREF_SUGGESTION_RULES, "l2,c3").commit()
            it.setContent { CustomizeSuggestionsPreference(Setting(ctx, Settings.PREF_SUGGESTION_RULES, R.string.customize_suggestions_row) { }) }
        }
        idle()
        compose.onNodeWithText(ctx.getString(R.string.customize_suggestions_row)).performClick()
        idle()
        compose.onNodeWithText("A word in your second language").assertExists()
        compose.onNodeWithText("The next common word in your third language").assertExists()
        compose.onNodeWithText(ctx.getString(R.string.suggestion_rules_languages)).assertExists()
    }

    // (on a one-language keyboard: before, it offered only that language)
    @Test fun `a position can be set to any of the first four languages`() {
        val options = ruleOptions()
        assertEquals((1..4).toList(), options.filter { it.kind == Kind.LANGUAGE }.map { it.language })
        assertEquals((1..4).toList(), options.filter { it.kind == Kind.COMMON }.map { it.language })
    }
}

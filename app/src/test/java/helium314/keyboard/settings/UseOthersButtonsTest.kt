// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.storedButton
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.LanguageListScreen
import helium314.keyboard.settings.screens.USE_OTHERS_BUTTONS_TAG
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Use lists from other keyboards" [Learned] [Blacklisted] on the keyboard's screen, above its languages, with the
 *  keyboard's "Learned & blacklisted words" under it (2026-10-10). */
@RunWith(RobolectricTestRunner::class)
class UseOthersButtonsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val b = available("en-US")
    private val a = available("de")

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        real.edit().putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(a.toPref() to 5, b.toPref() to 6)).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 7).commit()
        LearnedStores.init(ctx)
    }
    @After fun tearDown() {
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
    }

    private fun open() = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
        it.setContent { LanguageListScreen(initialKeyboard = b, onClickBack = { }) }
    }

    private fun idle() { compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun `the buttons write the keyboard into their lists and the keyboard follows at once`() {
        useKeyboards(ctx, real, b, a)
        open()
        compose.onNodeWithText(ctx.getString(helium314.keyboard.latin.R.string.learned_use_others)).assertExists()
        compose.onNodeWithText(ctx.getString(helium314.keyboard.latin.R.string.learned_words)).assertExists()
        compose.onNodeWithTag(USE_OTHERS_BUTTONS_TAG).onChildren().filterToOne(hasText(ctx.getString(helium314.keyboard.latin.R.string.learned_words_list_learned)))
            .assertIsNotSelected().performClick()
        idle()
        assertEquals(setOf(b.toPref()), storedButton(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED))
        assertEquals(emptySet(), storedButton(real, LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED))
        assertEquals(listOf(5), LearnedStores.currentView.othersLearned, "the keyboard reads A's words now")
        compose.onNodeWithTag(USE_OTHERS_BUTTONS_TAG).onChildren().filterToOne(hasText(ctx.getString(helium314.keyboard.latin.R.string.learned_words_list_learned)))
            .assertIsSelected()
        compose.onNodeWithTag(USE_OTHERS_BUTTONS_TAG).onChildren().filterToOne(hasText(ctx.getString(helium314.keyboard.latin.R.string.learned_words_list_blacklisted)))
            .performClick()
        idle()
        assertEquals(setOf(b.toPref()), storedButton(real, LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED))
        assertEquals(listOf(5), LearnedStores.currentView.othersBlacklisted)
        compose.onNodeWithTag(USE_OTHERS_BUTTONS_TAG).onChildren().filterToOne(hasText(ctx.getString(helium314.keyboard.latin.R.string.learned_words_list_learned)))
            .performClick()
        idle()
        assertEquals(emptySet(), storedButton(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED), "off again: taken out")
        assertTrue(LearnedStores.currentView.othersLearned.isEmpty())
    }

    @Test fun `both buttons stay together in one row, apart from the label`() {
        useKeyboards(ctx, real, b, a)
        open()
        val buttons = compose.onNodeWithTag(USE_OTHERS_BUTTONS_TAG).onChildren()
        buttons.filterToOne(hasText(ctx.getString(helium314.keyboard.latin.R.string.learned_words_list_learned))).assertExists()
        buttons.filterToOne(hasText(ctx.getString(helium314.keyboard.latin.R.string.learned_words_list_blacklisted))).assertExists()
        buttons.filterToOne(hasText(ctx.getString(helium314.keyboard.latin.R.string.learned_use_others))).assertDoesNotExist()
    }

    @Test fun `with one keyboard the buttons are hidden, the stored value kept, the learned words row shown`() {
        useKeyboards(ctx, real, b)
        real.edit().putString(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, JSONArray(listOf(b.toPref())).toString()).commit()
        open()
        idle()
        compose.onNodeWithText(ctx.getString(helium314.keyboard.latin.R.string.learned_use_others)).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(helium314.keyboard.latin.R.string.learned_words)).assertExists()
        assertEquals(setOf(b.toPref()), storedButton(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED))
    }
}

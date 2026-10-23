// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.translit.Transliteration
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.LanguageListScreen
import helium314.keyboard.settings.screens.SUGGEST_IN_SCRIPT_TAG
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** "Suggest in हिंदी" (Off / Some / All) under Hinglish in a keyboard's language list. */
@RunWith(RobolectricTestRunner::class)
class SuggestInScriptSettingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val hinglish = Locale.forLanguageTag("hi-Latn")
    private val english = Locale.forLanguageTag("en-US")

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
    // the keyboard's own reading (no activity: the keyboard in use's set)
    private fun keyboardPrefs() = DeviceProtectedUtils.getSharedPreferences(ctx)

    private fun show(keyboard: SettingsSubtype) {
        useKeyboards(ctx, real, keyboard)
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { KeyboardScope(keyboard.keyboardId) {
                LanguageListScreen(initialKeyboard = keyboard, onClickBack = { }) } } }
        idle()
    }

    @Test fun `the setting is Off until picked, and Off is not written down`() {
        assertEquals(Transliteration.OFF, Defaults.PREF_SUGGEST_IN_SCRIPT)
        assertEquals(Transliteration.OFF, Transliteration.get(keyboardPrefs(), hinglish))
        assertFalse(real.all.keys.any { Transliteration.isKey(it) || Transliteration.isKey(it.substringAfter('/')) })
        // nothing for the keyboard to do, and no table to read
        assertNull(Transliteration.choice(ctx, keyboardPrefs(), listOf(english, hinglish)))
    }

    @Test fun `a language without a table has no row`() {
        assertNull(Transliteration.specFor(english))
        assertEquals("हिंदी", Transliteration.specFor(hinglish)?.name)
    }

    @Test fun `the row is under Hinglish while it is on the keyboard`() {
        show(available("en-US").with(ExtraValue.SECONDARY_LOCALES, "hi-Latn").withId(1))
        compose.onAllNodesWithTag(SUGGEST_IN_SCRIPT_TAG).assertCountEquals(1)
        compose.onAllNodesWithText(ctx.getString(R.string.suggest_in_script, "हिंदी")).assertCountEquals(1)
        compose.onAllNodesWithText(ctx.getString(R.string.suggest_in_script_some)).assertCountEquals(1)
    }

    @Test fun `no row while Hinglish is off on the keyboard`() {
        show(available("en-US").withId(1))
        compose.onAllNodesWithTag(SUGGEST_IN_SCRIPT_TAG).assertCountEquals(0)
    }

    @Test fun `a tap on Some or All is the keyboard's own pick`() {
        show(available("en-US").with(ExtraValue.SECONDARY_LOCALES, "hi-Latn").withId(1))
        compose.onAllNodesWithText(ctx.getString(R.string.suggest_in_script_some))[0].performClick()
        idle()
        assertEquals(Transliteration.SOME, Transliteration.get(keyboardPrefs(), hinglish), "stored")
        compose.onAllNodesWithText(ctx.getString(R.string.suggest_in_script_some))[0].assertIsSelected()
        compose.onAllNodesWithText(ctx.getString(R.string.suggest_in_script_all))[0].performClick()
        idle()
        assertEquals(Transliteration.ALL, Transliteration.get(keyboardPrefs(), hinglish), "stored")
        val choice = Transliteration.choice(ctx, keyboardPrefs(), listOf(english, hinglish))
        assertEquals(Transliteration.ALL, choice?.mode)
        assertEquals("hi-Latn", choice?.locale?.toLanguageTag())
    }
}

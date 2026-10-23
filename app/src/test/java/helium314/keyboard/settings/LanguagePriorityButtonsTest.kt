// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LanguagePriority
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
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import kotlin.test.assertEquals

/** The L / M / H buttons of a keyboard's second language (owner 2026-10-18: "the selected hinglish lang seems stuck on M"). */
@RunWith(RobolectricTestRunner::class)
class LanguagePriorityButtonsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val hinglish = Locale.forLanguageTag("hi-Latn")
    private val keyboard get() = available("en-US").with(ExtraValue.SECONDARY_LOCALES, "hi-Latn").withId(1)

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
    private fun keyboardReads() = LanguagePriority.get(DeviceProtectedUtils.getSharedPreferences(ctx), hinglish)
    // the own languages come first, English (H) above Hinglish: the second row's button
    private fun button(label: Int) = compose.onAllNodesWithText(ctx.getString(label))[1]

    @Test fun `a tap on L or H moves the selection and the keyboard reads it`() = tapThrough(scoped = false, separate = false)
    // (as the app opens it: its navigation gives each screen the keyboard's own scope)
    @Test fun `the same in the keyboard's scope`() = tapThrough(scoped = true, separate = false)
    @Test fun `the same in the keyboard's scope with separate settings on`() = tapThrough(scoped = true, separate = true)

    private fun tapThrough(scoped: Boolean, separate: Boolean) {
        val k = keyboard
        useKeyboards(ctx, real, k)
        real.edit().putBoolean("separate_settings_per_keyboard", separate).commit()
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LanguagePriority.set(DeviceProtectedUtils.getSharedPreferences(ctx), hinglish, LanguagePriority.MEDIUM)
        assertEquals(LanguagePriority.MEDIUM, keyboardReads())
        ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { KeyboardScope(if (scoped) k.keyboardId else null) {
                LanguageListScreen(initialKeyboard = k, onClickBack = { }) } } }
        idle()
        button(R.string.language_priority_medium).assertIsSelected()
        button(R.string.language_priority_low).performClick()
        idle()
        assertEquals(LanguagePriority.LOW, keyboardReads(), "stored")
        button(R.string.language_priority_low).assertIsSelected()
        button(R.string.language_priority_high).performClick()
        idle()
        assertEquals(LanguagePriority.HIGH, keyboardReads(), "stored")
        button(R.string.language_priority_high).assertIsSelected()
    }
}

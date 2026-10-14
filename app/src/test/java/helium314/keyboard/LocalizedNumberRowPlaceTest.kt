// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.LocalSettingsMenu
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.settings.screens.SubtypeScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertTrue

/**
 * Owner 2026-10-14: "Localize number row" shows only on keyboards whose language has its own digits, "as advanced just
 * under the numbers row toggle in Layout & Typing". Driven through the screen of a Bengali keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowInputMethodManager2::class])
class LocalizedNumberRowPlaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val bengali = SettingsSubtype(Locale.forLanguageTag("bn-IN"), "").withId(1)
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val loadedSettings = Settings::class.java.getDeclaredField("mSettingsValues").apply { isAccessible = true }
    private var settingsBefore: Any? = null

    @Before fun setUp() {
        settingsBefore = loadedSettings.get(Settings.getInstance())
        real.edit().clear()
            .putString(Settings.PREF_SELECTED_SUBTYPE, bengali.toPref())
            .putString(Settings.PREF_ENABLED_SUBTYPES, bengali.toPref())
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(bengali)))
            .commit()
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        scenario?.close()
        loadedSettings.set(Settings.getInstance(), settingsBefore)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        SettingsDestination.navTarget.value = SettingsDestination.Keyboards
        LayoutDraft.close()
        SettingsMode.set(ctx, Defaults.PREF_ADVANCED_SETTINGS)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun open(advanced: Boolean) {
        SettingsMode.set(ctx, advanced)
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalSettingsMenu provides KeyboardProfiles.Group.LAYOUT) {
                SubtypeScreen(initialSubtype = bengali, onClickBack = { }) }
        } }
        compose.waitForIdle()
    }
    private fun top(text: Int) = compose.onNodeWithText(ctx.getString(text)).getUnclippedBoundsInRoot().top

    @Test fun `advanced - right under the numbers row, before the emoji key`() {
        open(advanced = true)
        val numbers = top(R.string.show_numbers_row)
        val localized = top(R.string.localized_number_row)
        val emoji = top(R.string.show_emoji_key)
        assertTrue(numbers < localized && localized < emoji, "numbers $numbers, localized $localized, emoji $emoji")
    }

    @Test fun `simple - not shown`() {
        open(advanced = false)
        compose.onNodeWithText(ctx.getString(R.string.show_numbers_row)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.localized_number_row)).assertDoesNotExist()
    }
}

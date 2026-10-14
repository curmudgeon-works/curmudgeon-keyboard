// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
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
import helium314.keyboard.latin.utils.getHasLocalizedNumberRow
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.screenSearch
import helium314.keyboard.settings.screenSettings
import helium314.keyboard.settings.screens.layoutScreenKeys
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // (the main search's row changes the keyboard: its old definition maps to the new one for the process, which a
        // later test's Bengali keyboard would follow)
        helium314.keyboard.latin.utils.SubtypeUtilsAdditional.changedTo.clear()
    }

    private fun open(advanced: Boolean, keyboard: SettingsSubtype = bengali) {
        SettingsMode.set(ctx, advanced)
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalSettingsMenu provides KeyboardProfiles.Group.LAYOUT) {
                SubtypeScreen(initialSubtype = keyboard, onClickBack = { }) }
        } }
        compose.waitForIdle()
    }
    private val title get() = ctx.getString(R.string.localized_number_row)
    private fun mainSearch(): List<String> {
        SettingsActivity.settingsContainer = SettingsContainer(ctx)
        return SettingsActivity.settingsContainer.filter(title, ctx).map { it.key }
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

    // owner 2026-10-14: "yes A, start with default on going forward": a keyboard on Default has the localized row, an
    // app-wide value (stored by an older version) is not read. Fail before: Default followed the app-wide false
    @Test fun `a keyboard on Default has the localized row, whatever an app-wide value says`() {
        real.edit().putBoolean(Settings.PREF_LOCALIZED_NUMBER_ROW, false).commit()
        assertTrue(getHasLocalizedNumberRow(bengali.toAdditionalSubtype(), real))
        open(advanced = true)
        compose.onNode(isToggleable() and hasParent(hasText(ctx.getString(R.string.localized_number_row)))).assertIsOn()
    }

    // round-7c review 20261014_L11_2 (owner: "add them"): a keyboard whose language has no digits of its own has no row,
    // on the screen or in either search
    @Test fun `a language without its own digits - no row, not found`() {
        val english = SettingsSubtype(Locale.US, "").withId(1)
        real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, english.toPref()).putString(Settings.PREF_ENABLED_SUBTYPES, english.toPref())
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(english))).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        open(advanced = true, keyboard = english)
        compose.onNodeWithText(ctx.getString(R.string.show_numbers_row)).assertExists()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertFalse(Settings.PREF_LOCALIZED_NUMBER_ROW in layoutScreenKeys(ctx.prefs(), ctx, english))
        assertFalse(Settings.PREF_LOCALIZED_NUMBER_ROW in mainSearch())
    }

    // (L11_2, M1_2 "yes A"): a row of the app's list now, so the main screen's search finds it for the keyboard in use
    // and Layout & Typing's own search for its keyboard; the main search's row edits the keyboard in use. Fail before:
    // found by neither (and the main search's row was the app-wide value)
    @Test fun `found by both searches, and the main search's row edits the keyboard in use`() {
        assertTrue(Settings.PREF_LOCALIZED_NUMBER_ROW in screenSearch(title, screenSettings(
            layoutScreenKeys(ctx.prefs(), ctx, bengali), real, KeyboardProfiles.Group.LAYOUT)).map { it.key })
        assertTrue(Settings.PREF_LOCALIZED_NUMBER_ROW in mainSearch())
        val row = SettingsActivity.settingsContainer.filter(title, ctx).first { it.key == Settings.PREF_LOCALIZED_NUMBER_ROW }
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { row.Preference() } }
        compose.waitForIdle()
        compose.onNode(isToggleable() and hasParent(hasText(title))).assertIsOn().performClick()
        compose.waitForIdle()
        val inUse = real.getString(Settings.PREF_SELECTED_SUBTYPE, "")!!.toSettingsSubtype()
        assertEquals("false", inUse.getExtraValueOf(ExtraValue.LOCALIZED_NUMBER_ROW))
        assertTrue(inUse.sameKeyboard(bengali))
        compose.onNode(isToggleable() and hasParent(hasText(title))).assertIsOff()
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.StartSettings
import helium314.keyboard.settings.screens.KeyboardsScreen
import helium314.keyboard.settings.screens.startSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Add keyboard asks its settings and word lists in one dialog, whether settings are separate or not (2026-10-10). */
@RunWith(RobolectricTestRunner::class)
class AddKeyboardTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a by lazy { available("en-US") }
    private val b by lazy { available("de-DE") }
    private val added by lazy { available("fr") }
    private fun set(keyboard: helium314.keyboard.latin.settings.SettingsSubtype) =
        ProfilePreferences(real) { KeyboardProfiles.idFor(real, keyboard) }
    private fun numberRow(keyboard: helium314.keyboard.latin.settings.SettingsSubtype) = set(keyboard).getBoolean(Settings.PREF_SHOW_NUMBER_ROW, false)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.init(ctx); useKeyboards(ctx, real, a, b) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** B has its own number row on (kept from a time with separate settings), the common settings don't. */
    private fun bWithItsOwn() {
        real.edit().putBoolean(KeyboardProfiles.ownKey(KeyboardProfiles.idFor(real, b), Settings.PREF_SHOW_NUMBER_ROW), true)
            .putBoolean(Settings.PREF_SHOW_NUMBER_ROW, false).commit()
    }
    private fun separateOn() = KeyboardProfiles.enable(real)

    @Test fun `separate settings - the new keyboard starts as the keyboard picked`() {
        KeyboardProfiles.enable(real); bWithItsOwn()
        startSettings(ctx, added, StartSettings.From(b))
        assertTrue(numberRow(added))
    }

    // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones: A's. The keyboard
    // added gets its own set at once, read once settings are separate)
    @Test fun `one set for all - the pick waits, then the keyboard starts as the one picked`() {
        bWithItsOwn()
        startSettings(ctx, added, StartSettings.From(b))
        assertTrue(KeyboardProfiles.hasOwnSettings(real, added)) // its own at once
        assertFalse(ProfilePreferences(real) { KeyboardProfiles.idFor(real, added) }.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, false)) // (A's for now)
        separateOn()
        assertTrue(numberRow(added))
        assertFalse(numberRow(a)) // (the others as before: the common settings)
    }

    @Test fun `one set for all - defaults picked, the keyboard starts at every default once separate`() {
        real.edit().putBoolean(KeyboardProfiles.ownKey(KeyboardProfiles.idFor(real, a), Settings.PREF_SHOW_NUMBER_ROW), true).commit() // the common settings, A's: number row on

        startSettings(ctx, added, StartSettings.Defaults)
        separateOn()
        assertFalse(numberRow(added))
        assertTrue(numberRow(a))
    }

    @Test fun `one set for all - the common settings picked, nothing is kept for later`() {
        startSettings(ctx, added, StartSettings.Common)
        assertFalse(real.contains("keyboard_start_from"))
    }

    @Test fun `Add keyboard always asks, also with one set for all, and Cancel adds nothing`() {
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { KeyboardsScreen { } } }.use {
            compose.onNodeWithText(ctx.getString(R.string.add_keyboard)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.copy_of_keyboard, helium314.keyboard.settings.screens.keyboardName(a, ctx))).performClick()
            compose.waitForIdle()
            // a Settings choice per keyboard, the common settings, every default
            assertEquals(2, compose.onAllNodesWithText(ctx.getString(R.string.add_keyboard_settings)).fetchSemanticsNodes().size)
            compose.onNodeWithText(ctx.getString(R.string.new_keyboard_default_settings)).assertExists()
            compose.onNodeWithText(ctx.getString(R.string.add_keyboard_settings_later)).assertExists()
            compose.onNodeWithText(ctx.getString(android.R.string.cancel)).performClick()
            compose.waitForIdle()
            assertEquals(2, SubtypeSettings.getEnabledSubtypes(true).size)
        }
    }
}

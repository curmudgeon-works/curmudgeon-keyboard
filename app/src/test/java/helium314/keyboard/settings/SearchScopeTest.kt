// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.clipboardHistoryItems
import helium314.keyboard.settings.screens.keyboardMenuSearch
import helium314.keyboard.settings.screens.layoutScreenAdvancedKeys
import helium314.keyboard.settings.screens.layoutScreenKeys
import helium314.keyboard.settings.screens.popupSearchRows
import helium314.keyboard.settings.screens.toolbarItems
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owner 2026-10-13 (SEARCH-SCOPE): "Every screen should have a search bar. That should only let you search within that
 * screen (including any items that may be duplicated between screens)"; "main screen's search bar can search anything
 * tho"; the advanced-only ones: "find them and show with advanced tint". Through the functions the screens' searches
 * run ([screenSettings], [screenSearch]; the main screens: [SettingsContainer.filter]). Fail before (compile: every
 * screen's search was the whole app's, Layout & Typing and Customize popups had none).
 */
@RunWith(RobolectricTestRunner::class)
class SearchScopeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SettingsActivity.settingsContainer = SettingsContainer(ctx)
    }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun keys(found: List<Setting>) = found.map { it.key }

    @Test fun `a screen's search finds its own settings only, the main screen's every setting`() {
        val term = "clipboard history"
        // the main screens
        assertTrue(Settings.PREF_ENABLE_CLIPBOARD_HISTORY in keys(SettingsActivity.settingsContainer.filter(term)))
        // a screen without it (the toolbar's settings): not found there
        val toolbar = screenSettings(toolbarItems(ctx.prefs()), real, null)
        assertFalse(Settings.PREF_ENABLE_CLIPBOARD_HISTORY in keys(screenSearch(term, toolbar)), "another screen's setting")
        // one that shows it: found
        val clipboard = screenSettings(clipboardHistoryItems(ctx.prefs()), real, null)
        assertTrue(Settings.PREF_ENABLE_CLIPBOARD_HISTORY in keys(screenSearch(term, clipboard)))
    }

    @Test fun `an advanced-only setting is found in simple mode, marked for the advanced tint`() {
        val before = SettingsMode.isAdvanced(ctx)
        SettingsMode.set(ctx, false) // simple mode
        try {
            // Layout & Typing: undo is in an advanced block
            val found = keys(screenSearch("undo", screenSettings(layoutScreenKeys(ctx.prefs(), ctx), real, null)))
            assertTrue(Settings.PREF_UNDO_HISTORY_LENGTH in found, "$found")
            assertTrue(Settings.PREF_UNDO_HISTORY_LENGTH in layoutScreenAdvancedKeys(ctx.prefs()))
            // a screen with simple mode keys: one not among them is advanced-only; none on a screen advanced as a whole
            assertTrue(advancedOnly(Settings.PREF_UNDO_HISTORY_LENGTH, setOf(Settings.PREF_SHOW_NUMBER_ROW)))
            assertFalse(advancedOnly(Settings.PREF_SHOW_NUMBER_ROW, setOf(Settings.PREF_SHOW_NUMBER_ROW)))
            assertFalse(advancedOnly(Settings.PREF_UNDO_HISTORY_LENGTH, emptySet()))
            assertFalse(advancedOnly(Settings.PREF_UNDO_HISTORY_LENGTH, null))
        } finally { SettingsMode.set(ctx, before) }
    }

    @Test fun `what no screen shows stays out of a screen's search`() {
        val found = screenSettings(listOf(Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER, Settings.PREF_SHOW_NUMBER_ROW, 5, null), real, null)
        assertEquals(listOf(Settings.PREF_SHOW_NUMBER_ROW), keys(found))
    }

    @Test fun `Customize popups - no search the tab, a search every tab by name`() {
        val groups = listOf(1 to listOf("a", "b"), 2 to listOf(".  (Web address)", "@  (Email)"))
        assertEquals(listOf(2 to ".  (Web address)", 2 to "@  (Email)"), popupSearchRows(groups, 1, "") { it })
        assertEquals(listOf(1 to "b"), popupSearchRows(groups, 1, "b") { it })
        assertEquals(listOf(2 to "@  (Email)"), popupSearchRows(groups, 0, "@") { it })
    }

    // owner 2026-10-13: "Keyboard screen search bar should only search items in that screen too", "Whole app search should
    // only be from the main screen of Curmedgeon Keyboard Settings"
    @Test fun `a keyboard's own screen finds its menu entries, not the settings under them`() {
        val keyboard = helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.ENGLISH, "")
        val entries = keyboardMenuSearch(ctx, keyboard)
        assertEquals(listOf(ctx.getString(helium314.keyboard.latin.R.string.settings_screen_appearance)),
            screenSearch(ctx.getString(helium314.keyboard.latin.R.string.settings_screen_appearance), entries).map { it.title })
        val autoCap = ctx.getString(helium314.keyboard.latin.R.string.auto_cap)
        assertTrue(screenSearch(autoCap, entries).isEmpty(), "a setting under a menu found on the keyboard's screen")
        assertTrue(SettingsActivity.settingsContainer.filter(autoCap).isNotEmpty(), "the first screen's search lost it")
    }
}

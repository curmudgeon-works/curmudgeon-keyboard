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
 * tho"; the advanced-only ones: "find them" (the tint they were shown on is gone: owner 2026-10-14, round-7 review
 * 20261013_L1_3: "Remove the tint entirely from all screens' searches"). Through the functions the screens' searches
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

    @Test fun `an advanced-only setting is found in simple mode`() {
        val before = SettingsMode.isAdvanced(ctx)
        SettingsMode.set(ctx, false) // simple mode
        try {
            // Layout & Typing: undo is in an advanced block
            val found = keys(screenSearch("undo", screenSettings(layoutScreenKeys(ctx.prefs(), ctx), real, null)))
            assertTrue(Settings.PREF_UNDO_HISTORY_LENGTH in found, "$found")
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

    // round-7 review 20261013_L9_3: a word in brackets is a word. Fail before: "(Web address)" split into "(Web", which
    // "web" doesn't start
    @Test fun `Customize popups - a word in brackets is found`() {
        val groups = listOf(1 to listOf("a", "b"), 2 to listOf(".  (Web address)", "@  (Email)"))
        assertEquals(listOf(2 to ".  (Web address)"), popupSearchRows(groups, 1, "web") { it })
        assertEquals(listOf(2 to "@  (Email)"), popupSearchRows(groups, 1, "email") { it })
        assertEquals(listOf(2 to ".  (Web address)"), popupSearchRows(groups, 1, "address") { it })
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

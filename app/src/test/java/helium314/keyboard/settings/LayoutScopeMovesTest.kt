// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.idKey
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.layoutScopeMoves
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.SubtypeScreen
import helium314.keyboard.settings.screens.layoutScreenAdvancedKeys
import helium314.keyboard.settings.screens.layoutScreenKeys
import helium314.keyboard.withId
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
 * 2026-10-24: the spacing between suggestions and "Hide symbols on keys" are the Layout's (Layout & Typing, in simple
 * mode: the spacing right under the numbers row, the symbols right under the voice key), not the theme's; the symbols
 * are one stored value (PREF_SHOW_HINTS), the number row's and the ellipsis's own keys go; the split keyboard rows are
 * advanced. Fail before: in the theme's scope, three keys, split in simple mode; no upgrade step (compile).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowInputMethodManager2::class])
class LayoutScopeMovesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val keyboard = SettingsSubtype(Locale.US, "").withId(1)
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val loadedSettings = Settings::class.java.getDeclaredField("mSettingsValues").apply { isAccessible = true }
    private var settingsBefore: Any? = null

    @Before fun setUp() {
        settingsBefore = loadedSettings.get(Settings.getInstance())
        real.edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        storeOneKeyboard(ctx, real)
    }
    @After fun tearDown() {
        scenario?.close()
        loadedSettings.set(Settings.getInstance(), settingsBefore)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        LayoutDraft.close()
        SettingsMode.set(ctx, Defaults.PREF_ADVANCED_SETTINGS)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Test fun `the spacing and the symbols are the Layout's, not the theme's, and the old symbol keys are nobody's`() {
        for (key in listOf(Settings.PREF_SUGGESTION_WORD_PADDING, Settings.PREF_SHOW_HINTS)) {
            assertTrue(LayoutDraft.inScope(key), key)
            assertTrue(LayoutPresets.inScope(key), key)
            assertFalse(AppearanceLooks.inScope(key), key)
            assertTrue(KeyboardProfiles.Group.LAYOUT.contains(key), key)
            assertFalse(KeyboardProfiles.Group.APPEARANCE.contains(key), key)
        }
        for (key in listOf(Settings.PREF_SHOW_NUMBER_ROW_HINTS, Settings.PREF_SHOW_POPUP_HINTS)) {
            assertFalse(LayoutDraft.inScope(key), key)
            assertFalse(AppearanceLooks.inScope(key), key)
        }
    }

    @Test fun `the rows - spacing under the numbers row, symbols under the voice key, both simple, split advanced`() {
        val all = layoutScreenKeys(ctx.prefs(), ctx, keyboard)
        val advanced = layoutScreenAdvancedKeys(ctx.prefs(), ctx, keyboard)
        assertEquals(all.indexOf(Settings.PREF_SHOW_NUMBER_ROW) + 1, all.indexOf(Settings.PREF_SUGGESTION_WORD_PADDING), "$all")
        assertEquals(all.indexOf(Settings.PREF_SHOW_VOICE_KEY) + 1, all.indexOf(SettingsWithoutKey.HIDE_ALL_SYMBOLS), "$all")
        assertFalse(Settings.PREF_SUGGESTION_WORD_PADDING in advanced)
        assertFalse(SettingsWithoutKey.HIDE_ALL_SYMBOLS in advanced)
        assertTrue(Settings.PREF_ENABLE_SPLIT_KEYBOARD in advanced)
        // (the gap while split somewhere: advanced with it)
        ctx.prefs().edit().putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, true).commit()
        assertTrue(Settings.PREF_SPLIT_SPACER_SCALE_PREFIX in layoutScreenAdvancedKeys(ctx.prefs(), ctx, keyboard))
        // the Appearance screen no longer lists them
        val appearance = helium314.keyboard.settings.screens.appearanceItems(ctx, ctx.prefs())
        assertFalse(Settings.PREF_SUGGESTION_WORD_PADDING in appearance)
        assertFalse(SettingsWithoutKey.HIDE_ALL_SYMBOLS in appearance)
    }

    /** The keys of the rows Layout & Typing draws now. */
    private fun drawn(): Set<String> {
        val keys = HashSet<String>()
        scenario?.close()
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalSettingsMenu provides KeyboardProfiles.Group.LAYOUT, LocalDrawnKeys provides keys) {
                SubtypeScreen(initialSubtype = keyboard, onClickBack = { }) }
        } }
        compose.waitForIdle()
        return keys
    }

    @Test fun `Layout and Typing draws the moved rows in simple mode and the split rows only in advanced mode`() {
        SettingsMode.set(ctx, false)
        val simple = drawn()
        assertTrue(Settings.PREF_SUGGESTION_WORD_PADDING in simple, "$simple")
        assertTrue(SettingsWithoutKey.HIDE_ALL_SYMBOLS in simple, "$simple")
        assertFalse(Settings.PREF_ENABLE_SPLIT_KEYBOARD in simple, "$simple")
        SettingsMode.set(ctx, true)
        val advanced = drawn()
        assertTrue(Settings.PREF_ENABLE_SPLIT_KEYBOARD in advanced, "$advanced")
        assertTrue(Settings.PREF_SUGGESTION_WORD_PADDING in advanced, "$advanced")
    }

    private fun values(): helium314.keyboard.latin.settings.SettingsValues {
        Settings.init(ctx)
        Settings.getInstance().loadSettings(ctx, Locale.US, InputAttributes(android.view.inputmethod.EditorInfo(), false, ctx.packageName))
        return Settings.getValues()
    }

    @Test fun `one stored value - the letters' and the number row's symbols follow it, the ellipsis stays off`() {
        var v = values()
        assertTrue(v.mShowsHints); assertTrue(v.mShowNumberRowHints); assertFalse(v.mShowsPopupHints)
        assertEquals(5, ctx.prefs().getInt(Settings.PREF_SUGGESTION_WORD_PADDING, Defaults.PREF_SUGGESTION_WORD_PADDING)) // the packed strip
        ctx.prefs().edit().putBoolean(Settings.PREF_SHOW_HINTS, false).commit()
        v = values()
        assertFalse(v.mShowsHints); assertFalse(v.mShowNumberRowHints); assertFalse(v.mShowsPopupHints)
        // the old keys say nothing any more
        ctx.prefs().edit().putBoolean(Settings.PREF_SHOW_HINTS, true).putBoolean(Settings.PREF_SHOW_NUMBER_ROW_HINTS, false)
            .putBoolean(Settings.PREF_SHOW_POPUP_HINTS, true).commit()
        v = values()
        assertTrue(v.mShowNumberRowHints); assertFalse(v.mShowsPopupHints)
    }

    @Test fun `the upgrade drops the old symbol keys from every set and the moved keys from saved themes, keeps the rest`() {
        real.edit().putBoolean(Settings.PREF_SHOW_NUMBER_ROW_HINTS, false).putBoolean(Settings.PREF_SHOW_HINTS, false) // the plain set: hidden
            .putBoolean(idKey(2, Settings.PREF_SHOW_POPUP_HINTS), false).putBoolean(idKey(2, Settings.PREF_SHOW_NUMBER_ROW_HINTS), true)
            .putBoolean(idKey(2, Settings.PREF_SHOW_HINTS), false).putInt(idKey(2, Settings.PREF_SUGGESTION_WORD_PADDING), 10)
            .putBoolean(idKey(3, KeyboardProfiles.TOMBSTONE + Settings.PREF_SHOW_POPUP_HINTS), true) // an old default mark
            .commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Night", mapOf(Settings.PREF_THEME_COLORS to "black",
            Settings.PREF_SUGGESTION_WORD_PADDING to 5, Settings.PREF_SHOW_HINTS to false, Settings.PREF_SHOW_NUMBER_ROW_HINTS to false),
            remembered = mapOf(Settings.PREF_SUGGESTION_WORD_PADDING to 10))), now = true)
        layoutScopeMoves(real)
        assertEquals(false, real.all[Settings.PREF_SHOW_HINTS])
        assertEquals(false, real.all[idKey(2, Settings.PREF_SHOW_HINTS)])
        assertEquals(10, real.all[idKey(2, Settings.PREF_SUGGESTION_WORD_PADDING)]) // keyboards keep their spacing
        for (key in listOf(Settings.PREF_SHOW_NUMBER_ROW_HINTS, idKey(2, Settings.PREF_SHOW_POPUP_HINTS), idKey(2, Settings.PREF_SHOW_NUMBER_ROW_HINTS),
                idKey(3, KeyboardProfiles.TOMBSTONE + Settings.PREF_SHOW_POPUP_HINTS)))
            assertFalse(real.contains(key), key)
        val night = AppearanceLooks.load(real).single()
        assertEquals(mapOf<String, Any?>(Settings.PREF_THEME_COLORS to "black"), night.values)
        assertEquals(emptyMap(), night.remembered)
        // again: nothing to do
        val before = real.all.toMap()
        layoutScopeMoves(real)
        assertEquals(before, real.all)
    }

    @Test fun `a keyboard restored on its own loses the old symbol keys and keeps its spacing and its one value`() {
        val backup = mapOf<String, Any?>(helium314.keyboard.latin.PICKED_ONLY_DONE to true,
            helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true, helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true,
            idKey(1, Settings.PREF_SHOW_HINTS) to false, idKey(1, Settings.PREF_SHOW_NUMBER_ROW_HINTS) to false,
            idKey(1, Settings.PREF_SHOW_POPUP_HINTS) to false, idKey(1, Settings.PREF_SUGGESTION_WORD_PADDING) to 10)
        val restored = helium314.keyboard.settings.preferences.restoredSettings(ctx, backup, keyboard)
        assertEquals(false, restored[Settings.PREF_SHOW_HINTS])
        assertEquals(10, restored[Settings.PREF_SUGGESTION_WORD_PADDING])
        assertFalse(Settings.PREF_SHOW_NUMBER_ROW_HINTS in restored)
        assertFalse(Settings.PREF_SHOW_POPUP_HINTS in restored)
    }
}

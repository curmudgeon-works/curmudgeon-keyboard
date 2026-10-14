// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.LocalDrawnKeys
import helium314.keyboard.settings.LocalSettingsMenu
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.settings.hiddenFromSearch
import helium314.keyboard.settings.screenSettings
import helium314.keyboard.settings.screens.SubtypeScreen
import helium314.keyboard.settings.screens.SwipeTuningScreen
import helium314.keyboard.settings.screens.layoutScreenKeys
import helium314.keyboard.settings.screens.swipeScreenKeys
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
import kotlin.test.assertTrue

/**
 * Round-7c review 20261014_L4_2 (owner: "B. Do the redesign now"): Layout & Typing and Swipe draw their rows from the
 * lists their searches (and the main screen's search) use. Checked against what the screens really draw (the keys
 * of the drawn rows, [LocalDrawnKeys]), not against the lists themselves: the old check, "every setting found is on
 * some screen's list", was circular. Advanced mode, so every row is drawn; a Bengali keyboard, so Localize number row
 * is one. Fail before: no drawn keys to compare (compile), and Localize number row drawn but on no list (M1_2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowInputMethodManager2::class])
class ScreensDrawTheirListsTest {
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
        SettingsMode.set(ctx, true)
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

    /** The settings of the app's list [screen] draws (as its search would find them: not what no search finds). */
    private fun drawn(menu: KeyboardProfiles.Group, screen: @androidx.compose.runtime.Composable () -> Unit): Set<String> {
        val keys = HashSet<String>()
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalSettingsMenu provides menu, LocalDrawnKeys provides keys) { screen() }
        } }
        compose.waitForIdle()
        return keys.filterTo(HashSet()) { SettingsActivity.settingsContainer[it] != null && !hiddenFromSearch(it) }
    }

    private fun searched(keys: List<String>, menu: KeyboardProfiles.Group) =
        screenSettings(keys, real, menu).map { it.key }.toSet()

    @Test fun `Layout and Typing draws what its search finds, the localized number row too`() {
        val drawn = drawn(KeyboardProfiles.Group.LAYOUT) { SubtypeScreen(initialSubtype = bengali, onClickBack = { }) }
        val searched = searched(layoutScreenKeys(ctx.prefs(), ctx, bengali), KeyboardProfiles.Group.LAYOUT)
        assertTrue(Settings.PREF_LOCALIZED_NUMBER_ROW in drawn, "$drawn")
        assertEquals(searched, drawn, "found but not drawn: ${searched - drawn}; drawn but not found: ${drawn - searched}")
    }

    @Test fun `Layout and Typing with the switches turned - still the same`() {
        ctx.prefs().edit().putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true).putBoolean(Settings.PREF_AUTO_CORRECTION, false)
            .putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, true).putBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, false).commit()
        val drawn = drawn(KeyboardProfiles.Group.LAYOUT) { SubtypeScreen(initialSubtype = bengali, onClickBack = { }) }
        val searched = searched(layoutScreenKeys(ctx.prefs(), ctx, bengali), KeyboardProfiles.Group.LAYOUT)
        assertTrue(Settings.PREF_SPLIT_SPACER_SCALE_PREFIX in drawn, "$drawn")
        assertEquals(searched, drawn, "found but not drawn: ${searched - drawn}; drawn but not found: ${drawn - searched}")
    }

    @Test fun `Swipe draws what its search finds, swiping on and off`() {
        for (on in listOf(true, false)) {
            ctx.prefs().edit().putBoolean(Settings.PREF_GESTURE_INPUT, on).commit()
            val drawn = drawn(KeyboardProfiles.Group.SWIPE) { SwipeTuningScreen(keyboard = bengali, onClickBack = { }) }
            scenario?.close(); scenario = null
            val searched = searched(swipeScreenKeys(ctx.prefs()), KeyboardProfiles.Group.SWIPE)
            assertEquals(on, Settings.PREF_GESTURE_TRAIL_THICKNESS in drawn, "swiping $on: $drawn")
            assertEquals(searched, drawn, "swiping $on: found but not drawn: ${searched - drawn}; drawn but not found: ${drawn - searched}")
        }
    }
}

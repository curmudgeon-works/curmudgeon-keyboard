// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** One Back leaves a settings screen (2026-10-08: a tap on a main-screen row put 3–6 copies of the screen on the back
 *  stack, as every redraw while the target was set navigated again, so Back needed a press per copy). Drives the real
 *  settings activity with the clock stepped by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class BackOncePerScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    // the settings activity loads the keyboard's settings once for the whole test run (Settings is a singleton): from
    // then on every preference change reloads them, which gives the keyboard in use an id and re-reads its learned
    // words in later tests (DeleteWarningTest, LearnedStoresSeedingTest). Put back as found after each test.
    private val loadedSettings = Settings::class.java.getDeclaredField("mSettingsValues").apply { isAccessible = true }
    private var settingsBefore: Any? = null

    @Before fun setUp() {
        settingsBefore = loadedSettings.get(Settings.getInstance())
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        // the keyboard enabled in Android: the settings open, not the setup wizard
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        shadowOf(imm).setEnabledInputMethodInfoList(listOf(InputMethodInfo(ctx.packageName, "LatinIME", "Curmudgeon", null)))
        SettingsDestination.navTarget.value = SettingsDestination.Keyboards
    }
    @After fun tearDown() {
        loadedSettings.set(Settings.getInstance(), settingsBefore)
        // the activity's background work done before the next test (its learned-words copy calls back on the main thread)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        SettingsDestination.navTarget.value = SettingsDestination.Keyboards
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
    private val mainTitle get() = ctx.getString(R.string.ime_settings)
    private val aboutTitle get() = ctx.getString(R.string.settings_screen_about)

    private fun launch(test: (SettingsActivity) -> Unit) {
        compose.mainClock.autoAdvance = false // the main screen may redraw on every frame: never wait for idle
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var activity: SettingsActivity
            scenario.onActivity { activity = it }
            compose.mainClock.advanceTimeBy(1000)
            test(activity)
        }
    }

    @Test fun `one Back leaves a screen that redraws while it opens`() = launch { activity ->
        assertEquals(1, shown(mainTitle), "the settings didn't open on the main screen")
        // a row's tap: navigateTo keeps the target set for 50 ms; held here by hand (no race with the real clock), with a
        // few redraws in that time, as a setting change or the opened keyboard's recolour make them
        SettingsDestination.navTarget.value = SettingsDestination.About
        repeat(4) {
            activity.runOnUiThread { activity.prefChanged() }
            compose.mainClock.advanceTimeByFrame()
        }
        SettingsDestination.navTarget.value = SettingsDestination.Keyboards
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(1, shown(aboutTitle), "About didn't open")
        activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(0, shown(aboutTitle), "About still shown after one Back: a copy of it was under it")
        assertEquals(1, shown(mainTitle), "one Back didn't reach the main screen")
    }

    @Test fun `navigateTo opens a screen and one Back leaves it`() = launch { activity ->
        activity.runOnUiThread { SettingsDestination.navigateTo(SettingsDestination.About) }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(10) } // past navigateTo's 50 ms
        Thread.sleep(100)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(SettingsDestination.Keyboards, SettingsDestination.navTarget.value)
        assertEquals(1, shown(aboutTitle), "About didn't open")
        activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(0, shown(aboutTitle), "About still shown after one Back")
        assertEquals(1, shown(mainTitle))
        // the same screen once more (navigateTo's toggle for a repeated target): it opens again
        activity.runOnUiThread { SettingsDestination.navigateTo(SettingsDestination.About) }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(10) }
        Thread.sleep(100)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(1, shown(aboutTitle), "About didn't open the second time")
    }
}

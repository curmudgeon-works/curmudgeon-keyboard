// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardScopeContext
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.keyboardName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which keyboard a settings screen edits comes from the screen's own navigation entry (2026-10-09, design (b)): no
 *  global "keyboard being edited" that whichever screen drew last set. Keyboard A (in use) and B, separate settings on.
 *  Drives the real settings activity with the clock stepped by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class EditingKeyboardTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first().toSettingsSubtype()
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private var idA = 0
    private var idB = 0
    private val key = Settings.PREF_AUTO_CAP // on Text correction, each keyboard's own
    // the activity loads the keyboard's settings into the Settings singleton, whose listener then reloads them on every
    // change in later tests: put back as found (see BackOncePerScreenTest)
    private val loadedSettings = Settings::class.java.getDeclaredField("mSettingsValues").apply { isAccessible = true }
    private var settingsBefore: Any? = null

    @Before fun setUp() {
        settingsBefore = loadedSettings.get(Settings.getInstance())
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        shadowOf(imm).setEnabledInputMethodInfoList(listOf(InputMethodInfo(ctx.packageName, "LatinIME", "Curmudgeon", null)))
        SettingsDestination.navTarget.value = SettingsDestination.Keyboards
        real.edit {
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
        }
        KeyboardProfiles.enable(real, listOf(a, b), keepExisting = false)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
        idA = KeyboardProfiles.idFor(real, a)
        idB = KeyboardProfiles.idFor(real, b)
        assertTrue(idA != idB && idA != KeyboardProfiles.SHARED && idB != KeyboardProfiles.SHARED)
    }
    @After fun tearDown() {
        loadedSettings.set(Settings.getInstance(), settingsBefore)
        // the activity's background work done before the next test
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        SettingsDestination.navTarget.value = SettingsDestination.Keyboards
        PrefsDraft.close("correction"); AppearanceDraft.close(); LayoutDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
        // the reloads refresh the input method's caches in the background: done before the next test, whose shadows
        // differ (an IME shadow cast failed there, reported as an uncaught exception before that test)
        val imeScope = helium314.keyboard.latin.RichInputMethodManager::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(helium314.keyboard.latin.RichInputMethodManager.getInstance()) as CoroutineScope
        kotlinx.coroutines.runBlocking { imeScope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.join() } }
    }

    private fun own(id: Int) = KeyboardProfiles.ownKey(id, key)

    // ---- 1: the preferences a context gets ----

    @Test fun `a keyboard's screen writes its own set, the other screens the keyboard in use, a gone set the main one`() {
        KeyboardScopeContext(ctx, idB).prefs().edit { putBoolean(key, false) }
        assertTrue(real.contains(own(idB)), "B's screen didn't write B's set: ${real.all.keys}")
        assertFalse(real.contains(own(idA)) || real.contains(key))

        val activity = Robolectric.buildActivity(SettingsActivity::class.java).get() // (an activity's context, not created)
        activity.prefs().edit { putBoolean(key, true) }
        assertEquals(true, real.getBoolean(own(idA), false), "a screen of no keyboard didn't write the keyboard in use's set")
        assertEquals(false, real.getBoolean(own(idB), true))

        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        activity.prefs().edit { putBoolean(key, false) }
        KeyboardScopeContext(activity, idB).prefs().edit { putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 333) }
        assertEquals(false, real.getBoolean(key, true), "separate settings off: not the plain key")
        assertEquals(333, real.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 0), "separate settings off: not the plain key")
        real.edit { putBoolean("separate_settings_per_keyboard", true) }

        KeyboardScopeContext(activity, 99).prefs().edit { putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 444) }
        assertFalse(real.all.keys.any { it.startsWith("p99/") }, "a set no keyboard has was written: ${real.all.keys}")
        assertEquals(444, real.getInt(KeyboardProfiles.ownKey(idA, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 0))
    }

    // ---- the real activity ----

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
    private val mainTitle get() = ctx.getString(R.string.ime_settings)
    private val correction get() = ctx.getString(R.string.settings_screen_correction)
    private val autoCap get() = ctx.getString(R.string.auto_cap)

    // (a state change from a tap or another thread reaches the screens through the main looper)
    private fun frames(ms: Long) { idle(); compose.mainClock.advanceTimeBy(ms); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    /** navigateTo's target resets after 50 ms of real time: a few frames with real time passing. */
    private fun afterTap() { repeat(6) { idle(); compose.mainClock.advanceTimeByFrame(); Thread.sleep(10) }; Thread.sleep(100); idle() }
    // (the row's click action: touch input waits for a clock that only moves by hand here)
    private fun tap(text: String) { compose.onAllNodesWithText(text)[0].performSemanticsAction(SemanticsActions.OnClick); idle() }

    private fun launch(test: (ActivityScenario<SettingsActivity>, SettingsActivity) -> Unit) {
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var activity: SettingsActivity
            scenario.onActivity { activity = it }
            frames(1000)
            assertEquals(1, shown(mainTitle), "the settings didn't open on the main screen")
            test(scenario, activity)
        }
    }

    /** B unfolded on the main screen, its Text correction tapped. */
    private fun openCorrectionOfB() {
        tap(keyboardName(b, ctx))
        frames(500)
        tap(correction)
        afterTap()
    }

    // (the row, not the search box with the same text)
    private fun toggleAutoCap() {
        compose.onAllNodes(hasText(autoCap) and !hasSetTextAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        idle(); compose.mainClock.advanceTimeByFrame()
    }

    // ---- 2: a tap or a Back during the 250 ms slide ----

    @Test fun `opened during the slide, B's screen edits B`() = launch { _, _ ->
        openCorrectionOfB()
        frames(100) // mid-slide: the main screen is still drawn
        toggleAutoCap()
        assertTrue(real.contains(own(idB)), "B's Text correction didn't write B's set: ${real.all.keys}")
        assertFalse(real.contains(own(idA)), "B's Text correction wrote A's set")
    }

    @Test fun `Back during the slide, then App settings and a setting found there edit the keyboard in use`() = launch { _, activity ->
        openCorrectionOfB()
        frames(1000)
        activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        frames(100) // mid-slide: B's Text correction is still drawn
        tap(ctx.getString(R.string.settings_screen_advanced))
        afterTap()
        frames(1000)
        assertEquals(0, shown(mainTitle), "App settings didn't open")
        assertEquals(0, shown(keyboardName(b, ctx)), "App settings names B")
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.label_search_key))[0].performSemanticsAction(SemanticsActions.OnClick)
        frames(500)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(autoCap)
        frames(500)
        toggleAutoCap()
        assertTrue(real.contains(own(idA)), "the setting didn't land in the keyboard in use's set: ${real.all.keys}")
        assertFalse(real.contains(own(idB)), "the setting landed in B's set")
    }

    // ---- 3: the main screen idle ----

    @Test fun `the main screen doesn't redraw while nothing happens`() = launch { _, activity ->
        val before = activity.prefChanged.value
        repeat(120) { compose.mainClock.advanceTimeByFrame() } // 2 s
        assertTrue(activity.prefChanged.value - before <= 2, "redrawn ${activity.prefChanged.value - before} times in 2 s")
    }

    // ---- 4: two settings windows ----

    @Test fun `two settings windows each edit their own keyboard`() = launch { first, _ ->
        openCorrectionOfB()
        frames(1000)
        ActivityScenario.launch(SettingsActivity::class.java).use { second ->
            frames(1000) // the second window on its main screen (the keyboard in use)
            second.moveToState(Lifecycle.State.STARTED)
            first.moveToState(Lifecycle.State.RESUMED)
            frames(500)
            toggleAutoCap() // (only the first window shows Text correction)
            assertTrue(real.contains(own(idB)), "the first window's B screen didn't write B: ${real.all.keys}")
            assertFalse(real.contains(own(idA)), "the second window took the first one's keyboard")
        }
    }

    // ---- 5: recreated (rotation, process death) ----

    @Test fun `recreated on B's screen, it still edits B`() = launch { scenario, _ ->
        openCorrectionOfB()
        frames(1000)
        scenario.recreate()
        frames(1000)
        assertEquals(1, shown(autoCap), "Text correction isn't shown after recreating")
        toggleAutoCap()
        assertTrue(real.contains(own(idB)), "after recreating, B's screen didn't write B: ${real.all.keys}")
        assertFalse(real.contains(own(idA)))
    }

    // ---- 6: a factory reset or a restore with B's screen open ----

    @Test fun `a factory reset with B's screen open goes back to the main screen`() = launch { _, activity ->
        openCorrectionOfB()
        frames(1000)
        var done = false
        activity.runOnUiThread { helium314.keyboard.settings.preferences.startFactoryReset(activity, keyboards = false,
            learnedWords = false, clipboard = false, custom = false) { done = true } }
        val until = System.currentTimeMillis() + 5000
        while (!done && System.currentTimeMillis() < until) { idle(); Thread.sleep(20) }
        assertTrue(done, "the reset didn't run")
        frames(1000)
        assertEquals(1, shown(mainTitle), "still on B's screen after the reset")
        assertEquals(0, shown(autoCap))
        assertFalse(real.all.keys.any { it.startsWith("p$idB/") || it.startsWith("p$idA/") }, "${real.all.keys}")
    }

    @Test fun `a restore with B's screen open goes back to the main screen`() = launch { _, activity ->
        openCorrectionOfB()
        frames(1000)
        // the backup's ids replace the phone's: B's old id is A's now
        activity.runOnUiThread { helium314.keyboard.settings.preferences.runRestore(activity, {}, R.string.backup_restored) {
            real.edit().clear().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(b, a)))
                .putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref()).commit()
            KeyboardProfiles.enable(real, listOf(b, a), keepExisting = false)
        } }
        frames(1000)
        assertEquals(idB, KeyboardProfiles.idFor(real, a), "(the restore didn't swap the ids)")
        assertEquals(1, shown(mainTitle), "still on B's screen after the restore")
        assertEquals(0, shown(autoCap))
        assertFalse(real.all.keys.any { it.endsWith("/$key") }, "${real.all.keys}")
    }

    // ---- 7: work started on B's screen that finishes after Back ----

    @Test fun `work started on B's screen and finished after Back writes B`() = launch { _, activity ->
        openCorrectionOfB()
        frames(1000)
        val screen = KeyboardScopeContext(activity, idB) // (what B's screen gets as its context)
        val scope = CoroutineScope(Dispatchers.Main)
        try {
            scope.launch { delay(300); screen.prefs().edit { putBoolean(key, false) } }
            activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
            frames(1000)
            assertEquals(1, shown(mainTitle))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertTrue(real.contains(own(idB)), "the late write didn't land in B's set: ${real.all.keys}")
            assertFalse(real.contains(own(idA)))
        } finally { scope.cancel() }
    }

    // a route whose last path argument is empty (the personal dictionary "for all languages") still opens with the set
    @Test fun `routes with the keyboard's set open, an empty argument too`() = launch { _, activity ->
        for (route in listOf(SettingsDestination.inSet(SettingsDestination.PersonalDictionary, idB),
                SettingsDestination.inSet(SettingsDestination.withKeyboard(SettingsDestination.Subtype, b), idB),
                SettingsDestination.inSet(SettingsDestination.Colors + "abc", idB))) {
            SettingsDestination.navTarget.value = route
            frames(100)
            SettingsDestination.navTarget.value = SettingsDestination.Keyboards
            frames(1000)
            assertEquals(0, shown(mainTitle), "$route didn't open")
            activity.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
            frames(1000)
            assertEquals(1, shown(mainTitle), "Back from $route")
        }
    }

    // (DeviceProtectedUtils: the per-id views are cached)
    @Test fun `one view per keyboard set`() {
        val prefsB = DeviceProtectedUtils.getSharedPreferences(KeyboardScopeContext(ctx, idB))
        assertTrue(prefsB === DeviceProtectedUtils.getSharedPreferences(KeyboardScopeContext(ctx, idB)))
        assertFalse(prefsB === DeviceProtectedUtils.getSharedPreferences(KeyboardScopeContext(ctx, idA)))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.spellcheck.SpellCheckerSettingsActivity
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fix A (owner 2026-10-14: "I updated swipe trail and had to press back a bunch to get to main settings screen"): the
 * settings key opened a settings window on top of an older one in the same task, and Back walked through the older
 * window's screens. It opens one window now, the older one closed; and closing a window counts as leaving it, also
 * one stopped while a file picker it opened was in front (its drafts stayed running, and the next screen took them over).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class OneSettingsWindowTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val key = Settings.PREF_AUTO_CORRECTION
    private val draftName = "onewindow"

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        PrefsDraft.close(draftName)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun Intent.has(flag: Int) = flags and flag == flag

    // fail before: the settings key's intent had no CLEAR_TASK (NEW_TASK, RESET_TASK_IF_NEEDED and CLEAR_TOP only)
    @Test fun `the settings key opens one window, an older one closed`() {
        val intent = SettingsActivity.openFromOutside(ctx)
        assertEquals(SettingsActivity2::class.java.name, intent.component?.className)
        assertTrue(intent.has(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(intent.has(Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    // the spell checker's settings opened the same way, on top of an older window; fail before: no CLEAR_TASK
    @Test fun `the spell checker's settings open one window too`() {
        val activity = Robolectric.buildActivity(SpellCheckerSettingsActivity::class.java).create().get()
        val started = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(SettingsActivity2::class.java.name, started.component?.className)
        assertTrue(started.has(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(started.has(Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(started.getBooleanExtra("spellchecker", false))
    }

    /** A settings window with nothing on it (the drafts are what matters here). */
    private fun window(): ActivityScenario<SettingsActivity> =
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { } }

    /** A Save/Discard edit open: its draft, and [key] changed since it opened. */
    private fun changeWithDraftOpen() {
        PrefsDraft.of(ctx, draftName, listOf(key))
        ctx.prefs().edit().putBoolean(key, false).commit()
        assertTrue(PrefsDraft.anyOpen())
    }

    // fail before: onStop skipped the drafts (a file picker in front) and onDestroy didn't look at them: the draft stayed
    // running with its change, and the next screen's draft was this one
    @Test fun `a window closed while its file picker was in front leaves its open edit`() {
        window().use { scenario ->
            changeWithDraftOpen()
            @Suppress("DEPRECATION")
            scenario.onActivity { it.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*"), 1) }
            scenario.moveToState(Lifecycle.State.CREATED) // stopped, the picker in front: not left
            assertTrue(PrefsDraft.anyOpen(), "stopped for its own file picker counted as leaving")
            scenario.moveToState(Lifecycle.State.DESTROYED) // closed (e.g. by the settings key's new window)
            assertFalse(PrefsDraft.anyOpen(), "the closed window's draft still running")
            assertEquals(true, ctx.prefs().getBoolean(key, true), "its change wasn't undone")
        }
    }

    // harmless after onStop: a window left (its edits handled then) doesn't touch an edit opened since, e.g. in the new
    // window, when it closes
    @Test fun `a window left before it closes leaves an edit opened since alone`() {
        window().use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED) // left: nothing open
            changeWithDraftOpen() // (another window's edit)
            scenario.moveToState(Lifecycle.State.DESTROYED)
            assertTrue(PrefsDraft.anyOpen(), "closing the window left another window's edit")
            assertEquals(false, ctx.prefs().getBoolean(key, true))
        }
    }
}

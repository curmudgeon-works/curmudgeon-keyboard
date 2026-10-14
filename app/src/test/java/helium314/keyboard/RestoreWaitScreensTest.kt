// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.LocalSettingsMenu
import helium314.keyboard.settings.PrefsDraft
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.runBackup
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.screens.AppearanceScreen
import helium314.keyboard.settings.screens.FineTuneLearningScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-12 A5: a restore goes on after its dialog closed; Appearance (and the other screens that take a
 * snapshot when they open) opened meanwhile took it mid-restore, and Discard put a half-restored state back. Now they
 * show "Restore in progress…" and take their snapshot once the restore ended.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowInputMethodManager2::class])
class RestoreWaitScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val gate = CountDownLatch(1)
    private val waiting get() = ctx.getString(R.string.restore_in_progress)
    private val fineTuneDraft get() = File(ctx.filesDir, "prefs_draft_finetunelearning.json")

    @After fun tearDown() {
        gate.countDown()
        idleUntil { !BackupRestoreJob.isRunning }
        scenario?.close()
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        AppearanceDraft.close(); PrefsDraft.close("finetunelearning")
        ctx.realPrefs().edit().clear().commit(); KeyboardProfiles.loadGroups(ctx.realPrefs())
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return done()
    }

    /** A restore that runs until [gate] opens. */
    private fun restoreRunning() {
        assertTrue(runRestore(ctx, {}, R.string.backup_restored) { gate.await(10, TimeUnit.SECONDS) })
        assertTrue(BackupRestoreJob.restoring)
    }

    private fun endRestore() {
        gate.countDown()
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the restore never ended")
        assertFalse(BackupRestoreJob.restoring)
        compose.waitForIdle()
    }

    // fail before: Appearance took its snapshot (AppearanceDraft started) while the restore was still writing
    @Test fun `Appearance opened during a restore waits, and takes its snapshot once the restore ended`() {
        restoreRunning()
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalSettingsMenu provides KeyboardProfiles.Group.APPEARANCE) { AppearanceScreen(onClickBack = { }) }
        } }
        compose.waitForIdle()
        compose.onNodeWithText(waiting).assertExists()
        assertNull(AppearanceDraft.activeOrNull(), "a snapshot taken mid-restore")
        endRestore()
        compose.onNodeWithText(waiting).assertDoesNotExist()
        assertNotNull(AppearanceDraft.activeOrNull(), "the screen opened with its snapshot after the restore")
    }

    // the same for a screen with a PrefsDraft (its snapshot is a file); fail before: the file was written at once
    @Test fun `a Save or Discard screen opened during a restore waits too`() {
        fineTuneDraft.delete()
        restoreRunning()
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            CompositionLocalProvider(LocalSettingsMenu provides KeyboardProfiles.Group.LEARNING) { FineTuneLearningScreen(onClickBack = { }) }
        } }
        compose.waitForIdle()
        compose.onNodeWithText(waiting).assertExists()
        assertFalse(fineTuneDraft.exists(), "a snapshot taken mid-restore")
        endRestore()
        compose.onNodeWithText(waiting).assertDoesNotExist()
        assertTrue(fineTuneDraft.exists(), "the screen opened with its snapshot after the restore")
    }

    // a backup changes nothing: the screens don't wait for it
    @Test fun `a backup running is not a restore`() {
        val zip = File(ctx.cacheDir, "wait-backup.zip")
        assertTrue(runBackup(ctx, {}) { gate.await(10, TimeUnit.SECONDS); FileOutputStream(zip) })
        assertTrue(BackupRestoreJob.isRunning)
        assertFalse(BackupRestoreJob.restoring)
        gate.countDown()
        assertTrue(idleUntil { !BackupRestoreJob.isRunning })
        zip.delete()
    }
}

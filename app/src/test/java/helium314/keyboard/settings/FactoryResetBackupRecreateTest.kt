// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.FactoryResetPreference
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Review 2026-10-12 L2 (5): the factory reset's Backup greys out once a backup is written while its dialog is open. The
 * flag was set by the backup's own callback, the screen's as it was when the file was picked; with the activity made
 * again while the backup ran (a rotation), that callback set the old screen's flag, and the new screen, its saved flag
 * still "not backed up", showed Backup on again after the backup was written.
 */
@RunWith(RobolectricTestRunner::class)
class FactoryResetBackupRecreateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zip = File(ctx.cacheDir, "factory-reset-recreate.zip")
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val release = CountDownLatch(1)

    /** The screen's saved state as an activity made again restores it: saved, the screen gone, then built again from
     *  what was saved (its old callbacks left with the old screen). */
    private class Restorer {
        var show by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    }
    private val restorer = Restorer()

    @After fun tearDown() {
        release.countDown()
        idleUntil { !BackupRestoreJob.isRunning }
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
        zip.delete()
    }

    private fun idleUntil(done: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle()
        return done()
    }

    private fun recreate() {
        val saved = restorer.registry.performSave()
        restorer.show = false
        compose.waitForIdle()
        restorer.registry = SaveableStateRegistry(saved) { true }
        restorer.show = true
        compose.waitForIdle()
    }

    @Test fun `a backup written while the screen was made again greys Backup out on the new screen`() {
        val setting = Setting(ctx, SettingsWithoutKey.FACTORY_RESET, R.string.factory_reset) { FactoryResetPreference(it) }
        var activity: Activity? = null
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            activity = it
            it.setContent {
                if (restorer.show) CompositionLocalProvider(LocalSaveableStateRegistry provides restorer.registry) {
                    FactoryResetPreference(setting)
                }
            }
        }
        compose.onNodeWithText("Factory reset").performClick()
        compose.onNodeWithText("Backup").assertIsEnabled().performClick()
        val picker = shadowOf(activity!!).nextStartedActivityForResult
        assertTrue(picker != null, "no file picker asked for")

        // the backup waits behind busy background threads, so the screen is made again while it runs
        val threads = maxOf(Runtime.getRuntime().availableProcessors() / 2, 1)
        val started = CountDownLatch(threads)
        repeat(threads) { ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { started.countDown(); release.await() } }
        assertTrue(started.await(10, TimeUnit.SECONDS), "the background threads weren't all taken")
        shadowOf(activity!!).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(zip)))
        assertTrue(BackupRestoreJob.isRunning, "the picked file didn't start the backup")

        recreate()
        release.countDown()
        assertTrue(idleUntil { !BackupRestoreJob.isRunning }, "the backup never ended")
        compose.waitForIdle()
        assertTrue(zip.length() > 0, "the backup wasn't written")
        compose.onNodeWithText("Backup").assertIsNotEnabled()

        // (owner 2026-10-11: on again once the dialog is closed and opened again)
        compose.onNodeWithText(ctx.getString(android.R.string.cancel)).performClick()
        compose.onNodeWithText("Factory reset").performClick()
        compose.onNodeWithText("Backup").assertIsEnabled()
    }
}

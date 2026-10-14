// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.ContinuationInterceptor

/**
 * Compose's main-thread dispatcher (AndroidUiDispatcher.Main, one for the whole test run) posts one message to the main
 * looper and then waits for it: until it runs, nothing more is posted ("scheduledTrampolineDispatch" stays set). Robolectric
 * drops whatever is still queued on the main looper when a test ends; a test that wrote Compose state (a draft closed, the
 * Advanced switch, "What to merge" ticks...) after an earlier class had drawn a screen, and ended without running the main
 * looper, so left the dispatcher waiting for a message that's gone. From then on nothing on it runs, among it Compose's
 * notice of state changes (GlobalSnapshotManager): screens stop reacting to taps that only the main looper carries
 * (EditingKeyboardTest, 2026-10-11: keyboard B didn't unfold in the full suite, whichever class left it).
 *
 * Called before a test that drives screens by the main looper: runs what's still queued, then, if the dispatcher still
 * waits for a dropped message, its queued work by hand (which clears the wait), and the pending state changes.
 */
fun reviveComposeMainDispatcher() {
    val looper = shadowOf(Looper.getMainLooper())
    looper.idle()
    val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
    val c = AndroidUiDispatcher::class.java
    val trampoline = c.getDeclaredField("scheduledTrampolineDispatch").apply { isAccessible = true }
    val frame = c.getDeclaredField("scheduledFrameDispatch").apply { isAccessible = true }
    // (a frame callback still due is posted for later, not dropped yet: running it now does what it would do)
    if (frame.getBoolean(dispatcher))
        c.getDeclaredMethod("access\$performFrameDispatch", c, Long::class.javaPrimitiveType)
            .invoke(null, dispatcher, System.nanoTime())
    if (trampoline.getBoolean(dispatcher))
        c.getDeclaredMethod("access\$performTrampolineDispatch", c).invoke(null, dispatcher)
    Snapshot.sendApplyNotifications()
    looper.idle()
}

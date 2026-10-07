// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.startFactoryReset
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The factory reset runs to the end from the dialog's button, with the learned-words merge off the screen thread. */
@RunWith(RobolectricTestRunner::class)
class FactoryResetTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the reset runs, with shared learned words off too`() {
        val real = ctx.realPrefs()
        real.edit { putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 999); putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false) }
        var done = false
        startFactoryReset(ctx, keyboards = true, learnedWords = false, clipboard = false, custom = false) { done = true }
        // the merge runs on a background thread, the reset is posted to the main one
        val until = System.currentTimeMillis() + 10_000
        while (!done && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        assertTrue(done, "the reset never ran")
        assertFalse(real.contains(Settings.PREF_KEY_LONGPRESS_TIMEOUT))
        assertTrue(helium314.keyboard.latin.personalization.LearnedStores.isShared(real))
    }
}

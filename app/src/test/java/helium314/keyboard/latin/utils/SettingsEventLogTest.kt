// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The event log really writes (a diagnostics log that silently writes nothing is worse than none). */
@RunWith(RobolectricTestRunner::class)
class SettingsEventLogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "settings_events.log")

    private fun settle() { Thread.sleep(300) } // the log writes on its own thread

    @Test fun `watched preferences are logged whoever writes them, only while diagnostics are on`() {
        file.delete()
        val prefs = ctx.realPrefs()
        prefs.edit().putBoolean(Settings.PREF_SWIPE_METRICS, false).commit()
        SettingsEventLog.init(ctx)
        prefs.edit().putString(Settings.PREF_ENABLED_SUBTYPES, "while-off").commit()
        settle()
        assertFalse(file.exists() && file.readText().contains("while-off"))

        prefs.edit().putBoolean(Settings.PREF_SWIPE_METRICS, true).commit()
        prefs.edit().putString(Settings.PREF_ENABLED_SUBTYPES, "en-US").commit()
        prefs.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        SettingsEventLog.log("direct event")
        settle()
        val text = file.readText()
        assertTrue(text.contains("pref ${Settings.PREF_ENABLED_SUBTYPES} = en-US"), text)
        assertTrue(text.contains("pref separate_settings_per_keyboard = true"), text)
        assertTrue(text.contains("direct event") && text.contains("from "), text)
    }
}

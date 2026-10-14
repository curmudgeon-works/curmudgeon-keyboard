// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.net.Uri
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.preferences.ResetKeep
import helium314.keyboard.settings.preferences.keptOnReset
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The review of 2026-10-06 before the production upload: items 5–8. */
@RunWith(RobolectricTestRunner::class)
class PreUploadFixes2Test {
    @Test fun `a renamed keyboard's screens get that keyboard`() {
        val keyboard = withKeyboardName(SettingsSubtype(Locale.US, ""), "Mom's (main), हिंदी")
        val route = SettingsDestination.withKeyboard(SettingsDestination.Languages, keyboard)
        // Navigation hands the screen the decoded argument
        assertEquals(keyboard.toPref(), Uri.decode(route.removePrefix(SettingsDestination.Languages)))
    }

    @Test fun `the logs of how you type stay out of Android's backup`() {
        for (name in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val rules = File("src/main/res/xml/$name").readText()
            assertTrue("domain=\"external\" path=\".\"" in rules, name)
            // the app's files are device-protected (defaultToDeviceProtectedStorage): that's where the logs fall back to
            assertTrue("domain=\"device_file\"" in rules, "$name: device_file")
            for (log in listOf("gesture_corpus.jsonl", "swipe_results.tsv", "learning_events.tsv", "settings_events.log"))
                assertTrue(log in rules, "$name: $log")
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("@xml/data_extraction_rules" in manifest && "@xml/backup_rules" in manifest)
    }

    // (owner 2026-10-11: ticks mean keep; all unticked = full reset)
    @Test fun `a factory reset keeps the one-time flags, and your own themes and Layouts only ticked`() {
        for (flag in listOf("defaults_look_midnight_done", "key_gap_defaults_migrated", "learning_swiping_global",
                "suggestions_refine_moved", "settings_picked_only_done"))
            assertTrue(keptOnReset(flag, ResetKeep()), flag)
        assertTrue(keptOnReset(Settings.PREF_VERSION_CODE, ResetKeep()))
        assertFalse(keptOnReset(idKey(1, "fonts_follow_migrated"), ResetKeep())) // a keyboard's own: its set goes (sets by id, 2026-10-11)
        assertFalse(keptOnReset(Settings.PREF_KEY_HORIZONTAL_GAP, ResetKeep(keyboards = true, own = true)))
        for (saved in listOf(AppearanceLooks.PREF, LayoutPresets.PREF)) {
            assertTrue(keptOnReset(saved, ResetKeep(own = true)), saved)
            assertFalse(keptOnReset(saved, ResetKeep()), saved)
        }
    }

    @Test fun `showing whether a theme is unsaved opens no draft`() {
        assertNull(AppearanceDraft.activeOrNull())
    }
}

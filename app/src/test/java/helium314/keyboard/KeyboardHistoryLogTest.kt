// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import helium314.keyboard.latin.utils.SettingsEventLog
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.deleteKeyboard
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.switchOffKeyboard
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The keyboard history in the settings event log (owner 2026-10-12 K1): one line per action, named (created, copied,
 * restored, switched off, switched on, deleted, renamed), the visible name first, the id after; a line when a keyboard
 * leaves the list with its records left behind; the whole log switched by "Log settings changes" (on by default), no
 * longer by "Log swipe results".
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardHistoryLogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val file get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "settings_events.log")
    private val english by lazy { available("en-US").withId(1) }
    private val german by lazy { available("de-DE").withId(2) }
    private val germanName get() = keyboardName(german, ctx)
    private val filesDirBefore = KeyboardProfiles.filesDir

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.filesDir = File(ctx.cacheDir, "history_${System.nanoTime()}").apply { mkdirs() }
        SubtypeSettings.init(ctx)
        useKeyboards(ctx, real, english, german)
        // (German's own settings: the records a switch-off leaves behind)
        real.edit().putString(KeyboardProfiles.ownKey(german.keyboardId!!, "test_own_setting"), "x").commit()
        SettingsEventLog.init(ctx)
        settle()
        file.delete() // (the test's own file, not a device's)
    }

    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        settle()
        KeyboardProfiles.filesDir = filesDirBefore
    }

    /** What the main thread posted (the records check) and the log's writes, done. */
    private fun settle() { shadowOf(Looper.getMainLooper()).idle(); SettingsEventLog.flushForTest() }
    private fun lines() = if (file.exists()) file.readLines().filter { !it.startsWith("    from ") } else emptyList()
    private fun linesWith(text: String) = lines().filter { it.contains(text) }

    // fail before: two "keyboard removed" lines with the definition only, nothing named a delete
    @Test fun `a delete writes exactly one deleted line, the name first`() {
        deleteKeyboard(ctx, german)
        settle()
        val deleted = linesWith("keyboard deleted:")
        assertEquals(1, deleted.size, lines().joinToString("\n"))
        assertTrue(deleted.single().contains("keyboard deleted: $germanName (${german.keyboardId};"), deleted.single())
        assertEquals(emptyList(), linesWith("keyboard removed"))
        assertEquals(emptyList(), linesWith("records kept"), "a delete takes its records: no straggler line")
    }

    // fail before: both were "keyboard removed" / "keyboard added", and nothing said the records stayed
    @Test fun `switch off and switch on write their own lines, and the records left behind are noted`() {
        switchOffKeyboard(ctx, german.toAdditionalSubtype())
        settle()
        assertEquals(1, linesWith("keyboard switched off: $germanName").size, lines().joinToString("\n"))
        val left = linesWith("keyboard left the list, records kept")
        assertEquals(1, left.size, lines().joinToString("\n"))
        assertTrue(left.single().contains("settings set") && left.single().contains(germanName), left.single())

        assertTrue(SwitchedOffKeyboards.switchOn(ctx, german))
        settle()
        assertEquals(1, linesWith("keyboard switched on: $germanName").size, lines().joinToString("\n"))
        assertEquals(emptyList(), linesWith("keyboard added"))
    }

    // the tile's Delete of a switched-off keyboard: fail before, nothing in the file named it
    @Test fun `deleting a switched-off keyboard from its tile writes one deleted line`() {
        switchOffKeyboard(ctx, german.toAdditionalSubtype())
        settle()
        deleteKeyboard(ctx, german)
        settle()
        val deleted = linesWith("keyboard deleted: $germanName")
        assertEquals(1, deleted.size, lines().joinToString("\n"))
        assertTrue(deleted.single().contains("was switched off"), deleted.single())
    }

    // fail before: the log followed "Log swipe results" (off by default), so a delete wrote nothing
    @Test fun `the log is on by default, and Log settings changes off writes nothing`() {
        assertFalse(real.contains(Settings.PREF_LOG_SETTINGS), "nothing stored: the default")
        assertFalse(real.getBoolean(Settings.PREF_SWIPE_METRICS, false))
        switchOffKeyboard(ctx, german.toAdditionalSubtype())
        settle()
        assertEquals(1, linesWith("keyboard switched off").size)
        file.delete()
        real.edit().putBoolean(Settings.PREF_LOG_SETTINGS, false).commit()
        SwitchedOffKeyboards.switchOn(ctx, german)
        deleteKeyboard(ctx, german)
        settle()
        assertEquals(emptyList(), lines())
    }
}

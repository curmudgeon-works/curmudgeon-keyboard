// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.RestoreChoiceDialogFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.latin.personalization.available
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The restore dialog, driven (owner 2026-10-11): "Delete app data and restore from backup" on top, checked; the keyboard
 * list greyed out and no line; OK asks before it wipes. Unchecked: the keyboards enabled and ticked, the line shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class RestoreTopCheckboxUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zip by lazy { File(ctx.cacheDir, "top-checkbox-${System.nanoTime()}.zip") }
    private var scenario: ActivityScenario<SettingsActivity>? = null
    // (owner 2026-10-12: the top checkbox reads "Full restore (delete app data)"; was "Delete app data and restore from backup")
    private val top = "Full restore (delete app data)"
    // (owner 2026-10-12: the line under the unchecked checkbox; was "App data won't be deleted. Each selected keyboard
    // will be merged or added, based on your selection on next screens.")
    private val line = "Prepare to do super-complex merging! Select what you want to bring in from the backup:"
    private val marker = Settings.PREF_CLIPBOARD_HISTORY_SIZE

    @Before fun setUp() {
        ctx.realPrefs().edit().clear().putInt(marker, 10).commit()
        KeyboardProfiles.loadGroups(ctx.realPrefs())
        SubtypeSettings.init(ctx)
        val fr = available("fr")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(fr)),
                Settings.PREF_SELECTED_SUBTYPE to fr.toPref(), marker to 99), z)
            z.closeEntry()
        }
    }
    @After fun tearDown() {
        scenario?.close()
        zip.delete()
        ctx.realPrefs().edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.loadGroups(ctx.realPrefs())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun show() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { RestoreChoiceDialogFrom(zip) { } } }
        // (the backup is read off the main thread, "Reading backup…" meanwhile: R5, 2026-10-11)
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodesWithText(top).fetchSemanticsNodes().isEmpty()) {
            assertTrue(System.currentTimeMillis() < end, "the restore dialog never came")
            Thread.sleep(20)
            compose.waitForIdle()
        }
    }

    /** The checkbox of the row saying [text]. */
    private fun box(text: String) =
        compose.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasParent(androidx.compose.ui.test.hasText(text, substring = true)))

    @Test fun `checked by default, no keyboards or rows below, no line, OK asks before wiping`() {
        show()
        box(top).assertIsOn()
        compose.onNodeWithText("French", substring = true).assertDoesNotExist()
        compose.onNodeWithText(line).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(android.R.string.ok)).performClick()
        // the question, nothing wiped yet
        assertEquals(2, compose.onAllNodesWithText("Delete and restore").fetchSemanticsNodes().size)
        assertEquals(10, ctx.realPrefs().getInt(marker, 0))
        // its Cancel: back to the restore dialog, still checked
        compose.onNodeWithText(ctx.getString(android.R.string.cancel)).performClick()
        box(top).assertIsOn()
        assertTrue(zip.exists(), "the backup file stays while the dialog is open")
    }

    @Test fun `unchecked, keyboards enabled and ticked, the line shown`() {
        show()
        compose.onNodeWithText(top).performClick()
        box(top).assertIsOff()
        compose.onNodeWithText("French", substring = true).assertIsEnabled()
        box("French").assertIsOn()
        compose.onNodeWithText(line).assertExists()
    }
}

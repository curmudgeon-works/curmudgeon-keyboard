// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.RestoreChoiceDialogFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

/**
 * The restore dialog's groups (owner 2026-10-12, v3): "Full restore (delete app data)" on top; unchecked, the line
 * "Prepare to do super-complex merging! …", then KEYBOARDS · RESTORE THEIR (Settings · Dictionaries, with the clash
 * subtext) · AND MERGE (Learned words · Clipboard history); no Custom words row, no "Bring with them" or "Also restore",
 * no row for other languages' dictionaries. The full restore's greyed view shows the same groups, ticked.
 * The device: English (k1) with its own French dictionary; the backup: French (k6) with another, Urdu's dictionary,
 * personal dictionary words and clipboard history, no learned words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class RestoreDialogGroupsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real get() = ctx.realPrefs()
    private val zip by lazy { File(ctx.cacheDir, "groups-${System.nanoTime()}.zip") }
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val top = "Full restore (delete app data)"
    private val line = "Prepare to do super-complex merging! Select what you want to bring in from the backup:"
    private val frDict get() = File(ctx.filesDir, "dicts${File.separator}fr${File.separator}main_user.dict")

    @Before fun setUp() {
        helium314.keyboard.reviveComposeMainDispatcher()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        useKeyboards(ctx, real, available("en-US").withId(1))
        frDict.parentFile!!.mkdirs(); frDict.writeText("device")
        val fr = available("fr").withId(6)
        val list = SubtypeSettings.createPrefSubtypes(listOf(fr))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                Settings.PREF_SELECTED_SUBTYPE to fr.toPref(), KeyboardIdUpgrade.DONE to true, KeyboardProfiles.NO_COMMON_SET_DONE to true), z)
            z.closeEntry()
            for ((path, text) in listOf("dicts/fr/main_user.dict" to "backup", "dicts/ur/main_user.dict" to "urdu",
                    "personal_dictionary.json" to """[{"word":"Meera","frequency":"200"}]""", "heliboard.db" to "clips")) {
                z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
    }

    @After fun tearDown() {
        scenario?.close()
        zip.delete()
        File(ctx.filesDir, "dicts").deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun show() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { RestoreChoiceDialogFrom(zip) { } } }
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodesWithText(top).fetchSemanticsNodes().isEmpty()) {
            assertTrue(System.currentTimeMillis() < end, "the restore dialog never came")
            Thread.sleep(20)
            compose.waitForIdle()
        }
    }

    private fun box(text: String) =
        compose.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasParent(androidx.compose.ui.test.hasText(text, substring = true)))

    private fun gone(vararg texts: String) = texts.forEach { compose.onNodeWithText(it, substring = true).assertDoesNotExist() }

    // fail before: the old headings ("Bring with them", "Also restore"), "Custom words", "Added dictionaries", and the
    // Learned words row missing for a backup with personal dictionary words only
    @Test fun `unchecked, the line, then KEYBOARDS, RESTORE THEIR and AND MERGE with their rows`() {
        show()
        compose.onNodeWithText(top).performClick()
        compose.onNodeWithText(line).assertExists()
        for (heading in listOf("KEYBOARDS", "RESTORE THEIR", "AND MERGE")) compose.onNodeWithText(heading).assertExists()
        box("(new)").assertIsOn() // (French: no keyboard of its language here)
        compose.onNodeWithText("Settings").assertIsEnabled()
        box("Dictionaries").assertIsOn()
        compose.onNodeWithText("French: Backup is prioritized, yours is kept aside as \"…prev\"").assertExists()
        // (the Learned words row brings the personal dictionary words: shown for them alone)
        box("Learned words").assertIsOn()
        compose.onNodeWithText("Clipboard history").assertExists()
        gone("Custom words", "Bring with them", "Also restore", "Added dictionaries", "other languages", "Urdu",
            "App data won't be deleted")
    }

    @Test fun `the full restore's greyed view shows the same groups, ticked`() {
        show()
        compose.onNodeWithText(line).assertDoesNotExist()
        for (heading in listOf("KEYBOARDS", "RESTORE THEIR", "AND MERGE")) compose.onNodeWithText(heading).assertExists()
        for (row in listOf("Settings", "Dictionaries", "Learned words", "Clipboard history")) {
            box(row).assertIsOn()
            box(row).assertIsNotEnabled()
        }
        gone("Custom words", "Bring with them", "Also restore", "FOR OTHER CLASHES")
    }
}

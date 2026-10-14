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
 * The restore dialog's sections (owner 2026-10-12, the restore redesign): "Full restore (delete app data)" on top;
 * unchecked, a row per backup keyboard and saved item, names as in the backup (no "(new)"), by section: KEYBOARDS ·
 * LAYOUTS & POPUPS · THEMES · PALETTES & IMAGES · FONTS; "Merge required" and "Required for …" under a row; "Tricky
 * merging ahead!" only with a ticked row that needs merging. No rows for settings, dictionaries, learned words or the
 * clipboard (they always come), no "For other clashes". The full restore shows nothing below the box.
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
    private val line = "Tricky merging ahead!"
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

    // fail before: "(new)" after French, the RESTORE THEIR and AND MERGE rows, and "Prepare to do super-complex merging!"
    @Test fun `unchecked, KEYBOARDS with the backup's names, no rows for what always comes, no line with nothing to merge`() {
        show()
        compose.onNodeWithText(top).performClick()
        compose.onNodeWithText("KEYBOARDS").assertExists()
        box("French").assertIsOn()
        compose.onNodeWithText("French").assertIsEnabled()
        compose.onNodeWithText(line).assertDoesNotExist() // (French: no keyboard of its language here, nothing to merge)
        // (nothing to ask: the button restores)
        assertTrue(compose.onAllNodesWithText("Restore").fetchSemanticsNodes().size >= 2) // (the title and the button)
        gone("(new)", "RESTORE THEIR", "AND MERGE", "Settings", "Dictionaries", "Learned words", "Clipboard history",
            "FOR OTHER CLASHES", "Custom words", "Bring with them", "Also restore", "Urdu", "Prepare to do", "App data won't be deleted")
    }

    // owner 2026-10-12: the backup's English (the device's, same id) on its Sunset, the device's Sunset other colors
    @Test fun `a row that needs merging says so, an item a ticked keyboard uses is required, and Next leads to the questions`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_KEY_HORIZONTAL_GAP to 1f))), now = true)
        val en = available("en-US").withId(1)
        val list = SubtypeSettings.createPrefSubtypes(listOf(en))
        val sunset = org.json.JSONObject().put("name", "Sunset").put("values", org.json.JSONObject()
            .put(Settings.PREF_KEY_HORIZONTAL_GAP, org.json.JSONObject().put("f", 3.0)))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                Settings.PREF_SELECTED_SUBTYPE to en.toPref(), KeyboardIdUpgrade.DONE to true, KeyboardProfiles.NO_COMMON_SET_DONE to true,
                AppearanceLooks.PREF to org.json.JSONArray(listOf(sunset)).toString(),
                KeyboardProfiles.ownKey("k1", AppearanceLooks.PREF_SELECTED) to "Sunset"), z)
            z.closeEntry()
        }
        show()
        compose.onNodeWithText(top).performClick()
        compose.onNodeWithText(line).assertExists()
        for (heading in listOf("KEYBOARDS", "THEMES")) compose.onNodeWithText(heading).assertExists()
        compose.onNodeWithText("Merge required").assertExists() // (under English)
        compose.onNodeWithText("Required for English (US) · Merge required").assertExists()
        box("Sunset").assertIsOn()
        box("Sunset").assertIsNotEnabled()
        // English unticked: Sunset is free again
        compose.onNodeWithText("English (US)").performClick()
        compose.onNodeWithText("Required for English (US) · Merge required").assertDoesNotExist()
        box("Sunset").assertIsEnabled()
        compose.onNodeWithText("English (US)").performClick()
        compose.onNodeWithText("Next").performClick()
        // (A4: the theme's question first, then the keyboard's)
        compose.onNodeWithText("1 of 2").assertExists()
        compose.onNodeWithText("Suggested: same keyboard as in the backup").assertDoesNotExist()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("2 of 2").assertExists()
        compose.onNodeWithText("Suggested: same keyboard as in the backup").assertExists()
    }

    // owner 2026-10-12: a full restore has nothing to choose, nothing shows below the box
    @Test fun `the full restore shows nothing below the box`() {
        show()
        compose.onNodeWithText(line).assertDoesNotExist()
        gone("KEYBOARDS", "THEMES", "RESTORE THEIR", "AND MERGE", "Settings", "Dictionaries", "Learned words", "Clipboard history",
            "Custom words", "Bring with them", "Also restore", "FOR OTHER CLASHES", "Merge required")
    }
}

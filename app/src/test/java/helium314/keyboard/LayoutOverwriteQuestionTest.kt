// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.LayoutEditDialog
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The custom key layout editor's Save when other keyboards use the layout, through the dialog (owner 2026-10-18:
 * "select those that should take this change. The others will retain their current settings named Warm 2 / Custom <-
 * as applicable. This should be our standard pattern."; "all should come selected yes"): one checkbox per other
 * keyboard, all selected; the ones not selected keep their keys as "My numrow 2"; no Yes / No, no name to type.
 * A ("Work", enabled, edited) = k1, B ("Home", enabled) = k2, C (German, switched off) = k3; all pick "My numrow".
 */
@RunWith(RobolectricTestRunner::class)
class LayoutOverwriteQuestionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val type = LayoutType.NUMBER_ROW
    private val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
    private val keeper = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withLayout(type, numrow).withId(1)
    private val b = withKeyboardName(SettingsSubtype(Locale.US, ""), "Home").withLayout(type, numrow).withId(2)
    private val c = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withLayout(type, numrow).withId(3)
    private val newKeys by lazy { ctx.assets.open("layouts/number_row/number_row_basic.txt").reader().readText() }
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private val folder get() = file(numrow).parentFile!!
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private var dismissed = false

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        folder.deleteRecursively(); folder.mkdirs()
        file(numrow).writeText("old keys")
        LayoutUtilsCustom.onLayoutFileChanged()
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b, c)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        dismissed = false
    }
    @After fun tearDown() {
        scenario?.close()
        folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun idle() = compose.waitForIdle()
    private fun additional() = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!)
    private fun layoutOf(id: Int) = additional().single { it.keyboardId == kid(id) }.layoutName(type)
    private val bName get() = keyboardName(b, ctx)
    private val cName get() = ctx.getString(R.string.keyboard_switched_off, keyboardName(c, ctx))
    private fun checkbox(name: String) = compose.onNode(isToggleable() and hasAnyAncestor(isDialog()) and hasParent(hasText(name)))

    /** A's layout editor on "My numrow" with new keys, its Save tapped: the question about B and C. */
    private fun saveNewKeys() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            LayoutEditDialog(onDismissRequest = { dismissed = true }, layoutType = type, initialLayoutName = numrow,
                startContent = newKeys, keyboard = a, isNameValid = null)
        } }
        idle()
        compose.onNodeWithText(ctx.getString(R.string.save)).performClick(); idle()
        compose.onNodeWithText("Overwrite My numrow?").assertExists()
    }

    @Test fun `the question - select those that should take this change, all selected, the others named My numrow 2`() {
        saveNewKeys()
        compose.onNodeWithText("Also used by the ones below. Select those that should take this change. " +
            "The others keep their current keys, named “My numrow 2”.").assertExists()
        checkbox(bName).assertIsOn()
        checkbox(cName).assertIsOn()
        compose.onNodeWithText("Change their keys too?", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Yes").assertDoesNotExist()
        compose.onNodeWithText("No").assertDoesNotExist()
        compose.onNodeWithText("Overwrite").assertExists() // (the button answers the title)
    }

    @Test fun `all selected - every one takes the new keys, no My numrow 2`() {
        saveNewKeys()
        compose.onNodeWithText("Overwrite").performClick(); idle()
        assertEquals(newKeys, file(numrow).readText())
        assertEquals(numrow, layoutOf(2)); assertEquals(numrow, layoutOf(3))
        assertFalse(file(keeper).exists())
        assertTrue(dismissed)
    }

    @Test fun `one not selected keeps its keys as My numrow 2, no name asked, the selected one takes the change`() {
        saveNewKeys()
        checkbox(cName).performClick(); idle()
        checkbox(cName).assertIsOff()
        compose.onNodeWithText("Overwrite").performClick(); idle()
        // (no name prompt: was "Name for their layout (with the keys they have now)")
        compose.onNodeWithText(ctx.getString(R.string.layout_overwrite_name_title)).assertDoesNotExist()
        assertEquals(newKeys, file(numrow).readText())
        assertEquals("old keys", file(keeper).readText())
        assertEquals(numrow, layoutOf(2)) // B, selected: the new keys
        assertEquals(keeper, layoutOf(3)) // C, not selected: its keys of now, as "My numrow 2"
        assertTrue(dismissed)
    }

    @Test fun `the keepers' name counts up past a taken My numrow 2`() {
        file(keeper).writeText("other"); LayoutUtilsCustom.onLayoutFileChanged()
        assertEquals(LayoutUtilsCustom.getLayoutName("My numrow 3", type), LayoutUtilsCustom.keeperLayoutName(numrow, type, ctx))
    }
}

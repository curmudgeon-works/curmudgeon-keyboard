// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isEditable
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.KeyPopupsSection
import helium314.keyboard.settings.screens.deletePopupSet
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The popups section on Layout & Typing follows what the Layouts row on the same screen puts on the keyboard (review
 *  2026-10-08): its row's name, and "Save current popup layout" saves the popups the keyboard has now. */
@RunWith(RobolectricTestRunner::class)
class KeyPopupsSectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val keyboard = SettingsSubtype(java.util.Locale.US, "")
    private val p1 = "{\"a\":[\"b\"]}"
    private val p2 = "{\"c\":[\"d\"]}"

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SettingsMode.set(ctx, true) // (the presets row is advanced; the state outlives a test)
    }
    @After fun tearDown() {
        SettingsMode.set(ctx, Defaults.PREF_ADVANCED_SETTINGS)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun open(): ActivityScenario<SettingsActivity> = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
        it.setContent { KeyPopupsSection(keyboard) { } }
    }

    /** As the Layouts row's OK does it (LayoutPresetsPreference: applySettings while previewed, then keep). */
    private fun applyLayout(preset: LayoutPresets.Preset) {
        LayoutPresets.applySettings(ctx, LayoutPresets.settingsFor(keyboard, preset))
        LayoutPresets.restorePopupSet(ctx, keyboard, preset)
        ctx.prefs().edit().putString(LayoutPresets.PREF_SELECTED, preset.name).commit()
    }

    @Test fun `after a popup set delete, a Layout applied on the same screen shows its popups' name`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("S", "all", null, mapOf("a" to listOf("b")))))
        ctx.prefs().edit().putString(KeyPopupOverrides.PREF, p1).putString(KeyPopupOverrides.PREF_SELECTED_SET, "S").commit()
        deletePopupSet(ctx, "S")
        open().use {
            compose.onNodeWithText("Custom").assertExists() // the keyboard keeps its popups
            applyLayout(LayoutPresets.builtIn(ctx).first()) // the Curmudgeon Layout: no popups of its own
            compose.waitForIdle()
            compose.onNodeWithText("Curmudgeon").assertExists()
        }
    }

    @Test fun `Save current popup layout after a Layout apply saves the popups the keyboard has now`() {
        // a Layout L saved with set S (popups p2); S deleted since; the keyboard now has p1 and no set
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("S", "all", null, mapOf("c" to listOf("d")))))
        ctx.prefs().edit().putString(KeyPopupOverrides.PREF, p2).putString(KeyPopupOverrides.PREF_SELECTED_SET, "S").commit()
        val layout = LayoutPresets.Preset("L", LayoutPresets.snapshot(ctx, keyboard))
        deletePopupSet(ctx, "S")
        ctx.prefs().edit().putString(KeyPopupOverrides.PREF, p1).commit()
        open().use {
            compose.onNodeWithText("Custom").assertExists()
            applyLayout(layout) // p2 on the keyboard, S back in the list
            compose.waitForIdle()
            compose.onNodeWithText("S").assertExists() // the set the Layout brought back
            compose.onNodeWithText("Preset popups").performClick()
            compose.onNodeWithText("Save current popup layout").performClick()
            compose.onNode(isEditable()).performTextReplacement("New")
            compose.onNodeWithText(ctx.getString(android.R.string.ok)).performClick()
            compose.waitForIdle()
        }
        assertEquals(mapOf("c" to listOf("d")), KeyPopupOverrides.loadSets(real).first { it.name == "New" }.overrides)
        assertEquals(p2, ctx.prefs().getString(KeyPopupOverrides.PREF, null), "the keyboard's popups were put back to p1")
    }
}

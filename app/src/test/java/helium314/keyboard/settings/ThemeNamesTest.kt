// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.idKey
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.createAppearanceSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Review 2026-10-08 M6: Save as and rename on the Themes list checked only the user's themes, so a user theme could
 *  take a built-in's name, and renaming it renamed every keyboard's built-in choice. The names are checked against the
 *  built-in themes too (2026-10-10), as the Layouts list does. */
@RunWith(RobolectricTestRunner::class)
class ThemeNamesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val curmudgeon get() = ctx.getString(R.string.theme_preset_curmudgeon)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun open(): ActivityScenario<SettingsActivity> = ActivityScenario.launch(SettingsActivity::class.java).onActivity { a ->
        a.setContent { createAppearanceSettings(a).first { it.key == SettingsWithoutKey.APPEARANCE_LOOKS }.Preference() }
    }
    private fun typeAndOk(name: String) {
        compose.onNode(isEditable()).performTextReplacement(name)
        compose.onAllNodesWithText(ctx.getString(android.R.string.ok)).onLast().performClick() // (the name dialog's, over the list)
        compose.waitForIdle()
    }
    private fun names() = AppearanceLooks.load(ctx.prefs()).map { it.name }

    @Test fun `Save as doesn't take a built-in theme's name`() {
        open().use {
            compose.onNodeWithText(ctx.getString(R.string.appearance_looks)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.appearance_look_save)).performClick()
            typeAndOk(curmudgeon)
            assertEquals(emptyList(), names())
            typeAndOk("Mine") // (the dialog still open: another name is taken)
            assertEquals(listOf("Mine"), names())
        }
    }

    @Test fun `rename doesn't take a built-in theme's name, so keyboards with the built-in keep it`() {
        AppearanceLooks.save(ctx.prefs(), listOf(AppearanceLooks.Look("Mine", mapOf(Settings.PREF_THEME_STYLE to "Rounded"))))
        real.edit().putString(idKey(1, AppearanceLooks.PREF_SELECTED), curmudgeon).commit() // a keyboard with the built-in
        open().use {
            compose.onNodeWithText(ctx.getString(R.string.appearance_looks)).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Mine")) // (after the built-in ones)
            compose.onNodeWithText("Mine").performTouchInput { longClick() } // (a long press renames, 2026-10-11)
            typeAndOk(curmudgeon)
            assertEquals(listOf("Mine"), names())
            typeAndOk("Work")
            assertEquals(listOf("Work"), names())
        }
        assertEquals(curmudgeon, real.getString(idKey(1, AppearanceLooks.PREF_SELECTED), null))
    }
}

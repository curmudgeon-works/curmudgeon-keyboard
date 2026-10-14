// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.DiscardChangesDialog
import helium314.keyboard.settings.dialogs.UnsavedChangesDialog
import helium314.keyboard.settings.dialogs.savedPaletteNames
import helium314.keyboard.settings.dialogs.savedSubScreenNames
import helium314.keyboard.settings.screens.customizePopupsSaved
import helium314.keyboard.settings.screens.layoutFilesSaved
import helium314.keyboard.settings.screens.paletteEditorSaved
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Owner 2026-10-11 ("send the discard warning yes"): the Discard questions of Appearance and Layout & Typing name the
 *  sub-screen saves they also undo; without such saves they are as before. */
@RunWith(RobolectricTestRunner::class)
class DiscardWarningDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setUp() { ctx.realPrefs().edit().clear().commit() }
    @After fun tearDown() {
        scenario?.close()
        AppearanceDraft.close(); LayoutDraft.close()
        ctx.realPrefs().edit().clear().commit()
        helium314.keyboard.latin.utils.SubtypeSettings.reloadEnabledSubtypes(ctx)
        helium314.keyboard.latin.settings.KeyboardProfiles.loadGroups(ctx.realPrefs())
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { content() } }
    }

    @Test fun `Appearance's Discard names the palette saved in the palette editor`() {
        AppearanceDraft.of(ctx)
        paletteEditorSaved("Warm", "Warm")
        show { DiscardChangesDialog({ }, savedPaletteNames(ctx, AppearanceDraft.of(ctx).savedPalettes())) { } }
        compose.onNodeWithText(ctx.getString(R.string.appearance_reject_title)).assertExists()
        compose.onNodeWithText("This also undoes what you saved in: palette \"Warm\"").assertExists()
    }

    @Test fun `Layout and Typing's leave dialog names both sub-screens saved, in that order`() {
        LayoutDraft.of(ctx, "en_US:")
        customizePopupsSaved(); layoutFilesSaved()
        show { UnsavedChangesDialog({ }, { }, { }, savedSubScreenNames(ctx, LayoutDraft.of(ctx, "en_US:").savedSubScreens())) }
        compose.onNodeWithText("Discard and exit also undoes what you saved in: " +
            ctx.getString(R.string.key_popups_full) + " · " + ctx.getString(R.string.layout_files)).assertExists()
    }

    @Test fun `without sub-screen saves the Discard question has no extra line`() {
        AppearanceDraft.of(ctx)
        show { DiscardChangesDialog({ }, savedPaletteNames(ctx, AppearanceDraft.of(ctx).savedPalettes())) { } }
        compose.onNodeWithText(ctx.getString(R.string.appearance_reject_title)).assertExists()
        compose.onNodeWithText("This also undoes", substring = true).assertDoesNotExist()
    }
}

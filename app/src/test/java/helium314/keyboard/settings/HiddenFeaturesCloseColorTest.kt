// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.AlertDialog
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.AboutScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import kotlin.test.assertEquals

/** About › Description of hidden features: its Close is in the settings theme's primary color, as the Compose dialogs'
 *  buttons are (owner 2026-10-18: "fine to code clode button orange (or the right theme color)"). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class HiddenFeaturesCloseColorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @After fun tearDown() { scenario?.close(); ctx.realPrefs().edit().clear().commit() }

    @Test fun `the hidden features dialog's Close has the theme's primary color`() {
        var primary = 0
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            Theme(dark = false) { primary = MaterialTheme.colorScheme.primary.toArgb(); AboutScreen(onClickBack = { }) }
        } }
        compose.waitForIdle()
        compose.onNodeWithText(ctx.getString(R.string.hidden_features_title)).performClick()
        compose.waitForIdle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals(primary, dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
    }
}

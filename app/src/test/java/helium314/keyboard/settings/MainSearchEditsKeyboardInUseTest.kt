// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.storeKeyboards
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Round-7c review 20261014_L11_2 (owner: "add them"): with separate settings on, a result of the main screen's search
 * edits the keyboard in use (KeyboardProfiles.mainEditingId), not the first keyboard. Two keyboards, the second in
 * use; the main search's "Numbers row" row tapped as the search shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class MainSearchEditsKeyboardInUseTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setUp() {
        helium314.keyboard.reviveComposeMainDispatcher()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        val en = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first().toSettingsSubtype().withId(1)
        val fr = SubtypeSettings.getResourceSubtypesForLocale(Locale.FRENCH).first().toSettingsSubtype().withId(2)
        storeKeyboards(ctx, real, listOf(en, fr), selected = fr, reload = false)
        KeyboardProfiles.enable(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
        SettingsActivity.settingsContainer = SettingsContainer(ctx)
    }
    @After fun tearDown() {
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Test fun `a main search result edits the keyboard in use with separate settings on`() {
        val title = ctx.getString(R.string.show_numbers_row)
        val row = SettingsActivity.settingsContainer.filter(title, ctx).first { it.key == Settings.PREF_SHOW_NUMBER_ROW }
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { row.Preference() } }
        compose.waitForIdle()
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        assertEquals(!Defaults.PREF_SHOW_NUMBER_ROW, real.getBoolean(idKey(2, Settings.PREF_SHOW_NUMBER_ROW), Defaults.PREF_SHOW_NUMBER_ROW))
        assertFalse(real.contains(idKey(1, Settings.PREF_SHOW_NUMBER_ROW)), "the first keyboard's set untouched")
    }
}

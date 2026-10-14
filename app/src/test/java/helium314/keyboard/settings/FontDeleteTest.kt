// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.FontDeleteButton
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owner 2026-10-11: global delete ("Global only let's do. With warning telling where it's being used, or mentioning
 *  it's not used anywhere"): the trash on a font always asks first; the question names every keyboard (the one being
 *  edited too) and saved theme that goes back to the default font, or says nothing uses it. Keyboard A ("Work") = set 1,
 *  B (German) = set 2. */
@RunWith(RobolectricTestRunner::class)
class FontDeleteTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work")
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private val font get() = File(FontLibrary.dir(ctx), "Mine.ttf")
    private val value = FontLibrary.PREFIX + "Mine.ttf"

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        assertEquals(1, KeyboardProfiles.idFor(real, a))
        assertEquals(2, KeyboardProfiles.idFor(real, b))
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        FontLibrary.dir(ctx).mkdirs(); font.writeText("not really a font")
    }
    @After fun tearDown() {
        AppearanceDraft.close(); FontLibrary.dir(ctx).deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun open(): ActivityScenario<SettingsActivity> = ActivityScenario.launch(SettingsActivity::class.java).onActivity { act ->
        act.setContent { FontDeleteButton(value) { } }
    }

    @Test fun `a font in use - the question names its keyboards and saved themes, Delete sends them all to the default`() {
        real.edit {
            putString("p1/${Settings.PREF_KEY_FONT}", value) // (A, the keyboard being edited: named too)
            putString("p2/${Settings.PREF_SUGGESTION_FONT}", value)
        }
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_HINT_FONT to value))), now = true)
        open().use {
            compose.onNodeWithContentDescription(ctx.getString(R.string.delete)).performClick()
            compose.onNodeWithText("Delete the font \"Mine.ttf\"?").assertExists()
            compose.onNodeWithText("These go back to the default font:\n${keyboardName(a, ctx)}\n${keyboardName(b, ctx)}\ntheme Sunset").assertExists()
            assertTrue(font.exists()) // (nothing done before the answer)
            compose.onNodeWithText(ctx.getString(R.string.delete)).performClick()
            compose.waitForIdle()
        }
        assertFalse(font.exists())
        assertEquals("default", real.getString("p1/${Settings.PREF_KEY_FONT}", null))
        assertEquals("default", real.getString("p2/${Settings.PREF_SUGGESTION_FONT}", null))
        assertEquals("default", AppearanceLooks.load(real).single().values[Settings.PREF_HINT_FONT])
        assertFalse("Mine.ttf" in FontLibrary.names(ctx))
    }

    @Test fun `a font nothing uses - the question says so, Cancel keeps it`() {
        open().use {
            compose.onNodeWithContentDescription(ctx.getString(R.string.delete)).performClick()
            compose.onNodeWithText("Delete the font \"Mine.ttf\"?").assertExists()
            compose.onNodeWithText("No keyboard or saved theme uses it.").assertExists()
            compose.onNodeWithText(ctx.getString(android.R.string.cancel)).performClick()
            compose.waitForIdle()
            assertTrue(font.exists())
            assertTrue("Mine.ttf" in FontLibrary.names(ctx))
            compose.onNodeWithContentDescription(ctx.getString(R.string.delete)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.delete)).performClick()
            compose.waitForIdle()
        }
        assertFalse(font.exists())
    }
}

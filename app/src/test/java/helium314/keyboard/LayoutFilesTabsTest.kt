// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.displayNameId
import helium314.keyboard.latin.utils.LayoutUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.screens.LayoutFilesScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Customize keys and popups with JSON" (the layout files screen), its new tabs through the screen itself: the symbols
 * page (JSON-S, owner 2026-10-13), saved by the same rule as the other tabs: changed text, the keyboard's own unnamed
 * copy; the built-in text, the built-in layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowInputMethodManager2::class])
class LayoutFilesTabsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val kb1 = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first().toSettingsSubtype().withId(1)
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val layoutsDir get() = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts")
    // the activity loads the keyboard's settings into the Settings singleton: put back as found (see SubScreenSaveRealPathTest)
    private val loadedSettings = Settings::class.java.getDeclaredField("mSettingsValues").apply { isAccessible = true }
    private var settingsBefore: Any? = null

    @Before fun setUp() {
        reviveComposeMainDispatcher()
        settingsBefore = loadedSettings.get(Settings.getInstance())
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, listOf(kb1))
        layoutsDir.deleteRecursively()
    }
    @After fun tearDown() {
        scenario?.close()
        loadedSettings.set(Settings.getInstance(), settingsBefore)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        LayoutDraft.close()
        layoutsDir.deleteRecursively(); SubtypeUtilsAdditional.changedTo.clear()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun show() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { LayoutFilesScreen(kb1, {}) } }
        compose.waitForIdle()
    }
    private fun kbNow() = SubtypeUtilsAdditional.current(kb1.toPref()).toSettingsSubtype()
    private fun field() = compose.onNode(isEditable())
    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun saveWithTick() {
        compose.onNodeWithContentDescription(ctx.getString(R.string.appearance_accept)).performClick()
        compose.onNode(hasText(ctx.getString(R.string.appearance_accept_all)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
    }

    // fail before: there was no Symbols tab
    @Test fun `the Symbols tab shows the keyboard's symbols page, and a change saves the keyboard's own copy`() {
        show()
        compose.onNodeWithText(ctx.getString(LayoutType.SYMBOLS.displayNameId)).performScrollTo().performClick()
        compose.waitForIdle()
        val builtIn = LayoutUtils.getContent(LayoutType.SYMBOLS, "symbols", ctx)
        assertEquals(builtIn, fieldText())
        field().performTextReplacement("$builtIn\n"); compose.waitForIdle()
        saveWithTick()
        val name = kbNow().layoutName(LayoutType.SYMBOLS)!!
        assertTrue(LayoutUtilsCustom.isPrivateLayout(name), "not the keyboard's own copy: $name")
        assertEquals("$builtIn\n", LayoutUtilsCustom.getLayoutFile(name, LayoutType.SYMBOLS, ctx).readText())
        // edited back to the built-in text: the built-in layout again
        field().performTextReplacement(builtIn); compose.waitForIdle()
        saveWithTick()
        assertEquals("symbols", kbNow().layoutName(LayoutType.SYMBOLS))
    }
}

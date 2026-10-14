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
 * page (JSON-S) and the letters (JSON-L, owner 2026-10-13), saved by the same rule as the other tabs: changed text, the keyboard's own unnamed
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

    // fail before (JSON-L): there was no letters tab, the screen opened on the number row
    @Test fun `the Keys tab comes first with the keyboard's letters, saves its own copy, and Built-in names layout and language`() {
        show()
        val default = helium314.keyboard.settings.screens.languageDefaultLayout(Locale.US)
        val builtIn = LayoutUtils.getContentWithPlus(default, Locale.US, ctx)
        assertEquals(builtIn, fieldText()) // (the first tab, open)
        field().performTextReplacement("$builtIn\n"); compose.waitForIdle()
        saveWithTick()
        val name = kbNow().mainLayoutName()!!
        assertTrue(LayoutUtilsCustom.isPrivateLayout(name), "not the keyboard's own copy: $name")
        assertEquals(LayoutUtilsCustom.scopeFor(Locale.US), LayoutUtilsCustom.scopeOf(name, LayoutType.MAIN))
        assertEquals("$builtIn\n", LayoutUtilsCustom.getLayoutFile(name, LayoutType.MAIN, ctx).readText())
        // Built-in: asks, naming the layout and the language; then the built-in layout again once saved
        // (20261013_BUILTIN-WORD, owner "App's original makes sense": fail before, the button read "Built-in" and the
        // question "the built-in QWERTY keys")
        compose.onNodeWithText("App's original").assertExists()
        compose.onNodeWithText(ctx.getString(R.string.layout_files_builtin_button)).performClick()
        // (owner 2026-10-14: "Keep App's original": the dialog's title too, not "Built-in")
        compose.onNode(hasText("App's original") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Replace this tab with the original ", substring = true)
            and hasText(" that came with the app? Your changes in it will be lost.", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        val language = Locale.US.let { with(helium314.keyboard.latin.common.LocaleUtils) { it.localizedDisplayName(ctx.resources) } }
        compose.onNode(hasText(ctx.getString(R.string.layout_files_builtin_confirm_letters,
            helium314.keyboard.latin.utils.SubtypeLocaleUtils.getLayoutDisplayNameInSystemLocale(default, Locale.US), language))
            and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText(ctx.getString(android.R.string.ok)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(builtIn, fieldText())
        saveWithTick()
        assertEquals(default, kbNow().mainLayoutName())
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
        // edited back to the built-in text: the built-in layout again, stored as the Default preset stores it (none),
        // so that preset still matches (JSON-S2; was "symbols", which no preset names)
        field().performTextReplacement(builtIn); compose.waitForIdle()
        saveWithTick()
        assertEquals(null, kbNow().layoutName(LayoutType.SYMBOLS))
    }

    private fun presetOf(kb: SettingsSubtype) =
        helium314.keyboard.settings.screens.currentPopupPreset(helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(ctx), kb,
            helium314.keyboard.settings.screens.popupPresets(kb, emptyList()), emptyMap())

    // fail before (JSON-S2): Built-in gave "symbols" stored by name, and the Default preset no longer matched
    @Test fun `default keyboard - an edit and Built-in leave no symbols entry, and its preset still matches`() {
        val before = presetOf(kbNow())
        show()
        compose.onNodeWithText(ctx.getString(LayoutType.SYMBOLS.displayNameId)).performScrollTo().performClick()
        compose.waitForIdle()
        val builtIn = fieldText()
        field().performTextReplacement("$builtIn\n"); compose.waitForIdle()
        saveWithTick()
        compose.onNodeWithText(ctx.getString(R.string.layout_files_builtin_button)).performClick()
        compose.onNode(hasText(ctx.getString(R.string.layout_files_builtin_confirm)) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText(ctx.getString(android.R.string.ok)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        saveWithTick()
        assertEquals(null, kbNow().layoutName(LayoutType.SYMBOLS))
        assertEquals(before, presetOf(kbNow()))
    }

    // fail before (JSON-S2): Built-in on an Arabic-preset keyboard gave the default symbols page
    @Test fun `Arabic-preset keyboard - Built-in gives Arabic's symbols page and names the preset`() {
        val arabic = SubtypeSettings.getResourceSubtypesForLocale(Locale.forLanguageTag("ar")).first().toSettingsSubtype()
            .withLayout(LayoutType.SYMBOLS, "symbols_arabic").withId(3)
        storeKeyboards(ctx, real, listOf(kb1, arabic))
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { LayoutFilesScreen(arabic, {}) } }
        compose.waitForIdle()
        compose.onNodeWithText(ctx.getString(LayoutType.SYMBOLS.displayNameId)).performScrollTo().performClick()
        compose.waitForIdle()
        val arabicPage = LayoutUtils.getContent(LayoutType.SYMBOLS, "symbols_arabic", ctx)
        assertEquals(arabicPage, fieldText())
        field().performTextReplacement("$arabicPage\n"); compose.waitForIdle()
        compose.onNodeWithText(ctx.getString(R.string.layout_files_builtin_button)).performClick()
        compose.onNode(hasText(ctx.getString(R.string.layout_files_builtin_confirm_symbols, ctx.getString(R.string.key_popups_preset_arabic)))
            and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText(ctx.getString(android.R.string.ok)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(arabicPage, fieldText())
    }
}

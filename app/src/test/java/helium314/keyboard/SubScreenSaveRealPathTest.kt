// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.LocalSettingsMenu
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.screens.AppearanceScreen
import helium314.keyboard.settings.screens.ColorsScreen
import helium314.keyboard.settings.screens.CustomizePopupsScreen
import helium314.keyboard.settings.screens.LayoutFilesScreen
import helium314.keyboard.settings.screens.SubtypeScreen
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.settings.Defaults
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.SettingsDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review S8: the sub-screen saves (the palette editor in Appearance, Customize popups and the layout files screen in
 * Layout & Typing) and the parent's Discard, driven through the screens themselves: the edit, Back, the screen's own
 * "Save and exit" (and the update question it asks), then the parent's cross and its Discard question, which names
 * the sub-screen. SubScreenSaveTest calls the save helpers directly.
 */
@RunWith(RobolectricTestRunner::class)
// (tall: every row on screen, a tap lands where it is; the input method shadow of the other activity tests, so that
// background work of the activity left over from here doesn't meet another shadow there)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowInputMethodManager2::class])
class SubScreenSaveRealPathTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private fun own(n: Int, key: String) = idKey(n, key) // (keyboard "k<n>"'s own set)
    // the one edited (in use): the built-in keyboard as the keyboard list gives it back, with its extra values (a bare
    // "en-US" came back with them, another identity, and the palette question listed the edited keyboard itself); with
    // its id (owner 2026-10-11: keyboards have unique ids)
    private val kb1 = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first().toSettingsSubtype().withId(1)
    private val kb2 = SettingsSubtype(Locale.GERMANY, "").withId(2) // switched off (in the added list only), with its own set
    private val before = """[{"name":"background","auto":false,"color":-1}]"""
    private var scenario: ActivityScenario<SettingsActivity>? = null

    /** Which of the two the host shows: the parent screen, or the sub-screen opened from it. */
    private var screen by mutableStateOf(PARENT)
    /** True for a moment: the sub-screen leaves the composition, to come back from its saved state. */
    /** Which sub-screen (for a test that opens two). */
    private var subKey by mutableStateOf(0)

    // the activity loads the keyboard's settings into the Settings singleton, whose listener then reloads them on every
    // change in later tests: put back as found (see BackOncePerScreenTest)
    private val loadedSettings = Settings::class.java.getDeclaredField("mSettingsValues").apply { isAccessible = true }
    private var settingsBefore: Any? = null

    @Before fun setUp() {
        settingsBefore = loadedSettings.get(Settings.getInstance())
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString(Settings.PREF_SELECTED_SUBTYPE, kb1.toPref())
            .putString(own(2, AppearanceLooks.PREF_SELECTED), "Sunset")
            .putString(own(2, Settings.PREF_THEME_COLORS), "Warm")
            // (keyboard 1's own, the first keyboard's: owner 2026-10-11, no separate common set; the first keyboard's own
            // settings are the common ones)
            .putString(Settings.PREF_ENABLED_SUBTYPES, kb1.toPref())
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(kb1, kb2)))
            // (owner 2026-10-11: palettes are one app-wide list; keyboards and themes name it, no copies)
            .putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", before)
            .putString(own(1, Settings.PREF_THEME_COLORS), "Warm")
            .commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Warm"))), now = true)
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx) // (keyboard 2 is switched off: not in the list loaded)
        screen = PARENT; subKey = 0
    }
    @After fun tearDown() {
        scenario?.close()
        loadedSettings.set(Settings.getInstance(), settingsBefore)
        // the activity's background work done before the next test (as EditingKeyboardTest)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).submit {}.get()
        shadowOf(Looper.getMainLooper()).idle()
        SettingsDestination.navTarget.value = SettingsDestination.Keyboards
        AppearanceDraft.close(); LayoutDraft.close()
        layoutsDir.deleteRecursively(); SubtypeUtilsAdditional.changedTo.clear()
        SettingsMode.set(ctx, Defaults.PREF_ADVANCED_SETTINGS)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
        // the reloads refresh the input method's caches in the background: done before the next test
        val imeScope = helium314.keyboard.latin.RichInputMethodManager::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(helium314.keyboard.latin.RichInputMethodManager.getInstance()) as CoroutineScope
        // (a reload can start more of it from a task still queued on the main thread: until none is left)
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { imeScope.coroutineContext[Job]?.children?.forEach { it.join() } }
        }
    }

    /** The settings activity showing [parent], or [sub] (its back goes to the parent), each with its saved state kept
     *  as the navigation keeps it. */
    private fun host(parent: @Composable () -> Unit, sub: @Composable (back: () -> Unit) -> Unit) {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent {
            val saved = rememberSaveableStateHolder()
            if (screen == PARENT) saved.SaveableStateProvider(PARENT) { parent() }
            else saved.SaveableStateProvider(SUB + subKey) { sub { screen = PARENT } }
        } }
        idle()
    }
    private fun appearance() = host(
        parent = { CompositionLocalProvider(LocalSettingsMenu provides KeyboardProfiles.Group.APPEARANCE) { AppearanceScreen(onClickBack = { }) } },
        sub = { back -> ColorsScreen(isNight = false, theme = "Warm", onClickBack = back) },
    )

    private fun idle() = compose.waitForIdle()
    private fun click(text: String) { compose.onNodeWithText(text).performClick(); idle() }
    private fun clickIcon(description: String) { compose.onNodeWithContentDescription(description).performClick(); idle() }
    private fun shows(text: String) { compose.onNodeWithText(text).assertExists() }
    private fun systemBack() { scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; idle() }
    private fun arrowBack() = clickIcon(ctx.getString(R.string.spoken_description_action_previous))

    private val unsavedTitle get() = ctx.getString(R.string.unsaved_changes_title)

    /** Appearance open: saved theme "Sunset" deleted through its Themes list (the trash, then Delete), which also takes
     *  it off keyboard 2. */
    private fun deleteSunsetOnAppearance() {
        compose.onNodeWithText(ctx.getString(R.string.appearance_looks), substring = true).performClick(); idle()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText("Sunset"))
        clickIcon(ctx.getString(R.string.delete)) // (the only user theme: the only trash)
        click(ctx.getString(R.string.delete)) // the question's button
        click(ctx.getString(android.R.string.cancel)) // the list closed
        assertEquals(emptyList(), AppearanceLooks.load(real))
        assertFalse(real.getString(own(2, AppearanceLooks.PREF_SELECTED), null) == "Sunset")
    }

    /** In the palette editor: the keyboard background's "Choose color automatically" switched on (a change of Warm). */
    private fun editWarm() {
        compose.onNode(isToggleable() and hasParent(hasText(ctx.getString(R.string.select_color_background)))).performClick(); idle()
        assertTrue(ctx.prefs().getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null) != before)
    }

    /** Back on Appearance: its cross, the Discard question naming the palette, Discard; then all is as before. */
    private fun discardOnAppearanceAndCheck() {
        assertEquals(PARENT, screen)
        assertTrue(AppearanceDraft.activeOrNull()!!.hasChanges(ctx))
        clickIcon(ctx.getString(R.string.appearance_reject))
        shows(ctx.getString(R.string.appearance_reject_title))
        shows(ctx.getString(R.string.discard_also_undoes, ctx.getString(R.string.discard_also_undoes_palette, "Warm")))
        click(ctx.getString(R.string.appearance_discard_all))
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name }) // the theme is back, as it was
        assertEquals(mapOf(Settings.PREF_THEME_COLORS to "Warm"), AppearanceLooks.load(real).single().values)
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null)) // on keyboard 2 too
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null)) // its colors choice as it was
        assertFalse(real.contains(own(2, KeyboardProfiles.TOMBSTONE + Settings.PREF_THEME_COLORS)))
        assertEquals(before, ctx.prefs().getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null)) // the edit undone
        // no palette kept by the edit question ("Warm 2") is left, nothing in a set
        assertEquals(listOf(Settings.PREF_USER_COLORS_PREFIX + "Warm"),
            real.all.keys.filter { it.contains(Settings.PREF_USER_COLORS_PREFIX) }.sorted())
        assertEquals(emptyList(), AppearanceDraft.savedPalettesOnDisk(ctx))
    }

    // B1, the way the phone does it: the edit, then Back. ColorsScreen passes ::leave to SearchScreen, whose back
    // lambdas keep the first composition's copy (a local function's references compare equal whatever they captured):
    // that copy must check for changes when Back is pressed, not take what it captured then (nothing changed yet)
    @Test fun `palette editor - Back after a color change asks Save or Discard`() {
        appearance()
        screen = SUB; idle()
        editWarm()
        arrowBack()
        shows(unsavedTitle)
    }

    @Test fun `palette editor - system Back after a color change asks Save or Discard`() {
        appearance()
        screen = SUB; idle()
        editWarm()
        systemBack()
        shows(unsavedTitle)
    }

    // B1 through the editor's own Save and exit, then Appearance's Discard
    @Test fun `B1 - theme deleted on Appearance, palette edited and saved in the editor, Appearance's Discard undoes all`() {
        appearance()
        deleteSunsetOnAppearance()
        screen = SUB; idle() // the palette editor on Warm
        editWarm()
        arrowBack()
        shows(unsavedTitle)
        click(ctx.getString(R.string.save_and_exit))
        // (owner 2026-10-11: palettes are one app-wide list; switched-off keyboard 2 uses Warm too, so it's asked: was
        // nothing to ask, the edit went to keyboard 1's own copy)
        shows(ctx.getString(R.string.palette_edit_title, "Warm"))
        click(ctx.getString(R.string.save)) // (keyboard 2 on the new colors: the starting choice)
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals(PARENT, screen)
        assertEquals(listOf("Warm"), AppearanceDraft.activeOrNull()!!.savedPalettes()) // the editor's save was recorded
        discardOnAppearanceAndCheck()
    }

    // the editor's save asks what each other user takes (owner 2026-10-11: "use the new edited; use app default; use
    // 'Warm N'"): switched-off keyboard 2 and saved theme Sunset, the three options each; Appearance's Discard undoes all
    private fun option(text: Int, user: Int, vararg args: Any) {
        val row = compose.onAllNodes(hasText(ctx.getString(text, *args)) and hasAnyAncestor(isDialog()))[user]
        row.performClick(); idle()
        row.assertIsSelected()
    }

    // (owner 2026-10-11: "on second thought, yes let's do new colors as the default option"; and no "Warm 2" when
    // nobody keeps the old colors)
    @Test fun `edit question - all on the new colors, the starting choice - no Warm 2, Appearance's Discard puts it back`() {
        appearance()
        screen = SUB; idle()
        editWarm()
        systemBack()
        click(ctx.getString(R.string.save_and_exit))
        shows(ctx.getString(R.string.palette_edit_title, "Warm"))
        shows(ctx.getString(R.string.keyboard_switched_off, helium314.keyboard.settings.screens.keyboardName(kb2, ctx)))
        shows(ctx.getString(R.string.update_item_theme, "Sunset"))
        click(ctx.getString(R.string.save)) // (nothing picked: each on the new colors)
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals("Warm", AppearanceLooks.load(real).single().values[Settings.PREF_THEME_COLORS])
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Warm 2")) // (nobody keeps the old colors)
        assertTrue(ctx.prefs().getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null) != before)
        discardOnAppearanceAndCheck()
    }

    @Test fun `edit question - keyboard 2 to the default colors, the saved theme keeps the old ones as Warm 2`() {
        appearance()
        screen = SUB; idle()
        editWarm()
        arrowBack()
        click(ctx.getString(R.string.save_and_exit))
        option(R.string.palette_take_default, 0)
        option(R.string.palette_take_old, 1, "Warm 2")
        click(ctx.getString(R.string.save))
        assertEquals(null, real.getString(own(2, Settings.PREF_THEME_COLORS), null)) // the default colors
        assertFalse(real.contains(own(2, KeyboardProfiles.TOMBSTONE + Settings.PREF_THEME_COLORS))) // (owner 2026-10-11: no default marks; nothing stored = the default)
        assertEquals("Warm 2", AppearanceLooks.load(real).single().values[Settings.PREF_THEME_COLORS])
        assertEquals(before, real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm 2", null)) // the old colors, on the list
        assertEquals("Warm", real.getString(own(1, Settings.PREF_THEME_COLORS), null)) // the edited one: the new
        discardOnAppearanceAndCheck()
    }

    @Test fun `edit question - both keep the old colors, one Warm 2 for both`() {
        appearance()
        screen = SUB; idle()
        editWarm()
        arrowBack()
        click(ctx.getString(R.string.save_and_exit))
        option(R.string.palette_take_old, 0, "Warm 2"); option(R.string.palette_take_old, 1, "Warm 2")
        click(ctx.getString(R.string.save))
        assertEquals("Warm 2", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals("Warm 2", AppearanceLooks.load(real).single().values[Settings.PREF_THEME_COLORS])
        assertEquals(listOf("Warm", "Warm 2").map { Settings.PREF_USER_COLORS_PREFIX + it },
            real.all.keys.filter { it.contains(Settings.PREF_USER_COLORS_PREFIX) }.sorted())
        discardOnAppearanceAndCheck()
    }

    private fun layoutSetup() {
        real.edit().putString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").commit()
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("á")))))
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(KeyPopupOverrides.PREF_SELECTED_SET to "Mine",
            KeyPopupOverrides.PREF to """{"a":["á"]}"""))))
        SettingsMode.set(ctx, true)
    }
    /** The saved Layouts as stored when Layout & Typing opened. */
    private var layoutsAtOpen: String? = null
    private fun layout(sub: @Composable (back: () -> Unit) -> Unit) {
        host(
            parent = { CompositionLocalProvider(LocalSettingsMenu provides KeyboardProfiles.Group.LAYOUT) { SubtypeScreen(initialSubtype = kb1, onClickBack = { }) } },
            sub = sub,
        )
        layoutsAtOpen = real.getString(LayoutPresets.PREF, null)
        keyboardsAtOpen = keyboardsNow()
        assertTrue(layoutsAtOpen!!.contains("Mine"))
    }
    /** Layout & Typing open: popup set "Mine" deleted through its presets list (the trash, then Delete). */
    private fun deleteMineOnLayout() {
        compose.onNodeWithText(ctx.getString(R.string.key_popups_presets), substring = true).performScrollTo().performClick(); idle()
        clickIcon(ctx.getString(R.string.delete)) // (the only set of the user's own: the only trash)
        shows(ctx.getString(R.string.key_popups_delete_set_title, "Mine"))
        click(ctx.getString(R.string.delete))
        assertEquals(emptyList(), KeyPopupOverrides.loadSets(real))
        assertFalse(real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null) == "Mine")
        assertFalse(LayoutPresets.load(real).single().values[KeyPopupOverrides.PREF_SELECTED_SET] == "Mine")
    }

    /** In Customize popups: key p unfolded, its popup "}" switched off. */
    private fun editKeyP() {
        compose.onNode(hasText("p") and hasText("}")).performScrollTo().performClick(); idle()
        compose.onNode(isToggleable()).performClick(); idle() // (the one popup of p unfolded)
        assertTrue(real.contains(own(1, KeyPopupOverrides.PREF)))
    }

    /** Back on Layout & Typing: its cross, the Discard question naming [subScreen], Discard; then "Mine" is back
     *  everywhere and nothing of keyboard 1's own popups is left. */
    private fun discardOnLayoutAndCheck(subScreen: Int) {
        assertEquals(PARENT, screen)
        clickIcon(ctx.getString(R.string.appearance_reject))
        shows(ctx.getString(R.string.appearance_reject_title))
        shows(ctx.getString(R.string.discard_also_undoes, ctx.getString(subScreen)))
        click(ctx.getString(R.string.appearance_discard_all))
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name }) // the set is back
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null)) // on keyboard 2 too
        // the saved Layout names it again, as the keyboards do: as it was when Layout & Typing opened
        assertEquals(layoutsAtOpen, real.getString(LayoutPresets.PREF, null))
        assertEquals("Mine", LayoutPresets.load(real).single().values[KeyPopupOverrides.PREF_SELECTED_SET])
        assertFalse(real.contains(own(1, KeyPopupOverrides.PREF))) // the key edit is undone
        assertEquals(emptyList(), LayoutDraft.savedSubScreensOnDisk(ctx))
    }

    // C2, the arrow in Customize popups' top bar (BackButton(::leave))
    @Test fun `Customize popups - the arrow after a key edit asks Save or Discard`() {
        layoutSetup()
        layout { back -> CustomizePopupsScreen(kb1, back) }
        screen = SUB; idle()
        editKeyP()
        arrowBack()
        shows(unsavedTitle)
    }

    // C2 through Customize popups' Back, Save and exit, then Layout & Typing's Discard
    @Test fun `C2 - popup set deleted on Layout and Typing, key edited and saved in Customize popups, its Discard undoes all`() {
        layoutSetup()
        layout { back -> CustomizePopupsScreen(kb1, back) }
        deleteMineOnLayout()
        screen = SUB; idle()
        editKeyP()
        systemBack()
        shows(unsavedTitle)
        click(ctx.getString(R.string.save_and_exit))
        assertEquals(listOf(LayoutDraft.SubScreen.POPUPS), LayoutDraft.savedSubScreensOnDisk(ctx)) // recorded
        discardOnLayoutAndCheck(R.string.key_popups_full)
    }

    // the same through the top bar's tick (Save changes, then Save), then Back
    @Test fun `C2 - saved with Customize popups' tick, Layout and Typing's Discard undoes all`() {
        layoutSetup()
        layout { back -> CustomizePopupsScreen(kb1, back) }
        deleteMineOnLayout()
        screen = SUB; idle()
        editKeyP()
        clickIcon(ctx.getString(R.string.appearance_accept))
        click(ctx.getString(R.string.appearance_accept_all))
        arrowBack() // nothing unsaved now: straight back
        discardOnLayoutAndCheck(R.string.key_popups_full)
    }

    private val layoutsDir get() = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts")
    private fun layoutFiles() = layoutsDir.walk().filter { it.isFile }.toList()
    /** What defines the keyboards: their definitions (with their ids, so their sets), which are on and in use. */
    private fun keyboardsNow() = listOf(Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_ENABLED_SUBTYPES,
        Settings.PREF_SELECTED_SUBTYPE).associateWith { real.all[it] }
    private var keyboardsAtOpen: Map<String, Any?> = emptyMap()

    /** In the layout files screen: the number row's text changed (still valid, not the built-in one any more). */
    private fun editNumberRow() {
        val field = compose.onNode(isEditable())
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        field.performTextReplacement("$text\n"); idle()
    }

    /** Back on Layout & Typing after the layout files screen saved: Discard puts the keyboard and its files back too. */
    private fun discardAfterLayoutFiles() {
        discardOnLayoutAndCheck(R.string.layout_files)
        assertEquals(keyboardsAtOpen, keyboardsNow()) // the keyboard's definition as before
        assertEquals(emptyList(), layoutFiles()) // the layout written there is gone
        // (owner 2026-10-11: keyboards have unique ids) the keyboards' sets never moved
        assertEquals(setOf(kid(1), kid(2)), KeyboardProfiles.setIds(real.all))
    }

    // the layout files screen through Back, Save and exit, then Layout & Typing's Discard
    @Test fun `layout files - popup set deleted, number row saved there, Layout and Typing's Discard undoes all`() {
        layoutSetup()
        layout { back -> LayoutFilesScreen(kb1, back) }
        deleteMineOnLayout()
        screen = SUB; idle()
        editNumberRow()
        systemBack()
        shows(unsavedTitle)
        click(ctx.getString(R.string.save_and_exit))
        assertEquals(PARENT, screen)
        assertTrue(layoutFiles().isNotEmpty()) // the keyboard's own copy of the number row
        assertTrue(keyboardsNow() != keyboardsAtOpen) // and the keyboard names it
        assertEquals(listOf(LayoutDraft.SubScreen.LAYOUT_FILES), LayoutDraft.savedSubScreensOnDisk(ctx))
        discardAfterLayoutFiles()
    }

    // the same through the screen's tick, then its arrow
    @Test fun `layout files - saved with the tick, Layout and Typing's Discard undoes all`() {
        layoutSetup()
        layout { back -> LayoutFilesScreen(kb1, back) }
        deleteMineOnLayout()
        screen = SUB; idle()
        editNumberRow()
        clickIcon(ctx.getString(R.string.appearance_accept))
        click(ctx.getString(R.string.appearance_accept_all))
        assertTrue(layoutFiles().isNotEmpty())
        arrowBack() // nothing unsaved now: straight back
        discardAfterLayoutFiles()
    }

    // both sub-screens saved: the Discard question names both, in that order, and undoes both
    @Test fun `Customize popups and layout files both saved, Layout and Typing's Discard names and undoes both`() {
        layoutSetup()
        layout { back -> if (subKey == 0) CustomizePopupsScreen(kb1, back) else LayoutFilesScreen(kb1, back) }
        deleteMineOnLayout()
        screen = SUB; idle()
        editKeyP()
        systemBack()
        click(ctx.getString(R.string.save_and_exit))
        subKey = 1; screen = SUB; idle() // the layout files screen, opened from Layout & Typing too
        editNumberRow()
        systemBack()
        click(ctx.getString(R.string.save_and_exit))
        clickIcon(ctx.getString(R.string.appearance_reject))
        shows(ctx.getString(R.string.discard_also_undoes,
            ctx.getString(R.string.key_popups_full) + " · " + ctx.getString(R.string.layout_files)))
        click(ctx.getString(R.string.appearance_discard_all))
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertFalse(real.contains(own(1, KeyPopupOverrides.PREF)))
        assertEquals(keyboardsAtOpen, keyboardsNow())
        assertEquals(emptyList(), layoutFiles())
    }

    // the parents' other Discard: Back, then "Discard and exit" in the leave question, which names the sub-screen too
    @Test fun `Layout and Typing's Back - Discard and exit names Customize popups and undoes its save`() {
        layoutSetup()
        layout { back -> CustomizePopupsScreen(kb1, back) }
        deleteMineOnLayout()
        screen = SUB; idle()
        editKeyP()
        systemBack()
        click(ctx.getString(R.string.save_and_exit))
        systemBack() // on Layout & Typing
        shows(unsavedTitle)
        shows(ctx.getString(R.string.discard_and_exit_also_undoes, ctx.getString(R.string.key_popups_full)))
        click(ctx.getString(R.string.discard_and_exit))
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(layoutsAtOpen, real.getString(LayoutPresets.PREF, null))
        assertFalse(real.contains(own(1, KeyPopupOverrides.PREF)))
    }

    @Test fun `Appearance's Back - Discard and exit names the palette and undoes the editor's save`() {
        appearance()
        deleteSunsetOnAppearance()
        screen = SUB; idle()
        editWarm()
        systemBack()
        click(ctx.getString(R.string.save_and_exit))
        click(ctx.getString(R.string.save)) // (switched-off keyboard 2 uses Warm too: asked, owner 2026-10-11)
        systemBack() // on Appearance
        shows(unsavedTitle)
        shows(ctx.getString(R.string.discard_and_exit_also_undoes, ctx.getString(R.string.discard_also_undoes_palette, "Warm")))
        click(ctx.getString(R.string.discard_and_exit))
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name })
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
        assertEquals(before, ctx.prefs().getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null))
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Warm 2"))
    }

    // review S2 (owner 2026-10-11, S2: "yes let's keep saved subscreen changes"): leaving the app (the activity stopped,
    // SettingsActivity.onStop) puts back only the parent's own changes; what a sub-screen saved stays as saved
    private fun leaveApp() { scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); idle() }

    @Test fun `S2 - Customize popups saved, then the app left - the popup edit stays, the parent's set delete is undone`() {
        layoutSetup()
        layout { back -> CustomizePopupsScreen(kb1, back) }
        deleteMineOnLayout() // the parent's own change
        screen = SUB; idle()
        editKeyP()
        val edit = real.getString(own(1, KeyPopupOverrides.PREF), null)
        systemBack()
        click(ctx.getString(R.string.save_and_exit))
        leaveApp()
        assertEquals(edit, real.getString(own(1, KeyPopupOverrides.PREF), null)) // the saved popup edit stays
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name }) // the parent's delete is undone
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(layoutsAtOpen, real.getString(LayoutPresets.PREF, null))
        assertEquals(emptyList(), LayoutDraft.savedSubScreensOnDisk(ctx)) // the draft is gone, its records with it
    }

    @Test fun `S2 - palette editor saved, then the app left - the palette change stays, the parent's theme delete is undone`() {
        appearance()
        deleteSunsetOnAppearance() // the parent's own change
        screen = SUB; idle()
        editWarm()
        val edit = ctx.prefs().getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null)
        arrowBack()
        click(ctx.getString(R.string.save_and_exit))
        click(ctx.getString(R.string.save)) // (switched-off keyboard 2 uses Warm too: asked, owner 2026-10-11)
        assertEquals(PARENT, screen)
        leaveApp()
        assertEquals(edit, ctx.prefs().getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null)) // the saved palette stays
        assertTrue(edit != before)
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name }) // the parent's delete is undone
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
        // (the question's answer, the starting choice, saved with the editor: keyboard 2 on the new colors)
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Warm 2"))
        assertEquals(emptyList(), AppearanceDraft.savedPalettesOnDisk(ctx))
        assertEquals(null, AppearanceDraft.activeOrNull())
    }

    @Test fun `S2 - layout files saved, then the app left - the file and the keyboard's definition stay, the parent's set delete is undone`() {
        layoutSetup()
        layout { back -> LayoutFilesScreen(kb1, back) }
        deleteMineOnLayout() // the parent's own change
        screen = SUB; idle()
        editNumberRow()
        systemBack()
        click(ctx.getString(R.string.save_and_exit))
        val files = layoutFiles().associate { it.path to it.readText() }
        val keyboards = keyboardsNow()
        assertTrue(files.isNotEmpty()); assertTrue(keyboards != keyboardsAtOpen)
        leaveApp()
        assertEquals(files, layoutFiles().associate { it.path to it.readText() }) // the layout written there stays
        assertEquals(keyboards, keyboardsNow()) // and the keyboard naming it (definition with its id, in use)
        assertEquals(kid(1), storedEnabled(real).single().keyboardId) // (the same keyboard: no set moved)
        assertEquals(setOf(kid(1), kid(2)), KeyboardProfiles.setIds(real.all))
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name }) // the parent's delete is undone
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(emptyList(), LayoutDraft.savedSubScreensOnDisk(ctx))
    }

    // a sub-screen opened and edited but not saved: leaving the app undoes its edit with the parent's
    @Test fun `S2 - Customize popups edited but not saved, then the app left - all is undone`() {
        layoutSetup()
        layout { back -> CustomizePopupsScreen(kb1, back) }
        deleteMineOnLayout()
        screen = SUB; idle()
        editKeyP()
        leaveApp()
        assertFalse(real.contains(own(1, KeyPopupOverrides.PREF)))
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name })
    }

    companion object {

        private const val PARENT = "parent"
        private const val SUB = "sub"
    }
}

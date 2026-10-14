// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.screens.customizePopupsSaved
import helium314.keyboard.settings.screens.deletePopupSet
import helium314.keyboard.settings.screens.layoutFilesSaved
import helium314.keyboard.settings.screens.paletteEditorSaved
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11 (option 1): a sub-screen's Save only keeps its change and goes back to the screen it was opened from;
 * that screen's Save or Discard decides everything, the sub-screen's changes included ("every action is held until the
 * screen's Save; Discard undoes it everywhere it reached"). Before, the palette editor's "Save and exit" took ALL of
 * what Appearance reaches as Appearance's new starting point (review B1: a theme deleted on Appearance stayed deleted
 * after its Discard), and Customize popups' Save did the same with the popup sets on Layout & Typing (review C2: a
 * deleted popup set stayed deleted, while the saved Layouts got its name back).
 */
@RunWith(RobolectricTestRunner::class)
class SubScreenSaveTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    // a palette as the editor stores it (the keyboard reads it as it loads), before and after a colour change
    private val before = """[{"name":"background","auto":false,"color":-1}]"""
    private val edited = """[{"name":"background","auto":false,"color":-16777216}]"""

    /** Separate settings, keyboard 1 edited; keyboard 2 on theme "Sunset", palette "Warm" and popup set "Mine". */
    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).toString())
            .putString(own(2, AppearanceLooks.PREF_SELECTED), "Sunset")
            .putString(own(2, Settings.PREF_THEME_COLORS), "Warm")
            .putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", before)
            .putString(Settings.PREF_THEME_COLORS, "Warm")
            .putString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine")
            .commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Warm",
            Settings.PREF_USER_COLORS_PREFIX + "Warm" to before))), now = true)
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("á")))))
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(KeyPopupOverrides.PREF_SELECTED_SET to "Mine",
            KeyPopupOverrides.PREF to """{"a":["á"]}"""))))
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() {
        AppearanceDraft.close(); LayoutDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    // B1
    @Test fun `a theme deleted on Appearance comes back on its Discard after the palette editor saved`() {
        AppearanceDraft.of(ctx) // Appearance opens
        // Sunset deleted, as AppearanceScreen's delete dialog does it
        AppearanceLooks.save(real, emptyList(), now = true)
        AppearanceLooks.choiceRenamed(ctx, "Sunset", null)
        // the palette editor: a colour of Warm changed, then "Save and exit"
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", edited).commit()
        paletteEditorSaved("Warm", "Warm")
        assertTrue(AppearanceDraft.activeOrNull()!!.hasChanges(ctx)) // Appearance shows unsaved changes
        AppearanceDraft.rejectOpen(ctx) // Appearance's Discard
        assertEquals("Sunset", AppearanceLooks.load(real).single().name)
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals(before, real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null)) // the colour too
    }

    @Test fun `after the palette editor saved, Appearance still has the change to save, and its Save keeps it`() {
        AppearanceDraft.of(ctx)
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", edited).commit()
        paletteEditorSaved("Warm", "Warm")
        assertTrue(AppearanceDraft.activeOrNull()!!.hasChanges(ctx))
        AppearanceDraft.activeOrNull()!!.accept(ctx) // Appearance's Save
        assertEquals(edited, real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null))
    }

    // C2
    @Test fun `a popup set deleted on Layout and Typing comes back on its Discard after Customize popups saved`() {
        val draft = LayoutDraft.of(ctx, "en_US:") // Layout & Typing opens
        deletePopupSet(ctx, "Mine")
        assertTrue(KeyPopupOverrides.loadSets(real).isEmpty())
        // Customize popups: a key edited on the edited keyboard, then Save
        KeyPopupOverrides.save(ProfilePreferences(real) { 1 }, mapOf("e" to listOf("é")))
        customizePopupsSaved()
        assertTrue(draft.hasChanges(ctx)) // Layout & Typing shows unsaved changes
        LayoutDraft.rejectOpen(ctx) // Layout & Typing's Discard
        assertEquals("Mine", KeyPopupOverrides.loadSets(real).single().name)
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        // the saved Layout names it again, as the keyboards do
        assertEquals("Mine", LayoutPresets.load(real).single().values[KeyPopupOverrides.PREF_SELECTED_SET])
        assertFalse(real.contains(own(1, KeyPopupOverrides.PREF))) // the key edit is undone too
    }

    // the same with the layout files screen (opened from Layout & Typing): its Save rebased every keyboard's definition
    // and every "layout_" key, the saved Layouts list among them
    @Test fun `a popup set deleted on Layout and Typing comes back on its Discard after the layout files screen saved`() {
        val layouts = java.io.File(DeviceProtectedUtils.getFilesDir(ctx), "layouts")
        java.io.File(layouts, "number").apply { mkdirs() }.resolve("old.txt").writeText("old") // a layout from before
        try {
            val draft = LayoutDraft.of(ctx, "en_US:") // Layout & Typing opens
            deletePopupSet(ctx, "Mine")
            // the layout files screen: a number layout written as the keyboard's own, then Save
            val written = java.io.File(layouts, "number").resolve("new.txt").apply { writeText("new") }
            layoutFilesSaved()
            assertTrue(draft.hasChanges(ctx)) // Layout & Typing shows unsaved changes
            LayoutDraft.rejectOpen(ctx) // Layout & Typing's Discard
            assertEquals("Mine", KeyPopupOverrides.loadSets(real).single().name)
            assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
            assertEquals("Mine", LayoutPresets.load(real).single().values[KeyPopupOverrides.PREF_SELECTED_SET])
            assertFalse(written.exists()) // the layout written there is undone too
            assertTrue(java.io.File(layouts, "number").resolve("old.txt").exists())
        } finally { layouts.deleteRecursively() }
    }
}

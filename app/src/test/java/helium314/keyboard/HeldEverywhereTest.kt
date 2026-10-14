// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.LayoutPresets
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
 * Owner 2026-10-11: "Every action on an editing screen ... is held until Save; Discard undoes it everywhere it reached".
 * A theme or palette renamed or deleted with Appearance open, or a saved Layout with Layout & Typing open, comes back on
 * Discard (or leaving the app, or a crash) for every keyboard and saved theme it reached; Keep keeps it.
 */
@RunWith(RobolectricTestRunner::class)
class HeldEverywhereTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)

    /** Separate settings, keyboard 1 edited; keyboard 2 also on theme "Sunset" and palette "Warm" (read from the shared set). */
    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).toString())
            .putString(own(2, AppearanceLooks.PREF_SELECTED), "Sunset")
            .putString(own(2, Settings.PREF_THEME_COLORS), "Warm")
            .putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", "colours")
            .putString(Settings.PREF_THEME_COLORS, "Warm")
            .commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Warm",
            Settings.PREF_USER_COLORS_PREFIX + "Warm" to "colours"))), now = true)
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))))
        real.edit().putString(own(2, LayoutPresets.PREF_SELECTED), "Work").commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() {
        AppearanceDraft.close(); LayoutDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    @Test fun `a theme renamed with Appearance open comes back on Discard for every keyboard`() {
        AppearanceDraft.of(ctx)
        val renamed = AppearanceLooks.load(real).map { AppearanceLooks.Look("Dusk", it.values, it.remembered) }
        AppearanceLooks.save(real, renamed, now = true)
        AppearanceLooks.choiceRenamed(ctx, "Sunset", "Dusk")
        assertEquals("Dusk", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
        assertTrue(AppearanceDraft.activeOrNull()!!.hasChanges(ctx))
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Sunset", AppearanceLooks.load(real).single().name)
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
    }

    @Test fun `a palette renamed follows into saved themes and other keyboards, and all of it comes back on Discard`() {
        AppearanceDraft.of(ctx)
        assertTrue(KeyboardTheme.renameUserColors("Warm", "Hot", real))
        val theme = AppearanceLooks.load(real).single()
        assertEquals("Hot", theme.values[Settings.PREF_THEME_COLORS])
        assertEquals("colours", theme.values[Settings.PREF_USER_COLORS_PREFIX + "Hot"])
        assertFalse(theme.values.containsKey(Settings.PREF_USER_COLORS_PREFIX + "Warm"))
        assertEquals("Hot", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Warm", AppearanceLooks.load(real).single().values[Settings.PREF_THEME_COLORS])
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals("colours", real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null))
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Hot"))
    }

    @Test fun `Keep keeps a theme delete, and a crash undoes one`() {
        val draft = AppearanceDraft.of(ctx)
        AppearanceLooks.save(real, emptyList(), now = true)
        AppearanceLooks.choiceRenamed(ctx, "Sunset", null)
        draft.accept(ctx)
        assertTrue(AppearanceLooks.load(real).isEmpty())

        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", emptyMap())), now = true)
        AppearanceDraft.of(ctx)
        AppearanceLooks.save(real, emptyList(), now = true)
        AppearanceDraft.forgetActiveForTest() // the process dies with the screen open
        AppearanceDraft.recoverAfterCrash(ctx)
        assertEquals("Sunset", AppearanceLooks.load(real).single().name)
    }

    // (already so before 2026-10-11: the saved Layouts list is in LayoutDraft by its "layout_" prefix; kept as a guard)
    @Test fun `a saved Layout renamed with Layout and Typing open comes back on Discard, with every keyboard's choice`() {
        LayoutDraft.of(ctx, "en_US:")
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Office", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))))
        KeyboardProfiles.replaceValueEverywhere(real, LayoutPresets.PREF_SELECTED, "Work", "Office")
        assertEquals("Office", real.getString(own(2, LayoutPresets.PREF_SELECTED), null))
        LayoutDraft.rejectOpen(ctx)
        assertEquals("Work", LayoutPresets.load(real).single().name)
        assertEquals("Work", real.getString(own(2, LayoutPresets.PREF_SELECTED), null))
    }

    @Test fun `the edited keyboard's own changes still come back as before`() {
        AppearanceDraft.of(ctx)
        ProfilePreferences(real) { 1 }.edit().putFloat(Settings.PREF_KEY_HORIZONTAL_GAP, 3f).commit()
        AppearanceDraft.rejectOpen(ctx)
        assertFalse(real.contains(own(1, Settings.PREF_KEY_HORIZONTAL_GAP)))
    }
}

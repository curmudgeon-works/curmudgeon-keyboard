// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.deletePopupSet
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
 * Review D2 (owner: "Yes"): Discard also drops the "Custom" labels added while the screen was open (a saved theme,
 * Layout or popup set deleted gives its keyboards one), so a Discard leaves no stray "Custom". Labels there before
 * the screen opened stay; Save keeps the new ones; a label alone is no unsaved change.
 * (owner 2026-10-11: labels are just "Custom", no number; only kept palettes are numbered)
 */
@RunWith(RobolectricTestRunner::class)
class CustomLabelsDiscardTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private val theme = CustomLabels.Kind.THEME.key
    private val layout = CustomLabels.Kind.LAYOUT.key
    private val popups = CustomLabels.Kind.POPUPS.key

    /** Separate settings; keyboard 2 on theme "Sunset", Layout "Work", popup set "Mine"; keyboard 1 already has a Custom
     *  label of each kind. */
    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).toString())
            .putString(own(2, AppearanceLooks.PREF_SELECTED), "Sunset")
            .putString(own(2, LayoutPresets.PREF_SELECTED), "Work")
            .putString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine")
            .putInt(own(1, theme), 1).putInt(own(1, layout), 1).putInt(own(1, popups), 1)
            .commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Warm"))), now = true)
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))))
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() {
        AppearanceDraft.close(); LayoutDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun deleteSunset() { // as AppearanceScreen does
        AppearanceLooks.save(real, AppearanceLooks.load(real).filter { it.name != "Sunset" }, now = true)
        AppearanceLooks.choiceRenamed(ctx, "Sunset", null)
    }

    @Test fun `Discard on Appearance drops the Custom label a theme delete gave`() {
        AppearanceDraft.of(ctx)
        deleteSunset()
        assertEquals(1, real.getInt(own(2, theme), 0))
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
        assertFalse(real.contains(own(2, theme)))
        assertEquals(1, real.getInt(own(1, theme), 0))
    }

    @Test fun `a Custom label alone is no unsaved change on Appearance, and Discard takes it back`() {
        val draft = AppearanceDraft.of(ctx)
        CustomLabels.mark(ProfilePreferences(real) { 2 }, CustomLabels.Kind.THEME)
        CustomLabels.mark(real, CustomLabels.Kind.THEME) // (the shared set too)
        assertFalse(draft.hasChanges(ctx))
        AppearanceDraft.rejectOpen(ctx)
        assertFalse(real.contains(own(2, theme)))
        assertFalse(real.contains(theme))
        assertEquals(1, real.getInt(own(1, theme), 0))
    }

    @Test fun `Save on Appearance keeps the Custom label`() {
        val draft = AppearanceDraft.of(ctx)
        deleteSunset()
        draft.accept(ctx)
        assertEquals(1, real.getInt(own(2, theme), 0))
    }

    @Test fun `a crash with Appearance open drops the Custom label too`() {
        AppearanceDraft.of(ctx)
        deleteSunset()
        AppearanceDraft.forgetActiveForTest()
        AppearanceDraft.recoverAfterCrash(ctx)
        assertFalse(real.contains(own(2, theme)))
        assertEquals(1, real.getInt(own(1, theme), 0))
    }

    @Test fun `Discard on Layout and Typing drops the Custom labels of a Layout and a popup set delete`() {
        LayoutDraft.of(ctx, "en_US:")
        LayoutPresets.delete(real, "Work")
        deletePopupSet(ctx, "Mine")
        assertEquals(1, real.getInt(own(2, layout), 0))
        assertEquals(1, real.getInt(own(2, popups), 0))
        LayoutDraft.rejectOpen(ctx)
        assertEquals("Work", real.getString(own(2, LayoutPresets.PREF_SELECTED), null))
        assertFalse(real.contains(own(2, layout)))
        assertFalse(real.contains(own(2, popups)))
        assertEquals(1, real.getInt(own(1, layout), 0))
        assertEquals(1, real.getInt(own(1, popups), 0))
    }

    @Test fun `a Custom label alone is no unsaved change on Layout and Typing`() {
        val draft = LayoutDraft.of(ctx, "en_US:")
        CustomLabels.mark(ProfilePreferences(real) { 2 }, CustomLabels.Kind.LAYOUT)
        assertFalse(draft.hasChanges(ctx))
        LayoutDraft.rejectOpen(ctx)
        assertFalse(real.contains(own(2, layout)))
    }

    @Test fun `Save on Layout and Typing keeps the Custom label, a crash drops it`() {
        val draft = LayoutDraft.of(ctx, "en_US:")
        LayoutPresets.delete(real, "Work")
        draft.accept()
        assertEquals(1, real.getInt(own(2, layout), 0))

        LayoutDraft.of(ctx, "en_US:")
        deletePopupSet(ctx, "Mine")
        assertTrue(real.contains(own(2, popups)))
        LayoutDraft.forgetActiveForTest()
        LayoutDraft.recoverAfterCrash(ctx)
        assertFalse(real.contains(own(2, popups)))
        assertEquals(1, real.getInt(own(2, layout), 0)) // (kept by the Save before)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardScopeContext
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.deletePopupSet
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Review E3 (owner 2026-10-11): Discard puts the Custom labels AND their stored origins ("from Work") back exactly as
 * they were when the screen opened, explicit or by leaving the app. Before, the snapshot held only which label keys
 * existed: the ones added since went, but a label or origin cleared on the screen (a keyboard choosing an item) stayed
 * cleared, so a deleted item's "Custom · from Old" became the built-in's name.
 */
@RunWith(RobolectricTestRunner::class)
class CustomLabelsDiscardOriginTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(n: Int, key: String) = idKey(n, key) // (keyboard "k<n>"'s own set)
    private val theme = CustomLabels.Kind.THEME
    private val layout = CustomLabels.Kind.LAYOUT
    private val popups = CustomLabels.Kind.POPUPS

    private fun clearPictures() = DeviceProtectedUtils.getFilesDir(ctx).listFiles { f -> f.name.startsWith("custom_background_image") }
        ?.forEach { it.delete() }

    @Before fun setUp() {
        real.edit().clear().putBoolean("separate_settings_per_keyboard", true).commit()
        KeyboardProfiles.loadGroups(real)
        clearPictures()
    }
    @After fun tearDown() {
        AppearanceDraft.close(); LayoutDraft.close(); clearPictures()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
    }

    // ---- themes ----

    /** Keyboards 1 to 3 on theme Work (2 changed since: labelled, "from Work" as it exists), keyboard 4 the copy of a
     *  theme Old deleted before ("Custom · from Old"); Work deleted with Appearance open, keyboard 4 chooses Home. */
    private fun themeScenario(): Map<String, Any?> {
        storeKeyboards(ctx, real, (1..4).map { SettingsSubtype(Locale.US, "").withId(it) })
        // the keyboard in use worked out for these keyboards (not left from the test before: the order tests run in)
        KeyboardProfiles.refreshImeId(real)
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Work", mapOf(Settings.PREF_THEME_COLORS to "Warm")),
            AppearanceLooks.Look("Home", mapOf(Settings.PREF_THEME_COLORS to "Cool"))), now = true)
        real.edit().apply {
            for (n in 1..3) putString(own(n, AppearanceLooks.PREF_SELECTED), "Work")
            putString(own(1, Settings.PREF_THEME_COLORS), "Warm"); putString(own(3, Settings.PREF_THEME_COLORS), "Warm")
            putString(own(2, Settings.PREF_THEME_COLORS), "Cool"); putInt(own(2, theme.key), CustomLabels.SET)
            putInt(own(4, theme.key), CustomLabels.SET); putString(own(4, theme.fromKey), "Old")
        }.commit()
        val before = CustomLabels.storedValues(real, listOf(theme))
        assertEquals(listOf("Work", "Custom · from Work", "Work", "Custom · from Old"), (1..4).map { themeText(it) })

        AppearanceDraft.of(ctx) // Appearance opens
        AppearanceLooks.save(real, AppearanceLooks.load(real).filter { it.name != "Work" }, now = true) // Work deleted
        AppearanceLooks.choiceRenamed(ctx, "Work", null)
        assertEquals(List(3) { "Work" }, (1..3).map { real.getString(own(it, theme.fromKey), null) })
        real.edit().putString(own(4, AppearanceLooks.PREF_SELECTED), "Home").commit() // keyboard 4 chooses Home (the list's OK)
        CustomLabels.clear(KeyboardScopeContext(ctx, kid(4)).prefs(), theme)
        assertNull(real.getString(own(4, theme.fromKey), null))
        return before
    }

    private fun themeText(n: Int): String? {
        val screen = KeyboardScopeContext(ctx, kid(n))
        return CustomLabels.themeRow(screen, screen.prefs(), AppearanceLooks.builtIn(ctx), AppearanceLooks.load(real)).text
    }

    private fun assertThemesAsBefore(before: Map<String, Any?>) {
        assertEquals(before, CustomLabels.storedValues(real, listOf(theme)))
        assertEquals(listOf("Work", "Work", "Work", null), (1..4).map { real.getString(own(it, AppearanceLooks.PREF_SELECTED), null) })
        assertEquals(listOf("Work", "Custom · from Work", "Work", "Custom · from Old"), (1..4).map { themeText(it) })
    }

    @Test fun `Appearance Discard puts every theme label and origin back as at the open`() {
        val before = themeScenario()
        AppearanceDraft.activeOrNull()!!.reject(ctx) // Appearance's Discard
        assertThemesAsBefore(before)
    }

    @Test fun `leaving the app with Appearance open puts every theme label and origin back as at the open`() {
        val before = themeScenario()
        AppearanceDraft.rejectOpen(ctx) // the app left
        assertThemesAsBefore(before)
    }

    @Test fun `a crash with Appearance open puts every theme label and origin back as at the open`() {
        val before = themeScenario()
        AppearanceDraft.forgetActiveForTest() // the process died
        AppearanceDraft.recoverAfterCrash(ctx) // the next app start
        assertThemesAsBefore(before)
    }

    // ---- Layouts and popup sets ----

    /** Keyboards 1 and 2 on Layout Work and popup set Mine (2 labelled before for both), keyboard 4 the copy of a Layout
     *  Old and a popup set OldSet deleted before; with Layout and Typing open Work and Mine are deleted, and keyboard 4
     *  chooses Layout Home and a built-in popup set. */
    private fun layoutScenario(): Map<String, Any?> {
        // (keyboards 1-4 on the phone: a write for no keyboard of the phone is dropped, 2026-10-12)
        storeKeyboards(ctx, real, (1..4).map { SettingsSubtype(Locale.US, "").withId(it) }, selected = null, reload = false)
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true)),
            LayoutPresets.Preset("Home", emptyMap())))
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        real.edit().apply {
            for (n in 1..2) { putString(own(n, LayoutPresets.PREF_SELECTED), "Work"); putString(own(n, KeyPopupOverrides.PREF_SELECTED_SET), "Mine") }
            putInt(own(2, layout.key), CustomLabels.SET); putInt(own(2, popups.key), CustomLabels.SET)
            putInt(own(4, layout.key), CustomLabels.SET); putString(own(4, layout.fromKey), "Old")
            putInt(own(4, popups.key), CustomLabels.SET); putString(own(4, popups.fromKey), "OldSet")
        }.commit()
        val before = CustomLabels.storedValues(real, listOf(layout, popups))

        LayoutDraft.of(ctx, "en_US:") // Layout & Typing opens
        LayoutPresets.delete(real, "Work")
        deletePopupSet(ctx, "Mine")
        assertEquals(listOf("Work", "Work"), (1..2).map { real.getString(own(it, layout.fromKey), null) })
        assertEquals(listOf("Mine", "Mine"), (1..2).map { real.getString(own(it, popups.fromKey), null) })
        // keyboard 4 chooses Home (the list's keep) and a built-in popup set (applyPreset): no copies
        real.edit().putString(own(4, LayoutPresets.PREF_SELECTED), "Home").commit()
        val four = helium314.keyboard.latin.settings.ProfilePreferences(real) { kid(4) }
        CustomLabels.clear(four, layout)
        CustomLabels.clear(four, popups)
        assertNull(real.getString(own(4, layout.fromKey), null)); assertNull(real.getString(own(4, popups.fromKey), null))
        return before
    }

    private fun assertLayoutsAsBefore(before: Map<String, Any?>) {
        assertEquals(before, CustomLabels.storedValues(real, listOf(layout, popups)))
        assertEquals(listOf("Work", "Work", null), listOf(1, 2, 4).map { real.getString(own(it, LayoutPresets.PREF_SELECTED), null) })
        assertEquals(listOf("Mine", "Mine"), (1..2).map { real.getString(own(it, KeyPopupOverrides.PREF_SELECTED_SET), null) })
        val four = helium314.keyboard.latin.settings.ProfilePreferences(real) { kid(4) }
        assertEquals("Custom · from Old", CustomLabels.text(ctx, four, layout, null))
        assertEquals("Custom · from OldSet", CustomLabels.text(ctx, four, popups, null))
        assertEquals(true, CustomLabels.deleted(four, layout, LayoutPresets.PREF_SELECTED))
        assertEquals(true, CustomLabels.deleted(four, popups, KeyPopupOverrides.PREF_SELECTED_SET))
    }

    @Test fun `Layout and Typing Discard puts every Layout and popups label and origin back as at the open`() {
        val before = layoutScenario()
        LayoutDraft.of(ctx, "en_US:").reject(ctx) // Layout & Typing's Discard (the running draft)
        assertLayoutsAsBefore(before)
    }

    @Test fun `leaving the app with Layout and Typing open puts every Layout and popups label and origin back as at the open`() {
        val before = layoutScenario()
        LayoutDraft.rejectOpen(ctx) // the app left
        assertLayoutsAsBefore(before)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
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
import helium314.keyboard.settings.screens.popupPresets
import helium314.keyboard.settings.screens.popupsRow
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review E2 (owner 2026-10-11): a keyboard that never chose a saved item (no choice stored: it reads the built-in
 * default) shows "Custom · from <the built-in>" while it differs, and the built-in's plain name once it matches again,
 * as a keyboard that chose a built-in or a saved item does. Before, its label alone was taken as "its item was deleted"
 * (no choice and a label), so its first change showed plain "Custom" for good. Deleted is a stored origin now: only a
 * delete stores one. Each case for themes, Layouts and popups, through the rows' own code (CustomLabels.themeRow,
 * layoutRow, popupsRow), on keyboard 1's screen.
 */
@RunWith(RobolectricTestRunner::class)
class CustomLabelNeverChosenTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val keyboard = SettingsSubtype(Locale.US, "").withId(1)
    private val screen = KeyboardScopeContext(ctx, kid(1)) // (the screens editing keyboard 1)
    private val prefs: SharedPreferences get() = screen.prefs()

    private fun clearPictures() = DeviceProtectedUtils.getFilesDir(ctx).listFiles { f -> f.name.startsWith("custom_background_image") }
        ?.forEach { it.delete() }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        real.edit { putBoolean("separate_settings_per_keyboard", true) }
        storeKeyboards(ctx, real, listOf(keyboard))
        clearPictures()
    }
    @After fun tearDown() {
        AppearanceDraft.close(); LayoutDraft.close(); clearPictures()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
    }

    // ---- themes ----

    private val builtInThemes get() = AppearanceLooks.builtIn(ctx)
    private fun themeText(): String? = CustomLabels.themeRow(screen, prefs, builtInThemes, AppearanceLooks.load(real)).text

    @Test fun `theme never chosen - Custom from Dynamic while it differs, Dynamic again once it matches`() {
        assertEquals("Dynamic", themeText())
        prefs.edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, false) }
        assertEquals("Custom · from Dynamic", themeText())
        prefs.edit { remove(Settings.PREF_THEME_KEY_BORDERS) }
        assertEquals("Dynamic", themeText()) // (was "Custom" for good)
        prefs.edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, false) }
        assertEquals("Custom · from Dynamic", themeText()) // (was "Custom")
    }

    // fail before (L5.1): older than Android 12 there is no Dynamic theme, and the row was blank; (L5.1b) Curmudgeon,
    // was Midnight
    @Config(sdk = [23]) // (older than Android 12; the SDK jar the other tests use too)
    @Test fun `Android 11 and older - theme never chosen shows Curmudgeon, the default look there, and Custom from Curmudgeon once it differs`() {
        val curmudgeon = ctx.getString(R.string.theme_preset_curmudgeon)
        assertEquals(curmudgeon, themeText())
        prefs.edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, false) }
        assertEquals("Custom · from $curmudgeon", themeText())
        prefs.edit { remove(Settings.PREF_THEME_KEY_BORDERS) }
        assertEquals(curmudgeon, themeText())
    }

    @Test fun `theme chosen saved Sunset - Custom from Sunset while it differs, Sunset once it matches`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Warm"))), now = true)
        prefs.edit { putString(AppearanceLooks.PREF_SELECTED, "Sunset"); putString(Settings.PREF_THEME_COLORS, "Warm") }
        assertEquals("Sunset", themeText())
        prefs.edit { putString(Settings.PREF_THEME_COLORS, "Cool") }
        assertEquals("Custom · from Sunset", themeText())
        prefs.edit { putString(Settings.PREF_THEME_COLORS, "Warm") }
        assertEquals("Sunset", themeText())
    }

    @Test fun `theme chosen built-in Midnight - Custom from Midnight while it differs, Midnight once it matches`() {
        val midnight = builtInThemes.first { it.name == ctx.getString(R.string.theme_preset_midnight) }
        AppearanceLooks.apply(screen, AppearanceLooks.current(prefs) + AppearanceLooks.applied(midnight, true), prefs)
        prefs.edit { putString(AppearanceLooks.PREF_SELECTED, midnight.name) }
        assertEquals("Midnight", themeText())
        prefs.edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, false) }
        assertEquals("Custom · from Midnight", themeText())
        prefs.edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, true) }
        assertEquals("Midnight", themeText())
    }

    @Test fun `theme deleted - Custom from Sunset even when it matches the default, until a theme is chosen`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_KEY_BORDERS to true))), now = true)
        prefs.edit { putString(AppearanceLooks.PREF_SELECTED, "Sunset") }
        AppearanceLooks.save(real, emptyList(), now = true) // (as AppearanceScreen's delete does)
        AppearanceLooks.choiceRenamed(ctx, "Sunset", null)
        assertEquals("Custom · from Sunset", themeText())
        prefs.edit { putString(AppearanceLooks.PREF_SELECTED, "Dynamic") } // (the list's OK)
        CustomLabels.clear(prefs, CustomLabels.Kind.THEME)
        assertEquals("Dynamic", themeText())
    }

    // ---- Layouts ----

    private val builtInLayouts get() = LayoutPresets.builtIn(ctx)
    private fun layoutText(): String {
        // (the chosen Layout as the Layouts row finds it: none chosen, the default Dense)
        val chosen = prefs.getString(LayoutPresets.PREF_SELECTED, null)
            ?.let { name -> (builtInLayouts + LayoutPresets.load(real)).firstOrNull { it.name == name } } ?: builtInLayouts.first()
        return CustomLabels.layoutRow(screen, prefs, keyboard, chosen).text ?: chosen.name
    }

    @Test fun `Layout never chosen - Custom from Dense while it differs, Dense again once it matches`() {
        assertEquals("Dense", layoutText())
        prefs.edit { putBoolean(Settings.PREF_SHOW_NUMBER_ROW, false) }
        assertEquals("Custom · from Dense", layoutText())
        prefs.edit { remove(Settings.PREF_SHOW_NUMBER_ROW) }
        assertEquals("Dense", layoutText()) // (was "Custom" for good)
    }

    @Test fun `Layout chosen saved Work - Custom from Work while it differs, Work once it matches`() {
        LayoutPresets.save(real, listOf(LayoutPresets.saved(screen, keyboard, "Work", null)))
        prefs.edit { putString(LayoutPresets.PREF_SELECTED, "Work") }
        assertEquals("Work", layoutText())
        prefs.edit { putBoolean(Settings.PREF_SHOW_NUMBER_ROW, false) }
        assertEquals("Custom · from Work", layoutText())
        prefs.edit { remove(Settings.PREF_SHOW_NUMBER_ROW) }
        assertEquals("Work", layoutText())
    }

    @Test fun `Layout chosen built-in Classic - Custom from Classic while it differs, Classic once it matches`() {
        val classic = builtInLayouts.first { it.name == ctx.getString(R.string.layout_preset_classic) }
        LayoutPresets.applySettings(screen, LayoutPresets.settingsFor(keyboard, classic, true), prefs)
        prefs.edit { putString(LayoutPresets.PREF_SELECTED, classic.name) }
        assertEquals("Classic", layoutText())
        prefs.edit { putBoolean(Settings.PREF_SHOW_NUMBER_ROW, false) } // (Classic has it)
        assertEquals("Custom · from Classic", layoutText())
        prefs.edit { putBoolean(Settings.PREF_SHOW_NUMBER_ROW, true) }
        assertEquals("Classic", layoutText())
    }

    @Test fun `Layout deleted - Custom from Work even when it matches Curmudgeon`() {
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))))
        prefs.edit { putString(LayoutPresets.PREF_SELECTED, "Work") }
        LayoutPresets.delete(real, "Work")
        assertEquals("Custom · from Work", layoutText())
    }

    // ---- popups ----

    private fun popupsText(): String {
        val sets = KeyPopupOverrides.loadSets(real)
        return popupsRow(screen, prefs, keyboard, popupPresets(keyboard, sets, true), sets, KeyPopupOverrides.load(prefs), true).text!!
    }

    @Test fun `popups never chosen - Custom from the default built-in while they differ, its name once they match`() {
        assertEquals("HeliBoard extra", popupsText()) // (the defaults: every accent, no symbol map)
        KeyPopupOverrides.save(prefs, mapOf("a" to listOf("à")))
        assertEquals("Custom · from HeliBoard extra", popupsText()) // (was plain "Custom")
        KeyPopupOverrides.save(prefs, emptyMap())
        assertEquals("HeliBoard extra", popupsText())
    }

    @Test fun `popups chosen saved set Mine - Custom from Mine while they differ, Mine once they match`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        prefs.edit { putString(KeyPopupOverrides.PREF_SELECTED_SET, "Mine") }
        KeyPopupOverrides.save(prefs, mapOf("a" to listOf("à")))
        assertEquals("Mine", popupsText())
        KeyPopupOverrides.save(prefs, mapOf("a" to listOf("à"), "e" to listOf("é")))
        assertEquals("Custom · from Mine", popupsText())
        KeyPopupOverrides.save(prefs, mapOf("a" to listOf("à")))
        assertEquals("Mine", popupsText())
    }

    @Test fun `popups chosen built-in HeliBoard - Custom from HeliBoard while they differ, HeliBoard once they match`() {
        prefs.edit { putString(Settings.PREF_MORE_POPUP_KEYS, "main") } // (HeliBoard's recipe: its accents level)
        assertEquals("HeliBoard", popupsText())
        KeyPopupOverrides.save(prefs, mapOf("a" to listOf("à")))
        assertEquals("Custom · from HeliBoard", popupsText()) // (was plain "Custom")
        KeyPopupOverrides.save(prefs, emptyMap())
        assertEquals("HeliBoard", popupsText())
    }

    @Test fun `popup set deleted - Custom from Mine until a set is chosen`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        prefs.edit { putString(KeyPopupOverrides.PREF_SELECTED_SET, "Mine") }
        KeyPopupOverrides.save(prefs, mapOf("a" to listOf("à")))
        deletePopupSet(ctx, "Mine")
        assertEquals("Custom · from Mine", popupsText())
        KeyPopupOverrides.save(prefs, emptyMap()) // (matches HeliBoard extra now: still the deleted set's copy)
        assertEquals("Custom · from Mine", popupsText())
    }
}

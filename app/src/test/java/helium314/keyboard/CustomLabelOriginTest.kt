// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
import kotlin.test.assertNull

/**
 * Owner 2026-10-11: "Custom 1's subtext just sticks around forever until saved/deleted/renamed/etc." … "I see, so
 * reference that name with rename. Hardcode with delete?": while the item exists the label references it (a rename
 * shows the new name); deleted, its name then is stored with each keyboard's label ("Custom · from Work") until the
 * copy goes (an item chosen again, Save as new, the label cleared). Themes, Layouts and popup sets.
 */
@RunWith(RobolectricTestRunner::class)
class CustomLabelOriginTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private val theme = CustomLabels.Kind.THEME
    private val layout = CustomLabels.Kind.LAYOUT
    private val popups = CustomLabels.Kind.POPUPS

    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).put("c", 3).toString())
            .putInt("keyboard_profile_next_id", 4)
            .commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() {
        AppearanceDraft.close(); LayoutDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    // the rows as they draw it: the chosen item's name while it exists, a label shown when the keyboard has one
    private fun themeRow(p: SharedPreferences): String {
        val chosen = p.getString(AppearanceLooks.PREF_SELECTED, null)?.takeIf { n -> AppearanceLooks.load(real).any { it.name == n } }
        return if (!CustomLabels.has(p, theme)) chosen ?: "-" else CustomLabels.text(ctx, p, theme, chosen)
    }
    private fun layoutRow(p: SharedPreferences): String {
        val chosen = p.getString(LayoutPresets.PREF_SELECTED, null)?.takeIf { n -> LayoutPresets.load(real).any { it.name == n } }
        return if (!CustomLabels.has(p, layout)) chosen ?: "-" else CustomLabels.text(ctx, p, layout, chosen)
    }
    private fun popupsRow(p: SharedPreferences): String {
        val chosen = p.getString(KeyPopupOverrides.PREF_SELECTED_SET, null)?.takeIf { n -> KeyPopupOverrides.loadSets(real).any { it.name == n } }
        return if (!CustomLabels.has(p, popups)) chosen ?: "-" else CustomLabels.text(ctx, p, popups, chosen)
    }

    private fun saveWork() = AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Work", mapOf(Settings.PREF_THEME_COLORS to "Warm")),
        AppearanceLooks.Look("Home", mapOf(Settings.PREF_THEME_COLORS to "Cool"))), now = true)
    private fun deleteTheme(name: String) { // as AppearanceScreen does
        AppearanceLooks.save(real, AppearanceLooks.load(real).filter { it.name != name }, now = true)
        AppearanceLooks.choiceRenamed(ctx, name, null)
    }
    private fun chooseTheme(p: SharedPreferences, name: String) { // as the theme list's OK does
        p.edit { putString(AppearanceLooks.PREF_SELECTED, name) }
        CustomLabels.clear(p, theme)
    }

    @Test fun `deleted theme Work keeps "from Work" on all three keyboards, until one chooses another`() {
        saveWork()
        real.edit().apply { for (id in 1..3) putString(own(id, AppearanceLooks.PREF_SELECTED), "Work") }.commit()
        CustomLabels.mark(view(2), theme) // (2 was tweaked already: labelled before the delete)
        deleteTheme("Work")
        assertEquals(List(3) { "Custom · from Work" }, (1..3).map { themeRow(view(it)) })
        assertEquals(List(3) { "Work" }, (1..3).map { real.getString(own(it, theme.fromKey), null) })

        chooseTheme(view(1), "Home")
        assertFalse(real.contains(own(1, theme.key)))
        assertFalse(real.contains(own(1, theme.fromKey)))
        assertEquals("Home", themeRow(view(1)))
        assertEquals(listOf("Custom · from Work", "Custom · from Work"), (2..3).map { themeRow(view(it)) })
    }

    @Test fun `a renamed theme that still exists is shown by its new name, stored only once it is deleted`() {
        saveWork()
        real.edit().putString(own(1, AppearanceLooks.PREF_SELECTED), "Work").commit()
        CustomLabels.mark(view(1), theme) // (tweaked)
        assertEquals("Custom · from Work", themeRow(view(1)))
        AppearanceLooks.save(real, AppearanceLooks.load(real).map { if (it.name == "Work") AppearanceLooks.Look("Office", it.values) else it }, now = true)
        AppearanceLooks.choiceRenamed(ctx, "Work", "Office")
        assertEquals("Custom · from Office", themeRow(view(1)))
        assertFalse(real.contains(own(1, theme.fromKey)))
        deleteTheme("Office")
        assertEquals("Custom · from Office", themeRow(view(1)))
    }

    @Test fun `Save as new drops the stored origin`() {
        saveWork()
        real.edit().putString(own(1, AppearanceLooks.PREF_SELECTED), "Work").commit()
        deleteTheme("Work")
        // as the theme row's Save as new does (list closed): the copy is the new theme
        AppearanceLooks.save(real, AppearanceLooks.load(real) + AppearanceLooks.saved(ctx, "Mine", null), now = true)
        chooseTheme(view(1), "Mine")
        assertFalse(real.contains(own(1, theme.fromKey)))
        assertEquals("Mine", themeRow(view(1)))
    }

    @Test fun `Discard after a theme delete puts Work back, no label and no origin left`() {
        saveWork()
        real.edit().apply { for (id in 1..3) putString(own(id, AppearanceLooks.PREF_SELECTED), "Work") }.commit()
        AppearanceDraft.of(ctx)
        deleteTheme("Work")
        AppearanceDraft.rejectOpen(ctx)
        for (id in 1..3) {
            assertEquals("Work", real.getString(own(id, AppearanceLooks.PREF_SELECTED), null))
            assertFalse(real.contains(own(id, theme.key)))
            assertFalse(real.contains(own(id, theme.fromKey)))
        }
        assertEquals(List(3) { "Work" }, (1..3).map { themeRow(view(it)) })
    }

    @Test fun `shared Appearance - one label and one origin, a hidden own choice gets its own`() {
        saveWork()
        real.edit().putString(own(2, AppearanceLooks.PREF_SELECTED), "Work").commit() // (its own, hidden once shared)
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.APPEARANCE, true, 1)
        real.edit().putString(AppearanceLooks.PREF_SELECTED, "Work").commit() // (the shared choice)
        deleteTheme("Work")
        assertEquals(1, real.getInt(theme.key, 0))
        assertEquals("Work", real.getString(theme.fromKey, null))
        assertEquals(List(3) { "Custom · from Work" }, (1..3).map { themeRow(view(it)) })
        assertFalse(real.contains(own(1, theme.key))); assertFalse(real.contains(own(1, theme.fromKey)))
        assertEquals("Work", real.getString(own(2, theme.fromKey), null)) // (hidden, back when per keyboard)
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.APPEARANCE, false, KeyboardProfiles.SHARED)
        assertEquals("Custom · from Work", themeRow(view(2)))
    }

    @Test fun `a copied keyboard takes the label with its origin`() {
        saveWork()
        real.edit().putString(own(1, AppearanceLooks.PREF_SELECTED), "Work").commit()
        deleteTheme("Work")
        KeyboardProfiles.copy(real, 1, 3)
        assertEquals("Work", real.getString(own(3, theme.fromKey), null))
        assertEquals("Custom · from Work", themeRow(view(3)))
    }

    @Test fun `a restored keyboard brings its label with its origin`() {
        val restored = helium314.keyboard.latin.settings.SettingsSubtype(java.util.Locale.US, "")
        val zip = java.io.File(ctx.cacheDir, "origin-${System.nanoTime()}.zip")
        val values = HashMap<String?, Any?>()
        values[Settings.PREF_ENABLED_SUBTYPES] = restored.toPref()
        values["separate_settings_per_keyboard"] = true
        values["keyboard_profile_ids"] = JSONObject().put(restored.toPref(), 1).toString()
        values[helium314.keyboard.latin.PICKED_ONLY_DONE] = true
        values[helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE] = true
        values[helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE] = true
        values[own(1, theme.key)] = 1
        values[own(1, theme.fromKey)] = "Work"
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("preferences.json"))
            helium314.keyboard.settings.preferences.settingsToJsonStream(values, z); z.closeEntry() }
        try { helium314.keyboard.settings.preferences.restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true) }
        finally { zip.delete() }
        val id = KeyboardProfiles.idEntries(real).getValue(restored.toPref())
        assertEquals("Work", real.getString(own(id, theme.fromKey), null))
        assertEquals("Custom · from Work", themeRow(view(id)))
    }

    @Test fun `deleted Layout Work keeps "from Work", Discard on Layout and Typing puts it back`() {
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))))
        real.edit().putString(own(1, LayoutPresets.PREF_SELECTED), "Work").putString(own(2, LayoutPresets.PREF_SELECTED), "Work").commit()
        LayoutPresets.delete(real, "Work")
        assertEquals(listOf("Custom · from Work", "Custom · from Work"), (1..2).map { layoutRow(view(it)) })
        // keyboard 1 chooses a Layout again (as the list's keep() does)
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Home", emptyMap())))
        view(1).edit { putString(LayoutPresets.PREF_SELECTED, "Home") }
        CustomLabels.clear(view(1), layout)
        assertFalse(real.contains(own(1, layout.fromKey)))
        assertEquals("Custom · from Work", layoutRow(view(2)))

        LayoutPresets.save(real, LayoutPresets.load(real) + LayoutPresets.Preset("Desk", emptyMap()))
        real.edit().putString(own(3, LayoutPresets.PREF_SELECTED), "Desk").commit()
        LayoutDraft.of(ctx, "en_US:")
        LayoutPresets.delete(real, "Desk")
        assertEquals("Custom · from Desk", layoutRow(view(3)))
        LayoutDraft.rejectOpen(ctx)
        assertEquals("Desk", real.getString(own(3, LayoutPresets.PREF_SELECTED), null))
        assertFalse(real.contains(own(3, layout.key))); assertFalse(real.contains(own(3, layout.fromKey)))
        assertEquals("Custom · from Work", layoutRow(view(2))) // (from before the screen opened: stays)
    }

    @Test fun `deleted popup set Mine keeps "from Mine", choosing a set again drops it`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        real.edit().putString(own(1, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").putString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").commit()
        deletePopupSet(ctx, "Mine")
        assertNull(real.getString(own(1, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(listOf("Custom · from Mine", "Custom · from Mine"), (1..2).map { popupsRow(view(it)) })
        CustomLabels.clear(view(1), popups) // (as applyPreset does when a set is chosen)
        assertFalse(real.contains(own(1, popups.key))); assertFalse(real.contains(own(1, popups.fromKey)))
        assertEquals("Custom · from Mine", popupsRow(view(2)))
    }

    @Test fun `shared Layout menu - one popups label and one origin`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à")))))
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.LAYOUT, true, 1)
        real.edit().putString(KeyPopupOverrides.PREF_SELECTED_SET, "Mine").commit()
        deletePopupSet(ctx, "Mine")
        assertEquals(setOf(popups.key, popups.fromKey), CustomLabels.stored(real, listOf(popups)))
        assertEquals(List(3) { "Custom · from Mine" }, (1..3).map { popupsRow(view(it)) })
    }
}

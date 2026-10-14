// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardScopeContext
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.deletePopupSet
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11, delete = off the list only: every keyboard and saved item using it keeps its copy, shown as Custom
 * nothing goes back to the default, nothing comes back by itself. Fonts are the exception (owner 2026-10-11: global
 * delete): the file goes and its users go back to the default font; and palettes (owner 2026-10-11: one app-wide list,
 * "Move all to default, Or keep all on Warm and move this to default"; the Custom N copies are gone).
 */
@RunWith(RobolectricTestRunner::class)
class DeleteKeepsCopiesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun view(id: Int) = ProfilePreferences(real) { id }

    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).toString())
            .commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() {
        AppearanceDraft.close(); FontLibrary.dir(ctx).deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    // (owner 2026-10-11: labels are just "Custom", no number; only kept palettes are numbered)
    @Test fun `a deleted Layout leaves its keyboards their settings, each with its Custom label`() {
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))))
        real.edit().putString(own(1, LayoutPresets.PREF_SELECTED), "Work").putBoolean(own(1, Settings.PREF_SHOW_NUMBER_ROW), true)
            .putString(own(2, LayoutPresets.PREF_SELECTED), "Work").commit()
        LayoutPresets.delete(real, "Work")
        assertTrue(LayoutPresets.load(real).isEmpty())
        assertEquals(true, real.all[own(1, Settings.PREF_SHOW_NUMBER_ROW)]) // (kept)
        assertNull(real.getString(own(1, LayoutPresets.PREF_SELECTED), null))
        assertEquals(listOf(1, 1), listOf(real.getInt(own(1, CustomLabels.Kind.LAYOUT.key), 0), real.getInt(own(2, CustomLabels.Kind.LAYOUT.key), 0)))
        assertTrue(CustomLabels.has(view(1), CustomLabels.Kind.LAYOUT))
    }

    @Test fun `a deleted popup set never comes back from a saved Layout`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("á")), "q!", "x")))
        val layout = LayoutPresets.Preset("Work", mapOf(KeyPopupOverrides.PREF_SELECTED_SET to "Mine", "script" to "Latn",
            KeyPopupOverrides.PREF to """{"a":["á"]}"""))
        LayoutPresets.save(real, listOf(layout))
        real.edit().putString(own(1, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").commit()
        deletePopupSet(ctx, "Mine")
        val kept = LayoutPresets.load(real).single()
        assertNull(kept.values[KeyPopupOverrides.PREF_SELECTED_SET]) // (the Layout keeps the popups, not the name)
        assertEquals("""{"a":["á"]}""", kept.values[KeyPopupOverrides.PREF])
        LayoutPresets.restorePopupSet(ctx, SettingsSubtype(Locale.US, ""), kept)
        assertTrue(KeyPopupOverrides.loadSets(real).isEmpty())
        assertTrue(real.getInt(own(1, CustomLabels.Kind.POPUPS.key), 0) > 0)
    }

    // owner 2026-10-11: global delete ("Global only let's do"): the font file goes and every keyboard (all sets, hidden
    // ones too) and saved theme that chose it goes back to the default font; with Appearance open only after its Save
    @Test fun `a deleted font goes back to the default everywhere, after Save with Appearance open`() {
        val screen = KeyboardScopeContext(ctx, 1) // (Appearance of keyboard A)
        val font = File(FontLibrary.dir(ctx).apply { mkdirs() }, "Mine.ttf").apply { writeText("x") }
        val value = FontLibrary.PREFIX + "Mine.ttf"
        real.edit().putString(own(1, Settings.PREF_KEY_FONT), value).putString(own(2, Settings.PREF_KEY_FONT), value)
            .putString(Settings.PREF_HINT_FONT, value) // (a shared key)
            .putString(own(9, Settings.PREF_EMOJI_FONT), value).commit() // (a set no keyboard has now)
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_SUGGESTION_FONT to value))), now = true)
        val draft = AppearanceDraft.of(screen)
        FontLibrary.delete(screen, "Mine.ttf")
        assertFalse(font.exists()); assertFalse("Mine.ttf" in FontLibrary.names(ctx)) // (held until Save)
        assertEquals("default", real.getString(own(1, Settings.PREF_KEY_FONT), null)) // (the edited keyboard lets go at once)
        assertEquals(value, real.getString(own(2, Settings.PREF_KEY_FONT), null)) // (the others after Save)
        draft.accept(screen)
        assertFalse(font.exists())
        assertEquals("default", real.getString(own(1, Settings.PREF_KEY_FONT), null))
        assertEquals("default", real.getString(own(2, Settings.PREF_KEY_FONT), null))
        assertEquals("default", real.getString(Settings.PREF_HINT_FONT, null))
        assertEquals("default", real.getString(own(9, Settings.PREF_EMOJI_FONT), null))
        assertEquals("default", AppearanceLooks.load(real).single().values[Settings.PREF_SUGGESTION_FONT])
    }

    @Test fun `Discard after a font delete brings the file and every choice back`() {
        val screen = KeyboardScopeContext(ctx, 1) // (Appearance of keyboard A)
        val font = File(FontLibrary.dir(ctx).apply { mkdirs() }, "Mine.ttf").apply { writeText("x") }
        val value = FontLibrary.PREFIX + "Mine.ttf"
        real.edit().putString(own(1, Settings.PREF_KEY_FONT), value).putString(own(2, Settings.PREF_KEY_FONT), value)
            .putString(Settings.PREF_HINT_FONT, value).commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_SUGGESTION_FONT to value))), now = true)
        val before = real.all.filterKeys { it != AppearanceLooks.PREF }
        AppearanceDraft.of(screen)
        FontLibrary.delete(screen, "Mine.ttf")
        AppearanceDraft.rejectOpen(screen)
        assertTrue(font.exists()); assertTrue("Mine.ttf" in FontLibrary.names(ctx))
        assertEquals(value, real.getString(own(1, Settings.PREF_KEY_FONT), null))
        assertEquals(value, real.getString(own(2, Settings.PREF_KEY_FONT), null))
        assertEquals(value, real.getString(Settings.PREF_HINT_FONT, null))
        assertEquals(value, AppearanceLooks.load(real).single().values[Settings.PREF_SUGGESTION_FONT])
        val fontsNow = real.all.filterKeys { k -> FontLibrary.fontPrefs.any { k.endsWith(it) } }
        assertEquals(before.filterKeys { k -> FontLibrary.fontPrefs.any { k.endsWith(it) } }, fontsNow)
    }

    // (owner 2026-10-11: palettes are one app-wide list; before, they kept it as Custom N, off the list)
    @Test fun `a palette in use deleted for all - its keyboards and saved themes go to the default colours`() {
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", "colours").putString(own(1, Settings.PREF_THEME_COLORS), "Warm")
            .putString(own(2, Settings.PREF_THEME_COLORS), "Warm").commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Warm"))), now = true)
        helium314.keyboard.settings.Palettes.delete(real, view(1), "Warm", onlyHere = false)
        assertEquals(null, real.getString(own(1, Settings.PREF_THEME_COLORS), null))
        assertEquals(null, real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertTrue(AppearanceLooks.load(real).single().values.let { it.containsKey(Settings.PREF_THEME_COLORS) && it[Settings.PREF_THEME_COLORS] == null })
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Warm"))
    }

    @Test fun `a palette nothing uses is deleted, and one deleted for all comes back on Discard`() {
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Cold", "c").commit()
        helium314.keyboard.settings.Palettes.delete(real, view(1), "Cold", onlyHere = false)
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Cold"))

        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", "colours").putString(own(2, Settings.PREF_THEME_COLORS), "Warm").commit()
        AppearanceDraft.of(ctx)
        helium314.keyboard.settings.Palettes.delete(real, view(1), "Warm", onlyHere = false)
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals("colours", real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
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
 * Owner 2026-10-11, delete = off the list only: every keyboard and saved item using it keeps its copy, shown as Custom 1,
 * Custom 2 …; nothing goes back to the default, nothing comes back by itself; a font nothing uses asks before its file goes.
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

    @Test fun `a deleted Layout leaves its keyboards their settings, each with its Custom number`() {
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true))))
        real.edit().putString(own(1, LayoutPresets.PREF_SELECTED), "Work").putBoolean(own(1, Settings.PREF_SHOW_NUMBER_ROW), true)
            .putString(own(2, LayoutPresets.PREF_SELECTED), "Work").commit()
        LayoutPresets.delete(real, "Work")
        assertTrue(LayoutPresets.load(real).isEmpty())
        assertEquals(true, real.all[own(1, Settings.PREF_SHOW_NUMBER_ROW)]) // (kept)
        assertNull(real.getString(own(1, LayoutPresets.PREF_SELECTED), null))
        assertEquals(setOf(1, 2), setOf(real.getInt(own(1, CustomLabels.Kind.LAYOUT.key), 0), real.getInt(own(2, CustomLabels.Kind.LAYOUT.key), 0)))
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

    @Test fun `a deleted font in use only leaves the list, every user keeps it`() {
        val font = File(FontLibrary.dir(ctx).apply { mkdirs() }, "Mine.ttf").apply { writeText("x") }
        real.edit().putString(own(2, Settings.PREF_KEY_FONT), FontLibrary.PREFIX + "Mine.ttf").commit()
        assertTrue(FontLibrary.removeFromList(ctx, "Mine.ttf"))
        assertTrue(font.exists())
        assertFalse("Mine.ttf" in FontLibrary.names(ctx))
        assertEquals(FontLibrary.PREFIX + "Mine.ttf", real.getString(own(2, Settings.PREF_KEY_FONT), null))
        assertEquals(listOf(FontLibrary.PREFIX + "Mine.ttf"), FontLibrary.choices(ctx, FontLibrary.PREFIX + "Mine.ttf")) // (its own keyboard sees it)
        assertTrue(FontLibrary.choices(ctx, "default").isEmpty())
    }

    @Test fun `a font nothing uses asks first - nothing hidden or deleted by the trash alone`() {
        val font = File(FontLibrary.dir(ctx).apply { mkdirs() }, "Mine.ttf").apply { writeText("x") }
        assertFalse(FontLibrary.removeFromList(ctx, "Mine.ttf")) // (the dialog asks)
        assertTrue("Mine.ttf" in FontLibrary.names(ctx))
        FontLibrary.hide(ctx, "Mine.ttf") // "Keep the file"
        assertTrue(font.exists()); assertFalse("Mine.ttf" in FontLibrary.names(ctx))
    }

    @Test fun `a deleted palette in use stays with its keyboards and saved themes as Custom N, off the list`() {
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", "colours").putString(Settings.PREF_THEME_COLORS, "Warm")
            .putString(own(2, Settings.PREF_THEME_COLORS), "Warm").commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Warm"))), now = true)
        KeyboardTheme.deleteUserColors(ctx, real, "Warm")
        val kept = real.getString(Settings.PREF_THEME_COLORS, null)!!
        assertTrue(KeyboardTheme.isHiddenPalette(kept))
        assertEquals("Custom 1", KeyboardTheme.paletteName(ctx, kept))
        assertEquals("colours", real.getString(Settings.PREF_USER_COLORS_PREFIX + kept, null))
        assertEquals(kept, real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals(kept, AppearanceLooks.load(real).single().values[Settings.PREF_THEME_COLORS])
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Warm"))
    }

    @Test fun `a palette nothing uses is deleted, and a kept one comes back on Discard`() {
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Cold", "c").commit()
        KeyboardTheme.deleteUserColors(ctx, real, "Cold")
        assertFalse(real.contains(Settings.PREF_USER_COLORS_PREFIX + "Cold"))

        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", "colours").putString(Settings.PREF_THEME_COLORS, "Warm").commit()
        AppearanceDraft.of(ctx)
        KeyboardTheme.deleteUserColors(ctx, real, "Warm")
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Warm", real.getString(Settings.PREF_THEME_COLORS, null))
        assertEquals("colours", real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11: "Yes, repeats are fine." … "Ok, call them just Custom without number.": the label of a keyboard's
 * own copy of a Layout, theme or popups is "Custom" (with "· from X" while its item exists), a flag, not a number;
 * only kept palettes ("~custom:N") stay numbered, uniquely.
 */
@RunWith(RobolectricTestRunner::class)
class CustomLabelsNoNumberTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private val theme = CustomLabels.Kind.THEME.key

    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put("a", 1).put("b", 2).put("c", 3).toString())
            .putInt("keyboard_profile_next_id", 4)
            .commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { AppearanceDraft.close(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    @Test fun `deleting theme Work used by three keyboards labels all three the same, no numbers`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Work", mapOf(Settings.PREF_THEME_COLORS to "Warm"))), now = true)
        real.edit().apply { for (id in 1..3) putString(own(id, AppearanceLooks.PREF_SELECTED), "Work") }.commit()
        AppearanceLooks.save(real, emptyList(), now = true)
        AppearanceLooks.choiceRenamed(ctx, "Work", null)
        assertEquals(listOf(1, 1, 1), (1..3).map { real.getInt(own(it, theme), 0) })
        assertTrue((1..3).all { CustomLabels.has(view(it), CustomLabels.Kind.THEME) })
        // (Work is gone: "Custom" alone, as the row shows a deleted item's copy; "from X" only while X exists)
        assertEquals("Custom", CustomLabels.text(ctx, null))
        assertEquals("Custom · from Work", CustomLabels.text(ctx, "Work"))
    }

    @Test fun `an older numbered label still reads as a label, a new one is written as 1`() {
        real.edit().putInt(own(1, theme), 7).commit()
        assertTrue(CustomLabels.has(view(1), CustomLabels.Kind.THEME))
        CustomLabels.mark(view(1), CustomLabels.Kind.THEME)
        assertEquals(7, real.getInt(own(1, theme), 0)) // (left as it is)
        CustomLabels.mark(view(2), CustomLabels.Kind.THEME)
        assertEquals(CustomLabels.SET, real.getInt(own(2, theme), 0))
    }

    @Test fun `a restore takes a keyboard's label as it is, not renumbered`() {
        real.edit().putInt(own(1, theme), 1).commit()
        val restored = SettingsSubtype(Locale.US, "")
        val zip = File(ctx.cacheDir, "nonum-${System.nanoTime()}.zip")
        val values = HashMap<String?, Any?>()
        values[Settings.PREF_ENABLED_SUBTYPES] = restored.toPref()
        values["separate_settings_per_keyboard"] = true
        values["keyboard_profile_ids"] = JSONObject().put(restored.toPref(), 1).toString()
        values[helium314.keyboard.latin.PICKED_ONLY_DONE] = true
        values[helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE] = true
        values[helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE] = true
        values[own(1, theme)] = 1
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json")); settingsToJsonStream(values, z); z.closeEntry() }
        try { restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true) } finally { zip.delete() }
        val id = KeyboardProfiles.idEntries(real).getValue(restored.toPref())
        assertEquals(1, real.getInt(own(id, theme), 0))
        assertEquals(1, real.getInt(own(1, theme), 0))
    }

    @Test fun `kept palettes stay Custom 1 and Custom 2, unique`() {
        val first = KeyboardTheme.nextHiddenPalette(real)
        real.edit().putString(own(1, Settings.PREF_THEME_COLORS), first).commit()
        val second = KeyboardTheme.nextHiddenPalette(real)
        assertEquals(KeyboardTheme.HIDDEN_PALETTE + "1", first)
        assertEquals(KeyboardTheme.HIDDEN_PALETTE + "2", second)
        assertEquals("Custom 1", KeyboardTheme.paletteName(ctx, first))
        assertEquals("Custom 2", KeyboardTheme.paletteName(ctx, second))
    }

    @Test fun `Discard still drops a label added while the screen was open`() {
        real.edit().putInt(own(1, theme), 1).commit()
        AppearanceDraft.of(ctx)
        view(2).edit().putInt(theme, 1).commit() // (as the row does when it first shows the label)
        AppearanceDraft.rejectOpen(ctx)
        assertFalse(real.contains(own(2, theme)))
        assertEquals(1, real.getInt(own(1, theme), 0))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.SavedItemDefaults
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
import kotlin.test.assertTrue

/**
 * A menu made shared keeps each keyboard's own settings for it, as the main switch does, and made per keyboard again
 * each keyboard reads its own (owner 2026-10-11: "Deleting should not happen. Behavior should be same"). (owner
 * 2026-10-11: no separate common set; the first keyboard's own settings are the common ones: US, set 1, first in the
 * list; nothing is copied either way.)
 */
@RunWith(RobolectricTestRunner::class)
class ShareMenuKeepsOwnTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val appearance = KeyboardProfiles.Group.APPEARANCE
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val label = CustomLabels.Kind.THEME.key
    private val us = SettingsSubtype(Locale.US, "")
    private val fr = SettingsSubtype(Locale.FRANCE, "")
    private val de = SettingsSubtype(Locale.GERMANY, "")
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)

    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put(us.toPref(), 1).put(fr.toPref(), 2).put(de.toPref(), 3).toString())
            .putInt("keyboard_profile_next_id", 4)
            .putString(Settings.PREF_ENABLED_SUBTYPES, helium314.keyboard.latin.utils.SubtypeSettings.createPrefSubtypes(listOf(us, fr, de)))
            .commit()
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.filesDir = DeviceProtectedUtils.getFilesDir(ctx)
        KeyboardProfiles.deleteAllFiles()
    }
    @After fun tearDown() { KeyboardProfiles.deleteAllFiles(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun share(winner: Int) = KeyboardProfiles.setGroupShared(real, appearance, true)
    private fun unshare() = KeyboardProfiles.setGroupShared(real, appearance, false)

    // (owner 2026-10-11: labels are just "Custom", no number; only kept palettes are numbered)
    @Test fun `own values and labels stay stored while shared and come back`() {
        view(1).edit().putFloat(gap, 2f).commit()
        view(2).edit().putFloat(gap, 4f).commit()
        CustomLabels.mark(view(2), CustomLabels.Kind.THEME)
        assertEquals(1, view(2).getInt(label, 0))
        share(1)
        assertEquals(2f, view(1).getFloat(gap, 0f))
        assertEquals(2f, view(2).getFloat(gap, 0f)) // (1's, for all)
        assertEquals(0, view(2).getInt(label, 0)) // (1 had no label)
        assertFalse(view(2).all.containsKey(label)) // (nothing hidden leaks into the reads)
        assertEquals(2f, view(2).all[gap])
        assertEquals(4f, real.getFloat(own(2, gap), 0f), "2's own gap went")
        assertEquals(1, real.getInt(own(2, label), 0), "2's own label went")
        assertEquals(2f, real.getFloat(own(1, gap), 0f))
        unshare()
        assertEquals(4f, view(2).getFloat(gap, 0f))
        assertEquals(1, view(2).getInt(label, 0))
        assertEquals(2f, view(1).getFloat(gap, 0f))
        assertEquals(0, view(1).getInt(label, 0))
    }

    @Test fun `a keyboard with nothing of its own reads the shared values after unshare`() {
        view(1).edit().putFloat(gap, 2f).commit()
        share(1)
        view(3).edit().putFloat(gap, 3f).commit() // (the shared set, while shared: US's)
        unshare()
        assertEquals(0f, view(3).getFloat(gap, 0f)) // (its own: nothing, the default; no copy of US's)
        assertEquals(3f, view(1).getFloat(gap, 0f)) // (US keeps the edits: they were its own)
    }

    @Test fun `a font deleted while shared is gone from a hidden own set too`() {
        val font = Settings.PREF_KEY_FONT
        view(1).edit().putString(font, FontLibrary.PREFIX + "A").commit()
        view(2).edit().putString(font, FontLibrary.PREFIX + "B").commit()
        share(1)
        FontLibrary.delete(ctx, "B")
        unshare()
        assertEquals("default", view(2).getString(font, null))
        assertEquals(FontLibrary.PREFIX + "A", view(1).getString(font, null))
    }

    @Test fun `pictures come back per keyboard, the shared ones only where a keyboard has none`() {
        val dir = KeyboardProfiles.filesDir!!
        fun pic(id: Int) = File(dir, "custom_background_image" + if (id == KeyboardProfiles.SHARED) "" else "_p$id")
        view(1).edit().putFloat(gap, 2f).commit()
        view(2).edit().putFloat(gap, 4f).commit()
        pic(1).writeText("one"); pic(2).writeText("two")
        share(1)
        assertEquals(pic(1), KeyboardProfiles.profileFile(ctx, "custom_background_image")) // (the shared picture: US's)
        assertEquals("two", pic(2).readText(), "2's own picture went")
        pic(1).writeText("shared") // changed while shared
        unshare()
        assertEquals("shared", pic(1).readText()) // (US keeps the edits: its own)
        assertEquals("two", pic(2).readText())
        assertFalse(pic(3).exists()) // (3 had nothing of its own: nothing copied)
    }

    @Test fun `the winner's default mark makes the shared value default, other marks stay`() {
        view(1).edit().remove(gap).commit() // 1: "Default"
        view(2).edit().remove(gap).commit()
        share(1)
        assertEquals(0f, view(3).getFloat(gap, 0f))
        assertTrue(real.contains(own(2, KeyboardProfiles.TOMBSTONE + gap)))
        view(3).edit().putFloat(gap, 6f).commit() // (US's set)
        assertEquals(6f, view(2).getFloat(gap, 0f)) // (the mark is not read while shared)
        unshare()
        assertEquals(0f, view(2).getFloat(gap, 0f)) // its own "Default" again
    }


    @Test fun `the saved-items switch flipped while shared reaches the hidden own sets`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to null), mapOf(gap to 0.5f))), now = true)
        view(1).edit().putBoolean(Settings.PREF_KEY_TEXT_BOLD, true).commit() // (1 chose no theme, and no gap)
        AppearanceLooks.applyToOther(ctx, fr, AppearanceLooks.load(real).single())
        assertFalse(real.contains(own(2, gap))) // (switch on: at its default)
        share(1)
        real.edit().putBoolean(SavedItemDefaults.PREF, false).commit()
        SavedItemDefaults.applySwitch(ctx, real, false)
        unshare()
        assertEquals(0.5f, view(2).getFloat(gap, 0f), "2's hidden set missed the switch")
    }

    @Test fun `a theme deleted while shared gives the hidden set that chose it its own Custom label`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to 0.5f), emptyMap())), now = true)
        view(1).edit().putFloat(gap, 2f).commit() // (1 chose no theme)
        AppearanceLooks.applyToOther(ctx, fr, AppearanceLooks.load(real).single())
        share(1)
        AppearanceLooks.choiceRenamed(ctx, "Sunset", null) // (deleted)
        assertEquals(0, view(1).getInt(label, 0), "the shared set got the hidden set's label")
        unshare()
        assertEquals(1, view(2).getInt(label, 0))
        assertEquals(null, view(2).getString(AppearanceLooks.PREF_SELECTED, null))
        assertEquals(0.5f, view(2).getFloat(gap, 0f)) // (it keeps the look)
        assertEquals(0, view(1).getInt(label, 0))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.applySetToOther
import helium314.keyboard.settings.screens.popupSetDiffers
import helium314.keyboard.settings.screens.updateSetInLayout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11, the copy model: a keyboard's changes stay its own ("Custom 1 · from Sunset") until a saved item is
 * deliberately updated (its save icon); the update reaches the other keyboards ticked in its dialog, and the open
 * screen's Discard undoes it there too.
 */
@RunWith(RobolectricTestRunner::class)
class CopyModelTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val us = SettingsSubtype(Locale.US, "")
    private val fr = "fr:".toSettingsSubtype()
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)

    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put(us.toPref(), 1).put(fr.toPref(), 2).toString())
            .commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { AppearanceDraft.close(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    @Test fun `Custom labels number per kind across keyboards, and go when an item is chosen`() {
        assertEquals(1, CustomLabels.number(real, view(1), CustomLabels.Kind.THEME))
        assertEquals(1, CustomLabels.number(real, view(1), CustomLabels.Kind.THEME)) // (kept)
        assertEquals(2, CustomLabels.number(real, view(2), CustomLabels.Kind.THEME))
        assertEquals(1, CustomLabels.number(real, view(2), CustomLabels.Kind.LAYOUT)) // (each kind its own)
        CustomLabels.clear(view(1), CustomLabels.Kind.THEME)
        assertFalse(real.contains(own(1, CustomLabels.Kind.THEME.key)))
        assertEquals(1, CustomLabels.number(real, view(1), CustomLabels.Kind.THEME)) // (the free number again)
        assertEquals("Custom 2 · from Sunset", CustomLabels.text(ctx, 2, "Sunset"))
        assertEquals("Custom 2", CustomLabels.text(ctx, 2, null))
    }

    @Test fun `a keyboard's popups differ from its set when an arrangement or the map changed`() {
        val set = KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("á")), "q!", "x")
        val kb = us.with(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.MORE_POPUPS, "all")
        val prefs = view(1)
        prefs.edit().putString(Settings.PREF_SYMBOL_POPUP_MAP, "q!").putString(Settings.PREF_POPUP_KEYS_ORDER, "x").commit()
        assertFalse(popupSetDiffers(prefs, kb, set, mapOf("a" to listOf("á")), takeNew = true))
        assertTrue(popupSetDiffers(prefs, kb, set, mapOf("a" to listOf("à")), takeNew = true))
        prefs.edit().putString(Settings.PREF_SYMBOL_POPUP_MAP, "w?").commit()
        assertTrue(popupSetDiffers(prefs, kb, set, mapOf("a" to listOf("á")), takeNew = true))
        // a map the set kept at its default: with the switch on, the keyboard reading the default is the same
        val atDefault = KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(), "q!", "x", setOf(KeyPopupOverrides.MAP))
        prefs.edit().remove(Settings.PREF_SYMBOL_POPUP_MAP).commit()
        assertFalse(popupSetDiffers(prefs, kb, atDefault, emptyMap(), takeNew = true))
        assertTrue(popupSetDiffers(prefs, kb, atDefault, emptyMap(), takeNew = false))
    }

    @Test fun `an updated popup set reaches a ticked keyboard and a ticked saved Layout`() {
        val set = KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("e" to listOf("é")), "q!", "x")
        view(2).edit().putString(KeyPopupOverrides.PREF_SELECTED_SET, "Old").putInt(CustomLabels.Kind.POPUPS.key, 3).commit()
        applySetToOther(ctx, fr, set, takeNew = true)
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(mapOf("e" to listOf("é")), KeyPopupOverrides.load(view(2)))
        assertEquals("q!", real.getString(own(2, Settings.PREF_SYMBOL_POPUP_MAP), null))
        assertFalse(real.contains(own(2, CustomLabels.Kind.POPUPS.key)))

        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(KeyPopupOverrides.PREF_SELECTED_SET to "Mine",
            KeyPopupOverrides.PREF to "{}"))))
        updateSetInLayout(real, "Work", KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("e" to listOf("é")), "q!", "x",
            setOf(KeyPopupOverrides.ORDER)))
        val work = LayoutPresets.load(real).single()
        assertEquals("q!", work.values[Settings.PREF_SYMBOL_POPUP_MAP])
        assertNull(work.values[Settings.PREF_POPUP_KEYS_ORDER]) // (at its default in the set: so in the Layout)
        assertEquals("x", work.remembered[Settings.PREF_POPUP_KEYS_ORDER])
        assertEquals(listOf("é"), JSONObject(work.values[KeyPopupOverrides.PREF] as String).getJSONArray("e").let { listOf(it.getString(0)) })
    }

    @Test fun `an updated Layout reaches a ticked keyboard`() {
        val work = LayoutPresets.Preset("Work", mapOf(Settings.PREF_SHOW_NUMBER_ROW to true, Settings.PREF_KEY_LONGPRESS_TIMEOUT to null))
        view(2).edit().putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 400).commit()
        LayoutPresets.applyToOther(ctx, fr, work)
        assertEquals(true, real.all[own(2, Settings.PREF_SHOW_NUMBER_ROW)])
        assertFalse(real.contains(own(2, Settings.PREF_KEY_LONGPRESS_TIMEOUT))) // ("default" in the Layout)
        assertEquals("Work", real.getString(own(2, LayoutPresets.PREF_SELECTED), null))
    }

    @Test fun `an updated theme reaches a ticked keyboard, and Discard undoes it there`() {
        val sunset = AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_KEY_HORIZONTAL_GAP to 3f))
        view(2).edit().putFloat(Settings.PREF_KEY_HORIZONTAL_GAP, 2f).putString(AppearanceLooks.PREF_SELECTED, "Old").commit()
        AppearanceDraft.of(ctx) // Appearance open (on keyboard 1)
        AppearanceLooks.applyToOther(ctx, fr, sunset)
        assertEquals(3f, real.all[own(2, Settings.PREF_KEY_HORIZONTAL_GAP)])
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
        AppearanceDraft.rejectOpen(ctx)
        assertEquals(2f, real.all[own(2, Settings.PREF_KEY_HORIZONTAL_GAP)])
        assertEquals("Old", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
    }

    @Test fun `a theme's pictures on a ticked keyboard come back on Discard`() {
        val picture = KeyboardProfiles.pictureFiles(ctx, 2).first()
        picture.parentFile?.mkdirs(); picture.writeText("old picture")
        val noPictures = AppearanceLooks.Look("Plain", mapOf(AppearanceLooks.PICTURES to ""))
        AppearanceDraft.of(ctx)
        AppearanceLooks.applyToOther(ctx, fr, noPictures)
        assertFalse(picture.exists()) // (the theme has none)
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("old picture", picture.readText())
        picture.delete()
    }
}

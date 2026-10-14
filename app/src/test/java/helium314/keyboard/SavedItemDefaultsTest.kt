// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SavedItemDefaults
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.popupPresets
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11: a saved Layout, theme or popup set remembers the value its unpicked settings had when saved; the
 * App settings switch "Saved items take new defaults" (on by default) decides whether they follow today's default or
 * apply as saved. Built-ins always follow; a restore keeps the phone's switch.
 */
@RunWith(RobolectricTestRunner::class)
class SavedItemDefaultsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val longPress = Settings.PREF_KEY_LONGPRESS_TIMEOUT
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val keyboard = SettingsSubtype(Locale.US, "")

    @Before fun setUp() { real.edit().clear().commit() }
    @After fun tearDown() { real.edit().clear().commit() }

    @Test fun `a saved Layout remembers its unpicked settings' values, and keeps them when stored`() {
        val values = mapOf<String, Any?>(longPress to null, Settings.PREF_SHOW_NUMBER_ROW to true)
        val remembered = SavedItemDefaults.remembered(values)
        assertEquals(mapOf<String, Any>(longPress to Defaults.PREF_KEY_LONGPRESS_TIMEOUT), remembered) // (only the unpicked one)
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", values, remembered = remembered)))
        val loaded = LayoutPresets.load(real).single()
        assertNull(loaded.values[longPress])
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, loaded.remembered[longPress])
    }

    @Test fun `switch on - an unpicked setting follows today's default, off - it applies as saved`() {
        // saved when long press was 300 by default; today's default differs
        val work = LayoutPresets.Preset("Work", mapOf(longPress to null), remembered = mapOf(longPress to 300))
        assertNull(LayoutPresets.settingsFor(keyboard, work, takeNew = true)[longPress])
        assertEquals(300, LayoutPresets.settingsFor(keyboard, work, takeNew = false)[longPress])
        // a built-in always follows
        val builtIn = LayoutPresets.Preset("Curmudgeon", mapOf(longPress to null), builtIn = true, remembered = mapOf(longPress to 300))
        assertNull(LayoutPresets.settingsFor(keyboard, builtIn, takeNew = false)[longPress])
    }

    @Test fun `a theme likewise, and it isn't shown changed while the keyboard has what it applied`() {
        val sunset = AppearanceLooks.Look("Sunset", mapOf(gap to null), mapOf(gap to 0.5f))
        AppearanceLooks.save(real, listOf(sunset), now = true)
        val loaded = AppearanceLooks.load(real).single()
        assertEquals(0.5f, loaded.remembered[gap])
        assertNull(AppearanceLooks.applied(loaded, takeNew = true)[gap])
        assertEquals(0.5f, AppearanceLooks.applied(loaded, takeNew = false)[gap])

        ctx.prefs().edit().putFloat(gap, 0.5f).commit() // (what the theme applied with the switch off)
        real.edit().putBoolean(SavedItemDefaults.PREF, false).commit()
        assertFalse(AppearanceLooks.isTweaked(ctx, loaded))
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        assertTrue(AppearanceLooks.isTweaked(ctx, loaded)) // (on: the theme means today's default gap)
    }

    @Test fun `saved items from before remember what they show today, once`() {
        real.edit().putString(LayoutPresets.PREF, """[{"name":"Work","values":{"$longPress":{"d":true}}}]""")
            .putString(AppearanceLooks.PREF, """[{"name":"Sunset","values":{"$gap":{"d":true}}}]""").commit()
        SavedItemDefaults.rememberToday(real)
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, LayoutPresets.load(real).single().remembered[longPress])
        assertEquals(Defaults.PREF_KEY_HORIZONTAL_GAP, AppearanceLooks.load(real).single().remembered[gap])
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(longPress to null), remembered = mapOf(longPress to 300))))
        SavedItemDefaults.rememberToday(real)
        assertEquals(300, LayoutPresets.load(real).single().remembered[longPress]) // (ran once)
    }

    @Test fun `a popup set's settings at their default follow only with the switch on`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(), "q!", "x",
            setOf(KeyPopupOverrides.MAP))))
        val set = KeyPopupOverrides.loadSets(real).single()
        assertEquals(setOf(KeyPopupOverrides.MAP), set.atDefault)
        assertEquals(setOf(KeyPopupOverrides.MAP), popupPresets(keyboard, listOf(set), takeNew = true).last().follows)
        assertEquals(emptySet(), popupPresets(keyboard, listOf(set), takeNew = false).last().follows)
    }

    @Test fun `a popup set a Layout brings back keeps the Layout's remembered map, marked at its default`() {
        val layout = LayoutPresets.Preset("Mine", mapOf(KeyPopupOverrides.PREF_SELECTED_SET to "Gone", "script" to "Latn",
            Settings.PREF_SYMBOL_POPUP_MAP to null), remembered = mapOf(Settings.PREF_SYMBOL_POPUP_MAP to "q!"))
        LayoutPresets.restorePopupSet(ctx, keyboard, layout)
        val set = KeyPopupOverrides.loadSets(real).single()
        assertEquals("q!", set.symbolMap)
        assertTrue(KeyPopupOverrides.MAP in set.atDefault)
        // (no order in the Layout at all: today's default, not picked either)
        assertEquals(Defaults.PREF_POPUP_KEYS_ORDER, set.popupOrder)
        assertTrue(KeyPopupOverrides.ORDER in set.atDefault)
    }

    @Test fun `a full restore keeps the phone's switch, set or not`() {
        val zip = File(ctx.cacheDir, "saved-defaults-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(SavedItemDefaults.PREF to true, Settings.PREF_SHOW_NUMBER_ROW to true), z)
            z.closeEntry()
        }
        real.edit().putBoolean(SavedItemDefaults.PREF, false).commit()
        restoreEverythingFrom(ctx, zip)
        assertEquals(false, real.all[SavedItemDefaults.PREF])
        assertEquals(true, real.all[Settings.PREF_SHOW_NUMBER_ROW]) // (the backup did come in)
        real.edit().remove(SavedItemDefaults.PREF).commit()
        restoreEverythingFrom(ctx, zip)
        assertFalse(real.contains(SavedItemDefaults.PREF))
        zip.delete()
    }

    @Test fun `the switch is one for all keyboards, also when set from a keyboard with its own settings`() {
        assertTrue(helium314.keyboard.latin.settings.KeyboardProfiles.isAppWide(SavedItemDefaults.PREF))
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        helium314.keyboard.latin.settings.ProfilePreferences(real) { 5 }.edit().putBoolean(SavedItemDefaults.PREF, false).commit()
        assertEquals(false, real.all[SavedItemDefaults.PREF])
        assertFalse(SavedItemDefaults.takeNewDefaults(real))
    }
}

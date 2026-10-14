// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardScopeContext
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.SavedItemDefaults
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.applySetToOther
import helium314.keyboard.settings.screens.popupPresets
import helium314.keyboard.settings.screens.storePopupSet
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
    private val keyboard = SettingsSubtype(Locale.US, "").withId(1)

    @Before fun setUp() { real.edit().clear().commit() }
    @After fun tearDown() { AppearanceDraft.close(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

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
        // (the keyboard on the phone: a write for no keyboard of the phone is dropped, 2026-10-12)
        storeKeyboards(ctx, real, listOf(keyboard), reload = false)
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
        helium314.keyboard.latin.settings.ProfilePreferences(real) { kid(5) }.edit().putBoolean(SavedItemDefaults.PREF, false).commit()
        assertEquals(false, real.all[SavedItemDefaults.PREF])
        assertFalse(SavedItemDefaults.takeNewDefaults(real))
    }

    // ---- review 2026-10-11 A1-A3: flipping the switch acts on the keyboards at once, and Update keeps what is still at
    // the chosen item's remembered default as default ----

    private val fr = SettingsSubtype(Locale.FRANCE, "").withId(2)
    private val de = SettingsSubtype(Locale.GERMANY, "").withId(3)
    private fun own(id: Int, key: String) = idKey(id, key)
    private fun mark(id: Int, key: String) = idKey(id, KeyboardProfiles.TOMBSTONE + key)
    private fun view(id: Int) = ProfilePreferences(real) { kid(id) }

    /** Separate settings, keyboards k1 (US), k2 (French), k3 (German) listed (owner 2026-10-11: keyboards have unique
     *  ids; a set is its keyboard id's, no id map); saved Layout Work (long press at its default, 300 when saved: today's
     *  is 150) and theme Sunset (gap at its default, 0.5 when saved). */
    private fun separateWithWorkAndSunset() {
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        storeKeyboards(ctx, real, listOf(keyboard, fr, de))
        KeyboardProfiles.loadGroups(real)
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(longPress to null), remembered = mapOf(longPress to 300))))
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to null), mapOf(gap to 0.5f))), now = true)
    }
    /** [kb] chooses Work and Sunset, as their lists do (with the switch as it is). */
    private fun choose(kb: SettingsSubtype) {
        LayoutPresets.applyToOther(ctx, kb, LayoutPresets.load(real).single())
        AppearanceLooks.applyToOther(ctx, kb, AppearanceLooks.load(real).single())
    }
    /** The App settings switch flipped: what its row does (the value stored, then its onCheckedChange). */
    private fun flip(on: Boolean) {
        real.edit().putBoolean(SavedItemDefaults.PREF, on).commit()
        SavedItemDefaults.applySwitch(ctx, real, on)
    }

    @Test fun `A1 - switch off, Update keeps a setting still at the chosen item's remembered default as default`() {
        separateWithWorkAndSunset()
        flip(false)
        choose(keyboard); choose(de)
        assertEquals(300, real.getInt(own(1, longPress), 0)) // (off: written as saved)
        view(3).edit().putInt(longPress, 400).commit() // (keyboard 3 picks its own)
        val work = LayoutPresets.load(real).single()
        val updated = LayoutPresets.saved(KeyboardScopeContext(ctx, kid(1)), keyboard, "Work", work)
        assertNull(updated.values[longPress], "Update made the remembered default a pick")
        assertEquals(300, updated.remembered[longPress])
        assertEquals(400, LayoutPresets.saved(KeyboardScopeContext(ctx, kid(3)), de, "Work", work).values[longPress]) // (a pick stays one)
        val sunset = AppearanceLooks.load(real).single()
        val look = AppearanceLooks.saved(KeyboardScopeContext(ctx, kid(1)), "Sunset", sunset)
        assertNull(look.values[gap], "Update made the theme's remembered gap a pick")
        assertEquals(0.5f, look.remembered[gap])
        // switch on: a stored value equal to it was picked by hand (the switch took the others off), so it is saved as one
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        assertEquals(300, LayoutPresets.saved(KeyboardScopeContext(ctx, kid(1)), keyboard, "Work", work).values[longPress])
    }

    @Test fun `A1 - a popup set updated with the switch off keeps its remembered map and accents at their default`() {
        separateWithWorkAndSunset()
        flip(false)
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(), "q!", "x",
            setOf(KeyPopupOverrides.MAP, KeyPopupOverrides.ACCENTS))))
        applySetToOther(ctx, fr, KeyPopupOverrides.loadSets(real).single(), takeNew = false)
        assertEquals("q!", real.getString(own(2, Settings.PREF_SYMBOL_POPUP_MAP), null)) // (off: written as saved)
        val frNow = fr.with(ExtraValue.MORE_POPUPS, "all")
        storePopupSet(KeyboardScopeContext(ctx, kid(2)), frNow, "Mine", "all", null, emptyMap())
        val set = KeyPopupOverrides.loadSets(real).single()
        assertEquals(setOf(KeyPopupOverrides.MAP, KeyPopupOverrides.ACCENTS), set.atDefault, "Update made the set's remembered defaults picks")
        assertEquals("q!", set.symbolMap)
    }

    @Test fun `A2 - switch off, choose, flip on - the written values go, the keyboards read the default, a pick stays`() {
        separateWithWorkAndSunset()
        flip(false)
        choose(keyboard); choose(fr); choose(de)
        view(3).edit().putInt(longPress, 400).commit()
        assertEquals(300, real.getInt(own(2, longPress), 0))
        flip(true)
        for (id in listOf(1, 2)) {
            assertFalse(real.contains(own(id, longPress)), "set $id kept the value written with the switch off")
            assertFalse(real.contains(mark(id, longPress))) // (the shared set holds none: no mark, review 2026-10-11 S4)
            assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, view(id).getInt(longPress, Defaults.PREF_KEY_LONGPRESS_TIMEOUT))
            assertFalse(real.contains(own(id, gap)), "set $id kept the theme's gap")
        }
        assertEquals(400, real.getInt(own(3, longPress), 0)) // (its own pick)
        assertFalse(LayoutPresets.isTweaked(KeyboardScopeContext(ctx, kid(1)), keyboard, LayoutPresets.load(real).single()))
        // a popup set's map and accents too
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(), "q!", "x",
            setOf(KeyPopupOverrides.MAP, KeyPopupOverrides.ACCENTS))))
        flip(false)
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, fr.toPref()).commit()
        applySetToOther(ctx, fr, KeyPopupOverrides.loadSets(real).single(), takeNew = false)
        flip(true)
        assertFalse(real.contains(own(2, Settings.PREF_SYMBOL_POPUP_MAP)), "the set's map stayed on the keyboard")
        val frNow = listedById(real, kid(2))!! // (its id kept)
        assertNull(frNow.getExtraValueOf(ExtraValue.MORE_POPUPS), "the set's accents stayed on the keyboard")
    }

    @Test fun `A3 - switch on, choose, flip off - the remembered values are written, so a new default doesn't reach them`() {
        separateWithWorkAndSunset()
        // keyboard 4 chooses Work through its own entry only (owner 2026-10-11: no separate common set; the first
        // keyboard's own settings are the common ones: no shared pick to read, so it gets Work's value too)
        real.edit().putString(own(4, LayoutPresets.PREF_SELECTED), "Work").commit()
        choose(keyboard); choose(fr); choose(de)
        view(3).edit().putInt(longPress, 400).commit()
        assertFalse(real.contains(own(1, longPress))) // (on: nothing stored, at the default)
        flip(false)
        for (id in listOf(1, 2)) {
            assertEquals(300, real.getInt(own(id, longPress), 0), "set $id doesn't hold Work's long press: a new default reaches it")
            assertFalse(real.contains(mark(id, longPress)))
            assertEquals(0.5f, real.getFloat(own(id, gap), 0f), "set $id doesn't hold Sunset's gap")
        }
        assertEquals(400, real.getInt(own(3, longPress), 0)) // (its own pick)
        assertEquals(300, real.getInt(own(4, longPress), 0))
        assertFalse(real.contains(longPress))

        assertFalse(LayoutPresets.isTweaked(KeyboardScopeContext(ctx, kid(1)), keyboard, LayoutPresets.load(real).single()))
        // and back on: gone again
        flip(true)
        assertFalse(real.contains(own(1, longPress)))
        assertEquals(400, real.getInt(own(3, longPress), 0))
    }
}

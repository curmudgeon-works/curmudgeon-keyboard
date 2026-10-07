// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.curmudgeonUpgrades
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.gesture.GestureStats
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.SettingDefaults
import helium314.keyboard.settings.keepBrokenAside
import helium314.keyboard.settings.screens.switchAfterDialogClose
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Low items of the 2026-10-06 review fixed on 2026-10-07 that have a unit to test (S1, S3, T2, T4, T5, T6, C4). */
@RunWith(RobolectricTestRunner::class)
class ReReviewLowTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    // language priority is the keyboard's (decision 2026-10-07)
    @Test fun `a keyboard's own set holds its own language priorities`() {
        val lp = helium314.keyboard.latin.utils.LanguagePriority
        val hi = java.util.Locale.forLanguageTag("hi")
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        val own = helium314.keyboard.latin.settings.ProfilePreferences(real) { 2 }
        lp.set(real, hi, lp.LOW)
        assertEquals(lp.LOW, lp.get(own, hi)) // nothing of its own: the shared value
        lp.set(own, hi, lp.HIGH)
        assertEquals(lp.HIGH, lp.get(own, hi))
        assertEquals(lp.LOW, lp.get(real, hi)) // the shared one unchanged
        assertTrue(real.contains("p2/language_priority_hi"))
        assertFalse(KeyboardProfiles.isGlobal("language_priority_hi"))
    }

    // S1
    @Test fun `a build that found nothing in a dictionary file isn't tried again for a minute`() {
        assertTrue(GestureDecoderVocabulary.buildAllowed("xx-broken", now = 1000))
        GestureDecoderVocabulary.noteBuildFailed("xx-broken", now = 1000)
        assertFalse(GestureDecoderVocabulary.buildAllowed("xx-broken", now = 1000 + 30_000))
        assertTrue(GestureDecoderVocabulary.buildAllowed("xx-broken", now = 1000 + 61_000))
        assertTrue(GestureDecoderVocabulary.buildAllowed("en-US", now = 1000))
    }

    // S3
    @Test fun `swipe statistics are written once for several swipes`() {
        GestureStats.prefsProvider = { real }
        var writes = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == GestureStats.PREF_KEY) writes++ }
        real.registerOnSharedPreferenceChangeListener(listener)
        try {
            GestureStats.onSwipe("t1", 5); GestureStats.onPicked(0)
            GestureStats.onSwipe("t1", 7); GestureStats.onDeleted()
            GestureStats.onSwipe("t1", 9)
            assertEquals(0, writes) // nothing written yet
            val row = GestureStats.read(real)["t1"]!! // what's in memory counts already
            assertEquals(2, row.swipes); assertEquals(3, row.timed)
            GestureStats.flush(real)
            assertEquals(1, writes)
            assertTrue(real.getString(GestureStats.PREF_KEY, "")!!.contains("\"t1\""))
        } finally { real.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // S3, reviewer: the cached rows must not come back over a reset or a restore
    @Test fun `swipe statistics read again after the stored ones were replaced`() {
        GestureStats.prefsProvider = { real }
        GestureStats.onSwipe("t1", 5); GestureStats.onPicked(0)
        GestureStats.flush(real)
        real.edit().remove(GestureStats.PREF_KEY).commit() // (a factory reset)
        GestureStats.reload()
        assertNull(GestureStats.read(real)["t1"])
        GestureStats.onSwipe("t2", 5)
        GestureStats.flush(real)
        assertFalse(real.getString(GestureStats.PREF_KEY, "")!!.contains("\"t1\""))
    }

    // reviewer 2026-10-07: the dialog's OK runs confirm then dismiss; the close mustn't turn a just-confirmed switch off
    @Test fun `a switch with a dialog stays on after OK and goes off after Cancel when it was off`() {
        val f = ::switchAfterDialogClose
        assertNull(f(true, true)); assertNull(f(true, false)) // on before: the close changes nothing
        assertNull(f(false, true)) // off before, OK: stays on
        assertEquals(false, f(false, false)) // off before, Cancel: off again
    }

    // T2 (+ T3: the steps take the stored settings)
    @Test fun `a fresh install stores no defaults for the retired font and trail switches`() {
        curmudgeonUpgrades(real, freshInstall = true)
        assertFalse(real.contains(Settings.PREF_FONT_FOLLOWS_KEY_TEXT))
        assertFalse(real.contains(Settings.PREF_GESTURE_PREVIEW_TRAIL))
        assertTrue(real.getBoolean("fonts_follow_migrated", false))
        assertTrue(real.getBoolean("trail_thickness_migrated", false))
    }

    // T4
    @Test fun `the moved-markers cleanup waits while Refine is per keyboard`() {
        val mark = "p3/~" + Settings.PREF_GESTURE_TURN_WEIGHT
        real.edit().putBoolean(mark, true).putBoolean(KeyboardProfiles.Group.REFINE.prefKey, false).commit()
        KeyboardProfiles.removeMovedMarkers(real)
        assertTrue(real.contains(mark)) // a genuine reset: kept
        assertFalse(real.getBoolean("moved_markers_removed", false))
        real.edit().putBoolean(KeyboardProfiles.Group.REFINE.prefKey, true).commit()
        KeyboardProfiles.removeMovedMarkers(real)
        assertFalse(real.contains(mark))
        assertTrue(real.getBoolean("moved_markers_removed", false))
    }

    // T5
    @Test fun `a setting on a shared menu keeps its known default`() {
        real.edit().putBoolean(KeyboardProfiles.Group.LAYOUT.prefKey, true).commit()
        KeyboardProfiles.loadGroups(real)
        assertTrue(SettingDefaults.includedInDefaults(Settings.PREF_SHOW_NUMBER_ROW))
        assertFalse(SettingDefaults.includedInDefaults(Settings.PREF_ENABLED_SUBTYPES))
    }

    // T6
    @Test fun `a themes list that can't be read is kept aside before an empty save`() {
        real.edit().putString(AppearanceLooks.PREF, "{not json").commit()
        assertEquals(emptyList(), AppearanceLooks.load(real))
        AppearanceLooks.save(real, emptyList())
        val aside = real.all.keys.filter { it.startsWith(AppearanceLooks.PREF + "_broken_") }
        assertEquals(1, aside.size)
        assertEquals("{not json", real.getString(aside[0], null))
        // a readable list is saved as usual, nothing put aside
        keepBrokenAside(real, "layout_presets")
        assertNull(real.all.keys.firstOrNull { it.startsWith("layout_presets_broken_") })
    }

    // C4
    @Test fun `a renamed or deleted theme is followed in every keyboard's set`() {
        real.edit().putString(AppearanceLooks.PREF_SELECTED, "Mine").putString("p2/" + AppearanceLooks.PREF_SELECTED, "Mine")
            .putString("p3/" + AppearanceLooks.PREF_SELECTED, "Other").putString("p4/other_key", "Mine").commit()
        KeyboardProfiles.replaceValueEverywhere(real, AppearanceLooks.PREF_SELECTED, "Mine", "Yours")
        assertEquals("Yours", real.getString(AppearanceLooks.PREF_SELECTED, null))
        assertEquals("Yours", real.getString("p2/" + AppearanceLooks.PREF_SELECTED, null))
        assertEquals("Other", real.getString("p3/" + AppearanceLooks.PREF_SELECTED, null))
        assertEquals("Mine", real.getString("p4/other_key", null))
        KeyboardProfiles.replaceValueEverywhere(real, AppearanceLooks.PREF_SELECTED, "Yours", null)
        assertFalse(real.contains(AppearanceLooks.PREF_SELECTED))
        assertFalse(real.contains("p2/" + AppearanceLooks.PREF_SELECTED))
        assertEquals("Other", real.getString("p3/" + AppearanceLooks.PREF_SELECTED, null))
    }
}

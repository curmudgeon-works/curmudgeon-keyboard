// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.profileIds
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.hiddenSettingsNote
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.keyboardName
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * (review 2026-10-11 R7, owner: a restore never turns separate settings on) With one set of settings for all, a
 * restored keyboard's settings go into its own set, behind the common ones; the common set and the other keyboards stay
 * as they are, and the restore says so once ("should inform so it doesn't look like nothing happened"). Turning
 * separate settings on later shows them.
 */
@RunWith(RobolectricTestRunner::class)
class ReReview2R7Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "r7-${System.nanoTime()}.zip") }
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val autoCap = Settings.PREF_AUTO_CAP
    private lateinit var en: SettingsSubtype
    private lateinit var hi: SettingsSubtype
    private val de get() = available("de-DE")
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        en = available("en-US"); hi = available("hi")
        // the phone: English (1) and Hindi (2) on one set for all (gap 2, auto-caps off): English's, the first keyboard's
        // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones)
        useKeyboards(ctx, real, en, hi)
        profileIds(real, mapOf(en to 1, hi to 2), 3)
        real.edit().putFloat(own(1, gap), 2f).putBoolean(own(1, autoCap), false).commit()
        KeyboardProfiles.disable(real)

        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    private fun backup() {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(en, hi)),
                Settings.PREF_SELECTED_SUBTYPE to en.toPref(), "separate_settings_per_keyboard" to true,
                "keyboard_profile_ids" to JSONObject().put(en.toPref(), 6).put(hi.toPref(), 7).toString(), "keyboard_profile_next_id" to 8,
                gap to 3f, own(7, gap) to 5f, own(7, autoCap) to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
        }
    }

    /** Everything outside Hindi's own set that is a keyboard's setting: the common set and the other keyboards' sets. */
    private fun outsideHindi() = real.all.filterKeys { stored ->
        KeyboardProfiles.splitOwnKey(stored)?.let { it.first != 2 } ?: !KeyboardProfiles.isAppWide(stored) }

    @Test fun `with one set for all, Hindi's settings wait in its own set, separate settings stay off, and come on with them`() {
        backup()
        val before = outsideHindi()
        val (_, hidden) = restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true, decisions = mapOf(hi to RestoreDecision.Merge(hi)))
        assertFalse(KeyboardProfiles.isSeparate(real), "the restore turned separate settings on")
        assertEquals(before, outsideHindi())
        assertEquals(2f, view(1).getFloat(gap, 0f)); assertEquals(2f, view(2).getFloat(gap, 0f)) // (one set for all)
        assertEquals(5f, real.getFloat(own(2, gap), 0f))
        assertEquals(true, real.getBoolean(own(2, autoCap), false))
        assertEquals(listOf(hi), hidden)
        assertEquals("${keyboardName(hi, ctx)}'s settings were restored. You'll see them once you turn on Use separate settings in App settings.",
            hiddenSettingsNote(ctx, hidden))
        // (English is the first keyboard: its settings are the common ones; owner 2026-10-11, no separate common set)
        assertEquals("The settings of ${keyboardName(hi, ctx)}, ${keyboardName(de, ctx)} were restored. You'll see them once you turn on Use separate settings in App settings.",
            hiddenSettingsNote(ctx, listOf(hi, de)))
        assertEquals("${keyboardName(en, ctx)}'s settings were restored. All keyboards use them, as they're the common settings.\n" +
            "${keyboardName(hi, ctx)}'s settings were restored. You'll see them once you turn on Use separate settings in App settings.",
            hiddenSettingsNote(ctx, listOf(hi, en)))

        // separate settings on: Hindi reads the backup's, English the common ones
        KeyboardProfiles.enable(real)
        assertEquals(5f, view(2).getFloat(gap, 0f))
        assertEquals(true, view(2).getBoolean(autoCap, false))
        assertEquals(2f, view(1).getFloat(gap, 0f))
        assertFalse(view(1).getBoolean(autoCap, true))
    }

    @Test fun `with separate settings on, nothing to say`() {
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        backup()
        val (_, hidden) = restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true, decisions = mapOf(hi to RestoreDecision.Merge(hi)))
        assertEquals(emptyList(), hidden)
        assertNull(hiddenSettingsNote(ctx, hidden))
    }
}

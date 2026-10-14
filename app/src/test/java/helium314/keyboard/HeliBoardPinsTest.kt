// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.heliBoardLayoutPins
import helium314.keyboard.latin.heliBoardPins
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.LayoutPresets
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A keyboard on the HeliBoard Layout in 0.3.008 keeps HeliBoard's values when the defaults move to the Curmudgeon
 *  Layout's (re-review 2026-10-07, MEDIUM). (owner 2026-10-11: keyboards have unique ids; sets are "p<id>/", found by
 *  their entries; an old phone's or backup's "p<n>/" sets and id map are converted by the ids upgrade first) */
@RunWith(RobolectricTestRunner::class)
class HeliBoardPinsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val sel = LayoutPresets.PREF_SELECTED

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { randomIds(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun heli() = LayoutPresets.builtIn(ctx).single { it.name == ctx.getString(R.string.layout_preset_heliboard) }

    @Test fun `a keyboard that picked HeliBoard in 0_3_008 stays HeliBoard's`() {
        // what 0.3.008's HeliBoard Layout stored, in the common set of then: its named keys, the five later-pinned ones
        // not at all
        real.edit {
            putString(Settings.PREF_ENABLED_SUBTYPES, SettingsSubtype(Locale.US, "").withId(1).toPref())
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SettingsSubtype(Locale.US, "").withId(1).toPref())
            for ((key, value) in heli().values - heliBoardPins().keys) KeyboardProfiles.put(this, key, value)
            putString(sel, ctx.getString(R.string.layout_preset_heliboard))
        }
        heliBoardLayoutPins(ctx, real, freshInstall = false)
        for ((key, value) in heliBoardPins()) assertEquals(value, real.all[key], key)
        // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones) the common
        // set becomes the first keyboard's, which had none
        KeyboardProfiles.dropCommonSet(real, fromCommon = true)
        assertFalse(LayoutPresets.isTweaked(ctx, SettingsSubtype(Locale.US, "").withId(1), heli()), "the Layouts row would say unsaved")
    }

    @Test fun `a keyboard's own set on HeliBoard extra, under its old name, is pinned and keeps its choice`() {
        real.edit {
            putString(idKey(2, sel), "HeliBoard Extra") // 0.3.008's name
            putInt(idKey(2, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 450) // its own pick stays
            putString(idKey(3, sel), "Mine") // another Layout: untouched
        }
        heliBoardLayoutPins(ctx, real, freshInstall = false)
        assertEquals(ctx.getString(R.string.layout_preset_heliboard_extra), real.getString(idKey(2, sel), null))
        assertEquals(450, real.getInt(idKey(2, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 0))
        assertEquals(false, real.all[idKey(2, Settings.PREF_BACKSPACE_SPEED_UP)])
        assertEquals("", real.all[idKey(2, Settings.PREF_SYMBOL_POPUP_MAP)])
        assertNull(real.all[idKey(3, Settings.PREF_BACKSPACE_SPEED_UP)])
        assertNull(real.all[Settings.PREF_BACKSPACE_SPEED_UP], "the shared set didn't pick HeliBoard")
    }

    // review 2026-10-08 M3: keyboards reading the shared HeliBoard Layout got their own set from the pins, and kept
    // HeliBoard's values once the shared set later took another Layout (2026-10-10)
    @Test fun `an update pins only the sets that picked a HeliBoard Layout, not keyboards reading the shared one`() {
        val longPress = Settings.PREF_KEY_LONGPRESS_TIMEOUT
        val extra = ctx.getString(R.string.layout_preset_heliboard_extra)
        // (a 0.3.008 phone's old sets and id map: the ids upgrade gives en k2, de k3, fr k4, it k5)
        countingIds(from = 2)
        real.edit {
            putInt(Settings.PREF_VERSION_CODE, 3008)
            putBoolean(helium314.keyboard.latin.PICKED_ONLY_DONE, true) // a 0.3.008 phone
            // (the common set kept: the step that drops it after this one is NoCommonSetTest's; owner 2026-10-11, no
            // separate common set)
            putBoolean(KeyboardProfiles.NO_COMMON_SET_DONE, true)
            putString("keyboard_profile_ids", """{"en_US:":2,"de_DE:":3,"fr_FR:":4,"it_IT:":5}""")
            putString(sel, "HeliBoard Extra") // the shared set, under 0.3.008's name
            // keyboard 2: nothing of its own (learned words only)
            putBoolean("p3/${KeyboardProfiles.TOMBSTONE}$longPress", true) // keyboard 3: its long press "at the default"
            putString("p4/$sel", ctx.getString(R.string.layout_preset_heliboard)) // keyboard 4: its own pick
            putBoolean("p5/${KeyboardProfiles.TOMBSTONE}$sel", true) // keyboard 5: no Layout chosen (the Curmudgeon one)
        }
        helium314.keyboard.latin.checkVersionUpgrade(ctx)
        assertEquals(extra, real.getString(sel, null))
        for ((key, value) in heliBoardPins()) assertEquals(value, real.all[key], key)
        assertEquals(emptyList(), real.all.keys.filter { it.startsWith(idKey(2, "")) }, "keyboard 2 got a set of its own")
        // keyboard 3 read the default of then, HeliBoard's (300), and reads it still; the rest it reads from the shared set
        assertEquals(300, real.all[idKey(3, longPress)])
        assertNull(real.all[idKey(3, KeyboardProfiles.TOMBSTONE + longPress)])
        for (key in heliBoardPins().keys - longPress) assertNull(real.all[idKey(3, key)], key)
        for ((key, value) in heliBoardPins()) assertEquals(value, real.all[idKey(4, key)], key)
        for (key in heliBoardPins().keys + sel) assertNull(real.all[idKey(5, key)], key)
    }

    @Test fun `a fresh install writes nothing and runs once`() {
        real.edit { putString(sel, ctx.getString(R.string.layout_preset_heliboard)) }
        heliBoardLayoutPins(ctx, real, freshInstall = true)
        assertNull(real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        heliBoardLayoutPins(ctx, real, freshInstall = false) // already done
        assertNull(real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
    }

    // re-review of 806882c75: a restore ran the step with the phone's menu sharing, not the backup's
    @Test fun `a restored backup's HeliBoard keyboard is pinned under the backup's own menu sharing`() {
        real.edit { putBoolean(KeyboardProfiles.Group.LAYOUT.prefKey, true) }
        KeyboardProfiles.loadGroups(real) // the phone shares Layout & Typing
        // the restored file: Layout & Typing per keyboard, keyboard 2 on HeliBoard, the step not run yet (from before the
        // ids: the ids upgrade gives it k2)
        countingIds(from = 2)
        real.edit().clear().putInt(Settings.PREF_VERSION_CODE, 3008).putString("keyboard_profile_ids", """{"en_US:":2}""")
            .putString("p2/$sel", ctx.getString(R.string.layout_preset_heliboard)).commit()
        helium314.keyboard.settings.preferences.restoreFollowUp(ctx)
        assertEquals(false, real.all[idKey(2, Settings.PREF_BACKSPACE_SPEED_UP)], "keyboard 2 missed its pins: ${real.all.keys}")
    }

    // 2026-10-07: "On restoring a single keyboard from 3008 backup -- the backup should apply backup's values."
    @Test fun `a keyboard restored on its own from a 0_3_008 backup gets HeliBoard's values`() {
        val keyboard = SettingsSubtype(java.util.Locale.US, "").withId(1)
        val heliName = ctx.getString(R.string.layout_preset_heliboard)
        // (its own set in the backup: owner 2026-10-11, no separate common set; a backup's common set is ignored; its
        // sets by id, as a backup is read)
        val backup = mapOf<String, Any?>(helium314.keyboard.latin.PICKED_ONLY_DONE to true,
            idKey(1, sel) to heliName, idKey(1, Settings.PREF_BACKSPACE_SPEED_UP) to true) // (a pick of its own stays)
        val restored = helium314.keyboard.settings.preferences.restoredSettings(ctx, backup, keyboard)
        assertEquals(300, restored[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        assertEquals(helium314.keyboard.latin.settings.Defaults.BACKSPACE_DELAY_FOLLOWS, restored[Settings.PREF_BACKSPACE_LONGPRESS_DELAY])
        assertEquals(true, restored[Settings.PREF_BACKSPACE_SPEED_UP])
        // a backup made since has its values as they are, and a keyboard on another Layout gets none
        val since = backup + (helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true)
        assertEquals(null, helium314.keyboard.settings.preferences.restoredSettings(ctx, since, keyboard)[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        val curmudgeon = backup + (idKey(1, sel) to ctx.getString(R.string.layout_preset_curmudgeon))
        assertEquals(null, helium314.keyboard.settings.preferences.restoredSettings(ctx, curmudgeon, keyboard)[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
    }

    // the same through the restore itself, onto a phone with separate settings off (the restore turns them on), down
    // to what the Layouts row shows
    @Test fun `a 0_3_008 HeliBoard keyboard restored on its own reads HeliBoard's values and its Layouts row is not unsaved`() {
        val keyboard = SettingsSubtype(Locale.US, "").withId(1)
        val heliName = ctx.getString(R.string.layout_preset_heliboard)
        val zip = java.io.File(ctx.cacheDir, "backup-3008.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("preferences.json"))
            // what 0.3.008 stored for it: the HeliBoard Layout's named keys (the five later-pinned ones not at all)
            // (its own set: owner 2026-10-11, no separate common set; a backup's common set is ignored)
            // (its set by its id k1)
            val stored: Map<String?, Any?> = (heli().values - heliBoardPins().keys).mapKeys { idKey(1, it.key) } + mapOf(idKey(1, sel) to heliName,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, Settings.PREF_VERSION_CODE to 3008,
                Settings.PREF_ENABLED_SUBTYPES to keyboard.toPref())
            helium314.keyboard.settings.preferences.settingsToJsonStream(stored, z)
            z.closeEntry()
        }
        assertFalse(KeyboardProfiles.isSeparate(real))
        helium314.keyboard.settings.preferences.restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true)
        // (a new keyboard on the phone: it keeps the backup's id)
        assertEquals(300, real.all[idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT)], "${real.all}")
        // (owner 2026-10-11: R7, a restore never turns separate settings on: its own set shows once they're on)
        assertFalse(KeyboardProfiles.isSeparate(real))
        KeyboardProfiles.enable(real)
        // (outside an activity the view reads the keyboard in use: this one)
        real.edit { putString(Settings.PREF_SELECTED_SUBTYPE, keyboard.toPref()) }
        KeyboardProfiles.refreshImeId(real)
        try {
            assertEquals(300, ctx.prefs().getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, -1))
            val now = ctx.prefs().all
            assertFalse(LayoutPresets.isTweaked(ctx, keyboard, heli()), "the Layouts row would say unsaved: " +
                heli().values.filter { (k, v) -> !helium314.keyboard.settings.KnownDefaults.same(k, now[k], v) }.keys)
        } finally { real.edit().clear().commit(); KeyboardProfiles.refreshImeId(real); zip.delete() }
    }

    // review 2026-10-07: a backup from before 0.3.008 (defaults written down, stripped first) and the old Layout name
    @Test fun `a keyboard from a backup older than 0_3_008, or on HeliBoard Extra, gets HeliBoard's values`() {
        val keyboard = SettingsSubtype(Locale.US, "").withId(1)
        // (its own set in the backup: owner 2026-10-11, no separate common set; a backup's common set is ignored; its
        // set by id, as a backup is read)
        val old = mapOf<String, Any?>(idKey(1, sel) to ctx.getString(R.string.layout_preset_heliboard), idKey(1, Settings.PREF_MORE_POPUP_KEYS) to "all")
        val restored = helium314.keyboard.settings.preferences.restoredSettings(ctx, old, keyboard)
        assertNull(restored[Settings.PREF_MORE_POPUP_KEYS]) // a default written down: left out
        for ((key, value) in heliBoardPins()) assertEquals(value, restored[key], key)
        val extra = helium314.keyboard.settings.preferences.restoredSettings(ctx,
            mapOf(helium314.keyboard.latin.PICKED_ONLY_DONE to true, idKey(1, sel) to "HeliBoard Extra"), keyboard)

        assertEquals(ctx.getString(R.string.layout_preset_heliboard_extra), extra[sel])
        assertEquals(300, extra[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
    }
}

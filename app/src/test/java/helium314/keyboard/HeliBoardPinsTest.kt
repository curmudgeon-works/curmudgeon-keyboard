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
 *  Layout's (re-review 2026-10-07, MEDIUM). */
@RunWith(RobolectricTestRunner::class)
class HeliBoardPinsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val sel = LayoutPresets.PREF_SELECTED

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun heli() = LayoutPresets.builtIn(ctx).single { it.name == ctx.getString(R.string.layout_preset_heliboard) }

    @Test fun `a keyboard that picked HeliBoard in 0_3_008 stays HeliBoard's`() {
        // what 0.3.008's HeliBoard Layout stored: its named keys, the five later-pinned ones not at all
        LayoutPresets.applySettings(ctx, heli().values - heliBoardPins().keys)
        real.edit { putString(sel, ctx.getString(R.string.layout_preset_heliboard)) }
        heliBoardLayoutPins(ctx, real, freshInstall = false)
        for ((key, value) in heliBoardPins()) assertEquals(value, real.all[key], key)
        assertFalse(LayoutPresets.isTweaked(ctx, SettingsSubtype(Locale.US, ""), heli()), "the Layouts row would say unsaved")
    }

    @Test fun `a keyboard's own set on HeliBoard extra, under its old name, is pinned and keeps its choice`() {
        real.edit {
            putString("keyboard_profile_ids", """{"en_US:":2,"de_DE:":3}""")
            putString("p2/$sel", "HeliBoard Extra") // 0.3.008's name
            putInt("p2/${Settings.PREF_KEY_LONGPRESS_TIMEOUT}", 450) // its own pick stays
            putString("p3/$sel", "Mine") // another Layout: untouched
        }
        heliBoardLayoutPins(ctx, real, freshInstall = false)
        assertEquals(ctx.getString(R.string.layout_preset_heliboard_extra), real.getString("p2/$sel", null))
        assertEquals(450, real.getInt("p2/${Settings.PREF_KEY_LONGPRESS_TIMEOUT}", 0))
        assertEquals(false, real.all["p2/${Settings.PREF_BACKSPACE_SPEED_UP}"])
        assertEquals("", real.all["p2/${Settings.PREF_SYMBOL_POPUP_MAP}"])
        assertNull(real.all["p3/${Settings.PREF_BACKSPACE_SPEED_UP}"])
        assertNull(real.all[Settings.PREF_BACKSPACE_SPEED_UP], "the shared set didn't pick HeliBoard")
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
        // the restored file: Layout & Typing per keyboard, keyboard 2 on HeliBoard, the step not run yet
        real.edit().clear().putInt(Settings.PREF_VERSION_CODE, 3008).putString("keyboard_profile_ids", """{"en_US:":2}""")
            .putString("p2/$sel", ctx.getString(R.string.layout_preset_heliboard)).commit()
        helium314.keyboard.settings.preferences.restoreFollowUp(ctx)
        assertEquals(false, real.all["p2/${Settings.PREF_BACKSPACE_SPEED_UP}"], "keyboard 2 missed its pins: ${real.all.keys}")
    }

    // 2026-10-07: "On restoring a single keyboard from 3008 backup -- the backup should apply backup's values."
    @Test fun `a keyboard restored on its own from a 0_3_008 backup gets HeliBoard's values`() {
        val keyboard = SettingsSubtype(java.util.Locale.US, "")
        val heliName = ctx.getString(R.string.layout_preset_heliboard)
        val backup = mapOf<String, Any?>(helium314.keyboard.latin.PICKED_ONLY_DONE to true, sel to heliName,
            Settings.PREF_BACKSPACE_SPEED_UP to true) // (a pick of its own stays)
        val restored = helium314.keyboard.settings.preferences.restoredSettings(ctx, backup, keyboard)
        assertEquals(300, restored[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        assertEquals(helium314.keyboard.latin.settings.Defaults.BACKSPACE_DELAY_FOLLOWS, restored[Settings.PREF_BACKSPACE_LONGPRESS_DELAY])
        assertEquals(true, restored[Settings.PREF_BACKSPACE_SPEED_UP])
        // a backup made since has its values as they are, and a keyboard on another Layout gets none
        val since = backup + (helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true)
        assertEquals(null, helium314.keyboard.settings.preferences.restoredSettings(ctx, since, keyboard)[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        val curmudgeon = backup + (sel to ctx.getString(R.string.layout_preset_curmudgeon))
        assertEquals(null, helium314.keyboard.settings.preferences.restoredSettings(ctx, curmudgeon, keyboard)[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardProfiles.Group
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.newCommonMain
import helium314.keyboard.settings.screens.switchOffKeyboard
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Switching off the first keyboard while its settings are the common ones makes the next keyboard the main, handled as
 * a reorder (refresh, caches, the line). The device: English (US) (k1, first), Hindi (k2), German (k3).
 */
@RunWith(RobolectricTestRunner::class)
class SwitchOffMainTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private lateinit var en: SettingsSubtype
    private lateinit var hi: SettingsSubtype
    private lateinit var de: SettingsSubtype

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        en = available("en-US").withId(1); hi = available("hi").withId(2); de = available("de").withId(3)
        useKeyboards(ctx, real, en, hi, de)
    }

    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun separate(on: Boolean, vararg shared: Group) {
        val e = real.edit().putBoolean("separate_settings_per_keyboard", on)
        for (g in Group.entries) e.putBoolean(g.prefKey, g in shared)
        e.commit()
        KeyboardProfiles.loadGroups(real)
    }

    // fail before (90ae541a0): the Languages screen's switch only took the keyboard out, nothing handled the new main
    @Test fun `switching off the first keyboard with common settings makes the next one the main, handled`() {
        separate(false)
        assertTrue(switchOffKeyboard(ctx, en.toAdditionalSubtype()))
        assertEquals(hi, KeyboardProfiles.mainKeyboard(real))
        assertNull(newCommonMain(real, hi), "handled once: no change left over")
    }

    @Test fun `a shared menu is enough`() {
        separate(true, Group.APPEARANCE)
        assertTrue(switchOffKeyboard(ctx, en.toAdditionalSubtype()))
        assertEquals(hi, KeyboardProfiles.mainKeyboard(real))
    }

    @Test fun `no change for another keyboard, or with nothing common`() {
        separate(false)
        assertFalse(switchOffKeyboard(ctx, hi.toAdditionalSubtype()), "not the first: the main stays")
        assertEquals(en, KeyboardProfiles.mainKeyboard(real))
        separate(true)
        assertFalse(switchOffKeyboard(ctx, en.toAdditionalSubtype()), "nothing shared: no keyboard reads the main's settings")
        assertEquals(de, KeyboardProfiles.mainKeyboard(real))
    }
}

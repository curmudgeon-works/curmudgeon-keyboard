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
import helium314.keyboard.settings.screens.groupTitle
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.mainChangedMessage
import helium314.keyboard.settings.screens.onOrderChanged
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The line shown when another keyboard becomes the first one (the main) while its settings are common: separate
 * settings off, all settings are common; on, only the shared menus are named, in the menus' order (owner 2026-10-12).
 * The device: English (US) (k1, first) and Hindi (k2).
 */
@RunWith(RobolectricTestRunner::class)
class MainChangedMessageTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private lateinit var en: SettingsSubtype
    private lateinit var hi: SettingsSubtype
    private val appearance get() = ctx.getString(groupTitle(Group.APPEARANCE))
    private val swipe get() = ctx.getString(groupTitle(Group.SWIPE))
    private val hiName get() = keyboardName(hi, ctx)

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        en = available("en-US").withId(1); hi = available("hi").withId(2)
        useKeyboards(ctx, real, en, hi)
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

    @Test fun `separate settings off, all settings are common`() {
        separate(false)
        assertEquals("Common settings now come from $hiName", mainChangedMessage(ctx, hi))
    }

    @Test fun `separate settings on, the shared menus are named in the menus' order`() {
        separate(true, Group.SWIPE, Group.APPEARANCE)
        assertEquals("$appearance, $swipe now come from $hiName", mainChangedMessage(ctx, hi))
        separate(true, Group.APPEARANCE)
        assertEquals("$appearance now comes from $hiName", mainChangedMessage(ctx, hi))
    }

    // fail before (90ae541a0): the toast said "Common settings now come from Hindi" with separate settings on too
    @Test fun `a reorder with some menus shared shows the shared menus`() {
        separate(true, Group.APPEARANCE, Group.SWIPE)
        val mainBefore = KeyboardProfiles.mainKeyboard(real)
        useKeyboards(ctx, real, hi, en)
        onOrderChanged(ctx, mainBefore)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("$appearance, $swipe now come from $hiName", ShadowToast.getTextOfLatestToast())
    }
}

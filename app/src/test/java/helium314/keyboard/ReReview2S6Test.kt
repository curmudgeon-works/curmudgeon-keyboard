// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.preferences.fontDeleteWarning
import helium314.keyboard.settings.preferences.fontUsers
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals

/** Review 2026-10-11 S6: a keyboard switched off on the Languages screen keeps its own set and its id, and a font delete
 *  sends its chosen font to the default too: the warning names it, "<name> (switched off)". A set no keyboard maps to
 *  is not named. Keyboard A ("Work", enabled) = id k1, B (German, switched off) = id k2, set k9 = nobody's (owner
 *  2026-10-11: keyboards have unique ids). */
@RunWith(RobolectricTestRunner::class)
class ReReview2S6Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withId(1)
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(2)
    private val font get() = File(FontLibrary.dir(ctx), "Mine.ttf")
    private val value = FontLibrary.PREFIX + "Mine.ttf"

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b))) // (B switched off: in the added list only)
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(idKey(1, Settings.PREF_KEY_FONT), FontLibrary.PREFIX + "Other.ttf")
            putString(idKey(2, Settings.PREF_SUGGESTION_FONT), value)
            putString(idKey(9, Settings.PREF_KEY_FONT), value)
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        FontLibrary.dir(ctx).mkdirs(); font.writeText("not really a font")
    }
    @After fun tearDown() {
        AppearanceDraft.close(); FontLibrary.dir(ctx).deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun offName() = ctx.getString(R.string.keyboard_switched_off, keyboardName(b, ctx))

    @Test fun `a switched-off keyboard whose own set chose the font is named, a set nobody maps to is not`() {
        assertEquals(listOf(offName()), fontUsers(ctx, "Mine.ttf"))
    }

    @Test fun `with one set of settings a switched-off keyboard's kept own set is named too, like an enabled one's`() {
        // (the delete resets every set, hidden ones too; enabled keyboards' hidden sets are named already)
        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        // (the line on the common settings under the list: owner 2026-10-18, review SP10; no "(hidden)" mark)
        assertEquals(listOf(offName()), fontUsers(ctx, "Mine.ttf"))
        assertEquals("Settings are common to all your keyboards (App settings › Per keyboard), so every keyboard follows Work, " +
            "first in your list. The keyboards below get this change in their own settings, which they use once you turn on Per keyboard.",
            fontDeleteWarning(ctx, "Mine.ttf").second)
    }

    // (owner 2026-10-18, review SP10: "Only show those that actually go back to default (as they would with separate
    // settings)". Fail before: B, switched on again with nothing of its own, was named as reading A's font, and no line)
    @Test fun `with one set of settings - only the own sets are named, the first keyboard unmarked, and the line`() {
        real.edit {
            putBoolean("separate_settings_per_keyboard", false)
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(idKey(1, Settings.PREF_KEY_FONT), value) // A's, the common one
            remove(idKey(2, Settings.PREF_SUGGESTION_FONT)) // B: nothing of its own
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(listOf("Work"), fontUsers(ctx, "Mine.ttf"))
        assertEquals("Settings are common to all your keyboards (App settings › Per keyboard), so every keyboard follows Work, " +
            "first in your list.", fontDeleteWarning(ctx, "Mine.ttf").second)
        real.edit { putBoolean("separate_settings_per_keyboard", true) }
        assertEquals(null, fontDeleteWarning(ctx, "Mine.ttf").second) // (separate settings: no line)
    }

    @Test fun `switched on again it is named once, without the mark`() {
        real.edit { putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b))) }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(listOf(keyboardName(b, ctx)), fontUsers(ctx, "Mine.ttf"))
    }
}

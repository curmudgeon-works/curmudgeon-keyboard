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
 *  is not named. Keyboard A ("Work", enabled) = set 1, B (German, switched off) = set 2, set 9 = nobody's. */
@RunWith(RobolectricTestRunner::class)
class ReReview2S6Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work")
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private val font get() = File(FontLibrary.dir(ctx), "Mine.ttf")
    private val value = FontLibrary.PREFIX + "Mine.ttf"

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        assertEquals(1, KeyboardProfiles.idFor(real, a))
        assertEquals(2, KeyboardProfiles.idFor(real, b))
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString("p1/${Settings.PREF_KEY_FONT}", FontLibrary.PREFIX + "Other.ttf")
            putString("p2/${Settings.PREF_SUGGESTION_FONT}", value)
            putString("p9/${Settings.PREF_KEY_FONT}", value)
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

    private fun offName() = ctx.getString(R.string.font_delete_switched_off, keyboardName(b, ctx))

    @Test fun `a switched-off keyboard whose own set chose the font is named, a set nobody maps to is not`() {
        assertEquals(listOf(offName()), fontUsers(ctx, "Mine.ttf"))
    }

    @Test fun `with one set of settings a switched-off keyboard's kept own set is named too, like an enabled one's`() {
        // (the delete resets every set, hidden ones too; enabled keyboards' hidden sets are named already)
        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        assertEquals(listOf(offName()), fontUsers(ctx, "Mine.ttf"))
    }

    @Test fun `switched on again it is named once, without the mark`() {
        real.edit { putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b))) }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(listOf(keyboardName(b, ctx)), fontUsers(ctx, "Mine.ttf"))
    }
}

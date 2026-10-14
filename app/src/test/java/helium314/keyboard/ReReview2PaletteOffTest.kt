// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.userNames
import helium314.keyboard.settings.screens.paletteUsers
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Owner 2026-10-11 ("should fix this fo sho"): a keyboard switched off on the Languages screen keeps its own set, and
 *  a palette saved with new colours reached it unasked; the update question names it, "<name> (switched off)", and
 *  left unticked it keeps the old colours as "Custom N". Keyboard A ("Work", enabled, edited) = set 1, B (German,
 *  switched off) = set 2, its own set chose "Warm". */
@RunWith(RobolectricTestRunner::class)
class ReReview2PaletteOffTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work")
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private val warm = Settings.PREF_USER_COLORS_PREFIX + "Warm"
    private val bChoice = KeyboardProfiles.ownKey(2, Settings.PREF_THEME_COLORS)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        assertEquals(1, KeyboardProfiles.idFor(real, a))
        assertEquals(2, KeyboardProfiles.idFor(real, b))
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
            putString(warm, "new") // (saved with new colours; the editor opened on "old")
            putString(KeyboardProfiles.ownKey(1, Settings.PREF_THEME_COLORS), "Warm")
            putString(bChoice, "Warm")
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun users() = paletteUsers(ctx, ctx.prefs(), "Warm", "Warm").first
    private fun offName() = "${keyboardName(b, ctx)} (switched off)"
    private fun keepOld(users: List<SettingsSubtype>) = KeyboardTheme.keepOldPalette(real, "Warm", "Warm", mapOf(warm to "old"), users, emptyList())

    @Test fun `the update question names a switched-off keyboard whose own set chose the palette`() {
        assertEquals(listOf(offName()), userNames(ctx, users()))
    }

    @Test fun `left unticked, the switched-off keyboard keeps the old colours as a kept copy`() {
        keepOld(users())
        val kept = real.getString(bChoice, null)!!
        assertTrue(KeyboardTheme.isHiddenPalette(kept), kept)
        assertEquals("old", real.getString(KeyboardProfiles.ownKey(2, Settings.PREF_USER_COLORS_PREFIX + kept), null)) // (in its own set: owner 2026-10-11, no separate common set)
        assertEquals("Warm", real.getString(KeyboardProfiles.ownKey(1, Settings.PREF_THEME_COLORS), null)) // (the edited one: new)
    }

    @Test fun `with one set of settings it is named too, and its own choice keeps the old colours`() {
        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        assertEquals(listOf(offName()), userNames(ctx, users()))
        keepOld(users())
        assertTrue(KeyboardTheme.isHiddenPalette(real.getString(bChoice, null)!!))
    }

    @Test fun `not named with Appearance shared, nor with its own copy of the palette, nor without a choice of its own`() {
        real.edit { putBoolean(KeyboardProfiles.Group.APPEARANCE.prefKey, true) }; KeyboardProfiles.loadGroups(real)
        assertEquals(emptyList(), users())
        real.edit { putBoolean(KeyboardProfiles.Group.APPEARANCE.prefKey, false) }; KeyboardProfiles.loadGroups(real)
        real.edit { putString(KeyboardProfiles.ownKey(2, warm), "its own") }
        assertEquals(emptyList(), users())
        real.edit { remove(KeyboardProfiles.ownKey(2, warm)).remove(bChoice).putString(Settings.PREF_THEME_COLORS, "Warm") }
        assertEquals(emptyList(), users()) // (only following the shared choice: its own set chose nothing)
    }
}

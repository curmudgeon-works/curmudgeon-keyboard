// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.Palettes
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

/** Owner 2026-10-11 ("should fix this fo sho"): a keyboard switched off on the Languages screen keeps its own set, and
 *  a palette saved with new colors reached it unasked; the update question names it, "<name> (switched off)", and
 *  keeping the old colors it gets them as "Warm 2" (owner 2026-10-11: palettes are one app-wide list; was "Custom N").
 *  Keyboard A ("Work", enabled, edited) = id k1, B (German, switched off) = id k2 (owner 2026-10-11: keyboards have
 *  unique ids), its own set chose "Warm". */
@RunWith(RobolectricTestRunner::class)
class ReReview2PaletteOffTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withId(1)
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(2)
    private val warm = Settings.PREF_USER_COLORS_PREFIX + "Warm"
    private val bChoice = idKey(2, Settings.PREF_THEME_COLORS)

    @Before fun setUp() {
        helium314.keyboard.latin.settings.Settings.getInstance().forgetLoadedValues() // (placeholder colors: no reload, see there)
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b))) // (B switched off: in the added list only)
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
            putString(warm, "new") // (saved with new colors; the editor opened on "old")
            putString(idKey(1, Settings.PREF_THEME_COLORS), "Warm")
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
    private fun keepOld(users: List<SettingsSubtype>) = Palettes.afterEdit(real, "Warm", mapOf(Settings.PREF_USER_COLORS_PREFIX to "old"),
        users.associateWith { Palettes.Choice.KEEP_OLD }, emptyMap())

    @Test fun `the update question names a switched-off keyboard whose own set chose the palette`() {
        assertEquals(listOf(offName()), userNames(ctx, users()))
    }

    @Test fun `keeping the old colors, the switched-off keyboard gets them as Warm 2 on the list`() {
        keepOld(users())
        assertEquals("Warm 2", real.getString(bChoice, null))
        assertEquals("old", real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm 2", null)) // (on the app-wide list)
        assertEquals("new", real.getString(warm, null))
        assertEquals("Warm", real.getString(idKey(1, Settings.PREF_THEME_COLORS), null)) // (the edited one: new)
    }

    @Test fun `with one set of settings it is named too, and its own choice keeps the old colors`() {
        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        assertEquals(listOf(offName()), userNames(ctx, users()))
        keepOld(users())
        assertEquals("Warm 2", real.getString(bChoice, null))
    }

    // (owner 2026-10-11: palettes are one app-wide list, no keyboard has a copy of its own: the "own copy" case is gone)
    // (owner 2026-10-12: "our saving policies should not be affected by the common/separate toggle at all.": with
    // Appearance shared its own set still chose it, so it is named; until then it wasn't)
    @Test fun `named with Appearance shared too, not without a choice of its own`() {
        real.edit { putBoolean(KeyboardProfiles.Group.APPEARANCE.prefKey, true) }; KeyboardProfiles.loadGroups(real)
        assertEquals(listOf(offName()), userNames(ctx, users()))
        real.edit { putBoolean(KeyboardProfiles.Group.APPEARANCE.prefKey, false) }; KeyboardProfiles.loadGroups(real)
        real.edit { remove(bChoice).putString(Settings.PREF_THEME_COLORS, "Warm") }
        assertEquals(emptyList(), users()) // (only following the shared choice: its own set chose nothing)
    }
}

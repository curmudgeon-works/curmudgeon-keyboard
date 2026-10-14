// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.Palettes
import helium314.keyboard.settings.screens.applySetToOther
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.otherUsers
import helium314.keyboard.settings.screens.paletteUsers
import helium314.keyboard.settings.screens.userNames
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Owner 2026-10-12: "our saving policies should not be affected by the common/separate toggle at all." The update
 * question of a saved Layout, theme, popup set or palette asks every other keyboard whose own set names the item, shown
 * or hidden behind the common settings, the same ones with separate settings off, on, and with the menu shared; a
 * ticked one gets the update in its own set, an unticked one keeps the old version there. Until then a shared menu
 * asked none and one set of settings only the switched-off ones.
 * Keyboard A ("Work", enabled, first: its own set is the common one) = k1, B (French, enabled) = k2, C (German,
 * switched off: in the added list only) = k3; each one's own set chose "Mine" (and palette "Warm").
 */
@RunWith(RobolectricTestRunner::class)
class OwnSetUsersTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withId(1)
    // (French: no built-in keyboard for fr-FR, the language's own locale has them; as CentralPalettesTest)
    private val b = SubtypeSettings.getAvailableSubtypeLocales().first { it.language == "fr" }
        .let { SubtypeSettings.getResourceSubtypesForLocale(it).first().toSettingsSubtype() }.withId(2)
    private val c = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(3)
    private val day = Settings.PREF_THEME_COLORS

    private enum class Kind(val key: String, val group: KeyboardProfiles.Group) {
        LAYOUT(LayoutPresets.PREF_SELECTED, KeyboardProfiles.Group.LAYOUT),
        THEME(AppearanceLooks.PREF_SELECTED, KeyboardProfiles.Group.APPEARANCE),
        POPUPS(KeyPopupOverrides.PREF_SELECTED_SET, KeyboardProfiles.Group.LAYOUT),
    }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b, c)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
            putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", "new") // (saved with new colors; the editor opened on "old")
            for (n in 1..3) {
                for (kind in Kind.entries) putString(idKey(n, kind.key), "Mine")
                putString(idKey(n, day), "Warm")
            }
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun separate(on: Boolean) = real.edit { putBoolean("separate_settings_per_keyboard", on) }
    private fun shareMenus() {
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.LAYOUT, true)
        KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.APPEARANCE, true)
    }
    private fun others(kind: Kind, edited: SettingsSubtype?) = userNames(ctx, otherUsers(ctx, kind.key, "Mine", edited, kind.group))
    private fun paletteNames() = userNames(ctx, paletteUsers(ctx, ctx.prefs(), "Warm", "Warm").first)
    private fun offName() = "${keyboardName(c, ctx)} (switched off)"
    private fun bAndC() = listOf(keyboardName(b, ctx), offName())

    @Test fun `separate settings off - a keyboard whose hidden own set names the item is asked`() {
        separate(false)
        // (the common screen edits A's set, the common one; Layout and popups pass the screen's keyboard, A)
        for (kind in Kind.entries) {
            assertEquals(bAndC(), others(kind, null), kind.name)
            assertEquals(bAndC(), others(kind, a), kind.name)
        }
        assertEquals(bAndC(), paletteNames())
    }

    @Test fun `separate settings on with the menu shared - the hidden own sets are asked`() {
        shareMenus()
        for (kind in Kind.entries) assertEquals(bAndC(), others(kind, a), kind.name)
        assertEquals(bAndC(), paletteNames())
    }

    @Test fun `the same keyboards are asked in every mode`() {
        val perKeyboard = Kind.entries.map { others(it, a) } + listOf(paletteNames())
        separate(false)
        assertEquals(perKeyboard, Kind.entries.map { others(it, a) } + listOf(paletteNames()))
        separate(true); shareMenus()
        assertEquals(perKeyboard, Kind.entries.map { others(it, a) } + listOf(paletteNames()))
        assertEquals(List(4) { bAndC() }, perKeyboard)
    }

    @Test fun `menu shared, editing B - the main keyboard's set is the edited one, not asked`() {
        shareMenus()
        real.edit { putString(Settings.PREF_SELECTED_SUBTYPE, b.toPref()) } // (B's screen: its writes go to A's set)
        for (kind in Kind.entries) assertEquals(listOf(offName()), others(kind, b), kind.name)
        assertEquals(listOf(offName()), paletteNames())
    }

    @Test fun `only reading the item through the common settings is no use of its own`() {
        separate(false)
        real.edit { for (kind in Kind.entries) remove(idKey(2, kind.key)); remove(idKey(2, day)) }
        for (kind in Kind.entries) assertEquals(listOf(offName()), others(kind, a), kind.name)
        assertEquals(listOf(offName()), paletteNames())
    }

    @Test fun `ticked with the menu shared - the update lands in the hidden own set, not the common one`() {
        shareMenus()
        // (B, as the question lists it: the apply alone is tested here, the listing above)
        LayoutPresets.applyToOther(ctx, b, LayoutPresets.Preset("Mine", mapOf(Settings.PREF_SOUND_ON to true)))
        AppearanceLooks.applyToOther(ctx, b, AppearanceLooks.Look("Mine", mapOf(Settings.PREF_KEY_TEXT_BOLD to true)))
        applySetToOther(ctx, b, KeyPopupOverrides.UserSet("Mine", "normal", null, emptyMap(), popupOrder = "the order"), false)
        assertEquals(true, real.getBoolean(idKey(2, Settings.PREF_SOUND_ON), false))
        assertEquals(true, real.getBoolean(idKey(2, Settings.PREF_KEY_TEXT_BOLD), false))
        assertEquals("the order", real.getString(idKey(2, Settings.PREF_POPUP_KEYS_ORDER), null))
        // (the common set, A's own, is untouched)
        for (key in listOf(Settings.PREF_SOUND_ON, Settings.PREF_KEY_TEXT_BOLD, Settings.PREF_POPUP_KEYS_ORDER)) {
            assertFalse(real.contains(idKey(1, key)), key); assertFalse(real.contains(key), key)
        }
    }

    @Test fun `unticked with Appearance shared - the hidden own sets keep the old colors as Warm 2`() {
        shareMenus()
        val users = paletteUsers(ctx, ctx.prefs(), "Warm", "Warm").first
        Palettes.afterEdit(real, "Warm", mapOf(Settings.PREF_USER_COLORS_PREFIX to "old"),
            users.associateWith { Palettes.Choice.KEEP_OLD }, emptyMap())
        assertEquals("Warm 2", real.getString(idKey(2, day), null))
        assertEquals("Warm 2", real.getString(idKey(3, day), null))
        assertEquals("old", real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm 2", null))
        assertEquals("Warm", real.getString(idKey(1, day), null)) // (the edited set, the common one: the new colors)
    }

    @Test fun `unticked with one set of settings - the hidden own set keeps the old colors as Warm 2`() {
        separate(false)
        val users = paletteUsers(ctx, ctx.prefs(), "Warm", "Warm").first
        Palettes.afterEdit(real, "Warm", mapOf(Settings.PREF_USER_COLORS_PREFIX to "old"),
            users.associateWith { Palettes.Choice.KEEP_OLD }, emptyMap())
        assertEquals("Warm 2", real.getString(idKey(2, day), null))
        assertEquals("Warm", real.getString(idKey(1, day), null))
    }
}

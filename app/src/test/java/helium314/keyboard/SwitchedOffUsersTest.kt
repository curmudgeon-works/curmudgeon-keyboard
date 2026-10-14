// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.applySetToOther
import helium314.keyboard.settings.screens.deleteKeepersText
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.otherUsers
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
import kotlin.test.assertTrue

/** Owner 2026-10-11 ("Switched off keyboards should be treated as any switched on in all our policies of edit rename
 *  delete restore upgrade etc."): the Update question of a saved Layout, theme or popup set offers a tick for a
 *  switched-off keyboard whose own set chose it, and the delete warning names it, "<name> (switched off)", whether or
 *  not separate settings is on; ticked, its own set gets the update and it stays switched off. Keyboard A ("Work",
 *  enabled, edited) = k1, B (German, switched off: in the added list only) = k2 (owner 2026-10-11: keyboards have
 *  unique ids; no id map). */
@RunWith(RobolectricTestRunner::class)
class SwitchedOffUsersTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withId(1)
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(2)

    private enum class Kind(val key: String, val group: KeyboardProfiles.Group, val plural: Int) {
        LAYOUT(LayoutPresets.PREF_SELECTED, KeyboardProfiles.Group.LAYOUT, R.plurals.delete_keeps_layout),
        THEME(AppearanceLooks.PREF_SELECTED, KeyboardProfiles.Group.APPEARANCE, R.plurals.delete_keeps_theme),
        POPUPS(KeyPopupOverrides.PREF_SELECTED_SET, KeyboardProfiles.Group.LAYOUT, R.plurals.delete_keeps_popup_set),
    }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones)
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
            for (kind in Kind.entries) {
                putString(idKey(1, kind.key), "Mine")
                putString(idKey(2, kind.key), "Mine")
            }
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(kid(1), KeyboardProfiles.idForListed(real, a))
        assertEquals(kid(2), KeyboardProfiles.idForListed(real, b)) // (switched off: one of the phone's all the same)
    }
    @After
 fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun offName() = ctx.getString(R.string.keyboard_switched_off, keyboardName(b, ctx))
    private fun others(kind: Kind) = otherUsers(ctx, kind.key, "Mine", a, kind.group)
    private fun enabled() = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }

    private fun assertAsked(kind: Kind) {
        val others = others(kind)
        assertEquals(listOf(offName()), userNames(ctx, others), kind.name)
        val keepers = deleteKeepersText(ctx, kind.plural, kind.key, "Mine", a)!!
        assertEquals(listOf("Work", offName()), keepers.substringAfter("\n").lines(), kind.name)
    }

    @Test fun `a switched-off keyboard whose own set chose the item is asked on Update and named on delete`() {
        for (kind in Kind.entries) assertAsked(kind)
    }

    @Test fun `with one set of settings it is asked and named too`() {
        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        for (kind in Kind.entries) assertEquals(listOf(offName()), userNames(ctx, others(kind)), kind.name)
        for (kind in Kind.entries) assertTrue(deleteKeepersText(ctx, kind.plural, kind.key, "Mine")!!.contains(offName()), kind.name)
    }

    @Test fun `only following the shared choice it is not asked, nor named twice`() {
        // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones: A's choice)
        real.edit { for (kind in Kind.entries) remove(idKey(2, kind.key)) }
        for (kind in Kind.entries) {
            assertEquals(emptyList(), others(kind), kind.name)
            assertEquals(listOf("Work"), deleteKeepersText(ctx, kind.plural, kind.key, "Mine", a)!!.substringAfter("\n").lines())
        }
    }

    @Test fun `switched on again it is named once, without the mark`() {
        real.edit { putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b))) }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        for (kind in Kind.entries) assertEquals(listOf(keyboardName(b, ctx)), userNames(ctx, others(kind)), kind.name)
    }

    private fun assertUpdated(separate: Boolean) {
        real.edit { putBoolean("separate_settings_per_keyboard", separate) }
        // (asked first: a Layout brings its popup set, so its update takes the popup choice off)
        val (layoutUser, themeUser, popupsUser) = Kind.entries.map { others(it).single() }
        val layout = LayoutPresets.Preset("Mine", mapOf(Settings.PREF_SOUND_ON to true))
        LayoutPresets.applyToOther(ctx, layoutUser, layout)
        assertEquals(true, real.getBoolean(idKey(2, Settings.PREF_SOUND_ON), false))
        val look = AppearanceLooks.Look("Mine", mapOf(Settings.PREF_KEY_TEXT_BOLD to true))
        AppearanceLooks.applyToOther(ctx, themeUser, look)
        assertEquals(true, real.getBoolean(idKey(2, Settings.PREF_KEY_TEXT_BOLD), false))
        val set = KeyPopupOverrides.UserSet("Mine", "normal", null, emptyMap(), popupOrder = "the order")
        applySetToOther(ctx, popupsUser, set, false)
        assertEquals("the order", real.getString(idKey(2, Settings.PREF_POPUP_KEYS_ORDER), null))
        // (the shared set and the edited keyboard's set are untouched, and it stays switched off)
        for (key in listOf(Settings.PREF_SOUND_ON, Settings.PREF_KEY_TEXT_BOLD, Settings.PREF_POPUP_KEYS_ORDER)) {
            assertFalse(real.contains(key), key); assertFalse(real.contains(idKey(1, key)), key)
        }
        assertEquals(listOf(a), enabled())
        assertTrue(enabled().none { it.locale == b.locale })
    }

    @Test fun `ticked on Update its own set gets the change and it stays switched off`() = assertUpdated(true)

    @Test fun `with one set of settings too, its own set gets the change, not the shared one`() = assertUpdated(false)
}

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
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.screens.deleteKeepersText
import helium314.keyboard.settings.screens.keepersText
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.withKeyboardName
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The delete questions of a Layout, theme or popup set name the keyboards that use it (2026-10-08): what each reads now,
 *  or what its own set keeps, hidden or not; one per line, never "all keyboards". Keyboard A (named "Work") = set 1,
 *  B (languages only) = set 2. */
@RunWith(RobolectricTestRunner::class)
class DeleteWarningTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work")
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private val layout = LayoutPresets.PREF_SELECTED

    private fun using(keyboards: List<SettingsSubtype>, key: String, name: String) =
        KeyboardProfiles.keyboardsUsing(real, keyboards, listOf(key)) { _, value -> value == name }

    private fun enable(vararg keyboards: SettingsSubtype) {
        real.edit {
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes((listOf(a) + keyboards).filter { it != b }))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards.toList()))
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        assertEquals(1, KeyboardProfiles.idFor(real, a))
        assertEquals(2, KeyboardProfiles.idFor(real, b))
        real.edit { putBoolean("separate_settings_per_keyboard", true) }
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    @Test fun `separate settings on - each keyboard's own choice, nobody reads the shared one`() {
        real.edit { putString(layout, "Y"); putString("p1/$layout", "X"); putString("p2/$layout", "X") }
        assertEquals(listOf(a, b), using(listOf(a, b), layout, "X"))
        assertEquals(listOf(b, a), using(listOf(b, a), layout, "X")) // the list's order
        assertEquals(emptyList(), using(listOf(a, b), layout, "Y"))
        real.edit { remove("p2/$layout") } // B has nothing of its own: it reads the shared one
        assertEquals(listOf(b), using(listOf(a, b), layout, "Y"))
    }

    @Test fun `separate settings off - every keyboard reads the shared one, and a hidden own set still counts`() {
        real.edit { putBoolean("separate_settings_per_keyboard", false); putString(layout, "Y"); putString("p2/$layout", "X") }
        assertEquals(listOf(a, b), using(listOf(a, b), layout, "Y"))
        assertEquals(listOf(b), using(listOf(a, b), layout, "X"))
    }

    @Test fun `a shared menu - both read the shared value, an own value hidden behind it still counts`() {
        real.edit { putBoolean(KeyboardProfiles.Group.LAYOUT.prefKey, true) }
        KeyboardProfiles.loadGroups(real)
        real.edit { putString(KeyPopupOverrides.PREF_SELECTED_SET, "S"); putString("p1/${KeyPopupOverrides.PREF_SELECTED_SET}", "T") }
        assertEquals(listOf(a, b), using(listOf(a, b), KeyPopupOverrides.PREF_SELECTED_SET, "S"))
        assertEquals(listOf(a), using(listOf(a, b), KeyPopupOverrides.PREF_SELECTED_SET, "T"))
    }

    @Test fun `a mark at the default - the keyboard reads nothing chosen, not the shared one`() {
        val key = AppearanceLooks.PREF_SELECTED
        real.edit { putString(key, "X"); putBoolean("p1/~$key", true) }
        assertEquals(listOf(b), using(listOf(a, b), key, "X"))
        real.edit { putBoolean("separate_settings_per_keyboard", false) } // the mark hidden: A reads the shared one
        assertEquals(listOf(a, b), using(listOf(a, b), key, "X"))
    }

    @Test fun `no ids are made`() {
        val c = SettingsSubtype(Locale.FRANCE, "")
        val ids = real.getString("keyboard_profile_ids", null)
        real.edit { putString(layout, "X") }
        assertEquals(listOf(c), using(listOf(c), layout, "X"))
        assertEquals(ids, real.getString("keyboard_profile_ids", null))
    }

    // review A4: while a Layout is previewed the edited keyboard has the previewed identity; an old entry for that
    // identity in the ids map names another set, so the keyboard was left out
    @Test fun `a previewed keyboard with an old ids entry is still named, through the set being edited`() {
        val previewed = a.withLayout(helium314.keyboard.latin.utils.LayoutType.MAIN, "dvorak")
        assertEquals(3, KeyboardProfiles.idFor(real, previewed)) // the old entry
        enable(previewed, b)
        KeyboardProfiles.editingId = 1
        try {
            real.edit { putString("p1/$layout", "X") }
            assertEquals("This keyboard keeps its settings, shown as unsaved:\nWork",
                deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "X", edited = previewed))
            assertNull(deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "X")) // no keyboard edited: the map
        } finally { KeyboardProfiles.editingId = KeyboardProfiles.SHARED }
    }

    @Test fun `the text names each keyboard - its name, or its languages`() {
        val bName = keyboardName(b, ctx)
        assertEquals("Work", keyboardName(a, ctx))
        assertEquals(true, bName.contains("German"), bName)
        assertNull(keepersText(ctx, R.plurals.delete_keeps_layout, emptyList()))
        assertEquals("This keyboard keeps its settings, shown as unsaved:\nWork",
            keepersText(ctx, R.plurals.delete_keeps_layout, listOf(a)))
        assertEquals("These keyboards keep its colors and look, shown as unsaved:\nWork\n$bName",
            keepersText(ctx, R.plurals.delete_keeps_theme, listOf(a, b)))
    }

    @Test fun `the dialog text lists the enabled keyboards using it, in the Keyboards screen's order`() {
        enable(b, a)
        val bName = keyboardName(b, ctx)
        real.edit { putString("p1/$layout", "X"); putString("p2/$layout", "X"); putString(layout, "Y") }
        assertEquals("These keyboards keep its settings, shown as unsaved:\n$bName\nWork",
            deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "X"))
        assertNull(deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "Y"))
        // separate settings off: every enabled keyboard reads the shared one, each named
        real.edit { putBoolean("separate_settings_per_keyboard", false) }
        assertEquals("These keyboards keep its settings, shown as unsaved:\n$bName\nWork",
            deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "Y"))
        val set = KeyPopupOverrides.PREF_SELECTED_SET
        real.edit { putString("p1/$set", "S") } // hidden
        assertEquals("This keyboard keeps its popups, shown as Custom:\nWork",
            deleteKeepersText(ctx, R.plurals.delete_keeps_popup_set, set, "S"))
    }
}

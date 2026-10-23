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

/** The delete questions of a Layout, theme or popup set name the keyboards that use it (2026-10-08): what each one's
 *  own set keeps, hidden or not, as separate settings would list them (owner 2026-10-18, review SP10: "Only show those
 *  that actually go back to default (as they would with separate settings)"; until then also the ones reading it only
 *  through the common settings), with a line on the common settings when they're common; one per line, never "all
 *  keyboards". Keyboard A (named "Work") = id k1, B (languages only) = id k2 (owner 2026-10-11: keyboards have unique
 *  ids). */
@RunWith(RobolectricTestRunner::class)
class DeleteWarningTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withId(1)
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(2)
    private val layout = LayoutPresets.PREF_SELECTED

    private fun using(keyboards: List<SettingsSubtype>, key: String, name: String) =
        KeyboardProfiles.keyboardsUsing(real, keyboards, listOf(key)) { _, value -> value == name }

    private fun enable(vararg keyboards: SettingsSubtype) = storeKeyboards(ctx, real, keyboards.toList())

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        enable(a, b) // (A first: its set is the common one with one set for all or a shared menu)
        real.edit { putBoolean("separate_settings_per_keyboard", true) }
    }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones: A's, set 1. A
    // plain value is no one's)
    @Test fun `separate settings on - each keyboard's own choice, nobody reads the shared one`() {
        real.edit { putString(layout, "Y"); putString(idKey(1, layout), "X"); putString(idKey(2, layout), "X") }
        assertEquals(listOf(a, b), using(listOf(a, b), layout, "X"))
        assertEquals(listOf(b, a), using(listOf(b, a), layout, "X")) // the list's order
        assertEquals(emptyList(), using(listOf(a, b), layout, "Y"))
        real.edit { remove(idKey(2, layout)) } // B has nothing of its own: nothing chosen, not A's
        assertEquals(listOf(a), using(listOf(a, b), layout, "X"))
        assertEquals(emptyList(), using(listOf(a, b), layout, "Y"))
    }

    // (owner 2026-10-18, SP10. Fail before: B, reading A's choice through the common settings, was named for Y too)
    @Test fun `separate settings off - only the keyboards whose own set chose it, a hidden own set counts`() {
        real.edit { putBoolean("separate_settings_per_keyboard", false); putString(idKey(1, layout), "Y"); putString(idKey(2, layout), "X") }
        assertEquals(listOf(a), using(listOf(a, b), layout, "Y"))
        assertEquals(listOf(b), using(listOf(a, b), layout, "X"))
    }

    @Test fun `a shared menu - only the keyboards whose own set chose it, an own value hidden behind it counts`() {
        real.edit { putBoolean(KeyboardProfiles.Group.LAYOUT.prefKey, true) }
        KeyboardProfiles.loadGroups(real)
        real.edit { putString(idKey(1, KeyPopupOverrides.PREF_SELECTED_SET), "S"); putString(idKey(2, KeyPopupOverrides.PREF_SELECTED_SET), "T") }
        assertEquals(listOf(a), using(listOf(a, b), KeyPopupOverrides.PREF_SELECTED_SET, "S"))
        assertEquals(listOf(b), using(listOf(a, b), KeyPopupOverrides.PREF_SELECTED_SET, "T"))
    }

    // (owner 2026-10-11: no default marks; nothing stored = the default)
    @Test fun `nothing stored - the keyboard chose nothing, in either mode`() {
        val key = AppearanceLooks.PREF_SELECTED
        real.edit { putString(idKey(1, key), "X") }
        assertEquals(listOf(a), using(listOf(a, b), key, "X"))
        real.edit { putBoolean("separate_settings_per_keyboard", false) } // B reads A's: not its own choice (SP10)
        assertEquals(listOf(a), using(listOf(a, b), key, "X"))
    }

    // (owner 2026-10-11: keyboards have unique ids; the old id map is gone: a lookup writes nothing at all)
    @Test fun `no ids are made`() {
        val c = SettingsSubtype(Locale.FRANCE, "")
        real.edit { putString(idKey(1, layout), "X") }
        val before = real.all.toMap()
        assertEquals(emptyList(), using(listOf(c), layout, "X")) // (no id, no set of its own: none chosen, SP10)
        assertEquals(before, real.all.toMap())
        assertNull(c.keyboardId)
    }

    // review A4: while a Layout is previewed the edited keyboard has the previewed definition; an old ids-map entry for
    // it named another set, so the keyboard was left out. (owner 2026-10-11: keyboards have unique ids; the previewed
    // definition keeps A's id, so it is named with or without the edited keyboard)
    @Test fun `a previewed keyboard with an old ids entry is still named, through the set being edited`() {
        val previewed = a.withLayout(helium314.keyboard.latin.utils.LayoutType.MAIN, "dvorak")
        assertEquals(kid(1), previewed.keyboardId)
        enable(previewed, b)
        val screenOfA = helium314.keyboard.latin.settings.KeyboardScopeContext(ctx, kid(1)) // (the screen editing A's set)
        real.edit { putString(idKey(1, layout), "X") }
        assertEquals("This keyboard keeps its settings, shown as Custom. Nothing goes back to the default:\nWork",
            deleteKeepersText(screenOfA, R.plurals.delete_keeps_layout, layout, "X", edited = previewed))
        assertEquals("This keyboard keeps its settings, shown as Custom. Nothing goes back to the default:\nWork",
            deleteKeepersText(screenOfA, R.plurals.delete_keeps_layout, layout, "X")) // no keyboard edited: by its id
    }

    @Test fun `the text names each keyboard - its name, or its languages`() {
        val bName = keyboardName(b, ctx)
        assertEquals("Work", keyboardName(a, ctx))
        assertEquals(true, bName.contains("German"), bName)
        assertNull(keepersText(ctx, R.plurals.delete_keeps_layout, emptyList()))
        assertEquals("This keyboard keeps its settings, shown as Custom. Nothing goes back to the default:\nWork",
            keepersText(ctx, R.plurals.delete_keeps_layout, listOf(a)))
        assertEquals("These keyboards keep its colors and look, shown as Custom. Nothing goes back to the default:\nWork\n$bName",
            keepersText(ctx, R.plurals.delete_keeps_theme, listOf(a, b)))
    }

    @Test fun `the dialog text lists the enabled keyboards using it, in the Keyboards screen's order`() {
        enable(b, a)
        val bName = keyboardName(b, ctx)
        real.edit { putString(idKey(1, layout), "X"); putString(idKey(2, layout), "X"); putString(layout, "Y") }
        assertEquals("These keyboards keep its settings, shown as Custom. Nothing goes back to the default:\n$bName\nWork",
            deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "X"))
        assertNull(deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "Y"))
        // separate settings off: every enabled keyboard reads the first one's (B's); only B's own set chose Y, the line
        // says every keyboard follows B (owner 2026-10-18, SP10)
        real.edit { putBoolean("separate_settings_per_keyboard", false); putString(idKey(2, layout), "Y") }

        assertEquals("This keyboard keeps its settings, shown as Custom. Nothing goes back to the default:\n$bName\n\n" +
            "Settings are common to all your keyboards (App settings › Per keyboard), so every keyboard follows $bName, first in your list.",
            deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "Y"))
        val set = KeyPopupOverrides.PREF_SELECTED_SET
        real.edit { putString(idKey(1, set), "S") } // hidden
        assertEquals("This keyboard keeps its popups, shown as Custom:\nWork\n\n" + hiddenNote(bName),
            deleteKeepersText(ctx, R.plurals.delete_keeps_popup_set, set, "S"))
    }

    private fun hiddenNote(first: String) = "Settings are common to all your keyboards (App settings › Per keyboard), so every " +
        "keyboard follows $first, first in your list. The keyboards below get this change in their own settings, which they use once you turn on Per keyboard."

    // (owner 2026-10-18, SP10: "Only show those that actually go back to default (as they would with separate settings).
    // But should clarify as per usual pattern that keyboards are using common settings of primary." Fail before: B,
    // reading A's theme through the common settings, was named, and nothing said why the others follow A)
    @Test fun `settings common - names the own sets only, no marks, and the line`() {
        val key = AppearanceLooks.PREF_SELECTED
        real.edit { putBoolean("separate_settings_per_keyboard", false); putString(idKey(1, key), "Sunset") }
        assertEquals("This keyboard keeps its colors and look, shown as Custom. Nothing goes back to the default:\nWork\n\n" +
            "Settings are common to all your keyboards (App settings › Per keyboard), so every keyboard follows Work, first in your list.",
            deleteKeepersText(ctx, R.plurals.delete_keeps_theme, key, "Sunset"))
        real.edit { putString(idKey(2, key), "Sunset") } // B's own set, hidden behind A's
        assertEquals("These keyboards keep its colors and look, shown as Custom. Nothing goes back to the default:\nWork\n" +
            "${keyboardName(b, ctx)}\n\n" + hiddenNote("Work"),
            deleteKeepersText(ctx, R.plurals.delete_keeps_theme, key, "Sunset"))
        // separate settings: the same keyboards, no line
        real.edit { putBoolean("separate_settings_per_keyboard", true) }
        assertEquals("These keyboards keep its colors and look, shown as Custom. Nothing goes back to the default:\nWork\n" +
            keyboardName(b, ctx), deleteKeepersText(ctx, R.plurals.delete_keeps_theme, key, "Sunset"))
    }

    @Test fun `a shared menu with separate settings - the line too`() {
        real.edit { putBoolean(KeyboardProfiles.Group.LAYOUT.prefKey, true); putString(idKey(2, KeyPopupOverrides.PREF_SELECTED_SET), "T") }
        KeyboardProfiles.loadGroups(real)
        assertEquals("This keyboard keeps its popups, shown as Custom:\n${keyboardName(b, ctx)}\n\n" + hiddenNote("Work"),
            deleteKeepersText(ctx, R.plurals.delete_keeps_popup_set, KeyPopupOverrides.PREF_SELECTED_SET, "T"))
        assertNull(deleteKeepersText(ctx, R.plurals.delete_keeps_layout, layout, "T")) // (nobody: no line alone)
    }
}

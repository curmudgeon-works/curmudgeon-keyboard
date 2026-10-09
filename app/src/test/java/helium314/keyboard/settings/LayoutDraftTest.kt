// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.realPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A screen opened from Layout & Typing saves for good: Layout & Typing's Discard no longer undoes it. Discard after a
 *  layout change leaves no stray keyboard entry behind. */
@RunWith(RobolectricTestRunner::class)
class LayoutDraftTest {
    @Test fun nestedSaveIsNoLongerAChangeOfTheParent() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val real = ctx.realPrefs()
        real.edit().clear().commit()
        val draft = LayoutDraft.of(ctx, "en-US:")
        real.edit().putString("key_popups", "{\"e\":[\"é\"]}").putBoolean(Settings.PREF_SHOW_NUMBER_ROW, false).commit() // (on is its default: no change)
        assertTrue(draft.changedKeys(ctx).containsAll(setOf("key_popups", Settings.PREF_SHOW_NUMBER_ROW)))
        LayoutDraft.rebaseOpen(ctx, setOf("key_popups")) // the popup editor's tick
        assertFalse("key_popups" in draft.changedKeys(ctx)) // saved for good
        assertTrue(Settings.PREF_SHOW_NUMBER_ROW in draft.changedKeys(ctx)) // the parent's own change still pending
        draft.reject(ctx) // the parent's cross
        assertTrue(real.getString("key_popups", null) == "{\"e\":[\"é\"]}") // kept
        assertFalse(real.contains(Settings.PREF_SHOW_NUMBER_ROW)) // undone
    }

    // review 2026-10-07: Discard after a layout change (separate settings on, the keyboard loaded) put the keyboard list
    // back first and the keyboard in use after: the reload in between gave the changed keyboard a new empty set, which
    // stayed, and the same change made again and kept then opened on that empty set
    @Test fun discardAfterALayoutChangeLeavesNoStrayKeyboardEntry() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val real = ctx.realPrefs()
        real.edit().clear().commit()
        val a = SettingsSubtype(java.util.Locale.US, "")
        val changed = a.withLayout(LayoutType.MAIN, "dvorak")
        try {
            real.edit().putBoolean("separate_settings_per_keyboard", true)
                .putString(Settings.PREF_ENABLED_SUBTYPES, a.toPref()).putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref()).commit()
            val id = KeyboardProfiles.idFor(real, a)
            real.edit().putInt(KeyboardProfiles.prefixedKey(id, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 333).commit() // its own
            Settings.init(ctx) // (the keyboard loaded: its listener reloads on every change)
            Settings.getInstance().loadSettings(ctx, java.util.Locale.US,
                helium314.keyboard.latin.InputAttributes(android.view.inputmethod.EditorInfo(), false, ctx.packageName))
            assertEquals(id, KeyboardProfiles.imeId)

            // a layout chosen on Layout & Typing, the preview on the changed keyboard (RichInputMethodManager.onSubtypeChanged)
            val draft = LayoutDraft.of(ctx, a.toPref())
            SubtypeUtilsAdditional.changeAdditionalSubtype(a, changed, ctx)
            real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, changed.toPref()).commit()
            KeyboardProfiles.refreshImeId(real)
            assertEquals(id, KeyboardProfiles.imeId) // its set went with it
            draft.reject(ctx) // Discard
            val ids = JSONObject(real.getString("keyboard_profile_ids", "{}")!!)
            assertFalse("stray entry: $ids", ids.has(changed.toPref()))
            assertEquals(a.toPref(), real.getString(Settings.PREF_SELECTED_SUBTYPE, null))
            assertEquals(id, KeyboardProfiles.imeId) // the keyboard in use reads its own set again

            // the same change again, kept: the keyboard still has its own settings
            val again = LayoutDraft.of(ctx, a.toPref())
            SubtypeUtilsAdditional.changeAdditionalSubtype(a, changed, ctx)
            again.accept()
            assertEquals(id, KeyboardProfiles.idFor(real, changed))
            assertEquals(333, real.getInt(KeyboardProfiles.prefixedKey(KeyboardProfiles.idFor(real, changed), Settings.PREF_KEY_LONGPRESS_TIMEOUT), -1))
        } finally {
            real.edit().clear().commit()
            KeyboardProfiles.refreshImeId(real)
        }
    }

    // re-review 2026-10-07: the settings values built by the listeners during Discard's edit read the keyboard list
    // before it was reloaded (still with the changed keyboard): a plain built-in keyboard in use wasn't in it, and the
    // values were the first keyboard's (its second languages, popup keys, number row) until the next text field
    @Test fun settingsValuesAfterDiscardAreTheKeyboardInUse() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val real = ctx.realPrefs()
        real.edit().clear().commit()
        val first = SettingsSubtype(java.util.Locale.GERMANY, "")
            .with(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.SECONDARY_LOCALES, "fr")
        val a = helium314.keyboard.latin.utils.SubtypeSettings.getResourceSubtypesForLocale(java.util.Locale.US).first()
            .toSettingsSubtype() // a plain built-in keyboard
        val changed = a.withLayout(LayoutType.MAIN, "dvorak")
        try {
            real.edit().putBoolean("separate_settings_per_keyboard", true)
                .putString(Settings.PREF_ADDITIONAL_SUBTYPES, first.toPref())
                .putString(Settings.PREF_ENABLED_SUBTYPES, helium314.keyboard.latin.utils.SubtypeSettings.createPrefSubtypes(listOf(first, a)))
                .putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref()).commit()
            helium314.keyboard.latin.utils.SubtypeSettings.reloadEnabledSubtypes(ctx)
            Settings.init(ctx)
            Settings.getInstance().loadSettings(ctx, java.util.Locale.US,
                helium314.keyboard.latin.InputAttributes(android.view.inputmethod.EditorInfo(), false, ctx.packageName))
            assertTrue(Settings.getValues().mSecondaryLocales.isEmpty())

            val draft = LayoutDraft.of(ctx, a.toPref())
            SubtypeUtilsAdditional.changeAdditionalSubtype(a, changed, ctx) // (the preview is on it: it's the one in use)
            draft.reject(ctx) // Discard
            assertEquals(a.toPref(), real.getString(Settings.PREF_SELECTED_SUBTYPE, null))
            assertEquals("the first keyboard's second languages", emptyList<java.util.Locale>(), Settings.getValues().mSecondaryLocales)
        } finally {
            real.edit().clear().commit()
            helium314.keyboard.latin.utils.SubtypeSettings.reloadEnabledSubtypes(ctx)
            KeyboardProfiles.refreshImeId(real)
        }
    }
}

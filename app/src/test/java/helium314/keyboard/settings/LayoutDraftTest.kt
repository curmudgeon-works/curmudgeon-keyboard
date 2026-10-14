// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.kid
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.listedById
import helium314.keyboard.storeKeyboards
import helium314.keyboard.withId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Customize popups' Save leaves its change to Layout & Typing's Save or Discard. Discard after a layout change leaves
 *  no stray keyboard entry behind. */
@RunWith(RobolectricTestRunner::class)
class LayoutDraftTest {
    // until 2026-10-11 this asserted the old rule (the popup editor's tick was saved for good: the parent's Discard kept
    // it). Owner 2026-10-11, option 1: a sub-screen's Save only keeps the change and goes back; the parent's Save or
    // Discard decides it (review C2: that rebase also kept a popup set deleted on the parent)
    @Test fun nestedSaveIsStillAChangeOfTheParent() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val real = ctx.realPrefs()
        real.edit().clear().commit()
        val draft = LayoutDraft.of(ctx, "en-US:")
        real.edit().putString("key_popups", "{\"e\":[\"é\"]}").putBoolean(Settings.PREF_SHOW_NUMBER_ROW, false).commit() // (on is its default: no change)
        assertTrue(draft.changedKeys(ctx).containsAll(setOf("key_popups", Settings.PREF_SHOW_NUMBER_ROW)))
        helium314.keyboard.settings.screens.customizePopupsSaved() // the popup editor's tick
        assertTrue(draft.changedKeys(ctx).containsAll(setOf("key_popups", Settings.PREF_SHOW_NUMBER_ROW))) // both pending
        draft.reject(ctx) // the parent's cross
        assertFalse(real.contains("key_popups")) // undone, the popup edit too
        assertFalse(real.contains(Settings.PREF_SHOW_NUMBER_ROW)) // undone
    }

    // review 2026-10-07: Discard after a layout change (separate settings on, the keyboard loaded) put the keyboard list
    // back first and the keyboard in use after: the reload in between gave the changed keyboard a new empty set, which
    // stayed, and the same change made again and kept then opened on that empty set
    // (owner 2026-10-11: keyboards have unique ids) the change keeps the id: no set moves, no other set appears
    @Test fun discardAfterALayoutChangeLeavesNoStrayKeyboardEntry() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val real = ctx.realPrefs()
        real.edit().clear().commit()
        val a = SettingsSubtype(java.util.Locale.US, "").withId(1)
        val changed = a.withLayout(LayoutType.MAIN, "dvorak")
        try {
            real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
            storeKeyboards(ctx, real, listOf(a))
            real.edit().putInt(idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 333).commit() // its own
            Settings.init(ctx) // (the keyboard loaded: its listener reloads on every change)
            Settings.getInstance().loadSettings(ctx, java.util.Locale.US,
                helium314.keyboard.latin.InputAttributes(android.view.inputmethod.EditorInfo(), false, ctx.packageName))
            assertEquals(kid(1), KeyboardProfiles.imeId)

            // a layout chosen on Layout & Typing, the preview on the changed keyboard (RichInputMethodManager.onSubtypeChanged)
            val draft = LayoutDraft.of(ctx, a.toPref())
            SubtypeUtilsAdditional.changeAdditionalSubtype(a, changed, ctx)
            real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, changed.toPref()).commit()
            KeyboardProfiles.refreshImeId(real)
            assertEquals(kid(1), KeyboardProfiles.imeId) // the same keyboard, the same set
            draft.reject(ctx) // Discard
            assertEquals("stray set", setOf(kid(1)), KeyboardProfiles.setIds(real.all))
            assertEquals(a.toPref(), real.getString(Settings.PREF_SELECTED_SUBTYPE, null))
            assertEquals(kid(1), KeyboardProfiles.imeId) // the keyboard in use reads its own set again

            // the same change again, kept: the keyboard still has its own settings
            val again = LayoutDraft.of(ctx, a.toPref())
            SubtypeUtilsAdditional.changeAdditionalSubtype(a, changed, ctx)
            again.accept()
            assertEquals("dvorak", listedById(real, kid(1))?.layoutName(LayoutType.MAIN)) // the same keyboard, changed
            assertEquals(333, real.getInt(idKey(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT), -1))
            assertEquals(setOf(kid(1)), KeyboardProfiles.setIds(real.all))
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
            .with(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.SECONDARY_LOCALES, "fr").withId(1)
        // a built-in keyboard's definition (owner 2026-10-11: keyboards have unique ids; every listed one has an id and
        // is in the added list)
        val a = helium314.keyboard.latin.utils.SubtypeSettings.getResourceSubtypesForLocale(java.util.Locale.US).first()
            .toSettingsSubtype().withId(2)
        val changed = a.withLayout(LayoutType.MAIN, "dvorak")
        try {
            real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
            storeKeyboards(ctx, real, listOf(first, a), selected = a)
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

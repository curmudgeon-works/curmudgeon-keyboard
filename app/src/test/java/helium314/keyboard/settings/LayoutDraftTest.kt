// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A screen opened from Layout & Typing saves for good: Layout & Typing's Discard no longer undoes it. */
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
}

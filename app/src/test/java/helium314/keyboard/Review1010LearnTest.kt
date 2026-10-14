// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Locale

/** The review of 2026-10-08 (0.3.007 to 0.3.010), fixed 2026-10-10: area 1 #4 and #5, area 2 #3. */
@RunWith(RobolectricTestRunner::class)
class Review1010LearnTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    // ---- area 1 #4: Discard after a saved theme was renamed or deleted on the open Appearance screen ----

    @Test fun `Discard after renaming the chosen theme keeps the new name`() {
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Mine") }
        val draft = AppearanceDraft.of(ctx) // the Appearance screen opens
        AppearanceLooks.choiceRenamed(ctx, "Mine", "Work") // Themes -> rename
        assertFalse(draft.hasChanges(ctx), "the rename alone counted as a change of the screen")
        AppearanceDraft.rejectOpen(ctx) // the cross, or leaving the app
        assertEquals("Work", ctx.prefs().getString(AppearanceLooks.PREF_SELECTED, null))
    }

    @Test fun `Discard after deleting the chosen theme doesn't name it again`() {
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Mine") }
        AppearanceDraft.of(ctx)
        AppearanceLooks.choiceRenamed(ctx, "Mine", null) // Themes -> delete
        AppearanceDraft.rejectOpen(ctx)
        assertNull(ctx.prefs().getString(AppearanceLooks.PREF_SELECTED, null))
    }

    @Test fun `Discard still puts back another theme chosen on the screen`() {
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Old") }
        AppearanceDraft.of(ctx)
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Mine") } // chosen on the screen
        AppearanceLooks.choiceRenamed(ctx, "Mine", "Work") // then renamed
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Old", ctx.prefs().getString(AppearanceLooks.PREF_SELECTED, null))
    }

    // ---- area 1 #5: a "defaults only" keyboard marked the one-time flags; separate OFF from it dropped them ----

    private val flags = listOf("learning_swiping_global", "suggestions_refine_moved", "moved_markers_removed")

    private fun separateWithFlags(): SettingsSubtype {
        val keyboard = SettingsSubtype(Locale.US, "")
        real.edit().putBoolean("separate_settings_per_keyboard", true).apply { flags.forEach { putBoolean(it, true) } }.commit()
        return keyboard
    }

    @Test fun `a keyboard with only defaults doesn't mark the one-time flags`() {
        val keyboard = separateWithFlags()
        val id = KeyboardProfiles.idFor(real, keyboard)
        KeyboardProfiles.write(real, id, emptyMap(), markDefaults = true) // Add keyboard -> "defaults only"
        for (flag in flags) assertFalse(real.contains(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + flag)), flag)
        KeyboardProfiles.disable(real, keyboard) // separate settings OFF, shared from that keyboard
        for (flag in flags) assertTrue(real.getBoolean(flag, false), "$flag dropped: its move runs again")
    }

    @Test fun `separate OFF keeps the one-time flags a keyboard marked before the fix`() {
        val keyboard = separateWithFlags()
        val id = KeyboardProfiles.idFor(real, keyboard)
        real.edit().apply { flags.forEach { putBoolean(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + it), true) } }.commit()
        KeyboardProfiles.disable(real, keyboard)
        for (flag in flags) assertTrue(real.getBoolean(flag, false), "$flag dropped: its move runs again")
    }
}

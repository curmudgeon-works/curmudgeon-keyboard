// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.copyKeyboard
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A copied keyboard keeps the source's "Use lists from other keyboards" buttons (review 2026-10-18 SP23c). */
@RunWith(RobolectricTestRunner::class)
class CopyKeyboardButtonsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        LearnedStores.init(ctx)
    }
    @After fun tearDown() {
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
    }

    @Test fun `a copy uses the other keyboards' learned words when its source does, and not their blacklists when it doesn't`() {
        val english = available("en-US").withId(1)
        val hindi = available("hi").withId(2)
        useKeyboards(ctx, real, english, hindi)
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedKeyboards.setUsesOthers(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED, english, true)

        val copy = copyKeyboard(ctx, english)

        assertNotEquals(english.keyboardId, copy.keyboardId)
        assertTrue(LearnedKeyboards.usesOthers(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED, copy))
        assertFalse(LearnedKeyboards.usesOthers(real, LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, copy))
        assertTrue(LearnedKeyboards.usesOthers(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED, english)) // (the source keeps its own)
    }
}

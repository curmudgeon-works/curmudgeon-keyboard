// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.dictionaryUserNames
import helium314.keyboard.settings.dialogs.dictionaryUsers
import helium314.keyboard.settings.screens.keyboardName
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Owner 2026-10-11: switched-off keyboards count as switched-on ones in all saving policies; for added dictionaries
 * "No delete allowed if other keyboards using dict". A keyboard switched off keeps its id (or its pool) and its
 * definition, so a dictionary only it uses stays, and the blocked message names it "<name> (switched off)".
 * (owner 2026-10-11: keyboards have unique ids) Switched off: in the added list, its id with a set or a pool.
 */
@RunWith(RobolectricTestRunner::class)
class SwitchedOffDictionaryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a by lazy { available("en-US").withId(1) }

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
    }
    @After fun tearDown() {
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Test fun `a dictionary only a switched-off keyboard uses can't be deleted, and the message names it switched off`() {
        // German with Italian second, switched off with its set; French, switched off with only its learned pool
        val german = available("de-DE").with(ExtraValue.SECONDARY_LOCALES, "it").withId(2)
        val french = available("fr").withId(3)
        useKeyboards(ctx, real, a)
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, german, french)))
            .putString(idKey(2, Settings.PREF_KEY_FONT), "font:Mine.ttf")
            .putString(LearnedKeyboards.PREF_POOLS, """{"k1":5,"k3":6,"k8":7}""").commit() // (k8: an id of no keyboard)

        assertEquals(listOf(german), dictionaryUsers(ctx, Locale.GERMANY, a)) // its main language
        assertEquals(listOf(german), dictionaryUsers(ctx, Locale.ITALIAN, a)) // its second language
        assertEquals(listOf(french), dictionaryUsers(ctx, Locale.FRENCH, a)) // a pool entry only
        val name = dictionaryUserNames(ctx, dictionaryUsers(ctx, Locale.GERMANY, a)).single()
        assertEquals(keyboardName(german, ctx) + " (switched off)", name)
        assertEquals(listOf(keyboardName(a, ctx)), dictionaryUserNames(ctx, listOf(a))) // an enabled one as before
    }

    @Test fun `no keyboard uses it - the delete goes on as before`() {
        useKeyboards(ctx, real, a)
        // (German in the added list with neither a set nor a pool: not a switched-off keyboard)
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, available("de-DE").withId(2))))
            .putString(LearnedKeyboards.PREF_POOLS, """{"k1":5}""").commit()
        assertEquals(emptyList(), dictionaryUsers(ctx, Locale.ITALIAN, a))
        assertEquals(emptyList(), dictionaryUsers(ctx, Locale.GERMANY, a))
    }
}

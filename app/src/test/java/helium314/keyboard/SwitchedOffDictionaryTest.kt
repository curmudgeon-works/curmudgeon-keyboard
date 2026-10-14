// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.profileIds
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.dictionaryUserNames
import helium314.keyboard.settings.dialogs.dictionaryUsers
import helium314.keyboard.settings.screens.keyboardName
import org.json.JSONObject
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
 */
@RunWith(RobolectricTestRunner::class)
class SwitchedOffDictionaryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a by lazy { available("en-US") }

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
    }
    @After fun tearDown() {
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Test fun `a dictionary only a switched-off keyboard uses can't be deleted, and the message names it switched off`() {
        // German with Italian second, switched off with its id; French, switched off with only its learned pool
        val german = available("de-DE").with(ExtraValue.SECONDARY_LOCALES, "it")
        val french = available("fr")
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(german))).commit()
        useKeyboards(ctx, real, a)
        profileIds(real, mapOf(a to 1, german to 2), 3)
        real.edit().putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(a.toPref() to 5, french.toPref() to 6, "" to 7)).toString()).commit()

        assertEquals(listOf(german), dictionaryUsers(ctx, Locale.GERMANY, a)) // its main language
        assertEquals(listOf(german), dictionaryUsers(ctx, Locale.ITALIAN, a)) // its second language
        assertEquals(listOf(french), dictionaryUsers(ctx, Locale.FRENCH, a)) // a pool entry only
        val name = dictionaryUserNames(ctx, dictionaryUsers(ctx, Locale.GERMANY, a)).single()
        assertEquals(keyboardName(german, ctx) + " (switched off)", name)
        assertEquals(listOf(keyboardName(a, ctx)), dictionaryUserNames(ctx, listOf(a))) // an enabled one as before
    }

    @Test fun `no keyboard uses it - the delete goes on as before`() {
        useKeyboards(ctx, real, a)
        profileIds(real, mapOf(a to 1, available("de-DE") to 0), 2) // (on the shared set: not a switched-off keyboard)
        assertEquals(emptyList(), dictionaryUsers(ctx, Locale.ITALIAN, a))
        assertEquals(emptyList(), dictionaryUsers(ctx, Locale.GERMANY, a))
    }
}

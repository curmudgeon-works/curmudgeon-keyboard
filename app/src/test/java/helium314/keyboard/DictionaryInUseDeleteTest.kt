// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.dictionary.DictionarySelection
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.dictionaryDeleteBlockers
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review L3.2 (owner decided): the dictionary version in use can't be deleted while any keyboard, on or off, has the
 * language, the keyboard asking included: also when it's the only copy, and an added main one over the built-in. It can
 * go once another version is selected or no keyboard has the language. (Before, only other keyboards blocked it: the
 * asking keyboard's only copy could be deleted.)
 */
@RunWith(RobolectricTestRunner::class)
class DictionaryInUseDeleteTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val en by lazy { available("en-US").withId(1) }
    private val de by lazy { available("de-DE").withId(2) }
    private val dir by lazy { File(DictionaryInfoUtils.getCacheDirectoryForLocale(Locale.US, ctx)!!) }

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        dir.deleteRecursively(); dir.mkdirs()
    }
    @After fun tearDown() {
        dir.deleteRecursively()
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    // fail before (compile: no such check; the dialog's old check, other keyboards only, left the asking keyboard's copy deletable)
    @Test fun `the only copy in use is kept while the asking keyboard has the language`() {
        useKeyboards(ctx, real, en)
        val main = File(dir, "main_user.dict").apply { writeText("x") } // (over the built-in English one)
        assertEquals(listOf(en), dictionaryDeleteBlockers(ctx, Locale.US, main))
    }

    @Test fun `another version selected frees it, the selected one is kept, and no keyboard with the language frees all`() {
        useKeyboards(ctx, real, en, de)
        val main = File(dir, "main_user.dict").apply { writeText("x") }
        val prev = File(dir, "main_prev_user.dict").apply { writeText("y") }
        assertEquals(emptyList(), dictionaryDeleteBlockers(ctx, Locale.US, prev)) // (not in use)
        DictionarySelection.select(ctx, Locale.US, prev.name)
        assertEquals(emptyList(), dictionaryDeleteBlockers(ctx, Locale.US, main))
        assertEquals(listOf(en), dictionaryDeleteBlockers(ctx, Locale.US, prev))
        useKeyboards(ctx, real, de) // (English off every keyboard)
        assertEquals(emptyList(), dictionaryDeleteBlockers(ctx, Locale.US, prev))
    }
}

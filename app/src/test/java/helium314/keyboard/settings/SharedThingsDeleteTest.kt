// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.idleUntil
import helium314.keyboard.latin.personalization.readList
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.personalization.writeList
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.dictionaryUsers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-11, keyboards and dictionaries: a deleted keyboard's lists can go into one of the keyboards that read
 * them ("Merge these lists into one of those keyboards and refer from others"); an added dictionary other keyboards use
 * can't be deleted ("No delete allowed if other keyboards using dict").
 */
@RunWith(RobolectricTestRunner::class)
class SharedThingsDeleteTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private val io = BodyStoreIo()
    private val a by lazy { available("en-US") }
    private val b by lazy { available("de-DE") }
    private val c by lazy { available("fr") }

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
    }
    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
    }

    /** Each keyboard its pool (5, 6, 7); B reads the other keyboards' learned words. */
    private fun threeKeyboards() {
        useKeyboards(ctx, real, a, b, c)
        real.edit().putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(a.toPref() to 5, b.toPref() to 6, c.toPref() to 7)).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 8)
            .putString(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, JSONArray(listOf(b.toPref())).toString()).commit()
    }

    @Test fun `the delete dialog names the keyboards reading the lists`() {
        threeKeyboards()
        assertEquals(listOf(b), LearnedKeyboards.listReaders(real, a))
        assertEquals(emptyList(), LearnedKeyboards.listReaders(real, b)) // (A and C read nothing of others)
    }

    @Test fun `a deleted keyboard's lists merge into the picked keyboard before they go`() {
        threeKeyboards()
        io.store(ce, "Latn", 5, word("alpha", 2, 100), word("beta", 5, 110))
        writeList(ce, "Latn", 5, "bad\n")
        io.store(ce, "Latn", 6, word("beta", 1, 50))
        LearnedKeyboards.deleteKeyboard(ctx, a, mergeInto = b)
        idleUntil { !LearnedStores.storeFile(ce, "Latn", 5).exists() }
        assertEquals(mapOf("alpha" to (2 to 100), "beta" to (5 to 110)), io.read(ce, "Latn", 6))
        assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", 6)?.get("bad"))
        assertTrue(!LearnedStores.storeFile(ce, "Latn", 5).exists()) // (A's own files went)
    }

    @Test fun `a merge that didn't fully work keeps the deleted keyboard's lists on disk`() {
        // (A on pool 9 here: nothing another test's background task logs about pool 5 can end the wait early)
        useKeyboards(ctx, real, a, b, c)
        real.edit().putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(a.toPref() to 9, b.toPref() to 6, c.toPref() to 7)).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 10).commit()
        io.store(ce, "Latn", 9, word("alpha", 2, 100))
        io.store(ce, "Cyrl", 9, word("бета", 3, 120))
        writeList(ce, "Latn", 9, "bad\n")
        io.store(ce, "Latn", 6, word("beta", 1, 50))
        // the Cyrillic store of the keyboard taking the lists can't be written
        LearnedKeyboards.storeIo = object : LearnedStoreIo by io {
            override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>) =
                if (dir.name.contains("Cyrl")) false else io.writeFile(dir, locale, entries)
        }
        ShadowLog.reset()
        LearnedKeyboards.deleteKeyboard(ctx, a, mergeInto = b)
        // (the task's last word: the lists deleted, or kept)
        idleUntil { ShadowLog.getLogsForTag("LearnedKeyboards").any { "pool 9" in it.msg } }
        assertTrue(LearnedStores.storeFile(ce, "Latn", 9).exists())
        assertTrue(LearnedStores.storeFile(ce, "Cyrl", 9).exists())
        assertEquals(mapOf("бета" to (3 to 120)), io.read(ce, "Cyrl", 9))
        assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", 9)?.get("bad"))
        assertEquals(mapOf("alpha" to (2 to 100), "beta" to (1 to 50)), io.read(ce, "Latn", 6)) // (what worked stays merged)
        assertTrue(9 !in storedPools(real).values) // (no keyboard has those lists any more)
    }

    @Test fun `none picked - the lists go, nobody else changes`() {
        threeKeyboards()
        io.store(ce, "Latn", 5, word("alpha", 2, 100))
        io.store(ce, "Latn", 6, word("beta", 1, 50))
        LearnedKeyboards.deleteKeyboard(ctx, a)
        idleUntil { !LearnedStores.storeFile(ce, "Latn", 5).exists() }
        assertEquals(mapOf("beta" to (1 to 50)), io.read(ce, "Latn", 6))
        assertNull(readList(ce, "Latn", 6))
    }

    @Test fun `an added dictionary's other users - a second language counts, the asking keyboard doesn't`() {
        val withEnglish = b.with(ExtraValue.SECONDARY_LOCALES, "en_US")
        real.edit().putString(helium314.keyboard.latin.settings.Settings.PREF_ADDITIONAL_SUBTYPES,
            SubtypeSettings.createPrefSubtypes(listOf(withEnglish))).commit() // (a keyboard with a second language is an added one)
        useKeyboards(ctx, real, a, withEnglish, c)
        assertEquals(listOf(withEnglish), dictionaryUsers(ctx, Locale.US, a))
        assertEquals(listOf(a), dictionaryUsers(ctx, Locale.US, withEnglish))
        assertEquals(emptyList(), dictionaryUsers(ctx, Locale.FRENCH, c)) // (only French keyboard: it can go)
    }
}

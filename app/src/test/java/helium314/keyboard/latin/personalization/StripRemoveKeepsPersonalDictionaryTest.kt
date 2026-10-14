// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.UserDictionary
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.poolsJson
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review finding M7, fixed with option C (owner 2026-10-12 "don't need option to delete from personal dict when trying
 * to remove a word"): the strip's Remove (LatinIME.removeSuggestion -> DictionaryFacilitator.removeWord) never touches
 * Android's personal dictionary, also for a word the keyboard's copy of it has; the word is still blocked here.
 */
@RunWith(RobolectricTestRunner::class)
class StripRemoveKeepsPersonalDictionaryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val pool = 6
    private lateinit var store: RemovableHistory

    @Before fun setUp() {
        deleteLearnedFiles(ctx.filesDir)
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        val keyboard = available("en-US").withId(1)
        useKeyboards(ctx, real, keyboard)
        real.edit().putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_POOLS, poolsJson(mapOf(keyboard to pool)))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 7).commit()
        store = RemovableHistory(ctx, "Latn", pool)
        PersonalizationHelper.setForTest("Latn", pool, store)
        LearnedStores.storeFile(ctx.filesDir, "Latn", pool).mkdirs()
        GestureDecoderVocabulary.clear()
        LearnedStores.init(ctx)
        LearnedStores.refresh(real)
        assertEquals(pool, LearnedStores.currentPool)
    }

    @After fun tearDown() {
        PersonalizationHelper.setForTest("Latn", pool, null)
        deleteLearnedFiles(ctx.filesDir)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
        GestureDecoderVocabulary.clear()
    }

    @Test fun `strip Remove of a word in the personal dictionary leaves Android's rows and still blocks the word`() {
        val provider = Robolectric.setupContentProvider(FakeUserDictionary::class.java, UserDictionary.AUTHORITY)
        provider.rows += listOf(Row(1, "zorblat", "en_US"), Row(2, "Zorblat", null))
        store.counts["zorblat"] = 3 // learned too
        val f = DictionaryFacilitatorImpl()
        f.resetDictionaries(ctx, Locale.US, false, false, true, false, "", null)
        f.waitForLoadingMainDictionaries(1, TimeUnit.SECONDS) // (a load that fails never counts down)
        // the keyboard's copy of Android's personal dictionary, holding the word (no native library in tests)
        val personalCopy = RemovableHistory(ctx, "Latn", 9).apply { counts["zorblat"] = 1 }
        val groups = DictionaryFacilitatorImpl::class.java.getDeclaredField("dictionaryGroups").apply { isAccessible = true }.get(f) as List<*>
        @Suppress("UNCHECKED_CAST")
        val subDicts = groups[0]!!.javaClass.getDeclaredField("subDicts").apply { isAccessible = true }
            .get(groups[0]) as MutableMap<String, ExpandableBinaryDictionary>
        subDicts[Dictionary.TYPE_USER] = personalCopy
        try {
            f.removeWord("zorblat") // what LatinIME.removeSuggestion calls for the strip's Remove

            // (the delete went to the background before: give it the time it had)
            Thread.sleep(500)
            assertEquals(listOf(1L, 2L), provider.rows.map { it.id }, "Android's personal dictionary lost a row")
            assertTrue(personalCopy.counts.containsKey("zorblat"), "the keyboard's copy of it lost the word")
            assertFalse(store.counts.containsKey("zorblat"), "still learned")
            assertTrue(RemovedWords.blacklist(ctx, "Latn", pool).entries().keys.any { it.equals("zorblat", true) }, "not blacklisted")
            assertTrue(f.isRemovedWord("zorblat"), "still offered")
        } finally { f.closeDictionaries() }
    }

    data class Row(val id: Long, val word: String, val locale: String?)

    /** Android's personal dictionary: every delete would show in [rows]. */
    class FakeUserDictionary : ContentProvider() {
        val rows = mutableListOf<Row>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection).apply {
                for (row in rows) addRow(projection!!.map { when (it) {
                    UserDictionary.Words._ID -> row.id
                    UserDictionary.Words.WORD -> row.word
                    UserDictionary.Words.LOCALE -> row.locale
                    else -> null
                } })
            }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = selectionArgs!![0].toLong()
            return if (rows.removeAll { it.id == id }) 1 else 0
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}

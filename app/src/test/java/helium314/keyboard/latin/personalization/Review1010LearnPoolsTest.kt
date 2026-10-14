// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.poolsJson
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import helium314.keyboard.latin.dictionary.Dictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A test store of learned words (as [FakeHistory]) whose Remove takes the word out. */
class RemovableHistory(ctx: Context, script: String, pool: Int)
    : UserHistoryDictionary(ctx, LearnedStores.storeName(script, pool), LearnedStores.storeLocale(script)) {
    val counts = ConcurrentHashMap<String, Int>()
    override fun awaitBeforeLoad() { throw IllegalStateException("a test store: no file") }
    override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray): ArrayList<SuggestedWordInfo> = ArrayList()
    override fun isInDictionary(word: String) = counts.containsKey(word)
    override fun isValidWord(word: String) = counts.containsKey(word)
    override fun getFrequency(word: String) = if (counts.containsKey(word)) 150 else NOT_A_PROBABILITY
    override fun getLearnedWordProperty(word: String, isBeginningOfSentence: Boolean): WordProperty? =
        counts[word]?.let { property(word, it) }
    override fun getAllWordPropertiesBlocking(): Array<WordProperty> = counts.map { property(it.key, it.value) }.toTypedArray()
    override fun removeUnigramEntryDynamically(word: String) { counts.remove(word) }
    override fun onFinishInput() { }
    override fun flushAndClose() { }
    private fun property(word: String, count: Int) =
        WordProperty(word, ProbabilityInfo(150, (System.currentTimeMillis() / 1000).toInt(), 0, count), null, null, false, false)
}

/** The review of 2026-10-08 (0.3.007 to 0.3.010), area 3 #6 and #7, fixed 2026-10-10: the learned words of the
 *  keyboard in use against those of another keyboard (the swipe lists, the spell checker). */
@RunWith(RobolectricTestRunner::class)
class Review1010LearnPoolsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private lateinit var a: SettingsSubtype
    private lateinit var b: SettingsSubtype
    private val poolA = 5
    private val poolB = 6
    private lateinit var storeA: RemovableHistory
    private lateinit var storeB: RemovableHistory

    @Before fun setUp() {
        deleteLearnedFiles(ctx.filesDir)
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        b = available("en-US").withId(1)
        a = available("de").withId(2)
        useKeyboards(ctx, real, b, a) // B in use
        real.edit().putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_POOLS, poolsJson(mapOf(a to poolA, b to poolB)))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 7).commit()
        storeA = RemovableHistory(ctx, "Latn", poolA)
        storeB = RemovableHistory(ctx, "Latn", poolB)
        PersonalizationHelper.setForTest("Latn", poolA, storeA)
        PersonalizationHelper.setForTest("Latn", poolB, storeB)
        LearnedStores.storeFile(ctx.filesDir, "Latn", poolA).mkdirs()
        LearnedStores.storeFile(ctx.filesDir, "Latn", poolB).mkdirs()
        GestureDecoderVocabulary.clear()
        LearnedStores.init(ctx)
        LearnedStores.refresh(real)
        assertEquals(poolB, LearnedStores.currentPool)
    }

    @After fun tearDown() {
        PersonalizationHelper.setForTest("Latn", poolA, null)
        PersonalizationHelper.setForTest("Latn", poolB, null)
        deleteLearnedFiles(ctx.filesDir)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
        GestureDecoderVocabulary.clear()
    }

    private fun swipeable(word: String) = GestureDecoderVocabulary.learnedWordsFor(ctx, Locale.US).any { it.first == word }

    /** A facilitator set up while A was in use (the spell checker's, say), with a main dictionary (none in tests). */
    private fun facilitatorOfA(): DictionaryFacilitatorImpl {
        useKeyboards(ctx, real, a, b)
        LearnedStores.refresh(real)
        assertEquals(poolA, LearnedStores.currentPool)
        val f = DictionaryFacilitatorImpl()
        f.resetDictionaries(ctx, Locale.US, false, false, true, false, "", null)
        f.waitForLoadingMainDictionaries(1, TimeUnit.SECONDS) // (a load that fails never counts down)
        val dict = object : Dictionary(Dictionary.TYPE_MAIN, Locale.US) {
            override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
                    settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
                    inOutWeightOfLangModelVsSpatialModel: FloatArray) = ArrayList<SuggestedWordInfo>()
            override fun isInDictionary(word: String) = false
        }
        val groups = DictionaryFacilitatorImpl::class.java.getDeclaredField("dictionaryGroups").apply { isAccessible = true }.get(f) as List<*>
        groups[0]!!.javaClass.getDeclaredMethod("setMainDict", Dictionary::class.java).apply { isAccessible = true }.invoke(groups[0], dict)
        useKeyboards(ctx, real, b, a) // back to B
        LearnedStores.refresh(real)
        assertEquals(poolB, LearnedStores.currentPool)
        return f
    }

    // area 3 #6: Remove on keyboard A's Learned words screen took the word out of the swipe lists of B, the keyboard in use
    @Test fun `Remove on another keyboard's learned words leaves the swipe list of the keyboard in use alone`() {
        storeA.counts["zorblat"] = 4
        storeB.counts["zorblat"] = 4
        storeB.counts["quexil"] = 4 // (a word the swipe lists lose here, to know their earlier work is done)
        val f = facilitatorOfA()
        try {
            assertTrue(swipeable("zorblat"), "B's own learned word")
            DictionaryFacilitatorImpl.removeWords(ctx, "Latn", poolA, listOf(Locale.US), listOf("zorblat")) // A's screen: Remove
            assertTrue(!storeA.counts.containsKey("zorblat"), "not removed from A")
            GestureDecoderVocabulary.onWordRemoved(Locale.US, "quexil")
            val until = System.currentTimeMillis() + 5000
            while (swipeable("quexil") && System.currentTimeMillis() < until) Thread.sleep(20)
            assertTrue(!swipeable("quexil"), "the swipe lists didn't take the marker word out")
            assertTrue(swipeable("zorblat"), "B lost its own learned word from its swipe list")
        } finally { f.closeDictionaries() }
    }

    // area 3 #7: the spell checker kept the learned words of the keyboard in use when it was set up
    @Test fun `the spell checker reads the learned words of the keyboard in use after a switch`() {
        storeA.counts["zorblat"] = 4 // learned on A only
        val cache = helium314.keyboard.latin.DictionaryFacilitatorLruCache(ctx, "spellcheck_")
        try {
            assertFalse(cache.get(Locale.US).isValidSpellingWord("zorblat"), "B in use: A's word isn't B's")
            useKeyboards(ctx, real, a, b) // A in use now
            LearnedStores.refresh(real)
            assertTrue(cache.get(Locale.US).isValidSpellingWord("zorblat"), "A in use: the spell checker still read B's words")
        } finally { cache.closeDictionaries() }
    }

    // fail before (L4.2): Remove cleared the allowed row of the struck spelling only, the other spelling's stayed
    @Test fun `Remove clears the allowed rows of the word as typed and in lowercase`() {
        storeA.counts["Zorblat"] = 3
        val list = helium314.keyboard.latin.utils.RemovedWords.blacklist(ctx, "Latn", poolA)
        list.ensureLoaded()
        list.allow("Zorblat", 1); list.allow("zorblat", 1)
        val f = facilitatorOfA()
        try {
            DictionaryFacilitatorImpl.removeWords(ctx, "Latn", poolA, listOf(Locale.US), listOf("Zorblat"))
            assertEquals(emptyMap(), list.allowedEntries())
            assertTrue(list.entries().keys.any { it.equals("zorblat", true) }, "Remove no longer blacklists")
        } finally {
            f.closeDictionaries()
            list.replaceAll(emptyMap())
        }
    }

    // 2026-10-11 (owner: "longpress delete should just knock that word out of that list entirely as if it never existed")
    @Test fun `Delete on the Learned words list forgets the word without blacklisting it - Remove still blacklists`() {
        storeA.counts["wfddsg"] = 3
        storeA.counts["teh"] = 2
        val f = facilitatorOfA()
        try {
            DictionaryFacilitatorImpl.removeWords(ctx, "Latn", poolA, listOf(Locale.US), listOf("wfddsg"), blacklist = false)
            assertTrue(!storeA.counts.containsKey("wfddsg"), "still learned")
            assertTrue(helium314.keyboard.latin.utils.RemovedWords.blacklist(ctx, "Latn", poolA).entries().keys.none { it.equals("wfddsg", true) },
                "Delete blacklisted it")
            DictionaryFacilitatorImpl.removeWords(ctx, "Latn", poolA, listOf(Locale.US), listOf("teh")) // long-press Remove
            assertTrue(!storeA.counts.containsKey("teh"), "still learned")
            assertTrue(helium314.keyboard.latin.utils.RemovedWords.blacklist(ctx, "Latn", poolA).entries().keys.any { it.equals("teh", true) },
                "Remove no longer blacklists")
        } finally { f.closeDictionaries() }
    }
}

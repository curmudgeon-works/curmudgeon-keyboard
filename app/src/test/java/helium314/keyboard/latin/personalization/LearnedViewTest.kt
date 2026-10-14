// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.os.UserManager
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.android.inputmethod.keyboard.ProximityInfo
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.poolsJson
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store of learned words without the native library: words with their counts, nothing read or written. */
class FakeHistory(ctx: Context, script: String, pool: Int)
    : UserHistoryDictionary(ctx, LearnedStores.storeName(script, pool), LearnedStores.storeLocale(script)) {
    val counts = ConcurrentHashMap<String, Int>()
    override fun awaitBeforeLoad() { throw IllegalStateException("a test store: no file") } // (the native load never runs)
    override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
            settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
            inOutWeightOfLangModelVsSpatialModel: FloatArray): ArrayList<SuggestedWordInfo> =
        ArrayList(counts.keys.filter { it.startsWith(composedData.mTypedWord, ignoreCase = true) }.map {
            SuggestedWordInfo(it, "", 100, SuggestedWordInfo.KIND_CORRECTION, this, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE) })
    override fun isInDictionary(word: String) = counts.containsKey(word)
    override fun getFrequency(word: String) = if (counts.containsKey(word)) 150 else NOT_A_PROBABILITY
    override fun getLearnedWordProperty(word: String, isBeginningOfSentence: Boolean): WordProperty? =
        counts[word]?.let { property(word, it) }
    override fun getAllWordPropertiesBlocking(): Array<WordProperty> = counts.map { property(it.key, it.value) }.toTypedArray()
    override fun updateEntriesForWord(ngramContext: NgramContext, word: String, isValid: Boolean, count: Int, timestamp: Int) {
        counts.merge(word, 0) { old, _ -> old + count }
    }
    override fun onFinishInput() { }
    override fun flushAndClose() { }
    private fun property(word: String, count: Int) =
        WordProperty(word, ProbabilityInfo(150, (System.currentTimeMillis() / 1000).toInt(), 0, count), null, null, false, false)
}

/**
 * The buttons "Use lists from other keyboards" (2026-10-10): with [Learned] on, the other keyboards' learned words are
 * read as the keyboard's own (suggestions, swipe, spell check, trust), learning stays in its own store; with
 * [Blacklisted] on, the other keyboards' blacklists apply, and typing a word back allows it on the keyboard's own list
 * (an allowed row); the list it came from stays as it is (owner 2026-10-11 DM21: borrowed lists are read only).
 * Through the real paths: the buttons' preferences, LearnedStores' refresh, the keyboard's facilitator, the swipe list.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedViewTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private lateinit var a: SettingsSubtype
    private lateinit var b: SettingsSubtype
    private val poolA = 5
    private val poolB = 6
    private lateinit var storeA: FakeHistory
    private lateinit var storeB: FakeHistory
    private lateinit var f: DictionaryFacilitatorImpl
    private val keyboard: Keyboard = Mockito.mock(Keyboard::class.java).also {
        Mockito.`when`(it.proximityInfo).thenReturn(Mockito.mock(ProximityInfo::class.java))
    }

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
        storeA = FakeHistory(ctx, "Latn", poolA).apply { counts["zorblat"] = 4 }
        storeB = FakeHistory(ctx, "Latn", poolB)
        PersonalizationHelper.setForTest("Latn", poolA, storeA)
        PersonalizationHelper.setForTest("Latn", poolB, storeB)
        LearnedStores.storeFile(ctx.filesDir, "Latn", poolA).mkdirs() // (A's store has a file)
        GestureDecoderVocabulary.clear()
        LearnedStores.init(ctx)
        Settings.getInstance().loadSettings(ctx, Locale.US, InputAttributes(EditorInfo(), false, ctx.packageName))
        f = DictionaryFacilitatorImpl()
        setUpDictionaries()
    }

    @After fun tearDown() {
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        f.closeDictionaries()
        PersonalizationHelper.setForTest("Latn", poolA, null)
        PersonalizationHelper.setForTest("Latn", poolB, null)
        deleteLearnedFiles(ctx.filesDir)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.loadGroups(real)
    }

    /** What LatinIME does when the view changes (its pool listener): the dictionaries set up again if they differ. */
    private fun setUpDictionaries() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        if (!f.usesSameSettings(listOf(Locale.US), false, false, true))
            f.resetDictionaries(ctx, Locale.US, false, false, true, false, "", null)
        f.awaitOtherStores()
    }

    private fun button(pref: String, on: Boolean) {
        LearnedKeyboards.setUsesOthers(real, pref, b, on)
        setUpDictionaries()
    }

    private fun offered(typed: String): List<String> =
        f.getSuggestionResults(ComposedData(InputPointers(1), false, typed), NgramContext.EMPTY_PREV_WORDS_INFO, keyboard,
            SettingsValuesForSuggestion(false), 0, 0).map { it.mWord }

    private fun swipeable(word: String) = GestureDecoderVocabulary.learnedWordsFor(ctx, Locale.US).any { it.first == word }

    /** A main dictionary with [words] for B's language (none in tests: no native library). */
    private fun mainDictionary(vararg words: String) {
        f.waitForLoadingMainDictionaries(1, TimeUnit.SECONDS) // (a load that fails never counts down)
        val dict = object : Dictionary(Dictionary.TYPE_MAIN, Locale.US) {
            override fun getSuggestions(composedData: ComposedData, ngramContext: NgramContext, proximityInfoHandle: Long,
                    settingsValuesForSuggestion: SettingsValuesForSuggestion, sessionId: Int, weightForLocale: Float,
                    inOutWeightOfLangModelVsSpatialModel: FloatArray) =
                ArrayList(words.filter { it.startsWith(composedData.mTypedWord) }.map {
                    SuggestedWordInfo(it, "", 200, SuggestedWordInfo.KIND_CORRECTION, this, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE) })
            override fun isInDictionary(word: String) = word in words
            override fun isValidWord(word: String) = word in words
        }
        val groups = DictionaryFacilitatorImpl::class.java.getDeclaredField("dictionaryGroups").apply { isAccessible = true }.get(f) as List<*>
        groups[0]!!.javaClass.getDeclaredMethod("setMainDict", Dictionary::class.java).apply { isAccessible = true }.invoke(groups[0], dict)
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(done(), what)
    }

    @Test fun `with Learned on another keyboard's learned word is offered and swipeable, off it's gone`() {
        assertFalse("zorblat" in offered("zorb"))
        button(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, true)
        assertEquals(listOf(poolA), LearnedStores.currentView.othersLearned)
        assertTrue("zorblat" in offered("zorb"), "offered on B")
        assertTrue(swipeable("zorblat"), "in B's swipe list")
        button(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, false)
        assertFalse("zorblat" in offered("zorb"), "gone from the strip")
        assertFalse(swipeable("zorblat"), "gone from the swipe list")
    }

    @Test fun `with Blacklisted on another keyboard's blacklist hides a dictionary word`() {
        writeList(ctx.filesDir, "Latn", poolA, "flimsy\n")
        mainDictionary("flimsy")
        assertTrue("flimsy" in offered("flim"), "A's list doesn't count with the button off")
        button(LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, true)
        mainDictionary("flimsy")
        waitFor("A's list read") { f.isRemovedWord("flimsy") }
        assertFalse("flimsy" in offered("flim"), "hidden on B")
        button(LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, false)
        mainDictionary("flimsy")
        assertTrue("flimsy" in offered("flim"), "visible again with the button off")
    }

    @Test fun `B's own blacklist beats A's learned word`() {
        writeList(ctx.filesDir, "Latn", poolB, "zorblat\n")
        button(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, true)
        waitFor("B's list read") { f.isRemovedWord("zorblat") }
        assertFalse("zorblat" in offered("zorb"), "A's count doesn't bring it back on B")
        assertFalse(swipeable("zorblat"))
    }

    @Test fun `typing on B learns into B's store only`() {
        button(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, true)
        assertTrue("zorblat" in offered("zorb"), "A's words read on B")
        val before = HashMap(storeA.counts)
        f.addToUserHistory("zorblat", false, NgramContext.EMPTY_PREV_WORDS_INFO, System.currentTimeMillis() / 1000, false)
        f.addToUserHistory("quintor", false, NgramContext.EMPTY_PREV_WORDS_INFO, System.currentTimeMillis() / 1000, false)
        assertEquals(before, HashMap(storeA.counts), "A's words unchanged")
        assertEquals(setOf("zorblat", "quintor"), storeB.counts.keys)
        assertEquals(listOf("UserHistoryDictionary.Latn.k5.dict"), ctx.filesDir.list()!!.filter { it.startsWith(UserHistoryDictionary.NAME) },
            "no file written for anyone")
    }

    @Test fun `typing a word back on B allows it on B's own list, A's list keeps it`() {
        writeList(ctx.filesDir, "Latn", poolA, "quexil\n")
        button(LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, true)
        waitFor("A's list read") { f.isRemovedWord("quexil") }
        f.addToUserHistory("quexil", false, NgramContext.EMPTY_PREV_WORDS_INFO, System.currentTimeMillis() / 1000, false)
        assertFalse(f.isRemovedWord("quexil"), "back on B")
        val listB = LearnedStores.blacklistFile(ctx.filesDir, "Latn", poolB)
        waitFor("the allowed row on B's list on disk") { listB.isFile && "quexil\tallowed\t1" in listB.readLines() }
        assertNull(readList(ctx.filesDir, "Latn", poolB)?.get("quexil"), "not blocked on B's list")
        assertTrue(readList(ctx.filesDir, "Latn", poolA)?.containsKey("quexil") == true, "A's list keeps it (read only)")
    }

    @Test fun `spell check and trust see A's word only with Learned on`() {
        storeA.counts["zorblat"] = 10
        assertFalse(f.isValidSpellingWord("zorblat"))
        assertFalse(f.isTrustedWord("zorblat"))
        button(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, true)
        assertTrue(f.isValidSpellingWord("zorblat"))
        assertTrue(f.isTrustedWord("zorblat"))
    }

    @Test fun `locked - no other keyboard's store is listed or opened`() {
        PersonalizationHelper.setForTest("Latn", poolA, null)
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        button(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, true)
        assertTrue(LearnedStores.currentView.othersLearned.isEmpty())
        assertNull(PersonalizationHelper.getCachedUserHistoryDictionary("Latn", poolA), "A's store not opened")
        // unlocked: the next refresh (the keyboard's next start or switch) lists it
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        LearnedStores.refresh(real)
        assertEquals(listOf(poolA), LearnedStores.currentView.othersLearned)
    }

    @Test fun `a keyboard without a file gets no store`() {
        PersonalizationHelper.setForTest("Latn", poolA, null)
        LearnedStores.storeFile(ctx.filesDir, "Latn", poolA).deleteRecursively()
        button(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, true)
        assertEquals(listOf(poolA), LearnedStores.currentView.othersLearned)
        swipeable("zorblat")
        assertNull(PersonalizationHelper.getCachedUserHistoryDictionary("Latn", poolA), "none created for A")
        assertFalse(LearnedStores.storeFile(ctx.filesDir, "Latn", poolA).exists())
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import android.os.SystemClock
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.gesture.Vocabulary
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.ScriptUtils.script
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-locale [Vocabulary] for the in-tree gesture decoder (lab flavor).
 *
 * Built from the ACTUAL dictionaries of the locale: the top [MAX_WORDS] words by
 * probability from the main binary dictionary (the same cached/extracted .dict file
 * the facilitator loads — opened read-only via our own [BinaryDictionary]), merged
 * with the user-history words at their own weight (the learned-word boost and "suggest learned words" are applied
 * when a swipe is scored: [Vocabulary.Node.weight]).
 *
 * The main-dict extraction is the expensive part (a full getNextWordProperty walk
 * over JNI, ~16 s at 10k words), so its result is persisted to
 * files/own_gesture_vocab/<languageTag>.txt. On later process starts the list loads
 * from disk in milliseconds and only user history (small, per-user) is merged live;
 * the dictionary is re-walked only when the cache header no longer matches the dict
 * file identity, the word cap, or the offensive-word setting — and while that
 * rebuild runs, the stale-but-close vocabulary already serves swipes.
 *
 * Decoding never blocks on this: [getOrBuildAsync] returns null until a build or
 * disk load completes (the decoder then simply produces no results yet).
 */
object GestureDecoderVocabulary {
    private const val TAG = "GestureDecoderVocab"
    private const val MAX_WORDS = 50_000
    private const val MIN_PROBABILITY = 1 // dictionary probabilities are 0..255, log-ish
    // (the learned-word boost and "suggest learned words" are no longer kept here: they were baked into every vocabulary,
    // so a change rebuilt all of them and the keyboard's warm-up built with whatever the last swipe had set; they are
    // settings the decoder applies at scoring time now, see DecoderConfig.learnedBoost; 2026-10-06)

    init {
        // another keyboard's own learned words (or all keyboards' again): the vocabularies carry the old ones
        LearnedStores.onPoolChanged { rebuildWithLearned() }
    }

    // learned words are merged into every cached vocabulary: when they change as a whole (another keyboard's own learned
    // words) or a removed word comes back, rebuild them in the background while the current ones keep serving swipes
    // (clearing them emptied every swipe until the rebuild)
    private fun rebuildWithLearned() {
        val context = Settings.getCurrentContext() ?: run { cache.clear(); clearMerged(); learned.clear(); return }
        val entries = mainEntries.toMap()
        Thread({
            for ((key, list) in entries) runCatching { publishNow(key, key.constructLocale(), context, list) }
        }, "GestureVocabBoost").start()
    }
    /**
     * Which words must stay out of the vocabularies, so they can't be swiped. Asked once per word while a vocabulary
     * is built (and for a word learned or removed live), never per swipe: keep it a set lookup.
     * [learned]: about the word's learned copy (user history) rather than its main-dictionary copy. A word that must
     * never come back (deleted everywhere) answers true for both; a removed word for both until it's back (typed again
     * often enough, see RemovedWords.isRemoved), then for its dictionary copy only.
     */
    fun interface WordExclusion {
        fun isExcluded(context: Context, locale: Locale, word: String, learned: Boolean): Boolean
    }

    /**
     * Words removed with long-press, like in typing's suggestions: the dictionary's copy stays out; the learned copy
     * comes in once the word is back (the same rule as DictionaryFacilitatorImpl.isRemovedWord: RemovedWords.isRemoved).
     * Further lists plug in by wrapping this (OR), telling the vocabularies about each new entry with [onWordRemoved].
     */
    @Volatile var exclusion: WordExclusion = WordExclusion { context, locale, word, learned ->
        isRemoved(context, locale, word, learned)
    }

    /** Removed with long-press, in this or the lowercase form, and (for the [learned] copy) not back yet. */
    private fun isRemoved(context: Context, locale: Locale, word: String, learned: Boolean): Boolean {
        // the same list object the keyboard's dictionaries hold (read here once if they haven't yet: a build thread);
        // the list of the word's own script, like the learned words
        val script = LearnedStores.scriptOf(word, locale)
        val entry = RemovedWords.blacklist(context, script).apply { ensureLoaded() }.entryFor(word) ?: return false
        if (!learned) return true
        // (only for a removed word: the counts aren't read per vocabulary word)
        val history = PersonalizationHelper.getUserHistoryDictionary(context, script, LearnedStores.currentPool)
        val lower = word.lowercase()
        val count = maxOf(history.getLearnedCount(word), if (lower != word) history.getLearnedCount(lower) else -1)
        return RemovedWords.isRemoved(entry, count)
    }

    private fun isExcluded(context: Context, locale: Locale, word: String, learned: Boolean) =
        try { exclusion.isExcluded(context, locale, word, learned) } catch (t: Throwable) { false }

    /** [entries] without the words [exclusion] keeps out of [locale]'s vocabulary. */
    internal fun withoutExcluded(context: Context, locale: Locale, entries: List<Pair<String, Int>>, learned: Boolean) =
        entries.filterNot { isExcluded(context, locale, it.first, learned) }

    private const val CACHE_DIR = "own_gesture_vocab"
    private const val CACHE_VERSION = 1
    // the learned-words store loads on its own thread; a read before that is done comes back empty, so a cold start
    // retries a few times. (Until 2026-10-05 the read itself gave up after 100 ms and answered with nothing, which a
    // store of ~11k words always missed: ~165 ms on a 2026 flagship phone. That read is gone; see historyEntries.)
    private const val HISTORY_RETRIES = 10
    private const val HISTORY_RETRY_DELAY_MS = 200L
    private const val HISTORY_LATE_RETRY_DELAY_MS = 20_000L

    private val cache = ConcurrentHashMap<String, Vocabulary>()
    private val building = ConcurrentHashMap.newKeySet<String>()
    // locales whose build found no dictionary: asking again would start a thread per request (e.g. per keystroke)
    private val noDictionary = ConcurrentHashMap.newKeySet<String>()
    // main-dict entries per locale, kept so multilingual vocabularies can be merged from them
    private val mainEntries = ConcurrentHashMap<String, List<Pair<String, Int>>>()
    private val merged = ConcurrentHashMap<String, Vocabulary>()
    // the languages of each merged vocabulary, so a newly learned word can go in with its language's weight
    private val mergedSpecs = ConcurrentHashMap<String, List<LocaleSpec>>()
    // learned words go into the live vocabularies one at a time, off the typing thread
    private val learnExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "GestureVocabLearn") }
    private const val LEARN_DELAY_MS = 1000L // the user-history write is asynchronous too: read the word's new weight after it

    /** One language of a multilingual keyboard: its score factor from the priority setting. */
    class LocaleSpec(val locale: Locale, val factor: Float) {
        val key get() = "${locale.toLanguageTag()}*$factor"
    }

    /**
     * Vocabulary for a multilingual keyboard: the per-locale vocabularies merged, each locale's words
     * scaled by its [LocaleSpec.factor]. Learned words are per script and count fully (as in typing's suggestions):
     * added once per script, unscaled. Null (and per-locale builds kicked off) until every locale's vocabulary exists.
     */
    /** The languages of a keyboard as the decoder asks for them: each with its priority factor. The keyboard's open
     *  (LatinIME) warms the same list the first swipe (OwnGestureDecoder) will ask for, so the merge is ready by then. */
    fun specsFor(context: Context, locales: List<Locale>): List<LocaleSpec> {
        val prefs = context.prefs()
        return locales.map { LocaleSpec(it, LanguagePriority.factor(prefs, it)) }
    }

    fun getOrBuildAsync(specs: List<LocaleSpec>): Vocabulary? {
        if (specs.size == 1 && specs[0].factor == 1f) return getOrBuildAsync(specs[0].locale)
        val key = specs.joinToString("|") { it.key }
        merged[key]?.let { return it }
        if (!mergeable(specs, ::getOrBuildAsync) { it in noDictionary }) { wanted[key] = specs; return null }
        // one merge at a time: the keyboard's warm-up and the first swipe may both get here; the second waits for the
        // first's result instead of doing the same work at the same time (review 2026-10-06). One lock for all lists:
        // merges are rare and two of different lists at once are rarer, and a lock per list name piled up (2026-10-06)
        synchronized(mergeLock) {
            merged[key]?.let { return it }
            return merge(key, specs)
        }
    }
    private val mergeLock = Any()

    /**
     * Whether a merged list can be made from [specs]: each language's list is there ([listFor], which also starts the
     * builds of those that aren't), or the language has no dictionary at all ([hasNoDictionary]: nothing to wait for;
     * merge() skips it). Review 2026-10-06: one language without a dictionary left every swipe of the keyboard empty.
     */
    internal fun mergeable(specs: List<LocaleSpec>, listFor: (Locale) -> Vocabulary?, hasNoDictionary: (String) -> Boolean): Boolean {
        var ready = true
        for (spec in specs) // (no early stop: every language's build is started)
            if (listFor(spec.locale) == null && !hasNoDictionary(spec.locale.toLanguageTag())) ready = false
        return ready
    }

    // counts the clears of [merged]: a merge that was running across one (a language rebuilt with learned words that
    // came in late) serves its own swipe but isn't kept, so the waiting rebuild makes a fresh one (review 2026-10-06:
    // with the lock, the rebuild took the stale list and the late learned words were missing again, as before 0.3.007)
    private val mergeGeneration = java.util.concurrent.atomic.AtomicInteger()
    private fun clearMerged() { mergeGeneration.incrementAndGet(); merged.clear() }

    private fun merge(key: String, specs: List<LocaleSpec>): Vocabulary? {
        val generation = mergeGeneration.get()
        val context = Settings.getCurrentContext() ?: return null
        val mergeStart = SystemClock.elapsedRealtime()
        val vocab = Vocabulary(emptyList())
        // dictionaries from different sources use different frequency scales (the Hinglish list's top thousand words
        // all sit at 248+, English's "the" is 222), so every further language is put on the first language's scale
        // by rank before its priority factor applies; otherwise the factor fights the scale instead of expressing priority
        val reference = specs.firstNotNullOfOrNull { mainEntries[it.locale.toLanguageTag()] }?.map { it.second }
        val scriptsDone = HashSet<String>()
        for (spec in specs) {
            val entries = mainEntries[spec.locale.toLanguageTag()] ?: continue
            val normalized = if (reference == null || entries.map { it.second } == reference) entries
                else entries.mapIndexed { i, (word, _) -> word to reference[i.coerceAtMost(reference.lastIndex)] }
            for ((word, freq) in withoutExcluded(context, spec.locale, normalized, learned = false))
                vocab.add(word, (freq * spec.factor).toInt().coerceAtLeast(MIN_PROBABILITY))
            // the languages of a script share their learned words: once for all of them
            if (scriptsDone.add(spec.locale.script()))
                for ((word, weight) in learnedOf(context, spec.locale)) vocab.addLearned(word, weight)
        }
        if (vocab.size == 0) return null
        Log.d(TAG, "merged vocabulary for $key: ${vocab.size} words")
        logVocab("swipe vocabulary for $key: ${vocab.size} words, learned ${scriptsDone.sumOf { learned[storeKey(it, LearnedStores.currentPool)]?.size ?: 0 }}, " +
                "merged in ${SystemClock.elapsedRealtime() - mergeStart} ms on ${Thread.currentThread().name}")
        if (mergeGeneration.get() == generation) {
            merged[key] = vocab
            mergedSpecs[key] = specs
        }
        return vocab
    }

    private const val COMMON_WORDS = 10_000
    private val common = ConcurrentHashMap<String, Set<String>>()

    /**
     * The [COMMON_WORDS] most frequent words of [locale]'s main dictionary, lowercase: what the suggestion rules call
     * common. Null while that word list isn't loaded yet (loading is kicked off).
     */
    fun commonWords(locale: Locale): Set<String>? {
        val key = locale.toLanguageTag()
        common[key]?.let { return it }
        val entries = mainEntries[key] ?: run { getOrBuildAsync(locale); return null }
        return entries.sortedByDescending { it.second }.take(COMMON_WORDS)
            .mapTo(HashSet(COMMON_WORDS * 2)) { it.first.lowercase(locale) }.also { common[key] = it }
    }

    private const val MAX_CONTRACTIONS = 5
    private val contractions = ConcurrentHashMap<String, Map<String, List<String>>>()

    /**
     * The words of [locale]'s main dictionary that start with [typed], a word ending in its only apostrophe
     * ("you'" → you're, you've, you'll, you'd), most frequent first. Main dictionary only, so learned typos stay out.
     * Empty while that word list isn't loaded yet (loading is kicked off).
     */
    fun contractionsFor(typed: String, locale: Locale): List<String> {
        val key = locale.toLanguageTag()
        val index = contractions[key] ?: run {
            val entries = mainEntries[key] ?: run { getOrBuildAsync(locale); return emptyList() }
            contractionIndex(entries, locale).also { contractions[key] = it }
        }
        return index[typed.lowercase(locale)].orEmpty()
    }

    /** Words with an apostrophe inside, by their lowercase beginning up to and including the first apostrophe. */
    internal fun contractionIndex(entries: List<Pair<String, Int>>, locale: Locale): Map<String, List<String>> {
        val byStart = HashMap<String, MutableList<Pair<String, Int>>>()
        for (entry in entries) {
            val apostrophe = entry.first.indexOf('\'')
            if (apostrophe <= 0 || apostrophe == entry.first.lastIndex) continue
            byStart.getOrPut(entry.first.substring(0, apostrophe + 1).lowercase(locale)) { ArrayList() }.add(entry)
        }
        return byStart.mapValues { (_, words) -> words.sortedByDescending { it.second }.take(MAX_CONTRACTIONS).map { it.first } }
    }

    /** Cached vocabulary for [locale], or null (and an async build is kicked off). */
    fun getOrBuildAsync(locale: Locale): Vocabulary? {
        val key = locale.toLanguageTag()
        cache[key]?.let { return it }
        if (key in noDictionary) return null
        if (building.add(key)) {
            Thread({
                try {
                    loadOrBuild(locale, key)
                } catch (t: Throwable) {
                    Log.w(TAG, "vocabulary build failed for $key", t)
                } finally {
                    building.remove(key)
                }
            }, "GestureVocabBuild-$key").start()
        }
        return null
    }

    /**
     * Drop in-memory vocabularies — call when dictionaries change. The disk cache is
     * deliberately KEPT: its header self-detects a changed main dict (rebuilding in the
     * background while the old list keeps serving swipes), and user history is merged
     * live on every load, so e.g. a backup restore's words appear on the next reload
     * without a dead-swipe window.
     */
    fun clear() {
        cache.clear()
        common.clear()
        contractions.clear()
        noDictionary.clear()
        clearMerged()
        mergedSpecs.clear()
        learned.clear()
        wanted.clear()
    }

    /**
     * A word was just learned into [script]'s learned words: put it into the vocabularies already built for the
     * languages of that script (they only read the learned words when they are built, so a new word could otherwise not
     * be swiped until the next rebuild).
     */
    fun onWordLearned(context: Context, script: String, word: String) {
        if (!isDecodableWord(word)) return
        val pool = LearnedStores.currentPool
        learnExecutor.schedule({
            try {
                val locale = LearnedStores.storeLocale(script)
                if (isExcluded(context, locale, word, learned = true)) return@schedule
                val probability = PersonalizationHelper.getUserHistoryDictionary(context, script, pool).getFrequency(word)
                if (probability <= 0) return@schedule
                learned[storeKey(script, pool)]?.put(word, probability)
                for ((tag, vocab) in cache) if (tag.constructLocale().script() == script) vocab.addLearned(word, probability)
                // (at full weight, as when the vocabulary was built)
                for ((key, vocab) in merged)
                    if (mergedSpecs[key]?.any { it.locale.script() == script } == true) vocab.addLearned(word, probability)
            } catch (t: Throwable) {
                Log.w(TAG, "could not add learned word to the gesture vocabulary", t)
            }
        }, LEARN_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * [word] was just removed from [locale] (long-press "Remove"): take it out of the vocabularies already built, in
     * any casing (its learned copies in all casings are gone too). Rebuilds leave it out via [exclusion]; a removed
     * word typed again often enough comes back as learned through [onWordLearned].
     */
    fun onWordRemoved(locale: Locale, word: String) {
        if (word.isEmpty()) return
        learnExecutor.execute {
            try {
                val tag = locale.toLanguageTag()
                for (m in learned.values) m.keys.removeIf { it.equals(word, ignoreCase = true) }
                cache[tag]?.remove(word)
                for ((key, vocab) in merged)
                    if (mergedSpecs[key]?.any { it.locale.toLanguageTag() == tag } == true) vocab.remove(word)
            } catch (t: Throwable) {
                Log.w(TAG, "could not remove a word from the gesture vocabulary", t)
            }
        }
    }

    /** [word] was taken off [locale]'s removed words (the settings screen "Learned & blacklisted words"): its dictionary
     *  copy can be swiped again (the vocabularies are rebuilt in the background; rare). */
    @Suppress("UNUSED_PARAMETER")
    fun onWordUnblacklisted(locale: Locale, word: String) {
        rebuildWithLearned()
    }

    /** [word] got one more strike on [locale]'s removed words (DictionaryGroup.strike; the list is RemovedWords' own, read
     *  by [exclusion]): out of the vocabularies built so far. */
    fun onWordBlacklisted(locale: Locale, word: String) {
        onWordRemoved(locale, word)
    }

    private fun loadOrBuild(locale: Locale, key: String) {
        val context = Settings.getCurrentContext() ?: return
        val mainDictFile = findMainDictFile(context, locale)
        val blockOffensive = try { Settings.getValues()?.mBlockPotentiallyOffensive ?: true } catch (_: Throwable) { true }
        val expectedHeader = cacheHeader(mainDictFile, blockOffensive)
        val cacheFile = File(File(context.filesDir, CACHE_DIR), "$key.txt")

        val start = SystemClock.elapsedRealtime()
        val disk = readCache(cacheFile)
        if (disk != null) {
            val merged = publishNow(key, locale, context, disk.entries)
            val loaded = SystemClock.elapsedRealtime() - start
            if (disk.header == expectedHeader) {
                Log.d(TAG, "loaded vocabulary for $key from disk cache in $loaded ms")
                if (merged == 0) retryHistoryLater(key, locale, context, disk.entries)
                logDictionarySizeOnce(cacheFile, key) { mainDictFile?.let { addWordsFromDict(it, locale, blockOffensive, HashMap()) } }
                return
            }
            Log.d(TAG, "disk cache for $key is stale (serving it anyway), rebuilding: " +
                    "'${disk.header}' vs '$expectedHeader'")
        }

        // full rebuild: the expensive JNI walk over the main dictionary
        val words = HashMap<String, Int>(MAX_WORDS * 2)
        if (mainDictFile != null) {
            val counts = addWordsFromDict(mainDictFile, locale, blockOffensive, words)
            logDictionarySizeOnce(cacheFile, key) { counts }
        } else {
            Log.w(TAG, "no main dictionary file found for $locale")
        }
        if (words.isEmpty()) { // keep whatever the disk cache provided
            if (mainDictFile == null && disk == null) {
                noDictionary.add(key)
                mergeWanted(key) // the keyboards waiting for it don't have to any more
            }
            return
        }
        val top = words.entries.sortedByDescending { it.value }.take(MAX_WORDS).map { it.key to it.value }
        writeCache(cacheFile, expectedHeader, top)
        val merged = publishNow(key, locale, context, top)
        Log.d(TAG, "built vocabulary for $key: ${top.size} words (of ${words.size} collected) " +
                "in ${SystemClock.elapsedRealtime() - start} ms")
        if (merged == 0) retryHistoryLater(key, locale, context, top)
    }

    /** Build the trie from the main-dict entries, merge live user history, publish it. Returns merged count. */
    private fun publishNow(key: String, locale: Locale, context: Context, mainEntries: List<Pair<String, Int>>): Int {
        // removed words' dictionary copies stay out (mainEntries keeps them: the merged vocabularies filter alike)
        val vocab = Vocabulary(withoutExcluded(context, locale, mainEntries, learned = false))
        val history = learnedOnce(context, locale, HISTORY_RETRIES)
        for ((word, weight) in history) vocab.addLearned(word, weight)
        if (vocab.size > 0) {
            cache[key] = vocab
            this.mainEntries[key] = mainEntries
            common.remove(key)
            contractions.remove(key)
            clearMerged() // multilingual vocabularies containing this locale are rebuilt below or on the next swipe
            // the keyboards that asked for this language's merged vocabulary before it was ready get it now, on this
            // build thread, instead of at their first swipe
            mergeWanted(key)
        }
        return history.size
    }

    /** The merged lists asked for before language [key] was there (built, or found to have no dictionary), made now. */
    private fun mergeWanted(key: String) {
        for ((mergedKey, specs) in wanted)
            if (specs.any { it.locale.toLanguageTag() == key } && getOrBuildAsync(specs) != null) wanted.remove(mergedKey)
    }

    // the languages of a script share one learned-words store: the store is read once per build round, by whichever
    // language's build gets there first, and the others take that list (English and Hinglish both read it before,
    // at the same time, and the lock on the walk made each read take twice as long)
    private val readLocks = ConcurrentHashMap<String, Any>()
    private fun learnedOnce(context: Context, locale: Locale, retries: Int): List<Pair<String, Int>> {
        val k = storeKey(locale.script(), LearnedStores.currentPool)
        synchronized(readLocks.getOrPut(k) { Any() }) {
            learned[k]?.takeIf { it.isNotEmpty() }?.let { m -> return m.map { it.key to it.value } }
            return historyEntries(context, locale, retries)
        }
    }

    /**
     * Warms the vocabularies for [specs] when the keyboard opens, off the main thread (review 2026-10-06: merging a
     * two-language vocabulary, and reading a store not read yet, could stall the keyboard's opening). A warm-up still
     * waiting takes the latest keyboard's list instead of its own (review 2026-10-06: switching keyboards quickly left
     * the new one cold).
     */
    fun prewarm(specs: List<LocaleSpec>) {
        if (prewarmWanted.getAndSet(specs) != null) return // the waiting one takes these
        prewarmExecutor.execute {
            val latest = prewarmWanted.getAndSet(null) ?: return@execute
            try { getOrBuildAsync(latest) } catch (t: Throwable) { Log.w(TAG, "could not warm the gesture vocabulary", t) }
        }
    }
    private val prewarmExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "GestureVocabWarm").apply { isDaemon = true } }
    private val prewarmWanted = java.util.concurrent.atomic.AtomicReference<List<LocaleSpec>?>(null)

    // merged vocabularies asked for before every language was built (the keyboard's open warms them): built by the
    // last language's build, see publishNow
    private val wanted = ConcurrentHashMap<String, List<LocaleSpec>>()

    /**
     * The user-history dictionary loads asynchronously and can still be empty when the
     * vocabulary publishes (observed: 0 words merged after the retry window on a cold
     * start). The published vocab already serves swipes; wait once, well past any
     * plausible dict-load time, then republish with a fresh merge. A genuinely empty
     * history just repeats the no-op merge.
     */
    private fun retryHistoryLater(key: String, locale: Locale, context: Context, mainEntries: List<Pair<String, Int>>) {
        Thread.sleep(HISTORY_LATE_RETRY_DELAY_MS)
        val merged = publishNow(key, locale, context, mainEntries)
        if (merged > 0) Log.d(TAG, "late user-history merge for $key: $merged words")
    }

    /**
     * The user-history words with their own weight (these are words the user actually types; the boost is added at
     * scoring). Added AFTER the [MAX_WORDS] cap on main-dict words, so personal words are never evicted by the cap;
     * [Vocabulary.addLearned] keeps them apart from the dictionary frequency.
     *
     * The whole store is read and waited for (2026-10-05): the old read gave up after 100 ms and a store of tens of
     * thousands of words often took longer, so after a restart the swiped words ran at their dictionary weight or were
     * missing (swipe at 74 instead of ~190, curmudgeon not there at all) until each was learned again. Only from a
     * build thread; the multilingual vocabularies take the list kept here ([learnedOf]).
     */
    private fun historyEntries(context: Context, locale: Locale, retries: Int): List<Pair<String, Int>> {
        try {
            val script = locale.script()
            val pool = LearnedStores.currentPool
            val history = PersonalizationHelper.getUserHistoryDictionary(context, script, pool)
            val start = SystemClock.elapsedRealtime()
            var props = history.allWordPropertiesBlocking
            var attempts = 0
            while ((props == null || props.isEmpty()) && attempts++ < retries) { // still loading on a cold start
                Thread.sleep(HISTORY_RETRY_DELAY_MS)
                props = history.allWordPropertiesBlocking
            }
            val raw = ConcurrentHashMap<String, Int>((props?.size ?: 0) * 2)
            val entries = ArrayList<Pair<String, Int>>(props?.size ?: 0)
            var notCounted = 0
            for (wp in props.orEmpty()) {
                val word = wp.mWord ?: continue
                if (!isDecodableWord(word) || isExcluded(context, locale, word, learned = true)) continue
                // not counted yet (a word outside the dictionaries is stored at 0 by its first use): left out, as
                // onWordLearned leaves it out
                if (wp.probability <= 0) { notCounted++; continue }
                raw[word] = wp.probability
                entries.add(word to wp.probability)
            }
            learned[storeKey(script, pool)] = raw
            val ms = SystemClock.elapsedRealtime() - start
            Log.d(TAG, "read ${entries.size} user-history words for $locale (after $attempts empty reads)")
            logVocab("learned words read for $script/$pool: ${props?.size ?: -1} in the store, ${entries.size} in the swipe " +
                    "vocabulary, $notCounted not counted yet, $ms ms, $attempts retries; weights ${histogram(entries)}")
            return entries
        } catch (t: Throwable) {
            Log.w(TAG, "could not read user history for $locale", t)
            return emptyList()
        }
    }

    // the learned words of each store (script and pool) with their own weight (0..255; the boost is added at scoring),
    // so a multilingual vocabulary can be put together
    // without reading the store again; kept up to date by onWordLearned / onWordRemoved
    private val learned = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    private fun storeKey(script: String, pool: Int) = "$script/$pool"

    /** [locale]'s learned words for a multilingual vocabulary: the list read by its own build, else read now. */
    private fun learnedOf(context: Context, locale: Locale): List<Pair<String, Int>> {
        learned[storeKey(locale.script(), LearnedStores.currentPool)]?.let { m -> return m.map { it.key to it.value } }
        return historyEntries(context, locale, retries = 0)
    }

    private fun histogram(entries: List<Pair<String, Int>>): String {
        val bands = IntArray(4)
        for ((_, f) in entries) bands[(f / 64).coerceIn(0, 3)]++
        return "0-63: ${bands[0]}, 64-127: ${bands[1]}, 128-191: ${bands[2]}, 192-255: ${bands[3]}"
    }

    /** A line in `swipe_vocab.log` next to the swipe results, while the swipe results log is on: what the swipe
     *  vocabularies were built from, to check on the phone. Nothing leaves the phone. */
    private fun logVocab(line: String) {
        if (!SwipeMetrics.isEnabled()) return
        val context = Settings.getCurrentContext() ?: return
        try {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "swipe_vocab.log")
                .appendText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(java.util.Date()) + "  $line\n")
        } catch (t: Throwable) {
            Log.w(TAG, "could not write the swipe vocabulary log", t)
        }
    }

    // ---- disk cache ----

    private class DiskCache(val header: String, val entries: List<Pair<String, Int>>)

    /** Identity of the inputs that make a cached word list valid. */
    private fun cacheHeader(dictFile: File?, blockOffensive: Boolean): String =
        if (dictFile == null) "v$CACHE_VERSION|cap=$MAX_WORDS|offensive=$blockOffensive|dict=none"
        else "v$CACHE_VERSION|cap=$MAX_WORDS|offensive=$blockOffensive" +
                "|dict=${dictFile.absolutePath}|size=${dictFile.length()}|mtime=${dictFile.lastModified()}"

    private fun readCache(file: File): DiskCache? {
        if (!file.isFile) return null
        return try {
            file.bufferedReader().useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext()) return@useLines null
                val header = it.next()
                if (!header.startsWith("v$CACHE_VERSION|")) return@useLines null
                val entries = ArrayList<Pair<String, Int>>(MAX_WORDS)
                while (it.hasNext()) {
                    val line = it.next()
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val freq = line.substring(tab + 1).toIntOrNull() ?: continue
                    entries.add(line.substring(0, tab) to freq)
                }
                if (entries.isEmpty()) null else DiskCache(header, entries)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read vocab cache ${file.name}", t)
            null
        }
    }

    private fun writeCache(file: File, header: String, entries: List<Pair<String, Int>>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(header)
                w.write("\n")
                for ((word, freq) in entries) {
                    w.write(word)
                    w.write("\t")
                    w.write(freq.toString())
                    w.write("\n")
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not write vocab cache ${file.name}", t)
        }
    }

    /**
     * The main dictionary file the keyboard uses for [locale]: prefer a user-provided
     * dict in the locale's cache dir, then the extracted internal one, else extract the
     * bundled assets dictionary.
     */
    private fun findMainDictFile(context: Context, locale: Locale): File? {
        val cached = DictionaryInfoUtils.getCachedDictsForLocale(locale, context)
            .filter { it.name.startsWith(DictionaryInfoUtils.DEFAULT_MAIN_DICT) && it.name.endsWith(".dict") }
        cached.firstOrNull { it.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }?.let { return it }
        cached.firstOrNull()?.let { return it }
        // not cached yet: extract the assets dict for this language, like DictionaryFactory does
        val assetsDicts = DictionaryInfoUtils.getAssetsDictionaryList(context).orEmpty()
        val match = assetsDicts.firstOrNull {
            it.startsWith(DictionaryInfoUtils.DEFAULT_MAIN_DICT) &&
                    it.substringAfter("_").substringBefore(".dict").constructLocale().language == locale.language
        } ?: return null
        return DictionaryInfoUtils.extractAssetsDictionary(match, locale, context)
    }

    /** How many words a dictionary has ([total]) and how many of them a swipe could use ([usable]). */
    private class DictCounts(val total: Int, val usable: Int)

    /**
     * Once per language (2026-10-06): the full dictionary's size next to the [MAX_WORDS] the swipe list keeps, in the
     * logs, kept in "<key>.size" next to the cache so it's counted only once. Not on every start: [count] may walk the
     * whole dictionary, which only a rebuild does otherwise. Runs on the language's build thread after its list is
     * served, so no swipe waits for it.
     */
    private fun logDictionarySizeOnce(cacheFile: File, key: String, count: () -> DictCounts?) {
        val marker = File(cacheFile.parentFile, "$key.size")
        if (marker.exists()) return
        val counts = try { count() } catch (t: Throwable) { Log.w(TAG, "could not count the dictionary for $key", t); null } ?: return
        val line = "full dictionary for $key: ${counts.total} words, ${counts.usable} usable for swiping; the swipe list keeps the top $MAX_WORDS"
        Log.i(TAG, line)
        logVocab(line)
        try { marker.writeText("${counts.total}\t${counts.usable}\n") } catch (t: Throwable) { Log.w(TAG, "could not note the dictionary size", t) }
    }

    private fun addWordsFromDict(file: File, locale: Locale, blockOffensive: Boolean, words: MutableMap<String, Int>): DictCounts? {
        val dict = BinaryDictionary(file.absolutePath, 0, file.length(), false, locale, Dictionary.TYPE_MAIN, false)
        var total = 0
        try {
            if (!dict.isValidDictionary) return null
            var token = 0
            do {
                val result = dict.getNextWordProperty(token)
                val wp = result.mWordProperty ?: break
                val word = wp.mWord
                if (word != null) total++
                if (word != null && !wp.mIsNotAWord
                    && wp.probability >= MIN_PROBABILITY
                    && !(wp.mIsPossiblyOffensive && blockOffensive)
                    && isDecodableWord(word)
                ) {
                    words.merge(word, wp.probability.coerceAtMost(255)) { a, b -> maxOf(a, b) }
                }
                token = result.mNextToken
            } while (token != 0)
        } finally {
            dict.close()
        }
        return DictCounts(total, words.size)
    }

    /**
     * Only words the sokgraph builder can route over keys are useful in the trie:
     * letters, plus apostrophes (gestured via the period key, OG-Swype style).
     * Hyphenated words stay excluded for now.
     */
    private fun isDecodableWord(word: String): Boolean =
        word.length in 1..24
                && word.all { Character.isLetter(it) || it == '\'' || it == '’' }
                && word.any { Character.isLetter(it) }
}

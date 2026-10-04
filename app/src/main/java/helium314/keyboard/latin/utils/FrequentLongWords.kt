// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.SystemClock
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Words the user types often that the engine can't rank early: long ones and addresses. The native scoring
 * charges every completed character, so "someone.long.name@example.com" only wins after several letters no
 * matter how often it was typed. These are matched by prefix instead and get an early slot in the strip.
 *
 * Per locale, read from the user history dictionary (level = how often typed, decays), refreshed in the
 * background at most every [REFRESH_MS]; the first lookup after a start returns nothing.
 */
object FrequentLongWords {
    private const val TAG = "FrequentLongWords"
    private const val MIN_LENGTH = 8
    private const val MIN_PROBABILITY = 40 // ~ level 3 of the forgetting curve: typed about three times
    private const val REFRESH_MS = 60_000L
    const val MAX_IN_STRIP = 2
    private const val READ_ATTEMPTS = 10
    private const val READ_RETRY_DELAY_MS = 300L

    private class Entry(val word: String, val lower: String, val probability: Int, val dict: Dictionary)
    private class Cache(val entries: List<Entry>, val time: Long)

    private val caches = ConcurrentHashMap<String, Cache>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    // removed words (lower case) -> when: a re-read that was already running when a word was removed leaves it out
    private val forgotten = ConcurrentHashMap<String, Long>()

    private fun qualifies(word: String) = word.length >= MIN_LENGTH || word.contains('@') || word.contains('.')

    /** Frequent long words of [locales] starting with [typed] (case-insensitive), most often typed first. */
    fun matching(context: Context, locales: List<Locale>, typed: String): List<SuggestedWordInfo> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val now = SystemClock.elapsedRealtime()
        val found = ArrayList<Entry>()
        for (locale in locales) {
            val key = locale.toLanguageTag()
            val cache = caches[key]
            if (cache == null || now - cache.time > REFRESH_MS) refreshAsync(context, locale, key)
            cache?.entries?.filterTo(found) { it.lower.startsWith(lower) && it.lower != lower }
        }
        return found.sortedByDescending { it.probability }.take(MAX_IN_STRIP).map {
            SuggestedWordInfo(it.word, "", it.probability, SuggestedWordInfo.KIND_CORRECTION, it.dict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }

    fun clear() = caches.clear()

    /** The word was removed (long-press; its learned uses are gone too): out of the lists until it's frequent again. */
    fun forget(word: String) {
        forgotten[word.lowercase()] = SystemClock.elapsedRealtime()
        for ((key, cache) in caches) caches[key] = Cache(cache.entries.filterNot { it.lower == word.lowercase() }, cache.time)
    }

    private fun refreshAsync(context: Context, locale: Locale, key: String) {
        if (!refreshing.add(key)) return
        val started = SystemClock.elapsedRealtime()
        Thread({
            try {
                val history = PersonalizationHelper.getUserHistoryDictionary(context, locale)
                // the dump gives up after 100 ms and answers with nothing, which a big store misses on the first tries
                var props = history.wordPropertiesForSyncing
                var attempts = 0
                while (props.isEmpty() && attempts++ < READ_ATTEMPTS) {
                    Thread.sleep(READ_RETRY_DELAY_MS)
                    props = history.wordPropertiesForSyncing
                }
                if (props.isEmpty()) {
                    Log.i(TAG, "user history for $key still empty after $attempts attempts, will retry later")
                    return@Thread
                }
                val entries = props.mapNotNull { wp ->
                    val word = wp.mWord ?: return@mapNotNull null
                    if (wp.probability < MIN_PROBABILITY || !qualifies(word)) null
                    else Entry(word, word.lowercase(), wp.probability, history)
                }.distinctBy { it.word }
                    // (removed while this ran: its read may predate the removal)
                    .filterNot { (forgotten[it.lower] ?: Long.MIN_VALUE) >= started }
                caches[key] = Cache(entries, SystemClock.elapsedRealtime())
                // counts only: the words are the user's own and never go to the log
                Log.d(TAG, "$key: ${entries.size} frequent long words of ${props.size} history words (${props.distinctBy { it.mWord }.size} distinct)")
            } catch (t: Throwable) {
                Log.w(TAG, "could not read user history for $key", t)
            } finally {
                refreshing.remove(key)
            }
        }, "FrequentLongWords-$key").start()
    }
}

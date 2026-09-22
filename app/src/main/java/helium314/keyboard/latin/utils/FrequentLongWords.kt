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
 * charges every completed character, so "mailer.rahul.jain@gmail.com" only wins after several letters no
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

    private class Entry(val word: String, val lower: String, val probability: Int, val dict: Dictionary)
    private class Cache(val entries: List<Entry>, val time: Long)

    private val caches = ConcurrentHashMap<String, Cache>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

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

    private fun refreshAsync(context: Context, locale: Locale, key: String) {
        if (!refreshing.add(key)) return
        Thread({
            try {
                val history = PersonalizationHelper.getUserHistoryDictionary(context, locale)
                val props = history.wordPropertiesForSyncing
                // the dictionary loads asynchronously and answers with nothing until then: don't cache that, ask again next time
                if (props.isEmpty()) {
                    Log.i(TAG, "user history for $key empty (not loaded yet?), will retry")
                    return@Thread
                }
                val entries = props.mapNotNull { wp ->
                    val word = wp.mWord ?: return@mapNotNull null
                    if (wp.probability < MIN_PROBABILITY || !qualifies(word)) null
                    else Entry(word, word.lowercase(), wp.probability, history)
                }
                caches[key] = Cache(entries, SystemClock.elapsedRealtime())
                Log.i(TAG, "$key: ${entries.size} frequent long words of ${props.size} history words, " +
                        "top: " + entries.sortedByDescending { it.probability }.take(5).joinToString { "${it.word}=${it.probability}" })
            } catch (t: Throwable) {
                Log.w(TAG, "could not read user history for $key", t)
            } finally {
                refreshing.remove(key)
            }
        }, "FrequentLongWords-$key").start()
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.utils.ScriptUtils

/*
 * Learned words as plain entries, for whatever puts stores together: the move to one store per script, a backup restore,
 * and the keyboards' own stores merged when they share them again (see LearnedStores). Reading and writing the native
 * stores is LearnedStoreIo's; everything here is plain Kotlin.
 */

/** A learned entry of a store: [word] after [context] (the words before it, nearest first; empty: the word itself), with
 *  its stored [count] and last use [time] (seconds). */
class LearnedEntry(val word: String, val context: List<String>, val count: Int, val time: Int) {
    /** The entry's identity in a store: the word and the words before it. */
    val key: List<String> get() = listOf(word) + context
    val isWord get() = context.isEmpty()

    override fun toString() = "${context.reversed().joinToString(" ")} $word: $count @$time".trim()
    companion object { const val SENTENCE_START = "\u0000" } // (no word contains it)
}

/** Every entry of a store's dump (the words, and the word pairs and longer each one starts). */
fun entriesOf(words: Iterable<WordProperty>): List<LearnedEntry> {
    fun contextOf(c: NgramContext) = (1..c.prevWordCount).map { n ->
        if (c.isNthPrevWordBeginningOfSentence(n)) LearnedEntry.SENTENCE_START else c.getNthPrevWord(n)?.toString() ?: ""
    }
    val entries = ArrayList<LearnedEntry>()
    for (wp in words) {
        // a sentence start isn't a word of its own: the pairs it starts count it
        if (!wp.mIsBeginningOfSentence) {
            val info = wp.mProbabilityInfo
            entries.add(LearnedEntry(wp.mWord, emptyList(), info.mCount, info.mTimestamp))
        }
        for (ngram in wp.mNgrams.orEmpty()) {
            val info = ngram.mTargetWord.mProbabilityInfo
            entries.add(LearnedEntry(ngram.mTargetWord.mWord, contextOf(ngram.mNgramContext), info.mCount, info.mTimestamp))
        }
    }
    return entries
}

/** The [NgramContext] an entry's [LearnedEntry.context] stands for. */
fun ngramContextOf(context: List<String>): NgramContext =
    if (context.isEmpty()) NgramContext.EMPTY_PREV_WORDS_INFO
    else NgramContext(*context.map {
        if (it == LearnedEntry.SENTENCE_START) NgramContext.WordInfo.BEGINNING_OF_SENTENCE_WORD_INFO else NgramContext.WordInfo(it)
    }.toTypedArray())

/**
 * Stores put together where a word was learned in several of them independently (the languages' stores of before, a
 * backup and the phone): the counts of a word, and of a word pair, add up; the last use is the latest. A word learned
 * in a store where it wasn't a dictionary word was stored at 0 by its first use, so a word new to two languages comes
 * out one use short; that's all it can be off by.
 */
fun mergeAdding(stores: List<List<LearnedEntry>>): List<LearnedEntry> = merge(stores) { a, b -> a + b }

/**
 * Stores put together that are copies of one store learned on since (the keyboards' own stores, each started from the
 * shared one): adding would count what they had in common twice, so each word and word pair keeps its highest count;
 * the last use is the latest.
 */
fun mergeHighest(stores: List<List<LearnedEntry>>): List<LearnedEntry> = merge(stores) { a, b -> maxOf(a, b) }

private fun merge(stores: List<List<LearnedEntry>>, counts: (Int, Int) -> Int): List<LearnedEntry> {
    val byKey = LinkedHashMap<List<String>, LearnedEntry>()
    for (store in stores) for (e in store) {
        val old = byKey[e.key]
        byKey[e.key] = if (old == null) e else LearnedEntry(e.word, e.context, counts(old.count, e.count), maxOf(old.time, e.time))
    }
    return byKey.values.toList()
}

/** [entries] by the script of their word (what a word pair goes by too); [fallback] for a word without letters. */
fun byScript(entries: List<LearnedEntry>, fallback: String): Map<String, List<LearnedEntry>> =
    entries.groupBy { ScriptUtils.scriptOfWord(it.word, fallback) }

/**
 * The count each of [entries] replays: one use of a word after some words also counted every shorter entry ending in it
 * (the pair counts the word, 3 words count the pair), so each replays its stored count minus those of the entries one
 * word longer that contain it. A word's stored count already holds its first use (a word of no dictionary is stored at
 * 0 by it), so a word replayed with its count, as a dictionary word, comes out with the same count.
 */
fun ownCounts(entries: List<LearnedEntry>): IntArray {
    val index = HashMap<List<String>, Int>()
    entries.forEachIndexed { i, e -> index[e.key] = i }
    val own = IntArray(entries.size) { entries[it].count }
    for (e in entries) {
        if (e.context.isEmpty()) continue
        val shorter = index[listOf(e.word) + e.context.dropLast(1)] ?: continue
        own[shorter] -= e.count
    }
    return IntArray(own.size) { own[it].coerceAtLeast(0) }
}

/**
 * Replays [entries] into an empty store through [update] (context, word, count, time; a negative count takes uses back
 * from the word alone), so it holds them with their counts and last uses:
 * - every word first at count 0: a pair is only stored when the store knows the word before it
 * - then each entry with its own count ([ownCounts]), oldest first: a replay also sets the time of the shorter entries
 *   it counts (the word of a pair), whose own last use is never older, so replayed after it, that one stays
 * - a word whose pairs counted it more often than its own count says (a use taken back from the word alone, or the
 *   highest counts of several stores) gets the difference taken back.
 */
fun replay(entries: List<LearnedEntry>, update: (context: List<String>, word: String, count: Int, time: Int) -> Unit) {
    for (e in entries) if (e.isWord) update(emptyList(), e.word, 0, e.time)
    val own = ownCounts(entries)
    for (i in entries.indices.sortedBy { entries[it].time }) update(entries[i].context, entries[i].word, own[i], entries[i].time)
    // what the replays gave each word: its own count and that of every longer entry of it (each counts the word too)
    val replayed = HashMap<String, Int>()
    entries.forEachIndexed { i, e -> replayed[e.word] = (replayed[e.word] ?: 0) + own[i] }
    for (e in entries) {
        if (!e.isWord) continue
        val excess = (replayed[e.word] ?: 0) - e.count
        if (excess > 0) update(emptyList(), e.word, -excess, 0)
    }
}

/** Words (entries of a word alone) and word pairs and longer in [entries]: for the log, which never gets words. */
fun countsOf(entries: List<LearnedEntry>): String {
    val words = entries.count { it.isWord }
    return "$words words, ${entries.size - words} pairs+"
}

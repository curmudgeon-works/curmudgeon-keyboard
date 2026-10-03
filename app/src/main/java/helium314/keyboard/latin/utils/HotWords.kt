// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import java.util.ArrayDeque

/**
 * Words typed several times recently. Typing a word [HOT_COUNT] times within the last [WINDOW] committed words
 * makes it "hot": it is offered right after the typed word as soon as the typed prefix matches, no matter what
 * the dictionaries think of it. It cools down when it falls out of the window.
 */
object HotWords {
    private const val WINDOW = 50
    private const val HOT_COUNT = 3
    const val MAX_IN_STRIP = 2

    private val recent = ArrayDeque<String>(WINDOW)

    /** Called for every word the user committed (typed or picked), as committed: counted case-insensitively, but the
     *  capitals are kept (a name, "iPhone"). */
    @Synchronized
    fun onWordCommitted(word: String) {
        if (word.length < 2) return
        if (recent.size >= WINDOW) recent.pollFirst()
        recent.addLast(word)
    }

    /** How a hot word is written: in lower case if it was ever typed that way (a capital then only came from the
     *  sentence start), else as typed last (a name keeps its capital); then capitalised like [typed] (sentence start,
     *  shift, caps lock). */
    private fun spelled(forms: List<String>, typed: String): String {
        val natural = forms.firstOrNull { it == it.lowercase() } ?: forms.last()
        return when {
            // (2+ letters: "I'" or "U." is one capital, not caps lock)
            typed.count { it.isLetter() } >= 2 && typed.none { it.isLowerCase() } -> natural.uppercase()
            typed.first().isUpperCase() -> natural.replaceFirstChar { it.uppercaseChar() }
            else -> natural
        }
    }

    /** Hot words starting with [typed] (case-insensitive), most recently typed first. */
    @Synchronized
    fun matching(typed: String, sourceDict: Dictionary): List<SuggestedWordInfo> {
        if (typed.isEmpty() || recent.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val counts = HashMap<String, Int>()
        val forms = HashMap<String, MutableList<String>>() // lower case -> the ways it was written, oldest first
        for (w in recent) {
            val l = w.lowercase()
            if (!l.startsWith(lower)) continue
            counts[l] = (counts[l] ?: 0) + 1
            forms.getOrPut(l) { ArrayList() }.add(w)
        }
        val hot = counts.filterValues { it >= HOT_COUNT }.keys.filter { it != lower }
        if (hot.isEmpty()) return emptyList()
        // most recent first: walk the window from the end
        val ordered = ArrayList<String>()
        for (w in recent.descendingIterator()) { val l = w.lowercase(); if (l in hot && l !in ordered) ordered.add(l) }
        return ordered.take(MAX_IN_STRIP).map { spelled(forms.getValue(it), typed) }.map {
            SuggestedWordInfo(it, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_CORRECTION, sourceDict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }

    @Synchronized
    fun clear() = recent.clear()

    /** The word was removed (long-press): its recent uses no longer count; typed again, it starts over. */
    @Synchronized
    fun forget(word: String) { recent.removeAll { it.equals(word, ignoreCase = true) } }
}

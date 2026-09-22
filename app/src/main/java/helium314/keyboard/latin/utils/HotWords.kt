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

    /** Called for every word the user committed (typed or picked). Case-insensitive. */
    @Synchronized
    fun onWordCommitted(word: String) {
        if (word.length < 2) return
        if (recent.size >= WINDOW) recent.pollFirst()
        recent.addLast(word.lowercase())
    }

    /** Hot words starting with [typed] (case-insensitive), most recently typed first. */
    @Synchronized
    fun matching(typed: String, sourceDict: Dictionary): List<SuggestedWordInfo> {
        if (typed.isEmpty() || recent.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val counts = HashMap<String, Int>()
        for (w in recent) if (w.startsWith(lower)) counts[w] = (counts[w] ?: 0) + 1
        val hot = counts.filterValues { it >= HOT_COUNT }.keys.filter { it != lower }
        if (hot.isEmpty()) return emptyList()
        // most recent first: walk the window from the end
        val ordered = ArrayList<String>()
        for (w in recent.descendingIterator()) if (w in hot && w !in ordered) ordered.add(w)
        return ordered.take(MAX_IN_STRIP).map {
            SuggestedWordInfo(it, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_CORRECTION, sourceDict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        }
    }

    @Synchronized
    fun clear() = recent.clear()
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

/**
 * Plain-language search over the settings, e.g. "make the suggestions smaller" or "stop the buzzing".
 * No language model, just cheap approximations: filler words are dropped, words match by beginning,
 * common stem or a single typo, and a small list of synonym groups covers the words people use
 * for things the settings call something else.
 */
object SettingsSearch {
    private const val WEIGHT_TITLE = 3f
    private const val WEIGHT_DESCRIPTION = 1.5f
    private const val WEIGHT_KEY = 1f
    private const val SYNONYM_FACTOR = 0.8f

    private val fillerWords = hashSetOf(
        "a", "an", "the", "to", "of", "in", "on", "at", "for", "from", "with", "and", "or", "but", "is", "are", "be",
        "it", "its", "this", "that", "i", "my", "me", "you", "your", "how", "what", "where", "why", "when", "can", "do",
        "does", "dont", "want", "need", "like", "please", "make", "get", "set", "setting", "settings", "option", "options",
        "change", "turn", "switch", "enable", "disable", "stop", "off", "use", "using", "keyboard", "so", "too", "more", "less",
    )

    // a query word also finds every other word of its group, slightly weaker than a direct match
    private val synonymGroups = listOf(
        listOf("swipe", "swiping", "glide", "gesture", "slide", "trace"),
        listOf("suggestion", "prediction", "autocomplete", "candidate", "completion"),
        listOf("strip", "bar"),
        listOf("capital", "caps", "uppercase", "shift", "capitalize", "capitalization"),
        listOf("vibrate", "vibration", "haptic", "buzz", "buzzing"),
        listOf("sound", "audio", "click", "noise", "volume", "beep"),
        listOf("autocorrect", "autocorrection", "correction", "correct", "typo", "mistake", "fix"),
        listOf("symbol", "special", "punctuation", "character", "hint"),
        listOf("theme", "color", "colour", "dark", "light", "night", "look", "appearance", "style"),
        listOf("size", "height", "scale", "big", "bigger", "large", "larger", "small", "smaller", "tiny"),
        listOf("spacing", "space", "gap", "padding", "tight", "compact", "dense", "pack"),
        listOf("language", "locale", "multilingual", "dictionary", "hindi", "hinglish"),
        listOf("emoji", "smiley", "emoticon"),
        listOf("clipboard", "copy", "paste", "clip"),
        listOf("number", "digit", "numeric"),
        listOf("popup", "longpress", "long", "hold", "press"),
        listOf("font", "text", "typeface", "bold", "italic"),
        listOf("delete", "backspace", "erase"),
        listOf("incognito", "private", "privacy", "learn", "history", "remember"),
        listOf("backup", "restore", "export", "import"),
        listOf("split", "landscape", "onehanded", "one-handed", "floating"),
    )
    private val synonyms: Map<String, List<String>> = HashMap<String, List<String>>().apply {
        synonymGroups.forEach { group -> group.forEach { word -> put(word, (get(word) ?: emptyList()) + (group - word)) } }
    }

    private class Words(val title: List<String>, val description: List<String>, val key: List<String>)
    // per Setting object: they are recreated when the app language changes, and with them the titles
    private val wordsOfSetting = java.util.WeakHashMap<Setting, Words>()

    /** @return [settings] that match [query], best match first */
    fun search(query: String, settings: List<Setting>): List<Setting> {
        val queryWords = splitWords(query).filterNot { it in fillerWords }.ifEmpty { splitWords(query) }
        if (queryWords.isEmpty()) return emptyList()
        return settings.mapNotNull { setting ->
            val words = wordsOfSetting.getOrPut(setting) {
                Words(splitWords(setting.title), splitWords(setting.description ?: ""), splitWords(setting.key))
            }
            val scores = queryWords.map { score(it, words) }
            val matched = scores.count { it > 0f }
            // every word has to find something, except in longer sentences where one may miss
            if (matched == 0 || matched < queryWords.size - queryWords.size / 3) null
            else setting to scores.sum()
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun score(queryWord: String, words: Words): Float {
        val direct = scoreInFields(queryWord, words)
        val viaSynonym = synonyms[queryWord]?.maxOfOrNull { scoreInFields(it, words) } ?: 0f
        return maxOf(direct, viaSynonym * SYNONYM_FACTOR)
    }

    private fun scoreInFields(word: String, words: Words) = maxOf(
        WEIGHT_TITLE * (words.title.maxOfOrNull { similarity(word, it) } ?: 0f),
        WEIGHT_DESCRIPTION * (words.description.maxOfOrNull { similarity(word, it) } ?: 0f),
        WEIGHT_KEY * (words.key.maxOfOrNull { similarity(word, it) } ?: 0f),
    )

    /** 0 for unrelated words, up to 1 for the same word */
    internal fun similarity(queryWord: String, word: String): Float {
        if (queryWord == word) return 1f
        if (word.startsWith(queryWord)) return 0.9f // also what makes search-as-you-type work
        val shorter = minOf(queryWord.length, word.length)
        // same stem: "capitalize" / "capitalization", "suggestions" / "suggested"
        val commonPrefix = queryWord.commonPrefixWith(word).length
        if (commonPrefix >= 4 && commonPrefix >= shorter * 0.7f) return 0.7f
        // typos in the first letter are rare, and without this "sound" finds "round"
        if (shorter >= 5 && queryWord[0] == word[0] && isOneTypoApart(queryWord, word)) return 0.6f
        return 0f
    }

    private fun splitWords(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    // one letter replaced, added, removed, or two neighbours swapped
    private fun isOneTypoApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val (short, long) = if (a.length <= b.length) a to b else b to a
        val start = short.commonPrefixWith(long).length
        if (short.length == long.length) {
            return short.substring(start + 1) == long.substring(start + 1)
                || (start + 1 < short.length && short[start] == long[start + 1] && short[start + 1] == long[start]
                    && short.substring(start + 2) == long.substring(start + 2))
        }
        return short.substring(start) == long.substring(start + 1)
    }
}

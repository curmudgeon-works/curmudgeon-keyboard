// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * The user's rules for the suggestion strip ("Customise suggestions", Others screen). The 1st suggestion is always
 * the engine's best; for each later position a rule can pull a word up from further down the list:
 * - a word of a given language of the keyboard (1 = the main one, then the others in the keyboard's order),
 * - a word with a different first letter than the 1st suggestion,
 * - a word scoring at most a tenth of the 1st suggestion,
 * - the next common word (one of that language's most frequent) of a given language.
 * When nothing further down fits, the position keeps its word. The typed word (and its "keep what I typed" copy)
 * stays where it is and doesn't count as a position.
 *
 * Stored as one string, a rule per position from the 2nd on, comma-separated: "d" (default), "l2" (language 2),
 * "f" (different first letter), "s" (low score), "c1" (common word of language 1).
 */
object SuggestionRules {
    enum class Kind(val code: Char, val hasLanguage: Boolean = false) {
        DEFAULT('d'), LANGUAGE('l', true), OTHER_FIRST_LETTER('f'), LOW_SCORE('s'), COMMON('c', true)
    }

    /** [language] is 1-based, for the kinds that have one. */
    data class Rule(val kind: Kind, val language: Int = 0)

    val DEFAULT = Rule(Kind.DEFAULT)

    fun parse(stored: String?): List<Rule> = stored.orEmpty().split(',').filter { it.isNotBlank() }.map { token ->
        val kind = Kind.entries.firstOrNull { it.code == token[0] } ?: Kind.DEFAULT
        if (kind.hasLanguage) Rule(kind, token.drop(1).toIntOrNull()?.coerceAtLeast(1) ?: 1) else Rule(kind)
    }

    /** Trailing default rules are dropped: they change nothing. */
    fun encode(rules: List<Rule>): String =
        rules.dropLastWhile { it.kind == Kind.DEFAULT }.joinToString(",") { if (it.kind.hasLanguage) "${it.kind.code}${it.language}" else "${it.kind.code}" }

    /**
     * Applies [rules] to [items] (the strip's words, best first) in place. [inLanguage] and [isCommon] take a word
     * and a 1-based language number.
     */
    fun <T> apply(
        items: MutableList<T>, rules: List<Rule>,
        word: (T) -> String, score: (T) -> Int, isTypedWord: (T) -> Boolean,
        inLanguage: (String, Int) -> Boolean, isCommon: (String, Int) -> Boolean,
    ) {
        if (rules.all { it.kind == Kind.DEFAULT }) return
        for ((r, rule) in rules.withIndex()) {
            if (rule.kind == Kind.DEFAULT) continue
            val positions = items.indices.filter { !isTypedWord(items[it]) } // the suggestions, typed word left out
            val slot = r + 1 // positions[1] is the 2nd suggestion
            if (slot >= positions.size) return
            val first = items[positions[0]]
            val matches: (T) -> Boolean = when (rule.kind) {
                Kind.LANGUAGE -> { t -> inLanguage(word(t), rule.language) }
                Kind.OTHER_FIRST_LETTER -> { t -> firstLetter(word(t)) != firstLetter(word(first)) }
                Kind.LOW_SCORE -> { t -> score(first) > 0 && score(t) <= score(first) / 10 }
                Kind.COMMON -> { t -> isCommon(word(t), rule.language) }
                Kind.DEFAULT -> { _ -> true }
            }
            val found = (slot until positions.size).firstOrNull { matches(items[positions[it]]) } ?: continue
            if (found == slot) continue
            items.add(positions[slot], items.removeAt(positions[found]))
        }
    }

    private fun firstLetter(word: String) = if (word.isEmpty()) 0 else Character.toLowerCase(word.codePointAt(0))
}

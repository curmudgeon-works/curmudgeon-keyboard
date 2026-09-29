// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.SuggestionRules.Kind
import helium314.keyboard.latin.SuggestionRules.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** The strip's per-position rules ("Customise suggestions"). */
class SuggestionRulesTest {
    private data class W(val word: String, val score: Int, val typed: Boolean = false, val lang: Int = 1)

    private val common = setOf("the", "then", "they")

    private fun run(items: List<W>, vararg rules: Rule): List<String> {
        val list = items.toMutableList()
        SuggestionRules.apply(list, rules.toList(), { it.word }, { it.score }, { it.typed },
            inLanguage = { w, l -> items.first { it.word == w }.lang == l },
            isCommon = { w, _ -> w in common })
        return list.map { it.word }
    }

    private val strip = listOf(W("thw", 0, typed = true), W("thaw", 1000), W("throw", 900), W("thew", 800),
        W("chaw", 300, lang = 2), W("then", 90), W("they", 50))

    @Test fun `no rules, nothing moves`() {
        assertEquals(strip.map { it.word }, run(strip))
    }

    @Test fun `the first suggestion never moves, the typed word keeps its place`() {
        assertEquals(listOf("thw", "thaw", "chaw", "throw", "thew", "then", "they"), run(strip, Rule(Kind.LANGUAGE, 2)))
    }

    @Test fun `different first letter`() {
        assertEquals(listOf("thw", "thaw", "throw", "chaw", "thew", "then", "they"),
            run(strip, SuggestionRules.DEFAULT, Rule(Kind.OTHER_FIRST_LETTER)))
    }

    @Test fun `low score is a tenth of the first or less`() {
        assertEquals(listOf("thw", "thaw", "then", "throw", "thew", "chaw", "they"), run(strip, Rule(Kind.LOW_SCORE)))
    }

    @Test fun `next common word`() {
        assertEquals(listOf("thw", "thaw", "throw", "then", "thew", "chaw", "they"),
            run(strip, SuggestionRules.DEFAULT, Rule(Kind.COMMON, 1)))
    }

    @Test fun `nothing fits, the position keeps its word`() {
        assertEquals(strip.map { it.word }, run(strip, Rule(Kind.LANGUAGE, 3)))
    }

    @Test fun `positions past the end are ignored`() {
        val short = listOf(W("a", 10), W("b", 1))
        assertEquals(listOf("a", "b"), run(short, SuggestionRules.DEFAULT, Rule(Kind.LOW_SCORE)))
    }

    @Test fun `stored form round-trips, trailing defaults dropped`() {
        val rules = listOf(Rule(Kind.LANGUAGE, 2), SuggestionRules.DEFAULT, Rule(Kind.OTHER_FIRST_LETTER),
            Rule(Kind.LOW_SCORE), Rule(Kind.COMMON, 1), SuggestionRules.DEFAULT)
        assertEquals("l2,d,f,s,c1", SuggestionRules.encode(rules))
        assertEquals(rules.dropLast(1), SuggestionRules.parse("l2,d,f,s,c1"))
        assertEquals(emptyList(), SuggestionRules.parse(""))
    }
}

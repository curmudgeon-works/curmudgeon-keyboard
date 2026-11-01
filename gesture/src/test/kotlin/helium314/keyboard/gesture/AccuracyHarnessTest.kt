// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scorer-vs-scorer accuracy harness (spec M1: "from day one").
 * Runs all three scorers on the same synthetic corpus and reports top-1 / top-4
 * accuracy (Swype showed 4 candidates), overall and per category, clean vs noisy.
 */
class AccuracyHarnessTest {

    private val geometry = QwertyFixture.geometry
    private val vocab = TestVocabulary.vocabulary

    // Categories of tricky cases, all present in TestVocabulary.
    private val categories: Map<String, List<String>> = mapOf(
        "short" to listOf("is", "in", "on", "it", "at", "as", "an", "to", "of", "or", "up", "go", "no", "my"),
        "double" to listOf("loop", "ball", "been", "seen", "good", "look", "book", "school", "less", "all",
            "will", "still", "keep", "too", "see", "free", "feel", "week", "off", "happy",
            "sorry", "little", "letter", "better", "coffee", "summer"),
        "long" to listOf("something", "important", "information", "government", "understand", "keyboard",
            "question", "probably", "beautiful", "computer", "remember", "together", "children",
            "business", "education", "experience", "technology", "everything", "university", "different"),
        "adjacent" to listOf("then", "than", "form", "from", "were", "wore", "hot", "hit", "top", "tip",
            "cat", "car", "pen", "pin", "pan", "sat", "sit", "set", "hear", "heat", "not", "nor"),
        "normal" to listOf("hello", "world", "water", "people", "house", "place", "think", "great",
            "right", "point", "story", "mother", "answer"),
    )

    private val noisySeeds = listOf(1L, 2L, 3L)

    private class Tally {
        var runs = 0
        var top1 = 0
        var top4 = 0
        fun add(rank: Int) { // rank: 0-based position of target, -1 if absent
            runs++
            if (rank == 0) top1++
            if (rank in 0..3) top4++
        }
        val top1Pct get() = if (runs == 0) 0.0 else 100.0 * top1 / runs
        val top4Pct get() = if (runs == 0) 0.0 else 100.0 * top4 / runs
    }

    private class ScorerResult(val name: String) {
        val clean = Tally()
        val noisy = Tally()
        val cleanByCategory = HashMap<String, Tally>()
        val noisyByCategory = HashMap<String, Tally>()
    }

    private fun runHarness(): List<ScorerResult> {
        val scorers: List<Scorer> = listOf(KushlerScorer(), LocationScorer(), HybridScorer())
        val results = scorers.map { ScorerResult(it.name) }
        for ((si, scorer) in scorers.withIndex()) {
            val decoder = GestureDecoder(scorer)
            val res = results[si]
            for ((category, words) in categories) {
                for (word in words) {
                    // clean
                    val cleanPath = SyntheticPathGenerator.idealPath(word, geometry)
                    val cleanRank = rankOf(decoder.decode(cleanPath, geometry, vocab, 10), word)
                    res.clean.add(cleanRank)
                    res.cleanByCategory.getOrPut(category) { Tally() }.add(cleanRank)
                    // noisy
                    for (seed in noisySeeds) {
                        val noisyPath = SyntheticPathGenerator.noisyPath(word, geometry, seed)
                        val noisyRank = rankOf(decoder.decode(noisyPath, geometry, vocab, 10), word)
                        res.noisy.add(noisyRank)
                        res.noisyByCategory.getOrPut(category) { Tally() }.add(noisyRank)
                    }
                }
            }
        }
        return results
    }

    private fun rankOf(results: List<ScoredWord>, word: String): Int =
        results.indexOfFirst { it.word == word }

    @Test
    fun `accuracy harness - all three scorers`() {
        val results = runHarness()

        val sb = StringBuilder("\n=== Gesture decoder accuracy harness ===\n")
        for (r in results) {
            sb.append(String.format(
                "%-8s clean: top1 %5.1f%% top4 %5.1f%% (n=%d) | noisy: top1 %5.1f%% top4 %5.1f%% (n=%d)\n",
                r.name, r.clean.top1Pct, r.clean.top4Pct, r.clean.runs,
                r.noisy.top1Pct, r.noisy.top4Pct, r.noisy.runs))
            for (category in categories.keys) {
                val c = r.cleanByCategory[category]!!
                val n = r.noisyByCategory[category]!!
                sb.append(String.format(
                    "    %-10s clean top1 %5.1f%% top4 %5.1f%% | noisy top1 %5.1f%% top4 %5.1f%%\n",
                    category, c.top1Pct, c.top4Pct, n.top1Pct, n.top4Pct))
            }
        }
        println(sb)

        // Floors chosen after observing actual numbers (asserted slightly below achieved
        // values so the tests stay stable against small tuning changes). Achieved 2026-08-08:
        //   kushler: clean 91.6/100.0, noisy 80.0/99.6
        //   location: clean 94.7/100.0, noisy 94.0/100.0 (2026-09-28)
        //   hybrid:  clean 94.7/100.0, noisy 93.7/100.0 (kushler + location)
        for (r in results) {
            assertTrue(r.clean.top1Pct >= 88.0, "${r.name} clean top1 ${r.clean.top1Pct}")
            assertTrue(r.clean.top4Pct >= 98.0, "${r.name} clean top4 ${r.clean.top4Pct}")
            assertTrue(r.noisy.top1Pct >= 72.0, "${r.name} noisy top1 ${r.noisy.top1Pct}")
            assertTrue(r.noisy.top4Pct >= 95.0, "${r.name} noisy top4 ${r.noisy.top4Pct}")
            // and per category, so a change that trades one kind of word for another shows (review 2026-10-21 L4)
            for (category in categories.keys) {
                val (clean1, noisy1) = categoryFloors.getValue(r.name).getValue(category)
                val c = r.cleanByCategory.getValue(category)
                val n = r.noisyByCategory.getValue(category)
                assertTrue(c.top1Pct >= clean1, "${r.name} $category clean top1 ${c.top1Pct} < $clean1")
                assertTrue(n.top1Pct >= noisy1, "${r.name} $category noisy top1 ${n.top1Pct} < $noisy1")
                assertTrue(c.top4Pct >= CATEGORY_TOP4_FLOOR, "${r.name} $category clean top4 ${c.top4Pct}")
                assertTrue(n.top4Pct >= CATEGORY_TOP4_FLOOR, "${r.name} $category noisy top4 ${n.top4Pct}")
            }
        }
    }

    private companion object {
        /** Every category's top-4 was 98.5% or better (2026-10-24). */
        const val CATEGORY_TOP4_FLOOR = 93.0

        /**
         * Per scorer and category: the clean top-1 floor, then the noisy top-1 floor, each 5 points under the value
         * achieved by the public branch on 2026-10-31 (100 -> 95; the double-letter row was first set from the private build's 2026-10-24 report, which scores 100 there). The categories are small (14 to 26 words, three noisy seeds each), so one
         * more clean miss in "short" is a 7-point fall: these are meant to catch a change, not to leave room for one.
         *   kushler:  short 100.0/85.7, double 92.3/80.8, long 100.0/100.0, adjacent 81.8/62.1, normal 84.6/71.8
         *   location: short 100.0/100.0, double 88.5/91.0, long 100.0/100.0, adjacent 90.9/84.8, normal 100.0/100.0
         *   hybrid:   short 100.0/100.0, double 92.3/91.0, long 100.0/100.0, adjacent 95.5/86.4, normal 100.0/94.9
         */
        val categoryFloors: Map<String, Map<String, Pair<Double, Double>>> = mapOf(
            "kushler" to mapOf("short" to (95.0 to 80.0), "double" to (87.0 to 78.0), "long" to (95.0 to 95.0),
                "adjacent" to (76.0 to 57.0), "normal" to (79.0 to 66.0)),
            "location" to mapOf("short" to (95.0 to 95.0), "double" to (83.0 to 86.0), "long" to (95.0 to 95.0),
                "adjacent" to (85.0 to 79.0), "normal" to (95.0 to 95.0)),
            "hybrid" to mapOf("short" to (95.0 to 95.0), "double" to (87.0 to 89.0), "long" to (95.0 to 95.0),
                "adjacent" to (90.0 to 81.0), "normal" to (95.0 to 89.0)),
        )
    }
}

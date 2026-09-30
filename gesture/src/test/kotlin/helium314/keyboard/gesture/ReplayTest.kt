// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import java.io.File
import kotlin.test.Test

/**
 * Replays swipes recorded on a phone (the keyboard's gesture_corpus.jsonl, flattened to corpus.tsv) against the
 * real dictionaries, for several decoder configurations, and prints top-1 / top-3 accuracy plus the misses.
 *
 * The data is personal typing, so it lives outside the repository: $GESTURE_REPLAY_DIR or
 * ~/Documents/curmudgeon/swipe-replay, holding corpus.tsv, en-US.vocab.tsv and hi-Latn.vocab.tsv.
 * The test is silent when that directory is missing. Run with
 *   ./gradlew :gesture:test --tests '*ReplayTest*' -i
 */
class ReplayTest {

    private class Swipe(
        val id: Int, val locale: String, val labelSource: String, val label: String, val phoneGot: String,
        val geometry: KeyboardGeometry, val points: List<GesturePoint>,
        /** who swiped it, where the corpus says (a 9th column: the FUTO import's sessions); else empty */
        val session: String = "",
    )

    /**
     * [decode] returns the ranked words a variant would put on the strip. With [learnedFreq] the swipes run in
     * time order and every word the phone committed before (kept or picked) is added to the vocabulary at that
     * frequency first, the way the phone's learned words would have been (a flat model: one level, plus boost).
     */
    private class Variant(val name: String, val decode: (Swipe, Vocabulary) -> List<ScoredWord>, val learnedFreq: Int = 0)

    private class Tally {
        var runs = 0; var top1 = 0; var top3 = 0; var top8 = 0
        fun add(rank: Int) { runs++; if (rank == 0) top1++; if (rank in 0..2) top3++; if (rank in 0..7) top8++ }
        fun pct(n: Int) = if (runs == 0) 0.0 else 100.0 * n / runs
        override fun toString() = String.format("top1 %5.1f%%  top3 %5.1f%%  top8 %5.1f%%  (n=%d)", pct(top1), pct(top3), pct(top8), runs)
    }

    private fun dataDir(): File? {
        val dir = File(System.getenv("GESTURE_REPLAY_DIR") ?: (System.getProperty("user.home") + "/Documents/curmudgeon/swipe-replay"))
        return if (File(dir, "corpus.tsv").isFile) dir else null
    }

    /**
     * The phone's merged vocabulary: every language of the keyboard, scaled by its priority factor. With
     * [normalize], each further language's list is put on the first language's frequency scale by rank first
     * (dictionaries from different sources use different scales).
     */
    private fun vocabulary(dir: File, factors: Map<String, Float>, normalize: Boolean = normalizeScales): Vocabulary {
        val vocab = Vocabulary(emptyList())
        var reference: List<Int>? = null
        for ((locale, factor) in factors) {
            val entries = File(dir, "$locale.vocab.tsv").readLines().map { line -> val (word, freq) = line.split('\t'); word to freq.toInt() }
                .sortedByDescending { it.second }
            val freqs = entries.map { it.second }
            val ref = reference
            entries.forEachIndexed { i, (word, freq) ->
                val f = if (normalize && ref != null) ref[i.coerceAtMost(ref.lastIndex)] else freq
                vocab.add(word, (f * factor).toInt().coerceAtLeast(1))
            }
            if (reference == null) reference = freqs
        }
        return vocab
    }

    private val normalizeScales = System.getenv("GESTURE_REPLAY_NORMALIZE") != "0"
    private val hiFactor = System.getenv("GESTURE_REPLAY_HI_FACTOR")?.toFloatOrNull() ?: 0.85f

    private fun corpus(dir: File): List<Swipe> = File(dir, "corpus.tsv").readLines().map { line ->
        val f = line.split('\t')
        val keys = f[6].split(';').map { k ->
            val p = k.split(':')
            KeyInfo(p[0][0], p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
        }
        val points = f[7].split(';').map { p -> val v = p.split(','); GesturePoint(v[0].toFloat(), v[1].toFloat(), v[2].toLong()) }
        Swipe(f[0].toInt(), f[1], f[2], f[3], f[4], KeyboardGeometry(keys), points, f.getOrElse(8) { "" })
    }

    /**
     * A decoder from a spec like "k=0.4 slow=1": the phone's defaults with the named settings changed.
     * k = inflection scorer's share of the blend (the rest is the location scorer), slow = slowdown soft weight,
     * turn = turn confidence scale, ends = location scorer's end emphasis, samples = its resampled points,
     * endR = start/end key radius, corridor = how far off the path a letter may be, stop = stop detection (dt factor),
     * skip = penalty for an inflection no letter uses, cap = candidates scored, reach / shortR / shortL = the long-reach
     * corner rule (reach length, allowed shortfall reaching right / left).
     */
    private fun decoder(spec: String): GestureDecoder {
        val v = spec.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toFloat() }
        fun f(key: String, default: Float) = v[key] ?: default
        val k = f("k", 0.4f)
        return GestureDecoder(
            HybridScorer(
                KushlerScorer(KushlerConfig(slowEmphasis = f("slow", 0.5f), skipInflectionPenalty = f("skip", 0.9f))),
                LocationScorer(LocationConfig(sampleCount = f("samples", 40f).toInt(), endpointEmphasis = f("ends", 2f))),
                kushlerWeight = k, locationWeight = 1f - k,
            ),
            DecoderConfig(endpointRadiusKeyWidths = f("endR", 1.6f), corridorRadiusKeyWidths = f("corridor", 1.4f),
                maxCandidates = f("cap", 4096f).toInt(), longReachKeyWidths = f("reach", 4f),
                reachShortfallRightKeyWidths = f("shortR", 0.6f), reachShortfallLeftKeyWidths = f("shortL", 0.4f)),
            GesturePreprocessor(PreprocessorConfig(turnConfidenceScale = f("turn", 1f), stopDtFactor = f("stop", 2.5f))),
        )
    }

    // the phone's settings, then one setting at a time
    private val sweep = listOf(
        "phone", "k=0", "k=1", "k=0.3", "k=0.5", "k=0.6", "k=0.7",
        "slow=0", "slow=1", "slow=1.5", "turn=0.7", "turn=1.3",
        "ends=1", "ends=3", "ends=4", "samples=24", "samples=64",
        "endR=1.3", "endR=2", "corridor=1.2", "corridor=1.8", "stop=3.5", "skip=0.6", "skip=1.2",
    )

    private fun variants(): List<Variant> {
        fun plain(d: GestureDecoder): (Swipe, Vocabulary) -> List<ScoredWord> = { s, v -> d.decode(s.points, s.geometry, v, 10) }
        // GESTURE_REPLAY_SPECS="k=0.4 slow=1;k=0.4": these instead of the sweep
        val specs = System.getenv("GESTURE_REPLAY_SPECS")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: sweep
        val history = (System.getenv("GESTURE_REPLAY_HISTORY") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        return specs.flatMap { spec ->
            listOf(Variant(spec, plain(decoder(spec)))) +
                history.map { Variant("$spec, learned words at $it", plain(decoder(spec)), learnedFreq = it) }
        }
    }

    /**
     * GESTURE_REPLAY_BREAKDOWN=1, for a watched swipe the phone's settings get wrong: the label next to the winner,
     * with its rank, the final score, each scorer's own score (all lower = better) and the frequency factor.
     */
    private fun breakdown(s: Swipe, vocab: Vocabulary): List<String> {
        val kushler = KushlerScorer(KushlerConfig(slowEmphasis = 0.5f, skipInflectionPenalty = 0.9f))
        val location = LocationScorer(LocationConfig(sampleCount = 40, endpointEmphasis = 2f))
        val hybrid = HybridScorer(kushler, location, kushlerWeight = 0.4f, locationWeight = 0.6f)
        val all = GestureDecoder(hybrid, preprocessor = GesturePreprocessor(PreprocessorConfig(stopDtFactor = 2.5f)))
            .decodeWithScorers(s.points, s.geometry, vocab, listOf(hybrid, kushler, location), 5000)
        val ranked = all[hybrid.name].orEmpty()
        if (ranked.isEmpty() || ranked[0].word.equals(s.label, ignoreCase = true)) return emptyList()
        fun row(word: String): String {
            val rank = ranked.indexOfFirst { it.word.equals(word, ignoreCase = true) }
            if (rank < 0) {
                // which filter threw it out: loosen one at a time and see which lets the word in
                val loosened = listOf(
                    "start/end radius" to DecoderConfig(endpointRadiusKeyWidths = 4f),
                    "corridor" to DecoderConfig(corridorRadiusKeyWidths = 3f),
                    "letter order along the path" to DecoderConfig(progressSlackKeyWidths = 20f),
                    "length band" to DecoderConfig(lengthRatioBand = 10f),
                    "minimum letters" to DecoderConfig(minLetterSlack = 50),
                ).filter { (_, config) ->
                    GestureDecoder(hybrid, config, GesturePreprocessor(PreprocessorConfig(stopDtFactor = 2.5f)))
                        .decode(s.points, s.geometry, vocab, 5000).any { it.word.equals(word, ignoreCase = true) }
                }.map { it.first }
                // the decoder's own letter-by-letter match on its preprocessed path (same rule as matchAlongPath)
                val gesture = GesturePreprocessor(PreprocessorConfig(stopDtFactor = 2.5f)).preprocess(s.points, s.geometry)
                val kw = s.geometry.keyWidth
                val trace = StringBuilder()
                var arc = 0f
                for (c in word.lowercase()) {
                    val key = s.geometry.keyForWordChar(c) ?: continue
                    val (d, newArc) = gesture.nearestArcPositionFrom(key.centerX, key.centerY, (arc - 1.2f * kw).coerceAtLeast(0f), 1.4f * kw)
                    trace.append(String.format(" %c %.2f@%.1f", c, d / kw, newArc / kw))
                    if (d > 1.4f * kw) { trace.append(" <- FAILS (limit 1.40)"); break }
                    arc = newArc
                }
                return String.format("      %-16s not a candidate; let in by loosening: %s\n        letter distance@position along the path (key widths), path %.1f long:%s", word,
                    if (vocab.contains(word)) loosened.ifEmpty { listOf("no single filter") }.joinToString() else "NOT IN VOCABULARY",
                    gesture.pathLength / kw, trace)
            }
            val h = ranked[rank]
            fun raw(name: String) = all[name]?.firstOrNull { it.word.equals(word, ignoreCase = true) }?.rawScore ?: Float.NaN
            return String.format("      %-16s rank %-4d final %6.3f = blend %6.3f (inflections %6.3f, path %6.3f) x frequency factor %4.2f (freq %d)",
                word, rank + 1, h.score, h.rawScore, raw(kushler.name), raw(location.name), h.score / h.rawScore, h.frequency)
        }
        return listOf(String.format("   #%d %s, %d candidates scored", s.id, s.label, ranked.size), row(s.label)) +
            ranked.take(3).map { row(it.word) }
    }

    /** Which phone recorded a swipe: make_corpus.py shifts each phone's ids. */
    private fun device(id: Int) = when { id >= 200000 -> "P8lab"; id >= 100000 -> "P8"; else -> "P11" }

    @Test
    fun `replay recorded swipes`() {
        val dir = dataDir() ?: run { println("ReplayTest: no replay data, skipped"); return }
        val vocab = vocabulary(dir, mapOf("en-US" to 1f, "hi-Latn" to hiFactor))
        val swipes = corpus(dir)
        println("\n=== Replay: ${swipes.size} labelled swipes, vocabulary ${vocab.size} words, scales normalized by rank: $normalizeScales, Hinglish factor $hiFactor ===")

        // what the phone itself got (whatever build recorded it), for reference
        val phone = Tally()
        for (s in swipes) phone.add(if (s.phoneGot == s.label) 0 else -1)
        println(String.format("%-42s %s", "phone at recording time (top1 only)", phone))

        val watch = (System.getenv("GESTURE_REPLAY_WORDS") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        val details = mutableListOf<String>()
        val misses = LinkedHashMap<String, MutableList<String>>()
        var baseline: BooleanArray? = null // the first variant's top-1 hits, for the head-to-head count
        for (v in variants()) {
            val all = Tally()
            val bySource = sortedMapOf<String, Tally>()
            val byDevice = sortedMapOf<String, Tally>()
            val missList = mutableListOf<String>()
            val hits = BooleanArray(swipes.size)
            // a learned-words model needs its own vocabulary, grown as the replay goes
            val vocabForVariant = if (v.learnedFreq > 0) vocabulary(dir, mapOf("en-US" to 1f, "hi-Latn" to hiFactor)) else vocab
            var nanos = 0L
            val bySession = HashMap<String, Tally>()
            for ((index, s) in swipes.withIndex()) {
                val t0 = System.nanoTime()
                val results = v.decode(s, vocabForVariant)
                nanos += System.nanoTime() - t0
                if (v.learnedFreq > 0 && (s.labelSource == "kept" || s.labelSource == "pick"))
                    vocabForVariant.add(s.label, maxOf(vocabForVariant.frequencyOf(s.label), v.learnedFreq))
                if (s.label == "?") { // outcome unknown: shown when watched, never tallied
                    if (s.phoneGot in watch) details.add(String.format("%-44s #%-4d label=?         phone=%-9s -> %s", v.name, s.id, s.phoneGot,
                        results.take(10).joinToString(", ") { it.word }))
                    continue
                }
                if (s.label in watch || s.phoneGot in watch)
                    details.add(String.format("%-44s #%-4d label=%-9s phone=%-9s -> %s", v.name, s.id, s.label, s.phoneGot,
                        results.take(6).joinToString(", ") { it.word }))
                val rank = results.indexOfFirst { it.word.equals(s.label, ignoreCase = true) }
                hits[index] = rank == 0
                all.add(rank)
                if (s.session.isNotEmpty()) bySession.getOrPut(s.session) { Tally() }.add(rank)
                bySource.getOrPut(s.labelSource) { Tally() }.add(rank)
                byDevice.getOrPut(device(s.id)) { Tally() }.add(rank)
                if (rank != 0) missList.add("#${s.id} ${s.label} -> ${results.take(3).joinToString(",") { it.word }} (rank ${if (rank < 0) "-" else rank + 1})")
            }
            fun top1s(m: Map<String, Tally>) = m.entries.joinToString("  ") { "${it.key}: ${String.format("%.1f", it.value.pct(it.value.top1))}" }
            // swipes this variant gets right that the first one misses, and the other way round
            val b = baseline ?: hits.also { baseline = it }
            val vs = String.format("+%d -%d", hits.indices.count { hits[it] && !b[it] }, hits.indices.count { !hits[it] && b[it] })
            println(String.format("%-42s %s  %-9s  %s   %s   %.1f ms/swipe", v.name, all, vs, top1s(bySource), top1s(byDevice), nanos / 1e6 / swipes.size))
            misses[v.name] = missList
            // how the people differ: top-1 per session with enough swipes, worst to best
            val people = bySession.values.filter { it.runs >= 30 }.map { it.pct(it.top1) }.sorted()
            if (people.isNotEmpty()) {
                fun at(p: Double) = people[((people.size - 1) * p).toInt()]
                println(String.format("    per person (%d with 30+ swipes) top1: worst %.0f%%, 10th pct %.0f%%, 25th %.0f%%, median %.0f%%, 75th %.0f%%, best %.0f%%; under 50%%: %d",
                    people.size, people.first(), at(0.10), at(0.25), at(0.5), at(0.75), people.last(), people.count { it < 50 }))
            }
        }
        if (details.isNotEmpty()) { println("\n--- watched words ---"); details.sortedBy { it.substringAfter('#').substringBefore(' ').toInt() }.forEach { println(it) } }
        if (System.getenv("GESTURE_REPLAY_BREAKDOWN") == "1") {
            println("\n--- score breakdown of watched misses (phone settings) ---")
            for (s in swipes) if (s.label in watch) breakdown(s, vocab).forEach { println(it) }
        }
        for ((name, list) in misses) {
            if (name != "phone" || System.getenv("GESTURE_REPLAY_MISSES") != "1") continue
            println("\n--- misses, $name (${list.size}) ---")
            list.forEach { println(it) }
        }
    }
}

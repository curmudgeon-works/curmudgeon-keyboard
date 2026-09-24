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

    private val normalizeScales = System.getenv("GESTURE_REPLAY_NORMALIZE") == "1"
    private val hiFactor = System.getenv("GESTURE_REPLAY_HI_FACTOR")?.toFloatOrNull() ?: 0.65f

    private fun corpus(dir: File): List<Swipe> = File(dir, "corpus.tsv").readLines().map { line ->
        val f = line.split('\t')
        val keys = f[6].split(';').map { k ->
            val p = k.split(':')
            KeyInfo(p[0][0], p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
        }
        val points = f[7].split(';').map { p -> val v = p.split(','); GesturePoint(v[0].toFloat(), v[1].toFloat(), v[2].toLong()) }
        Swipe(f[0].toInt(), f[1], f[2], f[3], f[4], KeyboardGeometry(keys), points)
    }

    private fun variants(): List<Variant> {
        fun decoder(endpointRadius: Float = 1.6f, penUp: Float? = null, endPenalty: Float = 0f, endFree: Float = 0.4f, startShare: Float = 1f, cap: Int = 512) =
            GestureDecoder(
                HybridScorer(),
                DecoderConfig(endpointRadiusKeyWidths = endpointRadius, penUpRadiusKeyWidths = penUp,
                    endpointPenaltyPerKeyWidth = endPenalty, endpointFreeKeyWidths = endFree, startPenaltyShare = startShare, maxCandidates = cap),
                GesturePreprocessor(PreprocessorConfig(pauseConfidence = 0.9f, pauseDtFactor = 2.5f, slowdownConfidence = 0.5f)),
            )
        fun plain(d: GestureDecoder): (Swipe, Vocabulary) -> List<ScoredWord> = { s, v -> d.decode(s.points, s.geometry, v, 10) }
        val history = (System.getenv("GESTURE_REPLAY_HISTORY") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        if (history.isNotEmpty())
            return listOf(Variant("phone (new defaults), no learned words", plain(decoder()))) +
                history.map { Variant("phone, learned words at $it", plain(decoder()), learnedFreq = it) }
        /** The normal top [keep], then the strict-ends ranking's words not yet listed, then the rest of the normal list. */
        fun merged(base: GestureDecoder, strict: GestureDecoder, keep: Int = 3): (Swipe, Vocabulary) -> List<ScoredWord> = { s, v ->
            val b = base.decode(s.points, s.geometry, v, 10)
            val st = strict.decode(s.points, s.geometry, v, 10)
            val out = b.take(keep).toMutableList()
            for (w in st) if (out.none { it.word == w.word }) out.add(w)
            for (w in b) if (out.none { it.word == w.word }) out.add(w)
            out
        }
        return listOf(
            Variant("phone (new defaults)", plain(decoder())),
            Variant("candidate cap 1024", plain(decoder(cap = 1024))),
            Variant("candidate cap 2048", plain(decoder(cap = 2048))),
            Variant("candidate cap 4096", plain(decoder(cap = 4096))),
            Variant("candidate cap 16384", plain(decoder(cap = 16384))),
            Variant("top 3 normal, then strict pen-up 1.1", merged(decoder(), decoder(penUp = 1.1f))),
            Variant("top 3 normal, then strict pen-up 1.3", merged(decoder(), decoder(penUp = 1.3f))),
            Variant("top 2 normal, then strict pen-up 1.1", merged(decoder(), decoder(penUp = 1.1f), keep = 2)),
            Variant("pen-up radius 1.3", plain(decoder(penUp = 1.3f))),
            Variant("pen-up radius 1.1", plain(decoder(penUp = 1.1f))),
            Variant("end-only penalty 0.5 free 0.4", plain(decoder(endPenalty = 0.5f, startShare = 0f))),
            Variant("end-only penalty 1.0 free 0.4", plain(decoder(endPenalty = 1f, startShare = 0f))),
            Variant("end-only penalty 1.0 free 0.6", plain(decoder(endPenalty = 1f, endFree = 0.6f, startShare = 0f))),
            Variant("end-only penalty 0.5 + pen-up 1.3", plain(decoder(endPenalty = 0.5f, startShare = 0f, penUp = 1.3f))),
            Variant("start share 0.3, penalty 0.5", plain(decoder(endPenalty = 0.5f, startShare = 0.3f))),
        )
    }

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
        for (v in variants()) {
            val all = Tally()
            val bySource = sortedMapOf<String, Tally>()
            val missList = mutableListOf<String>()
            // a learned-words model needs its own vocabulary, grown as the replay goes
            val vocabForVariant = if (v.learnedFreq > 0) vocabulary(dir, mapOf("en-US" to 1f, "hi-Latn" to hiFactor)) else vocab
            var nanos = 0L
            for (s in swipes) {
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
                all.add(rank)
                bySource.getOrPut(s.labelSource) { Tally() }.add(rank)
                if (rank != 0) missList.add("#${s.id} ${s.label} -> ${results.take(3).joinToString(",") { it.word }} (rank ${if (rank < 0) "-" else rank + 1})")
            }
            println(String.format("%-42s %s   %s   %.1f ms/swipe", v.name, all, bySource.entries.joinToString("  ") { "${it.key}: ${String.format("%.1f", it.value.pct(it.value.top1))}" }, nanos / 1e6 / swipes.size))
            misses[v.name] = missList
        }
        if (details.isNotEmpty()) { println("\n--- watched words ---"); details.sortedBy { it.substringAfter('#').substringBefore(' ').toInt() }.forEach { println(it) } }
        for ((name, list) in misses) {
            if (name != "phone (new defaults)") continue
            println("\n--- misses, $name (${list.size}) ---")
            list.forEach { println(it) }
        }
    }
}

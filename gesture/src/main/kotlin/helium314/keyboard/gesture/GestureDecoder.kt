// SPDX-License-Identifier: GPL-3.0-only
// Shared decode pipeline: capture → preprocess → prune → score → rank.
// Word picking per US7453439: first and last letters pinned to the start / end of the
// swipe, then each word's letters matched in order along the path within a corridor,
// plus the sokgraph path-length ratio band and a minimum letter count from the
// inflections; nothing fits → search again with wider thresholds.
// Ranking per the patent formula (lower is better).
package helium314.keyboard.gesture

import kotlin.math.ln

class DecoderConfig(
    /** First/last letter must be within this many key widths of PEN_DOWN / PEN_UP. */
    val endpointRadiusKeyWidths: Float = 1.6f,
    /** Mid letters must be within this many key widths of the drawn path (the corridor). */
    val corridorRadiusKeyWidths: Float = 1.4f,
    /** Allowed backtrack (key widths) when requiring letters to progress along the path. */
    val progressSlackKeyWidths: Float = 1.2f,
    /** Sokgraph length must be within [1/ratio, ratio] of drawn length (with additive slack). */
    val lengthRatioBand: Float = 2.0f,
    /** Additive slack (key widths) for the length band — dominates for short words. */
    val lengthSlackKeyWidths: Float = 2.0f,
    /** Extra drawn length allowed per double letter (the loop gesture adds arc). */
    val doubleLetterArcKeyWidths: Float = 1.5f,
    /** Inflections at or above this confidence demand a letter (min-letter pruning). */
    val strongInflectionConfidence: Float = 0.8f,
    /** Slack subtracted from the strong-inflection count for min-letter pruning. */
    val minLetterSlack: Int = 1,
    /**
     * When no word fits, the endpoint radii and the corridor are multiplied by this and the search runs once more
     * (Swype's retry with larger letter-to-path thresholds). 1 = no retry.
     */
    val retryWidening: Float = 1.5f,
    /**
     * Hard cap on candidates sent to the scorer (safety net for huge vocabularies). The cut is in lookup order,
     * not by merit: at 512 a long swipe lost words like "removed" before scoring (2026-09-23), so it sits high
     * enough to be a pure safety net. Scoring cost is ~linear and small (73k-word vocabulary: ~1 ms per swipe).
     */
    val maxCandidates: Int = 4096,
    /**
     * Graded cost for the ends: per key width that a word's first / last letter lies from pen-down / pen-up beyond
     * [endpointFreeKeyWidths], the rank is multiplied by (1 + this). Starts and ends are where people land where they
     * mean to, so a word ending on the neighbouring key should lose to one ending under the finger. 0 = off.
     */
    val endpointPenaltyPerKeyWidth: Float = 0f,
    val endpointFreeKeyWidths: Float = 0.4f,
    /** Share of the graded cost applied at pen-down (1 = same as pen-up, 0 = ends only: starts are less deliberate). */
    val startPenaltyShare: Float = 1f,
    /** Pen-up radius (key widths) when it should be tighter than [endpointRadiusKeyWidths]; null = the same. */
    val penUpRadiusKeyWidths: Float? = null,
    /**
     * People stop short of a key they reach for from far away, more so reaching right than left. A letter whose key is
     * at least [longReachKeyWidths] from the previous letter's may also be matched this many key widths short of its
     * key, back along the way in. 0 = off. The two shortfalls are per direction of the reach. Measured on 1,400 of
     * our own swipes (corners after a 4+ key reach fall 0.3 short at the median, rightward more than leftward) and
     * replayed on those and on 40,000 FUTO swipes: +9 −2 and +239 −41 top-1 at 0.6 / 0.4 (2026-09-30).
     */
    val longReachKeyWidths: Float = 4f,
    val reachShortfallRightKeyWidths: Float = 0.6f,
    val reachShortfallLeftKeyWidths: Float = 0.4f,
    /**
     * Fast swipes are looser: above [speedWidenFromKeysPerSecond] the corridor and the start/end radii grow by
     * [speedWidenPerKeyPerSecond] of themselves per key width per second of swipe speed (path length over duration).
     * A careful 9 keys/s swipe keeps the tight tolerances; a 20 keys/s one gets them widened. 0 = off.
     */
    val speedWidenFromKeysPerSecond: Float = 12f,
    val speedWidenPerKeyPerSecond: Float = 0f,
    /**
     * Fast swipes lean more on word frequency (the user's own words carry a boost) and less on exact shape: above
     * [frequencyEmphasisFromKeysPerSecond] the frequency weight in the ranking grows by [frequencyEmphasisPerKeyPerSecond]
     * per key width per second. 0 = off (weight 1, the plain Swype-style ranking). 0.05 (weight 1.4 at 20 keys/s):
     * +1.2 first-choice on our own 1,428 swipes and +1.2 on 40,245 FUTO swipes (+629 −142), slow swipes unchanged
     * (2026-10-01).
     */
    val frequencyEmphasisFromKeysPerSecond: Float = 12f,
    val frequencyEmphasisPerKeyPerSecond: Float = 0.05f,
    /**
     * This share of a word's letters (rounded down; never the first or last) may lie outside the corridor and still
     * leave the word in the running: the scorer, not the filter, then judges the miss. 0 = off; 0.2 = one letter of a
     * 5–9 letter word, two of a 10–14 letter word.
     */
    val missedLetterShare: Float = 0f,
    /** Capitalize the letter a swipe leaves the keyboard upwards from (the excursion is stripped from the path either way). */
    val capsExcursions: Boolean = true,
)

/**
 * Bumped whenever the decoder's behaviour changes, so logged swipe results can be told apart by the decoder that
 * produced them. 1 = the 0.2.000 decoder (Swype-style picking, hybrid scorer); 2 = a corner after a long reach may
 * stop short of its key (2026-09-30); 3 = fast swipes lean on word frequency and get relaxed corner matching
 * (2026-10-01).
 */
const val DECODER_VERSION = 3

class GestureDecoder(
    private val scorer: Scorer,
    private val config: DecoderConfig = DecoderConfig(),
    private val preprocessor: GesturePreprocessor = GesturePreprocessor(),
) {

    /** The speed of the last swipe decoded (key widths per second, from the preprocessed path), as the thresholds saw it. */
    @Volatile var lastSpeedKeysPerSecond: Float = 0f
        private set

    fun decode(
        points: List<GesturePoint>,
        geometry: KeyboardGeometry,
        vocabulary: Vocabulary,
        maxResults: Int = 10,
    ): List<ScoredWord> =
        decodeWithScorers(points, geometry, vocabulary, listOf(scorer), maxResults)[scorer.name] ?: emptyList()

    /**
     * Runs the shared pipeline (preprocess + prune) ONCE and scores the same candidate
     * set with each of [scorers] — scoring is the cheap stage, so comparing scorers per
     * swipe costs little. Returns results keyed by [Scorer.name], each ranked ascending.
     */
    fun decodeWithScorers(
        points: List<GesturePoint>,
        geometry: KeyboardGeometry,
        vocabulary: Vocabulary,
        scorers: List<Scorer>,
        maxResults: Int = 10,
    ): Map<String, List<ScoredWord>> {
        val out = LinkedHashMap<String, List<ScoredWord>>()
        if (points.isEmpty()) return out
        val gesture = preprocessor.preprocess(points, geometry)
        if (gesture.points.isEmpty()) return out

        val keysPerSecond = gesture.meanSpeed * 1000f / geometry.keyWidth
        lastSpeedKeysPerSecond = keysPerSecond
        val speedWiden = 1f + config.speedWidenPerKeyPerSecond * (keysPerSecond - config.speedWidenFromKeysPerSecond).coerceAtLeast(0f)
        var candidates = collectCandidates(gesture, geometry, vocabulary, speedWiden)
        if (candidates.isEmpty() && config.retryWidening > 1f)
            candidates = collectCandidates(gesture, geometry, vocabulary, speedWiden * config.retryWidening)
        if (candidates.isEmpty()) return out

        // caps-excursion: uppercase the letter matched nearest before each excursion.
        // Applied once per candidate, shared by all scorers (post-scoring display form).
        val displayWords = candidates.map { if (config.capsExcursions) applyExcursionCaps(it, gesture, geometry) else it.sokgraph.word }

        val maxFreq = vocabulary.maxFrequency.toFloat()
        val frequencyWeight = 1f + config.frequencyEmphasisPerKeyPerSecond * (keysPerSecond - config.frequencyEmphasisFromKeysPerSecond).coerceAtLeast(0f)
        for (s in scorers) {
            val scored = ArrayList<ScoredWord>(candidates.size)
            for ((i, candidate) in candidates.withIndex()) {
                val raw = s.score(gesture, candidate.sokgraph, geometry)
                if (raw == Float.MAX_VALUE) continue
                // patent ranking formula: score * (log(MAX_FREQ / word_frequency) + 1), lower is better;
                // the log term weighs more for fast swipes (frequencyWeight 1 = the plain formula)
                var rank = raw * (frequencyWeight * ln(maxFreq / candidate.frequency) + 1f)
                if (config.endpointPenaltyPerKeyWidth > 0f) {
                    val sok = candidate.sokgraph.points
                    val p0 = gesture.points.first(); val p1 = gesture.points.last()
                    val dStart = Math.hypot((p0.x - sok.first().x).toDouble(), (p0.y - sok.first().y).toDouble()).toFloat() / geometry.keyWidth
                    val dEnd = Math.hypot((p1.x - sok.last().x).toDouble(), (p1.y - sok.last().y).toDouble()).toFloat() / geometry.keyWidth
                    val over = config.startPenaltyShare * (dStart - config.endpointFreeKeyWidths).coerceAtLeast(0f) +
                            (dEnd - config.endpointFreeKeyWidths).coerceAtLeast(0f)
                    rank *= 1f + config.endpointPenaltyPerKeyWidth * over
                }
                scored.add(ScoredWord(displayWords[i], rank, raw, candidate.frequency))
            }
            scored.sortBy { it.score }
            out[s.name] = if (scored.size > maxResults) scored.subList(0, maxResults) else scored
        }
        return out
    }

    /**
     * For each excursion arc position, capitalize the candidate letter whose matched
     * path position most closely PRECEDES the excursion exit. An excursion before any
     * letter's match position capitalizes the first letter (the proper-noun case).
     */
    private fun applyExcursionCaps(candidate: Candidate, gesture: PreprocessedGesture, geometry: KeyboardGeometry): String {
        val excursions = gesture.excursionArcs
        val word = candidate.sokgraph.word
        if (excursions.isEmpty() || candidate.letterArcs.isEmpty()) return word
        // stripping the excursion stub shifts the capped letter's match slightly PAST the
        // junction, so allow ~0.6 key widths of forward slack (still < one key transition)
        val slack = 0.6f * geometry.keyWidth
        val chars = word.toCharArray()
        for (e in excursions) {
            var idx = 0
            for (i in candidate.letterArcs.indices) {
                if (candidate.letterArcs[i] <= e + slack) idx = i
            }
            while (idx > 0 && !chars[idx].isLetter()) idx-- // never "capitalize" an apostrophe
            chars[idx] = chars[idx].uppercaseChar()
        }
        return String(chars)
    }

    // ---- candidate generation (pruning, cheap → expensive) ----

    private class Candidate(
        val sokgraph: Sokgraph,
        val frequency: Int,
        /** matched arc position along the drawn path per word character */
        val letterArcs: FloatArray,
    )

    /** [widen] multiplies the endpoint radii and the corridor (1 = the normal search). */
    private fun collectCandidates(
        gesture: PreprocessedGesture,
        geometry: KeyboardGeometry,
        vocabulary: Vocabulary,
        widen: Float,
    ): List<Candidate> {
        val kw = geometry.keyWidth
        val start = gesture.points.first()
        val end = gesture.points.last()
        val endpointRadius = config.endpointRadiusKeyWidths * kw * widen
        val corridorRadius = config.corridorRadiusKeyWidths * kw * widen
        val progressSlack = config.progressSlackKeyWidths * kw
        val drawnLength = gesture.pathLength
        val minLetters = (gesture.strongInflectionCount(config.strongInflectionConfidence)
                - config.minLetterSlack).coerceAtLeast(1)

        // prune 1: the first letter's key near where the swipe started, the last letter's near where it ended
        val startKeys = geometry.keysNear(start.x, start.y, endpointRadius).map { it.char }.toHashSet()
        val endKeys = geometry.keysNear(end.x, end.y, (config.penUpRadiusKeyWidths ?: config.endpointRadiusKeyWidths) * kw * widen)
            .map { it.char }.toHashSet()
        fun onKeys(c: Char, keys: Set<Char>) = !geometry.isSkippedWordChar(c) && geometry.keyForWordChar(c)?.char in keys
        val firsts = vocabulary.firstChars().filter { onKeys(it, startKeys) }
        val lasts = vocabulary.lastChars().filter { onKeys(it, endKeys) }

        // distance from each key to the path, once per character
        // (apostrophes map to the period key via keyForWordChar, so they cache separately)
        val distCache = HashMap<Char, Float>()
        fun distToPath(c: Char): Float = distCache.getOrPut(c) {
            val k = geometry.keyForWordChar(c) ?: return@getOrPut Float.MAX_VALUE
            gesture.distanceToPath(k.centerX, k.centerY)
        }

        /**
         * prune 3: each letter's key near the path, in order: at or after (with slack) the previous letter's
         * position. Returns the matched arc position per character, or null if the word doesn't fit.
         */
        fun matchAlongPath(word: String): FloatArray? {
            val arcs = FloatArray(word.length)
            var arcPos = 0f
            var previous: KeyInfo? = null
            var missesLeft = if (config.missedLetterShare <= 0f) 0
                else (word.count { !geometry.isSkippedWordChar(it.lowercaseChar()) } * config.missedLetterShare).toInt()
            for (i in word.indices) {
                val c = word[i].lowercaseChar()
                if (geometry.isSkippedWordChar(c)) { // not on the path: holds the previous letter's position
                    arcs[i] = arcPos
                    continue
                }
                val key = geometry.keyForWordChar(c) ?: return null
                val from = (arcPos - progressSlack).coerceAtLeast(0f)
                var (d, newArc) = if (distToPath(c) > corridorRadius) Pair(Float.MAX_VALUE, from)
                    else gesture.nearestArcPositionFrom(key.centerX, key.centerY, from, corridorRadius)
                if (previous != null && i < word.lastIndex) {
                    // a long reach that stopped short: look where the key would be if it sat that much closer. Taken when
                    // the key itself is out of reach of the path, or was only found much further along it: a corner just
                    // outside the corridor otherwise matches the letter's NEXT appearance in the word (the second l of
                    // "analytical") and every letter between the two is then looked for past it
                    val dx = key.centerX - previous.centerX
                    val dy = key.centerY - previous.centerY
                    val reach = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val short = (if (dx > 0) config.reachShortfallRightKeyWidths else config.reachShortfallLeftKeyWidths) * kw
                    if (short > 0f && reach >= config.longReachKeyWidths * kw) {
                        val near = gesture.nearestArcPositionFrom(
                            key.centerX - dx / reach * short, key.centerY - dy / reach * short, from, corridorRadius)
                        if (near.first <= corridorRadius && (d > corridorRadius || newArc - near.second > SHORT_REACH_JUMP_KEY_WIDTHS * kw)) {
                            d = near.first
                            newArc = near.second
                        }
                    }
                }
                if (d > corridorRadius) {
                    // a letter the path never came near: allowed a few times per word, never at the ends
                    if (i == 0 || i == word.lastIndex || missesLeft-- <= 0) return null
                    arcs[i] = arcPos
                    previous = key
                    continue
                }
                arcs[i] = newArc
                arcPos = newArc
                previous = key
            }
            return arcs
        }

        val out = ArrayList<Candidate>()
        for (f in firsts) for (l in lasts) for (node in vocabulary.wordsByEnds(f, l)) {
            if (out.size >= config.maxCandidates) return out
            val word = node.word ?: continue
            if (word.length < minLetters) continue
            val arcs = matchAlongPath(word) ?: continue
            // prune 2: sokgraph length within ratio band of drawn length
            val sok = SokgraphBuilder.build(word, geometry) ?: continue
            if (lengthBandOk(sok, drawnLength, kw)) out.add(Candidate(sok, node.frequency, arcs))
        }
        return out
    }

    private companion object {
        /** A letter found this many key widths further along the path than its stopped-short position was a later pass. */
        const val SHORT_REACH_JUMP_KEY_WIDTHS = 3f
    }

    private fun lengthBandOk(sok: Sokgraph, drawnLength: Float, kw: Float): Boolean {
        val slack = config.lengthSlackKeyWidths * kw
        // loops for double letters add drawn arc the sokgraph doesn't have
        val loopAllowance = sok.doubleLetterCount * config.doubleLetterArcKeyWidths * kw
        val effectiveDrawn = (drawnLength - loopAllowance).coerceAtLeast(0f)
        val lo = effectiveDrawn / config.lengthRatioBand - slack
        val hi = effectiveDrawn * config.lengthRatioBand + slack
        return sok.length in lo..hi
    }
}

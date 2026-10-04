// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

/**
 * How learned words fade when they aren't used: in steps by the time since their last use, each step halving the
 * word's weight, down to a floor. The native side fades suggestions by this rule with [STEP_SECONDS]
 * (DynamicLanguageModelProbabilityUtils::getDecayedProbability, keep the constants in sync); trust
 * ("Trust words you've typed N+ times") fades by the same rule four times slower, [TRUST_STEP_SECONDS].
 * Counts are the stored ones: a word of no dictionary is stored at 0 by its first use, so n uses = count n - 1.
 */
object LearnedDecay {
    private const val DAY_SECONDS = 24L * 60 * 60
    /** One step per 3 months (90 days) unused: < 3 months 100 %, 3-6 months 50 %, 6-9 months 25 %, then 12.5 %. */
    const val STEP_SECONDS = 90 * DAY_SECONDS
    const val TRUST_STEP_SECONDS = 4 * STEP_SECONDS
    const val MAX_STEPS = 3
    /** A word with a stored count of [FREQUENT_COUNT] or more only halves once and stays at 50 %. */
    const val MAX_STEPS_FREQUENT = 1
    const val FREQUENT_COUNT = 5

    /** How many halvings a word of stored [count] last used [ageSeconds] ago has faded by, with steps of [stepSeconds].
     *  No time (age unknown) or a last use in the future (the clock was set back) fades nothing. */
    fun steps(ageSeconds: Long, count: Int, stepSeconds: Long): Int {
        if (ageSeconds <= 0 || stepSeconds <= 0) return 0
        val max = if (count >= FREQUENT_COUNT) MAX_STEPS_FREQUENT else MAX_STEPS
        return minOf(ageSeconds / stepSeconds, max.toLong()).toInt()
    }

    /** [steps] for a word last used at [timestamp] (seconds, as stored; <= 0: none) at [now] (seconds). */
    fun steps(timestamp: Int, now: Long, count: Int, stepSeconds: Long): Int =
        if (timestamp <= 0) 0 else steps(now - timestamp, count, stepSeconds)

    /** The stored [count] faded for trust: count / 2^steps. */
    fun effectiveCount(count: Int, timestamp: Int, now: Long): Double =
        count.toDouble() / (1 shl steps(timestamp, now, count, TRUST_STEP_SECONDS))

    /** Typed [typedCount] times, faded: effectiveCount >= typedCount - 1 (n uses = stored count n - 1), checked in
     *  whole numbers as count >= (typedCount - 1) * 2^steps. A word that isn't stored ([count] < 0) is never
     *  trusted. */
    fun isTrusted(count: Int, timestamp: Int, now: Long, typedCount: Int): Boolean {
        if (count < 0) return false
        val needed = (typedCount - 1).coerceAtLeast(0).toLong()
        return count >= needed shl steps(timestamp, now, count, TRUST_STEP_SECONDS)
    }
}

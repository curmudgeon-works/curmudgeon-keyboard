/*
 * Copyright (C) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef LATINIME_DYNAMIC_LANGUAGE_MODEL_PROBABILITY_UTILS_H
#define LATINIME_DYNAMIC_LANGUAGE_MODEL_PROBABILITY_UTILS_H

#include <algorithm>

#include "defines.h"
#include "dictionary/property/historical_info.h"
#include "utils/ngram_utils.h"
#include "utils/time_keeper.h"

namespace latinime {

class DynamicLanguageModelProbabilityUtils {
 public:
    static float computeRawProbabilityFromCounts(const int count, const int contextCount,
            const NgramType ngramType) {
        const int minCount = ASSUMED_MIN_COUNTS[static_cast<int>(ngramType)];
        return static_cast<float>(count) / static_cast<float>(std::max(contextCount, minCount));
    }

    static float backoff(const int ngramProbability, const NgramType ngramType) {
        const int probability =
                ngramProbability + ENCODED_BACKOFF_WEIGHTS[static_cast<int>(ngramType)];
        return std::min(std::max(probability, NOT_A_PROBABILITY), MAX_PROBABILITY);
    }

    // A learned entry (word or word pair) fades by how long ago it was last used: one halving per
    // DECAY_STEP_IN_SECONDS unused, at most MAX_DECAY_STEPS (down to 1/8), and an entry used
    // FREQUENT_ENTRY_MIN_COUNT times or more at most MAX_DECAY_STEPS_FOR_FREQUENT_ENTRY (down to 1/2).
    // The probability is log-encoded, so a halving is a fixed amount off it. Using the entry again sets
    // its timestamp to now, which brings it back to full strength. Mirrored in LearnedDecay.kt.
    static int getDecayedProbability(const int probability, const HistoricalInfo historicalInfo) {
        if (probability == NOT_A_PROBABILITY) {
            return NOT_A_PROBABILITY;
        }
        const int decay = static_cast<int>(
                ENCODED_HALVING * static_cast<float>(getDecaySteps(historicalInfo)) + 0.5f);
        return std::min(std::max(probability - decay, 0), MAX_PROBABILITY);
    }

    static int getDecaySteps(const HistoricalInfo historicalInfo) {
        const int timestamp = historicalInfo.getTimestamp();
        if (timestamp <= 0) {
            // No time of last use: nothing to fade from.
            return 0;
        }
        const int elapsedTime = TimeKeeper::peekCurrentTime() - timestamp;
        if (elapsedTime <= 0) {
            // Last used "in the future" (the clock was set back since): kept at full strength rather
            // than hidden, the next use fixes the timestamp.
            return 0;
        }
        const int maxSteps = historicalInfo.getCount() >= FREQUENT_ENTRY_MIN_COUNT
                ? MAX_DECAY_STEPS_FOR_FREQUENT_ENTRY : MAX_DECAY_STEPS;
        return std::min(elapsedTime / DECAY_STEP_IN_SECONDS, maxSteps);
    }

    static int shouldRemoveEntryDuringGC(const HistoricalInfo /* historicalInfo */) {
        // Learned words are never deleted for age: an old entry only fades (getDecayedProbability).
        // The entry-count cap (HeaderPolicy::DEFAULT_MAX_NGRAM_COUNTS) still evicts the least
        // recently used ones when a store outgrows it.
        return false;
    }

    static int getPriorityToPreventFromEviction(const HistoricalInfo historicalInfo) {
        // TODO: Improve this logic.
        // More recently input entries get higher priority.
        return historicalInfo.getTimestamp();
    }

private:
    DISALLOW_IMPLICIT_CONSTRUCTORS(DynamicLanguageModelProbabilityUtils);

    static_assert(MAX_PREV_WORD_COUNT_FOR_N_GRAM <= 3, "Max supported Ngram is Quadgram.");

    static const int ASSUMED_MIN_COUNTS[];
    static const int ENCODED_BACKOFF_WEIGHTS[];
    static const int DECAY_STEP_IN_SECONDS;
    static const int MAX_DECAY_STEPS;
    static const int MAX_DECAY_STEPS_FOR_FREQUENT_ENTRY;
    static const int FREQUENT_ENTRY_MIN_COUNT;
    static const float ENCODED_HALVING;
};

} // namespace latinime
#endif /* LATINIME_DYNAMIC_LANGUAGE_MODEL_PROBABILITY_UTILS_H */

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

#include "dictionary/structure/v4/content/dynamic_language_model_probability_utils.h"

namespace latinime {

// Used to provide stable probabilities even if the user's input count is small.
const int DynamicLanguageModelProbabilityUtils::ASSUMED_MIN_COUNTS[] = {8192, 2, 2, 1};

// Encoded backoff weights.
// Note that we give positive values for trigrams and quadgrams that means the weight is more than
// 1.
// TODO: Apply backoff for main dictionaries and quit giving a positive backoff weight.
const int DynamicLanguageModelProbabilityUtils::ENCODED_BACKOFF_WEIGHTS[] = {-32, -4, 2, 8};

// Fading of learned entries by time since last use (see getDecayedProbability). Keep in sync with
// helium314.keyboard.latin.utils.LearnedDecay.
// One step per 90 days (3 months) unused: < 3 months 100 %, 3-6 months 50 %, 6-9 months 25 %, then 12.5 %.
const int DynamicLanguageModelProbabilityUtils::DECAY_STEP_IN_SECONDS = 90 * 24 * 60 * 60;
const int DynamicLanguageModelProbabilityUtils::MAX_DECAY_STEPS = 3;
// An entry with a stored count of 5 or more (typed often) only halves once and stays at 50 %.
const int DynamicLanguageModelProbabilityUtils::MAX_DECAY_STEPS_FOR_FREQUENT_ENTRY = 1;
const int DynamicLanguageModelProbabilityUtils::FREQUENT_ENTRY_MIN_COUNT = 5;
// ProbabilityUtils::encodeRawProbability is 255 + this * log2(p), so halving p takes this off.
// (Same value as ProbabilityUtils::PROBABILITY_ENCODING_SCALER.)
const float DynamicLanguageModelProbabilityUtils::ENCODED_HALVING = 8.58923700372f;

} // namespace latinime

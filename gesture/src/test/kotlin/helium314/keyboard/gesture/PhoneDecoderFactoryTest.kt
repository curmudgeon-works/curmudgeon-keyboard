// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.gesture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The replay's "phone" variant and the device build the same decoder, from [PhoneDecoderFactory] (review 2026-10-21
 * L4; owner 2026-10-31: "replay and phone decoders should be same"). Fail before: ReplayTest built its "phone" decoder
 * from its own literals, so a setting the device changed (no capitals from an excursion since 2026-10-18) the replay
 * kept the old way, and a new decoder weight had to be added in both places by hand.
 */
class PhoneDecoderFactoryTest {

    @Test
    fun `the replay's phone variant is the device's decoder at its defaults`() {
        assertEquals(PhoneDecoderFactory.configs(PhoneDecoderFactory.Settings()), ReplaySpec.configs("phone"))
        assertEquals(PhoneDecoderFactory.configs(), ReplaySpec.configs(""))
    }

    @Test
    fun `every setting reaches the config the device reads it from`() {
        val settings = PhoneDecoderFactory.Settings(turn = 1.3f, slowdown = 0.9f, kushler = 0.7f, historyBoost = 100,
            fastCommon = 0.11f, cornerMiss = 0.12f, fastFrom = 15f, apostropheSkip = 0.2f, capsHeight = 0.8f, capsSwipe = true, learnedWords = false)
        val c = PhoneDecoderFactory.configs(settings)
        assertNotEquals(PhoneDecoderFactory.configs(), c)
        assertEquals(1.3f, c.preprocessor.turnConfidenceScale)
        assertEquals(0.9f, c.kushler.slowEmphasis)
        assertEquals(0.7f, c.kushlerWeight)
        assertEquals(100, c.decoder.learnedBoost)
        assertEquals(0.11f, c.decoder.frequencyEmphasisPerKeyPerSecond)
        assertEquals(0.12f, c.kushler.matchRelaxPerKeyPerSecond)
        assertEquals(15f, c.kushler.speedFromKeysPerSecond)
        assertEquals(15f, c.decoder.frequencyEmphasisFromKeysPerSecond)
        assertEquals(0.2f, c.decoder.apostropheSkipPenalty)
        assertEquals(0.8f, c.preprocessor.excursionMinHeightKeyHeights)
        assertEquals(true, c.decoder.capsExcursions)
        assertEquals(false, c.decoder.learnedWords)
        // the device's stop detection, tighter than the class default, on every build
        assertEquals(PhoneDecoderFactory.STOP_DT_FACTOR, c.preprocessor.stopDtFactor)
        assertEquals(PhoneDecoderFactory.STOP_DT_FACTOR, PhoneDecoderFactory.configs().preprocessor.stopDtFactor)
    }

    @Test
    fun `the device as shipped does not capitalize from an excursion`() {
        val phone = PhoneDecoderFactory.configs()
        assertEquals(false, phone.decoder.capsExcursions)
        assertEquals(1.25f, phone.preprocessor.excursionMinHeightKeyHeights)
    }

    @Test
    fun `a spec changes only what it names`() {
        val phone = PhoneDecoderFactory.configs()
        assertEquals(phone.copy(kushlerWeight = 0.5f, kushler = phone.kushler.copy(slowEmphasis = 1f),
            preprocessor = phone.preprocessor.copy(excursionMinHeightKeyHeights = 0.5f)), ReplaySpec.configs("k=0.5 slow=1 exc=0.5"))
        assertEquals(phone.copy(decoder = phone.decoder.copy(capsExcursions = true, learnedBoost = 0)), ReplaySpec.configs("caps=1 hist=0"))
        // the speed threshold moves everywhere the device's does, and where the replay sweeps it too
        val fast = ReplaySpec.configs("spdFrom=15")
        assertEquals(15f, fast.kushler.speedFromKeysPerSecond)
        assertEquals(15f, fast.location.speedFromKeysPerSecond)
        assertEquals(15f, fast.decoder.speedWidenFromKeysPerSecond)
    }

    @Test
    fun `a built decoder scores with the three scorers the app publishes, active first`() {
        val built = PhoneDecoderFactory.build()
        assertEquals(listOf("hybrid", "kushler", "location"), built.scorers.map { it.name })
        assertEquals(built.hybrid, built.scorers[0])
        val results = built.decoder.decodeWithScorers(SyntheticPathGenerator.idealPath("hello", QwertyFixture.geometry),
            QwertyFixture.geometry, TestVocabulary.vocabulary, built.scorers, 5)
        assertEquals(setOf("hybrid", "kushler", "location"), results.keys)
        assertEquals("hello", results["hybrid"]?.firstOrNull()?.word)
    }
}

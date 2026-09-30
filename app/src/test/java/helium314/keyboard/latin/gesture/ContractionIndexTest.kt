// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractionIndexTest {
    private val index = GestureDecoderVocabulary.contractionIndex(listOf(
        "you" to 200, "your" to 190, "you're" to 160, "you'll" to 95, "you've" to 96, "you'd" to 85,
        "I'm" to 116, "I'll" to 164, "aren't" to 102, "goin'" to 50, "'tis" to 40,
    ), Locale.US)

    @Test fun mostFrequentFirst() =
        assertEquals(listOf("you're", "you've", "you'll", "you'd"), index["you'"])

    @Test fun keyIsLowercaseAndWordsKeepTheirCase() =
        assertEquals(listOf("I'll", "I'm"), index["i'"])

    @Test fun singleContraction() =
        assertEquals(listOf("aren't"), index["aren'"])

    @Test fun apostropheAtTheStartOrEndIsNotAContraction() =
        assertEquals(setOf("you'", "i'", "aren'"), index.keys)
}

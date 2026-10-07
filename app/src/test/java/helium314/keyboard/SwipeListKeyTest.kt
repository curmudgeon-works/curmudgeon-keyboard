// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** A swipe word list is named by its languages and their priorities only: the learned-word boost and "suggest learned
 *  words" are applied at scoring, so the keyboard's warm-up and the first swipe ask for the same list whatever they are. */
@RunWith(RobolectricTestRunner::class)
class SwipeListKeyTest {
    @Test fun `the list's name has no setting applied at scoring`() {
        assertEquals("en-US*1.0", GestureDecoderVocabulary.LocaleSpec(Locale.US, 1f).key)
    }
}

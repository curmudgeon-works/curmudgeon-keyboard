// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.gesture.GestureCorpusRecorder
import helium314.keyboard.latin.gesture.SwipeMetrics
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A swipe made while typing privately is never logged, whatever field its outcome lands in (review 2026-10-06). */
@RunWith(RobolectricTestRunner::class)
class PrivateSwipeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val results = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "swipe_results.tsv")
    private var private = false

    @Before fun setUp() {
        results.delete()
        SwipeMetrics.init(ctx); GestureCorpusRecorder.init(ctx)
        SwipeMetrics.metricsOn = { true }
        GestureCorpusRecorder.corpusOn = { false }
        SwipeMetrics.privateNow = { private }
    }

    @After fun tearDown() { results.delete() }

    private fun swipe(word: String) {
        val pointers = InputPointers(4).apply { addPointer(10, 10, 0, 0); addPointer(50, 20, 0, 30); addPointer(90, 30, 0, 60) }
        val candidate = SuggestedWordInfo(word, "", 100, SuggestedWordInfo.KIND_CORRECTION, null, 0, 0)
        GestureCorpusRecorder.onSwipe(ComposedData(pointers, true, word), Mockito.mock(Keyboard::class.java), listOf(candidate), "en")
    }

    private fun logged(): String { Thread.sleep(300); return results.takeIf { it.exists() }?.readText() ?: "" }

    @Test fun `a private swipe whose word is kept in a normal field leaves no line`() {
        private = true
        swipe("secret")
        private = false // (back in a normal app before the word settles)
        GestureCorpusRecorder.onWordCommitted("secret")
        assertFalse("secret" in logged(), "the private swipe was logged")
    }

    @Test fun `a normal swipe is logged`() {
        private = false
        swipe("hello")
        GestureCorpusRecorder.onWordCommitted("hello")
        assertTrue("hello" in logged())
    }
}

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
import helium314.keyboard.latin.settings.Settings
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Swipe ids in the corpus are unique across keyboard starts (2026-10-23: a count that began again at every start left
 *  the "final" lines to be matched to their swipes by guesswork). */
@RunWith(RobolectricTestRunner::class)
class SwipeCorpusIdsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        GestureCorpusRecorder.init(ctx)
        GestureCorpusRecorder.onFieldChanged()
        GestureCorpusRecorder.corpusOn = { true }
        SwipeMetrics.privateNow = { false }
        Thread.sleep(300)
        GestureCorpusRecorder.corpusFile()!!.delete()
    }

    @After fun tearDown() {
        GestureCorpusRecorder.corpusOn = { Settings.getValues()?.mRecordGestureCorpus == true }
        SwipeMetrics.privateNow = { Settings.getValues()?.mIncognitoModeEnabled == true }
        Thread.sleep(300)
        GestureCorpusRecorder.corpusFile()?.delete()
    }

    private fun swipe(word: String) {
        val pointers = InputPointers(4).apply { addPointer(10, 10, 0, 0); addPointer(50, 20, 0, 30); addPointer(90, 30, 0, 60) }
        val candidate = SuggestedWordInfo(word, "", 100, SuggestedWordInfo.KIND_CORRECTION, null, 0, 0)
        GestureCorpusRecorder.onSwipe(ComposedData(pointers, true, word), Mockito.mock(Keyboard::class.java), listOf(candidate), "en")
    }

    private fun swipeIds(): List<Long> {
        Thread.sleep(300)
        return GestureCorpusRecorder.corpusFile()!!.readLines().map { JSONObject(it) }
            .filter { it.optString("type") == "swipe" }.map { it.getLong("id") }
    }

    @Test fun `swipe ids are their time and never repeat, also after the keyboard starts again`() {
        val before = System.currentTimeMillis()
        swipe("hello")
        swipe("world") // (most likely in the same millisecond)
        GestureCorpusRecorder.init(ctx) // the keyboard started again
        swipe("again")
        val ids = swipeIds()
        assertEquals(3, ids.size)
        assertTrue(ids.first() >= before, "an id is the swipe's time in ms: $ids")
        assertEquals(ids.size, ids.distinct().size, "ids repeat: $ids")
        assertEquals(ids.sorted(), ids, "ids go up: $ids")
    }

    @Test fun `two swipes in the same millisecond get different ids`() {
        val now = System.currentTimeMillis()
        val a = GestureCorpusRecorder.nextId(now)
        val b = GestureCorpusRecorder.nextId(now)
        assertTrue(b > a)
    }
}

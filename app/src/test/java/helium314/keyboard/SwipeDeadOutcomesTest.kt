// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.gesture.GestureCorpusRecorder
import helium314.keyboard.latin.gesture.OwnGestureDecoder
import helium314.keyboard.latin.gesture.SwipeMetrics
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.storeOneKeyboard
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 20261021_M2: a dead swipe (the decoder returned before decoding) is logged in the swipe results under the gate that
 * closed, not as "none": "nogeometry" when the keyboard had too few letter keys to lay words out on, "nopoints" with
 * fewer than two points, "novocab" as before; "none" is a decoded swipe that fit no word. The eight "none" rows of
 * 2026-10-01 to 2026-10-05 came before "novocab" existed, so the next pull can say which gate it was.
 * Fail before: "none" for the keyboard without letter keys (no OUTCOME_NO_GEOMETRY, no lastEarlyReturn).
 */
@RunWith(RobolectricTestRunner::class)
class SwipeDeadOutcomesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val results = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "swipe_results.tsv")

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); storeOneKeyboard(ctx, real)
        results.delete()
        SwipeMetrics.init(ctx); GestureCorpusRecorder.init(ctx)
        SwipeMetrics.metricsOn = { true }; GestureCorpusRecorder.corpusOn = { false }; SwipeMetrics.privateNow = { false }
        GestureCorpusRecorder.onFieldChanged() // (no swipe left open by another test)
    }

    @After fun tearDown() {
        SwipeMetrics.metricsOn = { Settings.getValues()?.mSwipeMetrics == true }
        SwipeMetrics.privateNow = { Settings.getValues()?.mIncognitoModeEnabled == true }
        GestureCorpusRecorder.corpusOn = { Settings.getValues()?.mRecordGestureCorpus == true }
        results.delete()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    /** A swipe of [pointers] on [keyboard], decoded as Suggest asks for it and then handed to the recorder: the outcome logged. */
    private fun swipe(pointers: InputPointers, keyboard: Keyboard): String {
        val decoded = OwnGestureDecoder.getSuggestionResults(ComposedData(pointers, true, ""), keyboard, listOf(Locale.US), null, 1.25f, 9)
        assertTrue(decoded.isEmpty())
        GestureCorpusRecorder.onSwipe(ComposedData(pointers, true, ""), keyboard, decoded.toList(), "en")
        Thread.sleep(300)
        val lines = results.readLines()
        assertEquals(1, lines.size, "logged: $lines")
        val p = lines.single().split('\t')
        assertEquals("0", p[6], "decode time: $p") // (not decoded)
        assertEquals("0.0", p[7], "speed: $p")
        return p[2]
    }

    @Test fun `a keyboard without letter keys logs nogeometry, not none`() {
        val pointers = InputPointers(4).apply { addPointer(10, 10, 0, 0); addPointer(50, 20, 0, 30); addPointer(90, 30, 0, 60) }
        val outcome = swipe(pointers, Mockito.mock(Keyboard::class.java)) // (a stand-in keyboard: no keys at all)
        assertEquals(SwipeMetrics.OUTCOME_NO_GEOMETRY, outcome)
        assertEquals("nogeometry", outcome)
        assertEquals(SwipeMetrics.OUTCOME_NO_GEOMETRY, OwnGestureDecoder.lastEarlyReturn)
    }

    @Test fun `the decoder names a swipe of one point, and forgets the last gate on the next swipe`() {
        val keyboard = Mockito.mock(Keyboard::class.java)
        val one = InputPointers(4).apply { addPointer(10, 10, 0, 0) }
        OwnGestureDecoder.getSuggestionResults(ComposedData(one, true, ""), keyboard, listOf(Locale.US), null, 1.25f, 9)
        assertEquals(SwipeMetrics.OUTCOME_NO_POINTS, OwnGestureDecoder.lastEarlyReturn)
        assertEquals("nopoints", OwnGestureDecoder.lastEarlyReturn)
        // (the recorder itself does not log a one-point swipe: nothing to follow)
        GestureCorpusRecorder.onSwipe(ComposedData(one, true, ""), keyboard, emptyList(), "en")
        Thread.sleep(300)
        assertTrue(!results.isFile || results.readLines().isEmpty(), "logged: ${results.takeIf { it.isFile }?.readLines()}")
        // the next swipe's own gate, not the last one's
        val three = InputPointers(4).apply { addPointer(10, 10, 0, 0); addPointer(50, 20, 0, 30); addPointer(90, 30, 0, 60) }
        assertEquals(SwipeMetrics.OUTCOME_NO_GEOMETRY, swipe(three, keyboard))
    }

    @Test fun `the dead-swipe outcomes count as never offered in the summary`() {
        for (outcome in listOf(SwipeMetrics.OUTCOME_NO_GEOMETRY, SwipeMetrics.OUTCOME_NO_POINTS, SwipeMetrics.OUTCOME_NO_VOCABULARY, SwipeMetrics.OUTCOME_NONE)) {
            val o = SwipeMetrics.Outcome(0L, outcome, -1, 0, "")
            assertTrue(o.neverOffered, outcome)
            assertTrue(!o.firstChoice && !o.fromStrip, outcome)
        }
        assertNull(OwnGestureDecoder.lastEarlyReturn.takeIf { it == SwipeMetrics.OUTCOME_NONE }) // (never a gate's name)
    }
}

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
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 20261021_L1 (2026-10-31): the swipe results log names its columns. A new file begins with one `#` header line; every
 * row ends with where the final word sat on the strip (column 13: 1..10 / missed / new / deleted) and the row's format
 * (column 14: v2). Older rows (9 to 13 columns) and the header still read.
 * Fail before: no header, 12 columns, no position.
 */
@RunWith(RobolectricTestRunner::class)
class SwipeResultsFormatTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val results = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "swipe_results.tsv")

    @Before fun setUp() {
        results.delete()
        SwipeMetrics.init(ctx); GestureCorpusRecorder.init(ctx)
        SwipeMetrics.metricsOn = { true }; GestureCorpusRecorder.corpusOn = { false }; SwipeMetrics.privateNow = { false }
        GestureCorpusRecorder.onFieldChanged() // (no swipe left open by another test)
        SwipeMetrics.awaitWrites()
        results.delete()
    }

    @After fun tearDown() {
        SwipeMetrics.metricsOn = { Settings.getValues()?.mSwipeMetrics == true }
        SwipeMetrics.privateNow = { Settings.getValues()?.mIncognitoModeEnabled == true }
        GestureCorpusRecorder.corpusOn = { Settings.getValues()?.mRecordGestureCorpus == true }
        GestureCorpusRecorder.isKnown = { false }
        results.delete()
    }

    private fun lines(): List<String> { SwipeMetrics.awaitWrites(); return results.readLines() }

    @Test fun `a new file begins with one header line, written once`() {
        SwipeMetrics.onOutcome(1L, "hello", SwipeMetrics.OUTCOME_KEPT, -1, "hello", 12L, 4.5f, "t", "1")
        SwipeMetrics.onOutcome(2L, "world", SwipeMetrics.OUTCOME_PICKED, 1, "words", 9L, 5f, "t", "2")
        val lines = lines()
        assertEquals(3, lines.size, "lines: $lines")
        assertEquals(SwipeMetrics.HEADER, lines[0])
        assertTrue(lines[0].startsWith("#"))
        assertEquals(14, lines[0].split('\t').size, "header names every column")
        assertEquals(1, lines.count { it.startsWith("#") }, "one header: $lines")
        // the keyboard started again (another onOutcome into the same file): no second header
        SwipeMetrics.onOutcome(3L, "again", SwipeMetrics.OUTCOME_KEPT, -1, "again", 8L, 4f, "t", "1")
        assertEquals(1, lines().count { it.startsWith("#") })
    }

    @Test fun `a file with rows already in it gets no header`() {
        results.writeText("1700000000000\t1\tkept\t-1\thello\thello\t12\t4.5\tt\n") // (an old 9-column row)
        SwipeMetrics.onOutcome(2L, "world", SwipeMetrics.OUTCOME_KEPT, -1, "world", 9L, 5f, "t", "1")
        val lines = lines()
        assertEquals(2, lines.size, "lines: $lines")
        assertFalse(lines.any { it.startsWith("#") }, "no header into an old file: $lines")
    }

    @Test fun `a restart marker into a new file comes after the header`() {
        SwipeMetrics.restartTracking()
        val lines = lines()
        assertEquals(2, lines.size, "lines: $lines")
        assertEquals(SwipeMetrics.HEADER, lines[0])
        assertEquals("restart", lines[1].split('\t')[2])
        assertEquals(1, SwipeMetrics.read().restarts.size)
    }

    @Test fun `a row has the position (column 13) and the format (column 14)`() {
        SwipeMetrics.onOutcome(5L, "their", SwipeMetrics.OUTCOME_PICKED, 1, "there", 12L, 4.5f, "t", "2")
        val p = lines().last().split('\t')
        assertEquals(14, p.size, "columns: $p")
        assertEquals("5", p[1])
        assertEquals("picked", p[2])
        assertEquals("2", p[12], "position: $p")
        assertEquals("v2", p[13], "format: $p")
        assertEquals(SwipeMetrics.FORMAT_VERSION, p[13])
        // a dead swipe (nothing shown) has an empty position and still the format
        SwipeMetrics.onOutcome(6L, "", SwipeMetrics.OUTCOME_NONE, -1, null, 0L, 0f, "t")
        val d = lines().last().split('\t')
        assertEquals(14, d.size, "columns: $d")
        assertEquals("", d[12])
        assertEquals("v2", d[13])
    }

    /** A swipe of three points with [decoder] words, [strip] the words as shown. */
    private fun swipeShowing(decoder: List<String>, strip: List<String>) {
        val pointers = InputPointers(4).apply { addPointer(10, 10, 0, 0); addPointer(50, 20, 0, 30); addPointer(90, 30, 0, 60) }
        val cands = decoder.map { SuggestedWordInfo(it, "", 100, SuggestedWordInfo.KIND_CORRECTION, null, 0, 0) }
        GestureCorpusRecorder.onSwipe(ComposedData(pointers, true, decoder.first()), Mockito.mock(Keyboard::class.java), cands, "en")
        GestureCorpusRecorder.onShown(strip)
    }

    @Test fun `the position is where the final word sat on the strip, through the recorder`() {
        // kept as shown first: 1
        swipeShowing(listOf("hello", "hallo", "hull"), listOf("hallo", "hull", "hello"))
        GestureCorpusRecorder.onWordCommitted("hallo")
        assertEquals("1", lines().last().split('\t')[12])
        // picked, the third on the strip: 3 (case as the strip capitalizes doesn't matter)
        swipeShowing(listOf("their", "there", "three"), listOf("their", "there", "three"))
        GestureCorpusRecorder.onSuggestionPicked("Three", 2, true)
        assertEquals("3", lines().last().split('\t')[12])
        // edited into a word the keyboard knows but never showed: missed
        GestureCorpusRecorder.isKnown = { it == "throw" }
        swipeShowing(listOf("their", "there"), listOf("their", "there"))
        GestureCorpusRecorder.onWordCommitted("throw")
        assertEquals("missed", lines().last().split('\t')[12])
        // edited into a word it doesn't know: new
        swipeShowing(listOf("their", "there"), listOf("their", "there"))
        GestureCorpusRecorder.onWordCommitted("thrwo")
        assertEquals("new", lines().last().split('\t')[12])
        // deleted
        swipeShowing(listOf("their", "there"), listOf("their", "there"))
        GestureCorpusRecorder.onWordDeleted()
        val p = lines().last().split('\t')
        assertEquals("deleted", p[2])
        assertEquals("deleted", p[12])
    }

    @Test fun `the final word's place in the list, else missed or new word, or deleted`() {
        GestureCorpusRecorder.setCandidatesForTest(listOf("their", "there", "three", "the", "then", "them", "they", "tree", "trek", "thee"))
        GestureCorpusRecorder.isKnown = { it == "throw" }
        val b = GestureCorpusRecorder::bucketOf
        assertEquals(0, b(SwipeMetrics.OUTCOME_KEPT, "their"))
        assertEquals(1, b(SwipeMetrics.OUTCOME_PICKED, "There")) // (case as typed doesn't matter)
        assertEquals(9, b(SwipeMetrics.OUTCOME_EDITED, "thee")) // tenth: never on the strip, still counted
        assertEquals(GestureCorpusRecorder.BUCKET_MISSED, b(SwipeMetrics.OUTCOME_EDITED, "throw"))
        assertEquals(GestureCorpusRecorder.BUCKET_NEW_WORD, b(SwipeMetrics.OUTCOME_EDITED, "thrwo"))
        assertEquals(GestureCorpusRecorder.BUCKET_DELETED, b(SwipeMetrics.OUTCOME_DELETED, null))
        assertEquals("10", GestureCorpusRecorder.positionName(9))
        assertEquals("missed", GestureCorpusRecorder.positionName(GestureCorpusRecorder.BUCKET_MISSED))
        assertEquals("new", GestureCorpusRecorder.positionName(GestureCorpusRecorder.BUCKET_NEW_WORD))
        assertEquals("deleted", GestureCorpusRecorder.positionName(GestureCorpusRecorder.BUCKET_DELETED))
        // "US" and "us" are two places: the exact word first
        GestureCorpusRecorder.setCandidatesForTest(listOf("US", "us", "use"))
        assertEquals(1, b(SwipeMetrics.OUTCOME_PICKED, "us"))
        assertEquals(0, b(SwipeMetrics.OUTCOME_KEPT, "US"))
    }

    @Test fun `the header and rows of every older width still read`() {
        results.writeText(listOf(
            SwipeMetrics.HEADER,
            "1700000000000\t1\tkept\t-1\thello\thello\t12\t4.5\tt", // 9 columns (before the versions)
            "1700000001000\t2\tpicked\t1\tworld\twords\t9\t5.0\tt\t3\t0.3.001", // 11 (before the keyboard start)
            "1700000002000\t3\tedited\t-1\tthere\tthree\t10\t5.5\tt\t3\t0.3.100\ts1", // 12 (public, until today)
            "1700000003000\t4\tdeleted\t-1\tthen\t\t8\t4.0\tt\t3\t0.3.100\ts1\tdeleted", // 13 (position, no format)
            "1700000004000\t5\tkept\t-1\tthem\tthem\t7\t4.2\tt\t3\t0.3.110\ts1\t1\tv2", // 14 (today)
        ).joinToString("\n") + "\n")
        val log = SwipeMetrics.read()
        assertEquals(5, log.outcomes.size, "outcomes: ${log.outcomes.map { it.outcome }}")
        assertEquals(listOf("kept", "picked", "edited", "deleted", "kept"), log.outcomes.map { it.outcome })
        assertEquals(listOf(12, 9, 10, 8, 7), log.outcomes.map { it.decodeMs })
        assertEquals(1, log.outcomes[1].rank)
        assertTrue(log.outcomes.all { it.tuning == "t" })
        assertTrue(log.restarts.isEmpty())
        val s = SwipeMetrics.summaryOf(log.outcomes)
        assertEquals(5, s.swipes)
        assertEquals(2, s.firstChoice) // the two kept
        assertEquals(1, s.fromStrip) // picked at rank 1
        assertEquals(2, s.neverOffered) // edited, deleted
    }
}

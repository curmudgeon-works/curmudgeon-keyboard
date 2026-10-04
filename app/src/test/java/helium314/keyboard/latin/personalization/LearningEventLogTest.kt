// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import kotlin.test.Test
import kotlin.test.assertEquals

class LearningEventLogTest {
    @Test fun `a line has the eleven columns in order`() {
        val line = LearningEventLog.line(1700000000000, LearningEventLog.AUTOCORRECT_REVERTED, LearningEventLog.AUTOCORRECT,
            "the", "teh", 5, 4, -1, -1, "en-US", "0.1.004")
        assertEquals("1700000000000\tautocorrect_reverted\tautocorrect\tthe\tteh\t5\t4\t-1\t-1\ten-US\t0.1.004", line)
    }

    @Test fun `tabs and line breaks in words don't break the columns`() {
        val line = LearningEventLog.line(1, LearningEventLog.ACCEPTED, LearningEventLog.TYPED, "a\tb", "c\nd\r", 0, 1, 0, 1, "de", "v")
        assertEquals(11, line.split('\t').size)
        assertEquals("a b", line.split('\t')[3])
        assertEquals("c d ", line.split('\t')[4])
    }

    @Test fun `the summary counts each event, within the days asked for`() {
        val day = 86_400_000L
        val now = 100 * day
        fun l(time: Long, event: String) = LearningEventLog.line(time, event, LearningEventLog.TYPED, "x", "y", 0, 1, 0, 1, "en", "v")
        val lines = sequenceOf(
            l(now - 10 * day, LearningEventLog.ACCEPTED),
            l(now - day, LearningEventLog.ACCEPTED),
            l(now - day, LearningEventLog.ACCEPTED_EDITED),
            l(now, LearningEventLog.SWIPE_DELETED),
            "garbage line",
            "123\taccepted", // too short: skipped
        )
        val all = LearningEventLog.summarize(lines, 0, now)
        assertEquals(2, all.of(LearningEventLog.ACCEPTED))
        assertEquals(1, all.of(LearningEventLog.ACCEPTED_EDITED))
        assertEquals(1, all.of(LearningEventLog.SWIPE_DELETED))
        assertEquals(0, all.of(LearningEventLog.REMOVED))
        assertEquals(4, all.total)
        val week = LearningEventLog.summarize(lines, 7, now)
        assertEquals(1, week.of(LearningEventLog.ACCEPTED))
        assertEquals(3, week.total)
    }

    @Test fun `undo and redo lines say the uses changed, and the summary tells the untracked ones apart`() {
        val line = LearningEventLog.line(1, LearningEventLog.UNDO, LearningEventLog.STRIP, "hello", "", 5, 1, -1, -1, "en", "v", "-4")
        assertEquals("1\tundo\tstrip\thello\t\t5\t1\t-1\t-1\ten\tv\t-4", line)
        fun l(event: String, origin: String) = LearningEventLog.line(1, event, origin, "", "", -1, -1, -1, -1, "en", "v")
        val lines = sequenceOf(line, l(LearningEventLog.UNDO, LearningEventLog.UNTRACKED), l(LearningEventLog.UNDO, LearningEventLog.NONE),
            l(LearningEventLog.REDO, LearningEventLog.UNTRACKED))
        val s = LearningEventLog.summarize(lines, 0, 2)
        assertEquals(3, s.of(LearningEventLog.UNDO))
        assertEquals(1, s.untrackedOf(LearningEventLog.UNDO))
        assertEquals(1, s.of(LearningEventLog.REDO))
        assertEquals(1, s.untrackedOf(LearningEventLog.REDO))
    }
}

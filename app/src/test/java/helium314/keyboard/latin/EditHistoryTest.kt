// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.inputlogic.EditHistory
import helium314.keyboard.latin.inputlogic.EditHistory.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The keyboard's own undo / redo, on a simulated text field (text before the cursor only). */
class EditHistoryTest {
    private var text = ""
    private val history = EditHistory()

    private fun live() = State(text.length, text.takeLast(EditHistory.WINDOW))

    /** A step: snapshot, then the edit happens. */
    private fun type(s: String) { history.onStepStart(live()); text += s }
    private fun backspace(n: Int) { history.onStepStart(live()); text = text.dropLast(n) }

    private fun apply(e: EditHistory.Edit?): Boolean {
        if (e == null) return false
        text = text.dropLast(e.delete) + e.insert
        return true
    }
    private fun undo(byChar: Boolean = false) = apply(history.undo(live(), byChar))
    private fun redo(byChar: Boolean = false) = apply(history.redo(live(), byChar))

    @Test fun `undo removes whole steps, newest first`() {
        type("hello "); type("world ")
        undo(); assertEquals("hello ", text)
        undo(); assertEquals("", text)
        assertEquals(false, undo())
    }

    @Test fun `redo puts undone steps back`() {
        type("hello "); type("world ")
        undo(); undo()
        redo(); assertEquals("hello ", text)
        redo(); assertEquals("hello world ", text)
        assertEquals(false, redo())
    }

    @Test fun `undo restores what a run of backspaces removed`() {
        type("hello world")
        backspace(5)
        assertEquals("hello ", text)
        undo(); assertEquals("hello world", text)
        redo(); assertEquals("hello ", text)
    }

    @Test fun `one character per press, then the next step`() {
        type("ab "); type("cd")
        undo(true); assertEquals("ab c", text)
        undo(true); assertEquals("ab ", text)
        undo(true); assertEquals("ab", text)
    }

    @Test fun `redo halfway through a character undo goes back to where it started`() {
        type("ab "); type("cd")
        undo(true); undo(true)
        redo(true); assertEquals("ab c", text)
        redo(true); assertEquals("ab cd", text)
        assertEquals(false, redo(true))
    }

    @Test fun `new input after undo ends redo`() {
        type("one "); type("two ")
        undo()
        history.onOtherInput(); type("three ")
        assertEquals(false, redo())
        undo(); assertEquals("one ", text)
    }

    @Test fun `only maxSteps steps back`() {
        val h = EditHistory(2)
        fun t(s: String) { h.onStepStart(State(text.length, text)); text += s }
        t("a"); t("b"); t("c"); t("d")
        fun u() = h.undo(State(text.length, text), false)?.let { text = text.dropLast(it.delete) + it.insert } != null
        u(); u()
        assertEquals("ab", text)
        assertEquals(false, u())
    }

    @Test fun `text that no longer lines up is left alone`() {
        type("hello ")
        type("world")
        text = "HELLO world" // the app rewrote the start of the field
        assertNull(history.undo(State(text.length, text.takeLast(3)), false))
    }

    @Test fun `emoji are whole characters`() {
        type("hi ")
        type("😀")
        undo(true); assertEquals("hi ", text)
        redo(true); assertEquals("hi 😀", text)
    }

    @Test fun `a replaced word is undone whole`() {
        type("teh") // autocorrect then replaces it within the same step
        text = "the "
        undo(); assertEquals("", text)
        redo(); assertEquals("the ", text)
    }
}

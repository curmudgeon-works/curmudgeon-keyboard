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

    // ---- what each step learned: undo takes it back, redo gives it again, each once ----

    private fun learned(word: String, uses: Int) = history.onLearned(EditHistory.Learning(word, uses, "typed"), false)
    private fun changes() = history.takeLearning().map { it.word to it.uses }

    @Test fun `a step's learning is taken back when it is all undone, given again when it is all redone`() {
        type("hello "); learned("hello", 1)
        type("world "); learned("world", 4)
        undo(); assertEquals(listOf("world" to -4), changes())
        undo(); assertEquals(listOf("hello" to -1), changes())
        redo(); assertEquals(listOf("hello" to 1), changes())
        redo(); assertEquals(listOf("world" to 4), changes())
        assertEquals(false, redo()); assertEquals(listOf(), changes())
    }

    @Test fun `one character at a time, the learning changes once the step is all undone or redone`() {
        type("hello "); learned("hello", 1)
        type("ok "); learned("ok", 1)
        undo(true); undo(true); assertEquals(listOf(), changes())
        undo(true); assertEquals("hello ", text); assertEquals(listOf("ok" to -1), changes())
        redo(true); undo(true); assertEquals(listOf(), changes()) // half way back and again: nothing
        redo(true); redo(true); assertEquals(listOf(), changes())
        redo(true); assertEquals("hello ok ", text); assertEquals(listOf("ok" to 1), changes())
        undo(true); redo(true); assertEquals(listOf(), changes())
    }

    @Test fun `a correction step reverses both its changes`() {
        type("helo "); learned("helo", 1)
        backspace(2); text += "lo "; history.onLearned(EditHistory.Learning("helo", -1, "edit"), false)
        learned("hello", 4)
        undo(); assertEquals("helo ", text); assertEquals(listOf("hello" to -4, "helo" to 1), changes())
        redo(); assertEquals(listOf("helo" to -1, "hello" to 4), changes())
    }

    @Test fun `typing after an undo drops the undone step's learning, the new typing keeps its own`() {
        type("hello "); learned("hello", 1)
        type("world "); learned("world", 1)
        undo(); changes()
        history.onOtherInput(); type("there "); learned("there", 1)
        undo(); assertEquals(listOf("there" to -1), changes())
        undo(); assertEquals(listOf("hello" to -1), changes())
        redo(); redo(); assertEquals(listOf("hello" to 1, "there" to 1), changes())
    }

    @Test fun `a word committed after the next step began belongs to the step that typed it`() {
        type("hello"); type(" world") // the next step began with hello still being composed
        history.onLearned(EditHistory.Learning("hello", 1, "swiped"), true)
        undo(); assertEquals(listOf(), changes())
        undo(); assertEquals(listOf("hello" to -1), changes())
    }

    @Test fun `a cleared history forgets the learning`() {
        type("hello "); learned("hello", 1)
        undo(); changes()
        history.clear()
        assertEquals(false, redo()); assertEquals(listOf(), changes())
    }

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

    // ---- a whole field with a cursor anywhere: typing after a tap elsewhere is a jump ----

    private class Field(val history: EditHistory = EditHistory()) {
        var doc = ""; var cursor = 0
        fun stateAt(pos: Int) = State(pos, doc.substring(0, pos))
        fun live() = stateAt(cursor)
        fun type(s: String) { history.onStepStart(live()); doc = doc.substring(0, cursor) + s + doc.substring(cursor); cursor += s.length }
        /** Typing after a tap: the history learns where typing ended ([typedAt]). */
        fun typeAfterTap(typedAt: Int, s: String) {
            history.onJump(stateAt(typedAt), live())
            doc = doc.substring(0, cursor) + s + doc.substring(cursor); cursor += s.length
        }
        fun apply(e: EditHistory.Edit?): Boolean {
            if (e == null) return false
            if (e.moveTo >= 0) { assertEquals(e.expect, stateAt(e.moveTo)); cursor = e.moveTo }
            doc = doc.substring(0, cursor - e.delete) + e.insert + doc.substring(cursor)
            cursor += e.insert.length - e.delete
            return true
        }
    }

    @Test fun `typing after a tap elsewhere - undo walks back across the jump in one press each`() {
        val f = Field()
        f.type("the "); f.type("keyboard") // cursor at 12
        f.cursor = 7 // a tap: key|board
        f.typeAfterTap(12, "x")
        assertEquals("the keyxboard", f.doc)
        f.apply(f.history.undo(f.live(), false)); assertEquals("the keyboard", f.doc); assertEquals(7, f.cursor)
        // back where the typing ended, and that step undone in the same press
        f.apply(f.history.undo(f.live(), false)); assertEquals("the ", f.doc); assertEquals(4, f.cursor)
        f.apply(f.history.redo(f.live(), false)); assertEquals("the keyboard", f.doc); assertEquals(12, f.cursor)
        f.apply(f.history.redo(f.live(), false)); assertEquals("the keyxboard", f.doc); assertEquals(8, f.cursor)
    }

    @Test fun `undo right after a tap works as if the cursor were still where typing ended`() {
        val f = Field()
        f.type("the "); f.type("keyboard")
        f.cursor = 7 // a tap; undo is given the text before where typing ended, then the cursor goes there
        val e = f.history.undo(f.stateAt(12), false)!!
        f.cursor = 12; f.apply(e)
        assertEquals("the ", f.doc)
    }
}

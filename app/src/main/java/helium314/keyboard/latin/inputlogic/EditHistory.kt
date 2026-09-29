// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

/**
 * The keyboard's own undo / redo. Where an edit step begins (a word starts, a swipe, a paste, a run of backspaces)
 * the text just before the cursor is remembered; undo and redo turn the text before the cursor back into the
 * neighbouring snapshot. They only ever delete and type at the cursor, so they work in any field, including ones
 * without an undo of their own. When the text no longer lines up with a snapshot (the app changed it, or the cursor
 * went elsewhere) the history is dropped instead of editing the wrong place.
 *
 * A step is undone whole, or one character per press; either way redo walks back the same way.
 */
class EditHistory(maxSteps: Int = 20) {
    /** The text just before the cursor (at most [WINDOW] chars of it) and where the cursor was. */
    data class State(val cursor: Int, val text: String) {
        val start get() = cursor - text.length
    }

    /** Delete [delete] chars before the cursor, then type [insert] there. */
    data class Edit(val delete: Int, val insert: String)

    /** How many steps back undo reaches. */
    var maxSteps = maxSteps.coerceAtLeast(1)
        set(value) { field = value.coerceAtLeast(1); trim() }

    private val states = ArrayList<State>()
    private var current = -1 // the snapshot the text is at, or is moving away from during undo / redo
    private var moving = 0   // -1 after undo, +1 after redo, until any other input; 0 otherwise

    fun clear() {
        states.clear()
        current = -1
        moving = 0
    }

    /** Any input other than undo / redo: once the text changes by other means, there is nothing left to redo. */
    fun onOtherInput() {
        if (moving == 0) return
        truncateAfterCurrent()
        moving = 0
    }

    /** An edit step is about to begin; [live] is the text as it is now. */
    fun onStepStart(live: State) {
        onOtherInput()
        if (current >= 0 && states[current] == live) return
        truncateAfterCurrent()
        states.add(live)
        current = states.lastIndex
        trim()
    }

    /** What to change to undo, or null if there is nothing (left) to undo. */
    fun undo(live: State, byCharacter: Boolean): Edit? {
        if (current < 0) return null
        val target = if (moving == 1 && live != states[current]) {
            current // halfway through a redo: back to where it started
        } else {
            if (moving == 0 && live != states[current]) {
                // typed since the last snapshot: keep that reachable for redo
                truncateAfterCurrent()
                states.add(live)
                current = states.lastIndex
                trim()
            }
            if (current == 0) return null
            current - 1
        }
        return move(live, target, byCharacter, -1)
    }

    /** What to change to redo, or null if there is nothing to redo. */
    fun redo(live: State, byCharacter: Boolean): Edit? {
        if (current < 0 || moving == 0) return null
        val target = when {
            moving == -1 && live != states[current] -> current // halfway through an undo: forward to where it started
            current < states.lastIndex -> current + 1
            else -> return null
        }
        return move(live, target, byCharacter, 1)
    }

    private fun move(live: State, target: Int, byCharacter: Boolean, direction: Int): Edit? {
        val full = diff(live, states[target]) ?: run { clear(); return null }
        moving = direction
        if (full.delete == 0 && full.insert.isEmpty()) {
            // already there (a step that changed nothing): take the next one
            current = target
            return if (direction < 0) undo(live, byCharacter) else redo(live, byCharacter)
        }
        val edit = if (byCharacter) oneCharacter(live, full) else full
        if (edit == full) current = target
        return edit
    }

    private fun truncateAfterCurrent() {
        while (states.size > current + 1) states.removeAt(states.lastIndex)
    }

    private fun trim() {
        while (states.size > maxSteps + 1) {
            states.removeAt(0)
            current--
        }
        if (current < 0 && states.isNotEmpty()) current = 0
    }

    companion object {
        /** How much text before the cursor a snapshot holds. */
        const val WINDOW = 1000

        /**
         * What turns [live] into [target], both being the text before the cursor: delete back to where they part,
         * then type the rest of [target]. Null if they can't be lined up: their windows don't overlap, or they
         * already differ at the start of a window, where the text before it is unknown.
         */
        fun diff(live: State, target: State): Edit? {
            val start = maxOf(live.start, target.start)
            val li = start - live.start
            val ti = start - target.start
            if (li > live.text.length || ti > target.text.length) return null
            var common = 0
            while (li + common < live.text.length && ti + common < target.text.length
                && live.text[li + common] == target.text[ti + common]) common++
            // never split a surrogate pair
            if (common > 0 && Character.isHighSurrogate(live.text[li + common - 1])
                && (li + common < live.text.length || ti + common < target.text.length)) common--
            val bothContinue = li + common < live.text.length && ti + common < target.text.length
            if (start > 0 && common == 0 && bothContinue) return null
            return Edit(live.text.length - (li + common), target.text.substring(ti + common))
        }

        /** The first character of [full]: one character deleted (whole code point), or else one typed. */
        fun oneCharacter(live: State, full: Edit): Edit {
            if (full.delete > 0) {
                val cp = live.text.codePointBefore(live.text.length)
                return Edit(Character.charCount(cp), "")
            }
            val cp = full.insert.codePointAt(0)
            return Edit(0, full.insert.substring(0, Character.charCount(cp)))
        }
    }
}

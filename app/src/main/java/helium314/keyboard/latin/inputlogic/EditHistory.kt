// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.NgramContext

/**
 * The keyboard's own undo / redo. Where an edit step begins (a word starts, a swipe, a paste, a run of backspaces)
 * the text just before the cursor is remembered; undo and redo turn the text before the cursor back into the
 * neighbouring snapshot. They only ever delete and type at the cursor, so they work in any field, including ones
 * without an undo of their own. When the text no longer lines up with a snapshot (the app changed it, or the cursor
 * went elsewhere) the history is dropped instead of editing the wrong place.
 * Typing somewhere else after a tap keeps the history: that snapshot is marked as a jump, and undoing past it moves
 * the cursor back to where the earlier typing ended (redo moves it forward again).
 *
 * A step is undone whole, or one character per press; either way redo walks back the same way.
 *
 * Each step also keeps what it changed in the learned words ([onLearned]): undoing the step takes exactly that back
 * once its text is all undone, redoing it gives exactly that again once its text is all back ([takeLearning]). Undone
 * halfway (one character per press) nothing changes yet, and a step whose learning is undone can't be undone twice,
 * so undo and redo back and forth always net to zero. Where the history is dropped, the learning goes with it.
 */
class EditHistory(maxSteps: Int = 20) {
    /** The text just before the cursor (at most [WINDOW] chars of it) and where the cursor was. */
    data class State(val cursor: Int, val text: String) {
        val start get() = cursor - text.length
    }

    /** Move the cursor to [moveTo] first (when not -1; the text before it must then be [expect], or nothing is done),
     *  then delete [delete] chars before the cursor and type [insert]. */
    data class Edit(val delete: Int, val insert: String, val moveTo: Int = -1, val expect: State? = null)

    /** A step changed the learned words: [word] got [uses] more uses (negative: uses taken back), counted as [origin]
     *  (a corrections log origin); [autoCapitalized] and [context] as it was learned, to learn it the same way again. */
    class Learning(val word: String, val uses: Int, val origin: String, val autoCapitalized: Boolean = false,
                   val context: NgramContext = NgramContext.EMPTY_PREV_WORDS_INFO)

    /** How many steps back undo reaches. */
    var maxSteps = maxSteps.coerceAtLeast(1)
        set(value) { field = value.coerceAtLeast(1); trim() }

    private val states = ArrayList<State>()
    private val jumps = ArrayList<Boolean>() // per snapshot: it starts at another place than the one before it
    private val learned = ArrayList<MutableList<Learning>>() // per snapshot: the learning of the step from it on
    private val undone = ArrayList<Boolean>() // per snapshot: that step's learning is taken back (the step is undone)
    private val pendingLearning = ArrayList<Learning>() // what the last undo / redo changes in the learned words
    private var pendingSteps = 0 // how many steps the last undo / redo finished
    private val pendingFlips = ArrayList<Int>() // the steps whose learning the last undo / redo took back or gave
    private var current = -1 // the snapshot the text is at, or is moving away from during undo / redo
    private var moving = 0   // -1 after undo, +1 after redo, until any other input; 0 otherwise

    /** Whether there is any history at all. */
    val isEmpty get() = states.isEmpty()

    fun clear() {
        states.clear()
        jumps.clear()
        learned.clear()
        undone.clear()
        pendingLearning.clear()
        pendingSteps = 0
        pendingFlips.clear()
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
        add(live, false)
    }

    /** Typing starts at another place ([here]) than where it ended ([there], the text before that place now). */
    fun onJump(there: State, here: State) {
        onOtherInput()
        truncateAfterCurrent()
        if (current < 0 || states[current] != there) add(there, false) // typed since the last snapshot: keep it reachable
        add(here, true)
    }

    /**
     * The step being typed (or, with [previousStep], the one before it: a word still being composed when the current
     * step began is committed into the step that typed it) changed the learned words. Call only after the step start
     * of the input that learned it. Nothing is kept while undo / redo is under way (the history can't tell the step).
     */
    fun onLearned(learning: Learning, previousStep: Boolean) {
        if (moving != 0 || learning.uses == 0) return
        val i = if (previousStep && current > 0 && !jumps[current]) current - 1 else current
        if (i < 0) return
        learned[i].add(learning)
    }

    /** What the last [undo] or [redo] changed in the learned words, each with the uses to give (negative: take back);
     *  empty when nothing. Read once: it's emptied. */
    fun takeLearning(): List<Learning> {
        val result = ArrayList(pendingLearning)
        pendingLearning.clear()
        pendingFlips.clear()
        return result
    }

    /** The last [undo] / [redo] wasn't carried out after all (nothing in the text changed): its steps keep their
     *  learning as it was. Call before [takeLearning]. */
    fun cancelLearning() {
        for (step in pendingFlips) if (step < undone.size) undone[step] = !undone[step]
        pendingFlips.clear()
        pendingLearning.clear()
    }

    /** How many steps the last [undo] or [redo] finished (0: it went one character into a step). Read once. */
    fun takeFinishedSteps(): Int = pendingSteps.also { pendingSteps = 0 }

    // The text is now at snapshot [target], having been undone ([direction] -1) or redone (+1) into it: the step it
    // finished (from [target] on when undone, the one before it when redone) takes back or gives its learning, once.
    private fun finished(target: Int, direction: Int) {
        val step = if (direction < 0) target else target - 1
        if (step < 0 || step >= learned.size) return
        pendingSteps++
        if (direction < 0 && !undone[step]) {
            undone[step] = true
            pendingFlips.add(step)
            for (l in learned[step].asReversed()) pendingLearning.add(Learning(l.word, -l.uses, l.origin, l.autoCapitalized, l.context))
        } else if (direction > 0 && undone[step]) {
            undone[step] = false
            pendingFlips.add(step)
            pendingLearning.addAll(learned[step])
        }
    }

    private fun add(state: State, jump: Boolean) {
        states.add(state)
        jumps.add(jump)
        learned.add(ArrayList())
        undone.add(false)
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
                add(live, false)
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
        // across a jump (the text is as it was there): only the cursor moves, to where the other place's text ends
        // (and the step there in the same press)
        if (live == states[current] && (if (direction < 0) jumps[current] && target == current - 1 else target == current + 1 && jumps[target])) {
            moving = direction
            current = target
            finished(target, direction)
            val there = states[target]
            val next = if (direction < 0) undo(there, byCharacter) else redo(there, byCharacter)
            return (next ?: Edit(0, "")).copy(moveTo = there.cursor, expect = there)
        }
        val full = diff(live, states[target]) ?: run { clear(); return null }
        moving = direction
        if (full.delete == 0 && full.insert.isEmpty()) {
            // already there (a step that changed nothing): take the next one
            current = target
            finished(target, direction)
            return if (direction < 0) undo(live, byCharacter) else redo(live, byCharacter)
        }
        val edit = if (byCharacter) oneCharacter(live, full) else full
        if (edit == full) {
            current = target
            finished(target, direction)
        }
        return edit
    }

    private fun truncateAfterCurrent() {
        if (states.size <= current + 1) return
        // the step from the current snapshot on ends elsewhere now: what it learned is over (taken back if undone)
        learned[current].clear()
        undone[current] = false
        while (states.size > current + 1) {
            states.removeAt(states.lastIndex)
            jumps.removeAt(jumps.lastIndex)
            learned.removeAt(learned.lastIndex)
            undone.removeAt(undone.lastIndex)
        }
    }

    private fun trim() {
        while (states.size > maxSteps + 1) {
            states.removeAt(0)
            jumps.removeAt(0)
            learned.removeAt(0)
            undone.removeAt(0)
            current--
        }
        if (jumps.isNotEmpty()) jumps[0] = false // nothing before the first one to jump to
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

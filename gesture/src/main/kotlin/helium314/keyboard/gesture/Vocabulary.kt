// SPDX-License-Identifier: GPL-3.0-only
// Trie vocabulary for the gesture decoder, populated from the binary dictionary /
// user history. Children are stored as parallel arrays (char + node) instead of a
// HashMap: at 50k words the trie has ~150k nodes, and boxed-char hash maps cost
// several times the memory and iterate slower in the decoder's hot trie walk.
package helium314.keyboard.gesture

/** Trie of (word, frequency). Frequencies must be positive. */
class Vocabulary(entries: Iterable<Pair<String, Int>>) {

    class Node {
        @Volatile var word: String? = null
            internal set
        @Volatile var frequency: Int = 0
            internal set
        // chars and nodes in one immutable holder, replaced in a single write: a learned word can be added while a
        // swipe is being decoded on another thread, and a reader never sees chars and nodes of different sizes
        // (children are only ever appended, so an index read from an older holder stays valid in a newer one)
        private class Children(val chars: CharArray, val nodes: Array<Node?>)
        @Volatile private var children = EMPTY

        val childCount: Int get() = children.chars.size
        fun childCharAt(i: Int): Char = children.chars[i]
        fun childAt(i: Int): Node = children.nodes[i]!!

        fun child(c: Char): Node? {
            val ch = children
            for (i in ch.chars.indices) if (ch.chars[i] == c) return ch.nodes[i]
            return null
        }

        internal fun getOrPut(c: Char): Node {
            child(c)?.let { return it }
            val node = Node()
            val ch = children
            val n = ch.chars.size
            children = Children(ch.chars.copyOf(n + 1).also { it[n] = c }, ch.nodes.copyOf(n + 1).also { it[n] = node })
            return node
        }

        companion object {
            private val EMPTY = Children(CharArray(0), arrayOfNulls(0))
        }
    }

    val root = Node()
    @Volatile var maxFrequency: Int = 1
        private set
    @Volatile var size: Int = 0
        private set

    init {
        for ((word, freq) in entries) add(word, freq)
    }

    /** Adds a word or raises its frequency. Safe while other threads read the trie; callers must not add concurrently. */
    @Synchronized
    fun add(word: String, frequency: Int) {
        if (word.isEmpty() || frequency <= 0) return
        var node = root
        for (c in word) node = node.getOrPut(c.lowercaseChar())
        if (node.word == null) size++
        // keep the casing of the highest-frequency variant (trie keys are lowercased,
        // stored words keep original casing so e.g. proper nouns display correctly)
        if (frequency >= node.frequency) {
            node.frequency = frequency
            node.word = word // last: a reader that sees the word sees its frequency
        }
        if (frequency > maxFrequency) maxFrequency = frequency
    }

    fun contains(word: String): Boolean = find(word)?.word != null

    fun frequencyOf(word: String): Int = find(word)?.frequency ?: 0

    private fun find(word: String): Node? {
        var node = root
        for (c in word) node = node.child(c.lowercaseChar()) ?: return null
        return node
    }
}

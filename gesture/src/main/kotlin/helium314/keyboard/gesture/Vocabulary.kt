// SPDX-License-Identifier: GPL-3.0-only
// Trie vocabulary for the gesture decoder, populated from the binary dictionary /
// user history. Children are stored as parallel arrays (char + node) instead of a
// HashMap: at 50k words the trie has ~150k nodes, and boxed-char hash maps cost
// several times the memory. The decoder looks words up by their first and last
// letters ([wordsByEnds]), Swype-style, not by walking the trie.
package helium314.keyboard.gesture

import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Words by (first char, last char) of their lowercased form, built on first use and then kept up to date by
     * [add]. Buckets are replaced, never changed in place, so a decode on another thread always reads a whole array.
     */
    @Volatile private var ends: Ends? = null

    private class Ends(
        val buckets: ConcurrentHashMap<Int, Array<Node>>,
        @Volatile var firstChars: CharArray,
        @Volatile var lastChars: CharArray,
    )

    /** The distinct first chars of all words (lowercase). */
    fun firstChars(): CharArray = endsIndex().firstChars

    /** The distinct last chars of all words (lowercase). */
    fun lastChars(): CharArray = endsIndex().lastChars

    /** The words starting with [first] and ending with [last] (lowercase chars); read [Node.word] and [Node.frequency] live. */
    fun wordsByEnds(first: Char, last: Char): Array<Node> = endsIndex().buckets[endsKey(first, last)] ?: NO_NODES

    private fun endsIndex(): Ends = ends ?: buildEnds()

    @Synchronized
    private fun buildEnds(): Ends {
        ends?.let { return it }
        val lists = HashMap<Int, ArrayList<Node>>()
        fun collect(node: Node, first: Char, c: Char) {
            if (node.word != null) lists.getOrPut(endsKey(first, c)) { ArrayList() }.add(node)
            for (i in 0 until node.childCount) collect(node.childAt(i), first, node.childCharAt(i))
        }
        for (i in 0 until root.childCount) collect(root.childAt(i), root.childCharAt(i), root.childCharAt(i))
        val buckets = ConcurrentHashMap<Int, Array<Node>>(lists.size * 2)
        for ((k, v) in lists) buckets[k] = v.toTypedArray()
        return Ends(buckets, lists.keys.map { (it ushr 16).toChar() }.distinct().toCharArray(),
            lists.keys.map { (it and 0xFFFF).toChar() }.distinct().toCharArray()).also { ends = it }
    }

    /** Adds a word or raises its frequency. Safe while other threads read the trie; callers must not add concurrently. */
    @Synchronized
    fun add(word: String, frequency: Int) {
        if (word.isEmpty() || frequency <= 0) return
        var node = root
        for (c in word) node = node.getOrPut(c.lowercaseChar())
        if (node.word == null) {
            size++
            ends?.let { e ->
                val first = word.first().lowercaseChar()
                val last = word.last().lowercaseChar()
                val k = endsKey(first, last)
                e.buckets[k] = (e.buckets[k] ?: NO_NODES) + node
                if (first !in e.firstChars) e.firstChars += first
                if (last !in e.lastChars) e.lastChars += last
            }
        }
        // keep the casing of the highest-frequency variant (trie keys are lowercased,
        // stored words keep original casing so e.g. proper nouns display correctly)
        if (frequency >= node.frequency) {
            node.frequency = frequency
            node.word = word // last: a reader that sees the word sees its frequency
        }
        if (frequency > maxFrequency) maxFrequency = frequency
    }

    /**
     * Takes a word out (any casing: trie keys are lowercase), e.g. one the user removed. Safe while other threads read
     * the trie (a decode that already holds the node sees no word and skips it); callers must not change it concurrently.
     */
    @Synchronized
    fun remove(word: String): Boolean {
        val node = find(word) ?: return false
        if (node.word == null) return false
        node.word = null // first: a reader that sees no word skips the node
        node.frequency = 0
        size--
        ends?.let { e ->
            val k = endsKey(word.first().lowercaseChar(), word.last().lowercaseChar())
            e.buckets[k]?.let { bucket -> e.buckets[k] = bucket.filter { it !== node }.toTypedArray() }
        }
        return true
    }

    fun contains(word: String): Boolean = find(word)?.word != null

    fun frequencyOf(word: String): Int = find(word)?.frequency ?: 0

    private fun find(word: String): Node? {
        var node = root
        for (c in word) node = node.child(c.lowercaseChar()) ?: return null
        return node
    }

    private companion object {
        val NO_NODES = arrayOf<Node>()
        fun endsKey(first: Char, last: Char): Int = (first.code shl 16) or last.code
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import java.io.File
import java.util.Locale

/**
 * Stores of learned words as plain text, for tests (the native library isn't there): a store is a directory, as the
 * real ones are, holding its entries one per line. Writing keeps the entries as they are: that the native replay gives
 * back what it's fed is LearnedWordsMergeTest's.
 */
class FakeLearnedStoreIo : LearnedStoreIo {
    var failWrites = false

    override fun readFile(dir: File, locale: Locale): List<LearnedEntry>? {
        val file = File(dir, FILE)
        if (!file.isFile) return null
        return file.readLines().filter { it.isNotEmpty() }.map { parse(it) }
    }

    override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>): Boolean {
        if (failWrites) return false
        dir.mkdirs()
        File(dir, FILE).writeText(entries.joinToString("") { format(it) + "\n" })
        return true
    }

    override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? = null

    companion object {
        private const val FILE = "entries"

        fun format(e: LearnedEntry) = listOf(e.word, e.count, e.time, e.context.joinToString("\u0001")).joinToString("\t")

        fun parse(line: String): LearnedEntry {
            val p = line.split('\t')
            return LearnedEntry(p[0], if (p[3].isEmpty()) emptyList() else p[3].split('\u0001'), p[1].toInt(), p[2].toInt())
        }

        /** Writes a store with [entries] where the app would have one. */
        fun store(dir: File, vararg entries: LearnedEntry) {
            FakeLearnedStoreIo().writeFile(dir, Locale.ROOT, entries.toList())
        }

        /** The entries of the store in [dir] by "context… word" (oldest word first), with count and time. */
        fun read(dir: File): Map<String, Pair<Int, Int>> =
            FakeLearnedStoreIo().readFile(dir, Locale.ROOT).orEmpty().associate { key(it) to (it.count to it.time) }

        fun key(e: LearnedEntry) = (e.context.reversed() + e.word).joinToString(" ")
    }
}

/** A word alone. */
fun word(word: String, count: Int, time: Int) = LearnedEntry(word, emptyList(), count, time)
/** [word] after [before] (the words before it in reading order). */
fun after(vararg before: String, word: String, count: Int, time: Int) = LearnedEntry(word, before.reversed(), count, time)

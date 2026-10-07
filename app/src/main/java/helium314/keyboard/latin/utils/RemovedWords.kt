// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.personalization.LearnedStoreMigration
import helium314.keyboard.latin.personalization.LearnedStores
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The blacklists (long-press Remove: every removed word, with how often it was removed), kept as files: one per script,
 * like the learned words (see LearnedStores: blacklists/<script>.txt, a keyboard's own blacklists/k<id>/<script>.txt), so
 * a word removed while typing English is removed for Hinglish too; a word goes by its own script.
 * Generic over the file, so a further list of the kind is one more function here.
 *
 * One object per file for the whole process: the keyboard's dictionaries (DictionaryGroup), the spell checker, the swipe
 * vocabularies and the settings screen "Learned & blacklisted words" all hold the same one, so an edit made in the
 * settings is what the running keyboard checks against right away (settings and keyboard run in the same process).
 *
 * Strikes: each Remove of a word is one more, and the more it has, the harder it comes back by typing ([isRemoved], the
 * rule every caller goes through). File: a word per line, "word<TAB>strikes" from the 2nd strike on and
 * "<TAB>confirmed" once a 3rd-strike word was brought back; a plain "word" line (all of them before strikes) is 1 strike.
 */
object RemovedWords {
    private val lists = ConcurrentHashMap<String, WordListFile>()
    // file writes and re-reads, in order
    private val io = Executors.newSingleThreadExecutor { Thread(it, "RemovedWords") }

    /** Long-press Remove: the list of [script] in [pool] (see LearnedStores). */
    @JvmStatic
    fun blacklist(context: Context, script: String, pool: Int = LearnedStores.currentPool): WordListFile =
        forFile(LearnedStores.blacklistFile(context.filesDir, script, pool))

    /** The list [word] belongs to: its own script's (from its letters; [fallback]'s for a word without any). */
    fun blacklistFor(context: Context, word: String, fallback: Locale, pool: Int = LearnedStores.currentPool): WordListFile =
        blacklist(context, LearnedStores.scriptOf(word, fallback), pool)

    fun forFile(file: File): WordListFile = lists.getOrPut(file.absolutePath) { WordListFile(file) }

    /** The list object for [file] if anything holds it already, else null. */
    fun cached(file: File): WordListFile? = lists[file.absolutePath]

    // ------------------------------- the rule -------------------------------

    /** A removed word: removed [strikes] times; [confirmed]: brought back with the strip's "+" (3rd strike). */
    data class Entry(val strikes: Int = 1, val confirmed: Boolean = false) {
        /** The same word in another list or spelling: the one removed more often counts (and its confirmation). */
        fun max(other: Entry?): Entry = when {
            other == null || other.strikes < strikes -> this
            other.strikes > strikes -> other
            else -> Entry(strikes, confirmed || other.confirmed)
        }
    }

    /** How often a word removed [strikes] times must be typed again to come back; null: never by typing (Un-blacklist only). */
    fun requiredUses(strikes: Int): Int? = when {
        strikes <= 1 -> 1
        strikes <= 3 -> 3
        else -> null
    }

    /** The 3rd strike: typed often enough, it still waits for a tap on the strip's "+" next to it. */
    fun needsConfirmation(strikes: Int) = strikes == 3

    /**
     * Typed often enough since the removal. [learnedCount]: ExpandableBinaryDictionary.getLearnedCount of its learned
     * copy (-1: none). Remove deleted that copy, and a removed word is learned as not-a-word until it's back
     * (DictionaryFacilitatorImpl.addWordToUserHistory), which stores it at count 0 by its first use and adds 1 per further
     * use: n uses are count n - 1.
     */
    fun hasEnoughUses(strikes: Int, learnedCount: Int): Boolean {
        val uses = requiredUses(strikes) ?: return false
        return learnedCount >= uses - 1
    }

    /** Still out: never suggested, swiped, auto-corrected to, nor spelled right. False for a word not removed ([entry] null). */
    fun isRemoved(entry: Entry?, learnedCount: Int): Boolean {
        if (entry == null) return false
        return !hasEnoughUses(entry.strikes, learnedCount) || (needsConfirmation(entry.strikes) && !entry.confirmed)
    }

    /** Typed often enough on its 3rd strike, not brought back yet: the strip offers its "+". */
    fun awaitsConfirmation(entry: Entry?, learnedCount: Int): Boolean =
        entry != null && needsConfirmation(entry.strikes) && !entry.confirmed && hasEnoughUses(entry.strikes, learnedCount)

    // ------------------------------- the file -------------------------------

    /** "word", or "word<TAB>N" (with a "<TAB>confirmed" after), as [format] writes; null for an empty line. */
    internal fun parse(line: String): Pair<String, Entry>? {
        val parts = line.split('\t')
        val word = parts[0]
        if (word.isEmpty()) return null
        val strikes = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return word to Entry(strikes, parts.drop(2).any { it.trim() == CONFIRMED })
    }

    /** A plain word for a single strike: the lines everything before strikes wrote and read. */
    internal fun format(word: String, entry: Entry): String = when {
        entry.confirmed -> "$word\t${entry.strikes}\t$CONFIRMED"
        entry.strikes > 1 -> "$word\t${entry.strikes}"
        else -> word
    }

    /** [lines] of a list file read into entries (a word listed twice: the most strikes). */
    internal fun parseAll(lines: List<String>): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        for (line in lines) {
            val (word, entry) = parse(line) ?: continue
            result[word] = entry.max(result[word])
        }
        return result
    }

    /** Lists merged into one: every word of each, the most strikes of a word on several (and its confirmation). */
    fun mergeLists(lists: List<Map<String, Entry>>): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        for (list in lists) for ((word, entry) in list) result[word] = entry.max(result[word])
        return result
    }

    /** The lines [WordListFile] writes for [entries]. */
    fun formatAll(entries: Map<String, Entry>): String =
        entries.entries.sortedBy { it.key }.joinToString("") { format(it.key, it.value) + "\n" }

    class WordListFile internal constructor(val file: File) {
        private val byWord = ConcurrentHashMap<String, Entry>()
        // changed in memory, not written yet (null: taken off): a re-read meanwhile must not undo them
        private val pending = HashMap<String, Entry?>()
        // strikes made before the file was read (a Remove during the one-time migration): added to the file's count
        // when the read catches up, not written over it (re-review 2026-10-07)
        private val strikesWhileLoading = HashMap<String, Int>()
        @Volatile private var loaded = false
        private val lock = Any()

        fun contains(word: String) = byWord.containsKey(word)

        /** [word]'s entry, null if it isn't listed. */
        fun entry(word: String): Entry? = byWord[word]

        /** The entry for [word] as typed or in lowercase (Remove takes a word out in every capitalization). */
        fun entryFor(word: String): Entry? {
            val lower = word.lowercase()
            return byWord[word]?.max(if (lower != word) byWord[lower] else null) ?: byWord[lower]
        }

        /** Reads the file first if that hasn't happened yet (call off the main thread, or where a short read is fine). */
        fun ensureLoaded() { if (!loaded) read() }

        /** All words (read from the file first if that hasn't happened yet: call off the main thread). */
        fun words(): List<String> = entries().keys.toList()

        /** All words with their entries (read from the file first if that hasn't happened yet: call off the main thread). */
        fun entries(): Map<String, Entry> {
            ensureLoaded()
            return byWord.toMap()
        }

        /** Reads the file again now, after what's being written (call off the main thread). */
        fun reload() { io.submit { read() }.get() }

        /** Re-reads the file in the background (the keyboard does it when its dictionaries are set up, e.g. after a restore). */
        fun reloadAsync() = io.execute { read() }

        private fun read() {
            // (while the per-language lists of before are merged into per-script ones, see LearnedStoreMigration;
            // outside the lock, so a waiting read doesn't block a strike or a write: re-review 2026-10-07)
            LearnedStoreMigration.awaitDone()
            synchronized(lock) { readNow() }
        }

        private fun readNow() {
            val read = try {
                if (file.isDirectory) file.delete() // this apparently was an issue in some versions
                if (file.isFile) parseAll(file.readLines()) else emptyMap()
            } catch (e: IOException) {
                Log.e(TAG, "Exception while trying to read word list ${file.name}", e)
                return
            }
            val result = HashMap(read)
            for ((word, entry) in pending) if (entry == null) result.remove(word) else result[word] = entry
            for ((word, n) in strikesWhileLoading) { val entry = Entry((read[word]?.strikes ?: 0) + n); result[word] = entry; pending[word] = entry }
            strikesWhileLoading.clear()
            byWord.putAll(result)
            byWord.keys.retainAll(result.keys)
            loaded = true
        }

        // the whole list each time (a count changes in place; the lists are short), after the changes before it
        private fun write() = io.execute {
            synchronized(lock) {
                try {
                    if (file.isDirectory) file.delete()
                    file.parentFile?.mkdirs()
                    file.writeText(formatAll(byWord))
                    pending.clear()
                } catch (e: IOException) {
                    Log.e(TAG, "Exception while trying to write word list ${file.name}", e)
                }
            }
        }

        private fun set(word: String, entry: Entry?) {
            if (entry == null) byWord.remove(word) else byWord[word] = entry
            pending[word] = entry
            write()
        }

        /** One more Remove of [word]: one more strike, and a brought-back word is out again. @return its strikes now */
        fun strike(word: String): Int {
            // the strikes so far are in the file (usually read already, when the dictionaries were set up); while the
            // one-time migration still runs, no wait on the main thread (re-review 2026-10-07: up to 3 min holding the
            // lock): the strike counts from what's in memory and the read catches up in the background
            if (!loaded) { if (LearnedStoreMigration.isRunning) reloadAsync() else read() }
            return synchronized(lock) {
                if (!loaded) strikesWhileLoading[word] = (strikesWhileLoading[word] ?: 0) + 1
                strikeNow(word)
            }
        }

        private fun strikeNow(word: String): Int {
            val strikes = (byWord[word]?.strikes ?: 0) + 1
            set(word, Entry(strikes))
            return strikes
        }

        /** [word] (3rd strike, typed often enough) brought back with the strip's "+". @return false if it wasn't waiting */
        fun confirm(word: String): Boolean = synchronized(lock) {
            val entry = byWord[word] ?: return false
            if (!needsConfirmation(entry.strikes) || entry.confirmed) return false
            set(word, entry.copy(confirmed = true))
            true
        }

        /** Un-blacklist: the word and its strikes. @return false if it wasn't there */
        fun remove(word: String): Boolean {
            if (!loaded) reload()
            synchronized(lock) {
                if (!byWord.containsKey(word)) return false
                set(word, null)
            }
            return true
        }

        /** The list replaced by [entries] (the keyboards' lists merged when they share them again). Call off the main thread. */
        fun replaceAll(entries: Map<String, Entry>) {
            io.submit {
                synchronized(lock) {
                    ensureLoaded()
                    for (word in byWord.keys.toList()) if (word !in entries) set(word, null)
                    for ((word, entry) in entries) if (byWord[word] != entry) set(word, entry)
                }
            }.get()
            reload() // after the write it started
        }

        /** A backup's list ([lines]) added to this one: its words, and the most strikes of a word on both. Call off the main thread. */
        fun combine(lines: List<String>) {
            io.submit {
                synchronized(lock) {
                    ensureLoaded()
                    for ((word, entry) in parseAll(lines)) {
                        val combined = entry.max(byWord[word])
                        if (combined != byWord[word]) set(word, combined)
                    }
                }
            }.get()
            reload() // after the write it started
        }
    }

    private const val CONFIRMED = "confirmed"
    private const val TAG = "RemovedWords"
}

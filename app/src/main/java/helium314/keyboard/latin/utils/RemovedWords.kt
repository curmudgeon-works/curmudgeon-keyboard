// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.personalization.LearnedStoreMigration
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.RestoreGate
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
 *
 * Allowed here (owner 2026-10-11 "DM21 ALLOWED HERE": borrowing another keyboard's blacklist is read-only): a word that
 * is blocked only through a borrowed list and comes back on this keyboard (typed often enough by the source's strike
 * rule, or the strip's "+") gets a silent row in THIS keyboard's own file, "word<TAB>allowed<TAB>N" (N: the source's
 * strikes it overrides), instead of an edit of the source. Each word is at most once in a file (owner 2026-10-12: one
 * row holds both): a word with its own strikes too keeps them in the same row, "word<TAB>N[<TAB>confirmed]<TAB>allowed
 * <TAB>L" (N written even for 1), so allowing it here loses none of its own history and a further Remove counts on
 * from there. The allowed part is no block: [WordListFile.entries] and [parseAll] have the own strikes only (an
 * allowed-only row isn't listed on the Blacklisted words screen); [Lists] reads the allowed part. Backups, restores and
 * copies carry it with the list (the same file). Older versions read a combined row as its own strikes alone.
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

    /** The list objects held for files in [dir] (or below it). */
    fun cachedIn(dir: File): List<WordListFile> = (dir.absolutePath + File.separator).let { start -> lists.filterKeys { it.startsWith(start) }.values.toList() }

    /** Waits for the list writes already started (a full restore, before its wipe: RestoreGate). */
    fun awaitWrites() { io.submit { }.get() }

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

    /**
     * The lists that apply on a keyboard (2026-10-10): its [own] and, with its button "Blacklisted" on, every other
     * listed keyboard's ([borrowed]). A word on several: the most strikes count (Entry.max). A word on a borrowed list is
     * out from every source until it's allowed here: typed again often enough on this keyboard (its own learned count,
     * by the strike rule of the borrowed entry), or the strip's "+", writes an allowed row on the own list
     * ([allowHere]); the borrowed lists are read only (owner 2026-10-11). More strikes on a borrowed list since than the
     * allowed row records: blocked again. Strikes and confirmations of this keyboard go on its own list only. Without
     * borrowed lists everything is as with the own list alone (allowed rows are kept, unread).
     */
    class Lists(val own: WordListFile, val borrowed: List<WordListFile> = emptyList()) {
        /** Listed as written (a dictionary's copy stays out); a borrowed row allowed here doesn't count. */
        fun contains(word: String) = own.contains(word) || (borrowed.any { it.contains(word) } && activeBorrowedEntry(word) != null)

        /** The entry of [word] (as typed or lowercase) over the borrowed lists, null if none has it. */
        fun borrowedEntry(word: String): Entry? {
            var result: Entry? = null
            for (list in borrowed) list.entryFor(word)?.let { result = it.max(result) }
            return result
        }

        /**
         * The borrowed entry of [word] that still blocks here: none if this keyboard's own list allows it (an allowed row
         * at a level of strikes the borrowed lists don't exceed; with more strikes there since, the exception lapses).
         */
        fun activeBorrowedEntry(word: String): Entry? {
            if (borrowed.isEmpty()) return null
            val entry = borrowedEntry(word) ?: return null
            val level = own.allowedFor(word) ?: return entry
            return if (entry.strikes > level) entry else null
        }

        /** Blocked through a borrowed list, and allowed on this keyboard (its own allowed row covers it). */
        fun isAllowedHere(word: String): Boolean =
            borrowed.isNotEmpty() && borrowedEntry(word) != null && activeBorrowedEntry(word) == null

        /** The entry of [word] (as typed or lowercase) over all the lists (a borrowed one allowed here left out). */
        fun entryFor(word: String): Entry? {
            val borrowedEntry = activeBorrowedEntry(word)
            return own.entryFor(word)?.max(borrowedEntry) ?: borrowedEntry
        }

        /** Still out ([RemovedWords.isRemoved]); [learnedCount]: this keyboard's own count, read only for a listed word. */
        fun isRemoved(word: String, learnedCount: () -> Int): Boolean {
            if (activeBorrowedEntry(word) != null) return true
            val entry = own.entryFor(word) ?: return false
            return RemovedWords.isRemoved(entry, learnedCount())
        }

        /**
         * [word] (as typed and lowercase) is back on this keyboard although borrowed lists have it: an allowed row on the
         * own list for each spelling they have, at their strikes (the most of them). The borrowed lists stay as they are
         * (owner 2026-10-11: borrowing is read-only; until then they were edited). An own blocked row of that spelling
         * keeps its strikes, the allowed part added to it (owner 2026-10-12: one row holds both). @return whether a row
         * was written
         */
        fun allowHere(word: String): Boolean {
            var allowed = false
            for (spelling in setOf(word, word.lowercase())) {
                var level: Entry? = null
                for (list in borrowed) list.entry(spelling)?.let { level = it.max(level) }
                val strikes = level?.strikes ?: continue
                if (own.allow(spelling, strikes)) allowed = true
            }
            return allowed
        }

        /**
         * Remove (strip) of [word] on this keyboard: one more strike on its own list. A word allowed here gets its own
         * strike row instead (its allowed part goes): one more than its own strikes or the borrowed ones, the more of
         * them (owner 2026-10-12: a borrowed 2 and no own row: 3; an own 3: 4). @return its strikes now
         */
        fun strike(word: String): Int {
            val floor = if (borrowed.isNotEmpty() && own.allowedFor(word) != null) borrowedEntry(word) else null
            return own.strike(word, floor)
        }

        /** Every list read (call off the main thread). */
        fun ensureLoaded() { own.ensureLoaded(); borrowed.forEach { it.ensureLoaded() } }
    }

    // ------------------------------- the file -------------------------------

    /** "word", or "word<TAB>N" (with a "<TAB>confirmed" after), as [format] writes, and the own strikes of a combined
     *  row ([formatRow]); null for an empty line and for an allowed-only row ([parseAllowed]: no block). */
    internal fun parse(line: String): Pair<String, Entry>? {
        val parts = line.split('\t')
        val word = parts[0]
        if (word.isEmpty()) return null
        if (parts.getOrNull(1)?.trim() == ALLOWED) return null
        val strikes = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val at = allowedAt(parts)
        val own = if (at < 0) parts.drop(2) else parts.subList(2, at) // (at >= 2: "word<TAB>allowed" returned above)
        return word to Entry(strikes, own.any { it.trim() == CONFIRMED })
    }

    // the index of the "allowed" part of a line's [parts] (after the word), -1 without one
    private fun allowedAt(parts: List<String>): Int {
        for (i in 1 until parts.size) if (parts[i].trim() == ALLOWED) return i
        return -1
    }

    /** A plain word for a single strike: the lines everything before strikes wrote and read. */
    internal fun format(word: String, entry: Entry): String = when {
        entry.confirmed -> "$word\t${entry.strikes}\t$CONFIRMED"
        entry.strikes > 1 -> "$word\t${entry.strikes}"
        else -> word
    }

    /** The allowed part of a row, "word<TAB>allowed<TAB>N" ([formatAllowed]) or after the own strikes of a combined
     *  row ([formatRow]): the word and N, the borrowed strikes it overrides; null for a line without one. */
    internal fun parseAllowed(line: String): Pair<String, Int>? {
        val parts = line.split('\t')
        val word = parts[0]
        val at = allowedAt(parts)
        if (word.isEmpty() || at < 0) return null
        return word to (parts.getOrNull(at + 1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
    }

    internal fun formatAllowed(word: String, level: Int): String = "$word\t$ALLOWED\t$level"

    /** [word]'s one row: its own [entry] (as [format]) and its [allowed] part, either or both (owner 2026-10-12: one
     *  row holds both); with both, the strikes are written even for 1, so the row reads as before up to "allowed". */
    internal fun formatRow(word: String, entry: Entry?, allowed: Int?): String = when {
        allowed == null -> format(word, entry ?: Entry())
        entry == null -> formatAllowed(word, allowed)
        else -> "$word\t${entry.strikes}${if (entry.confirmed) "\t$CONFIRMED" else ""}\t$ALLOWED\t$allowed"
    }

    /** The allowed parts of [lines] (a word allowed twice: the higher level; its own strikes don't change it, owner
     *  2026-10-12: one row holds both). */
    internal fun parseAllAllowed(lines: List<String>): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (line in lines) {
            val (word, level) = parseAllowed(line) ?: continue
            result[word] = maxOf(level, result[word] ?: 0)
        }
        return result
    }

    /** [lines] of a list file read into entries (a word listed twice: the most strikes); an allowed part is no entry. */
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

    /** The lines [WordListFile] writes for [entries] and the [allowed] parts (a word in both: one row, [formatRow]). */
    fun formatAll(entries: Map<String, Entry>, allowed: Map<String, Int> = emptyMap()): String =
        (entries.keys + allowed.keys).sorted().joinToString("") { formatRow(it, entries[it], allowed[it]) + "\n" }

    /** A change made on a list while a full restore ran (RestoreGate), as it was made: one more strike, not the count it
     *  made then (review 2026-10-12 L2). At the restore's end it is made again on the restored list of the same keyboard
     *  ([WordListFile.takeGated], [WordListFile.redo]). */
    sealed interface GatedOp { val word: String }
    data class GatedStrike(override val word: String) : GatedOp
    data class GatedConfirm(override val word: String) : GatedOp
    data class GatedTakeOff(override val word: String) : GatedOp
    data class GatedAllow(override val word: String, val level: Int) : GatedOp

    // a change in memory, not written yet: the word's row now, its own entry and its allowed part (either may be null)
    private class Change(val entry: Entry?, val allowed: Int?)

    class WordListFile internal constructor(val file: File) {
        private val byWord = ConcurrentHashMap<String, Entry>()
        // the allowed parts: word -> the borrowed strikes it overrides (a word in [byWord] too: one row holding both)
        private val allowedByWord = ConcurrentHashMap<String, Int>()
        // changed in memory, not written yet (null: taken off): a re-read meanwhile must not undo them
        private val pending = HashMap<String, Change?>()
        // strikes made before the file was read (a Remove during the one-time migration): added to the file's count
        // when the read catches up, not written over it (re-review 2026-10-07)
        private val strikesWhileLoading = HashMap<String, Int>()
        @Volatile private var loaded = false
        private val lock = Any()
        // the changes made while a full restore ran, as made ([GatedOp])
        private val gatedOps = ArrayList<GatedOp>()

        private fun recordGated(op: GatedOp) { if (RestoreGate.isSet) gatedOps.add(op) }

        /**
         * The changes made on this list while a full restore ran ([GatedOp]), taken off it: not put on top of its file
         * again (the file of this pool number can be another keyboard's after the restore, and their counts are the old
         * list's); the caller makes them again, by keyboard, with [redo] (review 2026-10-12 L2).
         */
        fun takeGated(): List<GatedOp> = synchronized(lock) {
            val ops = gatedOps.toList()
            gatedOps.clear()
            for (op in ops) { pending.remove(op.word); strikesWhileLoading.remove(op.word) }
            ops
        }

        /** [ops] made on this list (read first), as made: a strike is one more on the count it has here. Call off the
         *  main thread. */
        fun redo(ops: List<GatedOp>) {
            ensureLoaded()
            for (op in ops) when (op) {
                is GatedStrike -> strike(op.word)
                is GatedConfirm -> confirm(op.word)
                is GatedTakeOff -> takeOff(op.word)
                is GatedAllow -> allow(op.word, op.level)
            }
        }

        fun contains(word: String) = byWord.containsKey(word)

        /** [word]'s entry, null if it isn't listed. */
        fun entry(word: String): Entry? = byWord[word]

        /** The entry for [word] as typed or in lowercase (Remove takes a word out in every capitalization). */
        fun entryFor(word: String): Entry? {
            val lower = word.lowercase()
            return byWord[word]?.max(if (lower != word) byWord[lower] else null) ?: byWord[lower]
        }

        /** The level of [word]'s allowed row (as typed or in lowercase, the higher), null if it has none. */
        fun allowedFor(word: String): Int? {
            val lower = word.lowercase()
            val asTyped = allowedByWord[word]
            val ofLower = if (lower != word) allowedByWord[lower] else null
            return if (asTyped == null) ofLower else if (ofLower == null) asTyped else maxOf(asTyped, ofLower)
        }

        /** The allowed rows, word -> level (read from the file first if that hasn't happened yet: call off the main thread). */
        fun allowedEntries(): Map<String, Int> {
            ensureLoaded()
            return allowedByWord.toMap()
        }

        /** Reads the file first if that hasn't happened yet (call off the main thread, or where a short read is fine). */
        fun ensureLoaded() { if (!loaded) read() }

        /** All words (read from the file first if that hasn't happened yet: call off the main thread). */
        fun words(): List<String> = entries().keys.toList()

        /** All words with their entries, the blocked ones only: no allowed row (read from the file first if that hasn't
         *  happened yet: call off the main thread). */
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
            val lines = try {
                if (file.isDirectory) file.delete() // this apparently was an issue in some versions
                if (file.isFile) file.readLines() else emptyList()
            } catch (e: IOException) {
                Log.e(TAG, "Exception while trying to read word list ${file.name}", e)
                return
            }
            val read = parseAll(lines)
            val result = HashMap(read)
            val allowedResult = HashMap(parseAllAllowed(lines))
            for ((word, change) in pending) {
                val entry = change?.entry
                val allowed = change?.allowed
                if (entry == null) result.remove(word) else result[word] = entry
                if (allowed == null) allowedResult.remove(word) else allowedResult[word] = allowed
            }
            for ((word, n) in strikesWhileLoading) {
                val entry = Entry((read[word]?.strikes ?: 0) + n)
                result[word] = entry
                allowedResult.remove(word)
                pending[word] = Change(entry, null)
            }
            strikesWhileLoading.clear()
            byWord.putAll(result)
            byWord.keys.retainAll(result.keys)
            allowedByWord.putAll(allowedResult)
            allowedByWord.keys.retainAll(allowedResult.keys)
            loaded = true
        }

        // the whole list each time (a count changes in place; the lists are short), after the changes before it
        private fun write() = io.execute {
            synchronized(lock) {
                // while a full restore replaces the files (owner 2026-10-12: "A + B + swipe stats makes sense"), the
                // whole list in memory is still the old device's: the changes stay pending, put on top of the restored
                // file and written once it's done ([writePending])
                if (RestoreGate.isSet) return@synchronized
                writeLocked()
            }
        }

        private fun writeLocked() {
            try {
                if (file.isDirectory) file.delete()
                file.parentFile?.mkdirs()
                AtomicFiles.write(file, formatAll(byWord, allowedByWord)) // (crash-safe: never half a list, B4w.2)
                pending.clear()
            } catch (e: IOException) {
                Log.e(TAG, "Exception while trying to write word list ${file.name}", e)
            }
        }

        /** The changes kept in memory while a full restore ran (RestoreGate), written now, after what's being written;
         *  nothing if there are none (call off the main thread, after [reload] put them on top of the restored file). */
        fun writePending() {
            io.submit { synchronized(lock) { if (pending.isNotEmpty()) writeLocked() } }.get()
        }

        // [word]'s row: its own [entry] and its [allowed] part (null: none of it; both null: no row)
        private fun set(word: String, entry: Entry?, allowed: Int?) {
            if (entry == null) byWord.remove(word) else byWord[word] = entry
            if (allowed == null) allowedByWord.remove(word) else allowedByWord[word] = allowed
            pending[word] = if (entry == null && allowed == null) null else Change(entry, allowed)
            write()
        }

        /** [word] allowed on this keyboard over borrowed strikes up to [level] (its own strikes, if any, kept in the same
         *  row: owner 2026-10-12 "one row holds both"). The file is read first, as for a strike (review L4.1: an unread
         *  list's write was the new row only, the file's other rows lost); while the one-time migration runs, kept
         *  pending and the read catches up first. @return false if it was allowed so already */
        fun allow(word: String, level: Int): Boolean {
            if (!loaded) { if (LearnedStoreMigration.isRunning) reloadAsync() else read() }
            synchronized(lock) {
                if (allowedByWord[word] == level) return false
                set(word, byWord[word], level)
                recordGated(GatedAllow(word, level))
                return true
            }
        }

        /**
         * One more Remove of [word]: one more strike, and a brought-back word is out again (its allowed part goes).
         * [floor]: the borrowed entry of a word allowed here: one more than the more of its own strikes and those
         * (owner 2026-10-12: a borrowed 2 and no own row: 3, not 2). @return its strikes now
         */
        fun strike(word: String, floor: Entry? = null): Int {
            // the strikes so far are in the file (usually read already, when the dictionaries were set up); while the
            // one-time migration still runs, no wait on the main thread (re-review 2026-10-07: up to 3 min holding the
            // lock): the strike counts from what's in memory and the read catches up in the background
            if (!loaded) { if (LearnedStoreMigration.isRunning) reloadAsync() else read() }
            return synchronized(lock) {
                if (!loaded) strikesWhileLoading[word] = (strikesWhileLoading[word] ?: 0) + 1
                strikeNow(word, floor)
            }
        }

        private fun strikeNow(word: String, floor: Entry?): Int {
            // (a fresh Remove: never confirmed, whatever the borrowed entry had)
            val strikes = maxOf(byWord[word]?.strikes ?: 0, floor?.strikes ?: 0) + 1
            // (removed in every capitalization: an allowed part of the lowercase spelling goes too, its own strikes stay,
            // review L4.2; the as-typed one goes with its new row)
            val lower = word.lowercase()
            if (lower != word && allowedByWord.containsKey(lower)) set(lower, byWord[lower], null)
            set(word, Entry(strikes), null)
            recordGated(GatedStrike(word))
            return strikes
        }

        /** A Remove of [word]: the allowed rows of it as typed and in lowercase taken off (review L4.2: the strike's row
         *  can be another spelling's, as the dictionaries list the word; read first, as for a strike). */
        fun disallow(word: String) {
            if (!loaded) { if (LearnedStoreMigration.isRunning) reloadAsync() else read() }
            synchronized(lock) {
                // (only the allowed part: own strikes of the spelling stay in its row, owner 2026-10-12 one row holds both)
                for (spelling in setOf(word, word.lowercase()))
                    if (allowedByWord.containsKey(spelling)) set(spelling, byWord[spelling], null)
            }
        }

        /** [word] (3rd strike, typed often enough) brought back with the strip's "+". @return false if it wasn't waiting */
        fun confirm(word: String): Boolean = synchronized(lock) {
            val entry = byWord[word] ?: return false
            if (!needsConfirmation(entry.strikes) || entry.confirmed) return false
            set(word, entry.copy(confirmed = true), allowedByWord[word])
            recordGated(GatedConfirm(word))
            true
        }

        /** Un-blacklist: the word and its strikes (an allowed part of its row stays: the borrowed block stays lifted, as
         *  when the row was allowed-only). @return false if it wasn't there */
        fun remove(word: String): Boolean {
            if (!loaded) reload()
            synchronized(lock) {
                if (!byWord.containsKey(word)) return false
                set(word, null, allowedByWord[word])
                recordGated(GatedTakeOff(word))
            }
            return true
        }

        /** [word] taken off the list as it is in memory (no wait for a read: an unread list has nothing to take off; an
         *  allowed part stays, as with [remove]). @return false if it wasn't there */
        fun takeOff(word: String): Boolean = synchronized(lock) {
            if (!byWord.containsKey(word)) return false
            set(word, null, allowedByWord[word])
            recordGated(GatedTakeOff(word))
            true
        }

        /** The list replaced by [entries] and [allowed] parts (a copy of another list; emptied with both empty). Call off
         *  the main thread. */
        fun replaceAll(entries: Map<String, Entry>, allowed: Map<String, Int> = emptyMap()) {
            io.submit {
                synchronized(lock) {
                    ensureLoaded()
                    for (word in byWord.keys + allowedByWord.keys + entries.keys + allowed.keys)
                        if (byWord[word] != entries[word] || allowedByWord[word] != allowed[word])
                            set(word, entries[word], allowed[word])
                }
            }.get()
            reload() // after the write it started
        }

        /** A backup's list ([lines]) added to this one: its words, and the most strikes of a word on both (Entry.max);
         *  its allowed parts too (the higher level), each part of a row on its own (owner 2026-10-12: one row holds both;
         *  a word's own strikes still block by their own rule). Call off the main thread. */
        fun combine(lines: List<String>) {
            io.submit {
                synchronized(lock) {
                    ensureLoaded()
                    val entries = parseAll(lines)
                    val allowed = parseAllAllowed(lines)
                    for (word in entries.keys + allowed.keys) {
                        val entry = entries[word]?.max(byWord[word]) ?: byWord[word]
                        val level = allowed[word]?.let { maxOf(it, allowedByWord[word] ?: 0) } ?: allowedByWord[word]
                        if (entry != byWord[word] || level != allowedByWord[word]) set(word, entry, level)
                    }
                }
            }.get()
            reload() // after the write it started
        }
    }

    private const val CONFIRMED = "confirmed"
    private const val ALLOWED = "allowed"
    private const val TAG = "RemovedWords"
}

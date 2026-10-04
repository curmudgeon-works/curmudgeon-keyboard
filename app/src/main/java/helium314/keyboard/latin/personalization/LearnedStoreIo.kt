// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.makedict.FormatSpec
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.Locale

/**
 * Reading and writing whole stores of learned words, for what puts stores together (LearnedStoreMigration,
 * LearnedPools, the backup restore). An interface so that logic can be tested without the native library.
 */
interface LearnedStoreIo {
    /** The entries of the store in [dir] (a file nobody has open), null if there's no readable store. */
    fun readFile(dir: File, locale: Locale): List<LearnedEntry>?

    /** Writes a new store holding [entries] to [dir] (its name is the store's: the files in it are named after it). */
    fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>): Boolean

    /** The store of [script] in [pool] as the keyboard has it open, or null if nothing holds it. */
    fun live(script: String, pool: Int): LiveStore?

    interface LiveStore {
        /** Everything in it, what's only in memory too; null if it couldn't be read. */
        fun readAll(): List<LearnedEntry>?
        /** Its contents replaced by the store in [dir] (moved there), for everyone holding it. */
        fun replaceWith(dir: File): Boolean
    }

    /** The real stores, through the native library. */
    object Native : LearnedStoreIo {
        private const val TAG = "LearnedStoreIo"

        override fun readFile(dir: File, locale: Locale): List<LearnedEntry>? {
            if (!dir.exists()) return null
            val dict = BinaryDictionary(dir.absolutePath, 0, dir.length(), true, locale, Dictionary.TYPE_USER_HISTORY, false)
            try {
                if (!dict.isValidDictionary) return null
                val words = ArrayList<helium314.keyboard.latin.makedict.WordProperty>()
                var token = 0
                do {
                    val result = dict.getNextWordProperty(token)
                    words.add(result.mWordProperty ?: break)
                    token = result.mNextToken
                } while (token != 0)
                return entriesOf(words)
            } finally {
                dict.close()
            }
        }

        override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>): Boolean {
            dir.parentFile?.mkdirs()
            val name = dir.name.removeSuffix(LearnedStores.DICT_EXTENSION)
            val dict = BinaryDictionary(dir.absolutePath, true, locale, Dictionary.TYPE_USER_HISTORY,
                FormatSpec.VERSION4.toLong(), UserHistoryDictionary.headerAttributes(name, locale))
            try {
                if (!dict.isValidDictionary) return false
                replay(entries) { context, word, count, time ->
                    // (as ExpandableBinaryDictionary does before each update: a big store is compacted on the way)
                    if (dict.needsToRunGC(true)) dict.flushWithGC()
                    dict.updateEntriesForWordWithNgramContext(ngramContextOf(context), word, true, count, time)
                }
                // written as it is, not compacted (a compaction can also age old entries: the stores put together keep
                // what they had); an empty store has nothing to write but its header
                return if (entries.isEmpty()) dict.flushWithGC() else dict.flush()
            } catch (e: Exception) {
                Log.e(TAG, "could not write a store of learned words", e)
                return false
            } finally {
                dict.close()
            }
        }

        override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? {
            val store = PersonalizationHelper.getCachedUserHistoryDictionary(script, pool) ?: return null
            return object : LearnedStoreIo.LiveStore {
                override fun readAll() = store.allWordPropertiesBlocking?.let { entriesOf(it.asList()) }
                override fun replaceWith(dir: File) = store.replaceWith(dir)
            }
        }
    }

    /**
     * The files only, for the migration at app start: a store the keyboard opened meanwhile hasn't read its file yet (it
     * waits for the migration, holding its lock), so the files are what to read and replace; asking it would wait for
     * that lock, which it only lets go once the migration is done.
     */
    object FilesOnly : LearnedStoreIo by Native {
        override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? = null
    }
}

/** Store-level reading and writing over a [LearnedStoreIo], live stores first. */
object LearnedStoreFiles {
    /** Where new stores are put together before they replace the old ones. */
    const val WORK_DIR = "learned_merging"

    /** Everything in the store of [script] in [pool]: from the open store if there is one, else its file; empty if neither. */
    fun read(io: LearnedStoreIo, filesDir: File, script: String, pool: Int): List<LearnedEntry>? {
        io.live(script, pool)?.let { return it.readAll() }
        val file = LearnedStores.storeFile(filesDir, script, pool)
        if (!file.exists()) return emptyList()
        return io.readFile(file, LearnedStores.storeLocale(script))
    }

    /** The store of [script] in [pool] replaced by one holding [entries] (the open store too, if there is one). */
    fun write(io: LearnedStoreIo, filesDir: File, script: String, pool: Int, entries: List<LearnedEntry>): Boolean {
        val target = LearnedStores.storeFile(filesDir, script, pool)
        val work = File(File(filesDir, WORK_DIR), target.name)
        work.deleteRecursively()
        if (!io.writeFile(work, LearnedStores.storeLocale(script), entries)) {
            work.deleteRecursively()
            return false
        }
        return install(io, filesDir, script, pool, work)
    }

    /** The store written aside in [work] takes the place of that of [script] in [pool]. */
    fun install(io: LearnedStoreIo, filesDir: File, script: String, pool: Int, work: File): Boolean {
        io.live(script, pool)?.let { return it.replaceWith(work) }
        val target = LearnedStores.storeFile(filesDir, script, pool)
        if (target.exists() && !target.deleteRecursively()) return false
        return work.renameTo(target)
    }
}

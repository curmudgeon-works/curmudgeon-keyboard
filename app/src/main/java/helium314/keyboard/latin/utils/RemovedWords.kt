// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The per-language blacklists (long-press Remove: dictionary, contacts and apps words that aren't suggested until typed
 * again), kept as files. Generic over the file, so a further list of the kind is one more function here.
 *
 * One object per file for the whole process: the keyboard's dictionaries (DictionaryGroup), the spell checker and the
 * settings screen "Learned & blacklisted words" all hold the same one, so an edit made in the settings is what the
 * running keyboard checks against right away (settings and keyboard run in the same process).
 */
object RemovedWords {
    private val lists = ConcurrentHashMap<String, WordListFile>()
    // file writes and re-reads, in order
    private val io = Executors.newSingleThreadExecutor { Thread(it, "RemovedWords") }

    private fun dir(context: Context) = File(context.filesDir, "blacklists")

    /** Long-press Remove: filesDir/blacklists/<languageTag>.txt */
    fun blacklist(context: Context, locale: Locale): WordListFile = forFile(File(dir(context), locale.toLanguageTag() + ".txt"))

    private fun forFile(file: File): WordListFile = lists.getOrPut(file.absolutePath) { WordListFile(file) }

    class WordListFile internal constructor(private val file: File) {
        private val words: MutableSet<String> = ConcurrentHashMap.newKeySet()
        // added in memory, not written yet: a re-read meanwhile must not drop them
        private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile private var loaded = false
        private val lock = Any()

        fun contains(word: String) = words.contains(word)

        /** All words (read from the file first if that hasn't happened yet: call off the main thread). */
        fun words(): List<String> {
            if (!loaded) read()
            return words.toList()
        }

        /** Reads the file again now, after what's being written (call off the main thread). */
        fun reload() { io.submit { read() }.get() }

        /** Re-reads the file in the background (the keyboard does it when its dictionaries are set up, e.g. after a restore). */
        fun reloadAsync() = io.execute { read() }

        private fun read() = synchronized(lock) {
            val lines = try {
                if (file.isDirectory) file.delete() // this apparently was an issue in some versions
                if (file.isFile) file.readLines().filter { it.isNotEmpty() }.toSet() else emptySet()
            } catch (e: IOException) {
                Log.e(TAG, "Exception while trying to read word list ${file.name}", e)
                return@synchronized
            }
            words.addAll(lines)
            words.retainAll(lines + pending)
            loaded = true
        }

        /** @return false if it was there already */
        fun add(word: String): Boolean {
            if (!words.add(word)) return false
            pending.add(word)
            io.execute {
                synchronized(lock) {
                    try {
                        if (file.isDirectory) file.delete()
                        file.parentFile?.mkdirs()
                        file.appendText("$word\n")
                    } catch (e: IOException) {
                        Log.e(TAG, "Exception while trying to add a word to ${file.name}", e)
                    }
                    pending.remove(word)
                }
            }
            return true
        }

        /** @return false if it wasn't there */
        fun remove(word: String): Boolean {
            if (!loaded) reload()
            if (!words.remove(word)) return false
            io.execute {
                synchronized(lock) {
                    try {
                        if (!file.isFile) return@synchronized
                        file.writeText(file.readLines().filter { it.isNotEmpty() && it != word }.joinToString("") { "$it\n" })
                    } catch (e: IOException) {
                        Log.e(TAG, "Exception while trying to remove a word from ${file.name}", e)
                    }
                }
            }
            return true
        }
    }

    private const val TAG = "RemovedWords"
}

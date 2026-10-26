// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translit

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A Latin to other script table (Suggest in हिंदी): for a Latin word, its words in the other script, best first by the
 * table's score alone (the order the asset has them in; nothing else ranks them).
 *
 * The asset (written by the dictionaries' translit/scripts/export_app.py): a header ("HTL1", crc32 of the rest, key
 * count, block size, block count), an index of where each block of keys starts, then the keys in byte order, front-coded
 * within their block (a block's first key whole, for the binary search), each with its values, one byte per character
 * (0x80 + code point - [SCRIPT_BASE]). Read in place from [buffer] (mapped from a file when it can be), absolute reads
 * only, so lookups from several threads are fine.
 *
 * The same layout holds the table's reverse index ([reverse]: "HTR1", built on the device from the table by
 * [buildReverse]): the keys are the other script's words (one byte per character, as the values above), each with the
 * Latin words the table lists it for, and in front of each the word's rank in that Latin word's list ([spellings]).
 */
class TranslitTable(private val buffer: ByteBuffer, private val reverse: Boolean = false) {
    private val keyCount = buffer.getInt(8)
    private val blockSize = buffer.getInt(12)
    private val blockCount = buffer.getInt(16)
    private val dataStart = HEADER_SIZE + 4 * blockCount

    /** The table's checksum (its header); a reverse index carries the checksum of the table it was built from. */
    val crc: Int get() = buffer.getInt(4)

    init {
        require(buffer.getInt(0) == (if (reverse) MAGIC_REVERSE else MAGIC)) { "not a translit ${if (reverse) "reverse index" else "table"}" }
    }

    /** A Latin word the table lists a word of the other script for, and the word's [rank] in its list (0 = first). */
    class Spelling(@JvmField val latin: String, @JvmField val rank: Int)

    /** [latin]'s words in the other script, best first (at most 3); empty when the table has none or [latin] is not
     *  a word of a to z only (any capitals are looked up in lowercase: "Bohot" is "bohot"). Empty on a reverse index. */
    fun lookup(latin: String): List<String> {
        if (reverse) return emptyList()
        val key = latinKey(latin) ?: return emptyList()
        var pos = find(key)
        if (pos < 0) return emptyList()
        val count = u8(pos++)
        return List(count) {
            val length = u8(pos)
            val word = CharArray(length) { i -> (SCRIPT_BASE + u8(pos + 1 + i) - 0x80).toChar() }
            pos += 1 + length
            String(word)
        }
    }

    /** Reverse index: the Latin words the table lists [word] (of the other script) for, each with the word's rank in
     *  its list, in rank order; empty when none, or [word] has a letter outside the script. Empty on a table. */
    fun spellings(word: String): List<Spelling> {
        if (!reverse) return emptyList()
        val key = scriptKey(word) ?: return emptyList()
        var pos = find(key)
        if (pos < 0) return emptyList()
        val count = u8(pos++)
        return List(count) {
            val length = u8(pos)
            val rank = u8(pos + 1)
            val latin = String(CharArray(length) { i -> u8(pos + 2 + i).toChar() })
            pos += 2 + length
            Spelling(latin, rank)
        }
    }

    /** Every key of the table with its words, in the table's order: the whole file read, for [buildReverse]. */
    fun forEachEntry(action: (String, List<String>) -> Unit) {
        require(!reverse)
        val current = ByteArray(MAX_LENGTH)
        for (block in 0 until blockCount) {
            var pos = dataStart + buffer.getInt(HEADER_SIZE + 4 * block)
            repeat(minOf(blockSize, keyCount - block * blockSize)) {
                val prefix = u8(pos)
                val suffix = u8(pos + 1)
                for (i in 0 until suffix) current[prefix + i] = buffer.get(pos + 2 + i)
                pos += 2 + suffix
                val key = String(CharArray(prefix + suffix) { i -> (current[i].toInt() and 0xFF).toChar() })
                val count = u8(pos++)
                val words = List(count) {
                    val length = u8(pos)
                    val word = CharArray(length) { i -> (SCRIPT_BASE + u8(pos + 1 + i) - 0x80).toChar() }
                    pos += 1 + length
                    String(word)
                }
                action(key, words)
            }
        }
    }

    private fun latinKey(latin: String): ByteArray? {
        if (latin.isEmpty() || latin.length > MAX_LENGTH) return null
        val key = ByteArray(latin.length)
        for (i in latin.indices) {
            val c = latin[i].lowercaseChar()
            if (c !in 'a'..'z') return null
            key[i] = c.code.toByte()
        }
        return key
    }

    private fun scriptKey(word: String): ByteArray? {
        if (word.isEmpty() || word.length > MAX_LENGTH) return null
        val key = ByteArray(word.length)
        for (i in word.indices) {
            val offset = word[i].code - SCRIPT_BASE
            if (offset !in 0..0x7F) return null
            key[i] = (0x80 + offset).toByte()
        }
        return key
    }

    /** Where [key]'s values start (its count byte), or -1 when the key isn't there. */
    private fun find(key: ByteArray): Int {
        // the last block whose first key is not after the key
        var low = 0
        var high = blockCount - 1
        var block = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (compareFirstKey(mid, key) <= 0) {
                block = mid
                low = mid + 1
            } else high = mid - 1
        }
        if (block < 0) return -1
        var pos = dataStart + buffer.getInt(HEADER_SIZE + 4 * block)
        val current = ByteArray(MAX_LENGTH)
        val keysInBlock = minOf(blockSize, keyCount - block * blockSize)
        repeat(keysInBlock) {
            val prefix = u8(pos)
            val suffix = u8(pos + 1)
            for (i in 0 until suffix) current[prefix + i] = buffer.get(pos + 2 + i)
            pos += 2 + suffix
            val compared = compare(current, prefix + suffix, key)
            if (compared == 0) return pos
            if (compared > 0) return -1
            val count = u8(pos++)
            repeat(count) { pos += (if (reverse) 2 else 1) + u8(pos) }
        }
        return -1
    }

    private fun u8(at: Int) = buffer.get(at).toInt() and 0xFF

    private fun compareFirstKey(block: Int, key: ByteArray): Int {
        val pos = dataStart + buffer.getInt(HEADER_SIZE + 4 * block)
        val length = u8(pos + 1)
        for (i in 0 until minOf(length, key.size)) {
            val d = u8(pos + 2 + i) - (key[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return length - key.size
    }

    private fun compare(a: ByteArray, length: Int, key: ByteArray): Int {
        for (i in 0 until minOf(length, key.size)) {
            val d = (a[i].toInt() and 0xFF) - (key[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return length - key.size
    }

    companion object {
        private const val TAG = "TranslitTable"
        private const val MAGIC = 0x48544C31 // "HTL1"
        private const val MAGIC_REVERSE = 0x48545231 // "HTR1"
        private const val HEADER_SIZE = 20
        private const val MAX_LENGTH = 255
        private const val BLOCK_SIZE = 16
        private const val REVERSE_SUFFIX = ".rev"
        /** The first code point of the script the values are in (Devanagari; one byte each: 0x80 + offset). */
        private const val SCRIPT_BASE = 0x0900

        /**
         * Reads [asset] (a path under assets/). The asset is compressed in the APK, so it is copied once to the app's
         * files (no backup: it comes with the app) and mapped from there, nothing of it on the heap; the copy is made
         * again when the asset's header differs (a new table). Where that can't be done (no files before the device is
         * unlocked) it is read into memory. Slow: never on the main thread.
         */
        fun load(context: Context, asset: String): TranslitTable? = try {
            val header = context.assets.open(asset).use { it.readHeaderBytes(HEADER_SIZE) }
            val buffer = mapped(context, asset, header) ?: ByteBuffer.wrap(context.assets.open(asset).use { it.readBytes() })
            TranslitTable(buffer)
        } catch (e: Exception) {
            Log.e(TAG, "can't read $asset", e)
            null
        }

        /**
         * The reverse index of [asset]'s table [forward]: the file beside the table's copy, mapped. Built from the
         * table the first time (a second or two), and again when the table changed (its checksum is in the index's
         * header); kept in memory when no file can be written. Slow: never on the main thread.
         */
        fun loadReverse(context: Context, asset: String, forward: TranslitTable): TranslitTable? = try {
            val dir = File(context.noBackupFilesDir, "translit")
            val file = File(dir, asset.substringAfterLast('/') + REVERSE_SUFFIX)
            val header = if (file.isFile) file.inputStream().use { it.readHeaderBytes(8) } else ByteArray(0)
            val current = header.size == 8 && ByteBuffer.wrap(header).let { it.getInt(0) == MAGIC_REVERSE && it.getInt(4) == forward.crc }
            if (!current) {
                val bytes = buildReverse(forward)
                try {
                    dir.mkdirs()
                    val temp = File(dir, file.name + ".tmp")
                    temp.writeBytes(bytes)
                    if (!temp.renameTo(file)) throw IOException("can't rename $temp")
                } catch (e: Exception) {
                    Log.w(TAG, "can't write $file, keeping the reverse index in memory", e)
                    return TranslitTable(ByteBuffer.wrap(bytes), reverse = true)
                }
            }
            TranslitTable(RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }, reverse = true)
        } catch (e: Exception) {
            Log.e(TAG, "can't build the reverse index of $asset", e)
            null
        }

        /** The reverse index of [forward] as a file's bytes (the layout in the class comment): every word of the other
         *  script in byte order, with the Latin words whose lists have it, best rank first. */
        fun buildReverse(forward: TranslitTable): ByteArray {
            val links = HashMap<String, MutableList<Spelling>>()
            forward.forEachEntry { latin, words ->
                words.forEachIndexed { rank, word -> links.getOrPut(word) { ArrayList(2) }.add(Spelling(latin, rank)) }
            }
            // (a word's characters are single bytes in order, so the strings' order is the bytes' order)
            val words = links.keys.filter { w -> w.length in 1..MAX_LENGTH && w.all { it.code - SCRIPT_BASE in 0..0x7F } }.sorted()
            val body = ByteArrayOutputStream()
            val starts = ArrayList<Int>()
            var previous = ByteArray(0)
            words.forEachIndexed { i, word ->
                val key = ByteArray(word.length) { c -> (0x80 + word[c].code - SCRIPT_BASE).toByte() }
                if (i % BLOCK_SIZE == 0) {
                    starts.add(body.size())
                    previous = ByteArray(0)
                }
                var prefix = 0
                while (prefix < previous.size && prefix < key.size && previous[prefix] == key[prefix]) prefix++
                body.write(prefix)
                body.write(key.size - prefix)
                body.write(key, prefix, key.size - prefix)
                val spellings = links.getValue(word).sortedWith(compareBy({ it.rank }, { it.latin })).take(255)
                body.write(spellings.size)
                for (s in spellings) {
                    body.write(s.latin.length)
                    body.write(s.rank)
                    body.write(s.latin.toByteArray(Charsets.US_ASCII))
                }
                previous = key
            }
            val header = ByteBuffer.allocate(HEADER_SIZE + 4 * starts.size)
            header.putInt(MAGIC_REVERSE).putInt(forward.crc).putInt(words.size).putInt(BLOCK_SIZE).putInt(starts.size)
            for (start in starts) header.putInt(start)
            val out = ByteArrayOutputStream(header.capacity() + body.size())
            out.write(header.array())
            body.writeTo(out)
            return out.toByteArray()
        }

        private fun mapped(context: Context, asset: String, header: ByteArray): ByteBuffer? = try {
            val dir = File(context.noBackupFilesDir, "translit")
            val file = File(dir, asset.substringAfterLast('/'))
            val current = file.isFile && file.inputStream().use { it.readHeaderBytes(HEADER_SIZE) }.contentEquals(header)
            if (!current) {
                dir.mkdirs()
                val temp = File(dir, file.name + ".tmp")
                context.assets.open(asset).use { input -> temp.outputStream().use { input.copyTo(it) } }
                if (!temp.renameTo(file)) throw java.io.IOException("can't rename $temp")
            }
            RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        } catch (e: Exception) {
            Log.w(TAG, "can't map $asset, reading it into memory", e)
            null
        }

        private fun java.io.InputStream.readHeaderBytes(n: Int): ByteArray {
            val bytes = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = read(bytes, read, n - read)
                if (r < 0) break
                read += r
            }
            return if (read == n) bytes else bytes.copyOf(read)
        }
    }
}

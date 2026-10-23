// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translit

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.File
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
 */
class TranslitTable(private val buffer: ByteBuffer) {
    private val keyCount = buffer.getInt(8)
    private val blockSize = buffer.getInt(12)
    private val blockCount = buffer.getInt(16)
    private val dataStart = HEADER_SIZE + 4 * blockCount

    init {
        require(buffer.getInt(0) == MAGIC) { "not a translit table" }
    }

    /** [latin]'s words in the other script, best first (at most 3); empty when the table has none or [latin] is not
     *  a word of a to z only (any capitals are looked up in lowercase: "Bohot" is "bohot"). */
    fun lookup(latin: String): List<String> {
        if (latin.isEmpty() || latin.length > MAX_LENGTH) return emptyList()
        val key = ByteArray(latin.length)
        for (i in latin.indices) {
            val c = latin[i].lowercaseChar()
            if (c !in 'a'..'z') return emptyList()
            key[i] = c.code.toByte()
        }
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
        if (block < 0) return emptyList()
        var pos = dataStart + buffer.getInt(HEADER_SIZE + 4 * block)
        val current = ByteArray(MAX_LENGTH)
        val keysInBlock = minOf(blockSize, keyCount - block * blockSize)
        repeat(keysInBlock) {
            val prefix = u8(pos)
            val suffix = u8(pos + 1)
            for (i in 0 until suffix) current[prefix + i] = buffer.get(pos + 2 + i)
            pos += 2 + suffix
            val count = u8(pos++)
            val compared = compare(current, prefix + suffix, key)
            if (compared == 0) return List(count) {
                val length = u8(pos)
                val word = CharArray(length) { i -> (SCRIPT_BASE + u8(pos + 1 + i) - 0x80).toChar() }
                pos += 1 + length
                String(word)
            }
            if (compared > 0) return emptyList()
            repeat(count) { pos += 1 + u8(pos) }
        }
        return emptyList()
    }

    private fun u8(at: Int) = buffer.get(at).toInt() and 0xFF

    private fun compareFirstKey(block: Int, key: ByteArray): Int {
        val pos = dataStart + buffer.getInt(HEADER_SIZE + 4 * block)
        val length = u8(pos + 1)
        for (i in 0 until minOf(length, key.size)) {
            val d = u8(pos + 2 + i) - key[i]
            if (d != 0) return d
        }
        return length - key.size
    }

    private fun compare(a: ByteArray, length: Int, key: ByteArray): Int {
        for (i in 0 until minOf(length, key.size)) {
            val d = a[i] - key[i]
            if (d != 0) return d
        }
        return length - key.size
    }

    companion object {
        private const val TAG = "TranslitTable"
        private const val MAGIC = 0x48544C31 // "HTL1"
        private const val HEADER_SIZE = 20
        private const val MAX_LENGTH = 255
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


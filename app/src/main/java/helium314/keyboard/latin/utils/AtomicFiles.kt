// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import java.io.File
import java.io.OutputStream

/**
 * Crash-safe writes (review B4, B4w.2): the new content goes to a temporary file in the same folder, which is then
 * renamed over [file]; a write that fails or is cut off (a crash, a full disk) leaves the old file whole, never half of
 * it. The temporary file's name ("<name>.tmp") ends with no ending the app lists (no ".json", "user.dict"…).
 */
object AtomicFiles {
    /** The temporary file's ending: a folder listed as items (the key layouts) leaves such a file out. */
    const val TMP_SUFFIX = ".tmp"

    /** [text] into [file] (UTF-8, as File.writeText). */
    @JvmStatic
    fun write(file: File, text: String) = write(file) { it.write(text.toByteArray()) }

    /** [bytes] into [file]. */
    @JvmStatic
    fun write(file: File, bytes: ByteArray) = write(file) { it.write(bytes) }

    /** [source]'s content into [target] (B4w.3: a copy cut off leaves [target] as it was). */
    @JvmStatic
    fun copy(source: File, target: File) = write(target) { out -> source.inputStream().use { it.copyTo(out) } }

    /** What [content] writes into [file]. Throws what the write throws; then [file] is as it was. */
    fun write(file: File, content: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use { out -> content(out); out.flush(); out.fd.sync() }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        // (a rename over an existing file is one step on Android; one that fails anyway: written in place, as before)
        if (!tmp.renameTo(file)) {
            try { tmp.copyTo(file, overwrite = true) } finally { tmp.delete() }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import java.io.File

/**
 * A local log file kept to its newest records (2026-10-18, owner: "truncate ... but make it flexible in case user wants
 * a longer history retained"). A record starts at a line [isStart] says starts one and runs up to the next such line,
 * so lines that belong to a record (a swipe's outcome after the swipe) stay with it.
 *
 * Trimmed only once the file holds more than a tenth over the limit, so it isn't rewritten on every record; then it is
 * rewritten to the newest `limit` records through a temporary file. Used on the log's own thread only: the count is
 * remembered with the file's length and read again from the file when the length isn't the one it left (cleared,
 * replaced, or another build of the app wrote it).
 */
class KeptRecords(private val isStart: (String) -> Boolean) {
    private var knownLength = -1L
    private var knownCount = 0

    /** Appends [text] (whole lines, each ending in a line break) to [file], then trims it to [limit] records if it holds
     *  over a tenth more; null [limit]: all are kept. */
    fun append(file: File, text: String, limit: Int?) {
        val before = if (file.exists()) file.length() else 0L
        file.appendText(text)
        knownCount = if (before == knownLength) knownCount + text.lineSequence().count { isStart(it) } else count(file)
        knownLength = file.length()
        trimIfOver(file, limit)
    }

    /** Trims [file] to [limit] records if it holds over a tenth more (a lowered limit applies at once). */
    fun trimIfOver(file: File, limit: Int?) {
        if (limit == null || !file.exists()) return
        if (file.length() != knownLength) {
            knownCount = count(file)
            knownLength = file.length()
        }
        if (knownCount.toLong() * 10 <= limit.toLong() * 11) return
        val skip = knownCount - limit
        val tmp = File(file.parentFile, file.name + ".tmp")
        var seen = 0
        tmp.bufferedWriter().use { out ->
            file.useLines { lines ->
                for (line in lines) {
                    if (isStart(line)) seen++
                    if (seen > skip) { out.write(line); out.write("\n") }
                }
            }
        }
        if (!tmp.renameTo(file)) { tmp.delete(); return }
        knownCount = limit
        knownLength = file.length()
    }

    private fun count(file: File): Int = if (!file.exists()) 0 else file.useLines { lines -> lines.count { isStart(it) } }
}

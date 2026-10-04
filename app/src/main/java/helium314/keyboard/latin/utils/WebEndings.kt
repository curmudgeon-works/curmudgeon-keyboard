// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context

/**
 * The top-level domains ("com", "org", "uk"): IANA's list (data.iana.org/TLD/tlds-alpha-by-domain.txt, version
 * 2026100400, without the xn-- ones) in assets/web_endings.txt. A full stop's auto-space is taken back when the word
 * after it turns out to be one of them, unless it's also a word of the keyboard's languages ("net", "in", "me";
 * the caller asks the dictionaries, 2026-10-04).
 */
object WebEndings {
    @Volatile private var endings: Set<String>? = null

    fun isWebEnding(context: Context, word: String): Boolean {
        if (word.length < 2 || word.length > 24) return false
        val set = endings ?: load(context)
        return word.lowercase() in set
    }

    private fun load(context: Context): Set<String> = synchronized(this) {
        endings ?: runCatching {
            context.assets.open("web_endings.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
            }
        }.getOrElse { emptySet() }.also { endings = it }
    }
}

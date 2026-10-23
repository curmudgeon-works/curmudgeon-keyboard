// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translit

import android.content.Context
import helium314.keyboard.latin.utils.ExecutorUtils
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A language whose words can be suggested in another script ([TranslitTable]): [latinTag] is the keyboard language
 *  typed in, [name] the other language's own name for the setting ("Suggest in हिंदी"), [asset] its table. */
class TranslitSpec(val latinTag: String, val name: String, val asset: String)

/**
 * Suggest in हिंदी: the words of a Latin-script language (Hinglish) suggested in the script of its language (Hindi in
 * Devanagari), looked up in a table by the Latin word already chosen. Which languages have one is [specs] (data: add a
 * table and a line to have another one).
 */
object Transliteration {
    /** The tables there are. */
    val specs = listOf(
        TranslitSpec("hi-Latn", "हिंदी", "translit/hi-Latn.bin"),
    )

    fun specFor(locale: Locale): TranslitSpec? = specFor(locale.toLanguageTag())

    fun specFor(languageTag: String): TranslitSpec? = specs.firstOrNull { it.latinTag == languageTag }

    // the tables read so far, by asset: read once, kept (mapped from a file, see TranslitTable.load)
    private val tables = ConcurrentHashMap<String, TranslitTable>()
    private val loading = Collections.synchronizedSet(HashSet<String>())

    /** [spec]'s table if it is read; null while it is still being read ([prepare]). */
    fun table(spec: TranslitSpec): TranslitTable? = tables[spec.asset]

    /** Starts reading [spec]'s table in the background, unless it is read or being read. Only called while a keyboard
     *  has the setting on: with it off, nothing is read. */
    fun prepare(context: Context, spec: TranslitSpec) {
        if (tables.containsKey(spec.asset) || !loading.add(spec.asset)) return
        val app = context.applicationContext ?: context
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { load(app, spec) }
    }

    /** [spec]'s table, read now if it isn't yet (slow: not on the main thread). */
    fun load(context: Context, spec: TranslitSpec): TranslitTable? {
        tables[spec.asset]?.let { return it }
        val table = TranslitTable.load(context, spec.asset)
        if (table != null) tables[spec.asset] = table
        loading.remove(spec.asset)
        return table
    }
}

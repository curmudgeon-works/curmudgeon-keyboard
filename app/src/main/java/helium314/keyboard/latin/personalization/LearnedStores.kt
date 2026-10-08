// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import java.io.File
import java.util.Locale

/**
 * Where learned words and the blacklist live (2026-10-04): one store per SCRIPT (Latin, Devanagari, …), not per
 * language. English and Hinglish are both Latin and share their words; a word goes into the store of its own script,
 * read from its letters (ScriptUtils.scriptOfWord), whichever language was preferred when it was typed.
 *
 * A pool is a set of such stores, one per script: [SHARED] for every keyboard (the setting "Share learned &
 * blacklisted words across keyboards", default on), or with that setting off one pool per keyboard, numbered by the
 * keyboard's profile id (KeyboardProfiles.idFor, which exists for every keyboard whether or not its settings are
 * separate).
 *
 * Files (in filesDir):
 * - learned words: UserHistoryDictionary.<script>.dict, a keyboard's own UserHistoryDictionary.<script>.k<id>.dict
 * - blacklist: blacklists/<script>.txt, a keyboard's own blacklists/k<id>/<script>.txt
 * Before, both were per language (UserHistoryDictionary.<languageTag>.dict, blacklists/<languageTag>.txt); a language
 * tag never looks like a script code (4 letters, capital first), so old and new files can't be confused.
 */
object LearnedStores {
    private const val TAG = "LearnedStores"
    const val SHARED = 0

    /** The pool the keyboard in use learns into and reads from. */
    @Volatile var currentPool = SHARED
        private set

    fun isShared(prefs: SharedPreferences) = prefs.getBoolean(Settings.PREF_SHARE_LEARNED_WORDS, Defaults.PREF_SHARE_LEARNED_WORDS)

    /** The pool of the selected keyboard. [real]: the preferences as stored (not a keyboard's profile view). */
    fun poolFor(real: SharedPreferences): Int =
        if (isShared(real)) SHARED else KeyboardProfiles.idFor(real, KeyboardProfiles.selectedKeyboard(real))

    /** Re-reads which pool is in use (on every keyboard switch, and after the setting changed). A keyboard's own pool
     *  with nothing on disk yet (added since sharing went off) is first filled with a copy of the shared words, in the
     *  background (re-review 2026-10-07: on the main thread it froze the switch); the keyboard keeps the pool it had
     *  until the copy is done, then this runs again and switches. */
    fun refresh(real: SharedPreferences) {
        val pool = try { poolFor(real) } catch (e: Exception) { Log.w(TAG, "could not read the pool", e); SHARED }
        if (pool == currentPool) return
        if (needsSeeding(pool)) { seedInBackground(pool) { refresh(real) }; return }
        currentPool = pool
        helium314.keyboard.latin.utils.HotWords.usePool(pool) // (its recent words with it)
        Log.i(TAG, "learned words pool now $pool")
        listeners.forEach { it() }
    }

    private fun needsSeeding(pool: Int): Boolean {
        if (pool == SHARED || pool == seedingFailed) return false
        val dir = KeyboardProfiles.filesDir ?: return false
        return !LearnedPools.isSeeded(dir, pool)
    }

    private val seeding = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>() // the pools being filled now (a repeat call doesn't start another)
    @Volatile private var seedingFailed: Int? = null // a pool whose copy failed: used empty rather than never
    /** Fills [pool] off the main thread, then calls back on it with whether the copy was made (replaceable by tests). */
    internal var seedRunner: (pool: Int, done: (Boolean) -> Unit) -> Unit = { pool, done ->
        helium314.keyboard.latin.utils.ExecutorUtils.getBackgroundExecutor(helium314.keyboard.latin.utils.ExecutorUtils.KEYBOARD).execute {
            val ok = KeyboardProfiles.filesDir?.let { LearnedPools.seedIfNew(it, LearnedStoreIo.Native, pool) } ?: false
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }
    }
    private fun seedInBackground(pool: Int, then: () -> Unit) {
        if (!seeding.add(pool)) return
        seedRunner(pool) { ok ->
            seeding.remove(pool)
            if (!ok) { Log.w(TAG, "keyboard $pool: its learned words couldn't be copied, starting empty"); seedingFailed = pool }
            then()
        }
    }

    // things built from the learned words of the pool in use (swipe vocabularies, frequent long words): rebuilt when it
    // changes; registered by them, so this file doesn't reach into the keyboard
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onPoolChanged(listener: () -> Unit) { listeners.add(listener) }
    fun removeOnPoolChanged(listener: () -> Unit) { listeners.remove(listener) }

    /** The script whose store [word] is learned in, blacklisted in and read from; [fallback] for a word without letters. */
    @JvmStatic
    fun scriptOf(word: CharSequence, fallback: Locale): String = ScriptUtils.scriptOfWord(word, fallback.script())

    // ---------------- names ----------------

    @JvmStatic
    fun storeName(script: String, pool: Int): String =
        UserHistoryDictionary.NAME + "." + script + (if (pool == SHARED) "" else ".k$pool")

    fun storeFile(filesDir: File, script: String, pool: Int) = File(filesDir, storeName(script, pool) + DICT_EXTENSION)

    /** The locale a store is created with: it stands for the script only ("und-Latn"). */
    @JvmStatic
    fun storeLocale(script: String): Locale = Locale.Builder().setScript(script).build()

    fun blacklistFile(filesDir: File, script: String, pool: Int): File =
        File(File(filesDir, BLACKLIST_DIR), (if (pool == SHARED) "" else "k$pool${File.separator}") + script + ".txt")

    const val DICT_EXTENSION = ".dict"
    const val BLACKLIST_DIR = "blacklists"

    private val scriptCode = Regex("[A-Z][a-z]{3}")
    private val storeFileName = Regex(Regex.escape(UserHistoryDictionary.NAME) + "\\.([A-Z][a-z]{3})(?:\\.k(\\d+))?" + Regex.escape(DICT_EXTENSION))
    private val languageStoreFileName = Regex(Regex.escape(UserHistoryDictionary.NAME) + "\\.(.+)" + Regex.escape(DICT_EXTENSION))

    fun isScriptCode(name: String) = scriptCode.matches(name)

    /** (script, pool) of a store's file name, null if it isn't one (e.g. a per-language store of before). */
    fun parseStoreFileName(name: String): Pair<String, Int>? {
        val match = storeFileName.matchEntire(name) ?: return null
        return match.groupValues[1] to (match.groupValues[2].toIntOrNull() ?: SHARED)
    }

    /** The language tag of a per-language store of before (UserHistoryDictionary.<tag>.dict), null for anything else. */
    fun parseLanguageStoreFileName(name: String): String? {
        if (parseStoreFileName(name) != null) return null
        return languageStoreFileName.matchEntire(name)?.groupValues?.get(1)
    }

    /** The keyboards' own pools that have files (learned words or a blacklist), leftovers of deleted keyboards too. */
    fun keyboardPoolsOnDisk(filesDir: File): Set<Int> {
        val pools = HashSet<Int>()
        filesDir.list()?.forEach { name -> parseStoreFileName(name)?.second?.takeIf { it != SHARED }?.let { pools.add(it) } }
        File(filesDir, BLACKLIST_DIR).listFiles()?.forEach { f ->
            if (f.isDirectory && f.name.startsWith("k")) f.name.drop(1).toIntOrNull()?.let { pools.add(it) }
        }
        return pools
    }

    /** The scripts with a store or blacklist in [pool]. */
    fun scriptsOnDisk(filesDir: File, pool: Int): Set<String> {
        val scripts = HashSet<String>()
        filesDir.list()?.forEach { name -> parseStoreFileName(name)?.takeIf { it.second == pool }?.let { scripts.add(it.first) } }
        val dir = if (pool == SHARED) File(filesDir, BLACKLIST_DIR) else File(File(filesDir, BLACKLIST_DIR), "k$pool")
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".txt") && isScriptCode(f.name.removeSuffix(".txt"))) scripts.add(f.name.removeSuffix(".txt"))
        }
        return scripts
    }

    /** "Latn", or "Latn/k3" for a keyboard's own pool: where a word went, for the corrections log. */
    fun label(script: String, pool: Int) = if (pool == SHARED) script else "$script/k$pool"
}

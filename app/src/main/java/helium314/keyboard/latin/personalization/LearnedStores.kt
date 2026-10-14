// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.content.SharedPreferences
import androidx.core.os.UserManagerCompat
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import java.io.File
import java.util.Locale

/**
 * Where learned words and the blacklist live (2026-10-04): one store per SCRIPT (Latin, Devanagari, …), not per
 * language. English and Hinglish are both Latin and share their words; a word goes into the store of its own script,
 * read from its letters (ScriptUtils.scriptOfWord), whichever language was preferred when it was typed.
 *
 * A pool is a set of such stores, one per script: each keyboard has its own (LearnedKeyboards, 2026-10-10). [SHARED]
 * (pool 0, the files without a number) held the words all keyboards shared before; nothing reads it after the one-time
 * update, which copied it into each keyboard's pool.
 *
 * Files (in filesDir):
 * - learned words: UserHistoryDictionary.<script>.k<pool>.dict (pool 0: UserHistoryDictionary.<script>.dict)
 * - blacklist: blacklists/k<pool>/<script>.txt (pool 0: blacklists/<script>.txt)
 * Before, both were per language (UserHistoryDictionary.<languageTag>.dict, blacklists/<languageTag>.txt); a language
 * tag never looks like a script code (4 letters, capital first), so old and new files can't be confused.
 */
object LearnedStores {
    private const val TAG = "LearnedStores"
    const val SHARED = 0

    /**
     * What the keyboard in use reads (2026-10-10): its own pool, which it learns into, plus with its buttons "Use lists
     * from other keyboards" on, the other listed keyboards' pools: [othersLearned] read as if they were its own learned
     * words, [othersBlacklisted] whose blacklists apply as if they were its own (RemovedWords.Lists). Only once the user
     * is unlocked (the files are in credential storage).
     */
    data class LearnedView(val own: Int, val othersLearned: List<Int> = emptyList(), val othersBlacklisted: List<Int> = emptyList())

    @Volatile var currentView = LearnedView(SHARED)
        private set

    /** The pool the keyboard in use learns into and reads from (its own). */
    val currentPool get() = currentView.own

    /** The pool of the selected keyboard (LearnedKeyboards), null when it has none (a lookup). [real]: the preferences as
     *  stored (not a keyboard's profile view). */
    fun poolFor(real: SharedPreferences): Int? = LearnedKeyboards.poolOrNull(real, KeyboardProfiles.selectedKeyboard(real))

    /** The view of the selected keyboard (prefs only, no files); null when it has no pool (owner 2026-10-11: lookups never
     *  create records; e.g. a changed keyboard's pool moved, the selected keyboard not yet). */
    fun viewFor(real: SharedPreferences): LearnedView? = viewOf(real, KeyboardProfiles.selectedKeyboard(real))

    /** [viewFor] of [keyboard], selected or not (a full restore makes a Remove made meanwhile again on the keyboard's
     *  lists, 20261013_L11). */
    fun viewOf(real: SharedPreferences, keyboard: SettingsSubtype): LearnedView? {
        val own = LearnedKeyboards.poolOrNull(real, keyboard) ?: return null
        if (!LearnedKeyboards.isDone(real)) return LearnedView(own)
        val learned = LearnedKeyboards.usesOthers(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED, keyboard)
        val blacklisted = LearnedKeyboards.usesOthers(real, LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, keyboard)
        if (!learned && !blacklisted) return LearnedView(own)
        if (!unlocked()) return LearnedView(own) // (before unlock no other store is listed, so none is opened)
        val others = LearnedKeyboards.otherPools(real, keyboard, own)
        return LearnedView(own, if (learned) others else emptyList(), if (blacklisted) others else emptyList())
    }

    // the app, for the unlock check (App.onCreate)
    @Volatile private var app: Context? = null
    private fun unlocked() = app?.let { UserManagerCompat.isUserUnlocked(it) } == true

    /** At app start: the view follows the keyboard list, the pools and the buttons as they change (a keyboard added,
     *  deleted or renamed, a button toggled), wherever that's done. */
    fun init(context: Context) {
        app = context.applicationContext ?: context
        val real = DeviceProtectedUtils.getRealSharedPreferences(context)
        real.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh(real)
    }
    // (kept here: the preferences hold their listeners weakly)
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in viewKeys) refresh(prefs)
    }
    private val viewKeys = setOf(Settings.PREF_ENABLED_SUBTYPES, LearnedKeyboards.PREF_POOLS, LearnedKeyboards.PREF_DONE) + LearnedKeyboards.buttonPrefs

    /** Re-reads the view (on every keyboard switch, a change of the keyboard list or the buttons, after the one-time
     *  update, a reset or a restore); what's built from the learned words is rebuilt when it changed. */
    fun refresh(real: SharedPreferences) {
        // (no pool: the view stays as it is, a rename keeps the number; the net gives a listed keyboard its own)
        val view = try { viewFor(real) } catch (e: Exception) { Log.w(TAG, "could not read the pool", e); LearnedView(SHARED) } ?: return
        val old = currentView
        if (view == old) return
        currentView = view
        lists.clear()
        if (view.own != old.own) helium314.keyboard.latin.utils.HotWords.usePool(view.own) // (its recent words with it)
        Log.i(TAG, "learned words pool now ${view.own}" + (if (view.othersLearned.isEmpty() && view.othersBlacklisted.isEmpty()) ""
            else ", other keyboards' learned words ${view.othersLearned}, blacklists ${view.othersBlacklisted}"))
        listeners.forEach { it() }
    }

    // things built from the learned words of the view (swipe vocabularies, frequent long words, the keyboard's
    // dictionaries): rebuilt when it changes; registered by them, so this file doesn't reach into the keyboard
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onPoolChanged(listener: () -> Unit) { listeners.add(listener) }
    fun removeOnPoolChanged(listener: () -> Unit) { listeners.remove(listener) }

    /** The blacklists of [script] that apply on the keyboard in use: its own and the other keyboards' it uses (no file
     *  is read here). */
    fun lists(context: Context, script: String): RemovedWords.Lists {
        lists[script]?.let { return it }
        val view = currentView
        val filesDir = context.filesDir
        val result = RemovedWords.Lists(RemovedWords.forFile(blacklistFile(filesDir, script, view.own)),
            view.othersBlacklisted.map { RemovedWords.forFile(blacklistFile(filesDir, script, it)) })
        if (currentView == view) lists[script] = result
        return result
    }
    private val lists = java.util.concurrent.ConcurrentHashMap<String, RemovedWords.Lists>()

    /** Another keyboard's store of [script] in [pool], read as an extra source: only if its file exists (never created
     *  for it; learning goes into the own store only). The same object as anyone else's (PersonalizationHelper). Off
     *  the main thread (a file check). */
    fun otherStore(context: Context, script: String, pool: Int): UserHistoryDictionary? {
        PersonalizationHelper.getCachedUserHistoryDictionary(script, pool)?.let { return it }
        if (!storeFile(context.filesDir ?: return null, script, pool).exists()) return null
        return PersonalizationHelper.getUserHistoryDictionary(context, script, pool)
    }

    /** [pool]'s words were changed from outside the keyboard (words imported into a new keyboard, 2026-10-10): what is
     *  built from them is rebuilt. Always, not only for the pool in use (it may be read as another keyboard's). Main thread. */
    fun notifyPoolChanged(pool: Int) {
        Log.i(TAG, "pool $pool changed")
        listeners.forEach { it() }
    }

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

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.os.UserManagerCompat
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings

/**
 * A keyboard just added starts with words from other keyboards (2026-10-10, the dialog asked once when it's added):
 * the learned words of the keyboards ticked "Learned" (a word on several: its highest count, what it had too) and the
 * blacklists of those ticked "Blacklisted" (a word on several: its most strikes). The other keyboards keep theirs.
 * Pool 0 (the shared words of before) is never offered nor touched.
 */
object LearnedImport {
    private const val TAG = "LearnedImport"

    /** The keyboards [keyboard] can take words from: the other keyboards in the list (none before the one-time update). */
    fun sources(real: SharedPreferences, keyboard: SettingsSubtype): List<SettingsSubtype> {
        if (!LearnedKeyboards.isDone(real)) return emptyList()
        return SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }.distinct().filter { it != keyboard }
    }

    /** What came in: the distinct learned words and blacklisted words taken from the other keyboards. [complete]: every
     *  store and list of every script was read and written (false: one was left out, see the log); the delete of a
     *  keyboard whose lists were merged into another only deletes its files then. */
    class Result(val learned: Int, val blacklisted: Int, val complete: Boolean = true)

    /**
     * [keyboard] takes the learned words of [learnedFrom] and the blacklists of [blacklistedFrom], in the background (the
     * KEYBOARD executor, never the dialog's scope); then the keyboard rebuilds what it holds and [onDone] runs on the main
     * thread (null: nothing imported). The pools are fixed here (preferences only).
     */
    fun start(context: Context, keyboard: SettingsSubtype, learnedFrom: Collection<SettingsSubtype>,
              blacklistedFrom: Collection<SettingsSubtype>, onDone: (Result?) -> Unit = {}) {
        if (learnedFrom.isEmpty() && blacklistedFrom.isEmpty()) return
        val real = DeviceProtectedUtils.getRealSharedPreferences(context)
        // (the keyboard just added: the net gave it one; the sources are lookups, owner 2026-10-11)
        val pool = LearnedKeyboards.poolForListed(real, keyboard) ?: return
        if (pool == LearnedStores.SHARED) return // (before the update: no keyboard has its own words yet)
        fun poolsOf(keyboards: Collection<SettingsSubtype>) = keyboards.filter { !it.sameKeyboard(keyboard) }
            .mapNotNull { LearnedKeyboards.poolOrNull(real, it) }.filter { it != pool && it != LearnedStores.SHARED }.distinct()
        val learned = poolsOf(learnedFrom)
        val lists = poolsOf(blacklistedFrom)
        val app = context.applicationContext ?: context
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            val result = try { importNow(app, pool, learned, lists) } catch (e: Exception) { Log.e(TAG, "import failed", e); null }
            Handler(Looper.getMainLooper()).post {
                if (result != null) LearnedStores.notifyPoolChanged(pool)
                onDone(result)
            }
        }
    }

    /** The import itself (see [start]): under the files lock, files in [Context.getFilesDir] only (credential storage). */
    internal fun importNow(context: Context, pool: Int, learned: List<Int>, lists: List<Int>): Result? = LearnedKeyboards.withFiles {
        if (pool == LearnedStores.SHARED || !UserManagerCompat.isUserUnlocked(context)) return@withFiles null
        val filesDir = context.filesDir ?: return@withFiles null
        val io = LearnedKeyboards.storeIo
        val words = HashSet<String>()
        var complete = true // (every store and list read and written: see Result.complete)
        for (script in learned.flatMap { LearnedStores.scriptsOnDisk(filesDir, it) }.toSortedSet()) {
            val add = learned.mapNotNull { from ->
                LearnedStoreFiles.read(io, filesDir, script, from)
                    .also { if (it == null) { Log.w(TAG, "$script: a store could not be read"); complete = false } }
            }.filter { it.isNotEmpty() }
            if (add.isEmpty()) continue
            val own = LearnedStoreFiles.read(io, filesDir, script, pool)
            if (own == null) { Log.w(TAG, "$script: the keyboard's own store could not be read, left as it is"); complete = false; continue }
            val merged = mergeHighest(listOf(own) + add)
            if (!LearnedStoreFiles.write(io, filesDir, script, pool, merged)) { Log.w(TAG, "$script: could not be written"); complete = false; continue }
            add.forEach { store -> store.forEach { if (it.isWord) words.add(it.word) } }
            Log.i(TAG, "$script: ${countsOf(merged)} after the import from ${add.size} keyboards")
        }
        val removed = HashSet<String>()
        for (script in lists.flatMap { LearnedStores.scriptsOnDisk(filesDir, it) }.toSortedSet()) {
            val add = lists.mapNotNull { from ->
                val file = LearnedStores.blacklistFile(filesDir, script, from)
                val entries = (RemovedWords.cached(file) ?: file.takeIf { it.isFile }?.let { RemovedWords.forFile(it) })
                    ?.apply { reload() }?.entries()
                // (a list with words in its file that came out empty, or a file that can't be read: its read failed;
                // a file with no words is an empty list, review 2026-10-11 S9: "empty doesn't register as error")
                if (entries.isNullOrEmpty() && file.isFile && file.length() > 0
                    && (try { file.readLines().any { RemovedWords.parse(it) != null } } catch (e: Exception) { true })) {
                    Log.w(TAG, "$script: a blacklist could not be read"); complete = false
                }
                entries?.takeIf { it.isNotEmpty() }
            }
            if (add.isEmpty()) continue
            val merged = RemovedWords.mergeLists(add)
            val target = LearnedStores.blacklistFile(filesDir, script, pool)
            RemovedWords.forFile(target).combine(RemovedWords.formatAll(merged).lines())
            // (a failed write is only logged where it happens, and memory keeps the words: the file itself is checked)
            val written = try { target.takeIf { it.isFile }?.let { RemovedWords.parseAll(it.readLines()) } } catch (e: Exception) { null }
            if (written == null || merged.any { (word, entry) -> (written[word]?.strikes ?: 0) < entry.strikes }) {
                Log.w(TAG, "$script: the blacklist could not be written"); complete = false
            }
            removed.addAll(merged.keys)
            Log.i(TAG, "$script: ${merged.size} blacklisted words from ${add.size} keyboards")
        }
        Result(words.size, removed.size, complete)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import androidx.core.os.UserManagerCompat
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.ScriptUtils.script
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The move from learned words and blacklists per language to per script (2026-10-04, see LearnedStores), on the
 * first start that finds per-language files (UserHistoryDictionary.<languageTag>.dict, blacklists/<languageTag>.txt):
 * - each word (and word pair, …) goes into the store of its own script: English and Hinglish words into one Latin store,
 *   their counts added up, the latest last use kept (the replay of the backup restore, so pairs and longer aren't
 *   counted twice); the same for the blacklists, where a word on several keeps its most strikes
 * - into the shared pool, with what's there already (a store created since, e.g. while the phone was still locked)
 * - the old files aren't deleted: they're moved to filesDir/[PREMERGE_DIR] as they were (rollback: move them back and
 *   delete the per-script files). They don't match the backup's patterns there, so backups don't carry them.
 * - safe if interrupted: the new stores are written aside first; then a journal lists what's left to move, and a start
 *   that finds the journal finishes the moves (each one checks whether it's done). Without a journal, what was written
 *   aside is thrown away and it starts over from the untouched old files. Once done, nothing per language is left, so
 *   it never runs again (idempotent).
 * Only counts go to logcat, never words. Runs in the background at app start; the learned-word stores and blacklists
 * wait for it before they first read their files.
 */
object LearnedStoreMigration {
    private const val TAG = "LearnedStoreMigration"
    const val PREMERGE_DIR = "learned_premerge"
    private const val JOURNAL = "learned_merge_journal"
    private const val STORE = "store"
    private const val BLACKLIST = "blacklist"
    private const val ASIDE = "aside"

    @Volatile private var running: CountDownLatch? = null

    /** Waits while the migration runs (never longer than a few minutes: a hung migration mustn't hang the keyboard). */
    @JvmStatic
    fun awaitDone() {
        val latch = running ?: return
        try { latch.await(3, TimeUnit.MINUTES) } catch (_: InterruptedException) { }
    }

    /** At app start, before anything opens a store: starts the migration in the background if there's anything to move. */
    fun startIfNeeded(context: Context) {
        // the files are in credential-protected storage: before the first unlock there's nothing to see (a later start
        // does it; what was learned meanwhile is merged in then)
        if (!UserManagerCompat.isUserUnlocked(context)) return
        val filesDir = context.filesDir ?: return
        if (!isNeeded(filesDir)) return
        val latch = CountDownLatch(1)
        running = latch
        Thread({
            try {
                run(filesDir, LearnedStoreIo.FilesOnly)
            } catch (t: Throwable) {
                Log.e(TAG, "moving the learned words to per-script stores failed; the next start finishes or redoes it", t)
            } finally {
                latch.countDown()
                running = null
            }
        }, "LearnedStoreMigration").start()
    }

    /** Now, on this thread (a restore of everything brought back per-language files): the open stores are updated too. */
    fun runNow(context: Context) {
        val filesDir = context.filesDir ?: return
        if (isNeeded(filesDir)) run(filesDir, LearnedStoreIo.Native)
    }

    fun isNeeded(filesDir: File): Boolean =
        File(filesDir, JOURNAL).exists() || languageStores(filesDir).isNotEmpty() || languageBlacklists(filesDir).isNotEmpty()

    private fun languageStores(filesDir: File): List<Pair<File, String>> =
        filesDir.listFiles()?.mapNotNull { f -> LearnedStores.parseLanguageStoreFileName(f.name)?.let { f to it } }.orEmpty()

    private fun languageBlacklists(filesDir: File): List<Pair<File, String>> =
        File(filesDir, LearnedStores.BLACKLIST_DIR).listFiles()?.mapNotNull { f ->
            val name = f.name.removeSuffix(".txt")
            if (!f.isFile || !f.name.endsWith(".txt") || LearnedStores.isScriptCode(name)) null else f to name
        }.orEmpty()

    /** The migration itself (see the class). @return false if it stopped early (nothing changed then) */
    fun run(filesDir: File, io: LearnedStoreIo): Boolean {
        val journal = File(filesDir, JOURNAL)
        if (journal.exists()) {
            Log.i(TAG, "finishing a migration that was interrupted")
            finish(filesDir, io, journal)
        }
        if (!prepare(filesDir, io)) return false
        if (journal.exists()) finish(filesDir, io, journal)
        return true
    }

    /**
     * The first half: the new stores and lists written aside, then the journal of what's left to move. Nothing the
     * keyboard reads is changed yet. @return false if it stopped early (and threw away what it wrote); true also when
     * there's nothing to do (no journal then)
     */
    internal fun prepare(filesDir: File, io: LearnedStoreIo): Boolean {
        val journal = File(filesDir, JOURNAL)
        val stores = languageStores(filesDir)
        val blacklists = languageBlacklists(filesDir)
        if (stores.isEmpty() && blacklists.isEmpty()) return true
        val work = File(filesDir, LearnedStoreFiles.WORK_DIR)
        work.deleteRecursively() // a run that stopped before its journal: start over

        // learned words, by the script of each word
        val byScript = HashMap<String, MutableList<List<LearnedEntry>>>()
        for ((file, tag) in stores) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val entries = io.readFile(file, locale)
            if (entries == null) {
                // (what the keyboard couldn't read either: it would have started that store over)
                Log.w(TAG, "$tag: no readable store, set aside as it is")
                continue
            }
            val routed = byScript(entries, locale.script())
            Log.i(TAG, "$tag: ${countsOf(entries)} -> " + routed.entries.joinToString { "${it.key} ${it.value.size}" })
            for ((script, part) in routed) byScript.getOrPut(script) { mutableListOf() }.add(part)
        }
        val written = mutableListOf<String>()
        for ((script, parts) in byScript) {
            val existing = LearnedStoreFiles.read(io, filesDir, script, LearnedStores.SHARED)
            if (existing == null) {
                Log.e(TAG, "$script: the existing store can't be read, nothing moved")
                work.deleteRecursively()
                return false
            }
            val merged = mergeAdding(listOf(existing) + parts)
            val target = File(work, LearnedStores.storeFile(filesDir, script, LearnedStores.SHARED).name)
            if (!io.writeFile(target, LearnedStores.storeLocale(script), merged)) {
                Log.e(TAG, "$script: could not write the merged store, nothing moved")
                work.deleteRecursively()
                return false
            }
            Log.i(TAG, "$script: ${countsOf(existing)} there before, ${countsOf(merged)} merged")
            written.add(script)
        }

        // blacklists, by the script of each word
        val listsByScript = HashMap<String, MutableList<Map<String, RemovedWords.Entry>>>()
        for ((file, tag) in blacklists) {
            val fallback = java.util.Locale.forLanguageTag(tag).script()
            val entries = RemovedWords.parseAll(file.readLines())
            for ((word, entry) in entries)
                listsByScript.getOrPut(helium314.keyboard.latin.utils.ScriptUtils.scriptOfWord(word, fallback)) { mutableListOf() }
                    .add(mapOf(word to entry))
            Log.i(TAG, "blacklist $tag: ${entries.size} words")
        }
        val writtenLists = mutableListOf<String>()
        for ((script, parts) in listsByScript) {
            val target = LearnedStores.blacklistFile(filesDir, script, LearnedStores.SHARED)
            val existing = if (target.isFile) RemovedWords.parseAll(target.readLines()) else emptyMap()
            val merged = RemovedWords.mergeLists(listOf(existing) + parts)
            val aside = File(File(work, LearnedStores.BLACKLIST_DIR), target.name)
            aside.parentFile?.mkdirs()
            aside.writeText(RemovedWords.formatAll(merged))
            Log.i(TAG, "blacklist $script: ${existing.size} there before, ${merged.size} merged")
            writtenLists.add(script)
        }

        // all written: from here on the journal says what's left to do
        val lines = written.map { "$STORE\t$it" } + writtenLists.map { "$BLACKLIST\t$it" } +
            stores.map { "$ASIDE\t${it.first.name}" } +
            blacklists.map { "$ASIDE\t${LearnedStores.BLACKLIST_DIR}${File.separator}${it.first.name}" }
        val journalWork = File(filesDir, "$JOURNAL.tmp")
        journalWork.writeText(lines.joinToString("\n", postfix = "\n"))
        if (!journalWork.renameTo(journal)) {
            Log.e(TAG, "could not write the journal, nothing moved")
            journalWork.delete()
            work.deleteRecursively()
            return false
        }
        return true
    }

    /** The moves the journal lists, each skipped if it's done already; then the journal goes. */
    private fun finish(filesDir: File, io: LearnedStoreIo, journal: File) {
        val work = File(filesDir, LearnedStoreFiles.WORK_DIR)
        val premerge = File(filesDir, PREMERGE_DIR)
        for (line in journal.readLines()) {
            val (kind, name) = line.split('\t').takeIf { it.size == 2 } ?: continue
            when (kind) {
                ASIDE -> {
                    val file = File(filesDir, name)
                    if (!file.exists()) continue
                    var target = File(premerge, name)
                    var n = 1
                    while (target.exists()) target = File(premerge, "$name.${n++}") // (set aside before: kept as well)
                    target.parentFile?.mkdirs()
                    if (!file.renameTo(target)) Log.e(TAG, "could not set aside ${file.name}")
                }
                STORE -> {
                    val written = File(work, LearnedStores.storeFile(filesDir, name, LearnedStores.SHARED).name)
                    if (written.exists() && !LearnedStoreFiles.install(io, filesDir, name, LearnedStores.SHARED, written))
                        Log.e(TAG, "$name: could not put the merged store in place")
                }
                BLACKLIST -> {
                    val target = LearnedStores.blacklistFile(filesDir, name, LearnedStores.SHARED)
                    val written = File(File(work, LearnedStores.BLACKLIST_DIR), target.name)
                    if (!written.exists()) continue
                    target.parentFile?.mkdirs()
                    target.delete()
                    if (!written.renameTo(target)) Log.e(TAG, "blacklist $name: could not put the merged list in place")
                    RemovedWords.cached(target)?.reloadAsync() // (held already: a restore while the keyboard runs)
                }
            }
        }
        journal.delete()
        work.deleteRecursively()
        Log.i(TAG, "learned words and blacklists are per script now; the old files are in $PREMERGE_DIR")
    }
}

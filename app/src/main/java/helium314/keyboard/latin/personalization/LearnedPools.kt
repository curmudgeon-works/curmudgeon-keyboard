// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import java.io.File

/**
 * "Share learned & blacklisted words across keyboards" switched (Advanced, app-wide, default on; see LearnedStores):
 * - off: every keyboard gets its own learned words and blacklist per script, each starting as a copy of the shared ones
 * - on again: the keyboards' own are put together into the shared ones. They're copies of one start learned on since,
 *   so per word (and word pair) the highest count and the latest use count, not the sum; on blacklists the most strikes.
 *   The keyboards' own (of deleted keyboards too) are then emptied: all they had is in the shared ones, and switching off
 *   again starts each keyboard from a fresh copy.
 * The shared ones stay as they are while the keyboards have their own.
 */
object LearnedPools {
    private const val TAG = "LearnedPools"

    /** Switches the setting, moving the words as above, then the keyboard uses the new pool. Call off the main thread.
     *  @return false if the words couldn't be moved (the setting stays as it was then) */
    fun setShared(context: Context, real: SharedPreferences, shared: Boolean): Boolean {
        if (LearnedStores.isShared(real) == shared) return true
        val filesDir = context.filesDir ?: return false
        val ok = if (shared) share(filesDir, LearnedStoreIo.Native)
            else separate(filesDir, LearnedStoreIo.Native, keyboardPools(real))
        if (!ok) return false
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, shared).apply()
        helium314.keyboard.latin.utils.SettingsEventLog.log("share learned words ${if (shared) "ON" else "OFF"}")
        LearnedStores.refresh(real)
        return true
    }

    /** A keyboard whose own pool has nothing on disk yet (added after sharing went off) starts as a copy of the shared
     *  learned words and blacklists, like the keyboards there were when it went off (re-review 2026-10-07: it started
     *  empty). A pool with files, even emptied ones, is left as it is. */
    fun seedIfNew(filesDir: File, io: LearnedStoreIo, pool: Int): Boolean {
        if (isSeeded(filesDir, pool)) return true
        Log.i(TAG, "keyboard $pool: its own learned words start as a copy of the shared ones")
        return separate(filesDir, io, listOf(pool))
    }

    // a pool is seeded when its marker is there, written after its files (re-review 2026-10-07: a process killed
    // mid-copy left files that counted as a seeded pool)
    private fun marker(filesDir: File, pool: Int) = File(filesDir, "learned_seeded_k$pool")
    fun isSeeded(filesDir: File, pool: Int) = pool == LearnedStores.SHARED || marker(filesDir, pool).isFile
    private fun markSeeded(filesDir: File, pool: Int) { runCatching { marker(filesDir, pool).writeText("") } }
    /** Every pool with files on disk is a seeded one: after a restore (they came whole from the backup; reviewer
     *  2026-10-07: without this the pool refresh copied the shared words over a restored pool). */
    fun markPoolsOnDisk(filesDir: File) { for (pool in LearnedStores.keyboardPoolsOnDisk(filesDir)) markSeeded(filesDir, pool) }
    /** Once: the pools that have files from before the markers existed are seeded ones. */
    fun markExistingPools(filesDir: File, real: SharedPreferences) {
        if (real.getBoolean("learned_pools_marked", false)) return
        markPoolsOnDisk(filesDir)
        real.edit().putBoolean("learned_pools_marked", true).apply()
    }

    /** The pools of the keyboards in the list. */
    fun keyboardPools(real: SharedPreferences): List<Int> =
        SubtypeSettings.getEnabledSubtypes(true).map { KeyboardProfiles.idFor(real, it.toSettingsSubtype()) }.distinct()

    /** Each of [pools] gets a copy of the shared learned words and blacklists (what it had of its own before is replaced). */
    fun separate(filesDir: File, io: LearnedStoreIo, pools: Collection<Int>): Boolean {
        val scripts = LearnedStores.scriptsOnDisk(filesDir, LearnedStores.SHARED)
        for (script in scripts) {
            val entries = LearnedStoreFiles.read(io, filesDir, script, LearnedStores.SHARED) ?: return false
            val list = blacklist(filesDir, script, LearnedStores.SHARED).entries()
            for (pool in pools) {
                if (!LearnedStoreFiles.write(io, filesDir, script, pool, entries)) return false
                blacklist(filesDir, script, pool).replaceAll(list)
            }
            Log.i(TAG, "$script: ${countsOf(entries)}, ${list.size} blacklisted, copied to ${pools.size} keyboards")
        }
        // (a script only a keyboard's own pool had, from before: not part of the copy)
        for (pool in pools) for (script in LearnedStores.scriptsOnDisk(filesDir, pool) - scripts) empty(filesDir, io, script, pool)
        for (pool in pools) markSeeded(filesDir, pool)
        return true
    }

    /** The keyboards' own learned words and blacklists (all on disk) put together into the shared ones, then emptied. */
    fun share(filesDir: File, io: LearnedStoreIo): Boolean {
        val pools = LearnedStores.keyboardPoolsOnDisk(filesDir)
        val scripts = pools.flatMap { LearnedStores.scriptsOnDisk(filesDir, it) }.toSet()
        for (script in scripts) {
            val stores = pools.map { LearnedStoreFiles.read(io, filesDir, script, it) ?: return false }
            val merged = mergeHighest(stores)
            if (!LearnedStoreFiles.write(io, filesDir, script, LearnedStores.SHARED, merged)) return false
            val lists = RemovedWords.mergeLists(pools.map { blacklist(filesDir, script, it).entries() })
            blacklist(filesDir, script, LearnedStores.SHARED).replaceAll(lists)
            Log.i(TAG, "$script: ${pools.size} keyboards put together, ${countsOf(merged)}, ${lists.size} blacklisted")
        }
        for (pool in pools) for (script in LearnedStores.scriptsOnDisk(filesDir, pool)) empty(filesDir, io, script, pool)
        return true
    }

    /** A pool's store and list of [script] gone (a store the keyboard has open is emptied instead: it would save its
     *  words again when it's closed). */
    private fun empty(filesDir: File, io: LearnedStoreIo, script: String, pool: Int) {
        if (io.live(script, pool) != null) LearnedStoreFiles.write(io, filesDir, script, pool, emptyList())
        else LearnedStores.storeFile(filesDir, script, pool).deleteRecursively()
        val list = LearnedStores.blacklistFile(filesDir, script, pool)
        RemovedWords.cached(list)?.replaceAll(emptyMap())
        list.delete()
        list.parentFile?.takeIf { pool != LearnedStores.SHARED && it.list()?.isEmpty() == true }?.delete()
    }

    private fun blacklist(filesDir: File, script: String, pool: Int) = RemovedWords.forFile(LearnedStores.blacklistFile(filesDir, script, pool))
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.os.UserManagerCompat
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Every keyboard has its own learned words and blacklist (2026-10-10; the switch "Share learned & blacklisted words
 * across keyboards" is gone): a pool (see LearnedStores) per keyboard, found by the keyboard's identity
 * (SettingsSubtype.toPref), not its profile id (a factory reset starts the ids again, a restore brings the backup's).
 *
 * Preferences (app-wide, never a keyboard's own):
 * - [PREF_POOLS]: identity -> pool; [PREF_POOL_NEXT]: the next pool number, never lower (a reset keeps both)
 * - [PREF_USE_OTHERS_LEARNED], [PREF_USE_OTHERS_BLACKLISTED]: the keyboards set to use the other keyboards' lists
 * - [PREF_DONE]: the one-time update below is done. Until then the old rule applies: the shared pool 0 with the switch
 *   on, else the keyboard's profile id. So the pools the update copies into are in no one's use while it runs.
 *
 * The one-time update ([updateOnce]), once the user is unlocked (the files are in credential storage): with sharing on
 * each keyboard gets a fresh pool holding a copy of the shared words and blacklists, and both buttons on; with sharing off
 * each keyboard keeps its own pool, buttons off. Pool 0 (the shared files) is then read by nothing and left on disk.
 */
object LearnedKeyboards {
    private const val TAG = "LearnedKeyboards"
    const val PREF_POOLS = "learned_pools"
    const val PREF_POOL_NEXT = "learned_pool_next"
    const val PREF_DONE = "learned_per_keyboard_done"
    const val PREF_PLAN = "learned_update_plan" // the update's pools, decided before the copy (redone with them after a kill)
    const val PREF_USE_OTHERS_LEARNED = "learned_use_others_learned"
    const val PREF_USE_OTHERS_BLACKLISTED = "learned_use_others_blacklisted"
    val buttonPrefs = listOf(PREF_USE_OTHERS_LEARNED, PREF_USE_OTHERS_BLACKLISTED)

    /** Bookkeeping of the files, not settings: a factory reset keeps them. */
    val keptOnReset = setOf(PREF_POOLS, PREF_POOL_NEXT, PREF_DONE, PREF_PLAN)
    /** What a full restore puts back as the phone had it: it names the phone's files, not the backup's (the switch too,
     *  read only by the update and old backups). */
    private val phoneKeys = keptOnReset + Settings.PREF_SHARE_LEARNED_WORDS
    /** Never a keyboard's own (KeyboardProfiles.globalKeys). */
    val appWideKeys = phoneKeys + buttonPrefs

    /** The one lock of everything that changes pools on disk (the update, a reset, a restore): the KEYBOARD executor has
     *  several threads. Background threads only, but for [startUpdate]'s try. */
    @PublishedApi internal val files = ReentrantLock()
    inline fun <T> withFiles(block: () -> T): T {
        files.lock()
        try { return block() } finally { files.unlock() }
    }

    /** Reads and writes the stores (replaceable by tests: no native library there). */
    @Volatile internal var storeIo: LearnedStoreIo = LearnedStoreIo.Native

    fun isDone(real: SharedPreferences) = real.getBoolean(PREF_DONE, false)

    private fun shared(real: SharedPreferences) = real.getBoolean(Settings.PREF_SHARE_LEARNED_WORDS, Defaults.PREF_SHARE_LEARNED_WORDS)

    // ---------------- which pool ----------------

    /** The pool of [keyboard], a new one on first use. Before the update: the old rule (see the class). Prefs only: safe
     *  before unlock and on the main thread. [real]: the preferences as stored. */
    fun poolFor(real: SharedPreferences, keyboard: SettingsSubtype): Int {
        if (!isDone(real)) return if (shared(real)) LearnedStores.SHARED else KeyboardProfiles.idFor(real, keyboard)
        // (never calls into KeyboardProfiles while holding this: its hooks call in here)
        synchronized(this) {
            val map = pools(real)
            val key = keyboard.toPref()
            if (map.has(key)) return map.getInt(key)
            val pool = nextFree(real, map)
            map.put(key, pool)
            real.edit().putString(PREF_POOLS, map.toString()).putInt(PREF_POOL_NEXT, pool + 1).apply()
            Log.i(TAG, "a new keyboard: pool $pool")
            return pool
        }
    }

    private fun nextFree(real: SharedPreferences, map: JSONObject): Int =
        maxOf(real.getInt(PREF_POOL_NEXT, 1), (map.keys().asSequence().maxOfOrNull { map.optInt(it) } ?: 0) + 1, 1)

    private fun pools(real: SharedPreferences): JSONObject =
        try { JSONObject(real.getString(PREF_POOLS, null) ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Whether [keyboard] uses the other keyboards' lists of button [pref] (one of [buttonPrefs]). */
    fun usesOthers(real: SharedPreferences, pref: String, keyboard: SettingsSubtype) = keyboard.toPref() in ids(real.getString(pref, null))

    private fun ids(json: String?): LinkedHashSet<String> = try {
        JSONArray(json ?: "[]").let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } }
    } catch (e: Exception) { LinkedHashSet() }

    private fun SharedPreferences.Editor.putIds(pref: String, ids: Collection<String>) {
        if (ids.isEmpty()) remove(pref) else putString(pref, JSONArray(ids).toString())
    }

    // ---------------- keyboards changed ----------------

    /** [from] became [to] (rename, languages, layout; KeyboardProfiles.onKeyboardChanged): its words and buttons follow.
     *  As there: [to] has a pool already (a preview of another keyboard) → nothing moves. */
    @Synchronized
    fun onKeyboardChanged(real: SharedPreferences, from: SettingsSubtype, to: SettingsSubtype) {
        if (from == to) return
        val fromKey = from.toPref()
        val toKey = to.toPref()
        val editor = real.edit()
        var changed = false
        val map = pools(real)
        if (!map.has(toKey)) {
            if (map.has(fromKey)) {
                map.put(toKey, map.getInt(fromKey)); map.remove(fromKey)
                editor.putString(PREF_POOLS, map.toString()); changed = true
            }
            for (pref in buttonPrefs) {
                val set = ids(real.getString(pref, null))
                if (set.remove(fromKey)) { set.add(toKey); editor.putIds(pref, set); changed = true }
            }
        }
        // while the update runs: its plan too
        readPlan(real)?.let { plan ->
            if (plan.pools.containsKey(toKey)) return@let
            plan.pools.remove(fromKey)?.let { plan.pools[toKey] = it; editor.putString(PREF_PLAN, plan.json()); changed = true }
        }
        if (changed) editor.apply()
    }

    /** [keyboard] was deleted: its entry and buttons go (its files stay on disk). */
    @Synchronized
    fun onKeyboardDeleted(real: SharedPreferences, keyboard: SettingsSubtype) {
        val key = keyboard.toPref()
        val editor = real.edit()
        var changed = false
        val map = pools(real)
        if (map.remove(key) != null) { editor.putString(PREF_POOLS, map.toString()); changed = true }
        for (pref in buttonPrefs) {
            val set = ids(real.getString(pref, null))
            if (set.remove(key)) { editor.putIds(pref, set); changed = true }
        }
        readPlan(real)?.let { plan -> if (plan.pools.remove(key) != null) { editor.putString(PREF_PLAN, plan.json()); changed = true } }
        if (changed) editor.apply()
    }

    // ---------------- the one-time update ----------------

    private class Plan(val pools: LinkedHashMap<String, Int>, var next: Int) {
        fun json(): String = JSONObject().put("pools", JSONObject(pools as Map<*, *>)).put("next", next).toString()
    }

    private fun readPlan(real: SharedPreferences): Plan? {
        val json = real.getString(PREF_PLAN, null) ?: return null
        return try {
            val o = JSONObject(json)
            val p = o.getJSONObject("pools")
            Plan(p.keys().asSequence().associateWithTo(LinkedHashMap()) { p.getInt(it) }, o.getInt("next"))
        } catch (e: Exception) { null }
    }

    /** The keyboards in the list (the default ones if none), as stored: a restore of everything hasn't reloaded the
     *  list yet, and the loaded one is changed on the main thread. */
    private fun listedKeyboards(real: SharedPreferences): List<SettingsSubtype> {
        val stored = real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!
        if (stored.isNotEmpty()) SubtypeSettings.createSettingsSubtypes(stored).distinct().takeIf { it.isNotEmpty() }?.let { return it }
        repeat(3) {
            try {
                return SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }.distinct()
                    .ifEmpty { listOf(KeyboardProfiles.selectedKeyboard(real)) }
            } catch (_: ConcurrentModificationException) { } // (changed on the main thread meanwhile: read again)
        }
        return listOf(KeyboardProfiles.selectedKeyboard(real))
    }

    /** At app start (App.onCreate): the update once the user is unlocked; a locked start leaves it to the next start (the
     *  keyboard's process starts again at unlock). In the background; only with nothing to copy (sharing off, or no
     *  shared words) it's done here and now: preferences and file names only, as LearnedStoreMigration's check before. */
    fun startUpdate(context: Context) {
        val real = DeviceProtectedUtils.getRealSharedPreferences(context)
        if (isDone(real) || !UserManagerCompat.isUserUnlocked(context)) return
        val app = context.applicationContext ?: context
        if (!needsCopy(app, real) && readPlan(real) == null && files.tryLock()) {
            try { if (updateOnce(app)) return } finally { files.unlock() }
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { updateOnce(app) }
    }

    private fun needsCopy(context: Context, real: SharedPreferences): Boolean {
        if (!shared(real)) return false
        if (LearnedStoreMigration.isRunning) return true
        val dir = context.filesDir ?: return true
        return LearnedStoreMigration.isNeeded(dir) || LearnedStores.scriptsOnDisk(dir, LearnedStores.SHARED).isNotEmpty()
    }

    /**
     * The one-time update (see the class), if not done yet; then the keyboard switches pools. Under [files]; off the main
     * thread (but see [startUpdate]). Files in [Context.getFilesDir] only (credential storage).
     * - the plan first (each keyboard's pool; fresh numbers above any in use or on disk with sharing on), committed
     * - with sharing on, the copy into the planned pools (no one uses them yet: a kill before the end redoes it, same plan)
     * - keyboards added meanwhile join the plan (and get a copy) until the list stays the same
     * - one commit: the pools, the next number, the buttons, the flag
     * @return whether it's done
     */
    fun updateOnce(context: Context): Boolean = withFiles {
        val real = DeviceProtectedUtils.getRealSharedPreferences(context)
        if (isDone(real)) return@withFiles true
        if (!UserManagerCompat.isUserUnlocked(context)) return@withFiles false
        val filesDir = context.filesDir ?: return@withFiles false
        try {
            LearnedStoreMigration.awaitDone()
            if (LearnedStoreMigration.isNeeded(filesDir)) {
                Log.w(TAG, "the learned words aren't per script yet: the update waits for the next start")
                return@withFiles false
            }
            val shared = shared(real)
            val earlier = readPlan(real)
            val plan = earlier ?: newPlan(real, filesDir, shared)
            // (nothing to copy: no shared words, or sharing off)
            val copy = shared && (earlier != null || LearnedStores.scriptsOnDisk(filesDir, LearnedStores.SHARED).isNotEmpty())
            if (earlier == null) {
                // kept for a rerun after a kill, with the pools copied into (without a copy the same plan comes out again)
                if (copy) real.edit().putString(PREF_PLAN, plan.json()).commit()
                Log.i(TAG, "update: sharing ${if (shared) "on" else "off"}, pools ${plan.pools.values}")
            }
            if (copy && !copyShared(filesDir, storeIo, plan.pools.values.distinct())) return@withFiles false
            repeat(20) {
                val listed = listedKeyboards(real)
                // (the profile ids outside the lock below: KeyboardProfiles calls in here holding its own)
                val ids = if (shared) emptyMap() else listed.associate { it.toPref() to KeyboardProfiles.idFor(real, it) }
                val added = synchronized(this) {
                    val now = readPlan(real) ?: plan // (a hook may have moved an entry)
                    val keys = listedKeyboards(real).map { it.toPref() }
                    val missing = keys.filter { it !in now.pools }
                    if (missing.isEmpty()) { finish(real, now, shared, keys); return@synchronized null }
                    val pools = ArrayList<Int>()
                    for (key in missing) {
                        val pool = if (shared) now.next else ids[key] ?: continue // (not known yet: next round)
                        now.pools[key] = pool
                        now.next = maxOf(now.next, pool + 1)
                        pools.add(pool)
                    }
                    if (copy) real.edit().putString(PREF_PLAN, now.json()).commit() else real.edit().putString(PREF_PLAN, now.json()).apply()
                    pools
                } ?: run {
                    Log.i(TAG, "update done")
                    afterUpdate(real)
                    return@withFiles true
                }
                Log.i(TAG, "update: keyboards added meanwhile, pools $added")
                if (copy && !copyShared(filesDir, storeIo, added)) return@withFiles false
            }
            Log.w(TAG, "update: the keyboard list kept changing, again at the next start")
            false
        } catch (e: Exception) {
            Log.e(TAG, "update failed, again at the next start", e)
            false
        }
    }

    private fun newPlan(real: SharedPreferences, filesDir: File, shared: Boolean): Plan {
        val onDisk = (LearnedStores.keyboardPoolsOnDisk(filesDir).maxOrNull() ?: 0) + 1
        val pools = LinkedHashMap<String, Int>()
        var next = maxOf(KeyboardProfiles.nextProfileId(real), onDisk, 1)
        if (shared) {
            for (keyboard in listedKeyboards(real)) pools.getOrPut(keyboard.toPref()) { next++ }
        } else {
            // each keyboard its own pool of before, the keyboards with an id not in the list too (switched off)
            for (keyboard in listedKeyboards(real)) pools[keyboard.toPref()] = KeyboardProfiles.idFor(real, keyboard)
            for ((key, id) in KeyboardProfiles.idEntries(real)) if (id != LearnedStores.SHARED) pools.putIfAbsent(key, id)
            next = maxOf(next, (pools.values.maxOrNull() ?: 0) + 1, KeyboardProfiles.nextProfileId(real))
        }
        return Plan(pools, next)
    }

    private fun finish(real: SharedPreferences, plan: Plan, shared: Boolean, keyboards: List<String>) {
        val editor = real.edit()
            .putString(PREF_POOLS, JSONObject(plan.pools as Map<*, *>).toString())
            .putInt(PREF_POOL_NEXT, plan.next)
        for (pref in buttonPrefs) if (shared) editor.putIds(pref, keyboards) else editor.remove(pref)
        editor.putBoolean(PREF_DONE, true).remove(PREF_PLAN).remove("learned_pools_marked")
        // (Settings.PREF_SHARE_LEARNED_WORDS stays, unread: going back to the version before finds the old mode again)
        editor.commit()
    }

    /** The keyboard switches pools; on the main thread. */
    private fun afterUpdate(real: SharedPreferences) {
        if (Looper.myLooper() == Looper.getMainLooper()) LearnedStores.refresh(real)
        else Handler(Looper.getMainLooper()).post { LearnedStores.refresh(real) }
    }

    /** Each of [pools] gets a copy of the shared learned words and blacklists (what it had is replaced). */
    private fun copyShared(filesDir: File, io: LearnedStoreIo, pools: Collection<Int>): Boolean {
        val scripts = LearnedStores.scriptsOnDisk(filesDir, LearnedStores.SHARED)
        for (script in scripts) {
            val entries = LearnedStoreFiles.read(io, filesDir, script, LearnedStores.SHARED) ?: return false
            // (read again: a restore may have replaced the file under the list the keyboard holds)
            val list = blacklist(filesDir, script, LearnedStores.SHARED).apply { reload() }.entries()
            for (pool in pools) {
                if (!LearnedStoreFiles.write(io, filesDir, script, pool, entries)) return false
                blacklist(filesDir, script, pool).apply { reload() }.replaceAll(list)
            }
            Log.i(TAG, "$script: ${countsOf(entries)}, ${list.size} blacklisted, copied to ${pools.size} keyboards")
        }
        // (a script only the pool had, from before: not part of the copy)
        for (pool in pools) for (script in LearnedStores.scriptsOnDisk(filesDir, pool) - scripts) empty(filesDir, io, script, pool)
        return true
    }

    // ---------------- factory reset ----------------

    /** A factory reset, before the settings go (background): the update done first; [learnedWords]: every store and every
     *  blacklist deleted (2026-10-10: the blacklists stayed). */
    fun beforeFactoryReset(context: Context, learnedWords: Boolean) = withFiles {
        updateOnce(context)
        if (!learnedWords) return@withFiles
        PersonalizationHelper.removeAllUserHistoryDictionaries(context)
        val dir = File(context.filesDir ?: return@withFiles, LearnedStores.BLACKLIST_DIR)
        deleteLists(dir)
        Log.i(TAG, "factory reset: every keyboard's learned words and blacklists deleted")
    }

    /** A factory reset of the keyboards, after it (background): only [keyboards]' learned words stay, the other keyboards'
     *  are deleted (the shared ones of before, pool 0, stay on disk). */
    fun keepOnly(context: Context, keyboards: List<SettingsSubtype>) = withFiles {
        val real = DeviceProtectedUtils.getRealSharedPreferences(context)
        if (!isDone(real)) return@withFiles
        val keep = keyboards.map { it.toPref() }.toSet()
        val kept = synchronized(this) {
            val map = pools(real)
            map.keys().asSequence().toList().filter { it !in keep }.forEach { map.remove(it) }
            real.edit().putString(PREF_POOLS, map.toString()).commit()
            map.keys().asSequence().map { map.getInt(it) }.toSet()
        }
        val filesDir = context.filesDir ?: return@withFiles
        val gone = LearnedStores.keyboardPoolsOnDisk(filesDir) - kept
        for (pool in gone) deletePool(filesDir, storeIo, pool)
        Log.i(TAG, "factory reset: ${gone.size} other keyboards' learned words deleted, ${kept.size} kept")
    }

    /** [pool]'s stores and lists gone. */
    private fun deletePool(filesDir: File, io: LearnedStoreIo, pool: Int) {
        if (pool == LearnedStores.SHARED) return
        for (script in LearnedStores.scriptsOnDisk(filesDir, pool)) empty(filesDir, io, script, pool)
        deleteLists(File(File(filesDir, LearnedStores.BLACKLIST_DIR), "k$pool"))
    }

    /** Every list in [dir] emptied (the objects the keyboard holds too), then the folder deleted. */
    private fun deleteLists(dir: File) {
        for (list in RemovedWords.cachedIn(dir)) list.replaceAll(emptyMap())
        dir.deleteRecursively()
    }

    /** A pool's store and list of [script] gone (a store the keyboard has open is emptied instead: it would save its words
     *  again when it's closed). */
    private fun empty(filesDir: File, io: LearnedStoreIo, script: String, pool: Int) {
        if (io.live(script, pool) != null) LearnedStoreFiles.write(io, filesDir, script, pool, emptyList())
        else LearnedStores.storeFile(filesDir, script, pool).deleteRecursively()
        val list = LearnedStores.blacklistFile(filesDir, script, pool)
        RemovedWords.cached(list)?.replaceAll(emptyMap())
        list.delete()
        list.parentFile?.takeIf { pool != LearnedStores.SHARED && it.list()?.isEmpty() == true }?.delete()
    }

    private fun blacklist(filesDir: File, script: String, pool: Int) = RemovedWords.forFile(LearnedStores.blacklistFile(filesDir, script, pool))

    // ---------------- restore ----------------

    /** The phone's bookkeeping, read before a full restore replaces the preferences ([afterFullRestore] puts it back). */
    fun phoneState(real: SharedPreferences): Map<String, Any?> = real.all.filterKeys { it in phoneKeys }

    /** After a full restore: the phone's bookkeeping back over the backup's (the phone's files are the phone's; the
     *  backup's words are added to them by keyboard), and the buttons of the backup's [keyboards] (see [restoreButtons]). */
    fun afterFullRestore(real: SharedPreferences, phone: Map<String, Any?>, backup: Map<String, Any?>, keyboards: List<SettingsSubtype>) {
        synchronized(this) {
            val editor = real.edit()
            for (key in phoneKeys) phone[key]?.let { KeyboardProfiles.put(editor, key, it) } ?: editor.remove(key)
            editor.commit()
        }
        restoreButtons(real, backup, keyboards)
    }

    /** [keyboards]' buttons as in the [backup] (its preferences); a backup from before them: on if it shared its words,
     *  as the update does. */
    fun restoreButtons(real: SharedPreferences, backup: Map<String, Any?>, keyboards: List<SettingsSubtype>) = synchronized(this) {
        val withButtons = backup[PREF_DONE] == true
        val shared = backup[Settings.PREF_SHARE_LEARNED_WORDS] != false
        val editor = real.edit()
        for (pref in buttonPrefs) {
            val set = ids(real.getString(pref, null))
            val inBackup = ids(backup[pref] as? String)
            for (keyboard in keyboards) {
                val key = keyboard.toPref()
                if (if (withButtons) key in inBackup else shared) set.add(key) else set.remove(key)
            }
            editor.putIds(pref, set)
        }
        editor.commit()
    }

    /** The pool whose words [keyboard] had in [backup] (its preferences): its own; in a backup from before, the shared one
     *  (a copy for each keyboard, as the update does) or its profile id's; null: none. */
    fun poolIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Int? {
        if (backup[PREF_DONE] == true) {
            val map = try { JSONObject(backup[PREF_POOLS] as? String ?: "{}") } catch (e: Exception) { JSONObject() }
            return keyboard.toPref().takeIf { map.has(it) }?.let { map.getInt(it) }
        }
        if (backup[Settings.PREF_SHARE_LEARNED_WORDS] != false) return LearnedStores.SHARED
        return KeyboardProfiles.anyIdIn(backup, keyboard) ?: LearnedStores.SHARED
    }
}

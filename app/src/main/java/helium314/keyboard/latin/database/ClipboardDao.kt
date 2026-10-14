// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log

/*
 possible extension for later: allow non-text
 setting whether to allow it at all (because it could be slow with large files)
 separate retention time setting
 add mime type column
 add file name column
 add hash column (sha 256) for quick unique check (check full content on hash conflict)
 more sophisticated content loading: some getContent that reads the file, with cache
 async file reads and writes
 caches should be dropped on low memory
 */

/** Class providing cached access to the clipboard table */
// currently we should not need to worry about synchronizing access (though maybe we could addClip in a coroutine, then it might be relevant)
class ClipboardDao private constructor(private val db: Database) {
    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
        /** Everything may have changed (a restore's clips, committed or rolled back): redraw all. On the main thread
         *  (owner 2026-10-13, 20261013_L4). */
        fun onClipsReloaded()
    }

    var listener: Listener? = null

    // we clean up old clips when a new clip is added, but not too frequently
    private var lastClearOldClips = 0L

    // cache is loaded at start and never dropped
    private val cache = mutableListOf<ClipboardHistoryEntry>().apply {
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED, $COLUMN_TIMESTAMP DESC" // was only relevant in the initial approach of using a cursor instead of a cache
        ).use {
            while (it.moveToNext()) {
                add(ClipboardHistoryEntry(it.getLong(0), it.getLong(1), it.getInt(2) != 0, it.getString(3)))
            }
        }
        sort()
    }

    fun addClip(timestamp: Long, pinned: Boolean, text: String) = addClip(timestamp, pinned, text, restoring = false)

    // [restoring]: a failed insert throws (a restore's refill, rolled back as a whole) instead of going unnoticed, and the
    // view isn't told clip by clip (owner 2026-10-13, 20261013_L4: once, after the transaction)
    private fun addClip(timestamp: Long, pinned: Boolean, text: String, restoring: Boolean) {
        val existingIndex = cache.indexOfFirst { it.text == text }
        if (existingIndex >= 0 && cache[existingIndex].timeStamp == timestamp)
            return // nothing to do
        if (existingIndex >= 0) {
            updateTimestampAt(existingIndex, timestamp, notify = !restoring)
            return
        }
        insertNewEntry(timestamp, pinned, text, trim = !restoring, strict = restoring, notify = !restoring)
    }

    /**
     * The device's clips replaced by a backup's [clips] (time, pinned, text; a full restore), the clear and every add in
     * one transaction, as [mergeClips] (owner 2026-10-13, 20261013_L1): an add that fails rolls it all back, the cache
     * is read again, and the device's clips are really kept (before, they were cleared first and gone). The view is told
     * once, after it ([notifyReloaded]).
     */
    fun replaceClips(clips: List<Triple<Long, Boolean, String>>) {
        val w = db.writableDatabase
        w.beginTransaction()
        var done = false
        try {
            cache.clear()
            w.delete(TABLE, null, null)
            for ((timestamp, pinned, text) in clips) addClip(timestamp, pinned, text, restoring = true)
            w.setTransactionSuccessful()
            done = true
        } finally {
            w.endTransaction()
            if (!done) reloadCache()
            notifyReloaded()
        }
    }

    /** The view, if shown, redraws everything, on the main thread: after a restore's transaction, committed or rolled
     *  back (owner 2026-10-13, 20261013_L4: the restore runs on a background thread and told the view clip by clip, and a
     *  rollback's reload not at all). */
    private fun notifyReloaded() {
        mainHandler.post { listener?.onClipsReloaded() }
    }

    /**
     * A clip of a backup merged in (a partial restore, owner 2026-10-12: "yes, merge clipboard"): one with the same text
     * is kept once, pinned if either is, with the newer time; a new one is added. Nothing is dropped here: the history
     * size is applied once all are in ([keepNewestUnpinned]). The view isn't told ([mergeClips] does, once). An add that
     * fails throws, so [mergeClips] rolls them all back (round-5 review 20261013_F1, as the full restore's, L1: the
     * insert's error went unnoticed, and the others came in as "restored").
     */
    private fun mergeClip(timestamp: Long, pinned: Boolean, text: String) {
        val existing = cache.firstOrNull { it.text == text }
        if (existing == null) {
            insertNewEntry(timestamp, pinned, text, trim = false, strict = true, notify = false)
            return
        }
        val newPinned = existing.isPinned || pinned
        val newTime = maxOf(existing.timeStamp, timestamp)
        if (newPinned == existing.isPinned && newTime == existing.timeStamp) return
        existing.isPinned = newPinned
        existing.timeStamp = newTime
        cache.sort()
        val cv = ContentValues(2)
        cv.put(COLUMN_PINNED, newPinned)
        cv.put(COLUMN_TIMESTAMP, newTime)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${existing.id}", null)
    }

    /**
     * A backup's [clips] (time, pinned, text) merged in ([mergeClip] each), then the history [size] applied
     * ([keepNewestUnpinned]), all in one transaction (review L3.6): a merge that fails partway leaves the clips as they
     * were, and the cache is read again. The view is told once, after it ([notifyReloaded]; owner 2026-10-13,
     * 20261013_L4: before, clip by clip from the restore's thread, and not of a rollback).
     */
    fun mergeClips(clips: List<Triple<Long, Boolean, String>>, size: Int) {
        val w = db.writableDatabase
        w.beginTransaction()
        var done = false
        try {
            for ((timestamp, pinned, text) in clips) mergeClip(timestamp, pinned, text)
            keepNewestUnpinned(size)
            w.setTransactionSuccessful()
            done = true
        } finally {
            w.endTransaction()
            if (!done) reloadCache()
            notifyReloaded()
        }
    }

    /** Keeps the newest [size] unpinned clips, pins all (as [clearOldClips], right away and with the size given); also
     *  while the clipboard is shown (review L3.6: it was skipped then); the view isn't told ([mergeClips] does, once). */
    private fun keepNewestUnpinned(size: Int) {
        val unpinned = cache.filter { !it.isPinned }.sortedByDescending { it.timeStamp }
        if (unpinned.size <= size) return
        val old = unpinned.drop(size)
        cache.removeAll(old.toSet())
        db.writableDatabase.delete(TABLE, "$COLUMN_ID IN (${old.joinToString { it.id.toString() }})", null)
    }

    /** The cache read again from the table (after a failed merge or refill). */
    private fun reloadCache() {
        cache.clear()
        cache.addAll(readTable())
    }

    private fun readTable(): List<ClipboardHistoryEntry> = mutableListOf<ClipboardHistoryEntry>().apply {
        db.readableDatabase.query(TABLE, arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT),
            null, null, null, null, "$COLUMN_PINNED, $COLUMN_TIMESTAMP DESC").use {
            while (it.moveToNext()) add(ClipboardHistoryEntry(it.getLong(0), it.getLong(1), it.getInt(2) != 0, it.getString(3)))
        }
        sort()
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String, trim: Boolean = true, strict: Boolean = false,
                               notify: Boolean = true) {
        val cv = ContentValues(3)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        cv.put(COLUMN_PINNED, pinned)
        cv.put(COLUMN_TEXT, text)
        val rowId = if (strict) db.writableDatabase.insertOrThrow(TABLE, null, cv) else db.writableDatabase.insert(TABLE, null, cv)

        val entry = ClipboardHistoryEntry(rowId, timestamp, pinned, text)
        cache.add(entry)
        cache.sort()
        if (notify) listener?.onClipInserted(cache.indexOf(entry))
        if (trim) clearOldClips()
    }

    private fun updateTimestampAt(index: Int, timestamp: Long, notify: Boolean = true) {
        val entry = cache[index]
        entry.timeStamp = timestamp
        cache.sort()
        if (notify) listener?.onClipMoved(index, cache.indexOf(entry))
        val cv = ContentValues(1)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    fun isPinned(index: Int) = cache[index].isPinned

    fun getAt(index: Int) = cache[index]

    fun get(id: Long) = cache.first { it.id == id }

    fun count() = cache.size

    fun sort() = cache.sort()

    fun togglePinned(id: Long) {
        val entry = cache.first { it.id == id }
        entry.isPinned = !entry.isPinned
        entry.timeStamp = System.currentTimeMillis()
        if (listener != null) {
            val oldPos = cache.indexOf(entry)
            cache.sort()
            val newPos = cache.indexOf(entry)
            listener?.onClipMoved(oldPos, newPos)
        } else {
            cache.sort()
        }
        val cv = ContentValues(2)
        cv.put(COLUMN_PINNED, entry.isPinned)
        cv.put(COLUMN_TIMESTAMP, entry.timeStamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    // RecyclerView initiates this, so we don't call listener (or we'll get an IndexOutOfRangeException from RecyclerView)
    fun deleteClipAt(index: Int) {
        val entry = cache[index]
        cache.remove(entry)
        db.writableDatabase.delete(TABLE, "$COLUMN_ID = ${entry.id}", null)
    }

    /** Keeps the newest [Settings.PREF_CLIPBOARD_HISTORY_SIZE] unpinned entries; nothing expires by age. */
    fun clearOldClips(now: Boolean = false) {
        if (listener != null)
            return // never clear when clipboard is visible
        if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5 * 1000)
            return

        lastClearOldClips = SystemClock.elapsedRealtime()
        val size = Settings.getValues()?.mClipboardHistorySize ?: return
        val unpinned = cache.filter { !it.isPinned }.sortedByDescending { it.timeStamp }
        if (unpinned.size <= size) return
        val old = unpinned.drop(size)
        cache.removeAll(old.toSet())
        db.writableDatabase.delete(TABLE, "$COLUMN_ID IN (${old.joinToString { it.id.toString() }})", null)
    }

    fun clearNonPinned() {
        if (listener != null) {
            val indicesToRemove = mutableListOf<Int>()
            cache.forEachIndexed { idx, clip ->
                if (!clip.isPinned)
                    indicesToRemove.add(idx)
            }
            if (indicesToRemove.isEmpty())
                return // nothing to remove
            cache.removeAll { !it.isPinned }
            listener?.onClipsRemoved(indicesToRemove[0], indicesToRemove.size)
        } else if (!cache.removeAll { !it.isPinned }) {
            return // no listener, nothing to remove
        }
        db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
    }

    fun clear() {
        if (count() == 0) return
        cache.clear()
        listener?.onClipsRemoved(0, count())
        db.writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val TAG = "ClipboardDao"

        private const val TABLE = "CLIPBOARD"
        // it's possible timestamp is not unique, so we use a separate ID
        // ID is generated and returned on insert, see https://sqlite.org/rowidtable.html
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT" // we could enforce unique text, but that's only necessary if we can drop the cache (later)
        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT
            )
        """

        private var instance: ClipboardDao? = null
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Returns the instance or creates a new one. Returns null if instance can't be created (e.g. no access to db due to device being locked) */
        fun getInstance(context: Context): ClipboardDao? {
            if (instance == null)
                try {
                    instance = ClipboardDao(Database.getInstance(context))
                } catch (e: Throwable) {
                    Log.e(TAG, "can't create ClipboardDao", e)
                }
            return instance
        }
    }
}

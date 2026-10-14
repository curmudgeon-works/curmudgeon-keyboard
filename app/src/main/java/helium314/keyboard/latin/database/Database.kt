// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import helium314.keyboard.latin.utils.Log
import java.io.File

class Database private constructor(context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ClipboardDao.CREATE_TABLE)
        onUpgrade(db, 0, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // version 2 added upstream's gesture data table (its data gathering study, removed in 0.1.004):
        // no longer created; an existing one stays unused
    }

    companion object {
        private val TAG = Database::class.java.simpleName
        private const val VERSION = 2
        const val NAME = "heliboard.db"
        private var instance: Database? = null
        fun getInstance(context: Context): Database {
            if (instance == null)
                instance = Database(context)
            return instance!!
        }

        /** Everything written so far folded into the database file itself (its write-ahead log checkpointed), so a copy
         *  of the file alone holds every row: the backup copies the file (review 2026-10-12 L2: rows still in the log
         *  were missing from it). Nothing when there is no database yet. */
        fun checkpoint(context: Context) {
            if (!context.getDatabasePath(NAME).exists()) return
            getInstance(context).writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        }

        /** The clips of the database [file] replace the phone's (a full restore), or with [mergeKeeping] (the history
         *  size) are merged into them (a partial restore, owner 2026-10-12: backup's clips added, exact duplicates
         *  once, both sides' pins kept, newest first, the oldest unpinned beyond the size dropped).
         *  The backup's database is read as it is, its version ignored and never upgraded (review 2026-10-12 M8: one
         *  from a newer build, or a damaged one, threw here after the full restore's wipe and stopped it): any problem
         *  with it skips the clipboard and keeps the device's as it is (owner 2026-10-12 "makes senes to do B": no
         *  clipboard problem may break a restore). [file] is deleted either way.
         *  @return false when the backup's clips were skipped, true when restored (or there were none). Never throws. */
        fun copyFromDb(file: File, context: Context, mergeKeeping: Int? = null): Boolean {
            if (!file.exists())
                return true
            try {
                // all of the backup's clips read first: the device's are cleared only once they're in hand
                val clips = readClips(file) ?: return false
                val clipDao = ClipboardDao.getInstance(context) // insert to dao because of cache
                if (clipDao == null) {
                    Log.e(TAG, "can't transfer clipboard data because ClipboardDao is null")
                    return false
                }
                if (mergeKeeping == null) {
                    clipDao.clear()
                    clips.forEach { clipDao.addClip(it.first, it.second, it.third) }
                } else clipDao.mergeClips(clips, mergeKeeping) // (one transaction, the size applied after, L3.6)
                // (upstream also copied its gesture-data table here: not in this app, and fresh installs don't have it)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "clipboard not restored", t)
                return false
            } finally {
                file.delete()
            }
        }

        /** The clips (time, pinned, text) of the database [file], read-only and whatever its version (the columns every
         *  version so far has); null when it can't be read (damaged, not a database, no clipboard table). */
        private fun readClips(file: File): List<Triple<Long, Boolean, String>>? = try {
            // (an error handler that does nothing: Android's default one deletes a damaged file; no localized
            // collators: nothing may be written to the backup's database)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                DatabaseErrorHandler { }).use { db ->
                db.rawQuery("SELECT TIMESTAMP, PINNED, TEXT FROM CLIPBOARD", null).use {
                    val clips = ArrayList<Triple<Long, Boolean, String>>()
                    while (it.moveToNext()) {
                        val text = it.getString(2) ?: continue
                        clips.add(Triple(it.getLong(0), it.getInt(1) != 0, text))
                    }
                    clips
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "can't read the backup's clipboard database", t)
            null
        }
    }
}

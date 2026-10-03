// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
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

        // needs to be in sync with db version
        fun copyFromDb(file: File, context: Context) {
            if (!file.exists())
                return
            val otherDb = Database(context, file.name) // this upgrades the DB if necessary
            val clipDao = ClipboardDao.getInstance(context) // insert to dao because of cache
            if (clipDao == null) {
                Log.e(TAG, "can't transfer clipboard data because ClipboardDao is null")
            } else {
                otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT FROM CLIPBOARD", null)
                    .use {
                        clipDao.clear()
                        while (it.moveToNext())
                            clipDao.addClip(it.getLong(0), it.getInt(1) != 0, it.getString(2))
                    }
            }
            // (upstream also copied its gesture-data table here: not in this app, and fresh installs don't have it)
            otherDb.close()
            file.delete()
        }
    }
}

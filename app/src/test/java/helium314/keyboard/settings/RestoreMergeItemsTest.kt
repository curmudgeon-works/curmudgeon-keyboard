// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import android.provider.UserDictionary
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.FakeLearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.poolsJson
import helium314.keyboard.randomIds
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.dictionaryClashesFrom
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.restoreSummary
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A partial restore's learned words, dictionaries, personal dictionary words and clipboard as the owner decided them
 * (2026-10-12; the dialog's rows for them are gone since, all always come, owner 2026-10-12): learned words take
 * the higher count (DM18), the Learned words row brings the personal dictionary words too (the "Custom words" row
 * dropped), Dictionaries: same nothing / new comes in / differs: the backup's selected, the device's kept aside as
 * "…_prev"; other languages' new dictionaries come in silently; the clipboard merges.
 * The device: English (k1). The backup: French (k6), a new keyboard here.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreMergeItemsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private val io = BodyStoreIo()
    private val zip by lazy { File(ctx.cacheDir, "merge-items-${System.nanoTime()}.zip") }
    private lateinit var en: SettingsSubtype
    private lateinit var frB: SettingsSubtype
    private fun dictDir(tag: String) = File(ce, "dicts${File.separator}$tag")
    private fun dict(tag: String, name: String) = File(dictDir(tag), name)
    private fun selected(tag: String) = real.getString("dict_selected_${tag}_main", null)

    private fun resetClipboard() {
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        Database::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Before fun setUp() {
        resetClipboard()
        deleteLearnedFiles(ce)
        File(ce, "dicts").deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
        en = available("en-US").withId(1)
        frB = available("fr").withId(6)
        useKeyboards(ctx, real, en)
    }

    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        File(ce, "dicts").deleteRecursively()
        zip.delete()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        resetClipboard()
        randomIds()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The backup: [keyboards] listed, [prefs] on top, [entries] (zip path -> bytes). */
    private fun backup(keyboards: List<SettingsSubtype>, prefs: Map<String?, Any?> = emptyMap(), entries: Map<String, ByteArray> = emptyMap()) {
        val list = SubtypeSettings.createPrefSubtypes(keyboards)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                Settings.PREF_SELECTED_SUBTYPE to (keyboards.firstOrNull()?.toPref() ?: ""), KeyboardIdUpgrade.DONE to true,
                KeyboardProfiles.NO_COMMON_SET_DONE to true, LearnedKeyboards.PREF_DONE to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) + prefs, z)
            z.closeEntry()
            for ((path, bytes) in entries) { z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
        }
    }

    private fun restoreDictionaries(ticked: Boolean) =
        restoreChosenFrom(ctx, zip, listOf(frB), settings = false, learnedWords = false, io = io, dictionaries = ticked)

    // ---- 4. learned words: the higher count (owner 2026-10-11 DM18) ----

    // fail before: mergeAdding gave 2 + 3 = 5 the first time and 8 the second
    @Test fun `learned words merge by the higher count, so restoring twice changes nothing`() {
        val c = available("fr").withId(3)
        useKeyboards(ctx, real, c)
        real.edit().putString(LearnedKeyboards.PREF_POOLS, poolsJson(mapOf(c to 3))).putInt(LearnedKeyboards.PREF_POOL_NEXT, 4)
            .putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
        io.store(ce, "Latn", 3, word("shared", 2, 100), word("mine", 4, 100))
        val store = LearnedStores.storeName("Latn", 1) + LearnedStores.DICT_EXTENSION
        backup(listOf(c), mapOf(LearnedKeyboards.PREF_POOLS to poolsJson(mapOf(c to 1)), LearnedKeyboards.PREF_POOL_NEXT to 2),
            mapOf("$store/$store.body" to (FakeLearnedStoreIo.format(word("shared", 3, 200)) + "\n" +
                FakeLearnedStoreIo.format(word("mine", 1, 50)) + "\n").toByteArray()))
        repeat(2) { round ->
            restoreChosenFrom(ctx, zip, listOf(c), settings = false, learnedWords = true, io = io,
                decisions = mapOf(c to RestoreDecision.Merge(c)))
            val words = io.read(ce, "Latn", 3)!!
            assertEquals(3 to 200, words["shared"], "round ${round + 1}: the higher count, the latest use")
            assertEquals(4 to 100, words["mine"], "round ${round + 1}: the device's higher count stays")
        }
    }

    // ---- 4. the Learned words row brings the personal dictionary words (the Custom words row dropped) ----

    @Test fun `the Learned words row brings the backup's personal dictionary words, adding only`() {
        val provider = Robolectric.setupContentProvider(FakeUserDictionary::class.java, UserDictionary.AUTHORITY)
        provider.rows += "yaar|"
        backup(listOf(frB), entries = mapOf("personal_dictionary.json" to
            """[{"word":"Meera","frequency":"200","locale":"en_US"},{"word":"yaar","frequency":"250"}]""".toByteArray()))
        restoreChosenFrom(ctx, zip, listOf(frB), settings = false, learnedWords = true, io = io, personal = true)
        assertEquals(listOf("yaar|", "Meera|en_US"), provider.rows)
    }

    // ---- 2. Dictionaries of the restored keyboards' languages ----

    // fail before: the backup's file was copied over the device's main_user.dict, which was lost
    @Test fun `a dictionary that differs - the backup's comes in and is selected, the device's is kept aside as main_prev`() {
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("device")
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray()))
        restoreDictionaries(ticked = true)
        assertEquals("backup", dict("fr", "main_user.dict").readText())
        assertEquals("device", dict("fr", "main_prev_user.dict").readText(), "the device's, kept aside (still a .user.dict)")
        assertEquals("main_user.dict", selected("fr"))
    }

    @Test fun `the same dictionary anywhere on the device - nothing changes, and restoring twice adds no prev2`() {
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("device")
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray()))
        restoreDictionaries(ticked = true)
        restoreDictionaries(ticked = true)
        assertEquals(setOf("main_user.dict", "main_prev_user.dict"), dictDir("fr").list()!!.toSet())
        // the backup's content under another name on the device: nothing either
        File(ce, "dicts").deleteRecursively(); real.edit().remove("dict_selected_fr_main").commit()
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("device"); dict("fr", "main_prev_user.dict").writeText("backup")
        restoreDictionaries(ticked = true)
        assertEquals("device", dict("fr", "main_user.dict").readText())
        assertEquals(setOf("main_user.dict", "main_prev_user.dict"), dictDir("fr").list()!!.toSet())
        assertEquals(null, selected("fr"))
    }

    @Test fun `a second clash keeps the earlier prev and names the next one main_prev2`() {
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("now"); dict("fr", "main_prev_user.dict").writeText("older")
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray()))
        restoreDictionaries(ticked = true)
        assertEquals("backup", dict("fr", "main_user.dict").readText())
        assertEquals("older", dict("fr", "main_prev_user.dict").readText())
        assertEquals("now", dict("fr", "main_prev2_user.dict").readText())
    }

    @Test fun `a new dictionary comes in, and the backup's selected one is the one that comes`() {
        // (the backup had kept one aside and selected it: that one is the backup's dictionary)
        backup(listOf(frB), mapOf("dict_selected_fr_main" to "main_prev_user.dict"), mapOf(
            "dicts/fr/main_user.dict" to "not used".toByteArray(), "dicts/fr/main_prev_user.dict" to "used".toByteArray()))
        restoreDictionaries(ticked = true)
        assertEquals(setOf("main_user.dict"), dictDir("fr").list()!!.toSet())
        assertEquals("used", dict("fr", "main_user.dict").readText())
    }

    // (passes before too: unticked, the row brought none of these keyboards' dictionaries then either)
    @Test fun `unticked Dictionaries - none for the restored keyboards come in, the device's stays`() {
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("device")
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray(), "dicts/fr/emoji_user.dict" to "e".toByteArray()))
        restoreDictionaries(ticked = false)
        assertEquals(setOf("main_user.dict"), dictDir("fr").list()!!.toSet())
        assertEquals("device", dict("fr", "main_user.dict").readText())
        assertEquals(null, selected("fr"))
    }

    // ---- 3. other backup items come in silently ----

    // fail before: only the restored keyboards' languages' dictionaries came (with the row ticked)
    @Test fun `dictionaries of languages no restored keyboard has come in silently when new, a clash keeps the device's`() {
        dictDir("ko").mkdirs(); dict("ko", "main_user.dict").writeText("device ko")
        backup(listOf(frB), entries = mapOf("dicts/ur/main_user.dict" to "urdu".toByteArray(), "dicts/ko/main_user.dict" to "backup ko".toByteArray()))
        restoreDictionaries(ticked = false)
        assertEquals("urdu", dict("ur", "main_user.dict").readText())
        assertEquals(setOf("main_user.dict"), dictDir("ko").list()!!.toSet())
        assertEquals("device ko", dict("ko", "main_user.dict").readText())
        assertEquals(null, selected("ko"))
    }

    // (owner 2026-10-12: no Dictionaries row any more, dictionaries always come; the "Backup restored" dialog says so)
    @Test fun `the plan names the restored keyboards' languages whose dictionary clashes, and the summary says yours are kept aside`() {
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("device")
        dictDir("ko").mkdirs(); dict("ko", "main_user.dict").writeText("device ko")
        dictDir("de").mkdirs(); dict("de", "main_user.dict").writeText("same")
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray(),
            "dicts/ko/main_user.dict" to "backup ko".toByteArray(), "dicts/de/main_user.dict" to "same".toByteArray()))
        assertEquals(setOf("fr", "ko"), dictionaryClashesFrom(ctx, zip))
        assertEquals(listOf("fr"), restorePlanFrom(ctx, zip).dictionaryClashLanguages(listOf(frB)))
        assertEquals("Settings and dictionaries restored; your dictionaries kept as \"…prev\".",
            ctx.getString(helium314.keyboard.latin.R.string.restore_summary_settings))
    }

    // fail before (L3.1): the summary said "your dictionaries kept as …prev" whatever the answers were
    @Test fun `the summary's dictionaries line follows the answers - kept aside only when a clash was restored`() {
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("device")
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray()))
        val skipped = restoreChosenFrom(ctx, zip, listOf(frB), settings = false, learnedWords = false, io = io,
            dictionaries = true, decisions = mapOf(frB to RestoreDecision.Skip))
        assertEquals("device", dict("fr", "main_user.dict").readText())
        assertEquals(ctx.getString(helium314.keyboard.latin.R.string.restore_summary_settings_only), restoreSummary(ctx, skipped))
        val unticked = restoreChosenFrom(ctx, zip, emptyList(), settings = false, learnedWords = false, io = io, dictionaries = true)
        assertEquals(ctx.getString(helium314.keyboard.latin.R.string.restore_summary_settings_only), restoreSummary(ctx, unticked))
        val restored = restoreDictionaries(ticked = true)
        assertEquals("backup", dict("fr", "main_user.dict").readText())
        assertEquals(ctx.getString(helium314.keyboard.latin.R.string.restore_summary_settings), restoreSummary(ctx, restored).lines().last())
    }

    // fail before (L3.1): a new dictionary, nothing kept aside, still said "kept as …prev"
    @Test fun `the summary of a restore whose dictionary is new says nothing was kept aside`() {
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray()))
        val restored = restoreDictionaries(ticked = true)
        assertEquals("backup", dict("fr", "main_user.dict").readText())
        assertEquals(ctx.getString(helium314.keyboard.latin.R.string.restore_summary_settings_dictionaries), restoreSummary(ctx, restored).lines().last())
    }

    // fail before (L3.4): the device's dictionary couldn't be moved aside (here a folder holds the "…prev" name, so the
    // rename is skipped, as a failed renameTo leaves it) and the backup's was copied over it: the device's was lost
    @Test fun `a device dictionary that can't be moved aside is never written over`() {
        dictDir("fr").mkdirs(); dict("fr", "main_user.dict").writeText("device")
        dict("fr", "main_prev_user.dict").mkdirs() // (not a file: the plan names it the "…prev", the rename can't go there)
        backup(listOf(frB), entries = mapOf("dicts/fr/main_user.dict" to "backup".toByteArray()))
        val restored = restoreDictionaries(ticked = true)
        assertEquals("device", dict("fr", "main_user.dict").readText())
        assertEquals(null, selected("fr"))
        assertEquals(setOf("main_user.dict", "main_prev_user.dict"), dictDir("fr").list()!!.toSet()) // (nothing left beside)
        assertEquals(ctx.getString(helium314.keyboard.latin.R.string.restore_summary_settings_only), restoreSummary(ctx, restored).lines().last())
    }

    // fail before (L3.5): the other languages' new dictionaries came in silently with the clipboard
    @Test fun `a clipboard-only restore brings no dictionaries`() {
        val db = File(ctx.cacheDir, "clips-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use { d ->
            d.execSQL(ClipboardDao.CREATE_TABLE)
            d.version = 2
            d.insert("CLIPBOARD", null, ContentValues().apply { put("TIMESTAMP", 1L); put("PINNED", 0); put("TEXT", "clip") })
        }
        backup(listOf(frB), entries = mapOf(Database.NAME to db.readBytes(), "dicts/ur/main_user.dict" to "urdu".toByteArray(),
            "dicts/fr/main_user.dict" to "fr".toByteArray()))
        db.delete()
        restoreChosenFrom(ctx, zip, emptyList(), settings = false, learnedWords = false, io = io, dictionaries = true, clipboard = true)
        assertFalse(dict("ur", "main_user.dict").exists())
        assertFalse(dict("fr", "main_user.dict").exists())
        val dao = ClipboardDao.getInstance(ctx)!!
        assertEquals(listOf("clip"), (0 until dao.count()).map { dao.getAt(it).text })
    }

    // ---- Q1: after "Keep both", the restored copy of the keyboard being typed on is in use ----

    private fun inUse() = SubtypeSettings.getSelectedSubtype(real).let { with(SettingsSubtype) { it.toSettingsSubtype() } }

    // fail before: the device's English stayed in use
    @Test fun `Keep both - the restored copy of the keyboard being typed on is in use after the restore`() {
        val enB = available("en-US").withId(1) // (the backup's English: the device's keyboard itself)
        backup(listOf(frB, enB))
        val restored = restoreChosenFrom(ctx, zip, listOf(frB, enB), settings = false, learnedWords = false, io = io,
            decisions = mapOf(enB to RestoreDecision.Separate()))
        val copy = restored.onDevice.getValue(enB)
        assertFalse(copy.sameKeyboard(en), "the copy kept apart has its own id")
        assertEquals(copy.keyboardId, inUse().keyboardId)
    }

    // fail before: the device's English stayed in use
    @Test fun `Keep both with no copy of the keyboard being typed on - the first restored keyboard in the list is in use`() {
        val de = available("de-DE").withId(2)
        useKeyboards(ctx, real, en, de) // (English in use)
        val deB = available("de-DE").withId(8)
        backup(listOf(frB, deB))
        val restored = restoreChosenFrom(ctx, zip, listOf(frB, deB), settings = false, learnedWords = false, io = io,
            decisions = mapOf(deB to RestoreDecision.Separate()))
        assertEquals(restored.onDevice.getValue(frB).keyboardId, inUse().keyboardId) // (added first: first in the list)
    }

    // (passes before too: no keyboard kept apart, the keyboard in use stays)
    @Test fun `a restore without Keep both leaves the keyboard in use`() {
        backup(listOf(frB))
        restoreChosenFrom(ctx, zip, listOf(frB), settings = false, learnedWords = false, io = io)
        assertEquals(en.keyboardId, inUse().keyboardId)
    }

    // ---- 5. the clipboard merges (owner 2026-10-12: "yes, merge clipboard") ----

    // fail before: the device's clips were cleared and replaced by the backup's
    @Test fun `clipboard history merges - duplicates once, both pins kept, newest first, the oldest unpinned dropped`() {
        real.edit().putInt(Settings.PREF_CLIPBOARD_HISTORY_SIZE, 3).commit()
        val dao = ClipboardDao.getInstance(ctx)!!
        dao.addClip(10, false, "a"); dao.addClip(20, true, "b"); dao.addClip(30, false, "c")
        val db = File(ctx.cacheDir, "clips-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use { d ->
            d.execSQL(ClipboardDao.CREATE_TABLE)
            d.version = 2
            for ((t, pinned, text) in listOf(Triple(40L, true, "a"), Triple(5L, false, "d"), Triple(50L, false, "e"), Triple(1L, false, "f")))
                d.insert("CLIPBOARD", null, ContentValues().apply { put("TIMESTAMP", t); put("PINNED", if (pinned) 1 else 0); put("TEXT", text) })
        }
        backup(emptyList(), entries = mapOf(Database.NAME to db.readBytes()))
        db.delete()
        restoreChosenFrom(ctx, zip, emptyList(), settings = false, learnedWords = false, io = io, clipboard = true)
        val clips = (0 until dao.count()).map { dao.getAt(it) }.map { Triple(it.text, it.isPinned, it.timeStamp) }
        assertEquals(listOf(Triple("a", true, 40L), Triple("b", true, 20L), Triple("e", false, 50L), Triple("c", false, 30L),
            Triple("d", false, 5L)), clips)
        assertFalse(clips.any { it.first == "f" }, "the oldest unpinned beyond the size stays out")
    }

    // fail before (L3.6): with the clipboard view listening the size wasn't applied, and each clip was its own write
    @Test fun `clipboard merge - one transaction, and the size applied while the clipboard is shown`() {
        real.edit().putInt(Settings.PREF_CLIPBOARD_HISTORY_SIZE, 2).commit()
        val dao = ClipboardDao.getInstance(ctx)!!
        dao.addClip(10, false, "a")
        val inTransaction = mutableListOf<Boolean>()
        var removed = 0
        dao.listener = object : ClipboardDao.Listener {
            override fun onClipInserted(position: Int) { inTransaction += Database.getInstance(ctx).writableDatabase.inTransaction() }
            override fun onClipsRemoved(position: Int, count: Int) { removed += count }
            override fun onClipMoved(oldPosition: Int, newPosition: Int) { }
        }
        try {
            val db = File(ctx.cacheDir, "clips-${System.nanoTime()}.db")
            SQLiteDatabase.openOrCreateDatabase(db, null).use { d ->
                d.execSQL(ClipboardDao.CREATE_TABLE)
                d.version = 2
                for ((t, text) in listOf(20L to "b", 30L to "c"))
                    d.insert("CLIPBOARD", null, ContentValues().apply { put("TIMESTAMP", t); put("PINNED", 0); put("TEXT", text) })
            }
            backup(emptyList(), entries = mapOf(Database.NAME to db.readBytes()))
            db.delete()
            restoreChosenFrom(ctx, zip, emptyList(), settings = false, learnedWords = false, io = io, clipboard = true)
            assertEquals(listOf(true, true), inTransaction)
            assertEquals(1, removed)
            assertEquals(listOf("c", "b"), (0 until dao.count()).map { dao.getAt(it).text })
        } finally {
            dao.listener = null
        }
    }

    /** Android's personal dictionary, rows "word|locale"; inserts recorded. */
    class FakeUserDictionary : ContentProvider() {
        val rows = mutableListOf<String>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection).apply {
                for (row in rows) addRow(projection!!.map { when (it) {
                    UserDictionary.Words.WORD -> row.substringBefore("|")
                    UserDictionary.Words.LOCALE -> row.substringAfter("|").ifEmpty { null }
                    else -> null
                } })
            }
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            rows += values!!.getAsString(UserDictionary.Words.WORD) + "|" + (values.getAsString(UserDictionary.Words.LOCALE) ?: "")
            return null
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}

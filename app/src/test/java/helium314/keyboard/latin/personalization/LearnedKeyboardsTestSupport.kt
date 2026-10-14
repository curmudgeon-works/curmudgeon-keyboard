// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Locale

/**
 * The test stores of FakeLearnedStoreIo, in a file named as a backup takes a store's files (<store>/<store>.body), so
 * the same stores go through a backup zip. [beforeWrite] runs before each store written (its number, from 1).
 */
class BodyStoreIo : LearnedStoreIo {
    @Volatile var beforeWrite: (Int) -> Unit = {}
    @Volatile var writes = 0
    private fun file(dir: File) = File(dir, dir.name + ".body")
    override fun readFile(dir: File, locale: Locale): List<LearnedEntry>? =
        file(dir).takeIf { it.isFile }?.readLines()?.filter { it.isNotEmpty() }?.map { FakeLearnedStoreIo.parse(it) }
    override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>): Boolean {
        beforeWrite(++writes)
        dir.mkdirs()
        file(dir).writeText(entries.joinToString("") { FakeLearnedStoreIo.format(it) + "\n" })
        return true
    }
    override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? = null

    fun store(filesDir: File, script: String, pool: Int, vararg entries: LearnedEntry) =
        writeFile(LearnedStores.storeFile(filesDir, script, pool), Locale.ROOT, entries.toList())
    fun read(filesDir: File, script: String, pool: Int) =
        readFile(LearnedStores.storeFile(filesDir, script, pool), Locale.ROOT)?.associate { FakeLearnedStoreIo.key(it) to (it.count to it.time) }
    fun words(filesDir: File, script: String, pool: Int) = read(filesDir, script, pool).orEmpty().keys
}

/** The learned words' files of the app gone (stores, blacklists, work folders). */
fun deleteLearnedFiles(filesDir: File) {
    filesDir.listFiles()?.filter { it.name.startsWith(UserHistoryDictionary.NAME) || it.name.startsWith("learned_")
        || it.name == LearnedStores.BLACKLIST_DIR }?.forEach { it.deleteRecursively() }
}

fun writeList(filesDir: File, script: String, pool: Int, text: String) =
    LearnedStores.blacklistFile(filesDir, script, pool).apply { parentFile!!.mkdirs(); writeText(text) }

fun readList(filesDir: File, script: String, pool: Int) =
    LearnedStores.blacklistFile(filesDir, script, pool).takeIf { it.isFile }?.let { helium314.keyboard.latin.utils.RemovedWords.parseAll(it.readLines()) }

fun available(language: String): SettingsSubtype =
    SubtypeSettings.getAllAvailableSubtypes().map { it.toSettingsSubtype() }.first { it.locale == Locale.forLanguageTag(language) }

/** [keyboards] are the keyboard list, the first one selected. */
fun useKeyboards(ctx: Context, real: SharedPreferences, vararg keyboards: SettingsSubtype) {
    real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards.toList()))
        .putString(Settings.PREF_SELECTED_SUBTYPE, keyboards.first().toPref()).commit()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
}

fun profileIds(real: SharedPreferences, ids: Map<SettingsSubtype, Int>, next: Int) {
    real.edit().putString("keyboard_profile_ids", JSONObject(ids.mapKeys { it.key.toPref() }).toString())
        .putInt("keyboard_profile_next_id", next).commit()
}

/** The pools map as stored. */
fun storedPools(real: SharedPreferences): Map<String, Int> =
    JSONObject(real.getString(LearnedKeyboards.PREF_POOLS, null) ?: "{}").let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }

fun storedButton(real: SharedPreferences, pref: String): Set<String> =
    JSONArray(real.getString(pref, null) ?: "[]").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }

/** Runs the main looper until [done] (work posted from the background lands there), 10 s at most. */
fun idleUntil(done: () -> Boolean) {
    val until = System.currentTimeMillis() + 10_000
    while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
    shadowOf(Looper.getMainLooper()).idle()
}

/** Every file under [dir] (relative path -> contents). */
fun snapshot(dir: File): Map<String, String> =
    dir.walk().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

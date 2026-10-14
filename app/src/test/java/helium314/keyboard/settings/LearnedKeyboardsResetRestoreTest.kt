// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.countingIds
import helium314.keyboard.kid
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.idleUntil
import helium314.keyboard.latin.personalization.readList
import helium314.keyboard.latin.personalization.snapshot
import helium314.keyboard.latin.personalization.storedButton
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.personalization.writeList
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.poolsJson
import helium314.keyboard.randomIds
import helium314.keyboard.storedEnabled
import helium314.keyboard.withId
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.restoreFollowUp
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.preferences.ResetKeep
import helium314.keyboard.settings.preferences.startFactoryReset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every keyboard its own learned words (2026-10-10), through a factory reset (startFactoryReset, what the dialog calls)
 * and a restore (the restore dialog's functions): each keyboard's words stay its own, whatever happens to the pool numbers.
 * (owner 2026-10-11: keyboards have unique ids) Pools and buttons are keyed by keyboard id; a reset of the keyboards
 * leaves a new default keyboard (a new id); an old backup's keyboards get fresh ids when it is read.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedKeyboardsResetRestoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private lateinit var de: File
    private val io = BodyStoreIo()
    private val zip by lazy { File(ctx.cacheDir, "backup-learned-${System.nanoTime()}.zip") }
    private val filesDirBefore = KeyboardProfiles.filesDir

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        de = File(ctx.cacheDir, "de_${System.nanoTime()}").apply { mkdirs() }
        KeyboardProfiles.filesDir = de
        assertNotEquals(ce.canonicalPath, de.canonicalPath)
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
    }

    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.filesDir = filesDirBefore
        zip.delete()
        randomIds()
    }

    /** The phone after the update: [pools] each keyboard's (keyboards with ids), words "<pool>" in each, both buttons on
     *  for all. */
    private fun updatedPhone(pools: Map<SettingsSubtype, Int>, next: Int) {
        real.edit().putString(LearnedKeyboards.PREF_POOLS, poolsJson(pools))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, next).putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, JSONArray(pools.keys.map { it.keyboardId!! }).toString())
            .putString(LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, JSONArray(pools.keys.map { it.keyboardId!! }).toString())
            .commit()
        for (pool in pools.values) {
            io.store(ce, "Latn", pool, word("phone$pool", 2, 100))
            writeList(ce, "Latn", pool, "bad$pool\n")
        }
    }

    // (owner 2026-10-11: ticks mean keep; all unticked = full reset)
    private fun reset(keepKeyboards: Boolean, keepWords: Boolean) {
        var done = false
        startFactoryReset(ctx, ResetKeep(keyboards = keepKeyboards, words = keepWords, clipboard = true, own = true)) { done = true }
        idleUntil { done }
        assertTrue(done, "the reset never ran")
    }

    /** The keyboard the list falls back to (what a reset of the keyboards leaves). */
    private fun defaultKeyboard(): SettingsSubtype {
        real.edit().remove(Settings.PREF_ENABLED_SUBTYPES).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        return SubtypeSettings.getEnabledSubtypes(true).first().toSettingsSubtype()
    }

    // (owner 2026-10-11: "b. merge into default keyboard's words, retaining all script-wise lists"; before, the removed
    // keyboards' words were deleted)
    // (owner 2026-10-11: keyboards have unique ids) the default keyboard after the reset is a new keyboard (a new id): the
    // old default keyboard's words are merged into its new pool too, as the others'
    @Test fun `a reset of the keyboards keeping the words - the removed keyboards' words and lists join the default keyboard's, every script`() {
        val kept = defaultKeyboard()
        val other = available("de-DE").withId(2)
        val third = available("fr").withId(3)
        useKeyboards(ctx, real, kept, other, third)
        updatedPhone(mapOf(kept to 5, other to 6, third to 7), next = 9)
        // a second script on a removed keyboard, and a word on two of them (highest count, most strikes win)
        io.store(ce, "Cyrl", 6, word("привет", 4, 300))
        writeList(ce, "Cyrl", 6, "плохо\n")
        io.store(ce, "Latn", 7, word("phone7", 2, 100), word("phone6", 9, 500))
        writeList(ce, "Latn", 7, "bad7\nbad6\t3\n")

        reset(keepKeyboards = false, keepWords = true)

        val def = storedEnabled(real).single()
        assertNotEquals(kept.keyboardId, def.keyboardId, "the default keyboard after a reset is a new one")
        val pool = storedPools(real).getValue(def.keyboardId!!)
        assertTrue(pool >= 9, "a fresh pool: $pool")
        assertEquals(setOf("phone5", "phone6", "phone7"), io.words(ce, "Latn", pool))
        assertEquals(9, io.read(ce, "Latn", pool)!!.getValue("phone6").first, "the highest count")
        assertEquals(setOf("привет"), io.words(ce, "Cyrl", pool))
        val list = readList(ce, "Latn", pool)!!
        assertEquals(setOf("bad5", "bad6", "bad7"), list.keys)
        assertEquals(3, list.getValue("bad6").strikes, "the most strikes: $list")
        assertEquals(setOf("плохо"), readList(ce, "Cyrl", pool)?.keys)
        for (gone in listOf(5, 6, 7)) {
            assertFalse(LearnedStores.storeFile(ce, "Latn", gone).exists(), "pool $gone's words are left")
            assertFalse(File(ce, "${LearnedStores.BLACKLIST_DIR}/k$gone").exists(), "pool $gone's blacklists are left")
        }
        assertFalse(LearnedStores.storeFile(ce, "Cyrl", 6).exists())
        assertEquals(mapOf(def.keyboardId!! to pool), storedPools(real))
        for (pref in LearnedKeyboards.buttonPrefs) assertFalse(real.contains(pref), pref)
        assertTrue(real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0) > pool)
        assertTrue(LearnedKeyboards.isDone(real))
    }

    @Test fun `a reset of the keyboards keeping the words, the default keyboard new - it gets a pool with all their words`() {
        val a = available("de-DE").withId(1); val b = available("fr").withId(2)
        useKeyboards(ctx, real, a, b)
        updatedPhone(mapOf(a to 5, b to 6), next = 9)

        reset(keepKeyboards = false, keepWords = true)

        val def = storedEnabled(real).single()
        val pool = storedPools(real).getValue(def.keyboardId!!)
        assertTrue(pool >= 9, "a fresh pool: $pool")
        assertEquals(setOf("phone5", "phone6"), io.words(ce, "Latn", pool))
        assertEquals(setOf("bad5", "bad6"), readList(ce, "Latn", pool)?.keys)
        assertEquals(setOf(pool), LearnedStores.keyboardPoolsOnDisk(ce), "left: ${LearnedStores.keyboardPoolsOnDisk(ce)}")
    }

    @Test fun `nothing kept - every keyboard's words and lists go`() {
        val kept = defaultKeyboard()
        val other = available("de-DE").withId(2)
        useKeyboards(ctx, real, kept, other)
        updatedPhone(mapOf(kept to 5, other to 6), next = 9)

        reset(keepKeyboards = false, keepWords = false)

        assertTrue(LearnedStores.keyboardPoolsOnDisk(ce).isEmpty(), "left: ${LearnedStores.keyboardPoolsOnDisk(ce)}")
        assertFalse(File(ce, LearnedStores.BLACKLIST_DIR).exists(), "blacklists left: ${snapshot(ce).keys}")
        assertFalse(kid(2) in storedPools(real))
        assertFalse(kept.keyboardId!! in storedPools(real))
    }

    @Test fun `keyboards and words kept - each keyboard keeps its own, nothing merged`() {
        val a = available("en-US").withId(1); val b = available("de-DE").withId(2)
        useKeyboards(ctx, real, a, b)
        updatedPhone(mapOf(a to 5, b to 6), next = 9)

        reset(keepKeyboards = true, keepWords = true)

        assertEquals(setOf("phone5"), io.words(ce, "Latn", 5))
        assertEquals(setOf("phone6"), io.words(ce, "Latn", 6))
        assertEquals(setOf("bad6"), readList(ce, "Latn", 6)?.keys)
        assertEquals(mapOf(kid(1) to 5, kid(2) to 6), storedPools(real))
        assertEquals(listOf(kid(1), kid(2)), storedEnabled(real).map { it.keyboardId }, "the keyboards keep their ids")
    }

    @Test fun `a reset of the learned words - every keyboard's words and blacklists go, the pools stay`() {
        val a = available("en-US").withId(1); val b = available("de-DE").withId(2)
        useKeyboards(ctx, real, a, b)
        updatedPhone(mapOf(a to 5, b to 6), next = 9)
        writeList(ce, "Latn", 0, "old\n")

        reset(keepKeyboards = true, keepWords = false)

        assertTrue(LearnedStores.keyboardPoolsOnDisk(ce).isEmpty(), "left: ${LearnedStores.keyboardPoolsOnDisk(ce)}")
        assertFalse(LearnedStores.storeFile(ce, "Latn", 5).exists())
        assertFalse(File(ce, LearnedStores.BLACKLIST_DIR).exists(), "blacklists left: ${snapshot(ce).keys}")
        assertEquals(mapOf(kid(1) to 5, kid(2) to 6), storedPools(real))
        assertEquals(9, real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0))
    }

    private fun backup(prefs: Map<String?, Any?>, stores: Map<Int, String>, lists: Map<Int, String> = emptyMap()) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json")); settingsToJsonStream(prefs, z); z.closeEntry()
            for ((pool, w) in stores) {
                val store = LearnedStores.storeName("Latn", pool) + LearnedStores.DICT_EXTENSION
                z.putNextEntry(ZipEntry("$store/$store.body"))
                z.write((helium314.keyboard.latin.personalization.FakeLearnedStoreIo.format(word(w, 3, 200)) + "\n").toByteArray())
                z.closeEntry()
            }
            for ((pool, w) in lists) {
                z.putNextEntry(ZipEntry(LearnedStores.blacklistFile(File(""), "Latn", pool).path.removePrefix(File.separator)))
                z.write("$w\n".toByteArray()); z.closeEntry()
            }
        }
    }

    // (owner 2026-10-11: keyboards have unique ids) the backup's keyboards are the phone's (same ids), their pools other
    // numbers; the old profile id map is gone
    @Test fun `a restore onto a phone with other pool numbers - each keyboard keeps its own words and gets the backup's`() {
        val c = available("fr").withId(3); val d = available("es").withId(4)
        useKeyboards(ctx, real, c, d)
        updatedPhone(mapOf(c to 3, d to 4), next = 5)
        // the backup: the same keyboards, other pools
        backup(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c, d)),
            Settings.PREF_SELECTED_SUBTYPE to c.toPref(), LearnedKeyboards.PREF_DONE to true, KeyboardIdUpgrade.DONE to true,
            LearnedKeyboards.PREF_POOLS to poolsJson(mapOf(c to 1, d to 2)),
            LearnedKeyboards.PREF_POOL_NEXT to 3),
            stores = mapOf(1 to "backc", 2 to "backd"))

        // (both on the phone: answered "merge into" each, as the dialog asks; (owner 2026-10-11: a keyboard restore never wipes; only the full restore does))
        restoreChosenFrom(ctx, zip, listOf(c, d), settings = true, learnedWords = true, io = io,
            decisions = mapOf(c to RestoreDecision.Merge(c), d to RestoreDecision.Merge(d)))

        assertEquals(mapOf(kid(3) to 3, kid(4) to 4), storedPools(real), "the phone's pools")
        assertEquals(5, real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0))
        assertEquals(setOf("phone3", "backc"), io.words(ce, "Latn", 3))
        assertEquals(setOf("phone4", "backd"), io.words(ce, "Latn", 4))
        assertFalse(LearnedStores.storeFile(ce, "Latn", 1).exists() || LearnedStores.storeFile(ce, "Latn", 2).exists(),
            "the backup's pool numbers written: ${LearnedStores.keyboardPoolsOnDisk(ce)}")
        // (the backup had no buttons on)
        for (pref in LearnedKeyboards.buttonPrefs) assertTrue(storedButton(real, pref).isEmpty(), pref)
    }

    // (owner 2026-10-11: keyboards have unique ids) an old backup's keyboards get fresh ids when it is read (counted here:
    // c k1000, d k1001); merged, c is the phone's keyboard (its id), d comes in with the backup's id
    @Test fun `an old backup that shared its words - each restored keyboard gets them, buttons on`() {
        val c0 = available("fr"); val d0 = available("es")
        val c = c0.withId(3)
        useKeyboards(ctx, real, c)
        updatedPhone(mapOf(c to 3), next = 4)
        backup(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c0, d0)),
            Settings.PREF_SELECTED_SUBTYPE to c0.toPref()), stores = mapOf(0 to "shared"), lists = mapOf(0 to "teh"))
        countingIds(1000)
        val cB = c0.withId(1000); val dB = d0.withId(1001) // (as read from the backup)

        // (c on the phone: answered "merge into" it, as the dialog asks; (owner 2026-10-11: a keyboard restore never wipes; only the full restore does))
        restoreChosenFrom(ctx, zip, listOf(cB, dB), settings = true, learnedWords = true, io = io,
            decisions = mapOf(cB to RestoreDecision.Merge(c)))

        val pools = storedPools(real)
        assertEquals(3, pools[kid(3)])
        val poolD = pools.getValue(kid(1001))
        assertNotEquals(0, poolD)
        assertFalse(kid(1000) in pools, "the merged keyboard got a pool of its own: $pools")
        assertEquals(setOf("phone3", "shared"), io.words(ce, "Latn", 3))
        assertEquals(setOf("shared"), io.words(ce, "Latn", poolD))
        assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", poolD)?.get("teh"))
        assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", 3)?.get("teh"))
        for (pref in LearnedKeyboards.buttonPrefs) assertEquals(setOf(kid(3), kid(1001)), storedButton(real, pref), pref)
    }

    // (owner 2026-10-11: keyboards have unique ids) the restored keyboards get fresh ids (the follow-up's upgrade, as the
    // dialog runs it after the restore); the pools are keyed by them, numbered from 1 again (the phone's files are gone)
    @Test fun `an old backup restored whole is updated as the phone was`() {
        val c0 = available("fr"); val d0 = available("es")
        val c = c0.withId(3)
        useKeyboards(ctx, real, c)
        updatedPhone(mapOf(c to 3), next = 4)
        backup(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(c0, d0)),
            Settings.PREF_SELECTED_SUBTYPE to c0.toPref(),
            KeyboardIdUpgrade.OLD_IDS to JSONObject(mapOf(c0.toPref() to 1, d0.toPref() to 2) as Map<*, *>).toString(), "keyboard_profile_next_id" to 3),
            stores = mapOf(0 to "shared"), lists = mapOf(0 to "teh"))

        restoreEverythingFrom(ctx, zip)
        restoreFollowUp(ctx)

        assertTrue(LearnedKeyboards.isDone(real))
        val ids = storedEnabled(real).map { it.keyboardId }
        assertTrue(ids.all { it != null } && ids.size == 2, "$ids")
        val pools = storedPools(real)
        assertEquals(ids.filterNotNull().toSet(), pools.keys)
        assertEquals(2, pools.values.toSet().size, "one pool each: $pools")
        for (pool in pools.values) {
            assertNotEquals(LearnedStores.SHARED, pool, "$pools")
            assertEquals(setOf("shared"), io.words(ce, "Latn", pool))
            assertEquals(RemovedWords.Entry(1), readList(ce, "Latn", pool)?.get("teh"))
        }
        for (pref in LearnedKeyboards.buttonPrefs) assertEquals(pools.keys, storedButton(real, pref), pref)
        assertNull(real.getString(LearnedKeyboards.PREF_PLAN, null))
    }
}

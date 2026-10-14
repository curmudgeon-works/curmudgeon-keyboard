// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.countingIds
import helium314.keyboard.kid
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.randomIds
import helium314.keyboard.withId
import helium314.keyboard.settings.screens.scriptRows
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every keyboard its own learned words (2026-10-10): the one-time update at app start (LearnedKeyboards.startUpdate, what
 * App.onCreate calls), and the pools following a keyboard's identity. CE: the app's files (credential storage), DE: the
 * device-protected folder, which must get nothing.
 * (owner 2026-10-11: keyboards have unique ids) The pools are keyed by keyboard id; with sharing off the ids' upgrade
 * (KeyboardIdUpgrade) does the update from the old set numbers; a change of a keyboard keeps its id, nothing moves.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedKeyboardsUpdateTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val ce: File get() = ctx.filesDir
    private lateinit var de: File
    private val io = BodyStoreIo()
    private val filesDirBefore = KeyboardProfiles.filesDir

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit() // (a phone of before: no flag, no pools)
        de = File(ctx.cacheDir, "de_${System.nanoTime()}").apply { mkdirs() }
        KeyboardProfiles.filesDir = de
        assertNotEquals(ce.canonicalPath, de.canonicalPath)
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
    }

    @After fun tearDown() {
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.filesDir = filesDirBefore
        randomIds()
    }

    private val a by lazy { available("en-US").withId(1) }
    private val b by lazy { available("de-DE").withId(2) }
    private val c by lazy { available("fr").withId(3) }

    private fun sharedWords() {
        io.store(ce, "Latn", 0, word("hai", 9, 100), after("ok", word = "hai", count = 4, time = 100), word("ok", 5, 90))
        io.store(ce, "Deva", 0, word("नमस्ते", 2, 50))
        writeList(ce, "Latn", 0, "teh\t2\n")
    }

    private fun runUpdate() {
        LearnedKeyboards.startUpdate(ctx)
        idleUntil { LearnedKeyboards.isDone(real) && !LearnedKeyboards.files.isLocked }
    }

    @Test fun `sharing on - each keyboard gets its own copy of the shared words and lists, both buttons on`() {
        useKeyboards(ctx, real, a, b, c)
        real.edit().putInt(LearnedKeyboards.PREF_POOL_NEXT, 4).commit()
        sharedWords()
        val shared = snapshot(ce)

        runUpdate()

        assertTrue(LearnedKeyboards.isDone(real))
        val pools = storedPools(real)
        assertEquals(setOf(kid(1), kid(2), kid(3)), pools.keys)
        assertEquals(3, pools.values.toSet().size, "one pool each: $pools")
        assertTrue(pools.values.all { it >= 4 }, "fresh numbers, from the next pool number: $pools")
        for (pool in pools.values) {
            assertEquals(io.read(ce, "Latn", 0), io.read(ce, "Latn", pool))
            assertEquals(io.read(ce, "Deva", 0), io.read(ce, "Deva", pool))
            assertEquals(mapOf("teh" to RemovedWords.Entry(2)), readList(ce, "Latn", pool))
        }
        for (pref in LearnedKeyboards.buttonPrefs) assertEquals(pools.keys, storedButton(real, pref), pref)
        assertFalse(real.contains(LearnedKeyboards.PREF_PLAN))
        assertTrue(real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0) > pools.values.max())
        // the shared ones as they were, nothing in the device-protected folder
        assertEquals(shared, snapshot(ce).filterKeys { it in shared.keys })
        assertTrue(snapshot(de).isEmpty(), "written to DE: ${snapshot(de).keys}")
        // and the keyboard learns into its own pool now
        assertEquals(pools.getValue(kid(1)), LearnedStores.currentPool)
        assertEquals(LearnedStores.blacklistFile(ce, "Latn", pools.getValue(kid(1))), RemovedWords.blacklist(ctx, "Latn").file)
    }

    // (owner 2026-10-11: keyboards have unique ids) the update no longer knows the set numbers: the ids' upgrade finishes
    // it from the old id map, each keyboard's pool its set number of before
    @Test fun `sharing off - every keyboard keeps its own pool, buttons off, nothing copied`() {
        // a phone of before: keyboards without ids, the old map definition -> set number (only the enabled list written:
        // a write of the added list loads the keyboards, whose net would give them ids first)
        val a0 = available("en-US")
        val b0 = available("de-DE")
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a0, b0)))
            .putString(Settings.PREF_SELECTED_SUBTYPE, a0.toPref())
            .putString(KeyboardIdUpgrade.OLD_IDS, org.json.JSONObject(mapOf(a0.toPref() to 5, b0.toPref() to 6) as Map<*, *>).toString())
            .putInt("keyboard_profile_next_id", 7)
            .putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).commit()
        sharedWords()
        io.store(ce, "Latn", 5, word("mine", 3, 50))
        writeList(ce, "Latn", 6, "bad\n")
        val files = snapshot(ce)
        countingIds(1) // (the enabled list first: a0 gets k1, b0 k2)

        assertTrue(KeyboardIdUpgrade.upgrade(real, null)) // (no pictures to rename)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertTrue(LearnedKeyboards.isDone(real), "the ids' upgrade did the update")
        runUpdate()

        assertEquals(mapOf(kid(1) to 5, kid(2) to 6), storedPools(real))
        for (pref in LearnedKeyboards.buttonPrefs) assertFalse(real.contains(pref))
        assertEquals(files, snapshot(ce), "a file was written")
        assertTrue(snapshot(de).isEmpty())
        LearnedStores.refresh(real)
        assertEquals(5, LearnedStores.currentPool)
        assertTrue(real.getInt(LearnedKeyboards.PREF_POOL_NEXT, 0) >= 7)
    }

    // (owner 2026-10-11: keyboards have unique ids) not done by the ids' upgrade (it ran with sharing on): the update has
    // no set numbers, each listed keyboard gets a new pool, nothing copied, buttons off
    @Test fun `sharing off, not done by the ids' upgrade - each keyboard a new pool, nothing copied`() {
        useKeyboards(ctx, real, a, b)
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).commit()
        sharedWords()
        val files = snapshot(ce)

        runUpdate()

        val pools = storedPools(real)
        assertEquals(setOf(kid(1), kid(2)), pools.keys)
        assertEquals(2, pools.values.toSet().size, "one pool each: $pools")
        assertFalse(LearnedStores.SHARED in pools.values)
        for (pref in LearnedKeyboards.buttonPrefs) assertFalse(real.contains(pref))
        assertEquals(files, snapshot(ce), "a file was written")
        assertFalse(real.contains(LearnedKeyboards.PREF_PLAN))
    }

    @Test fun `killed mid-copy - the old pool stays in use, the next start redoes it with the same plan`() {
        useKeyboards(ctx, real, a, b, c)
        // (one script: a store written per keyboard)
        io.store(ce, "Latn", 0, word("hai", 9, 100))
        writeList(ce, "Latn", 0, "teh\t2\n")
        var killed = false
        val third = io.writes + 3
        io.beforeWrite = { n -> if (n == third) { killed = true; throw RuntimeException("killed") } }

        LearnedKeyboards.startUpdate(ctx)
        idleUntil { killed && !LearnedKeyboards.files.isLocked }

        assertTrue(killed)
        assertFalse(LearnedKeyboards.isDone(real))
        val plan = real.getString(LearnedKeyboards.PREF_PLAN, null)
        assertNotNull(plan, "the plan is kept for the next start")
        // until it's done the keyboard keeps learning where it did: the shared pool
        LearnedStores.refresh(real)
        assertEquals(LearnedStores.SHARED, LearnedStores.currentPool)
        assertEquals(LearnedStores.blacklistFile(ce, "Latn", 0), RemovedWords.blacklist(ctx, "Latn").file)

        io.beforeWrite = {}
        runUpdate()
        val planned = org.json.JSONObject(plan).getJSONObject("pools").let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
        assertEquals(planned, storedPools(real), "another plan was made")
        for (pool in planned.values) assertEquals(io.read(ce, "Latn", 0), io.read(ce, "Latn", pool))
    }

    @Test fun `locked - the update waits for an unlocked start`() {
        useKeyboards(ctx, real, a, b)
        sharedWords()
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        LearnedKeyboards.startUpdate(ctx)
        Thread.sleep(300); idleUntil { true }
        assertFalse(LearnedKeyboards.isDone(real))
        assertFalse(real.contains(LearnedKeyboards.PREF_PLAN))
        assertTrue(LearnedStores.keyboardPoolsOnDisk(ce).isEmpty(), "a pool was written while locked")
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        runUpdate()
        assertTrue(LearnedKeyboards.isDone(real))
    }

    @Test fun `a keyboard added while the words are copied gets a copy too`() {
        useKeyboards(ctx, real, a, b)
        sharedWords()
        val first = io.writes + 1
        io.beforeWrite = { n ->
            if (n == first) real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b, c))).commit()
        }
        runUpdate()
        val pools = storedPools(real)
        assertEquals(setOf(kid(1), kid(2), kid(3)), pools.keys)
        assertEquals(io.read(ce, "Latn", 0), io.read(ce, "Latn", pools.getValue(kid(3))))
        assertEquals(mapOf("teh" to RemovedWords.Entry(2)), readList(ce, "Latn", pools.getValue(kid(3))))
        for (pref in LearnedKeyboards.buttonPrefs) assertTrue(kid(3) in storedButton(real, pref))
    }

    // (owner 2026-10-11: keyboards have unique ids) a changed keyboard keeps its id: its pool and buttons stay, nothing
    // moves (the record moves on each change, and the preview check, went with onKeyboardChanged)
    @Test fun `a keyboard keeps its words through a rename, a language edit and a layout change`() {
        useKeyboards(ctx, real, a, b)
        sharedWords()
        runUpdate()
        val pool = LearnedKeyboards.poolFor(real, a)
        val poolB = LearnedKeyboards.poolFor(real, b)
        val before = storedPools(real)
        val buttons = LearnedKeyboards.buttonPrefs.associateWith { storedButton(real, it) }
        // languages
        val a2 = a.with(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.SECONDARY_LOCALES, "de")
        SubtypeUtilsAdditional.changeAdditionalSubtype(a, a2, ctx)
        assertEquals(pool, LearnedKeyboards.poolOrNull(real, a2))
        // a layout of its own
        val a3 = a2.withLayout(LayoutType.MAIN, "qwertz")
        SubtypeUtilsAdditional.changeAdditionalSubtype(a2, a3, ctx)
        assertEquals(pool, LearnedKeyboards.poolOrNull(real, a3))
        // a rename, through the screens' path
        val a4 = a3.with(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_NAME, "Mine")
        SubtypeUtilsAdditional.changeAdditionalSubtype(a3, a4, ctx)
        assertEquals(kid(1), a4.keyboardId)
        assertEquals(pool, LearnedKeyboards.poolOrNull(real, a4))
        assertEquals(before, storedPools(real), "a record moved")
        for (pref in LearnedKeyboards.buttonPrefs) {
            assertEquals(buttons.getValue(pref), storedButton(real, pref), pref)
            assertTrue(kid(1) in storedButton(real, pref), pref)
        }
        assertEquals(poolB, LearnedKeyboards.poolOrNull(real, b))
        // deleted: the entry goes, a keyboard added again later starts with a pool of its own
        KeyboardProfiles.onKeyboardDeleted(real, b)
        assertFalse(kid(2) in storedPools(real))
        assertNotEquals(poolB, LearnedKeyboards.poolFor(real, b))
    }

    @Test fun `each keyboard's Learned & blacklisted words screen lists its own pool`() {
        useKeyboards(ctx, real, a, b)
        sharedWords()
        runUpdate()
        // (on the keyboard's own screen since 2026-10-10: one keyboard's lists each)
        assertEquals(listOf(LearnedKeyboards.poolFor(real, a)), scriptRows(ctx, a).map { it.pool }.distinct())
        assertEquals(listOf(LearnedKeyboards.poolFor(real, b)), scriptRows(ctx, b).map { it.pool }.distinct())
    }
}

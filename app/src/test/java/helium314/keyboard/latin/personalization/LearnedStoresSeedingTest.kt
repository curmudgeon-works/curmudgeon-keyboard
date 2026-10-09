// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Re-review 2026-10-07: a new keyboard's own learned words are copied off the main thread; the keyboard keeps the
 *  pool it had until the copy is done, then switches. */
@RunWith(RobolectricTestRunner::class)
class LearnedStoresSeedingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val dir get() = ctx.filesDir // the learned words' folder (as the app sets it up)
    private lateinit var de: File // the pictures' folder (KeyboardProfiles.filesDir): a different one, as on the phones
    private var filesDirBefore: File? = null
    private val runs = mutableListOf<Pair<Int, (Boolean) -> Unit>>() // the seeds asked for, with their callbacks

    private fun cleanUp() = dir.listFiles()?.filter { it.name.startsWith("UserHistoryDictionary") || it.name.startsWith("learned_") }
        ?.forEach { it.deleteRecursively() }

    @Before fun setUp() {
        cleanUp()
        de = File(ctx.cacheDir.parentFile, "seed_test_de_${System.nanoTime()}").apply { mkdirs() }
        FakeLearnedStoreIo.store(LearnedStores.storeFile(dir, "Latn", 0), word("hai", 9, 100))
        filesDirBefore = KeyboardProfiles.filesDir
        KeyboardProfiles.filesDir = de
        LearnedStores.seedRunner = { _, pool, done -> runs.add(pool to done) }
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, true).apply()
        LearnedStores.refresh(real)
        assertEquals(LearnedStores.SHARED, LearnedStores.currentPool)
    }

    @After fun tearDown() {
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, true).apply()
        runs.forEach { it.second(true) } // (a pool still being copied isn't asked for again)
        LearnedStores.refresh(real)
        KeyboardProfiles.filesDir = filesDirBefore
        cleanUp(); de.deleteRecursively()
    }

    @Test fun `the switch waits for the copy, which runs elsewhere`() {
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).apply()
        val own = KeyboardProfiles.idFor(real, KeyboardProfiles.selectedKeyboard(real))
        assertNotEquals(LearnedStores.SHARED, own)
        LearnedStores.refresh(real)
        // nothing on disk for the keyboard yet: the copy is asked for, the pool stays as it was
        assertEquals(listOf(own), runs.map { it.first })
        assertEquals(LearnedStores.SHARED, LearnedStores.currentPool)
        LearnedStores.refresh(real) // (a second switch meanwhile doesn't start another copy)
        assertEquals(1, runs.size)
        // the copy done (what the real runner does, here by hand): the pool switches
        LearnedPools.separate(dir, FakeLearnedStoreIo(), listOf(own))
        runs[0].second(true)
        assertEquals(own, LearnedStores.currentPool)
        assertEquals(FakeLearnedStoreIo.read(LearnedStores.storeFile(dir, "Latn", 0)), FakeLearnedStoreIo.read(LearnedStores.storeFile(dir, "Latn", own)))
    }

    @Test fun `a copy that fails switches to the empty pool rather than never`() {
        real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).apply()
        val own = KeyboardProfiles.idFor(real, KeyboardProfiles.selectedKeyboard(real))
        LearnedStores.refresh(real)
        assertEquals(LearnedStores.SHARED, LearnedStores.currentPool)
        runs.single().second(false)
        assertEquals(own, LearnedStores.currentPool)
    }
}

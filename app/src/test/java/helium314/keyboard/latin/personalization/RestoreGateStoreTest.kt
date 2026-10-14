// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.ExecutorUtils
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store of learned words that counts the loads it starts (each stops before the file: no native library in tests)
 *  and its closes without saving. */
class LoadCountingHistory(ctx: Context, script: String, pool: Int)
    : UserHistoryDictionary(ctx, LearnedStores.storeName(script, pool), LearnedStores.storeLocale(script)) {
    val loads = AtomicInteger()
    val closes = AtomicInteger()
    override fun awaitBeforeLoad() {
        loads.incrementAndGet()
        throw IllegalStateException("a test store: no file")
    }
    override fun closeAndWait() {
        closes.incrementAndGet()
        super.closeAndWait()
    }
}

/** [dict] is the store of [script] in [pool] from now on, null: none (tests outside this package). */
fun putStoreForTest(script: String, pool: Int, dict: UserHistoryDictionary?) = PersonalizationHelper.setForTest(script, pool, dict)

/**
 * While a full restore runs on another thread (RestoreGate, owner 2026-10-12: "A + B + swipe stats makes sense"), a
 * store of learned words doesn't open: its updates and saves do nothing, its whole-store read is unread (not empty),
 * nothing NPEs. The restore's own thread still opens it (what it reads and replaces), and once the gate is open the next
 * use loads again.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreGateStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    // (a restore "running" on a thread that isn't any of the test's)
    private val restoreThread = Thread {}

    @Before fun setUp() {
        deleteLearnedFiles(ctx.filesDir)
        ExecutorUtils.setExecutorServiceForTests(executor)
    }

    @After fun tearDown() {
        RestoreGate.setForTest(null)
        ExecutorUtils.setExecutorServiceForTests(null)
        executor.shutdownNow()
        deleteLearnedFiles(ctx.filesDir)
    }

    private fun drain() = executor.submit { }.get(5, TimeUnit.SECONDS)

    @Test fun `with the gate set a store doesn't load and its updates and saves do nothing`() {
        RestoreGate.setForTest(restoreThread)
        val store = LoadCountingHistory(ctx, "Latn", 3) // (its constructor asks for a load)
        store.addUnigramEntry("word", 100, null, 0, false, false, 1)
        store.updateEntriesForWord(NgramContext.EMPTY_PREV_WORDS_INFO, "word", true, 1, 1)
        store.addNgramEntry(NgramContext.EMPTY_PREV_WORDS_INFO, "word", 100, 1)
        store.decrementEntryDynamically("word", null)
        store.removeUnigramEntryDynamically("word")
        store.runGCIfRequired(true)
        store.onFinishInput() // (the 5 s save after a learned word, and the end of a text field)
        store.flushAndClose() // (a switch to another keyboard)
        drain()
        assertTrue(store.flushAndWait(), "the save failed")
        assertEquals(Dictionary.NOT_A_PROBABILITY, store.getFrequency("word")) // (no NPE on a store kept closed)
        assertEquals(-1, store.getLearnedCount("word"))
        assertNull(store.allWordPropertiesBlocking, "a store kept closed read as empty")
        drain()
        assertEquals(0, store.loads.get(), "a store loaded while a full restore ran")
        assertFalse(LearnedStores.storeFile(ctx.filesDir, "Latn", 3).exists(), "a store wrote its file during the restore")
    }

    @Test fun `the restore's own thread still loads, and after the gate the next use does`() {
        RestoreGate.setForTest(Thread.currentThread())
        val store = LoadCountingHistory(ctx, "Latn", 4)
        drain() // (the constructor's load, on the executor: not the restore's thread)
        assertEquals(0, store.loads.get())
        store.allWordPropertiesBlocking // (on the restore's thread, as LearnedStoreMigration.runNow reads)
        assertEquals(1, store.loads.get(), "the restore couldn't read the store it merges")
        RestoreGate.setForTest(null)
        store.reloadDictionaryIfRequired()
        drain()
        assertEquals(2, store.loads.get(), "no load after the gate")
    }
}

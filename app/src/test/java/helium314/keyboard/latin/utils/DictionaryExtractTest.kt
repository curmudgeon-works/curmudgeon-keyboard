// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 2026-10-14 (P5 fresh install, swipe gave proper nouns): typing and the swipe word list both extract the bundled
 * dictionary on a fresh start; the swipe list was built from a half-copied main.dict. While a copy runs, no main.dict
 * is there; a second caller waits and gets the whole file; a copy cut off leaves none.
 */
@RunWith(RobolectricTestRunner::class)
class DictionaryExtractTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val locale = Locale.forLanguageTag("xx-TEST")
    private val dir get() = File(DictionaryInfoUtils.getCacheDirectoryForLocale(locale, ctx)!!)
    private val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }

    @After fun tearDown() { dir.deleteRecursively() }

    /** [bytes], pausing halfway until [resume] (after telling [halfway]). */
    private fun pausingStream(halfway: CountDownLatch, resume: CountDownLatch) = object : InputStream() {
        var pos = 0
        override fun read(): Int {
            if (pos == bytes.size / 2 && halfway.count > 0) { halfway.countDown(); resume.await(10, TimeUnit.SECONDS) }
            return if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
        }
    }

    @Test fun `while the copy runs there is no main dict, and a second caller gets the whole file`() {
        val halfway = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var first: File? = null
        val t1 = thread { first = DictionaryInfoUtils.extractAssetsDictionary("main_xx.dict", locale, ctx) { pausingStream(halfway, resume) } }
        assertTrue(halfway.await(10, TimeUnit.SECONDS))
        // what the swipe builder and typing look for: never a half-written main.dict
        assertFalse(File(dir, "main.dict").exists())
        assertFalse(DictionaryInfoUtils.getCachedDictsForLocale(locale, ctx).any { it.name.endsWith(".dict") })
        var second: File? = null
        val t2 = thread { second = DictionaryInfoUtils.extractAssetsDictionary("main_xx.dict", locale, ctx) { error("copied twice") } }
        Thread.sleep(200)
        assertTrue(t2.isAlive) // waits for the first copy
        resume.countDown()
        t1.join(10_000); t2.join(10_000)
        assertContentEquals(bytes, first!!.readBytes())
        assertContentEquals(bytes, second!!.readBytes())
    }

    @Test fun `a copy cut off leaves no main dict`() {
        val broken = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos++ < 1000) 7 else throw IOException("cut off")
        }
        assertNull(DictionaryInfoUtils.extractAssetsDictionary("main_xx.dict", locale, ctx) { broken })
        assertFalse(DictionaryInfoUtils.getCachedDictsForLocale(locale, ctx).any { it.name.endsWith(".dict") })
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first-start lag (owner 2026-10-14: "Yes, option A; and also create the folder."): at the app's start the
 * languages without a saved swipe list get theirs built in the background (the same build a swipe asks for, its
 * dictionary extracted under one lock), and the dictionary size note no longer fails on a fresh install because its
 * folder didn't exist yet. Fail before: no start-time build (compile), and the note threw FileNotFoundException.
 */
@RunWith(RobolectricTestRunner::class)
class FirstStartSwipeListTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir get() = File(ctx.filesDir, "own_gesture_vocab")

    @Before fun setUp() { dir.deleteRecursively() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun `a language without a saved swipe list is built at the start, one with a list is left alone`() {
        dir.mkdirs()
        File(dir, "${Locale.FRENCH.toLanguageTag()}.txt").writeText("saved")
        val started = mutableListOf<Locale>()
        val result = GestureDecoderVocabulary.buildMissingLists(ctx, listOf(Locale.US, Locale.FRENCH, Locale.US)) { started += it }
        assertEquals(listOf(Locale.US), started)
        assertEquals(listOf(Locale.US), result)
    }

    @Test fun `a fresh install - nothing saved, every language is built`() {
        val started = mutableListOf<Locale>()
        GestureDecoderVocabulary.buildMissingLists(ctx, listOf(Locale.US, Locale.GERMAN)) { started += it }
        assertEquals(listOf(Locale.US, Locale.GERMAN), started)
    }

    @Test fun `the dictionary size note makes its folder on a fresh install`() {
        assertTrue(!dir.exists())
        val cacheFile = File(dir, "en-US.txt") // (not written yet: the first build counts before it writes its list)
        GestureDecoderVocabulary.logDictionarySizeOnce(cacheFile, "en-US") { GestureDecoderVocabulary.DictCounts(160_715, 150_000) }
        val marker = File(dir, "en-US.size")
        assertTrue(marker.isFile)
        assertEquals("160715\t150000\n", marker.readText())
    }
}

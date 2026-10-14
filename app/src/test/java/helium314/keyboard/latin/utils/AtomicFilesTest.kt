// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Review B4w.2: the app's own lists and layouts are written through a temporary file renamed over the old one
 * ([AtomicFiles]): a write that fails or is cut off leaves the old file whole. Through the helper, and through a real
 * caller, the blacklist (RemovedWords).
 */
@RunWith(RobolectricTestRunner::class)
class AtomicFilesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(ctx.filesDir, "atomic-test-${System.nanoTime()}").apply { mkdirs() }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun `a write cut off halfway leaves the old file whole, and no temporary file`() {
        val file = File(dir, "list.txt").apply { writeText("old\ncontent\n") }
        assertFailsWith<IOException> {
            AtomicFiles.write(file) { out -> out.write("new\nhal".toByteArray()); throw IOException("disk full") }
        }
        assertEquals("old\ncontent\n", file.readText())
        assertFalse(File(dir, "list.txt" + AtomicFiles.TMP_SUFFIX).exists())
        AtomicFiles.write(file, "new\n") // (and a whole write replaces it)
        assertEquals("new\n", file.readText())
    }

    // fail before: the blacklist was written in place (here the write goes through: the new list over the old)
    @Test fun `a blacklist write that can't be made leaves the old list whole`() {
        val file = File(dir, "blacklists${File.separator}Zzzz.txt").apply { parentFile!!.mkdirs(); writeText("teh\nhte\t2\n") }
        // (the temporary file's place is taken: the write fails, as a full disk would make it)
        File(file.parentFile, file.name + AtomicFiles.TMP_SUFFIX).apply { mkdirs(); File(this, "x").writeText("x") }
        val list = RemovedWords.forFile(file)
        list.ensureLoaded()
        list.strike("adn")
        list.reload() // (after the write it started)
        assertEquals("teh\nhte\t2\n", file.readText())
    }
}

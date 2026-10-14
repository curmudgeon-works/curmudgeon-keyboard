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
import kotlin.test.assertTrue

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

    @Test fun `a copy that can't be read through leaves the target whole`() {
        val target = File(dir, "main_user.dict").apply { writeText("device's") }
        val unreadable = File(dir, "picked").apply { mkdirs() } // (a folder: its read fails)
        assertFailsWith<IOException> { AtomicFiles.copy(unreadable, target) }
        assertEquals("device's", target.readText())
        AtomicFiles.copy(File(dir, "new").apply { writeText("new") }, target)
        assertEquals("new", target.readText())
    }

    // fail before (B4w.3): the device's dictionary was deleted first, then the picked file renamed in (here a folder,
    // which a rename takes): the device's dictionary was gone
    @Test fun `adding a dictionary whose copy fails keeps the device's`() {
        val dictFile = File(dir, "en-US${File.separator}main_user.dict").apply { parentFile!!.mkdirs(); writeText("device's") }
        val picked = File(dir, "cached").apply { mkdirs(); File(this, "x").writeText("x") }
        assertFalse(helium314.keyboard.settings.dialogs.installDictionary(picked, dictFile))
        assertEquals("device's", dictFile.readText())
    }

    // round-5 review 20261013_L19 (owner: "B"): the dialog's add says when it failed. Fail before: nothing was said
    @Test fun `adding a dictionary that fails says so`() {
        val dictFile = File(dir, "en-US${File.separator}main_user.dict").apply { parentFile!!.mkdirs(); writeText("device's") }
        val picked = File(dir, "cached").apply { mkdirs(); File(this, "x").writeText("x") }
        org.robolectric.shadows.ShadowToast.reset()
        assertFalse(helium314.keyboard.settings.dialogs.installDictionaryOrSay(ctx, picked, dictFile))
        assertEquals("Error adding dictionary. Nothing was changed.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals("device's", dictFile.readText())
        // (one that comes in says nothing)
        org.robolectric.shadows.ShadowToast.reset()
        assertTrue(helium314.keyboard.settings.dialogs.installDictionaryOrSay(ctx, File(dir, "new").apply { writeText("new") }, dictFile))
        assertEquals(null, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    // fail before: the blacklist was written in place (here the write goes through: the new list over the old)
    @Test fun `a blacklist write that can't be made leaves the old list whole`() {
        val file = File(dir, "blacklists${File.separator}Zzzz.txt").apply { parentFile!!.mkdirs(); writeText("teh\nhte\t2\n") }
        // (the temporary files' folder can't be written: the write fails, as a full disk would make it)
        AtomicFiles.init(ctx)
        val tmpDir = File(ctx.noBackupFilesDir, "atomic_tmp").apply { mkdirs() }
        assertTrue(tmpDir.setWritable(false))
        try {
            val list = RemovedWords.forFile(file)
            list.ensureLoaded()
            list.strike("adn")
            list.reload() // (after the write it started)
        } finally { tmpDir.setWritable(true) }
        assertEquals("teh\nhte\t2\n", file.readText())
    }

    // round-5 review 20261013_L20 (owner: "Ok, A"): the temporary file is in a folder of its own, outside both files
    // folders (what a backup walks, what the lists show). Fail before: it was beside the target, "<name>.tmp"
    @Test fun `the temporary file is in no folder a backup or a list walks`() {
        AtomicFiles.init(ctx)
        val roots = listOf(ctx.filesDir, DeviceProtectedUtils.getFilesDir(ctx))
        val deDir = File(DeviceProtectedUtils.getFilesDir(ctx), "atomic-test-de")
        try {
            for (target in listOf(File(dir, "fonts${File.separator}Mine.ttf"),
                    File(deDir, "layouts${File.separator}custom.mine."))) {
                var during: List<File> = emptyList()
                AtomicFiles.write(target) { out ->
                    out.write("new".toByteArray())
                    during = roots.flatMap { root -> root.walk().filter { it.isFile && it.name.endsWith(AtomicFiles.TMP_SUFFIX) }.toList() }
                }
                assertEquals(emptyList<File>(), during, "a temporary file a backup takes")
                assertEquals("new", target.readText())
            }
        } finally { deDir.deleteRecursively() }
        assertEquals(emptyList<File>(), File(ctx.noBackupFilesDir, "atomic_tmp").listFiles()?.toList() ?: emptyList(), "left behind")
    }

    // B4w.1 / 20261013_L20: a Layout & Typing put-back cut off left "layouts.restoring" beside the layouts, and the
    // backups took it; the next start deletes it. Fail before: it stayed
    @Test fun `a leftover layouts restoring folder goes at the next start`() {
        val restoringDir = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts.restoring")
        File(restoringDir, "number_row${File.separator}custom.mine.").apply { parentFile!!.mkdirs(); writeText("1 2 3") }
        try {
            helium314.keyboard.settings.LayoutDraft.recoverAfterCrash(ctx)
            assertFalse(restoringDir.exists())
        } finally { restoringDir.deleteRecursively() }
    }
}

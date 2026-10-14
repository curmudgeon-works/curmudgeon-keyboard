// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Review B4w.1: Layout & Typing's crash undo, like Appearance's (B4). A snapshot that can't be read deleted the draft's
 * folder, and with it the copy of the key layouts taken at the open: a layout deleted with the screen open was lost.
 * Now the copy's layouts the live folder lacks go back first (never over a live one), then the folder goes.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutDraftRecoveryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val layouts get() = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts")
    private val draftDir get() = File(DeviceProtectedUtils.getFilesDir(ctx), "layout_draft")

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        layouts.deleteRecursively(); draftDir.deleteRecursively()
    }
    @After fun tearDown() {
        LayoutDraft.close()
        layouts.deleteRecursively(); draftDir.deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    // fail before: the deleted layout went with the draft's folder
    @Test fun `a layout deleted with the screen open survives a cut-off snapshot`() {
        val deleted = File(layouts, "number_row${File.separator}custom.mine.").apply { parentFile!!.mkdirs(); writeText("1 2 3") }
        val kept = File(layouts, "number_row${File.separator}custom.other.").apply { writeText("4 5 6") }
        LayoutDraft.of(ctx, "en_US:") // Layout & Typing opens
        deleted.delete() // (deleted on the screen)
        kept.writeText("7 8 9") // (changed on the screen: the live one stays as it is)
        LayoutDraft.forgetActiveForTest() // the process died
        val snapshot = File(draftDir, "draft_prefs.json")
        snapshot.writeText(snapshot.readText().take(20)) // cut off
        LayoutDraft.recoverAfterCrash(ctx) // the next app start
        assertEquals("1 2 3", deleted.readText())
        assertEquals("7 8 9", kept.readText())
        assertFalse(draftDir.exists(), "the draft's folder stays")
    }

    // round-5 review 20261013_L18 (owner: "Ok A"): Save's delete of the snapshot is one step (renamed to a finished
    // folder, then deleted). Fail before: a delete cut off (here: a folder of the copy that can't be emptied) took the
    // snapshot file and left the copy's layout, and the next start put the layout deleted and saved back
    @Test fun `a Save whose delete of the snapshot is cut off brings nothing back`() {
        val deleted = File(layouts, "number_row${File.separator}custom.mine.").apply { parentFile!!.mkdirs(); writeText("1 2 3") }
        val draft = LayoutDraft.of(ctx, "en_US:") // Layout & Typing opens
        deleted.delete() // (deleted on the screen)
        val stuck = listOf(File(draftDir, "layouts${File.separator}number_row"),
            File(DeviceProtectedUtils.getFilesDir(ctx), "layout_draft.finished${File.separator}layouts${File.separator}number_row"))
        stuck[0].setWritable(false) // (its file can't be deleted: the delete stops partway)
        try {
            draft.accept() // Save
        } finally { stuck.forEach { it.setWritable(true) } }
        LayoutDraft.recoverAfterCrash(ctx) // the next app start
        assertFalse(deleted.exists(), "the layout deleted and saved came back")
        assertFalse(draftDir.exists())
        assertFalse(File(DeviceProtectedUtils.getFilesDir(ctx), "layout_draft.finished").exists(), "the finished folder is left")
    }
}

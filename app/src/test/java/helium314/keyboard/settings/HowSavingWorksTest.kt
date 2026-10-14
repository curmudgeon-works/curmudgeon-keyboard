// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.settings.screens.savingGuide
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** App settings > How saving works (owner 2026-10-11): the tabs asked for, App update included, each with rows. */
@RunWith(RobolectricTestRunner::class)
class HowSavingWorksTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the tabs are the actions asked for, in order, each with its rows`() {
        assertEquals(listOf("Summary", "Choose", "Edit", "Save as new", "Rename", "Delete", "Default", "Add / Import", "Export / Share",
            "Backup", "Partial restore", "Full restore", "App update", "Factory reset", "Separate settings on/off"),
            savingGuide.map { ctx.getString(it.title) })
        for (tab in savingGuide) {
            assertTrue(tab.rows.isNotEmpty(), ctx.getString(tab.title))
            for (row in tab.rows) assertTrue(ctx.getString(row.text).length > 10, ctx.getString(row.kind))
        }
    }

    // owner 2026-10-14: "Screen should open on summary each time yes": Summary is the first tab, its 6 rows in order
    @Test fun `summary is the first tab with its six rows`() {
        val summary = savingGuide.first()
        assertEquals("Summary", ctx.getString(summary.title))
        assertEquals(listOf("Screens with Save and Discard", "Leaving a screen keeps your changes: on",
            "Leaving a screen keeps your changes: off", "Editors opened from those screens",
            "Saved Layouts, themes and popup sets", "One keyboard or all"), summary.rows.map { ctx.getString(it.kind) })
        val texts = summary.rows.map { ctx.getString(it.text) }
        assertTrue(texts.none { "phone" in it }, texts.toString())
        assertTrue(texts[0].startsWith("Appearance, Layout & typing,"), texts[0])
        assertTrue("you didn't save" in texts[2], texts[2])
        assertTrue("\"Custom · from Sunset\"" in texts[4], texts[4])
    }

    // owner 2026-10-12: the restore dialog's groups (Full restore (delete app data); Restore their: Settings,
    // Dictionaries; And merge: Learned words, Clipboard history), told as they work now; "device", never "phone"
    @Test fun `partial and full restore rows - the new names, dictionaries and clipboard, device not phone`() {
        val partial = savingGuide.first { ctx.getString(it.title) == "Partial restore" }.rows.map { it.text }
        val full = savingGuide.first { ctx.getString(it.title) == "Full restore" }.rows.map { it.text }
        assertTrue(helium314.keyboard.latin.R.string.saving_partial_dictionaries in partial)
        assertTrue(helium314.keyboard.latin.R.string.saving_partial_clips in partial)
        val texts = (partial + full).map { ctx.getString(it) }
        assertTrue(texts.none { "phone" in it || "Delete app data and restore from backup" in it }, texts.toString())
        assertTrue(texts.any { "Full restore (delete app data)" in it })
        assertTrue(texts.any { "higher count" in it && "personal dictionary" in it })
        assertTrue(texts.any { "…prev" in it })
    }

    // owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones
    @Test fun `separate settings rows - the first keyboard's settings, nothing copied, delete and reorder`() {
        val separate = savingGuide.last().rows.map { it.text }
        val texts = separate.map { ctx.getString(it) }
        assertTrue(texts.none { "a copy of the common settings" in it || "the keyboard you pick" in it }, texts.toString())
        assertTrue(texts.any { "the first keyboard\'s settings" in it }, texts.toString())
        assertTrue(helium314.keyboard.latin.R.string.saving_separate_first_delete in separate)
        assertTrue(helium314.keyboard.latin.R.string.saving_separate_reorder in separate)
    }
}

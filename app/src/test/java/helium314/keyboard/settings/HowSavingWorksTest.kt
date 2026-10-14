// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.settings.screens.savingActions
import helium314.keyboard.settings.screens.savingCell
import helium314.keyboard.settings.screens.savingGuide
import helium314.keyboard.settings.screens.savingKinds
import helium314.keyboard.settings.screens.savingShort
import helium314.keyboard.settings.screens.savingSummary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** App settings > How saving works (owner 2026-10-11): the tabs asked for, App update included, each with rows. */
@RunWith(RobolectricTestRunner::class)
class HowSavingWorksTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    // (2026-10-14: the Summary and the grid's columns, in this order)
    @Test fun `the tabs are the actions asked for, in order, each with its rows`() {
        assertEquals(listOf("Summary", "Choose", "Edit", "Save as new", "Rename", "Delete", "Default", "Add / Import", "Export / Share",
            "Backup", "Partial restore", "Full restore", "App update", "Factory reset", "Separate settings on/off"),
            savingGuide.map { ctx.getString(it.title) })
        for (tab in savingGuide) {
            assertTrue(tab.rows.isNotEmpty(), ctx.getString(tab.title))
            for (row in tab.rows) assertTrue(ctx.getString(row.text).length > 10, ctx.getString(row.kind))
        }
    }

    // owner 2026-10-14: "a table with save items as rows and actions as columns instead of current tabs?": the columns
    // are the old tabs without Summary, in order; the rows the kinds, in the order agreed
    @Test fun `the grid - actions as columns, kinds as rows`() {
        assertEquals(listOf("Choose", "Edit", "Save as new", "Rename", "Delete", "Default", "Add / Import", "Export / Share",
            "Backup", "Partial restore", "Full restore", "App update", "Factory reset", "Separate settings on/off"),
            savingActions.map { ctx.getString(it.title) })
        assertEquals(listOf("Layouts", "Themes", "Popup sets", "Palettes", "Fonts", "Pictures", "Custom key layouts",
            "Keyboards and their settings", "Added dictionaries", "Learned words", "Blacklisted words",
            "Android's personal dictionary", "Pinned clipboard items", "Clipboard history", "Everything",
            "Saved items take new defaults"), savingKinds.map { ctx.getString(it) })
    }

    // (2026-10-14) nothing on the old tabs is lost: every (kind, action) row is a cell of the grid, the cell's dialog
    // shows that full sentence (both, where a tab had two rows for a kind), and the cell has a short text
    @Test fun `every old row maps to its cell's full sentence, every cell with rows has a short text`() {
        for (action in savingActions) for (row in action.rows) {
            val where = "${ctx.getString(row.kind)} · ${ctx.getString(action.title)}"
            assertTrue(row.kind in savingKinds, where)
            assertTrue(row.text in savingCell(row.kind, action), where)
        }
        assertEquals(listOf(R.string.saving_delete_keyboards, R.string.saving_separate_first_delete),
            savingCell(R.string.saving_kind_keyboards, savingActions.first { ctx.getString(it.title) == "Delete" }))
        for (action in savingActions) for (kind in savingKinds) {
            val rows = savingCell(kind, action)
            if (rows.isEmpty()) continue
            val short = assertNotNull(savingShort[rows.first()], "${ctx.getString(kind)} · ${ctx.getString(action.title)}")
            val where = "${ctx.getString(kind)} · ${ctx.getString(action.title)}"
            val words = ctx.getString(short).split(" ").size
            assertTrue(words in 1..5, "$where: ${ctx.getString(short)}")
            assertTrue("phone" !in ctx.getString(short), where)
        }
        // a kind an action has no row for shows "—"
        assertTrue(savingCell(R.string.saving_kind_everything, savingActions.first { ctx.getString(it.title) == "Choose" }).isEmpty())
        assertEquals("—", ctx.getString(R.string.saving_cell_not_applicable))
        assertEquals("Layouts · Delete", ctx.getString(R.string.saving_cell_dialog_title, "Layouts", "Delete"))
    }

    // owner 2026-10-14: "Screen should open on summary each time yes": the Summary comes first, its 6 rows in order
    @Test fun `summary comes first with its six rows`() {
        val summary = savingGuide.first()
        assertEquals("Summary", ctx.getString(summary.title))
        assertEquals(summary.rows, savingSummary)
        assertTrue(savingActions.none { it === summary })
        assertEquals(listOf("Screens with Save and Discard", "Leaving a screen keeps your changes: on",
            "Leaving a screen keeps your changes: off", "Editors opened from those screens",
            "Saved Layouts, themes and popup sets", "One keyboard or all"), summary.rows.map { ctx.getString(it.kind) })
        val texts = summary.rows.map { ctx.getString(it.text) }
        assertTrue(texts.none { "phone" in it }, texts.toString())
        assertTrue(texts[0].startsWith("Appearance, Layout & typing,"), texts[0])
        assertTrue("you didn't save" in texts[2], texts[2])
        assertTrue("\"Custom · from Sunset\"" in texts[4], texts[4])
    }

    // owner 2026-10-12: the restore dialog's groups (Full restore (delete app data); since R-DICT, 2026-10-13, the
    // keyboards and "Also restore"), told as they work now; "device", never "phone"
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

    // owner 2026-10-14 ("yes A."): the Partial restore rows follow the dialog of today (R-DICT): each keyboard brings its
    // own dictionaries, no tick; "Also restore" has Other dictionaries, Clipboard history and Android personal dictionary
    // words, all ticked. Fail before: they named "And merge" and "Restore their", headings the dialog no longer has
    @Test fun `partial restore rows name the dialog's Also restore rows, not the old headings`() {
        val texts = listOf(R.string.saving_partial_words, R.string.saving_partial_dictionaries, R.string.saving_partial_clips)
            .map { ctx.getString(it) }
        assertTrue(texts.none { "And merge" in it || "Restore their" in it || "Unticked, none" in it }, texts.toString())
        assertTrue(texts.all { ctx.getString(R.string.restore_section_also) in it && "ticked to start with" in it }, texts.toString())
        for (row in listOf(R.string.restore_other_dictionaries, R.string.restore_clipboard, R.string.restore_personal_words))
            assertTrue(texts.any { "\"${ctx.getString(row)}\"" in it }, ctx.getString(row))
        assertTrue("brings the dictionaries of its languages, no tick" in texts[1], texts[1])
    }

    // owner 2026-10-14 ("A. yes fill."): no "—" where the action does something. Fail before: these cells were empty
    @Test fun `the grid fills clipboard history on reset, pins on partial restore, themes and popup sets on separate`() {
        fun action(title: String) = savingActions.first { ctx.getString(it.title) == title }
        val reset = savingCell(R.string.restore_clipboard, action("Factory reset"))
        assertEquals(listOf(R.string.saving_reset_clipboard), reset)
        assertTrue("\"${ctx.getString(R.string.factory_reset_clipboard)}\" is ticked" in ctx.getString(reset.single()))
        assertEquals(listOf(R.string.saving_partial_clips), savingCell(R.string.saving_kind_clips, action("Partial restore")))
        assertTrue("pins are kept" in ctx.getString(R.string.saving_partial_clips))
        for (kind in listOf(R.string.saving_kind_themes, R.string.saving_kind_popups))
            assertEquals(listOf(R.string.saving_separate_items), savingCell(kind, action("Separate settings on/off")), ctx.getString(kind))
    }

    // owner 2026-10-14 ("A. change sentence"): a factory reset keeps "Saved items take new defaults" only with the saved
    // items' box ticked (keptOnReset). Fail before: the sentence said it stays as it is
    @Test fun `factory reset - the saved items switch is kept only with the saved items box ticked`() {
        val text = ctx.getString(R.string.saving_reset_items)
        assertTrue("\"Saved items take new defaults\" stays as it is" !in text, text)
        assertTrue("\"Saved items take new defaults\" is kept only when that box is ticked; otherwise it goes back on" in text, text)
        val key = SavedItemDefaults.PREF
        assertTrue(helium314.keyboard.settings.preferences.keptOnReset(key, helium314.keyboard.settings.preferences.ResetKeep(
            keyboards = false, words = false, clipboard = false, own = true)))
        assertTrue(!helium314.keyboard.settings.preferences.keptOnReset(key, helium314.keyboard.settings.preferences.ResetKeep(
            keyboards = true, words = true, clipboard = true, own = false)))
        assertTrue(SavedItemDefaults.DEFAULT) // ("goes back on")
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

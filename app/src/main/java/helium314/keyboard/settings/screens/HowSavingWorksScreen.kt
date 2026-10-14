// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

/**
 * App settings > How saving works (owner 2026-10-11: "A tile in app settings should take us to a screen where there's
 * a list of all there elements layouts, themes, palettes, fonts, images, etc., with sub text showing what an action
 * does. Actions should be tabs up top"): read only.
 * (owner 2026-10-14: "a table with save items as rows and actions as columns instead of current tabs? [...] feels
 * better to have a single screen with everything to compare actions across columns"; "fully agree with this. Ship it,
 * no need to mock up"): the Summary on top, then one grid, kinds by actions. Each tab below is now a column; a kind an
 * action has no row for shows "—" in it, a tap on any other cell shows the cell's full rows.
 */
internal class SavingRow(@StringRes val kind: Int, @StringRes val text: Int)
internal class SavingTab(@StringRes val title: Int, val rows: List<SavingRow>)

internal val savingGuide: List<SavingTab> = listOf(
    // owner 2026-10-14: "Screen should open on summary each time yes": first, the text above the grid
    SavingTab(R.string.saving_tab_summary, listOf(
        SavingRow(R.string.saving_summary_save_discard_title, R.string.saving_summary_save_discard_text),
        SavingRow(R.string.saving_summary_keeps_on_title, R.string.saving_summary_keeps_on_text),
        SavingRow(R.string.saving_summary_keeps_off_title, R.string.saving_summary_keeps_off_text),
        SavingRow(R.string.saving_summary_editors_title, R.string.saving_summary_editors_text),
        SavingRow(R.string.saving_summary_saved_items_title, R.string.saving_summary_saved_items_text),
        SavingRow(R.string.saving_summary_one_or_all_title, R.string.saving_summary_one_or_all_text),
    )),
    SavingTab(R.string.saving_tab_choose, listOf(
        SavingRow(R.string.saving_kind_layouts, R.string.saving_choose_layouts),
        SavingRow(R.string.saving_kind_themes, R.string.saving_choose_themes),
        SavingRow(R.string.saving_kind_popups, R.string.saving_choose_popups),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_choose_palettes),
        SavingRow(R.string.saving_kind_fonts, R.string.saving_choose_fonts),
        SavingRow(R.string.saving_kind_pictures, R.string.saving_choose_pictures),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_choose_key_layouts),
        SavingRow(R.string.saved_items_take_new_defaults, R.string.saving_choose_saved_defaults),
    )),
    SavingTab(R.string.saving_tab_edit, listOf(
        SavingRow(R.string.saving_kind_layouts, R.string.saving_edit_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_edit_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_edit_popups),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_edit_palettes),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_edit_key_layouts),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_edit_keyboards),
    )),
    SavingTab(R.string.saving_tab_save_new, listOf(
        SavingRow(R.string.saving_kind_layouts, R.string.saving_new_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_new_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_new_items),
    )),
    SavingTab(R.string.saving_tab_rename, listOf(
        SavingRow(R.string.saving_kind_layouts, R.string.saving_rename_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_rename_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_rename_items),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_rename_palettes),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_rename_key_layouts),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_rename_keyboards),
    )),
    SavingTab(R.string.saving_tab_delete, listOf(
        SavingRow(R.string.saving_kind_layouts, R.string.saving_delete_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_delete_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_delete_popups),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_delete_palettes),
        SavingRow(R.string.saving_kind_fonts, R.string.saving_delete_fonts),
        SavingRow(R.string.saving_kind_pictures, R.string.saving_delete_pictures),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_delete_key_layouts),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_delete_keyboards),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_first_delete),
        SavingRow(R.string.saving_kind_dictionaries, R.string.saving_delete_dictionaries),
        SavingRow(R.string.saving_kind_learned, R.string.saving_delete_learned),
        SavingRow(R.string.saving_kind_blacklisted, R.string.saving_delete_blacklisted),
        SavingRow(R.string.saving_kind_personal, R.string.saving_delete_personal),
        SavingRow(R.string.saving_kind_clips, R.string.saving_delete_clips),
    )),
    SavingTab(R.string.saving_tab_default, listOf(
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_default_keyboards),
        SavingRow(R.string.saving_kind_layouts, R.string.saving_default_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_default_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_default_items),
    )),
    SavingTab(R.string.saving_tab_add, listOf(
        SavingRow(R.string.saving_kind_palettes, R.string.saving_add_palettes),
        SavingRow(R.string.saving_kind_fonts, R.string.saving_add_fonts),
        SavingRow(R.string.saving_kind_pictures, R.string.saving_add_pictures),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_add_key_layouts),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_add_keyboards),
        SavingRow(R.string.saving_kind_dictionaries, R.string.saving_add_dictionaries),
        SavingRow(R.string.saving_kind_personal, R.string.saving_add_personal),
    )),
    SavingTab(R.string.saving_tab_export, listOf(
        SavingRow(R.string.saving_kind_palettes, R.string.saving_export_palettes),
        SavingRow(R.string.saving_kind_everything, R.string.saving_export_everything),
    )),
    SavingTab(R.string.saving_tab_backup, listOf(
        SavingRow(R.string.saving_kind_layouts, R.string.saving_backup_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_backup_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_backup_items),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_backup_items),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_backup_keyboards),
        SavingRow(R.string.saving_kind_fonts, R.string.saving_backup_files),
        // (the kinds those two sentences name get their cells in the grid too, 2026-10-14)
        SavingRow(R.string.saving_kind_pictures, R.string.saving_backup_files),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_backup_files),
        SavingRow(R.string.saving_kind_dictionaries, R.string.saving_backup_files),
        SavingRow(R.string.saving_kind_learned, R.string.saving_backup_words),
        SavingRow(R.string.saving_kind_blacklisted, R.string.saving_backup_words),
        SavingRow(R.string.saving_kind_personal, R.string.saving_backup_words),
        SavingRow(R.string.saving_kind_clips, R.string.saving_backup_words),
        SavingRow(R.string.restore_clipboard, R.string.saving_backup_words), // ("Clipboard history")
    )),
    SavingTab(R.string.saving_tab_partial, listOf(
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_partial_keyboards),
        SavingRow(R.string.saving_kind_layouts, R.string.saving_partial_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_partial_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_partial_items),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_partial_palettes),
        SavingRow(R.string.saving_kind_fonts, R.string.saving_partial_files),
        SavingRow(R.string.saving_kind_pictures, R.string.saving_partial_files),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_partial_files),
        SavingRow(R.string.saving_kind_dictionaries, R.string.saving_partial_dictionaries),
        SavingRow(R.string.saving_kind_learned, R.string.saving_partial_words),
        SavingRow(R.string.saving_kind_clips, R.string.saving_partial_clips), // (the merge keeps both sides' pins)
        SavingRow(R.string.restore_clipboard, R.string.saving_partial_clips), // ("Clipboard history")
        SavingRow(R.string.saved_items_take_new_defaults, R.string.saving_partial_switch),
    )),
    SavingTab(R.string.saving_tab_full, listOf(
        SavingRow(R.string.saving_kind_everything, R.string.saving_full_everything),
    )),
    SavingTab(R.string.saving_tab_update, listOf(
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_update_keyboards),
        SavingRow(R.string.saving_kind_layouts, R.string.saving_update_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_update_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_update_items),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_update_files),
        SavingRow(R.string.saving_kind_fonts, R.string.saving_update_files),
        SavingRow(R.string.saving_kind_pictures, R.string.saving_update_files),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_update_files),
        SavingRow(R.string.saving_kind_learned, R.string.saving_update_words),
        SavingRow(R.string.saving_kind_blacklisted, R.string.saving_update_words),
        SavingRow(R.string.saving_kind_dictionaries, R.string.saving_update_words),
    )),
    SavingTab(R.string.saving_tab_reset, listOf(
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_reset_keyboards),
        SavingRow(R.string.saving_kind_layouts, R.string.saving_reset_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_reset_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_reset_items),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_reset_items),
        // (2026-10-11: the files the 4th box keeps too)
        SavingRow(R.string.saving_kind_fonts, R.string.saving_reset_items),
        SavingRow(R.string.saving_kind_pictures, R.string.saving_reset_items),
        SavingRow(R.string.saving_kind_key_layouts, R.string.saving_reset_items),
        SavingRow(R.string.saving_kind_dictionaries, R.string.saving_reset_items),
        SavingRow(R.string.saving_kind_learned, R.string.saving_reset_words),
        SavingRow(R.string.saving_kind_blacklisted, R.string.saving_reset_words),
        SavingRow(R.string.saving_kind_clips, R.string.saving_reset_clips),
        SavingRow(R.string.restore_clipboard, R.string.saving_reset_clipboard), // ("Clipboard history")
    )),
    SavingTab(R.string.saving_tab_separate, listOf(
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_on),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_off),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_first_delete),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_reorder),
        SavingRow(R.string.saving_kind_layouts, R.string.saving_separate_items),
        SavingRow(R.string.saving_kind_themes, R.string.saving_separate_items),
        SavingRow(R.string.saving_kind_popups, R.string.saving_separate_items),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_separate_items),
        SavingRow(R.string.saving_kind_learned, R.string.saving_separate_words),
    )),
)

/** The text above the grid: the Summary's rows. */
internal val savingSummary: List<SavingRow> get() = savingGuide.first().rows

/** The grid's columns: every action, in the old tabs' order. */
internal val savingActions: List<SavingTab> get() = savingGuide.drop(1)

/** The grid's rows, in the owner's order (2026-10-14); every kind any action has a row for is one of them. */
internal val savingKinds: List<Int> = listOf(
    R.string.saving_kind_layouts, R.string.saving_kind_themes, R.string.saving_kind_popups, R.string.saving_kind_palettes,
    R.string.saving_kind_fonts, R.string.saving_kind_pictures, R.string.saving_kind_key_layouts, R.string.saving_kind_keyboards,
    R.string.saving_kind_dictionaries, R.string.saving_kind_learned, R.string.saving_kind_blacklisted,
    R.string.saving_kind_personal, R.string.saving_kind_clips, R.string.restore_clipboard, R.string.saving_kind_everything,
    R.string.saved_items_take_new_defaults,
)

/** A cell's full rows: 2 or more where an action told a kind twice (Delete › Keyboards); none = "—". */
internal fun savingCell(@StringRes kind: Int, action: SavingTab): List<Int> = action.rows.filter { it.kind == kind }.map { it.text }

/**
 * A cell's short text, by its first full row (owner 2026-10-14: "It'll need massive text contraction potentially";
 * "Wording needs work", reworked in a later batch). Cells telling the same sentence share one short text.
 */
internal val savingShort: Map<Int, Int> = mapOf(
    R.string.saving_choose_layouts to R.string.saving_short_choose_layouts,
    R.string.saving_choose_themes to R.string.saving_short_choose_themes,
    R.string.saving_choose_popups to R.string.saving_short_choose_popups,
    R.string.saving_choose_palettes to R.string.saving_short_choose_palettes,
    R.string.saving_choose_fonts to R.string.saving_short_choose_fonts,
    R.string.saving_choose_pictures to R.string.saving_short_choose_pictures,
    R.string.saving_choose_key_layouts to R.string.saving_short_choose_key_layouts,
    R.string.saving_choose_saved_defaults to R.string.saving_short_choose_saved_defaults,
    R.string.saving_edit_items to R.string.saving_short_this_keyboard,
    R.string.saving_edit_popups to R.string.saving_short_this_keyboard,
    R.string.saving_edit_palettes to R.string.saving_short_users_change,
    R.string.saving_edit_key_layouts to R.string.saving_short_users_change,
    R.string.saving_edit_keyboards to R.string.saving_short_edit_keyboards,
    R.string.saving_new_items to R.string.saving_short_new_items,
    R.string.saving_rename_items to R.string.saving_short_users_follow,
    R.string.saving_rename_palettes to R.string.saving_short_users_follow,
    R.string.saving_rename_key_layouts to R.string.saving_short_users_follow,
    R.string.saving_rename_keyboards to R.string.saving_short_rename_keyboards,
    R.string.saving_delete_items to R.string.saving_short_off_list_kept,
    R.string.saving_delete_popups to R.string.saving_short_off_list_kept,
    R.string.saving_delete_palettes to R.string.saving_short_delete_palettes,
    R.string.saving_delete_fonts to R.string.saving_short_delete_fonts,
    R.string.saving_delete_pictures to R.string.saving_short_delete_pictures,
    R.string.saving_delete_key_layouts to R.string.saving_short_delete_key_layouts,
    R.string.saving_delete_keyboards to R.string.saving_short_delete_keyboards,
    R.string.saving_delete_dictionaries to R.string.saving_short_delete_dictionaries,
    R.string.saving_delete_learned to R.string.saving_short_delete_learned,
    R.string.saving_delete_blacklisted to R.string.saving_short_delete_blacklisted,
    R.string.saving_delete_personal to R.string.saving_short_delete_personal,
    R.string.saving_delete_clips to R.string.saving_short_delete_clips,
    R.string.saving_default_keyboards to R.string.saving_short_this_keyboard,
    R.string.saving_default_items to R.string.saving_short_default_items,
    R.string.saving_add_palettes to R.string.saving_short_add_palettes,
    R.string.saving_add_fonts to R.string.saving_short_add_fonts,
    R.string.saving_add_pictures to R.string.saving_short_add_pictures,
    R.string.saving_add_key_layouts to R.string.saving_short_add_key_layouts,
    R.string.saving_add_keyboards to R.string.saving_short_add_keyboards,
    R.string.saving_add_dictionaries to R.string.saving_short_add_dictionaries,
    R.string.saving_add_personal to R.string.saving_short_add_personal,
    R.string.saving_export_palettes to R.string.saving_short_export_palettes,
    R.string.saving_export_everything to R.string.saving_short_export_everything,
    R.string.saving_backup_items to R.string.saving_short_backup_items,
    R.string.saving_backup_keyboards to R.string.saving_short_backup_keyboards,
    R.string.saving_backup_files to R.string.saving_short_included,
    R.string.saving_backup_words to R.string.saving_short_included,
    R.string.saving_partial_keyboards to R.string.saving_short_partial_keyboards,
    R.string.saving_partial_items to R.string.saving_short_clashes,
    R.string.saving_partial_palettes to R.string.saving_short_clashes,
    R.string.saving_partial_files to R.string.saving_short_partial_files,
    R.string.saving_partial_dictionaries to R.string.saving_short_partial_dictionaries,
    R.string.saving_partial_words to R.string.saving_short_partial_words,
    R.string.saving_partial_clips to R.string.saving_short_partial_clips,
    R.string.saving_partial_switch to R.string.saving_short_switch_stays,
    R.string.saving_full_everything to R.string.saving_short_full_everything,
    R.string.saving_update_keyboards to R.string.saving_short_update_keyboards,
    R.string.saving_update_items to R.string.saving_short_update_items,
    R.string.saving_update_files to R.string.saving_short_kept,
    R.string.saving_update_words to R.string.saving_short_kept,
    R.string.saving_reset_keyboards to R.string.saving_short_reset_keyboards,
    R.string.saving_reset_items to R.string.saving_short_gone_unless_ticked,
    R.string.saving_reset_words to R.string.saving_short_gone_unless_ticked,
    R.string.saving_reset_clips to R.string.saving_short_gone_unless_ticked,
    R.string.saving_reset_clipboard to R.string.saving_short_gone_unless_ticked,
    R.string.saving_separate_on to R.string.saving_short_separate_keyboards,
    R.string.saving_separate_items to R.string.saving_short_separate_items,
    R.string.saving_separate_words to R.string.saving_short_separate_words,
)

private val kindWidth = 120.dp
private val cellWidth = 128.dp // (2–3 action columns fit beside the kinds on a device)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HowSavingWorksScreen(onClickBack: () -> Unit) {
    // one sideways scroll for the header and every row keeps the columns lined up; the kind column stays put
    val sideways = rememberScrollState()
    var open by remember { mutableStateOf<Pair<Int, SavingTab>?>(null) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.saving_guide_title)) }, navigationIcon = { BackButton(onClickBack) }) },
    ) { innerPadding ->
        LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
            // the Summary, scrolling with the page
            items(savingSummary) { row ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
                    Text(stringResource(row.kind), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(row.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            // the action names stay at the top of the grid while it scrolls
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row {
                        Spacer(Modifier.width(kindWidth))
                        Row(Modifier.horizontalScroll(sideways)) {
                            for (action in savingActions) Text(stringResource(action.title), Modifier.width(cellWidth).padding(8.dp),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    HorizontalDivider()
                }
            }
            items(savingKinds) { kind ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Text(stringResource(kind), Modifier.width(kindWidth).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(sideways)) {
                        for (action in savingActions) {
                            val rows = savingCell(kind, action)
                            if (rows.isEmpty())
                                Text(stringResource(R.string.saving_cell_not_applicable), Modifier.width(cellWidth).padding(8.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else
                                Text(stringResource(savingShort.getValue(rows.first())),
                                    Modifier.width(cellWidth).fillMaxHeight().clickable { open = kind to action }.padding(8.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    open?.let { (kind, action) ->
        ThreeButtonAlertDialog(
            onDismissRequest = { open = null },
            onConfirmed = { },
            title = { Text(stringResource(R.string.saving_cell_dialog_title, stringResource(kind), stringResource(action.title))) },
            content = { Text(savingCell(kind, action).map { stringResource(it) }.joinToString("\n\n")) },
            scrollContent = true,
            confirmButtonText = null,
            cancelButtonText = stringResource(android.R.string.ok),
        )
    }
}

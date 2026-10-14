// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton

/**
 * App settings > How saving works (owner 2026-10-11: "A tile in app settings should take us to a screen where there's
 * a list of all there elements layouts, themes, palettes, fonts, images, etc., with sub text showing what an action
 * does. Actions should be tabs up top"): read only. A kind an action doesn't apply to is left out of its tab.
 */
internal class SavingRow(@StringRes val kind: Int, @StringRes val text: Int)
internal class SavingTab(@StringRes val title: Int, val rows: List<SavingRow>)

internal val savingGuide: List<SavingTab> = listOf(
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
        SavingRow(R.string.saving_kind_learned, R.string.saving_backup_words),
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
        SavingRow(R.string.saving_kind_learned, R.string.saving_partial_words),
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
    )),
    SavingTab(R.string.saving_tab_separate, listOf(
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_on),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_off),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_first_delete),
        SavingRow(R.string.saving_kind_keyboards, R.string.saving_separate_reorder),
        SavingRow(R.string.saving_kind_layouts, R.string.saving_separate_items),
        SavingRow(R.string.saving_kind_palettes, R.string.saving_separate_items),
        SavingRow(R.string.saving_kind_learned, R.string.saving_separate_words),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowSavingWorksScreen(onClickBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.saving_guide_title)) }, navigationIcon = { BackButton(onClickBack) }) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                savingGuide.forEachIndexed { i, t -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(stringResource(t.title)) }) }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (row in savingGuide[tab.coerceAtMost(savingGuide.lastIndex)].rows) Column {
                    Text(stringResource(row.kind), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(row.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

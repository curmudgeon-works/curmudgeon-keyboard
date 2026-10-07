// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import helium314.keyboard.settings.scrollbar
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.GetIconOrEmpty
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// the three toolbars, in the order of the chips and of the list's sections
private val toolbars = listOf(Settings.PREF_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, Settings.PREF_PINNED_TOOLBAR_KEYS)
private val toolbarDefaults = listOf(Defaults.PREF_TOOLBAR_KEYS, Defaults.PREF_CLIPBOARD_TOOLBAR_KEYS, Defaults.PREF_PINNED_TOOLBAR_KEYS)

/** Whether [key] can be on toolbar [t] (index into [toolbars]): closing the history belongs to the clipboard toolbar. */
private fun allowed(key: String, t: Int) = if (key == ToolbarKey.CLOSE_HISTORY.name) t == 1 else true

/**
 * One list for the three toolbars' keys (was three reorder lists): each key has M(ain) / C(lipboard) / P(inned)
 * choices. The list opens sorted (main toolbar keys first, then clipboard-only, pinned-only, unused) and then stays
 * put while choices change (rows jumping away under the finger was worse); dragging sets the order, and each
 * toolbar's order is the list's order of its keys. Every change shows at once on the preview keyboard's open
 * toolbar; OK keeps it, Cancel puts the old lists back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarKeysPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    // stored lists: name -> on, in each toolbar's order
    fun read(t: Int) = prefs.getString(toolbars[t], toolbarDefaults[t])!!.split(Separators.ENTRY).mapNotNull {
        val (name, on) = it.split(Separators.KV).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
        name to on.toBoolean()
    }
    val summary = (0..2).map { t -> read(t).count { it.second } }
    Preference(name = setting.title, onClick = { showDialog = true },
        description = stringResource(R.string.toolbar_keys_summary, summary[0], summary[1], summary[2]))
    if (!showDialog) return

    // the preview keyboard (the screen's) comes up with the dialog; its toolbar opens, so the keys can be seen
    androidx.compose.runtime.DisposableEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val open = Runnable { helium314.keyboard.latin.suggestions.SuggestionStripView.showToolbarForPreview(true) }
        handler.postDelayed(open, 700) // once the keyboard is up
        onDispose { handler.removeCallbacks(open); helium314.keyboard.latin.suggestions.SuggestionStripView.showToolbarForPreview(false) }
    }
    val lists = remember { (0..2).map { read(it) } }
    val on = remember { (0..2).map { t -> mutableStateListOf<String>().apply { addAll(lists[t].filter { it.second }.map { it.first }) } } }
    fun section(key: String) = when { key in on[0] -> 0; key in on[1] -> 1; key in on[2] -> 2; else -> 3 }
    // the list's order: each toolbar's own order for its section, the unused keys in the main list's order
    val order = remember {
        val all = lists[0].map { it.first } + lists.flatMap { l -> l.map { it.first } }
        val keys = all.distinct().filter { name -> ToolbarKey.entries.any { it.name == name } }
        mutableStateListOf<String>().apply {
            addAll(keys.sortedWith(compareBy({ section(it) }, { k ->
                val s = section(k); if (s < 3) on[s].indexOf(k) else keys.indexOf(k) })))
        }
    }
    // what the three prefs held when the dialog opened (null = unset), for Cancel
    val opened = remember { toolbars.map { prefs.getString(it, null) } }
    var confirmed by remember { mutableStateOf(false) }
    fun write() {
        prefs.edit {
            for (t in 0..2) {
                val keys = order.filter { allowed(it, t) }
                putString(toolbars[t], keys.joinToString(Separators.ENTRY) { it + Separators.KV + (it in on[t]) })
            }
        }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        helium314.keyboard.latin.suggestions.SuggestionStripView.showToolbarForPreview(true) // (a reload closes it)
    }
    val listState = rememberLazyListState()
    val dragState = rememberReorderableLazyListState(listState) { from, to ->
        order.add(to.index, order.removeAt(from.index))
        write()
    }
    ThreeButtonAlertDialog(
        onDismissRequest = {
            if (!confirmed) { // Cancel / back / outside: the lists as they were
                prefs.edit { toolbars.forEachIndexed { t, key -> if (opened[t] == null) remove(key) else putString(key, opened[t]) } }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
            showDialog = false
        },
        title = { Text(setting.title) },
        neutralButtonText = stringResource(R.string.button_default),
        // (and closes: review 2026-10-06, OK after it wrote the lists as they were)
        onNeutral = { confirmed = true; prefs.edit { toolbars.forEach { remove(it) } }; KeyboardSwitcher.getInstance().setThemeNeedsReload(); showDialog = false },
        onConfirmed = { confirmed = true; write() },
        content = {
            Column {
                Text(stringResource(R.string.toolbar_keys_legend), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                // a short list that scrolls inside: the dialog stays small so the preview keyboard's toolbar shows above it
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 220.dp).scrollbar(listState)) {
                    items(order.toList(), key = { it }) { key ->
                        ReorderableItem(state = dragState, key = key) { dragging ->
                            val elevation by animateDpAsState(if (dragging) 4.dp else 0.dp)
                            Surface(shadowElevation = elevation) {
                                Row(Modifier.longPressDraggableHandle().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(R.drawable.ic_drag_indicator), null, Modifier.padding(end = 4.dp),
                                        MaterialTheme.colorScheme.onSurfaceVariant)
                                    KeyboardIconsSet.instance.GetIconOrEmpty(key)
                                    val text = key.lowercase().getStringResourceOrName("", ctx).let {
                                        if (it != key.lowercase()) it else key.lowercase().getStringResourceOrName("popup_keys_", ctx) }
                                    Text(text, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                                    MultiChoiceSegmentedButtonRow {
                                        listOf(R.string.toolbar_keys_main, R.string.toolbar_keys_clipboard, R.string.toolbar_keys_pinned)
                                            .forEachIndexed { t, label ->
                                                SegmentedButton(
                                                    checked = key in on[t],
                                                    enabled = allowed(key, t),
                                                    onCheckedChange = { checked ->
                                                        if (checked) on[t].add(key) else on[t].remove(key)
                                                        write()
                                                    },
                                                    shape = SegmentedButtonDefaults.itemShape(t, 3),
                                                    icon = { },
                                                    label = { Text(stringResource(label)) },
                                                )
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

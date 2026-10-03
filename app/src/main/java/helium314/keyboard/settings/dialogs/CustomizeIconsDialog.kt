// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import helium314.keyboard.settings.rememberPrefSnapshot
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.customIconNames
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.latin.utils.previewDark
import kotlinx.serialization.json.Json
import androidx.core.content.edit
import helium314.keyboard.settings.GetIconOrEmpty
import helium314.keyboard.settings.painterResourceCompat

@Composable
fun CustomizeIconsDialog(
    prefKey: String,
    onDismissRequest: () -> Unit,
) {
    val state = rememberLazyListState()
    val ctx = LocalContext.current
    var iconsAndNames by remember { mutableStateOf(
        KeyboardIconsSet.getAllIcons(ctx).keys.map { iconName ->
            val name = iconName.getStringResourceOrName("", ctx)
            if (name == iconName) iconName to iconName.getStringResourceOrName("label_", ctx)
            else iconName to name
        }.sortedBy { it.second }
    ) }
    fun reloadItem(iconName: String) {
        iconsAndNames = iconsAndNames.map { item ->
            if (item.first == iconName) {
                item.first to if (item.second.endsWith(" ")) item.second.trimEnd() else item.second + " "
            }
            else item
        }
    }
    var showIconDialog: Pair<String, String>? by rememberSaveable { mutableStateOf(null) }
    var showDeletePrefConfirmDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = ctx.prefs()
    // every change shows on the live keyboard at once; Cancel puts back what was set when the dialog opened
    val snapshot = rememberPrefSnapshot(prefs, listOf(prefKey))
    var confirmed by remember { mutableStateOf(false) }
    fun writeIcons(change: (MutableMap<String, String>) -> Unit) {
        runCatching {
            val icons = customIconNames(prefs).toMutableMap()
            change(icons)
            if (icons.isEmpty()) prefs.edit { remove(prefKey) }
            else prefs.edit { putString(prefKey, Json.encodeToString(icons)) }
        }
        KeyboardIconsSet.instance.loadIcons(ctx)
        KeyboardIconsSet.needsReload = true
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }
    fun reloadIcons() {
        KeyboardIconsSet.instance.loadIcons(ctx)
        KeyboardIconsSet.needsReload = true
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }
    ThreeButtonAlertDialog(
        onDismissRequest = {
            if (!confirmed && snapshot.restore()) reloadIcons()
            onDismissRequest()
        },
        onConfirmed = { confirmed = true },
        neutralButtonText = if (prefs.contains(prefKey)) stringResource(R.string.button_default) else null,
        onNeutral = { showDeletePrefConfirmDialog = true },
        title = { Text(stringResource(R.string.customize_icons)) },
        content = {
            // short enough that the preview keyboard stays in view below
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 220.dp)) {
                items(iconsAndNames, key = { it.second }) { (iconName, displayName) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showIconDialog = iconName to displayName }
                    ) {
                        KeyboardIconsSet.instance.GetIconOrEmpty(iconName)
                        Text(displayName, Modifier.weight(1f))
                    }
                }
            }
        },
    )
    if (showIconDialog != null) {
        val iconName = showIconDialog!!.first
        val allIcons = KeyboardIconsSet.getAllIcons(ctx)
        val iconsForName = allIcons[iconName].orEmpty()
        val iconsSet = mutableSetOf<Int>()
        iconsSet.addAll(iconsForName)
        KeyboardIconsSet.getAllIcons(ctx).forEach { iconsSet.addAll(it.value) }
        val icons = iconsSet.toList()
        val initialIcon = KeyboardIconsSet.instance.iconIds[iconName]
        var selectedIcon by rememberSaveable { mutableStateOf(initialIcon) }
        // this slot's saved name when the grid opened, for Cancel
        val slotInitial = remember(iconName) { customIconNames(prefs)[iconName] }
        var slotConfirmed by remember(iconName) { mutableStateOf(false) }

        val gridState = rememberLazyGridState()
        LaunchedEffect(initialIcon) {
            val index = icons.indexOf(initialIcon)
            if (index != -1) gridState.animateScrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        }
        ThreeButtonAlertDialog(
            onDismissRequest = {
                if (!slotConfirmed && customIconNames(prefs)[iconName] != slotInitial)
                    writeIcons { if (slotInitial == null) it.remove(iconName) else it[iconName] = slotInitial }
                reloadItem(iconName)
                showIconDialog = null
            },
            onConfirmed = { slotConfirmed = true; reloadItem(iconName) },
            neutralButtonText = if (customIconNames(prefs).contains(iconName)) stringResource(R.string.button_default) else null,
            onNeutral = {
                slotConfirmed = true
                showIconDialog = null
                writeIcons { it.remove(iconName) }
                reloadItem(iconName)
            },
            title = { Text(showIconDialog!!.second) },
            content = {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 64.dp),
                    state = gridState,
                    modifier = Modifier.heightIn(max = 220.dp)
                ) {
                    items(icons, key = { it }) { resId ->
                        val color = if (resId == selectedIcon) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        CompositionLocalProvider(
                            LocalContentColor provides color
                        ) {
                            Box(
                                Modifier.size(40.dp).clickable {
                                    selectedIcon = resId
                                    // shows on the live keyboard right away
                                    writeIcons { icons -> icons[iconName] = ctx.resources.getResourceEntryName(resId) }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painterResourceCompat(resId), null, Modifier.fillMaxSize(0.8f))
                            }
                        }
                    }
                }
            },
        )
    }
    if (showDeletePrefConfirmDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDeletePrefConfirmDialog = false },
            onConfirmed = {
                showDeletePrefConfirmDialog = false
                confirmed = true
                onDismissRequest()
                prefs.edit { remove(prefKey) } // reset: all icons back to the style's own
                reloadIcons()
            },
            content = { Text(stringResource(R.string.customize_icons_reset_message)) }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        CustomizeIconsDialog(
            prefKey = "",
            onDismissRequest = { },
        )
    }
}

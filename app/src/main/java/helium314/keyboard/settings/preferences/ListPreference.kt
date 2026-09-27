package helium314.keyboard.settings.preferences

import helium314.keyboard.settings.rememberPrefSnapshot
import android.content.SharedPreferences
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ListPickerDialog

@Composable
/** [items] are displayString to value */
fun <T: Any> ListPreference(
    setting: Setting,
    items: List<Pair<String, T>>,
    default: T,
    live: Boolean = false, // a tap applies the value at once (the live keyboard shows it); OK keeps it, Cancel puts the old one back
    itemTrailing: (@Composable (Pair<String, T>) -> Unit)? = null, // shown at the end of each row, e.g. a preview of the choice
    previewKeyboard: Boolean = true, // off: the rows preview the choice themselves, the keyboard needn't come up
    onChanged: (T) -> Unit = { }
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = LocalContext.current.prefs()
    val selected = items.firstOrNull { it.second == getPrefOfType(prefs, setting.key, default) }
    // what was set when the dialog opened, for Cancel in live mode (unset stays unset)
    val snapshot = rememberPrefSnapshot(prefs, listOf(setting.key), showDialog)
    var confirmed by remember(showDialog) { mutableStateOf(false) }
    fun apply(value: T) {
        if (value == getPrefOfType(prefs, setting.key, default)) return
        putPrefOfType(prefs, setting.key, value)
        onChanged(value)
    }
    Preference(
        name = setting.title,
        description = selected?.first,
        onClick = { showDialog = true }
    )
    if (showDialog) {
        ListPickerDialog(
            onDismissRequest = {
                if (live && !confirmed && snapshot.restore()) onChanged(getPrefOfType(prefs, setting.key, default))
                showDialog = false
            },
            items = items,
            confirmImmediately = !live,
            onItemHighlighted = if (live) { { apply(it.second) } } else null,
            onItemSelected = { confirmed = true; apply(it.second) },
            selectedItem = selected,
            title = { Text(setting.title) },
            getItemName = { it.first },
            trailing = itemTrailing,
            summonKeyboard = previewKeyboard,
        )
    }
}

@Suppress("UNCHECKED_CAST")
fun <T: Any> getPrefOfType(prefs: SharedPreferences, key: String, default: T): T =
    when (default) {
        is String -> prefs.getString(key, default)
        is Int -> prefs.getInt(key, default)
        is Long -> prefs.getLong(key, default)
        is Float -> prefs.getFloat(key, default)
        is Boolean -> prefs.getBoolean(key, default)
        else -> throw IllegalArgumentException("unknown type ${default.javaClass}")
    } as T

private fun <T: Any> putPrefOfType(prefs: SharedPreferences, key: String, value: T) =
    prefs.edit {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            else -> throw IllegalArgumentException("unknown type ${value.javaClass}")
        }
    }

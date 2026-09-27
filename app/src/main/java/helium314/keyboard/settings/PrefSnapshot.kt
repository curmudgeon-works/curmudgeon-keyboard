// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.edit
import helium314.keyboard.latin.settings.KeyboardProfiles

/**
 * What some preferences held when a dialog opened, for its Cancel: the dialogs show every change on the live
 * keyboard at once, OK keeps it, Cancel puts back these values (a key that wasn't set is removed again).
 * The dialog does its own reload, and anything that isn't a preference (a font file, say) stays with it.
 */
class PrefSnapshot(private val prefs: SharedPreferences, keys: Collection<String>) {
    private val values: Map<String, Any?> = prefs.all.let { all -> keys.associateWith { all[it] } }

    /** The value [key] had, null when it wasn't set. */
    operator fun get(key: String): Any? = values[key]

    fun changed(): Boolean = prefs.all.let { all -> values.any { (key, value) -> all[key] != value } }

    /** Puts the values back; true when anything had changed, so the caller reloads. */
    fun restore(): Boolean {
        if (!changed()) return false
        prefs.edit { values.forEach { (key, value) -> if (value == null) remove(key) else KeyboardProfiles.put(this, key, value) } }
        return true
    }
}

/** A [PrefSnapshot] taken when the dialog first composes, or again whenever [key] changes (e.g. the dialog reopening). */
@Composable
fun rememberPrefSnapshot(prefs: SharedPreferences, keys: Collection<String>, key: Any? = Unit): PrefSnapshot =
    remember(key) { PrefSnapshot(prefs, keys) }

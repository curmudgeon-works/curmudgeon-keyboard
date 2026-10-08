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
class PrefSnapshot(private val prefs: SharedPreferences, private val values: Map<String, Any?>) {
    constructor(prefs: SharedPreferences, keys: Collection<String>) : this(prefs, prefs.all.let { all -> keys.associateWith { all[it] } })

    internal fun saved(): HashMap<String, Any?> = HashMap(values.mapValues { (_, v) -> if (v is Set<*>) HashSet(v) else v })

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

/** A [PrefSnapshot] taken when the dialog first composes, or again whenever [key] changes (e.g. the dialog reopening);
 *  kept over a rotation or dark-mode switch, so Cancel still puts back what was there when the dialog opened. */
@Composable
fun rememberPrefSnapshot(prefs: SharedPreferences, keys: Collection<String>, key: Any? = Unit): PrefSnapshot =
    androidx.compose.runtime.saveable.rememberSaveable(key, saver = androidx.compose.runtime.saveable.Saver<PrefSnapshot, HashMap<String, Any?>>(
        save = { it.saved() },
        restore = { @Suppress("UNCHECKED_CAST") PrefSnapshot(prefs, it as Map<String, Any?>) },
    )) { PrefSnapshot(prefs, keys) }

/**
 * Like [PrefSnapshot], but of what is stored for the set [setId] itself: its own value, its "at the default" mark, or
 * neither (it follows the shared value). Cancel then leaves a keyboard that followed the shared value following it;
 * reading the values through the keyboard's view gave the shared value, and putting that back made it the keyboard's
 * own (review 2026-10-07, finding 10).
 */
class RawPrefSnapshot(private val real: SharedPreferences, private val setId: Int, keys: Collection<String>) {
    private val stored: Map<String, Any?> = keys.flatMap { rawKeys(it) }.associateWith { real.all[it] }

    private fun rawKeys(key: String): List<String> {
        val own = KeyboardProfiles.prefixedKey(setId, key)
        return if (own == key) listOf(key) else listOf(own, KeyboardProfiles.prefixedKey(setId, KeyboardProfiles.TOMBSTONE + key))
    }

    /** Puts back what was stored; true when anything had changed, so the caller reloads. */
    fun restore(): Boolean {
        val now = real.all
        if (stored.all { (k, v) -> now[k] == v }) return false
        real.edit { stored.forEach { (k, v) -> if (v == null) remove(k) else KeyboardProfiles.put(this, k, v) } }
        return true
    }
}

/** A [RawPrefSnapshot] of the set the settings screens edit now, taken when the dialog first composes. */
@Composable
fun rememberRawPrefSnapshot(ctx: android.content.Context, keys: Collection<String>): RawPrefSnapshot = remember {
    RawPrefSnapshot(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx), PrefsDraft.currentSetId(ctx), keys)
}

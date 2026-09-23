// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.SharedPreferences
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.internal.KeySpecParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-key long-press popups the user rearranged: key label -> the popup labels to show, in order. Keys without
 * an entry keep the generated popups. Stored per keyboard (a plain pref, so per profile with separate settings).
 */
object KeyPopupOverrides {
    const val PREF = "key_popups"

    fun load(prefs: SharedPreferences): Map<String, List<String>> {
        val json = prefs.getString(PREF, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key ->
                val arr = obj.getJSONArray(key)
                List(arr.length()) { arr.getString(it) }
            }
        } catch (e: Exception) { emptyMap() }
    }

    fun save(prefs: SharedPreferences, overrides: Map<String, List<String>>) {
        if (overrides.isEmpty()) { prefs.edit().remove(PREF).apply(); return }
        val obj = JSONObject()
        for ((key, labels) in overrides) obj.put(key, JSONArray(labels))
        prefs.edit().putString(PREF, obj.toString()).apply()
    }

    fun set(prefs: SharedPreferences, keyLabel: String, labels: List<String>?) {
        val all = load(prefs).toMutableMap()
        if (labels == null) all.remove(keyLabel) else all[keyLabel] = labels
        save(prefs, all)
    }

    /**
     * The generated popup specs of a key, rearranged as the user wants: the chosen labels in their order, dropping
     * everything else. Null when the key has no override. Matching is by the spec's label.
     */
    /** Override key for a key: symbol-page keys are kept apart from same-looking keys of the letter page. */
    @JvmStatic
    fun overrideKey(elementId: Int, keyLabel: String): String = when (elementId) {
        KeyboardId.ELEMENT_SYMBOLS -> "symbols:$keyLabel"
        KeyboardId.ELEMENT_SYMBOLS_SHIFTED -> "symbols_shifted:$keyLabel"
        KeyboardId.ELEMENT_NUMPAD -> "numpad:$keyLabel"
        else -> keyLabel
    }

    @JvmStatic
    fun apply(overrides: Map<String, List<String>>, elementId: Int, keyLabel: String?, specs: Array<String>?): Array<String>? {
        if (keyLabel == null) return null
        val wanted = overrides[overrideKey(elementId, keyLabel)] ?: return null
        val byLabel = LinkedHashMap<String, String>()
        for (spec in specs.orEmpty()) byLabel.putIfAbsent(KeySpecParser.getLabel(spec) ?: spec, spec)
        // a label the generator didn't produce (from the full accent pool, or the user's own) becomes a plain key spec
        return wanted.map { byLabel[it] ?: it.replace("\\", "\\\\").replace("|", "\\|") }.toTypedArray()
    }
}

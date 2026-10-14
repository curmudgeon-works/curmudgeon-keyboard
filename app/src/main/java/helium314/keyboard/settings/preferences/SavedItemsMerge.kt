// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets

/**
 * Restoring some keyboards brings in the backup's saved items (owner 2026-10-11: "partial restore -- keep everything
 * existing and also add whatever was in the backup", "Items inside items must be renamed together", "Next free
 * number"): every popup set, Layout and theme of the backup comes in unless the phone has one with the same content
 * (any name; then the phone's is used). A name the phone uses already (its own items or a built-in) gets the next free
 * number, "Sunset 2", when [asCopies] (the restore dialog's tick, on by default); off, the phone's same-named one is used.
 * Inner items first (popup sets), then the outer ones (Layouts) with their references rewritten, so a second restore
 * of the same backup adds nothing. The result maps each kind's backup names to the phone's.
 */
internal object SavedItemsMerge {
    class Renames(val popupSets: Map<String, String>, val layouts: Map<String, String>, val themes: Map<String, String>) {
        /** A restored keyboard's settings ([values], plain keys) naming its items as they are called on the phone now. */
        fun apply(values: Map<String, Any?>): Map<String, Any?> = values.mapValues { (key, value) ->
            val map = when (key) {
                KeyPopupOverrides.PREF_SELECTED_SET -> popupSets
                LayoutPresets.PREF_SELECTED -> layouts
                AppearanceLooks.PREF_SELECTED -> themes
                else -> null
            }
            (value as? String)?.let { map?.get(it) } ?: value
        }
    }

    /** The next free name for [name]: itself if free, else "Name 2", "Name 3" … */
    fun freeName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        return generateSequence(2) { it + 1 }.map { "$name $it" }.first { it !in taken }
    }

    fun merge(ctx: Context, real: SharedPreferences, backup: Map<String, Any?>, asCopies: Boolean): Renames {
        // the backup's lists, read as the phone reads its own (a scratch store, gone afterwards)
        val scratch = ctx.getSharedPreferences("restore_saved_items", Context.MODE_PRIVATE)
        scratch.edit(commit = true) {
            clear()
            for (key in listOf(KeyPopupOverrides.PREF_SETS, LayoutPresets.PREF, AppearanceLooks.PREF))
                (backup[key] as? String)?.let { putString(key, it) }
        }
        try {
            // popup sets: by everything but the name
            fun content(s: KeyPopupOverrides.UserSet) = listOf(s.morePopups, s.symbolsLayout, s.overrides, s.symbolMap, s.popupOrder, s.atDefault)
            val phoneSets = KeyPopupOverrides.loadSets(real).toMutableList()
            val setNames = HashMap<String, String>()
            for (set in KeyPopupOverrides.loadSets(scratch)) {
                phoneSets.firstOrNull { content(it) == content(set) }?.let { setNames[set.name] = it.name; continue }
                val taken = phoneSets.map { it.name }.toSet()
                if (set.name in taken && !asCopies) { setNames[set.name] = set.name; continue } // (the phone's is used)
                val name = freeName(set.name, taken)
                phoneSets += KeyPopupOverrides.UserSet(name, set.morePopups, set.symbolsLayout, set.overrides, set.symbolMap, set.popupOrder, set.atDefault)
                setNames[set.name] = name
            }
            KeyPopupOverrides.saveSets(real, phoneSets)

            // Layouts: their popup set named as on the phone now, then by content (values)
            val builtInLayouts = LayoutPresets.builtIn(ctx).map { it.name }.toSet()
            val phoneLayouts = LayoutPresets.load(real).toMutableList()
            val layoutNames = HashMap<String, String>()
            for (preset in LayoutPresets.load(scratch)) {
                val values = preset.values.mapValues { (key, value) ->
                    if (key == KeyPopupOverrides.PREF_SELECTED_SET) (value as? String)?.let { setNames[it] ?: it } else value }
                phoneLayouts.firstOrNull { it.values == values }?.let { layoutNames[preset.name] = it.name; continue }
                val taken = phoneLayouts.map { it.name }.toSet() + builtInLayouts
                if (preset.name in taken && !asCopies) { layoutNames[preset.name] = preset.name; continue }
                val name = freeName(preset.name, taken)
                phoneLayouts += LayoutPresets.Preset(name, values, remembered = preset.remembered)
                layoutNames[preset.name] = name
            }
            LayoutPresets.save(real, phoneLayouts)

            // themes, by content (values)
            val builtInThemes = AppearanceLooks.builtIn(ctx).map { it.name }.toSet()
            val phoneThemes = AppearanceLooks.load(real).toMutableList()
            val themeNames = HashMap<String, String>()
            for (look in AppearanceLooks.load(scratch)) {
                phoneThemes.firstOrNull { it.values == look.values }?.let { themeNames[look.name] = it.name; continue }
                val taken = phoneThemes.map { it.name }.toSet() + builtInThemes
                if (look.name in taken && !asCopies) { themeNames[look.name] = look.name; continue }
                val name = freeName(look.name, taken)
                phoneThemes += AppearanceLooks.Look(name, look.values, look.remembered)
                themeNames[look.name] = name
            }
            AppearanceLooks.save(real, phoneThemes, now = true)
            return Renames(setNames, layoutNames, themeNames)
        } finally {
            scratch.edit(commit = true) { clear() }
        }
    }
}

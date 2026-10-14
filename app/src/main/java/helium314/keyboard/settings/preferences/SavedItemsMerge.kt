// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SavedItemDefaults

/**
 * Restoring some keyboards brings in the backup's saved items (owner 2026-10-11: "partial restore -- keep everything
 * existing and also add whatever was in the backup", "Items inside items must be renamed together", "Next free
 * number"): every popup set, Layout and theme of the backup comes in unless the phone has one with the same content
 * (any name; then the phone's is used). A name the phone uses already (its own items or a built-in) gets the next free
 * number, "Sunset 2", when [asCopies] (the restore dialog's tick, on by default); off, it's not brought in and nothing
 * names it: the keyboards that chose it keep its look and show "Custom", a Layout that held such a popup set keeps its
 * popups (owner 2026-10-11 chose this "option 2": names never point at other content; before, they named the phone's
 * same-named item, "Custom · from Sunset" on a look that isn't Sunset).
 * Inner items first (popup sets), then the outer ones (Layouts) with their references rewritten, so a second restore
 * of the same backup adds nothing. The result maps each kind's backup names to the phone's. The custom key layouts that
 * came in under other names (restoreLayoutFiles) are named so in the Layouts, and in the keyboards' defaults for each type.
 */
internal object SavedItemsMerge {
    class Renames(val popupSets: Map<String, String>, val layouts: Map<String, String>, val themes: Map<String, String>,
                  val fonts: Map<String, String> = emptyMap(), val keyLayouts: Map<LayoutType, Map<String, String>> = emptyMap(),
                  val palettes: Map<String, String> = emptyMap(),
                  /** backup names not brought in (taken by other content, copies off), by the selected-name key */
                  val notBrought: Map<String, Set<String>> = emptyMap()) {
        /** A restored keyboard's settings ([values], plain keys) naming its items as they are called on the phone now
         *  (a choice of one not brought in left out: it names none). */
        fun apply(values: Map<String, Any?>): Map<String, Any?> = RestoredCustomCopies.renamePalettes(values, palettes)
            .filter { (key, value) -> !names(key, value) }.mapValues { (key, value) ->
            val map = when (key) {
                KeyPopupOverrides.PREF_SELECTED_SET -> popupSets
                LayoutPresets.PREF_SELECTED -> layouts
                AppearanceLooks.PREF_SELECTED -> themes
                else -> defaultLayoutType(key)?.let { keyLayouts[it] }
            }
            if (key in helium314.keyboard.keyboard.FontLibrary.fontPrefs) return@mapValues renamedFont(value, fonts)
            (value as? String)?.let { map?.get(it) } ?: value
        }

        private fun names(key: String, value: Any?) = value is String && value in notBrought[key].orEmpty()

        /** The kinds whose item the keyboard ([values], before [apply]) chose wasn't brought in: it gets its Custom label. */
        fun customKinds(values: Map<String, Any?>): List<helium314.keyboard.settings.CustomLabels.Kind> = listOfNotNull(
            helium314.keyboard.settings.CustomLabels.Kind.POPUPS.takeIf { names(KeyPopupOverrides.PREF_SELECTED_SET, values[KeyPopupOverrides.PREF_SELECTED_SET]) },
            helium314.keyboard.settings.CustomLabels.Kind.LAYOUT.takeIf { names(LayoutPresets.PREF_SELECTED, values[LayoutPresets.PREF_SELECTED]) },
            helium314.keyboard.settings.CustomLabels.Kind.THEME.takeIf { names(AppearanceLooks.PREF_SELECTED, values[AppearanceLooks.PREF_SELECTED]) })
    }

    /** The layout type whose default [key] is (Settings.PREF_LAYOUT_PREFIX + type), or null. */
    private fun defaultLayoutType(key: String): LayoutType? =
        if (!key.startsWith(Settings.PREF_LAYOUT_PREFIX)) null else LayoutType.entries.firstOrNull { key == Settings.PREF_LAYOUT_PREFIX + it.name }

    /** A Layout's values naming the custom key layouts as they came in ([keyLayouts], by type): its keys ("layout:<type>")
     *  and its defaults for the types. */
    private fun renamedKeyLayouts(values: Map<String, Any?>, keyLayouts: Map<LayoutType, Map<String, String>>): Map<String, Any?> =
        if (keyLayouts.isEmpty()) values else values.mapValues { (key, value) ->
            val type = defaultLayoutType(key) ?: LayoutType.entries.firstOrNull { key == "layout:" + it.name }
            (value as? String)?.let { type?.let { t -> keyLayouts[t]?.get(it) } } ?: value
        }

    /** A font choice ("font:<file>") naming [fonts]' new name. */
    fun renamedFont(value: Any?, fonts: Map<String, String>): Any? {
        val name = (value as? String)?.takeIf { it.startsWith(helium314.keyboard.keyboard.FontLibrary.PREFIX) }
            ?.removePrefix(helium314.keyboard.keyboard.FontLibrary.PREFIX) ?: return value
        return fonts[name]?.let { helium314.keyboard.keyboard.FontLibrary.PREFIX + it } ?: value
    }

    /** A theme's values with its fonts and library pictures named as they came in ([fonts], [pictures]). */
    private fun renamedFiles(values: Map<String, Any?>, fonts: Map<String, String>, pictures: Map<String, String>): Map<String, Any?> =
        values.mapValues { (key, value) -> when {
            key in helium314.keyboard.keyboard.FontLibrary.fontPrefs -> renamedFont(value, fonts)
            key == AppearanceLooks.PICTURES && pictures.isNotEmpty() && (value as? String)?.startsWith("lib:") == true -> runCatching {
                val o = org.json.JSONObject(value.removePrefix("lib:"))
                for (slot in o.keys()) o.optJSONObject(slot)?.let { s -> pictures[s.optString("p")]?.let { s.put("p", it) } }
                "lib:$o"
            }.getOrDefault(value)
            else -> value
        } }

    /** The next free name for [name]: itself if free, else "Name 2", "Name 3" … */
    fun freeName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        return generateSequence(2) { it + 1 }.map { "$name $it" }.first { it !in taken }
    }

    /** [keyboards]: the restored keyboards' settings as in the backup (plain keys), [phone]: the phone's stored settings
     *  without the sets the restore replaces, for the kept palettes' numbers (see [RestoredCustomCopies]). Not [write]:
     *  the renames only, the phone's lists untouched (review 2026-10-11 R3: the restore question compares with them). */
    fun merge(ctx: Context, real: SharedPreferences, backup: Map<String, Any?>, asCopies: Boolean,
              fonts: Map<String, String> = emptyMap(), pictures: Map<String, String> = emptyMap(),
              keyLayouts: Map<LayoutType, Map<String, String>> = emptyMap(),
              keyboards: Collection<Map<String, Any?>> = emptyList(), phone: Map<String, Any?> = real.all, write: Boolean = true): Renames {
        // the backup's lists, read as the phone reads its own (a scratch store, gone afterwards)
        val scratch = ctx.getSharedPreferences(if (write) "restore_saved_items" else "restore_saved_items_dry", Context.MODE_PRIVATE)
        scratch.edit(commit = true) {
            clear()
            for (key in listOf(KeyPopupOverrides.PREF_SETS, LayoutPresets.PREF, AppearanceLooks.PREF))
                (backup[key] as? String)?.let { putString(key, it) }
        }
        try {
            // popup sets: by everything but the name (a field at its default keeps its remembered value in place, so
            // that is compared too)
            fun content(s: KeyPopupOverrides.UserSet) = listOf(s.morePopups, s.symbolsLayout, s.overrides, s.symbolMap, s.popupOrder, s.atDefault)
            val phoneSets = KeyPopupOverrides.loadSets(real).toMutableList()
            val setNames = HashMap<String, String>()
            val setsNotBrought = HashSet<String>()
            for (set in KeyPopupOverrides.loadSets(scratch)) {
                phoneSets.firstOrNull { content(it) == content(set) }?.let { setNames[set.name] = it.name; continue }
                val taken = phoneSets.map { it.name }.toSet()
                if (set.name in taken && !asCopies) { setsNotBrought += set.name; continue }
                val name = freeName(set.name, taken)
                phoneSets += KeyPopupOverrides.UserSet(name, set.morePopups, set.symbolsLayout, set.overrides, set.symbolMap, set.popupOrder, set.atDefault)
                setNames[set.name] = name
            }
            if (write) KeyPopupOverrides.saveSets(real, phoneSets)

            // Layouts: their popup set and custom key layouts named as on the phone now, then by content (values, and the remembered ones: SavedItemDefaults.sameContent)
            val builtInLayouts = LayoutPresets.builtIn(ctx).map { it.name }.toSet()
            val phoneLayouts = LayoutPresets.load(real).toMutableList()
            val layoutNames = HashMap<String, String>()
            val layoutsNotBrought = HashSet<String>()
            for (preset in LayoutPresets.load(scratch)) {
                // (a popup set not brought in: no name, its popups kept)
                val values = renamedKeyLayouts(preset.values, keyLayouts)
                    .filter { (key, value) -> key != KeyPopupOverrides.PREF_SELECTED_SET || value !in setsNotBrought }.mapValues { (key, value) ->
                    if (key == KeyPopupOverrides.PREF_SELECTED_SET) (value as? String)?.let { setNames[it] ?: it } else value }
                phoneLayouts.firstOrNull { SavedItemDefaults.sameContent(it.values, it.remembered, values, preset.remembered) }?.let { layoutNames[preset.name] = it.name; continue }
                val taken = phoneLayouts.map { it.name }.toSet() + builtInLayouts
                if (preset.name in taken && !asCopies) { layoutsNotBrought += preset.name; continue }
                val name = freeName(preset.name, taken)
                phoneLayouts += LayoutPresets.Preset(name, values, remembered = preset.remembered)
                layoutNames[preset.name] = name
            }
            if (write) LayoutPresets.save(real, phoneLayouts)

            // themes, by content (values and remembered, likewise)
            val builtInThemes = AppearanceLooks.builtIn(ctx).map { it.name }.toSet()
            val phoneThemes = AppearanceLooks.load(real).toMutableList()
            val themeNames = HashMap<String, String>()
            val themesNotBrought = HashSet<String>()
            // kept palette copies ("~custom:N") the phone uses for other colours: the next free number, in the keyboards
            // and the themes together (review C4)
            val backupLooks = AppearanceLooks.load(scratch)
            val palettes = RestoredCustomCopies.paletteRenames(phone, phoneThemes.map { it.values }, keyboards + backupLooks.map { it.values })
            for (backupLook in backupLooks) {
                // (its fonts, pictures and kept palettes named as they came in, before it's compared)
                val look = AppearanceLooks.Look(backupLook.name,
                    RestoredCustomCopies.renamePalettes(renamedFiles(backupLook.values, fonts, pictures), palettes), backupLook.remembered)
                phoneThemes.firstOrNull { SavedItemDefaults.sameContent(it.values, it.remembered, look.values, look.remembered) }?.let { themeNames[look.name] = it.name; continue }
                val taken = phoneThemes.map { it.name }.toSet() + builtInThemes
                if (look.name in taken && !asCopies) { themesNotBrought += look.name; continue }
                val name = freeName(look.name, taken)
                phoneThemes += AppearanceLooks.Look(name, look.values, look.remembered)
                themeNames[look.name] = name
            }
            if (write) AppearanceLooks.save(real, phoneThemes, now = true)
            return Renames(setNames, layoutNames, themeNames, fonts, keyLayouts, palettes, mapOf(KeyPopupOverrides.PREF_SELECTED_SET to setsNotBrought,
                LayoutPresets.PREF_SELECTED to layoutsNotBrought, AppearanceLooks.PREF_SELECTED to themesNotBrought))
        } finally {
            scratch.edit(commit = true) { clear() }
        }
    }
}

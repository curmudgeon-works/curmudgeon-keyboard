// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * A "Layout" is everything on the Layout & Typing screen saved under a name, as a theme is for Appearance: the keys of
 * every layout type (a custom one's content too, so it outlives the custom layout), and the settings of the screen's
 * groups (shape, toolbar, typing, backspace, undo). Not in a Layout: the popups (their own sets) and what is app-wide
 * (clipboard history). Saved Layouts are one JSON list, shared by all keyboards.
 */
object LayoutPresets {
    const val PREF = "layout_presets"
    const val PREF_SELECTED = "layout_preset_selected"
    private const val LAYOUT = "layout:" // + type: the keys' layout name (no entry: the default)
    private const val LAYOUT_TEXT = "layoutText:" // + type: a custom layout's content

    class Preset(val name: String, val values: Map<String, Any?>)

    // the popups' own (their sets), the keyboard list, and what an entry of its own covers
    private val notInPresets = setOf(Settings.PREF_SHOW_TLD_POPUP_KEYS, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        Settings.PREF_SYMBOL_POPUP_MAP, "key_popups", "key_popup_set_selected", "key_popup_sets", PREF_SELECTED,
        Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE, "keyboard_profile_ids")
    private val prefixes = LayoutDraft.prefixes.filter { it != Settings.PREF_SAVED_APP_SUBTYPE_PREFIX }

    fun inScope(key: String) = !KeyboardProfiles.isGlobal(key) && key !in notInPresets && !key.startsWith(KeyboardProfiles.TOMBSTONE)
        && (key in LayoutDraft.keys || prefixes.any { key.startsWith(it) })

    /** The settings part as it is now (plain keys, the edited keyboard's set). */
    fun current(prefs: SharedPreferences): Map<String, Any?> = prefs.all.filterKeys { inScope(it) }

    /** What a Layout saves for [keyboard]: its keys, the settings set now, and every other one as null (its default). */
    fun snapshot(ctx: Context, keyboard: SettingsSubtype): Map<String, Any?> {
        val values = HashMap<String, Any?>()
        LayoutDraft.keys.filter { inScope(it) }.forEach { values[it] = null }
        values.putAll(current(ctx.prefs()))
        for (type in LayoutType.entries) {
            val name = keyboard.layoutName(type) ?: continue
            values[LAYOUT + type.name] = name
            if (LayoutUtilsCustom.isCustomLayout(name))
                LayoutUtilsCustom.getLayoutFile(name, type, ctx).takeIf { it.isFile }?.let { values[LAYOUT_TEXT + type.name] = it.readText() }
        }
        return values
    }

    /** [keyboard] with [preset]'s keys: a type it has none for goes back to the default; main keys that don't suit the
     *  keyboard's language (QWERTY on a Hindi keyboard) stay as they are; custom keys whose layout changed or went
     *  come back as the keyboard's own unnamed copy. */
    fun keyboardWith(ctx: Context, keyboard: SettingsSubtype, preset: Preset): SettingsSubtype {
        var kb = keyboard
        for (type in LayoutType.entries) {
            val name = preset.values[LAYOUT + type.name] as? String
            if (name == null) { if (type != LayoutType.MAIN) kb = kb.withoutLayout(type); continue }
            if (LayoutUtilsCustom.isCustomLayout(name)) {
                if (type == LayoutType.MAIN && LayoutUtilsCustom.scopeOf(name, type) != LayoutUtilsCustom.scopeFor(kb.locale)) continue
                val text = preset.values[LAYOUT_TEXT + type.name] as? String
                val file = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
                val use = when {
                    file.isFile && (text == null || file.readText() == text) -> name
                    text != null -> LayoutUtilsCustom.makePrivateLayout(text, type, LayoutUtilsCustom.scopeOf(name, type), ctx)
                    else -> null
                } ?: continue
                kb = kb.withLayout(type, use)
            } else {
                if (type == LayoutType.MAIN && name !in LayoutUtils.getAvailableLayouts(type, ctx, kb.locale)) continue
                kb = kb.withLayout(type, name)
            }
        }
        return kb
    }

    /** [values] (settings part) replace the current ones: null and missing keys go back to their default. */
    fun applySettings(ctx: Context, values: Map<String, Any?>) {
        val prefs = ctx.prefs()
        val now = current(prefs)
        prefs.edit {
            for (key in now.keys) if (inScope(key) && !values.containsKey(key)) remove(key)
            for ((key, value) in values) if (inScope(key) && now[key] != value) {
                if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
            }
        }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    /** Whether what [preset] sets differs from what is set now for [keyboard]. */
    fun isTweaked(ctx: Context, keyboard: SettingsSubtype, preset: Preset): Boolean {
        val now = ctx.prefs().all
        if (preset.values.any { (key, value) -> inScope(key) && !KnownDefaults.same(key, now[key], value) }) return true
        return snapshot(ctx, keyboard).filterKeys { it.startsWith(LAYOUT) && !it.startsWith(LAYOUT_TEXT) } !=
            preset.values.filterKeys { it.startsWith(LAYOUT) && !it.startsWith(LAYOUT_TEXT) }
    }

    fun load(prefs: SharedPreferences): List<Preset> {
        val json = prefs.getString(PREF, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val v = o.getJSONObject("values")
                Preset(o.getString("name"), v.keys().asSequence().associateWith { key ->
                    v.getJSONObject(key).let { if (it.has("d")) null else AppearanceLooks.fromJson(it) } })
            }
        }.getOrDefault(emptyList())
    }

    fun save(prefs: SharedPreferences, presets: List<Preset>) {
        val arr = JSONArray()
        for (preset in presets) arr.put(JSONObject().apply {
            put("name", preset.name)
            put("values", JSONObject().also { v -> preset.values.forEach { (key, value) ->
                (if (value == null) JSONObject().put("d", true) else AppearanceLooks.toJson(value))?.let { v.put(key, it) } } })
        })
        prefs.edit { putString(PREF, arr.toString()) }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * A "Layout" is everything on the Layout & Typing screen saved under a name, as a theme is for Appearance: the keys of
 * every layout type (a custom one's content too, so it outlives the custom layout), and the settings of the screen's
 * groups (shape, toolbar, typing, backspace, undo, popups: the popup set with its arrangement, the accents level, the
 * switches). Popups are only put on a keyboard of the script the Layout was saved from (they're for its letters); a
 * Layout saved before popups were in Layouts leaves them alone. Not in a Layout: what is app-wide (clipboard
 * history). Saved Layouts are one JSON list, shared by all keyboards.
 */
object LayoutPresets {
    const val PREF = "layout_presets"
    const val PREF_SELECTED = "layout_preset_selected"
    internal const val LAYOUT = "layout:" // + type: the keys' layout name (no entry: the default)
    internal const val LAYOUT_TEXT = "layoutText:" // + type: a custom layout's content
    private const val SCRIPT = "script" // the script of the keyboard it was saved from (popups only go to that script)
    private const val MORE_POPUPS = "morePopups" // the keyboard's own accents level (no entry / null: the general one)

    /** The popup settings (also in Layouts since 2026-10-03). */
    private val popupKeys = setOf(Settings.PREF_SHOW_TLD_POPUP_KEYS, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        Settings.PREF_SYMBOL_POPUP_MAP, KeyPopupOverrides.PREF, KeyPopupOverrides.PREF_SELECTED_SET)

    /** Equal by content, not name: the list's tapped one is still found after a rename (or a reload of the list).
     *  [builtIn]: one that ships with the app ([builtIn]), not the user's. [remembered]: the values its unpicked settings
     *  (null) had when saved ([SavedItemDefaults]). */
    class Preset(val name: String, val values: Map<String, Any?>, val builtIn: Boolean = false,
                 val remembered: Map<String, Any> = emptyMap()) {
        override fun equals(other: Any?) = other is Preset && other.values == values
        override fun hashCode() = values.hashCode()
    }

    // the saved popup sets (app-wide), the keyboard list, and what an entry of its own covers
    private val notInPresets = setOf("key_popup_sets", PREF_SELECTED,
        Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE,
        helium314.keyboard.latin.settings.KeyboardIdUpgrade.OLD_IDS) // (the old id map: read by the one-time upgrade only)
    private val prefixes = LayoutDraft.prefixes.filter { it != Settings.PREF_SAVED_APP_SUBTYPE_PREFIX }

    fun inScope(key: String) = !KeyboardProfiles.isAppWide(key) && key !in notInPresets
        && (key in LayoutDraft.keys || prefixes.any { key.startsWith(it) })

    /**
     * The Layouts that ship with the app (2026-10-06), like the built-in themes: each sets the whole Layout & Typing
     * screen, what it doesn't name back to its default ([complete]); not the keys' arrangement (QWERTY, Dvorak…: the
     * language's), the keyboards, or the saved popup sets. Two since 2026-10-24: Dense is the default (everything at its
     * default, so it follows them; the Curmudgeon Layout until then), Classic is upstream HeliBoard's typing with the
     * number row, the Curmudgeon symbol map and HeliBoard's main popups (the HeliBoard and HeliBoard extra Layouts went;
     * a keyboard on one of them is on Classic now, see AppUpgrade.builtInLayoutRenames). First = the default.
     */
    fun builtIn(ctx: Context): List<Preset> {
        // HeliBoard's own typing: ?123 long-press does nothing, holding backspace deletes letters, 50 ms apart, no
        // speed-up, a 300 ms long press, 100% under the bottom row in portrait (what the Dense Layout, the defaults,
        // changed on 2026-10-07 stays HeliBoard's here)
        // (the backspace waits as long as any key; the same values the update step writes for 0.3.008's HeliBoard keyboards)
        val heliBoardTyping = mapOf(Settings.PREF_LONG_PRESS_SYMBOL_ACTION to "none",
            Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS to false, Settings.PREF_BACKSPACE_REPEAT_INTERVAL to 50) +
            helium314.keyboard.latin.heliBoardPins()
        val fullBottomRow = sizeKeys.keys.filter { it.startsWith(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX) }.associateWith { 1f }
        return listOf(
            Preset(ctx.getString(R.string.layout_preset_dense), complete(emptyMap()), builtIn = true),
            Preset(ctx.getString(R.string.layout_preset_classic), complete(heliBoardTyping + fullBottomRow + mapOf(
                // the number row and the Curmudgeon symbol map (the pins' "" is HeliBoard's none), HeliBoard's main popups
                Settings.PREF_SHOW_NUMBER_ROW to true, Settings.PREF_SYMBOL_POPUP_MAP to Defaults.CURMUDGEON_SYMBOL_POPUP_MAP,
                MORE_POPUPS to "main",
                // (HeliBoard has no undo history length: the Curmudgeon one before 2026-10-06; no emoji key, as upstream)
                Settings.PREF_UNDO_HISTORY_LENGTH to 20, Settings.PREF_SHOW_EMOJI_KEY to false)), builtIn = true),
        )
    }

    /** A built-in Layout: [values], and every other Layout & Typing setting at its default (null: not set). */
    private fun complete(values: Map<String, Any?>): Map<String, Any?> =
        LayoutDraft.keys.filter { inScope(it) }.associateWith { null } + sizeKeys + values

    /** The settings part as it is now (plain keys, the edited keyboard's set). */
    fun current(prefs: SharedPreferences): Map<String, Any?> = prefs.all.filterKeys { inScope(it) }

    /** What a Layout saves for [keyboard]: its keys, the settings set now (picked), and every other one as "not set"
     *  (null), with the value it had remembered (Preset.remembered, [SavedItemDefaults]: today's default or as saved, by
     *  the switch; 2026-10-11, as for themes). */
    fun snapshot(ctx: Context, keyboard: SettingsSubtype): Map<String, Any?> {
        val values = HashMap<String, Any?>()
        LayoutDraft.keys.filter { inScope(it) }.forEach { values[it] = null }
        values.putAll(sizeKeys)
        values.putAll(current(ctx.prefs()))
        values[SCRIPT] = keyboard.locale.script()
        values[MORE_POPUPS] = keyboard.getExtraValueOf(ExtraValue.MORE_POPUPS)
        for (type in LayoutType.entries) {
            val name = keyboard.layoutName(type) ?: continue
            values[LAYOUT + type.name] = name
            if (LayoutUtilsCustom.isCustomLayout(name))
                LayoutUtilsCustom.getLayoutFile(name, type, ctx).takeIf { it.isFile }?.let { values[LAYOUT_TEXT + type.name] = it.readText() }
        }
        return values
    }

    /** Layout [name] as Save as new or Update saves it from [keyboard] ([snapshot]), [chosen] the Layout on it: what is
     *  still at the value [chosen] remembered for its default stays at its default ([SavedItemDefaults.forSave]). */
    fun saved(ctx: Context, keyboard: SettingsSubtype, name: String, chosen: Preset?): Preset {
        val (values, remembered) = SavedItemDefaults.forSave(snapshot(ctx, keyboard),
            chosen?.let { rememberedFor(it, listOf(keyboard)) } ?: emptyMap(), SavedItemDefaults.takeNewDefaults(ctx.realPrefs()))
        return Preset(name, values, remembered = remembered)
    }

    /** What [preset] holds at its default with the value it remembered, as it applies on [keyboards] (its popups on one
     *  of them, see [popupsFit]); a built-in none (it follows the defaults). */
    fun rememberedFor(preset: Preset, keyboards: Collection<SettingsSubtype>): Map<String, Any> =
        if (preset.builtIn) emptyMap()
        else preset.remembered.filterKeys { key -> preset.values.containsKey(key) && preset.values[key] == null && inScope(key)
            && (key !in popupKeys || keyboards.any { popupsFit(it, preset) }) }

    /** [keyboard] with [preset]'s keys: a type it has none for goes back to the default; main keys that don't suit the
     *  keyboard's language (QWERTY on a Hindi keyboard) stay as they are; custom keys whose layout changed or went
     *  come back as the keyboard's own unnamed copy. */
    fun keyboardWith(ctx: Context, keyboard: SettingsSubtype, preset: Preset): SettingsSubtype {
        var kb = keyboard
        // a built-in sets the keyboard's accents level (none: the general one) and its popup order back to the default;
        // its keys stay as they are
        if (preset.builtIn) {
            val more = preset.values[MORE_POPUPS] as? String
            kb = if (more == null) kb.without(ExtraValue.MORE_POPUPS) else kb.with(ExtraValue.MORE_POPUPS, more)
            return kb.without(ExtraValue.POPUP_ORDER)
        }
        if (popupsFit(kb, preset)) {
            val more = preset.values[MORE_POPUPS] as? String
            kb = if (more == null) kb.without(ExtraValue.MORE_POPUPS) else kb.with(ExtraValue.MORE_POPUPS, more)
        }
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

    /** Whether [preset]'s popups go on [keyboard]: saved with popups, from a keyboard of the same script. */
    private fun popupsFit(keyboard: SettingsSubtype, preset: Preset) = preset.builtIn || preset.values[SCRIPT] == keyboard.locale.script()

    /** What [preset] applies: a built-in follows the defaults; a saved one its unpicked settings at today's default or
     *  as saved, by the switch [SavedItemDefaults] ([takeNew]). */
    fun applied(preset: Preset, takeNew: Boolean): Map<String, Any?> =
        if (preset.builtIn) preset.values else SavedItemDefaults.applied(preset.values, preset.remembered, takeNew)

    /** The settings [preset] puts on [keyboard] (its popups only when they fit, see [popupsFit]). */
    fun settingsFor(keyboard: SettingsSubtype, preset: Preset, takeNew: Boolean = SavedItemDefaults.DEFAULT): Map<String, Any?> =
        applied(preset, takeNew).filterKeys { inScope(it) && (it !in popupKeys || popupsFit(keyboard, preset)) }

    /** A Layout's popup set, if it names one of the user's and that set was deleted since: back in the sets list, so
     *  the keyboard's popups have their name again. Nothing there is replaced. */
    fun restorePopupSet(ctx: Context, keyboard: SettingsSubtype, preset: Preset) {
        if (!popupsFit(keyboard, preset)) return
        val name = preset.values[KeyPopupOverrides.PREF_SELECTED_SET] as? String ?: return
        val real = ctx.realPrefs()
        val sets = KeyPopupOverrides.loadSets(real)
        if (sets.any { it.name == name }) return
        val overrides = (preset.values[KeyPopupOverrides.PREF] as? String)?.let { json ->
            runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k); List(a.length()) { a.getString(it) } } } }.getOrNull() } ?: emptyMap()
        // the set comes back whole: a value the Layout kept as "default" is written in as it was when the Layout was saved
        // (else today's) and marked as at its default, as the Layout has it (owner 2026-10-11: "Saved items take new
        // defaults"; review 2026-10-11: it came back without a map, the 0.3.012 popups bug again)
        fun kept(key: String, default: String) = preset.values[key] as? String ?: preset.remembered[key] as? String ?: default
        val atDefault = setOfNotNull(KeyPopupOverrides.ACCENTS.takeIf { preset.values[MORE_POPUPS] == null },
            KeyPopupOverrides.MAP.takeIf { preset.values[Settings.PREF_SYMBOL_POPUP_MAP] == null },
            KeyPopupOverrides.ORDER.takeIf { preset.values[Settings.PREF_POPUP_KEYS_ORDER] == null })
        KeyPopupOverrides.saveSets(real, sets + KeyPopupOverrides.UserSet(name,
            preset.values[MORE_POPUPS] as? String ?: ctx.prefs().getString(Settings.PREF_MORE_POPUP_KEYS, Defaults.PREF_MORE_POPUP_KEYS)!!,
            preset.values[LAYOUT + LayoutType.SYMBOLS.name] as? String, overrides,
            kept(Settings.PREF_SYMBOL_POPUP_MAP, Defaults.PREF_SYMBOL_POPUP_MAP),
            kept(Settings.PREF_POPUP_KEYS_ORDER, Defaults.PREF_POPUP_KEYS_ORDER), atDefault))
    }

    /** [preset] (just updated with the edited keyboard's settings) on another [keyboard] that uses it and was ticked in
     *  the update dialog (2026-10-11): its settings, its keys, the name, no Custom label. */
    fun applyToOther(ctx: Context, keyboard: SettingsSubtype, preset: Preset) {
        val real = ctx.realPrefs()
        // (listed by its own set's choice since 2026-10-12, so it has a set; review 2026-10-11 C8 was one without a set
        // of its own yet, read through the shared set)
        // (owner 2026-10-11: lookups never create records; a listed one gets its set from the net, one neither listed nor
        // with a set is left alone)
        val id = KeyboardProfiles.idForListed(real, keyboard) ?: return
        // (its own set as stored, shown or hidden: with one set of settings or the menu shared too, as it was asked by
        // its own set's choice; a ProfilePreferences view writes a shared menu's settings to the main keyboard's set.
        // Owner 2026-10-11: a switched-off keyboard is asked by its own choice; owner 2026-10-12: "our saving policies
        // should not be affected by the common/separate toggle at all.")
        val view = helium314.keyboard.latin.settings.OwnSetPreferences(real, id)
        applySettings(ctx, settingsFor(keyboard, preset, SavedItemDefaults.takeNewDefaults(real)), view)
        chosen(view, keyboard, preset)
        val changed = keyboardWith(ctx, keyboard, preset)
        if (changed != keyboard) helium314.keyboard.latin.utils.SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, changed, ctx)
    }

    /** [preset] applied on [keyboard] ([prefs]: its settings view, [preset]'s settings on it already): its name, and no
     *  Custom label or origin for the Layout, nor for the popups when the Layout's popups came with it ([popupsFit]: they
     *  replaced the keyboard's; review 2026-10-08, the popups row follows a Layout applied on the same screen). The
     *  Layouts row's OK and [applyToOther] both end with this. */
    fun chosen(prefs: SharedPreferences, keyboard: SettingsSubtype, preset: Preset) {
        prefs.edit { putString(PREF_SELECTED, preset.name) }
        CustomLabels.clear(prefs, CustomLabels.Kind.LAYOUT)
        // (else "Custom · from <a deleted set>" stayed on popups the Layout replaced)
        if (popupsFit(keyboard, preset)) CustomLabels.clear(prefs, CustomLabels.Kind.POPUPS)
    }

    /** [values] (settings part) replace the current ones ([prefs]: the edited keyboard's): null and missing keys go
     *  back to their default. */
    fun applySettings(ctx: Context, values: Map<String, Any?>, prefs: SharedPreferences = ctx.prefs()) {
        val now = current(prefs)
        prefs.edit {
            for (key in now.keys) if (inScope(key) && !values.containsKey(key)) remove(key)
            for ((key, value) in values) if (inScope(key) && now[key] != value) {
                if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
            }
        }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    /** The sizes, each "not set": a Layout saved before 2026-10-03 has a size missing when it was at its default. */
    private val sizeKeys by lazy { SettingDefaults.all.keys.filter { key -> sizePrefixes.any { key.startsWith(it) } }.associateWith { null } }
    private val sizePrefixes = listOf(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX,
        Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, Settings.PREF_SIDE_PADDING_SCALE_PREFIX)

    /** Whether what [preset] sets differs from what is set now for [keyboard]. */
    fun isTweaked(ctx: Context, keyboard: SettingsSubtype, preset: Preset): Boolean {
        val now = ctx.prefs().all
        if (applied(preset, SavedItemDefaults.takeNewDefaults(ctx.realPrefs()))
                .any { (key, value) -> inScope(key) && !KnownDefaults.same(key, now[key], value) }) return true
        if (preset.builtIn) return false // (its keys are the language's: not compared)
        // the keys by name only (this runs on every redraw: no layout file is read)
        return LayoutType.entries.mapNotNull { type -> keyboard.layoutName(type)?.let { LAYOUT + type.name to it } }.toMap() !=
            preset.values.filterKeys { it.startsWith(LAYOUT) && !it.startsWith(LAYOUT_TEXT) }
    }

    fun load(prefs: SharedPreferences): List<Preset> = readEach(prefs, PREF, ::read).first

    /** How many saved Layouts couldn't be read (skipped by [load], set aside by the next [save]). */
    fun skipped(prefs: SharedPreferences): Int = readEach(prefs, PREF, ::read).second

    private fun read(o: JSONObject): Preset {
        val v = o.getJSONObject("values")
        return Preset(o.getString("name"), sizeKeys + v.keys().asSequence().associateWith { key ->
            v.getJSONObject(key).let { if (it.has("d")) null else AppearanceLooks.fromJson(it) } },
            remembered = AppearanceLooks.rememberedIn(v))
    }

    /** Saved Layout [name] goes from the list, and every keyboard that had it chosen has none chosen (2026-10-07: only
     *  the keyboard being edited forgot it; the others kept the name, and took a Layout saved later under it). */
    fun delete(real: SharedPreferences, name: String) {
        CustomLabels.detach(real, PREF_SELECTED, name, CustomLabels.Kind.LAYOUT) // (they keep their copy: Custom)
        save(real, load(real).filter { it.name != name })
        KeyboardProfiles.replaceValueEverywhere(real, PREF_SELECTED, name, null)
    }

    /** A name the saved Layouts carry as [key]'s value was renamed to [to], or deleted ([to] null: "not set", the
     *  default, as the keyboards get): they follow, as the keyboards do (review 2026-10-08 area 4 #4: applying a Layout
     *  brought the old name back, and with a popup set a second copy of it). */
    private fun followValue(real: SharedPreferences, key: String, from: String, to: String?) {
        val presets = load(real)
        if (presets.none { it.values[key] == from }) return
        save(real, presets.map { if (it.values[key] == from) Preset(it.name, it.values + (key to to), it.builtIn, it.remembered) else it })
    }

    /** User popup set [from] renamed to [to], or deleted ([to] null): the Layouts holding it follow; a deleted one's
     *  popups stay in them, nameless, and never bring the set back by themselves (2026-10-11: "No coming back by itself"). */
    fun onPopupSetRenamed(real: SharedPreferences, from: String, to: String?) = followValue(real, KeyPopupOverrides.PREF_SELECTED_SET, from, to)

    /** Custom key layout [from] of [type] renamed to [to], or deleted ([to] null). The default for its type follows
     *  (deleted: the built-in one); the keys' own name only follows a rename, a deleted one comes back from the content
     *  the Layout keeps, as the keyboard's own copy ([keyboardWith]). */
    fun onLayoutRenamed(real: SharedPreferences, type: LayoutType, from: String, to: String?) {
        followValue(real, Settings.PREF_LAYOUT_PREFIX + type.name, from, to)
        if (to != null) followValue(real, LAYOUT + type.name, from, to)
    }

    fun save(prefs: SharedPreferences, presets: List<Preset>) {
        keepBrokenAside(prefs, PREF, ::read)
        val arr = JSONArray()
        for (preset in presets) arr.put(JSONObject().apply {
            put("name", preset.name)
            put("values", JSONObject().also { v -> preset.values.forEach { (key, value) ->
                (if (value == null) AppearanceLooks.atDefault(preset.remembered[key]) else AppearanceLooks.toJson(value))?.let { v.put(key, it) } } })
        })
        prefs.edit { putString(PREF, arr.toString()) }
    }
}

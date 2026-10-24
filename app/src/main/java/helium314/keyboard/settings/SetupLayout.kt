// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardIds
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.OwnSetPreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The setup's "Pick a layout" (2026-10-24, beside [SetupTheme]): Minimal, Classic or Dense, on every keyboard of the
 * device. Dense and Classic are the built-in Layouts, applied as the Layouts list applies a built-in to a keyboard (as
 * [LayoutPresets.applyToOther]: its settings, its accents level, the name, no Custom label). Minimal is no Layout: the
 * Classic Layout with the number row off, the symbol map off and the symbols on the keys hidden written on top (the
 * Layouts row shows Custom afterwards). Dense is the default: left picked, nothing is written (stored = picked); picked
 * again after another, each keyboard's Layout & Typing settings and definition go back to what they were before
 * ([before], as the Layout & Typing screen's Discard puts them back).
 */
object SetupLayout {
    enum class Pick { MINIMAL, CLASSIC, DENSE }

    /** What Minimal writes on top of the Classic Layout. */
    val minimalOverrides: Map<String, Any> = mapOf(
        Settings.PREF_SHOW_NUMBER_ROW to false, Settings.PREF_SYMBOL_POPUP_MAP to "", Settings.PREF_SHOW_HINTS to false)

    // (the Layout's and the popups' labels: a built-in Layout clears both, see LayoutPresets.chosen)
    private val labelKeys = listOf(CustomLabels.Kind.LAYOUT, CustomLabels.Kind.POPUPS).flatMap { listOf(it.key, it.fromKey) }
    private const val KEYBOARDS = "_keyboards" // the keyboards' definitions in a [before] snapshot (their accents level)

    private fun inSnapshot(key: String) = LayoutPresets.inScope(key) || key == LayoutPresets.PREF_SELECTED || key in labelKeys

    private fun dense(ctx: Context) = LayoutPresets.builtIn(ctx).single { it.name == ctx.getString(R.string.layout_preset_dense) }
    private fun classic(ctx: Context) = LayoutPresets.builtIn(ctx).single { it.name == ctx.getString(R.string.layout_preset_classic) }

    /** What the keyboard shows now (the settings' view: the keyboard edited from the main screen): Classic with
     *  Minimal's three values on top is Minimal; Classic; anything else Dense (the default, nothing chosen). */
    fun current(ctx: Context): Pick {
        val prefs = ctx.prefs()
        if (prefs.getString(LayoutPresets.PREF_SELECTED, null) != ctx.getString(R.string.layout_preset_classic)) return Pick.DENSE
        return if (minimalOverrides.all { (key, value) -> prefs.all[key] == value }) Pick.MINIMAL else Pick.CLASSIC
    }

    /** Each keyboard's Layout & Typing settings and definition as they are now, for [pickDense] (a string: kept over a
     *  rotation). Reading writes nothing. */
    fun before(ctx: Context): String {
        val real = ctx.realPrefs()
        val all = JSONObject()
        for (id in SetupTheme.ids(real)) {
            val values = JSONObject()
            for ((key, value) in OwnSetPreferences(real, id).all)
                if (inSnapshot(key)) AppearanceLooks.toJson(value)?.let { values.put(key, it) }
            all.put(id, values)
        }
        all.put(KEYBOARDS, JSONArray(KeyboardIds.phoneKeyboards(real).map { it.toPref() }))
        return all.toString()
    }

    /** [pick] on every keyboard (Minimal: Classic, then its three values on top). */
    fun pick(ctx: Context, pick: Pick) {
        val real = ctx.realPrefs()
        val preset = if (pick == Pick.DENSE) dense(ctx) else classic(ctx)
        val takeNew = SavedItemDefaults.takeNewDefaults(real)
        for (keyboard in KeyboardIds.phoneKeyboards(real)) {
            val id = KeyboardProfiles.idForListed(real, keyboard) ?: continue
            val view = OwnSetPreferences(real, id)
            LayoutPresets.applySettings(ctx, LayoutPresets.settingsFor(keyboard, preset, takeNew), view)
            LayoutPresets.chosen(view, keyboard, preset)
            if (pick == Pick.MINIMAL) view.edit { for ((key, value) in minimalOverrides) KeyboardProfiles.put(this, key, value) }
            val changed = LayoutPresets.keyboardWith(ctx, keyboard, preset)
            if (changed != keyboard) SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, changed, ctx)
        }
    }

    /** Back to Dense: every keyboard's Layout & Typing settings and definition as they were in [before] (a keyboard not
     *  in it: none of its own, the defaults); no [before] (the keyboard wasn't on Dense when the setup opened): the Dense
     *  Layout applied. */
    fun pickDense(ctx: Context, before: String?) {
        if (before == null) return pick(ctx, Pick.DENSE)
        val real = ctx.realPrefs()
        val saved = runCatching { JSONObject(before) }.getOrElse { JSONObject() }
        for (id in SetupTheme.ids(real)) {
            val view = OwnSetPreferences(real, id)
            val values = saved.optJSONObject(id)?.let { o -> o.keys().asSequence().associateWith { AppearanceLooks.fromJson(o.getJSONObject(it)) } }
                ?: emptyMap()
            LayoutPresets.applySettings(ctx, values.filterKeys { LayoutPresets.inScope(it) }, view)
            view.edit {
                for (key in labelKeys + LayoutPresets.PREF_SELECTED) {
                    val value = values[key]
                    if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
                }
            }
        }
        // the keyboards' definitions (the accents level a built-in set): each back as it was, found by its id
        val definitions = saved.optJSONArray(KEYBOARDS) ?: return
        val now = KeyboardIds.phoneKeyboards(real)
        for (i in 0 until definitions.length()) {
            val was = definitions.getString(i).toSettingsSubtype()
            val current = now.firstOrNull { it.keyboardId != null && it.keyboardId == was.keyboardId } ?: continue
            if (current != was) SubtypeUtilsAdditional.changeAdditionalSubtype(current, was, ctx)
        }
    }

    /** The spacing between suggestions [pick] shows (the preview's strip follows it). */
    fun wordPadding(ctx: Context, pick: Pick): Int = when (pick) {
        Pick.DENSE -> helium314.keyboard.latin.settings.Defaults.PREF_SUGGESTION_WORD_PADDING
        else -> classic(ctx).values[Settings.PREF_SUGGESTION_WORD_PADDING] as? Int ?: helium314.keyboard.latin.settings.Defaults.PREF_SUGGESTION_WORD_PADDING
    }

    /** The settings of keyboard [keyboard]'s own set, for the tests. */
    internal fun view(real: SharedPreferences, keyboard: SettingsSubtype): SharedPreferences? =
        KeyboardProfiles.idForListed(real, keyboard)?.let { OwnSetPreferences(real, it) }
}

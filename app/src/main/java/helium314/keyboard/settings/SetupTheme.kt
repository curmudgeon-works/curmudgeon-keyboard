// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardIds
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.OwnSetPreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import org.json.JSONObject

/**
 * The setup's "Pick a theme" (owner 2026-10-14): Dynamic or Curmudgeon, on every keyboard of the device. Curmudgeon is
 * applied as the Themes list applies a built-in theme to a keyboard (as [AppearanceLooks.applyToOther]: its settings,
 * the name, no Custom label; its day and night colors are both Curmudgeon's), not its background pictures. Dynamic is
 * the default: left picked, nothing is written (stored = picked); picked again after Curmudgeon, each keyboard's
 * Appearance settings go back to what they were before ([before], as the Appearance screen's Discard puts them back).
 */
object SetupTheme {
    private val labelKeys = CustomLabels.Kind.THEME.let { listOf(it.key, it.fromKey) }

    /** The sets of the device's keyboards (listed or switched off). */
    private fun ids(real: SharedPreferences): List<String> =
        KeyboardIds.phoneKeyboards(real).mapNotNull { KeyboardProfiles.idForListed(real, it) }.distinct()

    /** Whether the keyboard shows Curmudgeon's colors now (the settings' view: the keyboard edited from the main screen). */
    fun isCurmudgeon(ctx: Context): Boolean =
        ctx.prefs().getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_CURMUDGEON

    /** Each keyboard's Appearance settings as they are now, for [pickDynamic] (a string: kept over a rotation). */
    fun before(ctx: Context): String {
        val real = ctx.realPrefs()
        val all = JSONObject()
        for (id in ids(real)) {
            val values = JSONObject()
            for ((key, value) in OwnSetPreferences(real, id).all)
                if (AppearanceLooks.onScreen(key) || key in labelKeys) AppearanceLooks.toJson(value)?.let { values.put(key, it) }
            all.put(id, values)
        }
        return all.toString()
    }

    /** Built-in theme [nameRes] on every keyboard (Curmudgeon; Dynamic for a keyboard that had Curmudgeon before the
     *  setup: picked, so stored). */
    fun pick(ctx: Context, nameRes: Int) {
        val real = ctx.realPrefs()
        val look = AppearanceLooks.builtIn(ctx).single { it.name == ctx.getString(nameRes) }
        val takeNew = SavedItemDefaults.takeNewDefaults(real)
        for (id in ids(real)) {
            val view = OwnSetPreferences(real, id)
            AppearanceLooks.apply(ctx, AppearanceLooks.current(view) + AppearanceLooks.applied(look, takeNew), view)
            view.edit { putString(AppearanceLooks.PREF_SELECTED, look.name) }
            CustomLabels.clear(view, CustomLabels.Kind.THEME)
        }
    }

    fun pickCurmudgeon(ctx: Context) = pick(ctx, R.string.theme_preset_curmudgeon)

    /** Back to Dynamic: every keyboard's Appearance settings as they were in [before] (a keyboard not in it: none of its
     *  own, the defaults); no [before] (the keyboard had Curmudgeon when the setup opened): the Dynamic theme applied. */
    fun pickDynamic(ctx: Context, before: String?) {
        if (before == null) return pick(ctx, R.string.theme_preset_dynamic)
        val real = ctx.realPrefs()
        val saved = runCatching { JSONObject(before) }.getOrElse { JSONObject() }
        for (id in ids(real)) {
            val view = OwnSetPreferences(real, id)
            val values = saved.optJSONObject(id)?.let { o -> o.keys().asSequence().associateWith { AppearanceLooks.fromJson(o.getJSONObject(it)) } }
                ?: emptyMap()
            AppearanceLooks.applyScreen(ctx, values.filterKeys { it !in labelKeys }, view)
            view.edit {
                for (key in labelKeys) {
                    val value = values[key]
                    if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
                }
            }
        }
    }

    /** The colors theme [name] draws the keyboard with, for its picture: Dynamic light or dark as the device is now, as
     *  the keyboard has it with Dynamic picked (it follows the device's dark mode: the Dynamic theme turns that on; read
     *  from the keyboard's settings it would follow Curmudgeon's "off" once that is picked). */
    fun colors(ctx: Context, name: String): Colors {
        val prefs = ctx.prefs()
        val night = SettingsActivity.forceNight ?: ResourceUtils.isNight(ctx.resources)
        return KeyboardTheme.getThemeColors(name, KeyboardTheme.STYLE_MATERIAL, ctx, prefs, night)
    }
}

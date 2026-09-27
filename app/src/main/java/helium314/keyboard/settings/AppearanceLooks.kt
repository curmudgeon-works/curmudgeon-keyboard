// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * A "look" is everything the Appearance screen sets, saved under a name: theme, colors, sizes, gaps, suggestion
 * strip style. Saved looks are one JSON list in the preferences, shared by all keyboards. The custom background
 * image and fonts are files and are not part of a look.
 */
object AppearanceLooks {
    const val PREF = "appearance_looks"

    class Look(val name: String, val values: Map<String, Any?>)

    private val keys = setOf(
        Settings.PREF_THEME_STYLE, Settings.PREF_ICON_STYLE, Settings.PREF_CUSTOM_ICON_NAMES, Settings.PREF_THEME_COLORS,
        Settings.PREF_THEME_KEY_BORDERS, Settings.PREF_THEME_DAY_NIGHT, Settings.PREF_THEME_COLORS_NIGHT, Settings.PREF_NAVBAR_COLOR,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD, Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE, Settings.PREF_NARROW_KEY_GAPS, Settings.PREF_SPACE_BAR_TEXT,
        Settings.PREF_FONT_SCALE, Settings.PREF_EMOJI_FONT_SCALE, Settings.PREF_EMOJI_KEY_FIT, Settings.PREF_EMOJI_SKIN_TONE,
        Settings.PREF_SUGGESTION_TEXT_SIZE, Settings.PREF_SUGGESTION_BOLD, Settings.PREF_SUGGESTION_ITALIC,
        Settings.PREF_SUGGESTION_UNDERLINE, Settings.PREF_SUGGESTION_WORD_PADDING, Settings.PREF_TOOLBAR_EXPAND_ICON,
        Settings.PREF_KEY_HORIZONTAL_GAP, Settings.PREF_KEY_VERTICAL_GAP,
    )
    // the scales have a key per orientation / fold state, the custom colors one per theme
    private val prefixes = listOf(
        Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_BOTTOM_ROW_SCALE_PREFIX,
        Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
        Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX,
    )

    fun inScope(key: String) = key in keys || prefixes.any { key.startsWith(it) }

    /** The appearance values as they are now (plain keys, the current keyboard's set). */
    fun current(prefs: SharedPreferences): Map<String, Any?> = prefs.all.filterKeys { inScope(it) }

    /** [values] replace the current appearance values as a whole; the live keyboard reloads. */
    fun apply(ctx: Context, values: Map<String, Any?>) {
        val prefs = ctx.prefs()
        val now = current(prefs)
        prefs.edit {
            for (key in now.keys) if (key !in values) remove(key)
            for ((key, value) in values) if (now[key] != value) KeyboardProfiles.put(this, key, value)
        }
        reload(ctx)
    }

    /** Everything that draws the keyboard reads the preferences again. */
    fun reload(ctx: Context) {
        Settings.clearCachedBackgroundImages()
        KeyboardIconsSet.needsReload = true
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    }

    // ---- the saved list; every value carries its type, as JSON alone can't tell an int from a float ----

    fun load(prefs: SharedPreferences): List<Look> {
        val json = prefs.getString(PREF, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val v = o.getJSONObject("values")
                Look(o.getString("name"), v.keys().asSequence().associateWith { key -> fromJson(v.getJSONObject(key)) })
            }
        }.getOrDefault(emptyList())
    }

    fun save(prefs: SharedPreferences, looks: List<Look>) {
        val arr = JSONArray()
        for (look in looks) arr.put(JSONObject().apply {
            put("name", look.name)
            put("values", JSONObject().also { v -> look.values.forEach { (key, value) -> toJson(value)?.let { v.put(key, it) } } })
        })
        prefs.edit { putString(PREF, arr.toString()) }
    }

    private fun toJson(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("b", value)
        is Int -> JSONObject().put("i", value)
        is Long -> JSONObject().put("l", value)
        is Float -> JSONObject().put("f", value.toDouble())
        is String -> JSONObject().put("s", value)
        is Set<*> -> JSONObject().put("ss", JSONArray(value.toList()))
        else -> null
    }

    private fun fromJson(o: JSONObject): Any? = when {
        o.has("b") -> o.getBoolean("b")
        o.has("i") -> o.getInt("i")
        o.has("l") -> o.getLong("l")
        o.has("f") -> o.getDouble("f").toFloat()
        o.has("s") -> o.getString("s")
        o.has("ss") -> o.getJSONArray("ss").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        else -> null
    }
}

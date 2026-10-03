// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.R

import helium314.keyboard.keyboard.KeyboardTheme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.common.PictureFraming
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * A "look" (theme) is the keyboard's styling saved under a name: colours, background pictures, key borders and gaps,
 * key and icon style, icons, fonts. Saved looks are one JSON list in the preferences, shared by all keyboards; a
 * look's background pictures are copies in their own folder (see [PICTURES]); also the symbols on the keys (the
 * hide-symbols switches) and the space bar text (since 2026-10-02: a theme saved before keeps the current ones). Not in
 * looks: the emoji settings (on Appearance but kept by its Save / Discard only, see [onScreen]).
 */
object AppearanceLooks {
    const val PREF = "appearance_looks"
    const val PREF_SELECTED = "appearance_look_selected" // the theme last chosen (its name), for the Themes row

    class Look(val name: String, val values: Map<String, Any?>)

    private val keys = setOf(
        Settings.PREF_THEME_STYLE, Settings.PREF_ICON_STYLE, Settings.PREF_CUSTOM_ICON_NAMES, Settings.PREF_THEME_COLORS,
        Settings.PREF_THEME_KEY_BORDERS, Settings.PREF_THEME_DAY_NIGHT, Settings.PREF_THEME_COLORS_NIGHT,
        Settings.PREF_BACKGROUND_WHOLE_PICTURE,
        Settings.PREF_FONT_SCALE,
        Settings.PREF_SUGGESTION_TEXT_SIZE, Settings.PREF_SUGGESTION_BOLD, Settings.PREF_SUGGESTION_ITALIC,
        Settings.PREF_SUGGESTION_UNDERLINE, Settings.PREF_SUGGESTION_WORD_PADDING,
        Settings.PREF_KEY_HORIZONTAL_GAP, Settings.PREF_KEY_VERTICAL_GAP,
        Settings.PREF_KEY_TEXT_BOLD, Settings.PREF_KEY_TEXT_ITALIC, Settings.PREF_KEY_TEXT_UNDERLINE, Settings.PREF_HINT_FONT_SCALE,
        Settings.PREF_KEY_FONT, Settings.PREF_HINT_FONT, Settings.PREF_SUGGESTION_FONT, Settings.PREF_FONT_FOLLOWS_KEY_TEXT, Settings.PREF_HINT_TEXT_BOLD, Settings.PREF_HINT_TEXT_ITALIC, Settings.PREF_HINT_TEXT_UNDERLINE,
        Settings.PREF_SPACE_BAR_TEXT, Settings.PREF_SHOW_NUMBER_ROW_HINTS, Settings.PREF_SHOW_HINTS, Settings.PREF_SHOW_POPUP_HINTS,
        Settings.PREF_SUGGESTION_TEXT_COLOR,
        // the emoji look (since 2026-10-03; a theme saved before leaves them as they are): not the emoji version, which
        // is about what the phone's font can draw
        Settings.PREF_EMOJI_FONT_SCALE, Settings.PREF_EMOJI_KEY_FIT, Settings.PREF_EMOJI_FONT, Settings.PREF_EMOJI_SKIN_TONE,
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
    )
    /** The emoji settings at their defaults: the built-in themes put them back (a theme sets the whole look). */
    private val emojiDefaults: Map<String, Any?> = listOf(Settings.PREF_EMOJI_FONT_SCALE, Settings.PREF_EMOJI_KEY_FIT,
        Settings.PREF_EMOJI_FONT, Settings.PREF_EMOJI_SKIN_TONE, Settings.PREF_SHOW_EMOJI_DESCRIPTIONS).associateWith { null }
    // the scales have a key per orientation / fold state, the custom colors one per theme
    private val prefixes = listOf(
        // (keyboard height, split, numbers row, bottom row size and side padding are Layout & Typing's: not in themes)
        Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX,
    )

    fun inScope(key: String) = key in keys || prefixes.any { key.startsWith(it) }

    // on the Appearance screen but not in looks: its Save / Discard (AppearanceDraft) keeps them too
    private val screenOnlyKeys = setOf(
        Settings.PREF_EMOJI_MAX_SDK,
        PREF_SELECTED, // Discard puts the chosen theme back too
        "day_night_paired_from", // (which of Midnight / Daylight the light / dark switch paired from)
    )
    fun onScreen(key: String) = inScope(key) || key in screenOnlyKeys

    /** Everything the Appearance screen sets, as it is now (the draft's snapshot). */
    fun screenValues(prefs: SharedPreferences): Map<String, Any?> = prefs.all.filterKeys { onScreen(it) }

    /** Back to [values] for everything on the Appearance screen (the draft's Discard). */
    fun applyScreen(ctx: Context, values: Map<String, Any?>, prefs: SharedPreferences = ctx.prefs()) {
        val now = screenValues(prefs)
        prefs.edit {
            for (key in now.keys) if (key !in values) remove(key)
            for ((key, value) in values) if (onScreen(key) && now[key] != value) KeyboardProfiles.put(this, key, value)
        }
        reload(ctx)
    }

    // ---- background pictures: a look saved since 0.1.004 names its picture folder under this key ----
    const val PICTURES = "look_pictures" // (not a preference: never written to the settings)
    private const val NO_PICTURES = "" // the built-in looks: no background picture

    // each picture with its framing (how it sits on the keyboard, see PictureFraming)
    private fun livePictures(ctx: Context) = listOf(false, true).flatMap { night -> listOf(false, true).flatMap { land ->
        Settings.getCustomBackgroundFile(ctx, night, land).let { listOf(it, PictureFraming.fileFor(it)) } } }
    private fun picturesDir(ctx: Context, id: String) = java.io.File(ctx.filesDir, "looks" + java.io.File.separator + id)

    /** Copies the background pictures there are now into a new folder; returns its id, to store in the look. */
    fun savePictures(ctx: Context): String {
        val id = java.util.UUID.randomUUID().toString()
        val dir = picturesDir(ctx, id).apply { mkdirs() }
        livePictures(ctx).filter { it.exists() }.forEach { it.copyTo(java.io.File(dir, it.name), overwrite = true) }
        return id
    }

    /** The look's pictures become the background ones (a built-in look: none); a look saved before pictures were
     *  part of looks leaves them as they are. */
    fun applyPictures(ctx: Context, look: Look) {
        val id = look.values[PICTURES] as? String ?: return
        val dir = if (id == NO_PICTURES) null else picturesDir(ctx, id)
        for (live in livePictures(ctx)) {
            val saved = dir?.let { java.io.File(it, live.name) }
            if (saved?.exists() == true) saved.copyTo(live, overwrite = true) else live.delete()
        }
        reload(ctx)
    }

    /** Whether what [look] sets differs from what is set now (a value, or the background pictures). */
    fun isTweaked(ctx: Context, look: Look): Boolean {
        val now = ctx.prefs().all
        if (look.values.any { (key, value) -> inScope(key) && !KnownDefaults.same(key, now[key], value) }) return true
        val id = look.values[PICTURES] as? String ?: return false // saved before pictures were in themes: not compared
        // a picture by its size, a framing (a few bytes of the same length whatever it says) by its content
        fun sig(f: java.io.File): Any = if (f.name.endsWith(".framing")) f.readText() else f.length()
        val saved = if (id == NO_PICTURES) emptyMap() else picturesDir(ctx, id).listFiles()?.associate { it.name to sig(it) } ?: emptyMap()
        val live = livePictures(ctx).filter { it.exists() }.associate { it.name to sig(it) }
        return saved != live
    }

    fun deletePictures(ctx: Context, look: Look) {
        (look.values[PICTURES] as? String)?.takeIf { it != NO_PICTURES }?.let { picturesDir(ctx, it).deleteRecursively() }
    }

    /** The pictures as they are now, as a look of their own (for putting them back on Cancel). */
    fun currentPictures(ctx: Context): Look = Look("", mapOf(PICTURES to savePictures(ctx)))

    /** The themes that ship with the app: Midnight and Daylight one colour set each, the others a light and a dark one
     *  following the system. */
    fun builtIn(ctx: Context): List<Look> = listOf(
        Look(ctx.getString(R.string.theme_preset_midnight), emojiDefaults + mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_KEY_HORIZONTAL_GAP to null, Settings.PREF_KEY_VERTICAL_GAP to null, // the default gaps (1% / 2%)
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
            // one colour set, always (the light / dark switch off)
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_BLACK, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_BLACK,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to false)),
        Look(ctx.getString(R.string.theme_preset_daylight), emojiDefaults + mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_KEY_HORIZONTAL_GAP to null, Settings.PREF_KEY_VERTICAL_GAP to null, // the default gaps (1% / 2%)
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
            // one colour set, always (the light / dark switch off)
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_LIGHT,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to false)),
        Look(ctx.getString(R.string.theme_preset_holo), emojiDefaults + mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_HOLO, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_HOLO,
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_HOLO_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_HOLO_WHITE,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to true)),
        Look(ctx.getString(R.string.theme_preset_paper), emojiDefaults + mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_DARK,
            Settings.PREF_THEME_KEY_BORDERS to false, Settings.PREF_THEME_DAY_NIGHT to true)),
        Look(ctx.getString(R.string.theme_preset_ocean), emojiDefaults + mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_ROUNDED, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_ROUNDED,
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_OCEAN_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_OCEAN,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_KEY_HORIZONTAL_GAP to 1.0f, Settings.PREF_KEY_VERTICAL_GAP to 1.5f,
            Settings.PREF_THEME_DAY_NIGHT to true)),
    )

    /** The appearance values as they are now (plain keys, the current keyboard's set). */
    fun current(prefs: SharedPreferences): Map<String, Any?> = prefs.all.filterKeys { inScope(it) }

    /** [values] replace the current appearance values as a whole (keys missing from it go back to default);
     *  to apply a theme on top of the current values, pass `current + theme`. The live keyboard reloads. */
    fun apply(ctx: Context, values: Map<String, Any?>) {
        val prefs = ctx.prefs()
        val now = current(prefs)
        prefs.edit {
            for (key in now.keys) if (key !in values) remove(key)
            // only what themes cover: themes saved before hold keys that moved out (keyboard height, split, numbers row)
            // (null: the theme has it at its default, so it's unset again)
            for ((key, value) in values) if (inScope(key) && now[key] != value) {
                if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
            }
        }
        reload(ctx)
    }

    /** What a theme saves: the values set now, and every theme setting left at its default as null, so applying the
     *  theme puts those back to their default too (before 2026-10-02 a theme had only the values set, and one left at
     *  its default kept whatever was there when the theme was applied). */
    fun snapshot(prefs: SharedPreferences): Map<String, Any?> = keys.associateWith { null } + current(prefs)

    /** Everything that draws the keyboard reads the preferences again. */
    fun reload(ctx: Context) {
        helium314.keyboard.keyboard.emoji.SupportedEmojis.load(ctx) // which emojis show (the emoji version)
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
            // a value at its default is saved as {"d":true} (read back as null, see snapshot)
            put("values", JSONObject().also { v -> look.values.forEach { (key, value) ->
                (if (value == null) JSONObject().put("d", true) else toJson(value))?.let { v.put(key, it) } } })
        })
        prefs.edit { putString(PREF, arr.toString()) }
    }

    internal fun toJson(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("b", value)
        is Int -> JSONObject().put("i", value)
        is Long -> JSONObject().put("l", value)
        is Float -> JSONObject().put("f", value.toDouble())
        is String -> JSONObject().put("s", value)
        is Set<*> -> JSONObject().put("ss", JSONArray(value.toList()))
        else -> null
    }

    internal fun fromJson(o: JSONObject): Any? = when {
        o.has("b") -> o.getBoolean("b")
        o.has("i") -> o.getInt("i")
        o.has("l") -> o.getLong("l")
        o.has("f") -> o.getDouble("f").toFloat()
        o.has("s") -> o.getString("s")
        o.has("ss") -> o.getJSONArray("ss").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        else -> null
    }
}

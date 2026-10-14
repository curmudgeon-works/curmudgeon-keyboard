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
import helium314.keyboard.latin.common.PictureLibrary
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
 * hide-symbols switches) and the space bar text (since 2026-10-02: a theme saved before keeps the current ones), and the
 * emoji settings. A theme sets all of the Theme and Emoji groups: what it doesn't name goes back to its default
 * (2026-10-06: the built-in themes named a few settings and left the rest as they were).
 */
object AppearanceLooks {
    const val PREF = "appearance_looks"
    const val PREF_SELECTED = "appearance_look_selected" // the theme last chosen (its name), for the Themes row

    /** Equal by content, not name: the list's tapped one is still found after a rename (or a reload of the list).
     *  [remembered]: the values its unpicked settings (null) had when saved ([SavedItemDefaults]). */
    class Look(val name: String, val values: Map<String, Any?>, val remembered: Map<String, Any> = emptyMap()) {
        override fun equals(other: Any?) = other is Look && other.values == values
        override fun hashCode() = values.hashCode()
    }

    internal val keys = setOf(
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
        Settings.PREF_SUGGESTION_TEXT_COLOR, Settings.PREF_GESTURE_TRAIL_COLOR,
        // the emoji look (since 2026-10-03; a theme saved before leaves them as they are): not the emoji version, which
        // is about what the phone's font can draw
        Settings.PREF_EMOJI_FONT_SCALE, Settings.PREF_EMOJI_KEY_FIT, Settings.PREF_EMOJI_FONT, Settings.PREF_EMOJI_SKIN_TONE,
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
        // and the emoji version, which the Emoji group shows too (2026-10-06; its default is what the phone can draw)
        Settings.PREF_EMOJI_MAX_SDK,
    )
    /** A built-in theme: [values], and every other theme setting at its default (null: not set). */
    private fun complete(values: Map<String, Any?>): Map<String, Any?> = keys.associateWith { null } + values

    // (keyboard height, split, numbers row, bottom row size and side padding are Layout & Typing's: not in themes; the
    // palettes are one app-wide list a theme names by its light and dark colours, no copy: owner 2026-10-11, Palettes)
    fun inScope(key: String) = key in keys

    // on the Appearance screen but not in looks: its Save / Discard (AppearanceDraft) keeps them too
    private val screenOnlyKeys = setOf(
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

    // since 2026-10-07 a look names its pictures in the picture library (one copy of each picture in the app, shared by
    // keyboards and themes: "lib:" + JSON {slot: {"p": library file name, "f": framing text or absent}}); before, a
    // folder of copies per look (its uuid), converted at app start by [migratePictureFolders]
    private const val LIB = "lib:"

    // a slot is the plain picture name, the same on every keyboard (custom_background_image[_landscape][_night]): a
    // keyboard's own file has its set's "_p<id>" on it, which themes saved before 2026-10-10 kept, so applied on another
    // keyboard none matched and its pictures were deleted (review 2026-10-08 M5); old names are read without it (2026-10-10)
    private val keyboardSuffix = Regex("_p-?\\d+$") // (-: KeyboardProfiles.NO_SET, a first keyboard without an id yet)
    internal fun slotOf(fileName: String): String =
        if (fileName.endsWith(".framing")) slotOf(fileName.removeSuffix(".framing")) + ".framing"
        else fileName.replace(keyboardSuffix, "")
    // a look's slots by their plain names (a plain name wins over an old one, should a look have both)
    private fun slotsOf(refs: JSONObject): Map<String, JSONObject> = refs.keys().asSequence()
        .sortedBy { slotOf(it) == it }.mapNotNull { k -> refs.optJSONObject(k)?.let { slotOf(k) to it } }.toMap()

    /** The background pictures there are now, as a look stores them: their library names (each added to the library
     *  if it isn't there) with their framing; [NO_PICTURES] when there are none. */
    fun savePictures(ctx: Context): String {
        val o = JSONObject()
        for (live in livePictures(ctx)) {
            if (live.name.endsWith(".framing") || !live.exists()) continue
            val name = PictureLibrary.nameOf(ctx, live) ?: continue
            val slot = JSONObject().put("p", name)
            PictureFraming.fileFor(live).takeIf { it.isFile }?.let { slot.put("f", it.readText()) }
            o.put(slotOf(live.name), slot)
        }
        return if (o.length() == 0) NO_PICTURES else LIB + o.toString()
    }

    /** The background pictures as they are now, held for the Themes list's Cancel: copies in a folder of their own
     *  (by slot, the old look format [applyPictures] still reads), not in the library; [deletePictures] removes them.
     *  (review 2026-10-08 area 4 #6: every list open added the keyboard's pictures to the library, so a picture
     *  forgotten there came back.) One folder, made again at each open: a process killed with the list open leaves no
     *  more than one. */
    fun holdPictures(ctx: Context): String {
        val dir = picturesDir(ctx, HELD).apply { deleteRecursively(); mkdirs() }
        for (live in livePictures(ctx)) if (live.isFile) runCatching { live.copyTo(java.io.File(dir, slotOf(live.name)), overwrite = true) }
        return HELD
    }
    private const val HELD = "held" // (saved looks name a uuid folder, or the library)

    private fun refsOf(id: String): JSONObject? = if (id.startsWith(LIB)) runCatching { JSONObject(id.removePrefix(LIB)) }.getOrNull() else null

    /** Looks saved before 2026-10-07 kept a folder of picture copies: each becomes library names (the copies join the
     *  library, the folder goes); a look with an empty folder says [NO_PICTURES]. At app start, cheap once done. */
    fun migratePictureFolders(ctx: Context, prefs: SharedPreferences) {
        val looks = load(prefs)
        val done = ArrayList<java.io.File>()
        val converted = looks.map { look ->
            val id = look.values[PICTURES] as? String ?: return@map look
            if (id == NO_PICTURES || id.startsWith(LIB)) return@map look
            val dir = picturesDir(ctx, id)
            if (!dir.isDirectory) return@map look // (restored from a backup without it: left as it is, "leave the background alone")
            val o = JSONObject()
            var complete = true
            for (file in dir.listFiles().orEmpty()) {
                if (file.name.endsWith(".framing")) continue
                val name = PictureLibrary.nameOf(ctx, file)
                if (name == null) { complete = false; continue } // (couldn't be copied: the folder stays for the next start)
                val slot = JSONObject().put("p", name)
                java.io.File(dir, file.name + ".framing").takeIf { it.isFile }?.let { slot.put("f", it.readText()) }
                o.put(slotOf(file.name), slot)
            }
            // the folder goes only once every picture is in the library (review session 2026-10-07: a failed copy lost it)
            if (!complete) return@map look
            done.add(dir)
            Look(look.name, look.values + (PICTURES to if (o.length() == 0) NO_PICTURES else LIB + o.toString()), look.remembered)
        }
        if (done.isEmpty()) return
        // the looks naming the library are on disk before their folders go (review 2026-10-08 area 4 #7: a folder went
        // first, and a process killed before the async write left the look naming it, its pictures lost); killed in
        // between, a folder stays behind unused
        save(prefs, converted, now = true)
        done.forEach { it.deleteRecursively() }
    }

    /** How many saved looks name library picture [name]: the library's "forget" warns with it. */
    fun looksUsingPicture(prefs: SharedPreferences, name: String): Int = load(prefs).count { look ->
        (look.values[PICTURES] as? String)?.let { refsOf(it) }?.let { o -> o.keys().asSequence().any { o.getJSONObject(it).optString("p") == name } } == true
    }

    /** The look's pictures become the background ones (a built-in look: none); a look saved before pictures were
     *  part of looks, or whose picture folder is missing (restored from a backup without it), leaves them as they are. */
    fun applyPictures(ctx: Context, look: Look, files: List<java.io.File> = livePictures(ctx)) {
        val id = look.values[PICTURES] as? String ?: return
        val refs = refsOf(id)?.let { slotsOf(it) }
        if (refs != null) {
            for (live in files) {
                if (live.name.endsWith(".framing")) continue
                val slot = refs[slotOf(live.name)]
                val framing = PictureFraming.fileFor(live)
                if (slot == null) { live.delete(); framing.delete(); continue }
                val source = PictureLibrary.file(ctx, slot.optString("p"))
                if (!source.isFile) continue // forgotten from the library: this slot stays as it is
                source.copyTo(live, overwrite = true)
                if (slot.has("f")) framing.writeText(slot.getString("f")) else framing.delete()
            }
            reload(ctx)
            return
        }
        val dir = if (id == NO_PICTURES) null else picturesDir(ctx, id)
        // its pictures aren't there: the background stays (review 2026-10-06: it was deleted)
        if (dir != null && !dir.isDirectory) { reload(ctx); return }
        val folder = dir?.listFiles().orEmpty().associateBy { slotOf(it.name) }
        for (live in files) {
            val saved = folder[slotOf(live.name)]
            if (saved?.exists() == true) saved.copyTo(live, overwrite = true) else live.delete()
        }
        reload(ctx)
    }

    /** Whether what [look] sets differs from what is set now (a value, or the background pictures). */
    fun isTweaked(ctx: Context, look: Look): Boolean {
        val now = ctx.prefs().all
        if (applied(look, SavedItemDefaults.takeNewDefaults(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)))
                .any { (key, value) -> inScope(key) && !KnownDefaults.same(key, now[key], value) }) return true
        val id = look.values[PICTURES] as? String ?: return false // saved before pictures were in themes: not compared
        // a picture by its size, a framing (a few bytes of the same length whatever it says) by its content
        fun sig(f: java.io.File): Any = if (f.name.endsWith(".framing")) f.readText() else f.length()
        val live = livePictures(ctx).filter { it.exists() }.associate { slotOf(it.name) to sig(it) }
        refsOf(id)?.let { slotsOf(it) }?.let { refs ->
            val saved = HashMap<String, Any>()
            for ((slot, o) in refs) {
                val lib = PictureLibrary.file(ctx, o.optString("p"))
                saved[slot] = if (lib.isFile) lib.length() else -1L
                if (o.has("f")) saved["$slot.framing"] = o.getString("f")
            }
            return saved != live
        }
        val saved = if (id == NO_PICTURES) emptyMap() else picturesDir(ctx, id).listFiles()?.associate { slotOf(it.name) to sig(it) } ?: emptyMap()
        return saved != live
    }

    fun deletePictures(ctx: Context, look: Look) {
        (look.values[PICTURES] as? String)?.takeIf { it != NO_PICTURES && !it.startsWith(LIB) }?.let { picturesDir(ctx, it).deleteRecursively() }
    }

    /** The pictures as they are now, as a look of their own (for putting them back on Cancel). */
    fun currentPictures(ctx: Context): Look = Look("", mapOf(PICTURES to savePictures(ctx)))

    /** The themes that ship with the app, each setting the whole Theme and Emoji groups ([complete]): Dynamic (the
     *  default, Android 12+) first; Midnight and Daylight one colour set each; the others a light and a dark one
     *  following the system. Gaps not named are the default 1% / 2%. */
    fun builtIn(ctx: Context): List<Look> = listOfNotNull(
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            Look(ctx.getString(R.string.theme_preset_dynamic), complete(mapOf(PICTURES to NO_PICTURES,
                Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
                // the phone's colours, light or dark as the phone is
                Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_DYNAMIC, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_DYNAMIC,
                Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to true)))
        else null,
        // Curmudgeon's own: black, the orange swipe trail and suggestions, the suggestions close together (2026-10-06)
        Look(ctx.getString(R.string.theme_preset_curmudgeon), complete(mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_CURMUDGEON, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_CURMUDGEON,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to false,
            Settings.PREF_SUGGESTION_WORD_PADDING to 5))),
        Look(ctx.getString(R.string.theme_preset_midnight), complete(mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
            // one colour set, always (the light / dark switch off)
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_BLACK, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_BLACK,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to false))),
        Look(ctx.getString(R.string.theme_preset_daylight), complete(mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
            // one colour set, always (the light / dark switch off)
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_LIGHT,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to false))),
        Look(ctx.getString(R.string.theme_preset_holo), complete(mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_HOLO, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_HOLO,
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_HOLO_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_HOLO_WHITE,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_THEME_DAY_NIGHT to true,
            // the classic look: the keyboard's own gaps (config_key_*_gap_holo: 0.5% / 0.75%) and bold key text, what the
            // Holo style drew when they weren't set (2026-10-06: 0% was the long-press panels' gap, not the keyboard's)
            Settings.PREF_KEY_HORIZONTAL_GAP to 0.5f, Settings.PREF_KEY_VERTICAL_GAP to 0.75f, Settings.PREF_KEY_TEXT_BOLD to true))),
        Look(ctx.getString(R.string.theme_preset_paper), complete(mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_MATERIAL, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_MATERIAL,
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_DARK,
            Settings.PREF_THEME_KEY_BORDERS to false, Settings.PREF_THEME_DAY_NIGHT to true))),
        Look(ctx.getString(R.string.theme_preset_ocean), complete(mapOf(PICTURES to NO_PICTURES,
            Settings.PREF_THEME_STYLE to KeyboardTheme.STYLE_ROUNDED, Settings.PREF_ICON_STYLE to KeyboardTheme.STYLE_ROUNDED,
            Settings.PREF_THEME_COLORS to KeyboardTheme.THEME_OCEAN_LIGHT, Settings.PREF_THEME_COLORS_NIGHT to KeyboardTheme.THEME_OCEAN,
            Settings.PREF_THEME_KEY_BORDERS to true, Settings.PREF_KEY_HORIZONTAL_GAP to 1.0f, Settings.PREF_KEY_VERTICAL_GAP to 1.5f,
            Settings.PREF_THEME_DAY_NIGHT to true))),
    )

    /** The appearance values as they are now (plain keys, the current keyboard's set). */
    fun current(prefs: SharedPreferences): Map<String, Any?> = prefs.all.filterKeys { inScope(it) }

    /** [values] replace the current appearance values as a whole (keys missing from it go back to default);
     *  to apply a theme on top of the current values, pass `current + theme`. The live keyboard reloads. */
    fun apply(ctx: Context, values: Map<String, Any?>, prefs: SharedPreferences = ctx.prefs()) {
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

    /** [look] (just updated with the edited keyboard's look) on another [keyboard] that uses it and was ticked in the
     *  update dialog (2026-10-11): its settings and pictures, the name, no Custom label. The open screen's Discard puts
     *  that keyboard's pictures back too (held first). */
    fun applyToOther(ctx: Context, keyboard: helium314.keyboard.latin.settings.SettingsSubtype, look: Look) {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        // (one without a set of its own yet reads the shared set, so it was listed: it gets its set now, as opening its
        // settings gives one, then the change; before, it was skipped; review 2026-10-11 C8)
        // (owner 2026-10-11: lookups never create records; a listed one gets its set from the net, one neither listed nor
        // with a set is left alone)
        val id = KeyboardProfiles.idForListed(real, keyboard) ?: return
        // (its own set, with one set of settings too: a switched-off keyboard is asked by its own choice; owner
        // 2026-10-11)
        val view = helium314.keyboard.latin.settings.ProfilePreferences(real, ownSet = true) { id }
        apply(ctx, current(view) + applied(look, SavedItemDefaults.takeNewDefaults(real)), view)
        view.edit { putString(PREF_SELECTED, look.name) }
        CustomLabels.clear(view, CustomLabels.Kind.THEME)
        val files = KeyboardProfiles.pictureFiles(ctx, id)
        AppearanceDraft.holdFilesOpen(files)
        applyPictures(ctx, look, files)
    }

    /** What a theme saves: the values set now (picked), and every other theme setting as "not set" (null); the value
     *  each had is remembered beside it (Look.remembered, [SavedItemDefaults]), and the switch "Saved items take new
     *  defaults" decides which applies (2026-10-11; before 2026-10-02 a theme had only the values set, from 2026-10-02
     *  to 0.3.008 the defaults were written out as picks, and from then to 2026-10-11 the defaults followed). */
    fun snapshot(prefs: SharedPreferences): Map<String, Any?> = keys.associateWith { null } + current(prefs)

    /** Theme [name] as Save as new or Update saves it from the edited keyboard ([snapshot], its pictures), [chosen] the
     *  theme on it: what is still at the value [chosen] remembered for its default stays at its default
     *  ([SavedItemDefaults.forSave]). */
    fun saved(ctx: Context, name: String, chosen: Look?): Look {
        val (values, remembered) = SavedItemDefaults.forSave(snapshot(ctx.prefs()) + (PICTURES to savePictures(ctx)),
            chosen?.let { rememberedOf(it) } ?: emptyMap(),
            SavedItemDefaults.takeNewDefaults(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)))
        return Look(name, values, remembered)
    }

    /** What [look] holds at its default with the value it remembered (a built-in remembers none: it follows). */
    fun rememberedOf(look: Look): Map<String, Any> =
        look.remembered.filterKeys { look.values.containsKey(it) && look.values[it] == null && inScope(it) }

    /** Everything that draws the keyboard reads the preferences again. */
    fun reload(ctx: Context) {
        helium314.keyboard.keyboard.emoji.SupportedEmojis.load(ctx) // which emojis show (the emoji version)
        Settings.clearCachedBackgroundImages()
        KeyboardIconsSet.needsReload = true
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    }

    // ---- the saved list; every value carries its type, as JSON alone can't tell an int from a float ----

    fun load(prefs: SharedPreferences): List<Look> = readEach(prefs, PREF, ::read).first

    /** How many saved themes couldn't be read (skipped by [load], set aside by the next [save]). */
    fun skipped(prefs: SharedPreferences): Int = readEach(prefs, PREF, ::read).second

    private fun read(o: JSONObject): Look {
        val v = o.getJSONObject("values")
        return Look(o.getString("name"), v.keys().asSequence().associateWith { key -> fromJson(v.getJSONObject(key)) }, rememberedIn(v))
    }

    /** In every saved look, [keys] reading [from] read [to] (null: not set). */
    fun replaceValue(prefs: SharedPreferences, keys: Collection<String>, from: String, to: String?) {
        val looks = load(prefs)
        if (looks.none { look -> keys.any { look.values[it] == from } }) return
        save(prefs, looks.map { look ->
            if (keys.none { look.values[it] == from }) look
            else Look(look.name, look.values.mapValues { (key, value) -> if (key in keys && value == from) to else value }, look.remembered)
        })
    }

    /** [now]: written before this returns (commit), not in the background. */
    /** A saved theme renamed to [new], or deleted ([new] null): every keyboard that chose [old] follows, and the open Appearance screen's snapshot (see
     *  [KeyboardProfiles.replaceValueEverywhere]; deleted: removed, the default). */
    fun choiceRenamed(ctx: Context, old: String, new: String?) {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        if (new == null) CustomLabels.detach(real, PREF_SELECTED, old, CustomLabels.Kind.THEME) // (they keep the look: Custom)
        KeyboardProfiles.replaceValueEverywhere(real, PREF_SELECTED, old, new)
        // (the open screen's Discard undoes it everywhere: its snapshot holds every keyboard's choice, 2026-10-11)
    }

    fun save(prefs: SharedPreferences, looks: List<Look>, now: Boolean = false) {
        keepBrokenAside(prefs, PREF, ::read)
        val arr = JSONArray()
        for (look in looks) arr.put(JSONObject().apply {
            put("name", look.name)
            // a value at its default is saved as {"d":true} (read back as null, see snapshot)
            put("values", JSONObject().also { v -> look.values.forEach { (key, value) ->
                (if (value == null) atDefault(look.remembered[key]) else toJson(value))?.let { v.put(key, it) } } })
        })
        prefs.edit(commit = now) { putString(PREF, arr.toString()) }
    }

    /** A saved item's setting at its default: {"d":true}, with the value it had when saved ({"d":true,"v":{…}}). */
    internal fun atDefault(remembered: Any?): JSONObject =
        JSONObject().put("d", true).also { o -> toJson(remembered)?.let { o.put("v", it) } }

    /** The remembered values in a saved item's JSON values ([atDefault]). */
    internal fun rememberedIn(values: JSONObject): Map<String, Any> = values.keys().asSequence().mapNotNull { key ->
        values.getJSONObject(key).optJSONObject("v")?.let { fromJson(it) }?.let { key to it } }.toMap()

    /** What [look] applies: its unpicked settings at today's default or as saved ([takeNew], [SavedItemDefaults]); a
     *  built-in has none remembered, so it follows the defaults. */
    fun applied(look: Look, takeNew: Boolean): Map<String, Any?> = SavedItemDefaults.applied(look.values, look.remembered, takeNew)

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

/** The items of the stored list under [key] that [read] can read, and how many it couldn't: a bad one is skipped, the
 *  rest still load (re-review R9 2026-10-11: one bad item emptied the list). Text that isn't a list counts as one. */
internal fun <T : Any> readEach(prefs: SharedPreferences, key: String, read: (JSONObject) -> T): Pair<List<T>, Int> {
    val stored = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return emptyList<T>() to 0
    val arr = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList<T>() to 1
    val items = (0 until arr.length()).mapNotNull { i -> runCatching { read(arr.getJSONObject(i)) }.getOrNull() }
    return items to arr.length() - items.size
}

/** The stored list under [key] has something [read] can't read (the whole text, or an item skipped on load): the text
 *  as stored is kept under "[key]_broken_<time>" before anything is written over it (review 2026-10-06 Low: a load
 *  failure turned into an empty save; R9 2026-10-11: one bad item, and the good ones went with it). */
internal fun keepBrokenAside(prefs: SharedPreferences, key: String, read: (JSONObject) -> Any = { it }) {
    if (readEach(prefs, key, read).second == 0) return
    prefs.edit { putString("${key}_broken_${System.currentTimeMillis()}", prefs.getString(key, null)) }
}

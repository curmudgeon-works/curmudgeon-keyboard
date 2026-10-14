// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import helium314.keyboard.latin.common.PictureFraming
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.prefs
import org.json.JSONObject
import java.io.File

/**
 * What the Appearance screen looked like when it was opened. Every change on the screen is applied at once
 * (the live keyboard is the preview), so "reject all" means putting this snapshot back: the appearance
 * preferences and the custom background / font files.
 * The snapshot is also on disk while the screen is open: changes not accepted are discarded when the app is left
 * (SettingsActivity.onStop) and, after a crash, when the app starts again ([recoverAfterCrash]).
 */
class AppearanceDraft private constructor(
    private var prefs: Map<String, Any?>,
    private var files: Map<File, Saved?>, // the live file -> its saved copy, null when it didn't exist
    private val dir: File,
    private val setId: Int = KeyboardProfiles.SHARED, // the set the screen edited (see PrefsDraft)
    // what the screen's saved items reach beyond the edited set, as stored (every keyboard's own set too): the saved
    // themes, each set's theme choice and palettes, so Discard undoes a rename or delete everywhere it reached (owner
    // 2026-10-11: "Every action on an editing screen ... is held until Save; Discard undoes it everywhere it reached")
    private var reached: Map<String, Any?> = emptyMap(),
) {
    /** A copy of a custom file, with the size and time the live one had (enough to tell a change, no reading). */
    class Saved(val copy: File, val length: Long, val modified: Long)

    // fonts and palettes deleted while the screen is open ("font:<name>", "palette:<name>"): done for good when the changes
    // are kept, undone with them (owner 2026-10-10: "so discard should bring the font back"). Until then a deleted font's
    // file waits in the snapshot folder, and only the edited keyboard has let go of it.
    private val held = LinkedHashSet<String>()

    /** Font [name] deleted with the screen open: its file moves into the snapshot folder (out of the font list). False
     *  when it couldn't be moved: then it's deleted at once, as without the screen. */
    fun holdFontDelete(ctx: Context, name: String): Boolean {
        val target = File(File(dir, HELD_FONTS), name)
        target.parentFile?.mkdirs()
        if (!File(helium314.keyboard.keyboard.FontLibrary.dir(ctx), name).renameTo(target)) return false
        held += FONT + name
        writeHeld(dir, held)
        version.intValue++
        return true
    }

    /** [more] files (another keyboard's pictures, about to be written by the update dialog) join the snapshot: Discard
     *  puts them back too. Files already in it stay as they were taken. */
    fun holdFiles(more: List<File>) {
        val added = more.filter { it !in files }.associateWith { live ->
            if (live.exists()) Saved(live.copyTo(File(dir, live.name), overwrite = true), live.length(), live.lastModified()) else null // (another set: its own names)
        }
        if (added.isEmpty()) return
        files = files + added
        writeSnapshot(dir, setId, prefs, files, reached)
    }

    /** Palette [name] deleted with the screen open: the edited keyboard's part is done (the snapshot puts it back), the
     *  other keyboards follow only when the changes are kept. */
    fun holdPaletteDelete(name: String) {
        held += PALETTE + name
        writeHeld(dir, held)
        version.intValue++
    }

    /** True when a preference or file in the snapshot's scope differs from it, or something was deleted. */
    fun hasChanges(ctx: Context): Boolean {
        if (held.isNotEmpty()) return true
        if (reachedNow(ctx) != reached) return true
        if (changedKeys(ctx).isNotEmpty()) return true
        return files.any { (live, saved) ->
            if (saved == null) live.exists() else !live.exists() || live.length() != saved.length || live.lastModified() != saved.modified
        }
    }

    /** The preferences whose value differs from the snapshot. */
    fun changedKeys(ctx: Context): Set<String> {
        val now = AppearanceLooks.screenValues(
            ProfilePreferences(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)) { setId })
        return (now.keys + prefs.keys).filterTo(HashSet()) { !KnownDefaults.same(it, now[it], prefs[it]) }
    }

    /** The names of the custom files (background images, fonts) that differ from the snapshot. */
    fun changedFiles(): Set<String> = files.filter { (live, saved) ->
        if (saved == null) live.exists() else !live.exists() || live.length() != saved.length || live.lastModified() != saved.modified
    }.keys.mapTo(HashSet()) { it.name }

    /** Back to the snapshot; the live keyboard reloads. */
    fun reject(ctx: Context) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("Appearance draft put back")
        for ((live, saved) in files) {
            if (saved == null) live.delete()
            else { saved.copy.copyTo(live, overwrite = true); live.setLastModified(saved.modified) }
        }
        for (entry in held) if (entry.startsWith(FONT)) { // the deleted fonts come back (palettes: in the snapshot's prefs)
            val name = entry.removePrefix(FONT)
            val live = File(helium314.keyboard.keyboard.FontLibrary.dir(ctx), name)
            if (!live.exists()) File(File(dir, HELD_FONTS), name).renameTo(live)
        }
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        val now = reachedNow(ctx)
        if (now != reached) real.edit().apply {
            for (key in now.keys) if (key !in reached) remove(key)
            for ((key, value) in reached) if (now[key] != value) KeyboardProfiles.put(this, key, value)
        }.commit()
        AppearanceLooks.applyScreen(ctx, prefs, ProfilePreferences(real) { setId })
        discard()
    }

    /** [keys] are kept as they are now ("Save and exit" on a screen opened from Appearance, the palette editor): the
     *  snapshot takes their values, so neither the cross nor leaving the app undoes them. */
    fun rebase(ctx: Context, keys: Set<String>) {
        val now = AppearanceLooks.screenValues(
            ProfilePreferences(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)) { setId })
        prefs = prefs.filterKeys { it !in keys } + now.filterKeys { it in keys }
        // (the palette editor's "Save and exit" keeps a palette's rename too, wherever it reached)
        if (keys.any { reachesBeyond(it) }) reached = reachedNow(ctx)
        writeSnapshot(dir, setId, prefs, files, reached)
        version.intValue++
        helium314.keyboard.latin.utils.SettingsEventLog.log("Appearance snapshot rebased: $keys")
    }

    /** Keeps what is on the phone now; only the snapshot goes. Deletes made on the screen are done for good now: every
     *  keyboard and saved theme lets go of a deleted font, the other keyboards of a deleted palette. */
    fun accept(ctx: Context) {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        for (entry in held) {
            if (entry.startsWith(FONT)) helium314.keyboard.keyboard.FontLibrary.forgetEverywhere(ctx, entry.removePrefix(FONT))
            else if (entry.startsWith(PALETTE))
                helium314.keyboard.keyboard.KeyboardTheme.followInHiddenSets(ProfilePreferences(real) { setId }, entry.removePrefix(PALETTE), null)
        }
        discard() // (the held font files go with the folder)
    }

    private fun discard() {
        dir.deleteRecursively()
        if (active === this) active = null
        version.intValue++
    }

    companion object {
        /** Bumped when the snapshot goes (accept, reject, leaving): what shows "unsaved" reads it, so it's drawn again
         *  when that ends without any setting changing (2026-10-06: the Themes row kept "(unsaved)" after the tick). */
        val version = androidx.compose.runtime.mutableIntStateOf(0)

        private fun currentPrefs(ctx: Context): Map<String, Any?> = AppearanceLooks.screenValues(ctx.prefs())

        private fun liveFiles(ctx: Context) = listOf(
            Settings.getCustomBackgroundFile(ctx, false, false), Settings.getCustomBackgroundFile(ctx, false, true),
            Settings.getCustomBackgroundFile(ctx, true, false), Settings.getCustomBackgroundFile(ctx, true, true),
        ).flatMap { listOf(it, PictureFraming.fileFor(it)) } // each picture with its framing (the fonts, emoji too, are
        // a list of files kept by name: only the choices change, preferences)

        private fun dir(ctx: Context) = File(ctx.filesDir, "appearance_draft")
        private const val PREFS_FILE = "draft_prefs.json" // written last: its presence means a complete snapshot
        private const val HELD_FILE = "held_deletes.json"
        private const val HELD_FONTS = "held_fonts"
        private const val FONT = "font:"
        private const val PALETTE = "palette:"

        private fun writeHeld(dir: File, held: Collection<String>) =
            File(dir, HELD_FILE).writeText(org.json.JSONArray(held.toList()).toString())

        /** The draft of the open Appearance screen; it outlives the screen's composition (rotation, search). */
        private var active: AppearanceDraft? = null

        /** The running draft, or a fresh snapshot: the preference values in scope and a copy of every custom file. */
        fun of(ctx: Context): AppearanceDraft = active ?: start(ctx).also { active = it }

        /** The running draft (the Appearance screen open), without starting one: what only shows "unsaved" mustn't open
         *  a draft (review 2026-10-06: the Themes row in search results did, and leaving the app undid the theme). */
        fun activeOrNull(): AppearanceDraft? = active

        /** The screen is left with nothing changed: the snapshot goes (the next visit starts from what is there then). */
        fun close() { active?.discard() }

        /** Tests: the process died with the screen open (the draft's folder stays for [recoverAfterCrash]). */
        @androidx.annotation.VisibleForTesting internal fun forgetActiveForTest() { active = null }

        /** [AppearanceDraft.holdFiles] of the running draft, if there is one. */
        fun holdFilesOpen(files: List<File>) { active?.holdFiles(files) }

        /** [AppearanceDraft.rebase] of the running draft, if there is one. */
        fun rebaseOpen(ctx: Context, keys: Set<String>) { active?.rebase(ctx, keys) }


        /** The app is left (or the screen goes to the background) with the screen open: changes not accepted are undone. */
        fun rejectOpen(ctx: Context) { active?.reject(ctx) }

        private fun start(ctx: Context): AppearanceDraft {
            val dir = dir(ctx).apply { deleteRecursively(); mkdirs() }
            val files = liveFiles(ctx).associateWith { live ->
                if (live.exists()) Saved(live.copyTo(File(dir, live.name), overwrite = true), live.length(), live.lastModified()) else null
            }
            val prefs = currentPrefs(ctx)
            val setId = PrefsDraft.currentSetId(ctx)
            val reached = reachedNow(ctx)
            writeSnapshot(dir, setId, prefs, files, reached)
            return AppearanceDraft(prefs, files, dir, setId, reached)
        }

        /** A stored key beyond the edited set that the screen's saved items reach ([reached]): the saved themes; in
         *  any set, the theme choice, the light and dark colours' choice, the palettes, and their "at default" marks;
         *  in every keyboard's own set, its theme settings. */
        private fun reachesBeyond(stored: String): Boolean {
            val own = KeyboardProfiles.splitOwnKey(stored)
            val plain = (own?.second ?: stored).removePrefix(KeyboardProfiles.TOMBSTONE)
            // (a keyboard's own theme settings: the update dialog writes them on the keyboards ticked there)
            if (own != null && (AppearanceLooks.inScope(plain) || plain == CustomLabels.Kind.THEME.key)) return true
            return plain == AppearanceLooks.PREF || plain == AppearanceLooks.PREF_SELECTED || plain == helium314.keyboard.keyboard.FontLibrary.PREF_HIDDEN
                || plain == Settings.PREF_THEME_COLORS || plain == Settings.PREF_THEME_COLORS_NIGHT
                || plain.startsWith(Settings.PREF_USER_COLORS_PREFIX) || plain.startsWith(Settings.PREF_USER_ALL_COLORS_PREFIX)
                || plain.startsWith(Settings.PREF_USER_MORE_COLORS_PREFIX)
        }
        private fun reachedNow(ctx: Context): Map<String, Any?> =
            helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx).all.filterKeys { reachesBeyond(it) }

        private fun writeSnapshot(dir: File, setId: Int, prefs: Map<String, Any?>, files: Map<File, Saved?>, reached: Map<String, Any?>) {
            val json = JSONObject().put("set", setId)
            json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            json.put("reached", JSONObject().also { o -> reached.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            json.put("files", JSONObject().also { o -> files.forEach { (live, saved) ->
                o.put(live.path, saved?.let { JSONObject().put("length", it.length).put("modified", it.modified) } ?: JSONObject.NULL)
            } })
            File(dir, PREFS_FILE).writeText(json.toString())
        }

        /** A snapshot left on disk by a process that died with Appearance open: put it back. Called at app start. */
        fun recoverAfterCrash(ctx: Context) {
            if (active != null) return
            val dir = dir(ctx)
            val json = runCatching { JSONObject(File(dir, PREFS_FILE).readText()) }.getOrNull()
            if (json == null) { dir.deleteRecursively(); return }
            runCatching {
                val p = json.getJSONObject("prefs")
                val prefs = p.keys().asSequence().associateWith { AppearanceLooks.fromJson(p.getJSONObject(it)) }
                val f = json.getJSONObject("files")
                val files = f.keys().asSequence().associate { path ->
                    val live = File(path)
                    live to (f.optJSONObject(path)?.let { Saved(File(dir, live.name), it.getLong("length"), it.getLong("modified")) })
                }
                // (a snapshot from before 2026-10-11 has no "reached": what it reached then stays as it is now)
                val r = json.optJSONObject("reached")
                val reached = r?.keys()?.asSequence()?.associateWith { AppearanceLooks.fromJson(r.getJSONObject(it)) }
                    ?: reachedNow(ctx)
                val draft = AppearanceDraft(prefs, files, dir, json.optInt("set", KeyboardProfiles.SHARED), reached)
                // (the deletes made before the crash: undone too, their fonts moved back)
                runCatching { org.json.JSONArray(File(dir, HELD_FILE).readText()) }.getOrNull()
                    ?.let { a -> for (i in 0 until a.length()) draft.held += a.getString(i) }
                draft.reject(ctx)
            }.onFailure { dir.deleteRecursively() }
        }
    }
}

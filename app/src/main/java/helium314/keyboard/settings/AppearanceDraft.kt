// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
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
 * (SettingsActivity.onStop) and, after a crash, when the app starts again ([recoverAfterCrash]); what the palette
 * editor saved stays then ([SubScreenWrites]), only Appearance's own Discard undoes it too.
 */
class AppearanceDraft private constructor(
    private val prefs: Map<String, Any?>,
    private var files: Map<File, Saved?>, // the live file -> its saved copy, null when it didn't exist
    private val dir: File,
    private val setId: String = KeyboardProfiles.SHARED, // the set the screen edited (see PrefsDraft)
    // what the screen's saved items reach beyond the edited set, as stored (every keyboard's own set too): the saved
    // themes, each set's theme choice and palettes, so Discard undoes a rename or delete everywhere it reached (owner
    // 2026-10-11: "Every action on an editing screen ... is held until Save; Discard undoes it everywhere it reached")
    private val reached: Map<String, Any?> = emptyMap(),
    // the theme Custom labels and their origins stored when the screen opened (any set): Discard puts them back exactly
    // (review D2: the ones added since go; E3: a cleared one, "from Work" too, comes back); not part of hasChanges, as a
    // label alone is no change of the screen. Null: left as they are
    private val labels: CustomLabels.Opened? = null,
    // what the palette editor wrote with its Save: kept when the app is left (owner 2026-10-11, S2: "yes let's keep
    // saved subscreen changes"), undone by Appearance's own Discard
    private val writes: SubScreenWrites = SubScreenWrites(dir, null) { false },
    // the fonts and pictures in their lists when the screen opened (file names): one there now and not then was added
    // with the screen open (review 2026-10-12 B5). Null: not known (a snapshot recovered after a crash), none counts
    private val fontsAtStart: Set<String>? = null,
    private val picturesAtStart: Set<String>? = null,
) {
    /** A copy of a custom file, with the size and time the live one had (enough to tell a change, no reading). */
    class Saved(val copy: File, val length: Long, val modified: Long)

    // fonts and pictures of "Choose a picture" deleted while the screen is open: done for good when the changes are
    // kept, undone with them (owner 2026-10-10: "so discard should bring the font back"). Until then a deleted font's or
    // picture's file waits in the snapshot folder (held_fonts, held_pictures; out of its list), and only the edited
    // keyboard has let go of a font; what a picture's delete did to the saved themes is in the snapshot's prefs. The
    // held ones are the files in those folders, no list of them (round-5 review 20261013_L17: a list written after the
    // move could miss one, and that file went with the folder)

    /** Font [name] deleted with the screen open: its file moves into the snapshot folder (out of the font list). False
     *  when it couldn't be moved: then it's deleted at once, as without the screen. */
    fun holdFontDelete(ctx: Context, name: String): Boolean {
        val target = File(File(dir, HELD_FONTS), name)
        target.parentFile?.mkdirs()
        if (!File(helium314.keyboard.keyboard.FontLibrary.dir(ctx), name).renameTo(target)) return false
        version.intValue++
        return true
    }

    /** Library picture [picture] deleted from "Choose a picture" with the screen open (the saved themes have let go of
     *  it, a prefs change Discard puts back): its file moves into the snapshot folder, out of the list (review H1, owner:
     *  "make picture disappear yes"). False when it couldn't be moved: then it's deleted at once, as without the screen. */
    fun holdPictureDelete(picture: File): Boolean {
        val target = File(File(dir, HELD_PICTURES), picture.name)
        target.parentFile?.mkdirs()
        if (!picture.renameTo(target)) return false
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
        writeSnapshot(dir, setId, prefs, files, reached, labels)
    }

    // palettes the palette editor saved while the screen is open (its Save only keeps them, this screen's Save or
    // Discard decides): the Discard question names them (owner 2026-10-11: "send the discard warning yes")
    private val savedPalettes = LinkedHashSet<String>()

    /** The palettes (stored names) saved in the palette editor since the screen opened. */
    fun savedPalettes(): List<String> = savedPalettes.toList()

    /** The palette editor opens (or "Add your own" is about to make a palette for it): what it changes from here is
     *  told at its Save ([paletteSaved]). */
    fun paletteEditorOpened(ctx: Context) = writes.opened(ctx)

    /** The palette editor saved, the palette opened as [opened] and now called [now] (renamed: one entry, the new name). */
    fun paletteSaved(opened: String, now: String) {
        writes.saved()
        savedPalettes -= opened
        savedPalettes += now
        writeAtomically(File(dir, SUB_SAVES_FILE), org.json.JSONArray(savedPalettes.toList()).toString())
    }

    /** The fonts added to the font list since the screen opened (file names); one deleted (held) and added again
     *  under its name too. Appearance's Discard removes them, every one (review 2026-10-12 B5; round-5 review
     *  20261013_L21, owner "D": one something outside the edit used stayed, the Discard question said it goes); leaving the app, or the undo after a crash, keeps them, like the palette editor's saves. */
    fun addedFonts(ctx: Context): List<String> = added(fontsAtStart, helium314.keyboard.keyboard.FontLibrary.dir(ctx), HELD_FONTS)

    /** The pictures added to the list of "Choose a picture" since the screen opened (file names), as [addedFonts]. */
    fun addedPictures(ctx: Context): List<String> = added(picturesAtStart, helium314.keyboard.latin.common.PictureLibrary.dir(ctx), HELD_PICTURES)

    // (one in the list under the name of a held file: deleted and added again)
    private fun added(atStart: Set<String>?, folder: File, heldFolder: String): List<String> {
        atStart ?: return emptyList()
        val held = heldNames(dir, heldFolder)
        return libraryNames(folder).filter { it !in atStart || it in held }
    }

    /** True when a preference or file in the snapshot's scope differs from it, something was deleted, or a font or
     *  picture was added to its list (B5). */
    fun hasChanges(ctx: Context): Boolean {
        if (heldNames(dir, HELD_FONTS).isNotEmpty() || heldNames(dir, HELD_PICTURES).isNotEmpty()) return true
        if (addedFonts(ctx).isNotEmpty() || addedPictures(ctx).isNotEmpty()) return true
        if (reachedChanged(ctx)) return true
        if (changedKeys(ctx).isNotEmpty()) return true
        return files.any { (live, saved) ->
            if (saved == null) live.exists() else !live.exists() || live.length() != saved.length || live.lastModified() != saved.modified
        }
    }

    /** Whether what the saved items reach beyond the edited set differs from [reached] by meaning (review C9, owner: "yes
     *  go by meaning"): a keyboard's own set storing the value it already read (a switch turned on and off again) is
     *  no change. The stored values stay as picked; Discard still puts the raw snapshot back. */
    private fun reachedChanged(ctx: Context): Boolean {
        val all = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx).all
        val now = all.filterKeys { reachesBeyond(it) }
        if (now == reached) return false
        // (one not stored reads its default: owner 2026-10-11, no default marks, no common set to fall back on)
        return (now.keys + reached.keys).any { key ->
            val plain = LayoutDraft.plain(key)
            if (plain == AppearanceLooks.PREF) now[key] != reached[key] // (the saved themes: a JSON string)
            else !KnownDefaults.same(plain, now[key], reached[key])
        }
    }

    /** The preferences whose value differs from the snapshot; a palette on the app-wide list too (its Colors rows). */
    fun changedKeys(ctx: Context): Set<String> {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        val now = AppearanceLooks.screenValues(ProfilePreferences(real) { setId })
        val changed = (now.keys + prefs.keys).filterTo(HashSet()) { !KnownDefaults.same(it, now[it], prefs[it]) }
        val all = real.all
        (all.keys + reached.keys).filterTo(changed) { Palettes.isKey(it) && KeyboardProfiles.splitOwnKey(it) == null && all[it] != reached[it] }
        return changed
    }

    /** The names of the custom files (background images, fonts) that differ from the snapshot. */
    fun changedFiles(): Set<String> = files.filter { (live, saved) ->
        if (saved == null) live.exists() else !live.exists() || live.length() != saved.length || live.lastModified() != saved.modified
    }.keys.mapTo(HashSet()) { it.name }

    /** Back to the snapshot; the live keyboard reloads. [keepSubScreenSaves] (the app left, or the undo after a crash):
     *  what the palette editor saved stays as saved (a key Appearance changed too: the editor's saved value), and so do
     *  the fonts and pictures added to their lists; Appearance's Discard: undone too, the added fonts and pictures
     *  removed from their lists, every one (B5, 20261013_L21). */
    fun reject(ctx: Context, keepSubScreenSaves: Boolean = false) {
        val keep = keepSubScreenSaves && !writes.isEmpty()
        // (listed before anything is put back: a held font or picture coming back is no addition)
        val fontsAdded = if (keepSubScreenSaves) emptyList() else addedFonts(ctx)
        val picturesAdded = if (keepSubScreenSaves) emptyList() else addedPictures(ctx)
        helium314.keyboard.latin.utils.SettingsEventLog.log("Appearance draft put back" + if (keep) ", palette editor saves kept" else "")
        for ((live, saved) in files) {
            if (saved == null) live.delete()
            // (crash-safe: a put-back cut off leaves the live file as it was, B4w.3)
            else { helium314.keyboard.latin.utils.AtomicFiles.copy(saved.copy, live); live.setLastModified(saved.modified) }
        }
        putHeldBack(ctx, dir) // the deleted fonts and pictures come back (the themes naming them: in the snapshot's prefs)
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        val now = reachedNow(ctx)
        if (now != reached) real.edit().apply {
            for (key in now.keys) if (key !in reached) remove(key)
            for ((key, value) in reached) if (now[key] != value) KeyboardProfiles.put(this, key, value)
        }.commit()
        AppearanceLooks.applyScreen(ctx, prefs, ProfilePreferences(real) { setId })
        labels?.putBack(real, LABELS)
        if (keep) { writes.writeTo(real); AppearanceLooks.reload(ctx) }
        // the fonts and pictures added: out of their lists, once the edit's choices and themes are back as they were;
        // what still names one then lets go of it. A held one whose name an addition took comes back now
        if (removeAdded(ctx, fontsAdded, picturesAdded)) { putHeldBack(ctx, dir); AppearanceLooks.reload(ctx) }
        discard()
    }

    /** The fonts [fonts] and pictures [pictures] (file names) leave their lists through their plain deletes (20261013_L21,
     *  owner "D"): a font's settings and saved themes naming it let go of it ([FontLibrary.deleteNow]), the saved themes
     *  naming a picture let go of it ([AppearanceLooks.removeFromList]; the keyboards' pictures are their own copies).
     *  One under the name of a held file is only its file going: the held one comes back under that name, and what
     *  names it is the snapshot's. @return whether there was one */
    private fun removeAdded(ctx: Context, fonts: List<String>, pictures: List<String>): Boolean {
        if (fonts.isEmpty() && pictures.isEmpty()) return false
        val heldFonts = heldNames(dir, HELD_FONTS)
        val heldPictures = heldNames(dir, HELD_PICTURES)
        for (name in fonts) {
            if (name in heldFonts) File(helium314.keyboard.keyboard.FontLibrary.dir(ctx), name).delete()
            else helium314.keyboard.keyboard.FontLibrary.deleteNow(ctx, name)
        }
        for (name in pictures) {
            val file = helium314.keyboard.latin.common.PictureLibrary.file(ctx, name)
            if (name in heldPictures) helium314.keyboard.latin.common.PictureLibrary.remove(file)
            else AppearanceLooks.removeFromList(ctx, file)
        }
        helium314.keyboard.latin.utils.SettingsEventLog.log("Appearance draft: fonts or pictures added since it opened removed")
        return true
    }

    // (no rebase: the palette editor's "Save and exit" leaves this snapshot as it is, so Appearance's Save or Discard
    // decides the editor's changes too; owner 2026-10-11, option 1, review B1)

    /** Keeps what is on the phone now; only the snapshot goes. Deletes made on the screen are done for good now: every
     *  keyboard and saved theme lets go of a deleted font. (A palette delete is done everywhere at once, the snapshot
     *  holds the list and every choice: owner 2026-10-11, palettes are one app-wide list; an old "palette:" entry of a
     *  snapshot from before means nothing.) */
    fun accept(ctx: Context) {
        for (name in heldNames(dir, HELD_FONTS)) helium314.keyboard.keyboard.FontLibrary.forgetEverywhere(ctx, name)
        discard() // (the held font and picture files go with the folder: a picture leaving the list is only its file going)
    }

    private fun discard() {
        helium314.keyboard.latin.utils.AtomicFiles.deleteDir(dir) // (one step: a cut-off delete leaves no part of the snapshot to put back, 20261013_L18)
        savedPalettes.clear()
        writes.clear()
        if (active === this) active = null
        version.intValue++
    }

    companion object {
        /** Bumped when the snapshot goes (accept, reject, leaving): what shows "unsaved" reads it, so it's drawn again
         *  when that ends without any setting changing (2026-10-06: the Themes row kept "(unsaved)" after the tick). */
        val version = androidx.compose.runtime.mutableIntStateOf(0)

        /** The screen's values in set [setId], the set the undo writes into ([reject]): read from the same set, so the
         *  snapshot and what it puts back can't be two sets. (Until 2026-10-12 read through the context's preferences:
         *  the same set for a settings screen, the keyboard in use for any other context.) */
        private fun currentPrefs(ctx: Context, setId: String): Map<String, Any?> = AppearanceLooks.screenValues(
            ProfilePreferences(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)) { setId })

        /** The background pictures of set [setId], the edited one (as [currentPrefs]; review L5.2: read through the
         *  context, which outside the settings screens is the keyboard in use), each with its framing; the main
         *  keyboard's while Appearance is common to all keyboards. (The fonts, emoji too, are a list of files kept by
         *  name: only the choices change, preferences.) */
        private fun liveFiles(ctx: Context, setId: String): List<File> {
            val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
            val id = if (KeyboardProfiles.isShared(real, KeyboardProfiles.Group.APPEARANCE)) KeyboardProfiles.SHARED else setId
            return KeyboardProfiles.pictureFiles(ctx, id)
        }

        // in the device-protected storage, with the font and picture lists: a held delete is a rename into it, and a
        // rename between the two storages is refused on an encrypted device, so the file was deleted at once (round-6
        // review 20261013_M1_2, owner 2026-10-13; no move of an old folder in the other storage: pre-launch)
        private fun dir(ctx: Context) = File(helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(ctx), "appearance_draft")

        // the files of a font or picture list (its pending folder is no file)
        private fun libraryNames(folder: File): List<String> =
            folder.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
        private const val PREFS_FILE = "draft_prefs.json" // written last: its presence means a complete snapshot
        private const val HELD_FONTS = "held_fonts"
        private const val HELD_PICTURES = "held_pictures"
        private const val SUB_SAVES_FILE = "sub_screen_saves.json"
        private val LABELS = listOf(CustomLabels.Kind.THEME)

        /** [text] into [file] through a temporary file renamed over it: a crash mid-write leaves the old file whole, never
         *  half a list (owner 2026-10-12 B4: a half-written held list lost the held fonts and pictures; shared since
         *  B4w.2, [helium314.keyboard.latin.utils.AtomicFiles]). */
        private fun writeAtomically(file: File, text: String) = helium314.keyboard.latin.utils.AtomicFiles.write(file, text)

        /** The names of the held files in [folder] of the snapshot folder [dir] (HELD_FONTS, HELD_PICTURES). */
        private fun heldNames(dir: File, folder: String): List<String> =
            File(dir, folder).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

        /** The held fonts and pictures (every file in the held folders) move back into their lists; one whose name is
         *  taken there again stays out. */
        private fun putHeldBack(ctx: Context, dir: File) {
            for (name in heldNames(dir, HELD_FONTS))
                putBack(File(File(dir, HELD_FONTS), name), File(helium314.keyboard.keyboard.FontLibrary.dir(ctx), name))
            for (name in heldNames(dir, HELD_PICTURES))
                putBack(File(File(dir, HELD_PICTURES), name), helium314.keyboard.latin.common.PictureLibrary.file(ctx, name))
        }

        private fun putBack(held: File, live: File) {
            if (live.exists()) return
            live.parentFile?.mkdirs()
            held.renameTo(live)
        }

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

        /** [AppearanceDraft.paletteEditorOpened] of the running draft, if there is one. */
        fun paletteEditorOpenedOpen(ctx: Context) { active?.paletteEditorOpened(ctx) }

        /** [AppearanceDraft.paletteSaved] of the running draft, if there is one. */
        fun paletteSavedOpen(opened: String, now: String) { active?.paletteSaved(opened, now) }

        /** Tests: the saved palettes as kept on disk with the snapshot. */
        @androidx.annotation.VisibleForTesting internal fun savedPalettesOnDisk(ctx: Context): List<String> = readSubSaves(dir(ctx))

        private fun readSubSaves(dir: File): List<String> =
            runCatching { org.json.JSONArray(File(dir, SUB_SAVES_FILE).readText()) }.getOrNull()
                ?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()


        /** The app is left (or the screen goes to the background) with the screen open: changes not accepted are undone,
         *  but not what the palette editor saved (owner 2026-10-11, S2: "yes let's keep saved subscreen changes"). */
        fun rejectOpen(ctx: Context) { active?.reject(ctx, keepSubScreenSaves = true) }

        private fun start(ctx: Context): AppearanceDraft {
            val dir = dir(ctx).apply { deleteRecursively(); mkdirs() }
            val setId = PrefsDraft.currentSetId(ctx)
            val files = liveFiles(ctx, setId).associateWith { live ->
                if (live.exists()) Saved(live.copyTo(File(dir, live.name), overwrite = true), live.length(), live.lastModified()) else null
            }
            val prefs = currentPrefs(ctx, setId)
            val reached = reachedNow(ctx)
            val labels = CustomLabels.Opened.of(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx), LABELS)
            writeSnapshot(dir, setId, prefs, files, reached, labels)
            return AppearanceDraft(prefs, files, dir, setId, reached, labels, SubScreenWrites(dir, null, ::recorded),
                libraryNames(helium314.keyboard.keyboard.FontLibrary.dir(ctx)).toSet(),
                libraryNames(helium314.keyboard.latin.common.PictureLibrary.dir(ctx)).toSet())
        }

        /** A stored key beyond the edited set that the screen's saved items reach ([reached]): the saved themes, the
         *  palettes (the app-wide list; a set's old copy too); in any set, the theme choice, the light and dark colors'
         *  choice; in every keyboard's own set, its theme settings. */
        private fun reachesBeyond(stored: String): Boolean {
            val own = KeyboardProfiles.splitOwnKey(stored)
            val plain = own?.second ?: stored
            // (a keyboard's own theme settings: the update dialog writes them on the keyboards ticked there; not its Custom
            // label, which the Themes row gives when it first shows one: that's no change of the screen)
            if (own != null && AppearanceLooks.inScope(plain)) return true
            return plain == AppearanceLooks.PREF || plain == AppearanceLooks.PREF_SELECTED
                || plain == Settings.PREF_THEME_COLORS || plain == Settings.PREF_THEME_COLORS_NIGHT
                || plain.startsWith(Settings.PREF_USER_COLORS_PREFIX) || plain.startsWith(Settings.PREF_USER_ALL_COLORS_PREFIX)
                || plain.startsWith(Settings.PREF_USER_MORE_COLORS_PREFIX)
        }
        /** A stored key the palette editor's save is recorded with ([SubScreenWrites]): what the undo puts back (on the
         *  screen, in any set, or reached beyond it), and the theme Custom label. */
        private fun recorded(stored: String): Boolean {
            val plain = LayoutDraft.plain(stored)
            return reachesBeyond(stored) || AppearanceLooks.onScreen(plain) || LABELS.any { it.key == plain }
        }
        private fun reachedNow(ctx: Context): Map<String, Any?> =
            helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx).all.filterKeys { reachesBeyond(it) }

        private fun writeSnapshot(dir: File, setId: String, prefs: Map<String, Any?>, files: Map<File, Saved?>, reached: Map<String, Any?>, labels: CustomLabels.Opened?) {
            val json = JSONObject().put("set", setId)
            labels?.toJson(json)
            json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            json.put("reached", JSONObject().also { o -> reached.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            json.put("files", JSONObject().also { o -> files.forEach { (live, saved) ->
                o.put(live.path, saved?.let { JSONObject().put("length", it.length).put("modified", it.modified) } ?: JSONObject.NULL)
            } })
            writeAtomically(File(dir, PREFS_FILE), json.toString())
        }

        /** A snapshot left on disk by a process that died with Appearance open: put it back. Called at app start. */
        fun recoverAfterCrash(ctx: Context) {
            if (active != null) return
            val dir = dir(ctx)
            // (a Save's or Discard's delete of the snapshot cut off: nothing of it is put back, 20261013_L18)
            helium314.keyboard.latin.utils.AtomicFiles.deleteFinished(dir)
            val json = runCatching { JSONObject(File(dir, PREFS_FILE).readText()) }.getOrNull()
            // (a snapshot that can't be read: the held fonts and pictures go back to their lists first, then the folder;
            // owner 2026-10-12 B4: before, the folder went first, and the held files with it)
            if (json == null) { runCatching { putHeldBack(ctx, dir) }; dir.deleteRecursively(); return }
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
                // (a snapshot from before review D2 has no "labels": the labels stay as they are now)
                val labels = CustomLabels.Opened.fromJson(json)
                // (what the palette editor saved before the crash stays, as when the app is left)
                val writes = SubScreenWrites.load(dir, null, ::recorded)
                val draft = AppearanceDraft(prefs, files, dir, json.optString("set", KeyboardProfiles.SHARED), reached, labels, writes)
                // (the deletes made before the crash: undone too, their fonts and pictures moved back, [reject])
                draft.reject(ctx, keepSubScreenSaves = true)
            }.onFailure {
                // (a snapshot that can't be read: the held files still go back to their lists before the folder goes)
                runCatching { putHeldBack(ctx, dir) }
                dir.deleteRecursively()
            }
        }
    }
}

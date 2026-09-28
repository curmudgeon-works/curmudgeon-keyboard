// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import helium314.keyboard.latin.settings.Settings
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
    private val prefs: Map<String, Any?>,
    private val files: Map<File, Saved?>, // the live file -> its saved copy, null when it didn't exist
    private val dir: File,
) {
    /** A copy of a custom file, with the size and time the live one had (enough to tell a change, no reading). */
    class Saved(val copy: File, val length: Long, val modified: Long)

    /** True when a preference or file in the snapshot's scope differs from it. */
    fun hasChanges(ctx: Context): Boolean {
        if (changedKeys(ctx).isNotEmpty()) return true
        return files.any { (live, saved) ->
            if (saved == null) live.exists() else !live.exists() || live.length() != saved.length || live.lastModified() != saved.modified
        }
    }

    /** The preferences whose value differs from the snapshot. */
    fun changedKeys(ctx: Context): Set<String> {
        val now = currentPrefs(ctx)
        return (now.keys + prefs.keys).filterTo(HashSet()) { !KnownDefaults.same(it, now[it], prefs[it]) }
    }

    /** The names of the custom files (background images, fonts) that differ from the snapshot. */
    fun changedFiles(): Set<String> = files.filter { (live, saved) ->
        if (saved == null) live.exists() else !live.exists() || live.length() != saved.length || live.lastModified() != saved.modified
    }.keys.mapTo(HashSet()) { it.name }

    /** Back to the snapshot; the live keyboard reloads. */
    fun reject(ctx: Context) {
        for ((live, saved) in files) {
            if (saved == null) live.delete()
            else { saved.copy.copyTo(live, overwrite = true); live.setLastModified(saved.modified) }
        }
        AppearanceLooks.apply(ctx, prefs)
        discard()
    }

    /** Keeps what is on the phone now; only the snapshot goes. */
    fun accept() = discard()

    private fun discard() {
        dir.deleteRecursively()
        if (active === this) active = null
    }

    companion object {
        private fun currentPrefs(ctx: Context): Map<String, Any?> = AppearanceLooks.current(ctx.prefs())

        private fun liveFiles(ctx: Context) = listOf(
            Settings.getCustomBackgroundFile(ctx, false, false), Settings.getCustomBackgroundFile(ctx, false, true),
            Settings.getCustomBackgroundFile(ctx, true, false), Settings.getCustomBackgroundFile(ctx, true, true),
            Settings.getCustomEmojiFontFile(ctx), // (the text fonts are a list of files kept by name: only the choices change)
        )

        private fun dir(ctx: Context) = File(ctx.filesDir, "appearance_draft")
        private const val PREFS_FILE = "draft_prefs.json" // written last: its presence means a complete snapshot

        /** The draft of the open Appearance screen; it outlives the screen's composition (rotation, search). */
        private var active: AppearanceDraft? = null

        /** The running draft, or a fresh snapshot: the preference values in scope and a copy of every custom file. */
        fun of(ctx: Context): AppearanceDraft = active ?: start(ctx).also { active = it }

        /** The screen is left with nothing changed: the snapshot goes (the next visit starts from what is there then). */
        fun close() { active?.discard() }

        /** The app is left (or the screen goes to the background) with the screen open: changes not accepted are undone. */
        fun rejectOpen(ctx: Context) { active?.reject(ctx) }

        private fun start(ctx: Context): AppearanceDraft {
            val dir = dir(ctx).apply { deleteRecursively(); mkdirs() }
            val files = liveFiles(ctx).associateWith { live ->
                if (live.exists()) Saved(live.copyTo(File(dir, live.name), overwrite = true), live.length(), live.lastModified()) else null
            }
            val prefs = currentPrefs(ctx)
            val json = JSONObject()
            json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            json.put("files", JSONObject().also { o -> files.forEach { (live, saved) ->
                o.put(live.path, saved?.let { JSONObject().put("length", it.length).put("modified", it.modified) } ?: JSONObject.NULL)
            } })
            File(dir, PREFS_FILE).writeText(json.toString())
            return AppearanceDraft(prefs, files, dir)
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
                AppearanceDraft(prefs, files, dir).reject(ctx)
            }.onFailure { dir.deleteRecursively() }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.io.File

/**
 * What the Appearance screen looked like when it was opened. Every change on the screen is applied at once
 * (the live keyboard is the preview), so "reject all" means putting this snapshot back: the appearance
 * preferences and the custom background / font files.
 */
class AppearanceDraft private constructor(
    private val prefs: Map<String, Any?>,
    private val files: Map<File, Saved?>, // the live file -> its saved copy, null when it didn't exist
) {
    /** A copy of a custom file, with the size and time the live one had (enough to tell a change, no reading). */
    class Saved(val copy: File, val length: Long, val modified: Long)

    /** True when a preference or file in the snapshot's scope differs from it. */
    fun hasChanges(ctx: Context): Boolean {
        if (currentPrefs(ctx) != prefs) return true
        return files.any { (live, saved) ->
            if (saved == null) live.exists() else !live.exists() || live.length() != saved.length || live.lastModified() != saved.modified
        }
    }

    /** Back to the snapshot; the live keyboard reloads. */
    fun reject(ctx: Context) {
        for ((live, saved) in files) {
            if (saved == null) live.delete()
            else saved.copy.copyTo(live, overwrite = true)
        }
        AppearanceLooks.apply(ctx, prefs)
        discard()
    }

    /** Keeps what is on the phone now; only the saved file copies go. */
    fun accept() = discard()

    private fun discard() {
        files.values.forEach { it?.copy?.delete() }
        active = null
    }

    companion object {
        private fun currentPrefs(ctx: Context): Map<String, Any?> = AppearanceLooks.current(ctx.prefs())

        private fun liveFiles(ctx: Context) = listOf(
            Settings.getCustomBackgroundFile(ctx, false, false), Settings.getCustomBackgroundFile(ctx, false, true),
            Settings.getCustomBackgroundFile(ctx, true, false), Settings.getCustomBackgroundFile(ctx, true, true),
            Settings.getCustomFontFile(ctx), Settings.getCustomEmojiFontFile(ctx),
        )

        /** The draft of the open Appearance screen; it outlives the screen's composition (rotation, search). */
        private var active: AppearanceDraft? = null

        /** The running draft, or a fresh snapshot: the preference values in scope and a copy of every custom file. */
        fun of(ctx: Context): AppearanceDraft = active ?: start(ctx).also { active = it }

        private fun start(ctx: Context): AppearanceDraft {
            val dir = File(ctx.cacheDir, "appearance_draft").apply { deleteRecursively(); mkdirs() }
            val files = liveFiles(ctx).associateWith { live ->
                if (live.exists()) Saved(live.copyTo(File(dir, live.name), overwrite = true), live.length(), live.lastModified()) else null
            }
            return AppearanceDraft(currentPrefs(ctx), files)
        }

    }
}

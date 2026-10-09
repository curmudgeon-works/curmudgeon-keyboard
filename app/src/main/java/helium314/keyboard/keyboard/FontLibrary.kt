// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import java.io.File

/**
 * The font files the user has loaded, kept by name in one folder and offered in every text style dialog
 * (key text, symbols, suggestion strip) and the emoji font's: shared by all keyboards, each keyboard's settings pick. A dialog's choice is stored as "font:<name>".
 * A file loaded in a dialog waits in the pending folder until OK; Cancel throws it away.
 */
object FontLibrary {
    const val PREFIX = "font:"
    private const val PENDING = ".pending"

    // the one file per text style of before, and the name each gets in the list
    const val SLOT_KEY = "custom_font"
    const val SLOT_HINT = "custom_hint_font"
    const val SLOT_SUGGESTION = "custom_suggestion_font"
    const val SLOT_EMOJI = "custom_emoji_font" // the emoji font of before (one file for every keyboard)
    private val slots = listOf(
        Triple(SLOT_KEY, Settings.PREF_KEY_FONT, "Key text font"),
        Triple(SLOT_HINT, Settings.PREF_HINT_FONT, "Symbols font"),
        Triple(SLOT_SUGGESTION, Settings.PREF_SUGGESTION_FONT, "Suggestion strip font"),
        Triple(SLOT_EMOJI, Settings.PREF_EMOJI_FONT, "Emoji font"),
    )
    private val fontPrefs = slots.map { it.second }

    @Volatile private var migrated = false

    fun dir(ctx: Context) = File(DeviceProtectedUtils.getFilesDir(ctx), "fonts")
    private fun pendingDir(ctx: Context) = File(dir(ctx), PENDING)

    /** The loaded fonts' names, sorted; pending files are not in the list yet. */
    fun names(ctx: Context): List<String> {
        migrate(ctx)
        return dir(ctx).listFiles()?.filter { it.isFile }?.map { it.name }?.sortedBy { it.lowercase() } ?: emptyList()
    }

    fun isPending(value: String) = value.startsWith("$PREFIX$PENDING/")
    fun displayName(value: String) = value.removePrefix(PREFIX).removePrefix("$PENDING/")

    /** The file behind a stored choice, or null when the choice isn't a font file (or the file is gone). */
    fun fileFor(ctx: Context, value: String, slot: String): File? {
        migrate(ctx)
        return when {
            value.startsWith(PREFIX) -> File(dir(ctx), value.removePrefix(PREFIX))
            // choices stored before the list existed ("file", or nothing chosen with a file loaded), e.g. from a
            // saved theme or a backup: the file that slot had
            value == "file" || value == "auto" -> slots.firstOrNull { it.first == slot }?.let { File(dir(ctx), it.third) }
            else -> null
        }?.takeIf { it.isFile }
    }

    /** Copies a picked file into the pending folder (replacing an earlier pending one); returns the choice to store. */
    fun addPending(ctx: Context, source: File, name: String): String {
        val pending = pendingDir(ctx)
        pending.deleteRecursively()
        pending.mkdirs()
        val clean = name.replace(File.separatorChar, '_').trim().ifEmpty { "font" }
        source.copyTo(File(pending, clean), overwrite = true)
        return "$PREFIX$PENDING/$clean"
    }

    /** OK in a dialog: a pending file joins the list (the same font already there is reused); returns the choice to store. */
    fun commit(ctx: Context, value: String): String {
        if (!isPending(value)) return value
        val file = File(dir(ctx), value.removePrefix(PREFIX))
        if (!file.isFile) return "default"
        var target = File(dir(ctx), file.name)
        var n = 2
        while (target.exists() && !sameContent(target, file)) {
            target = File(dir(ctx), "${file.nameWithoutExtension} ($n)" + (if (file.extension.isEmpty()) "" else ".${file.extension}"))
            n++
        }
        if (!target.exists()) file.copyTo(target)
        discardPending(ctx)
        return PREFIX + target.name
    }

    fun discardPending(ctx: Context) { pendingDir(ctx).deleteRecursively() }

    /** Removes a font from the list; text styles that used it go back to the default, in every keyboard's set (2026-10-07:
     *  only the keyboard being edited did; the others kept the name and took a font loaded later under it). "default"
     *  is written, not the choice removed: nothing chosen ("auto") reads a loaded font of before ([fileFor]). */
    fun delete(ctx: Context, name: String) {
        File(dir(ctx), name).delete()
        val real = ctx.realPrefs()
        fontPrefs.forEach { KeyboardProfiles.replaceValueEverywhere(real, it, PREFIX + name, "default") }
    }

    private fun sameContent(a: File, b: File) = a.length() == b.length() && a.readBytes().contentEquals(b.readBytes())

    /**
     * The files of before (one per text style, also what an old backup brings back) move into the list, and a style
     * that used its file now names it. Checked again after every settings change (see [KeyboardTypeface.clearCache]).
     */
    fun migrate(ctx: Context) {
        if (migrated) return
        migrated = true
        val prefs: SharedPreferences = ctx.prefs()
        val moved = HashSet<String>()
        for ((slot, pref, name) in slots) {
            val old = File(DeviceProtectedUtils.getFilesDir(ctx), slot)
            if (!old.isFile) continue
            dir(ctx).mkdirs()
            old.copyTo(File(dir(ctx), name), overwrite = true)
            old.delete()
            moved.add(slot)
            val value = prefs.getString(pref, null)
            if (value == null || value == "auto" || value == "file") prefs.edit { putString(pref, PREFIX + name) }
        }
        // symbols or suggestions had a file of their own: they keep it rather than follow the key text font,
        // and the other one keeps following the key text font through its own choice
        if ((SLOT_HINT in moved || SLOT_SUGGESTION in moved) && !prefs.contains(Settings.PREF_FONT_FOLLOWS_KEY_TEXT)) {
            val keyFont = prefs.getString(Settings.PREF_KEY_FONT, "default")!!
            prefs.edit {
                putBoolean(Settings.PREF_FONT_FOLLOWS_KEY_TEXT, false)
                for (pref in listOf(Settings.PREF_HINT_FONT, Settings.PREF_SUGGESTION_FONT))
                    if (prefs.getString(pref, "auto") == "auto") putString(pref, keyFont)
            }
        }
    }

    fun recheck() { migrated = false }
}

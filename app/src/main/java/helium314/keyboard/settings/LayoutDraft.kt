// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import org.json.JSONObject
import java.io.File

/**
 * What Layout & Typing looked like when it was opened, for its Keep / Discard (like [AppearanceDraft]): the
 * preferences its rows and sub-screens write (read from the raw store, so a keyboard's own copies are included)
 * and the custom layout files. Kept on disk while the screen is open: changes not kept are undone when the app is
 * left (SettingsActivity.onStop) and, after a crash, at the next app start ([recoverAfterCrash]).
 * Not undone: what clipboard history settings already removed from the history.
 */
class LayoutDraft private constructor(
    private var prefs: Map<String, Any?>, // raw keys (a keyboard's own settings are stored as p<id>/<key>)
    private var layoutsCopy: File?, // copy of the layouts folder, null when there was none
    private val dir: File,
    /** The keyboard (subtype preference string) the screen was opened for. */
    val subtype: String,
) {
    fun hasChanges(ctx: Context): Boolean = changedKeys(ctx).isNotEmpty() || layoutsChanged(ctx)

    /**
     * A screen opened from here saved its part for good (its own tick): the preferences [keys] (plain keys; one ending
     * in "*" stands for every key starting with it) and the layout [folders] (e.g. "number") as they are now become
     * this snapshot's starting point, so this screen's Discard (or leaving the app) no longer undoes them.
     */
    fun rebase(ctx: Context, keys: Set<String>, folders: Set<String>) {
        fun ours(key: String) = plain(base(key)).let { k -> k in keys || keys.any { it.endsWith("*") && k.startsWith(it.dropLast(1)) } }
        val now = scoped(ctx)
        prefs = prefs.filterKeys { !ours(it) } + now.filterKeys { ours(it) }
        if (folders.isNotEmpty()) {
            val live = layoutsDir(ctx)
            val copy = layoutsCopy ?: File(dir, "layouts").also { layoutsCopy = it }
            for (folder in folders) {
                val target = File(copy, folder).apply { deleteRecursively() }
                File(live, folder).takeIf { it.isDirectory }?.copyRecursively(target, overwrite = true)
                // (the copies keep the live files' times, so they compare equal)
                target.walkTopDown().filter { it.isFile }.forEach { f -> f.setLastModified(File(live, f.relativeTo(copy).path).lastModified()) }
            }
        }
        write(dir, subtype, layoutsCopy != null, prefs)
        helium314.keyboard.latin.utils.SettingsEventLog.log("Layout & Typing snapshot rebased: $keys $folders")
    }

    /** The changed preferences, as plain keys (without a keyboard's p<id>/ prefix). */
    fun changedKeys(ctx: Context): Set<String> {
        val now = scoped(ctx)
        // the rows: every key of either snapshot, a "~" mark standing for its key
        val keys = (now.keys + prefs.keys).mapTo(HashSet()) { base(it) }
        return keys.filterTo(HashSet()) { !switchState(it) && !KnownDefaults.same(plain(it), reads(now, it), reads(prefs, it)) }.mapTo(HashSet()) { plain(it) }
    }

    /** What [key] (full) reads as in [stored]: its value; a keyboard's key "at its default" (its "~" mark, see
     *  ProfilePreferences) its default; a keyboard's key with neither the shared value. */
    private fun reads(stored: Map<String, Any?>, key: String): Any? {
        if (key in stored) return stored[key]
        if (plain(key) == key) return null
        if (mark(key) in stored) return KnownDefaults.of(plain(key)) ?: AT_DEFAULT
        return stored[plain(key)]
    }

    /** The layout types (folder names, e.g. "main", "symbols") whose custom files changed. */
    fun changedLayoutFolders(ctx: Context): Set<String> {
        val live = layoutsDir(ctx)
        val names = (listing(live).keys + listing(layoutsCopy).keys)
        val now = listing(live); val before = listing(layoutsCopy)
        return names.filterTo(HashSet()) { now[it] != before[it] }.mapTo(HashSet()) { it.substringBefore(File.separator) }
    }

    private fun layoutsChanged(ctx: Context) = listing(layoutsDir(ctx)) != listing(layoutsCopy)

    /** Back to the snapshot: preferences, layout files, the keyboards and the live keyboard. */
    fun reject(ctx: Context) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("Layout & Typing draft put back (snapshot of $subtype)")
        val real = ctx.realPrefs()
        val now = scoped(ctx)
        real.edit {
            for (key in now.keys) if (key !in prefs && !switchState(key)) remove(key)
            for ((key, value) in prefs) if (now[key] != value && !switchState(key)) {
                if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
            }
        }
        // the keyboard in use stays as it is (the preview switched it, not a setting), unless the undo took its
        // definition away: then the one it had when the screen opened
        val selected = real.getString(Settings.PREF_SELECTED_SUBTYPE, null)?.toSettingsSubtype()
        val enabled = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ENABLED_SUBTYPES, "") ?: "")
        if (selected != null && selected !in enabled) (prefs[Settings.PREF_SELECTED_SUBTYPE] as? String)?.let {
            real.edit { putString(Settings.PREF_SELECTED_SUBTYPE, it) } }
        // same for each app's remembered keyboard (a changed keyboard took the apps with it)
        real.edit {
            real.all.filterKeys { it.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX) }.forEach { (key, value) ->
                if (value.toString().toSettingsSubtype() in enabled) return@forEach
                val before = prefs[key] as? String
                if (before != null && before.toSettingsSubtype() in enabled) putString(key, before) else remove(key)
            }
        }
        val live = layoutsDir(ctx)
        live.deleteRecursively()
        layoutsCopy?.copyRecursively(live, overwrite = true)
        LayoutUtilsCustom.onLayoutFileChanged()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardLayoutSet.onSystemLocaleChanged()
        runCatching { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        rejectedSubtype = subtype
        discard()
    }

    /** Keeps what is on the phone now; only the snapshot goes. */
    fun accept() = discard()

    private fun discard() {
        dir.deleteRecursively()
        if (active === this) active = null
    }

    companion object {
        // the plain keys the screen's rows and sub-screens write (see SubtypeScreen, KeyPopupsScreen, LayoutFilesScreen)
        internal val keys = setOf(
            "layout_preset_selected", // the saved Layout last chosen (its name), for the Layouts row
            // typing
            Settings.PREF_POPUP_ON, Settings.PREF_VIBRATE_ON, Settings.PREF_VIBRATION_DURATION_SETTINGS,
            Settings.PREF_VIBRATE_IN_DND_MODE, Settings.PREF_SOUND_ON, Settings.PREF_KEYPRESS_SOUND,
            Settings.PREF_KEYPRESS_SOUND_VOLUME,
            Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS, Settings.PREF_BACKSPACE_REPEAT_INTERVAL, Settings.PREF_BACKSPACE_SPEED_UP,
            Settings.PREF_BACKSPACE_SPEED_UP_AFTER, Settings.PREF_BACKSPACE_TOP_INTERVAL, Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD,
            Settings.PREF_DELETE_SWIPE, Settings.PREF_DELETE_SWIPE_SPEED, Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT,
            Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Settings.PREF_CLIPBOARD_HISTORY_SIZE,
            Settings.PREF_KEY_LONGPRESS_TIMEOUT, Settings.PREF_BACKSPACE_LONGPRESS_DELAY, Settings.PREF_LONG_PRESS_SYMBOL_ACTION, Settings.PREF_SPACE_TO_CHANGE_LANG,
            Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
            Settings.PREF_ABC_AFTER_EMOJI, Settings.PREF_ABC_AFTER_CLIP,
            Settings.PREF_UNDO_HISTORY_LENGTH, Settings.PREF_UNDO_UNIT, Settings.PREF_REDO_UNIT,
            // the toolbar group
            Settings.PREF_TOOLBAR_MODE, Settings.PREF_TOOLBAR_HIDING_GLOBAL, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE,
            Settings.PREF_TOOLBAR_KEYS, Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS,
            Settings.PREF_TOOLBAR_EXPAND_ICON,
            Settings.PREF_TOOLBAR_IN_STRIP_ROW, Settings.PREF_TOOLBAR_VISIBILITY,
            // layout
            Settings.PREF_SHOW_NUMBER_ROW, Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS,
            Settings.PREF_ENABLE_SPLIT_KEYBOARD, Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE,
            Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE,
            Settings.PREF_SHOW_EMOJI_KEY, Settings.PREF_SHOW_VOICE_KEY, Settings.PREF_SHOW_TLD_POPUP_KEYS, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
            Settings.PREF_SYMBOL_POPUP_MAP, "key_popups", "key_popup_set_selected", "key_popup_sets",
            // the accents level and popup order every keyboard without its own follows (2026-10-06: Layouts set them too)
            Settings.PREF_MORE_POPUP_KEYS, Settings.PREF_POPUP_KEYS_ORDER,
            // the keyboards themselves (a layout choice changes the keyboard's definition)
            Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE,
            "keyboard_profile_ids",
        )
        internal val prefixes = listOf(
            Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, Settings.PREF_LAYOUT_PREFIX,
            Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
            Settings.PREF_SAVED_APP_SUBTYPE_PREFIX,
        )
        private val profileKey = Regex("^p\\d+/")
        /** Which keyboard is in use, and each app's remembered one: switching keyboards (the preview does when the try-it
         *  box is tapped) is no change of this screen's settings, and Discard doesn't switch back. */
        private fun switchState(key: String) = plain(key) == Settings.PREF_SELECTED_SUBTYPE || plain(key).startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX)
        fun plain(key: String) = key.replace(profileKey, "")
        private val AT_DEFAULT = Any() // a mark whose default isn't known: equal only to itself
        private val TOMB = KeyboardProfiles.TOMBSTONE
        /** The key a "~" mark stands for ("p3/~x" -> "p3/x"); other keys as they are. */
        fun base(key: String) = if (plain(key).startsWith(TOMB)) key.substring(0, key.length - plain(key).length) + plain(key).removePrefix(TOMB) else key
        fun mark(key: String) = key.substring(0, key.length - plain(key).length) + TOMB + plain(key)
        fun inScope(plainKey: String) = plainKey in keys || prefixes.any { plainKey.startsWith(it) }

        // (with the "at its default" marks of a keyboard's own set, so Discard puts those back too)
        private fun scoped(ctx: Context): Map<String, Any?> =
            ctx.realPrefs().all.filterKeys { inScope(plain(it).removePrefix(helium314.keyboard.latin.settings.KeyboardProfiles.TOMBSTONE)) }

        private fun layoutsDir(ctx: Context) = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts")
        /** relative path -> size and time, enough to tell a change without reading */
        private fun listing(dir: File?): Map<String, Pair<Long, Long>> =
            dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }
                ?.associate { it.relativeTo(dir).path to (it.length() to it.lastModified()) } ?: emptyMap()

        private fun dir(ctx: Context) = File(ctx.filesDir, "layout_draft")
        private const val PREFS_FILE = "draft_prefs.json" // written last: its presence means a complete snapshot

        private var active: LayoutDraft? = null

        // the keyboard as it was put back by the last undo: the open screen must edit that one again, not the changed
        // one it still holds (changing a keyboard that isn't in the list any more adds a second one)
        private var rejectedSubtype: String? = null
        fun takeRejectedSubtype(): String? = rejectedSubtype.also { rejectedSubtype = null }

        /** The running draft for [subtype], or a fresh snapshot. */
        fun of(ctx: Context, subtype: String): LayoutDraft = active ?: start(ctx, subtype).also { active = it }

        /** A screen opened from Layout & Typing saved its part for good: see [rebase]. */
        fun rebaseOpen(ctx: Context, keys: Set<String>, folders: Set<String> = emptySet()) { active?.rebase(ctx, keys, folders) }

        private fun write(dir: File, subtype: String, hasLayouts: Boolean, prefs: Map<String, Any?>) {
            val json = JSONObject().put("subtype", subtype).put("layouts", hasLayouts)
            json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            File(dir, PREFS_FILE).writeText(json.toString())
        }

        /** The screen is left with nothing changed, or a keyboard deleted: the snapshot goes. */
        fun close() { active?.discard() }

        /** The app is left with the screen open: changes not kept are undone. */
        fun rejectOpen(ctx: Context) { active?.reject(ctx) }

        private fun start(ctx: Context, subtype: String): LayoutDraft {
            helium314.keyboard.latin.utils.SettingsEventLog.log("Layout & Typing snapshot taken for $subtype")
            val dir = dir(ctx).apply { deleteRecursively(); mkdirs() }
            val live = layoutsDir(ctx)
            val copy = if (live.isDirectory) File(dir, "layouts").also { live.copyRecursively(it, overwrite = true) } else null
            // copies keep the live files' times, so an unchanged folder compares equal
            copy?.walkTopDown()?.filter { it.isFile }?.forEach { f -> f.setLastModified(File(live, f.relativeTo(copy).path).lastModified()) }
            val prefs = scoped(ctx)
            write(dir, subtype, copy != null, prefs)
            return LayoutDraft(prefs, copy, dir, subtype)
        }

        /** A snapshot left on disk by a process that died with Layout & Typing open: put it back. Called at app start. */
        fun recoverAfterCrash(ctx: Context) {
            if (active != null) return
            if (File(dir(ctx), PREFS_FILE).exists()) helium314.keyboard.latin.utils.SettingsEventLog.log("Layout & Typing snapshot found at app start: recovering")
            val dir = dir(ctx)
            val json = runCatching { JSONObject(File(dir, PREFS_FILE).readText()) }.getOrNull()
            if (json == null) { dir.deleteRecursively(); return }
            runCatching {
                val p = json.getJSONObject("prefs")
                val prefs = p.keys().asSequence().associateWith { AppearanceLooks.fromJson(p.getJSONObject(it)) }
                val copy = if (json.optBoolean("layouts")) File(dir, "layouts") else null
                LayoutDraft(prefs, copy, dir, json.getString("subtype")).reject(ctx)
            }.onFailure { dir.deleteRecursively() }
        }
    }
}

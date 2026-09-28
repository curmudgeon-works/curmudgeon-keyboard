// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
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
    private val prefs: Map<String, Any?>, // raw keys (a keyboard's own settings are stored as p<id>/<key>)
    private val layoutsCopy: File?, // copy of the layouts folder, null when there was none
    private val dir: File,
    /** The keyboard (subtype preference string) the screen was opened for. */
    val subtype: String,
) {
    fun hasChanges(ctx: Context): Boolean = changedKeys(ctx).isNotEmpty() || layoutsChanged(ctx)

    /** The changed preferences, as plain keys (without a keyboard's p<id>/ prefix). */
    fun changedKeys(ctx: Context): Set<String> {
        val now = scoped(ctx)
        return (now.keys + prefs.keys).filterTo(HashSet()) { !KnownDefaults.same(plain(it), now[it], prefs[it]) }.mapTo(HashSet()) { plain(it) }
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
        val real = ctx.realPrefs()
        val now = scoped(ctx)
        real.edit {
            for (key in now.keys) if (key !in prefs) remove(key)
            for ((key, value) in prefs) if (now[key] != value) {
                if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
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
        private val keys = setOf(
            // typing
            Settings.PREF_POPUP_ON, Settings.PREF_VIBRATE_ON, Settings.PREF_VIBRATION_DURATION_SETTINGS,
            Settings.PREF_VIBRATE_IN_DND_MODE, Settings.PREF_SOUND_ON, Settings.PREF_KEYPRESS_SOUND,
            Settings.PREF_KEYPRESS_SOUND_VOLUME, Settings.PREF_SAVE_SUBTYPE_PER_APP,
            Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS, Settings.PREF_BACKSPACE_REPEAT_INTERVAL, Settings.PREF_BACKSPACE_SPEED_UP,
            Settings.PREF_BACKSPACE_SPEED_UP_AFTER, Settings.PREF_BACKSPACE_TOP_INTERVAL, Settings.PREF_BACKSPACE_DELETES_SWIPED_WORD,
            Settings.PREF_DELETE_SWIPE, Settings.PREF_DELETE_SWIPE_SPEED, Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT,
            Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Settings.PREF_CLIPBOARD_HISTORY_SIZE,
            Settings.PREF_KEY_LONGPRESS_TIMEOUT, Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD, Settings.PREF_SPACE_TO_CHANGE_LANG,
            Settings.PREF_CUSTOM_CURRENCY_KEY, Settings.PREF_ABC_AFTER_SYMBOL_SPACE, Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
            Settings.PREF_ABC_AFTER_EMOJI, Settings.PREF_ABC_AFTER_CLIP,
            // the toolbar group
            Settings.PREF_TOOLBAR_MODE, Settings.PREF_TOOLBAR_HIDING_GLOBAL, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE,
            Settings.PREF_TOOLBAR_KEYS, Settings.PREF_PINNED_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS,
            Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Settings.PREF_QUICK_PIN_TOOLBAR_KEYS, Settings.PREF_AUTO_SHOW_TOOLBAR,
            Settings.PREF_AUTO_HIDE_TOOLBAR, Settings.PREF_VARIABLE_TOOLBAR_DIRECTION, Settings.PREF_TOOLBAR_EXPAND_ICON,
            Settings.PREF_TOOLBAR_IN_STRIP_ROW, Settings.PREF_TOOLBAR_VISIBILITY,
            // layout
            Settings.PREF_SHOW_NUMBER_ROW, Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS,
            Settings.PREF_ENABLE_SPLIT_KEYBOARD, Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE,
            Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE,
            Settings.PREF_SHOW_EMOJI_KEY, Settings.PREF_SHOW_TLD_POPUP_KEYS, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
            Settings.PREF_SYMBOL_POPUP_MAP, "key_popups", "key_popup_set_selected", "key_popup_sets",
            // the keyboards themselves (a layout choice changes the keyboard's definition)
            Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE,
            "keyboard_profile_ids",
        )
        private val prefixes = listOf(
            Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, Settings.PREF_LAYOUT_PREFIX,
            Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
            Settings.PREF_SAVED_APP_SUBTYPE_PREFIX,
        )
        private val profileKey = Regex("^p\\d+/")
        fun plain(key: String) = key.replace(profileKey, "")
        fun inScope(plainKey: String) = plainKey in keys || prefixes.any { plainKey.startsWith(it) }

        private fun scoped(ctx: Context): Map<String, Any?> = ctx.realPrefs().all.filterKeys { inScope(plain(it)) }

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

        /** The screen is left with nothing changed, or a keyboard deleted: the snapshot goes. */
        fun close() { active?.discard() }

        /** The app is left with the screen open: changes not kept are undone. */
        fun rejectOpen(ctx: Context) { active?.reject(ctx) }

        private fun start(ctx: Context, subtype: String): LayoutDraft {
            val dir = dir(ctx).apply { deleteRecursively(); mkdirs() }
            val live = layoutsDir(ctx)
            val copy = if (live.isDirectory) File(dir, "layouts").also { live.copyRecursively(it, overwrite = true) } else null
            // copies keep the live files' times, so an unchanged folder compares equal
            copy?.walkTopDown()?.filter { it.isFile }?.forEach { f -> f.setLastModified(File(live, f.relativeTo(copy).path).lastModified()) }
            val prefs = scoped(ctx)
            val json = JSONObject().put("subtype", subtype).put("layouts", copy != null)
            json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            File(dir, PREFS_FILE).writeText(json.toString())
            return LayoutDraft(prefs, copy, dir, subtype)
        }

        /** A snapshot left on disk by a process that died with Layout & Typing open: put it back. Called at app start. */
        fun recoverAfterCrash(ctx: Context) {
            if (active != null) return
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

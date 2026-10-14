// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.AtomicFiles
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.realPrefs
import org.json.JSONObject
import java.io.File

/**
 * What Layout & Typing looked like when it was opened, for its Keep / Discard (like [AppearanceDraft]): the
 * preferences its rows and sub-screens write (read from the raw store, so a keyboard's own copies are included)
 * and the custom layout files. Kept on disk while the screen is open: changes not kept are undone when the app is
 * left (SettingsActivity.onStop) and, after a crash, at the next app start ([recoverAfterCrash]); what a screen opened
 * from here saved stays then ([SubScreenWrites]), only this screen's Discard undoes it too.
 * Not undone: what clipboard history settings already removed from the history.
 */
class LayoutDraft private constructor(
    private val prefs: Map<String, Any?>, // raw keys (a keyboard's own settings are stored as p<id>/<key>)
    private val layoutsCopy: File?, // copy of the layouts folder, null when there was none
    private val dir: File,
    /** The keyboard (subtype preference string) the screen was opened for. */
    val subtype: String,
    // the Layout and popup Custom labels and their origins stored when the screen opened (any set): Discard puts them
    // back exactly (review D2: the ones added since go; E3: a cleared one, "from Work" too, comes back); out of
    // [changedKeys], as a label alone is no change of the screen. Null: left as they are
    private val labels: CustomLabels.Opened? = null,
    // what the screens opened from here wrote with their Save: kept when the app is left (owner 2026-10-11, S2: "yes
    // let's keep saved subscreen changes"), undone by this screen's own Discard
    private val writes: SubScreenWrites = SubScreenWrites(dir, null) { false },
    // whether the one-time learned-words update was done when the screen opened; null: a snapshot without the learned
    // words' records (from before 2026-10-11), which Discard leaves as they are
    private val learnedDone: Boolean? = null,
) {
    fun hasChanges(ctx: Context): Boolean = changedKeys(ctx).isNotEmpty() || layoutsChanged(ctx)

    /** A screen opened from here whose Save only keeps its change (this screen's Save or Discard decides it). */
    enum class SubScreen { POPUPS, LAYOUT_FILES }

    // the sub-screens saved while the screen is open, in that order: the Discard question names them (owner
    // 2026-10-11: "send the discard warning yes")
    private val savedSubScreens = LinkedHashSet<SubScreen>()

    /** The sub-screens saved since the screen opened. */
    fun savedSubScreens(): List<SubScreen> = savedSubScreens.toList()

    /** A screen opened from here ([SubScreen]) shows: what it changes is told at its Save ([subScreenSaved]). */
    fun subScreenOpened(ctx: Context) = writes.opened(ctx)

    /** [screen] saved with this screen open. */
    fun subScreenSaved(screen: SubScreen) {
        savedSubScreens += screen
        writes.saved()
        AtomicFiles.write(File(dir, SUB_SAVES_FILE), org.json.JSONArray(savedSubScreens.map { it.name }).toString())
    }

    // (no rebase: the Save of a screen opened from here, Customize popups or the layout files, leaves this snapshot as
    // it is, so this screen's Save or Discard decides those changes too; owner 2026-10-11, option 1, review C2)

    /** The changed preferences, as plain keys (without a keyboard's p<id>/ prefix). */
    fun changedKeys(ctx: Context): Set<String> {
        val now = scoped(ctx)
        // the rows: every key of either snapshot; one not stored reads its default (owner 2026-10-11: no default marks,
        // no common set to fall back on)
        val keys = now.keys + prefs.keys
        return keys.filterTo(HashSet()) { it !in learnedKeys && !switchState(it) && !KnownDefaults.same(plain(it), now[it], prefs[it]) }.mapTo(HashSet()) { plain(it) }
    }

    /** The layout types (folder names, e.g. "main", "symbols") whose custom files changed. */
    fun changedLayoutFolders(ctx: Context): Set<String> {
        val live = layoutsDir(ctx)
        val names = (listing(live).keys + listing(layoutsCopy).keys)
        val now = listing(live); val before = listing(layoutsCopy)
        return names.filterTo(HashSet()) { now[it] != before[it] }.mapTo(HashSet()) { it.substringBefore(File.separator) }
    }

    private fun layoutsChanged(ctx: Context) = listing(layoutsDir(ctx)) != listing(layoutsCopy)

    /** Back to the snapshot: preferences, layout files, the keyboards and the live keyboard. [keepSubScreenSaves] (the
     *  app left, or the undo after a crash): what a screen opened from here saved stays as saved, over the snapshot
     *  (a key this screen changed too: the sub-screen's saved value); this screen's Discard: undone too. */
    fun reject(ctx: Context, keepSubScreenSaves: Boolean = false) {
        val keep = keepSubScreenSaves && !writes.isEmpty()
        helium314.keyboard.latin.utils.SettingsEventLog.log("Layout & Typing draft put back (snapshot of $subtype)" + if (keep) ", sub-screen saves kept" else "")
        val real = ctx.realPrefs()
        val now = scoped(ctx)
        val prefs = if (keep) writes.over(this.prefs) else this.prefs
        // the learned words' records as at the open too (owner 2026-10-11, "OK"): a keyboard a draft changed gets its own
        // pool back, none is left on the changed identity; not if the one-time update ran meanwhile (its pools are new)
        val learned = learnedDone != null && learnedDone == helium314.keyboard.latin.personalization.LearnedKeyboards.isDone(real)
        fun puts(key: String) = !switchState(key) && (learned || key !in learnedKeys)
        // the keyboard in use stays as it is (the preview switched it, not a setting), unless the undo takes its
        // definition away: then the one it had when the screen opened. Same for each app's remembered keyboard (a
        // changed keyboard took the apps with it). Worked out against the snapshot's list before anything is written,
        // and written in the same edit: a listener reloading between two edits (Settings, on the UI thread) found the
        // changed keyboard in use but not in the restored list, and gave it a new empty set that stayed (review
        // 2026-10-07: a stray keyboard entry, and the same change made again and kept opened on that empty set)
        val enabled = SubtypeSettings.createSettingsSubtypes(prefs[Settings.PREF_ENABLED_SUBTYPES] as? String ?: "")
        // a keyboard as it is in the list put back: itself, or (sub-screen saves kept) what a sub-screen's save made of
        // it (the layout files change the keyboard's definition), else one of the same language
        fun listed(keyboard: String?): String? {
            if (keyboard == null || keyboard.toSettingsSubtype() in enabled) return keyboard
            // (the same keyboard, by its id, as the list put back has it; owner 2026-10-11, keyboards have unique ids)
            enabled.firstOrNull { it.sameKeyboard(keyboard.toSettingsSubtype()) }?.let { return it.toPref() }
            if (!keep) return null
            var k: String = keyboard
            for (i in 0 until 20) { k = SubtypeUtilsAdditional.changedTo[k] ?: break; if (k.toSettingsSubtype() in enabled) return k }
            return enabled.firstOrNull { it.locale == keyboard.toSettingsSubtype().locale }?.toPref()
        }
        val selected = real.getString(Settings.PREF_SELECTED_SUBTYPE, null)?.toSettingsSubtype()
        val selectedBefore = if (selected != null && selected !in enabled)
            (prefs[Settings.PREF_SELECTED_SUBTYPE] as? String).let { if (keep) listed(it) else it } else null
        val apps = real.all.filterKeys { it.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX) }
            .filterValues { it.toString().toSettingsSubtype() !in enabled }
            .mapValues { (key, _) -> listed(prefs[key] as? String) }
        real.edit {
            for (key in now.keys) if (key !in prefs && puts(key)) remove(key)
            for ((key, value) in prefs) if (now[key] != value && puts(key)) {
                if (value == null) remove(key) else KeyboardProfiles.put(this, key, value)
            }
            selectedBefore?.let { putString(Settings.PREF_SELECTED_SUBTYPE, it) }
            apps.forEach { (key, before) -> if (before != null) putString(key, before) else remove(key) }
        }
        // (no listener runs when the keyboard isn't loaded yet, e.g. at app start)
        KeyboardProfiles.refreshImeId(real)
        // the layout files as at the open: copied in beside first, then swapped in, so the live folder is never gone
        // while the copy still has to be made (review B4w.1: a copy failing after the delete left neither, and the crash
        // undo then deleted the draft's folder, the last copy)
        val live = layoutsDir(ctx)
        if (layoutsCopy != null) {
            val incoming = restoringDir(ctx).apply { deleteRecursively() }
            layoutsCopy.copyRecursively(incoming, overwrite = true)
            live.deleteRecursively()
            if (!incoming.renameTo(live)) { incoming.copyRecursively(live, overwrite = true); incoming.deleteRecursively() }
        } else live.deleteRecursively()
        if (keep) writes.writeTo(real) // (the files; the keys are in the edit above)
        LayoutUtilsCustom.onLayoutFileChanged()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        // the settings values the edit's listeners built read the keyboard list before that reload (still with the
        // changed keyboard): a built-in keyboard in use wasn't found, and the first keyboard's values were taken (its
        // second languages, popup keys, number row; re-review 2026-10-07). Built again when loaded (not at app start)
        Settings.getValues()?.let { Settings.getInstance().loadSettings(Settings.getCurrentContext(), it.mLocale, it.mInputAttributes) }
        KeyboardLayoutSet.onSystemLocaleChanged()
        runCatching { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        labels?.putBack(real, LABELS)
        if (keep) writes.writeTo(real) // (a Custom label a sub-screen's save handed out stays too)
        rejectedSubtype = if (keep) listed(subtype) ?: subtype else subtype
        discard()
    }

    /** Keeps what is on the phone now; only the snapshot goes. */
    fun accept() = discard()

    private fun discard() {
        AtomicFiles.deleteDir(dir) // (one step: a cut-off delete leaves no part of the snapshot to put back, 20261013_L18)
        savedSubScreens.clear()
        writes.clear()
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
            // (no "keyboard_profile_ids" since 2026-10-11: a keyboard's set is found by its id, in its definition)
        )
        internal val prefixes = listOf(
            Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, Settings.PREF_LAYOUT_PREFIX,
            Settings.PREF_BOTTOM_ROW_SCALE_PREFIX, Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
            Settings.PREF_SAVED_APP_SUBTYPE_PREFIX,
        )
        private val profileKey = Regex("^p(k[0-9a-z]+|\\d+)/") // (a keyboard's id; a number: a set of before 2026-10-11)
        /** Which keyboard is in use, and each app's remembered one: switching keyboards (the preview does when the try-it
         *  box is tapped) is no change of this screen's settings, and Discard doesn't switch back. */
        private fun switchState(key: String) = plain(key) == Settings.PREF_SELECTED_SUBTYPE || plain(key).startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX)
        fun plain(key: String) = key.replace(profileKey, "")
        fun inScope(plainKey: String) = plainKey in keys || prefixes.any { plainKey.startsWith(it) }
        // the learned words' records (by keyboard id since 2026-10-11: a change moves none, put back all the same), no
        // setting of the screen (not in [keys]: no menu's, no saved Layout's, no change shown)
        private val learnedKeys = setOf(helium314.keyboard.latin.personalization.LearnedKeyboards.PREF_POOLS,
            helium314.keyboard.latin.personalization.LearnedKeyboards.PREF_POOL_NEXT) +
            helium314.keyboard.latin.personalization.LearnedKeyboards.buttonPrefs
        /** A stored key a sub-screen's save is recorded with ([SubScreenWrites]): what the undo puts back, and the
         *  Custom labels; not which keyboard is in use. */
        private fun recorded(key: String): Boolean {
            val p = plain(key)
            return (inScope(p) && !switchState(key)) || LABELS.any { it.key == p } || key in learnedKeys
        }

        private fun scoped(ctx: Context): Map<String, Any?> =
            ctx.realPrefs().all.filterKeys { inScope(plain(it)) || it in learnedKeys }

        private fun layoutsDir(ctx: Context) = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts")
        // the snapshot's layouts copied in beside the live folder before they are swapped in ([reject])
        private fun restoringDir(ctx: Context) = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts.restoring")
        /** relative path -> size and time, enough to tell a change without reading */
        private fun listing(dir: File?): Map<String, Pair<Long, Long>> =
            dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }
                ?.associate { it.relativeTo(dir).path to (it.length() to it.lastModified()) } ?: emptyMap()

        // in the device-protected storage, with the layouts it copies (round-6 review 20261013_M1_2, owner 2026-10-13, as
        // AppearanceDraft's: one storage for the draft and what it holds; no move of an old folder: pre-launch)
        private fun dir(ctx: Context) = File(DeviceProtectedUtils.getFilesDir(ctx), "layout_draft")
        private const val PREFS_FILE = "draft_prefs.json" // written last: its presence means a complete snapshot
        private const val SUB_SAVES_FILE = "sub_screen_saves.json"
        private val LABELS = listOf(CustomLabels.Kind.LAYOUT, CustomLabels.Kind.POPUPS)

        private var active: LayoutDraft? = null

        // the keyboard as it was put back by the last undo: the open screen must edit that one again, not the changed
        // one it still holds (changing a keyboard that isn't in the list any more adds a second one)
        private var rejectedSubtype: String? = null
        fun takeRejectedSubtype(): String? = rejectedSubtype.also { rejectedSubtype = null }

        /** Whether the screen is open (its draft running). */
        fun isOpen(): Boolean = active != null

        /** The running draft for [subtype], or a fresh snapshot. */
        fun of(ctx: Context, subtype: String): LayoutDraft = active ?: start(ctx, subtype).also { active = it }

        private fun write(dir: File, subtype: String, hasLayouts: Boolean, prefs: Map<String, Any?>, labels: CustomLabels.Opened, learnedDone: Boolean) {
            val json = JSONObject().put("subtype", subtype).put("layouts", hasLayouts).put("learnedDone", learnedDone)
            labels.toJson(json)
            json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> AppearanceLooks.toJson(v)?.let { o.put(k, it) } } })
            AtomicFiles.write(File(dir, PREFS_FILE), json.toString()) // (crash-safe: never half a snapshot, B4w.2)
        }

        /** The screen is left with nothing changed, or a keyboard deleted: the snapshot goes. */
        fun close() { active?.discard() }

        /** The app is left with the screen open: changes not kept are undone, but not what a screen opened from here
         *  saved (owner 2026-10-11, S2: "yes let's keep saved subscreen changes"). */
        fun rejectOpen(ctx: Context) { active?.reject(ctx, keepSubScreenSaves = true) }

        /** The app is left with the screen open and [leavingKeepsChanges] on: its changes are kept, as with Save (owner
         *  2026-10-14). Customize popups' unsaved changes are undone before ([OpenEditors]). */
        fun acceptOpen() { active?.accept() }

        /** The running draft's pending changes for the log, e.g. "Layout & typing: 2 settings, 1 layout types"; null:
         *  none (or no draft). */
        fun pendingOpen(ctx: Context): String? = active?.takeIf { it.hasChanges(ctx) }?.let {
            "Layout & typing: ${it.changedKeys(ctx).size} settings, ${it.changedLayoutFolders(ctx).size} layout types"
        }

        /** [LayoutDraft.subScreenOpened] of the running draft, if there is one. */
        fun subScreenOpenedOpen(ctx: Context) { active?.subScreenOpened(ctx) }

        /** [LayoutDraft.subScreenSaved] of the running draft, if there is one. */
        fun subScreenSavedOpen(screen: SubScreen) { active?.subScreenSaved(screen) }

        /** Tests: the saved sub-screens as kept on disk with the snapshot. */
        @androidx.annotation.VisibleForTesting internal fun savedSubScreensOnDisk(ctx: Context): List<SubScreen> =
            runCatching { org.json.JSONArray(File(dir(ctx), SUB_SAVES_FILE).readText()) }.getOrNull()
                ?.let { a -> List(a.length()) { SubScreen.valueOf(a.getString(it)) } } ?: emptyList()
        /** Tests: the process died with the screen open (the draft's folder stays for [recoverAfterCrash]). */
        @androidx.annotation.VisibleForTesting internal fun forgetActiveForTest() { active = null }

        private fun start(ctx: Context, subtype: String): LayoutDraft {
            helium314.keyboard.latin.utils.SettingsEventLog.log("Layout & Typing snapshot taken for $subtype")
            val dir = dir(ctx).apply { deleteRecursively(); mkdirs() }
            val live = layoutsDir(ctx)
            val copy = if (live.isDirectory) File(dir, "layouts").also { live.copyRecursively(it, overwrite = true) } else null
            // copies keep the live files' times, so an unchanged folder compares equal
            copy?.walkTopDown()?.filter { it.isFile }?.forEach { f -> f.setLastModified(File(live, f.relativeTo(copy).path).lastModified()) }
            val prefs = scoped(ctx)
            val labels = CustomLabels.Opened.of(ctx.realPrefs(), LABELS)
            val learnedDone = helium314.keyboard.latin.personalization.LearnedKeyboards.isDone(ctx.realPrefs())
            write(dir, subtype, copy != null, prefs, labels, learnedDone)
            return LayoutDraft(prefs, copy, dir, subtype, labels, SubScreenWrites(dir, live, ::recorded), learnedDone)
        }

        /** A snapshot left on disk by a process that died with Layout & Typing open: put it back. Called at app start. */
        fun recoverAfterCrash(ctx: Context) {
            if (active != null) return
            // (a Save's or Discard's delete of the snapshot cut off: nothing of it is put back, 20261013_L18)
            AtomicFiles.deleteFinished(dir(ctx))
            // (a put-back of the layouts cut off: what it copied in beside goes, the snapshot's copy is still whole if
            // one is left to put back; backups stopped carrying it, 20261013_L20)
            restoringDir(ctx).deleteRecursively()
            if (File(dir(ctx), PREFS_FILE).exists()) helium314.keyboard.latin.utils.SettingsEventLog.log("Layout & Typing snapshot found at app start: recovering")
            val dir = dir(ctx)
            val json = runCatching { JSONObject(File(dir, PREFS_FILE).readText()) }.getOrNull()
            // (a snapshot that can't be read: the key layouts of its copy missing now go back first, then the folder;
            // review B4w.1, as AppearanceDraft's held files, B4)
            if (json == null) { runCatching { putMissingLayoutsBack(ctx, dir) }; dir.deleteRecursively(); return }
            runCatching {
                val p = json.getJSONObject("prefs")
                val prefs = p.keys().asSequence().associateWith { AppearanceLooks.fromJson(p.getJSONObject(it)) }
                val copy = if (json.optBoolean("layouts")) File(dir, "layouts") else null
                // (a snapshot from before review D2 has no "labels": the labels stay as they are now)
                val labels = CustomLabels.Opened.fromJson(json)
                // (what a sub-screen saved before the crash stays, as when the app is left)
                val writes = SubScreenWrites.load(dir, layoutsDir(ctx), ::recorded)
                val learnedDone = if (json.has("learnedDone")) json.getBoolean("learnedDone") else null
                LayoutDraft(prefs, copy, dir, json.getString("subtype"), labels, writes, learnedDone).reject(ctx, keepSubScreenSaves = true)
            }.onFailure { runCatching { putMissingLayoutsBack(ctx, dir) }; dir.deleteRecursively() }
        }

        /** The key layout files of the snapshot's copy in [dir] that the live folder lacks, copied back (never over a live
         *  one): what was deleted with the screen open isn't lost with the draft's folder (review B4w.1). */
        private fun putMissingLayoutsBack(ctx: Context, dir: File) {
            val copy = File(dir, "layouts")
            if (!copy.isDirectory) return
            val live = layoutsDir(ctx)
            var any = false
            copy.walkTopDown().filter { it.isFile }.forEach { f ->
                val target = File(live, f.relativeTo(copy).path)
                if (!target.exists()) { f.copyTo(target); any = true }
            }
            if (any) LayoutUtilsCustom.onLayoutFileChanged()
        }
    }
}

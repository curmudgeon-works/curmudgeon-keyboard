// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import helium314.keyboard.latin.utils.getActivity
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject

/**
 * Separate settings per keyboard. Every keyboard (subtype) has a stable profile id; with the feature on, a
 * keyboard's settings live under keys "p<id>/<key>" in the same preferences file, while the plain keys hold
 * the shared set used when the feature is off. Sets are never deleted by switching, only by deleting a keyboard.
 *
 * Which profile a read or write goes to is decided by [ProfilePreferences]: the keyboard in use for the IME,
 * the keyboard being edited for the settings screens.
 */
object KeyboardProfiles {
    const val SHARED = 0 // profile id of the plain keys

    private const val PREF_SEPARATE = "separate_settings_per_keyboard"
    private const val PREF_IDS = "keyboard_profile_ids" // json: subtype pref string -> id
    private const val PREF_NEXT_ID = "keyboard_profile_next_id"
    private const val PREFIX = "p"
    private const val SEPARATOR = "/"

    /** Keys that are never per keyboard: the keyboards themselves, app-level state, data. */
    private val globalKeys = setOf(
        Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE,
        Settings.PREF_ADVANCED_SETTINGS, Settings.PREF_VERSION_CODE,
        Settings.PREF_SAVE_SUBTYPE_PER_APP, // it picks which keyboard comes up in an app: one switch for all of them
        Settings.PREF_SHOW_SETUP_WIZARD_ICON, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        Settings.PREF_CLIPBOARD_HISTORY_SIZE, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST,
        Settings.PREF_RECORD_GESTURE_CORPUS, Settings.PREF_SWIPE_METRICS, Settings.PREF_AUTO_PREVIEW_KEYBOARD, // logs of the user's swiping: one file, one switch
        Settings.PREF_LEARNING_LOG, // and of what corrections do to the learned words
        Settings.PREF_SHARE_LEARNED_WORDS, // it says whether the keyboards have their own learned words: app-wide
        PREF_SEPARATE, PREF_IDS, PREF_NEXT_ID, PREF_EDITING,
        "key_popup_sets", // saved popup sets are meant to be reused across keyboards
        "appearance_looks", // saved looks too
        "layout_presets", // and saved Layouts
    ) + Group.entries.map { it.prefKey }

    /**
     * The top-level menus whose settings can be the keyboard's own or shared by all keyboards (App settings > Per
     * keyboard). A setting on two menus (auto-space after a swipe: Swipe and Text correction) is shared only when both
     * are; when one is per keyboard it belongs to that one and the shared menu doesn't show it ([hiddenOn]).
     * Learned & blacklisted words are files, not settings: their own switch (LearnedStores.isShared).
     */
    enum class Group(val prefKey: String, val sharedByDefault: Boolean) {
        LAYOUT("share_group_layout", false),
        APPEARANCE("share_group_appearance", false),
        SWIPE("share_group_swipe", false),
        TEXT_CORRECTION("share_group_correction", false),
        REFINE("share_group_refine", true); // "Refine swipe and learning": your hand and your words, so one set by default

        fun contains(key: String): Boolean = when (this) {
            LAYOUT -> helium314.keyboard.settings.LayoutDraft.inScope(key)
            APPEARANCE -> helium314.keyboard.settings.AppearanceLooks.onScreen(key)
            SWIPE -> key in helium314.keyboard.settings.screens.swipeSettingKeys
            TEXT_CORRECTION -> key in helium314.keyboard.settings.screens.correctionKeys
            REFINE -> key in learningSwipingKeys
        }
    }

    /** The groups shared by all keyboards, read once ([loadGroups]) since every preference read asks. */
    @Volatile private var sharedGroups: Set<Group> = Group.entries.filterTo(HashSet()) { it.sharedByDefault }
    private val groupsOfKey = java.util.concurrent.ConcurrentHashMap<String, Set<Group>>()

    fun loadGroups(real: SharedPreferences) {
        sharedGroups = Group.entries.filterTo(HashSet()) { isShared(real, it) }
    }

    fun isShared(real: SharedPreferences, group: Group) = real.getBoolean(group.prefKey, group.sharedByDefault)

    /** The menus [key] is on (none: a setting of no menu here, it stays the keyboard's own). */
    fun groupsOf(key: String): Set<Group> = groupsOfKey.getOrPut(key) { Group.entries.filterTo(HashSet()) { it.contains(key) } }

    private fun sharedByGroups(key: String, shared: Set<Group> = sharedGroups): Boolean {
        val groups = groupsOf(key)
        return groups.isNotEmpty() && shared.containsAll(groups)
    }

    /** True when [key] is not shown on [menu]: separate settings, [menu] shared, and the setting is also on a menu that
     *  is per keyboard, which owns it. */
    fun hiddenOn(real: SharedPreferences, menu: Group, key: String): Boolean =
        isSeparate(real) && menu in sharedGroups && groupsOf(key).any { it !in sharedGroups }

    /**
     * [group] becomes shared by all keyboards ([shared]) or each keyboard's own. Becoming shared, the settings that
     * turn shared take the values of keyboard [winnerId] and every keyboard's own copy goes; becoming per keyboard,
     * each keyboard starts from the shared values (its reads fall back to them). Background pictures follow Appearance.
     */
    @Synchronized
    fun setGroupShared(real: SharedPreferences, group: Group, shared: Boolean, winnerId: Int) {
        val before = sharedGroups
        val after = if (shared) before + group else before - group
        val editor = real.edit()
        if (shared && isSeparate(real)) {
            val winnerValues = HashMap<String, Any?>()
            val winnerDefaults = HashSet<String>()
            for ((stored, value) in real.all) {
                if (!(stored.startsWith(PREFIX) && stored.contains(SEPARATOR))) continue
                val sep = stored.indexOf(SEPARATOR)
                val id = stored.substring(PREFIX.length, sep).toIntOrNull() ?: continue
                val raw = stored.substring(sep + 1)
                val plain = raw.removePrefix(TOMBSTONE)
                if (!sharedByGroups(plain, after) || sharedByGroups(plain, before)) continue
                if (id == winnerId) { if (raw.startsWith(TOMBSTONE)) winnerDefaults.add(plain) else winnerValues[plain] = value }
                editor.remove(stored)
            }
            // a value of its own wins over a "default" mark left behind (as in [copy])
            for (plain in winnerDefaults) if (plain !in winnerValues) editor.remove(plain)
            for ((plain, value) in winnerValues) put(editor, plain, value)
        }
        editor.putBoolean(group.prefKey, shared)
        editor.commit()
        sharedGroups = after
        if (group == Group.APPEARANCE && isSeparate(real)) {
            if (shared) copyFiles(winnerId, SHARED)
            else for (id in profileIds(real)) copyFiles(SHARED, id)
        }
        helium314.keyboard.latin.utils.SettingsEventLog.log("group ${group.name} ${if (shared) "shared, from set $winnerId" else "per keyboard"}")
    }

    private fun profileIds(real: SharedPreferences): List<Int> {
        val map = ids(real)
        return map.keys().asSequence().map { map.optInt(it, SHARED) }.filter { it != SHARED }.distinct().toList()
    }

    /** "Refine swipe and learning" (2026-10-04): shared by all keyboards unless its group is per keyboard ([Group.REFINE]). */
    private val learningSwipingKeys: Set<String> get() = setOf(
        Settings.PREF_GESTURE_TURN_WEIGHT, Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Settings.PREF_GESTURE_KUSHLER_WEIGHT,
        Settings.PREF_GESTURE_HISTORY_BOOST, Settings.PREF_GESTURE_FAST_COMMON_WORDS, Settings.PREF_GESTURE_CORNER_MISS, Settings.PREF_GESTURE_FAST_SPEED,
        Settings.PREF_SUGGESTION_COUNT, Settings.PREF_SUGGESTION_RULES, // Customize suggestions (from Text correction, 2026-10-06)
        Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT,
    )
    // ("share_user_history_": the retired per-language share switch, kept global so old keys stay where they are)
    private val globalPrefixes = listOf(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, "language_priority_", "share_user_history_", LanguagePriority.PREF_ADDED_PREFIX, "debug_", "gesture_stats")

    /** A key of the app itself (never a keyboard's), whatever the menus' sharing: the keyboard list, the saved themes and
     *  Layouts, the logs' switches… (re-review 2026-10-07: what a shared menu holds for all keyboards is not app-wide,
     *  a saved Layout still carries it). */
    fun isAppWide(key: String) = key in globalKeys || globalPrefixes.any { key.startsWith(it) } || (key.startsWith(PREFIX) && key.contains(SEPARATOR))
    /** Stored once for all keyboards: [isAppWide], or on menus that are all shared. */
    fun isGlobal(key: String) = isAppWide(key) || sharedByGroups(key)

    fun isSeparate(real: SharedPreferences) = real.getBoolean(PREF_SEPARATE, false)

    /** A stored key of a keyboard's own set as its set id and plain key (a mark keeps its [TOMBSTONE]); null for a key of
     *  the shared set or an app-wide one. */
    fun splitOwnKey(stored: String): Pair<Int, String>? {
        if (!stored.startsWith(PREFIX)) return null
        val sep = stored.indexOf(SEPARATOR)
        if (sep < 0) return null
        val id = stored.substring(PREFIX.length, sep).toIntOrNull() ?: return null
        return id to stored.substring(sep + 1)
    }

    /** [key] in keyboard set [id], whether or not the key is shared now (as stored, unlike [prefixedKey]). */
    fun ownKey(id: Int, key: String) = "$PREFIX$id$SEPARATOR$key"

    fun prefixedKey(id: Int, key: String) = if (id == SHARED || isGlobal(key)) key else "$PREFIX$id$SEPARATOR$key"

    /** The key as the app knows it, or null if [key] belongs to another profile than [id]. */
    fun unprefixedKey(id: Int, key: String): String? {
        if (!key.startsWith(PREFIX)) return key
        val sep = key.indexOf(SEPARATOR)
        if (sep < 0) return key
        val keyId = key.substring(PREFIX.length, sep).toIntOrNull() ?: return key
        return if (keyId == id) key.substring(sep + 1) else null
    }

    // ---- ids ----

    private fun ids(real: SharedPreferences): JSONObject =
        try { JSONObject(real.getString(PREF_IDS, "{}")!!) } catch (e: Exception) { JSONObject() }

    /** Every stored copy of [key] (the shared one and each keyboard's own) that reads [old] becomes [new] (null: removed):
     *  a saved theme, Layout or popup set renamed or deleted is followed by every keyboard that chose it, not only the
     *  one being edited (review 2026-10-06 Low). */
    fun replaceValueEverywhere(real: SharedPreferences, key: String, old: String, new: String?) {
        val editor = real.edit()
        for ((stored, value) in real.all) {
            if (value != old) continue
            if (stored != key && unprefixedKey(stored, key) == null) continue
            if (new == null) editor.remove(stored) else editor.putString(stored, new)
        }
        editor.apply()
    }
    // [stored] is "p<id>/[key]" for some id (or [key] itself)
    private fun unprefixedKey(stored: String, key: String): String? =
        if (stored.startsWith(PREFIX) && stored.endsWith("$SEPARATOR$key") && stored.substring(PREFIX.length, stored.length - key.length - SEPARATOR.length).toIntOrNull() != null) key else null

    /** Whether set [id] has anything stored of its own (a value or a mark). */
    fun hasOwnEntries(real: SharedPreferences, id: Int): Boolean = real.all.keys.any { it.startsWith("$PREFIX$id$SEPARATOR") }

    /** Profile id of a keyboard, created on first use. */
    @Synchronized
    fun idFor(real: SharedPreferences, keyboard: SettingsSubtype): Int {
        val map = ids(real)
        val pref = keyboard.toPref()
        if (map.has(pref)) return map.getInt(pref)
        val id = real.getInt(PREF_NEXT_ID, 1)
        map.put(pref, id)
        real.edit().putString(PREF_IDS, map.toString()).putInt(PREF_NEXT_ID, id + 1).apply()
        return id
    }

    /** A keyboard changed identity (layout, languages ...): its settings follow it. */
    @Synchronized
    fun onKeyboardChanged(real: SharedPreferences, from: SettingsSubtype, to: SettingsSubtype) {
        if (from == to) return
        val map = ids(real)
        val fromPref = from.toPref()
        if (!map.has(fromPref)) return
        // it became a keyboard that already has a set (a preview of another keyboard's keys, a layout renamed or
        // deleted): both sets stay as they are, nothing moves or goes; changed back (Cancel), it finds its own again
        if (map.has(to.toPref())) return
        val id = map.getInt(fromPref)
        map.remove(fromPref)
        map.put(to.toPref(), id)
        real.edit().putString(PREF_IDS, map.toString()).apply()
    }

    /** A keyboard was deleted: its set goes with it. */
    @Synchronized
    fun onKeyboardDeleted(real: SharedPreferences, keyboard: SettingsSubtype) {
        val map = ids(real)
        val pref = keyboard.toPref()
        if (!map.has(pref)) return
        val id = map.getInt(pref)
        map.remove(pref)
        val editor = real.edit().putString(PREF_IDS, map.toString())
        real.all.keys.filter { unprefixedKey(id, it) != null && it.startsWith("$PREFIX$id$SEPARATOR") }.forEach { editor.remove(it) }
        editor.apply()
        deleteFiles(id) // its own pictures: no keyboard uses them any more
    }

    fun hasOwnSettings(real: SharedPreferences, keyboard: SettingsSubtype): Boolean {
        val map = ids(real)
        val pref = keyboard.toPref()
        if (!map.has(pref)) return false
        val start = "$PREFIX${map.getInt(pref)}$SEPARATOR"
        return real.all.keys.any { it.startsWith(start) }
    }

    // ---- copying sets ----

    /** Copy the settings of profile [fromId] over those of profile [toId] (the shared set is id 0). */
    @Synchronized
    fun copy(real: SharedPreferences, fromId: Int, toId: Int) {
        if (fromId == toId) return
        val editor = real.edit()
        // clear the target first so nothing stale survives
        real.all.keys.filter { if (toId == SHARED) !isGlobal(it) else it.startsWith("$PREFIX$toId$SEPARATOR") }.forEach { editor.remove(it) }
        // what the source set reads as: the shared values, then its own on top (a profile's reads fall back to the shared
        // set); before, both were copied in the file's order, so a key in both came out as either at random
        val values = HashMap<String, Any?>()
        for ((key, value) in real.all) {
            if (key.startsWith(PREFIX) && key.contains(SEPARATOR)) continue // another set's
            if (!isGlobal(key)) values[key] = value
        }
        if (fromId != SHARED) {
            val own = real.all.mapNotNull { (key, value) -> unprefixedKey(fromId, key)?.takeIf { it != key }?.let { it to value } }
            // first what's at its default in the source (no shared value then), then its own values: a value of its own
            // wins over a mark left behind (before, the file's order decided)
            for ((plain, _) in own) if (plain.startsWith(TOMBSTONE)) values.remove(plain.removePrefix(TOMBSTONE))
            for ((plain, value) in own) if (!isGlobal(plain) && !(plain.startsWith(TOMBSTONE) && own.any { it.first == plain.removePrefix(TOMBSTONE) }))
                values[plain] = value
        }
        for ((plain, value) in values) {
            // a "default" mark only means something in a keyboard's own set
            if (toId == SHARED && plain.startsWith(TOMBSTONE)) continue
            put(editor, prefixedKey(toId, plain), value)
        }
        editor.apply()
        copyFiles(fromId, toId)
        Log.i("KeyboardProfiles", "copied settings $fromId -> $toId")
    }

    // one-time upgrade flags ("fonts_follow_migrated", "defaults_feedback_on_done"): bookkeeping, never marked
    private val upgradeFlag = Regex(".*(_migrated|_done)")
    private val oneTimeFlags = setOf("learning_swiping_global", "suggestions_refine_moved", "moved_markers_removed")

    /** A one-time step's flag (an upgrade or a settings move, done once): bookkeeping, not a setting; a keyboard's own
     *  copy (p<id>/…) doesn't count. */
    fun isOneTimeFlag(key: String) = splitOwnKey(key) == null && (upgradeFlag.matches(key) || key in oneTimeFlags)

    /** Write [settings] (plain keys) as the own set of profile [id], replacing what was there; [markDefaults]: every
     *  shared setting not in [settings] reads its default in that set (a mark), not the shared value. */
    @Synchronized
    fun write(real: SharedPreferences, id: Int, settings: Map<String, Any?>, markDefaults: Boolean = false) {
        val editor = real.edit()
        real.all.keys.filter { it.startsWith("$PREFIX$id$SEPARATOR") }.forEach { editor.remove(it) }
        for ((key, value) in settings) put(editor, prefixedKey(id, key), value)
        if (markDefaults && id != SHARED)
            for (key in real.all.keys)
                if (!isGlobal(key) && !key.startsWith(TOMBSTONE) && key !in settings && !upgradeFlag.matches(key))
                    editor.putBoolean(prefixedKey(id, TOMBSTONE + key), true)
        editor.apply()
    }

    fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value as Set<String>)
        }
    }

    // ---- reading a backup's preference map (keys as stored, i.e. prefixed) ----

    /** [keyboard]'s own set inside a backed-up preference map, plain keys; null when the backup kept no separate set for it. */
    fun ownSettingsIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?>? {
        if (backup[PREF_SEPARATE] != true) return null
        val map = try { JSONObject(backup[PREF_IDS] as? String ?: "{}") } catch (e: Exception) { JSONObject() }
        val pref = keyboard.toPref()
        if (!map.has(pref)) return null
        val id = map.getInt(pref)
        val start = "$PREFIX$id$SEPARATOR"
        return backup.filterKeys { it.startsWith(start) }.mapKeys { it.key.substring(start.length) }.filterKeys { !isGlobal(it) }
    }

    /** [keyboard]'s set id inside a backed-up preference map, or null when the backup kept no separate set for it. */
    fun idIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Int? {
        if (backup[PREF_SEPARATE] != true) return null
        val map = try { JSONObject(backup[PREF_IDS] as? String ?: "{}") } catch (e: Exception) { JSONObject() }
        return keyboard.toPref().takeIf { map.has(it) }?.let { map.getInt(it) }
    }

    /** [keyboard]'s profile id inside a backed-up preference map, whether or not its settings were separate (the id
     *  also names a keyboard's own learned words, see LearnedStores); null if the backup has none for it. */
    fun anyIdIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Int? {
        val map = try { JSONObject(backup[PREF_IDS] as? String ?: "{}") } catch (e: Exception) { JSONObject() }
        return keyboard.toPref().takeIf { map.has(it) }?.let { map.getInt(it) }
    }

    /** What [keyboard] read in a backed-up preference map: the backup's shared set with its own set on top (a default
     *  mark of its own takes the shared value out), plain keys, no marks. */
    fun effectiveSettingsIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?> {
        val values = HashMap(sharedSettingsIn(backup))
        val own = ownSettingsIn(backup, keyboard) ?: return values
        for (key in own.keys) if (key.startsWith(TOMBSTONE)) values.remove(key.removePrefix(TOMBSTONE))
        for ((key, value) in own) if (!key.startsWith(TOMBSTONE)) values[key] = value
        return values
    }

    /** The shared set of a backed-up preference map: plain, non-global keys. */
    fun sharedSettingsIn(backup: Map<String, Any?>): Map<String, Any?> =
        backup.filterKeys { !isGlobal(it) && !(it.startsWith(PREFIX) && it.contains(SEPARATOR)) }

    /**
     * Turn separate settings on: every enabled keyboard without a set of its own gets a copy of the shared set
     * ([keepExisting] false resets keyboards that have an older set too).
     */
    @Synchronized
    fun enable(real: SharedPreferences, keyboards: List<SettingsSubtype>, keepExisting: Boolean) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("separate settings ON for ${keyboards.map { it.toPref() }} (keepExisting $keepExisting)")
        for (keyboard in keyboards) {
            if (keepExisting && hasOwnSettings(real, keyboard)) continue
            copy(real, SHARED, idFor(real, keyboard))
        }
        real.edit().putBoolean(PREF_SEPARATE, true).apply()
    }

    /** Turn separate settings off; [sharedFrom] = the keyboard whose set becomes the shared one, null keeps the previous shared set. */
    @Synchronized
    fun disable(real: SharedPreferences, sharedFrom: SettingsSubtype?) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("separate settings OFF, shared from ${sharedFrom?.toPref()}")
        if (sharedFrom != null) copy(real, idFor(real, sharedFrom), SHARED)
        real.edit().putBoolean(PREF_SEPARATE, false).apply()
    }

    /** Profile of the keyboard in use (the IME's view). Refreshed on every keyboard switch. */
    @Volatile var imeId: Int = SHARED
        private set
    /** Profile the settings screens edit: a keyboard's id, or [SHARED]. Kept in the settings too, so a settings screen
     *  restored after the process died edits the same keyboard (re-review 2026-10-07: it edited the shared set). */
    @Volatile var editingId: Int = SHARED
        set(value) { field = value; editingStore?.edit()?.putInt(PREF_EDITING, value)?.apply() }
    private const val PREF_EDITING = "keyboard_profile_editing"
    /** Where [editingId] is kept, set at app start. */
    @Volatile var editingStore: SharedPreferences? = null
    /** After the process died with a settings screen open: the keyboard it was editing. */
    fun restoreEditingId(real: SharedPreferences) { editingId = real.getInt(PREF_EDITING, SHARED) }
    internal fun forgetEditingInMemory() { editingStore.let { store -> editingStore = null; editingId = SHARED; editingStore = store } }

    /** Whether the look (Appearance settings and background pictures) of set [a] differs from set [b]'s: a keyboard
     *  switch reloads the theme only then (re-review 2026-10-07: it reloaded, with a blink, on every switch). Shared
     *  Appearance: never. */
    fun looksDiffer(real: SharedPreferences, a: Int, b: Int): Boolean {
        if (a == b || !isSeparate(real) || Group.APPEARANCE in sharedGroups) return false
        fun look(id: Int): Map<String, Any?> = ProfilePreferences(real) { id }.all.filterKeys { helium314.keyboard.settings.AppearanceLooks.onScreen(it) }
        if (look(a) != look(b)) return true
        val dir = filesDir ?: return false
        fun pictures(id: Int) = profileFileNames.map { name -> java.io.File(dir, name + suffix(id)).let { if (it.isFile) it.length() to it.lastModified() else null } }
        return pictures(a) != pictures(b)
    }

    fun refreshImeId(real: SharedPreferences) {
        loadGroups(real) // (a restore can bring other group choices)
        // the learned words of the keyboard in use too, when each keyboard has its own
        helium314.keyboard.latin.personalization.LearnedStores.refresh(real)
        val old = imeId
        imeId = if (isSeparate(real)) idFor(real, selectedKeyboard(real)) else SHARED
        // another keyboard's background picture and emoji font (see profileFile)
        if (imeId != old) {
            Settings.clearCachedBackgroundImages()
            runCatching { helium314.keyboard.keyboard.KeyboardTypeface.clearCache() }
        }
    }

    // ---- files that belong to a keyboard's settings ----
    // The background pictures (with their framing) are files, not preferences: with separate
    // settings each keyboard has its own copy, named with "_p<id>" (the shared set keeps the plain name). Copying a
    // set copies them too. Before 2026-10-02 every keyboard used the plain files.

    /** The app's files dir, set at start (App) so [copy] can reach the files without a context. */
    @Volatile var filesDir: java.io.File? = null

    /** Before a plain key in a keyboard's own set: "this one is at its default" (not the shared value). */
    const val TOMBSTONE = "~"

    private val profileFileNames = listOf("custom_background_image", "custom_background_image_night",
        "custom_background_image_landscape", "custom_background_image_landscape_night")

    /** [name] for the set in use where [context] is: the settings screens' keyboard, or the keyboard on screen. */
    @JvmStatic
    fun profileFile(context: android.content.Context, name: String): java.io.File {
        val dir = helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(context)
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)
        val id = if (!isSeparate(real) || Group.APPEARANCE in sharedGroups) SHARED
            else if (context.getActivity() != null) editingId else imeId
        return java.io.File(dir, name + suffix(id))
    }

    private fun suffix(id: Int) = if (id == SHARED) "" else "_p$id"

    /** The files of set [fromId] over those of [toId] (one missing in [fromId] is removed in [toId]). */
    private fun copyFiles(fromId: Int, toId: Int) {
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) {
            val from = java.io.File(dir, name + suffix(fromId) + ext)
            val to = java.io.File(dir, name + suffix(toId) + ext)
            runCatching { if (from.exists()) from.copyTo(to, overwrite = true) else to.delete() }
        }
    }

    /** The files of set [id] (not the shared set's). */
    fun deleteFiles(id: Int) {
        if (id == SHARED) return
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) java.io.File(dir, name + suffix(id) + ext).delete()
    }

    /** Every keyboard's own picture files and the shared set's (a factory reset of the settings). */
    fun deleteAllFiles() {
        val dir = filesDir ?: return
        dir.listFiles { f -> profileFileNames.any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    /** The backup file names [restoreFiles] can use for set [fromId] (its own, and the plain ones). */
    fun restoreFileNames(fromId: Int): Set<String> =
        profileFileNames.flatMap { n -> listOf("", ".framing").flatMap { e -> listOf(n + suffix(fromId) + e, n + e) } }.toSet()

    /** Backup restore of one keyboard: its pictures from the backup's files (named for the backup's id [fromId]),
     *  given as name -> bytes, become set [toId]'s. */
    fun restoreFiles(files: Map<String, ByteArray>, fromId: Int, toId: Int, perKeyboardBackup: Boolean) {
        val dir = filesDir ?: return
        // a backup made since the pictures are per keyboard ([perKeyboardBackup]: it has "profile_files_migrated") says
        // exactly what the keyboard had, a missing file = no picture; an older one has only the plain pictures (what every
        // keyboard showed then), and without any the phone's are kept
        val source = if (perKeyboardBackup) fromId else SHARED
        if (!perKeyboardBackup && profileFileNames.none { files.containsKey(it + suffix(source)) }) return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) {
            val to = java.io.File(dir, name + suffix(toId) + ext)
            val bytes = files[name + suffix(source) + ext]
            runCatching { if (bytes != null) to.writeBytes(bytes) else to.delete() }
        }
    }

    /** Every keyboard's own entries for [key] in [all]: its value, and its "reset to default" marker ([TOMBSTONE];
     *  review 2026-10-06: the migrations below missed the markers). */
    private fun ownEntries(all: Map<String, *>, key: String) = all.keys.filter {
        it.startsWith(PREFIX) && (it.endsWith("$SEPARATOR$key") || it.endsWith("$SEPARATOR$TOMBSTONE$key")) }

    /** Moves the value [inUse] (a keyboard's id) has for [key] to the plain settings: its value, or the default if it was
     *  reset there (the plain value goes). A value beats a marker, as everywhere else in this file. */
    private fun SharedPreferences.Editor.takeOwn(all: Map<String, *>, inUse: Int, key: String) {
        if (inUse == SHARED) return
        when (val v = all["$PREFIX$inUse$SEPARATOR$key"]) {
            is Boolean -> putBoolean(key, v)
            is Int -> putInt(key, v)
            is Long -> putLong(key, v)
            is Float -> putFloat(key, v)
            is String -> putString(key, v)
            else -> if (all.containsKey("$PREFIX$inUse$SEPARATOR$TOMBSTONE$key")) remove(key)
        }
    }

    /** Once: the "reset to default" markers the two moves above left behind on phones that ran them before they removed
     *  markers too (0.3.006 / 0.3.007; review 2026-10-06): harmless now, wrong if those menus are made per keyboard. */
    /** The one-time settings moves (each checks its own flag): at app start, and right after a restore, whose backup may
     *  be from before them (re-review 2026-10-07: they ran at the next start and overwrote changes made in between). */
    fun settingsMoves(real: SharedPreferences) {
        migrateLearningSwiping(real)
        migrateSuggestionsToRefine(real)
        removeMovedMarkers(real)
        forgetSetting(real, "gesture_space_aware")
    }

    fun removeMovedMarkers(real: SharedPreferences) {
        if (real.getBoolean("moved_markers_removed", false)) return
        // with Refine per keyboard the marks are genuine resets, not leftovers (review 2026-10-06 Low): left as they are
        if (!isShared(real, Group.REFINE)) return
        val all = real.all
        val keys = learningSwipingKeys + movedToRefine
        real.edit().apply {
            for (key in keys) for (stored in all.keys)
                if (stored.startsWith(PREFIX) && stored.endsWith("$SEPARATOR$TOMBSTONE$key")) remove(stored)
            putBoolean("moved_markers_removed", true)
        }.apply()
    }

    /** Once: the settings that became the same for every keyboard ([learningSwipingKeys]) take the values of the keyboard
     *  in use (with separate settings), and the keyboards' own copies go. */
    fun migrateLearningSwiping(real: SharedPreferences) {
        if (real.getBoolean("learning_swiping_global", false)) return
        val inUse = if (isSeparate(real)) idFor(real, selectedKeyboard(real)) else SHARED
        val all = real.all
        real.edit().apply {
            for (key in learningSwipingKeys) {
                takeOwn(all, inUse, key)
                for (stored in ownEntries(all, key)) remove(stored)
            }
            putBoolean("learning_swiping_global", true)
        }.apply()
    }

    /**
     * Once (2026-10-06): Customize suggestions moved from Text correction (per keyboard) to Refine suggestions &
     * learning (shared by default). The keyboard in use keeps what it had: its own count and rules, if it has its own
     * settings, become the shared ones; every keyboard's own copy is removed.
     */
    /** The settings that moved from Text correction to Refine suggestions & learning (2026-10-06): number of suggestions
     *  and their order rules. */
    private val movedToRefine = listOf(Settings.PREF_SUGGESTION_COUNT, Settings.PREF_SUGGESTION_RULES)

    fun migrateSuggestionsToRefine(real: SharedPreferences) {
        if (real.getBoolean("suggestions_refine_moved", false)) return
        val inUse = if (isSeparate(real)) idFor(real, selectedKeyboard(real)) else SHARED
        val all = real.all
        real.edit().apply {
            for (key in movedToRefine) {
                takeOwn(all, inUse, key)
                // every keyboard's own copy (and reset marker) goes, as in migrateLearningSwiping: stale values must not
                // come back if the menu is made per keyboard later
                for (stored in ownEntries(all, key)) remove(stored)
            }
            putBoolean("suggestions_refine_moved", true)
        }.apply()
    }

    /** Once: a setting the app no longer has is switched off and then forgotten, in the plain settings and in every
     *  keyboard's own set (2026-10-05: "Phrase gesture", which only Google's removed swipe library read). */
    fun forgetSetting(real: SharedPreferences, key: String) {
        val all = real.all
        val stored = all.keys.filter { it == key || (it.startsWith(PREFIX) && it.endsWith("$SEPARATOR$key")) }
        val markers = ownEntries(all, key) - stored.toSet()
        if (stored.isEmpty() && markers.isEmpty()) return
        real.edit().apply { for (k in stored) putBoolean(k, false) }.apply()
        real.edit().apply { for (k in stored + markers) remove(k) }.apply()
    }

    /** Once: keyboards that got their own set before the files were per keyboard get a copy of the plain ones. */
    fun migrateFiles(real: SharedPreferences) {
        if (real.getBoolean("profile_files_migrated", false)) return
        val map = ids(real)
        for (key in map.keys()) {
            val id = map.optInt(key, SHARED)
            if (id == SHARED) continue
            val dir = filesDir ?: return
            if (profileFileNames.none { java.io.File(dir, it + suffix(id)).exists() }) copyFiles(SHARED, id)
        }
        real.edit().putBoolean("profile_files_migrated", true).apply()
    }

    /** The keyboard whose settings the screens edit (separate settings on), or null (shared settings). */
    fun editingKeyboard(real: SharedPreferences): SettingsSubtype? {
        val id = editingId
        if (id == SHARED || !isSeparate(real)) return null
        val map = ids(real)
        return map.keys().asSequence().firstOrNull { map.optInt(it, -1) == id }?.toSettingsSubtype()
    }

    fun selectedKeyboard(real: SharedPreferences): SettingsSubtype =
        real.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype()
}

/**
 * The preferences every part of the app talks to. Reads and writes are redirected to the active profile's keys
 * when separate settings are on; listeners get the un-prefixed key names. [activeId] is looked up on every call.
 */
class ProfilePreferences(private val real: SharedPreferences, private val activeId: () -> Int) : SharedPreferences {
    private fun id() = if (KeyboardProfiles.isSeparate(real)) activeId() else KeyboardProfiles.SHARED
    private fun k(key: String) = KeyboardProfiles.prefixedKey(id(), key)
    // a profile key that was never written falls back to the shared value, so a half-copied set still behaves
    private fun has(key: String) = real.contains(k(key))
    // set back to its default in this keyboard's own set (the "Default" buttons, Discard, a theme's default): not the
    // shared value
    private fun dead(key: String) = id() != KeyboardProfiles.SHARED && !KeyboardProfiles.isGlobal(key)
        && real.contains(KeyboardProfiles.prefixedKey(id(), KeyboardProfiles.TOMBSTONE + key))

    override fun getAll(): MutableMap<String, *> {
        // what the reads give: in a keyboard's own set its values, else the shared ones (not where it's marked "at its
        // default"); before, the shared fallbacks were missing, and a draft's Discard set those keys to their default
        val id = id()
        val result = HashMap<String, Any?>()
        val marked = HashSet<String>()
        for ((key, value) in real.all) {
            if (key.startsWith(KeyboardProfiles.TOMBSTONE)) continue
            if (key.startsWith("p") && key.contains("/")) continue // a set's own key, below
            result[key] = value
        }
        if (id != KeyboardProfiles.SHARED) {
            val own = HashMap<String, Any?>()
            for ((key, value) in real.all) {
                val plain = KeyboardProfiles.unprefixedKey(id, key)?.takeIf { it != key } ?: continue
                if (plain.startsWith(KeyboardProfiles.TOMBSTONE)) marked.add(plain.removePrefix(KeyboardProfiles.TOMBSTONE)) else own[plain] = value
            }
            for (key in marked) if (!KeyboardProfiles.isGlobal(key)) result.remove(key)
            result.putAll(own) // a value of its own wins over a mark left behind
        }
        return result
    }
    override fun getString(key: String, defValue: String?) = if (has(key)) real.getString(k(key), defValue) else if (dead(key)) defValue else real.getString(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = if (has(key)) real.getStringSet(k(key), defValues) else if (dead(key)) defValues else real.getStringSet(key, defValues)
    override fun getInt(key: String, defValue: Int) = if (has(key)) real.getInt(k(key), defValue) else if (dead(key)) defValue else real.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long) = if (has(key)) real.getLong(k(key), defValue) else if (dead(key)) defValue else real.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float) = if (has(key)) real.getFloat(k(key), defValue) else if (dead(key)) defValue else real.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = if (has(key)) real.getBoolean(k(key), defValue) else if (dead(key)) defValue else real.getBoolean(key, defValue)
    override fun contains(key: String) = has(key) || (!dead(key) && real.contains(key))

    override fun edit(): SharedPreferences.Editor = Editor(real.edit(), id())

    private inner class Editor(private val e: SharedPreferences.Editor, private val id: Int) : SharedPreferences.Editor {
        private fun k(key: String) = KeyboardProfiles.prefixedKey(id, key)
        private val ownSet = id != KeyboardProfiles.SHARED
        // a value written: no longer "at its default"; removed in a keyboard's own set: at its default, not the shared value
        private fun alive(key: String) { if (ownSet && !KeyboardProfiles.isGlobal(key)) e.remove(k(KeyboardProfiles.TOMBSTONE + key)) }
        override fun putString(key: String, value: String?) = apply { e.putString(k(key), value); alive(key) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { e.putStringSet(k(key), values); alive(key) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(k(key), value); alive(key) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(k(key), value); alive(key) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(k(key), value); alive(key) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(k(key), value); alive(key) }
        override fun remove(key: String) = apply {
            e.remove(k(key))
            if (ownSet && !KeyboardProfiles.isGlobal(key)) e.putBoolean(k(KeyboardProfiles.TOMBSTONE + key), true)
        }
        override fun clear() = apply { e.clear() }
        override fun commit() = e.commit()
        override fun apply() = e.apply()
    }

    private val listeners = HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val plain = if (key == null) null else KeyboardProfiles.unprefixedKey(id(), key)?.removePrefix(KeyboardProfiles.TOMBSTONE)
            // keys of other profiles are not this profile's business; shared keys still are (fallback reads)
            if (key == null || plain != null) listener.onSharedPreferenceChanged(this, plain)
        }
        // registered again (a restore restarts the listener without stopping it): one callback, not two
        synchronized(listeners) { listeners.put(listener, wrapped) }?.let { real.unregisterOnSharedPreferenceChangeListener(it) }
        real.registerOnSharedPreferenceChangeListener(wrapped)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapped = synchronized(listeners) { listeners.remove(listener) } ?: return
        real.unregisterOnSharedPreferenceChangeListener(wrapped)
    }

    /** The underlying preferences, for the profile bookkeeping and backup. */
    val underlying: SharedPreferences get() = real
}

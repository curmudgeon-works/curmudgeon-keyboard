// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import helium314.keyboard.latin.utils.getActivity
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject

/**
 * Separate settings per keyboard. Every keyboard (subtype) has a stable profile id and its own set of settings, under
 * keys "p<id>/<key>" in the same preferences file; the plain keys are the app-wide ones only. There is no common set
 * (owner 2026-10-11: "I think we shouldn't have separate common settings. Just keep main keyboard's settings and othrr
 * keyboards point to it. Creating a separate set just creates risk and it's not intended behavior."): the common
 * settings are the main keyboard's own ([mainKeyboard], the first in the list), read by every keyboard while separate
 * settings is off, and for each shared menu. Switching copies nothing: it only changes which set a read points at. Sets
 * are never deleted by switching, only by deleting a keyboard.
 *
 * Which profile a read or write goes to is decided by [ProfilePreferences]: the keyboard in use for the IME,
 * the keyboard being edited for the settings screens.
 */
object KeyboardProfiles {
    /** "The common settings": no set of its own, the main keyboard's ([mainId]); its pictures too. */
    const val SHARED = 0

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
        PREF_SEPARATE, PREF_IDS, PREF_NEXT_ID, PREF_START_FROM, PREF_SHARED_FROM, // (the last two: old bookkeeping)
        "key_popup_sets", // saved popup sets are meant to be reused across keyboards
        "appearance_looks", // saved looks too
        "layout_presets", // and saved Layouts
        helium314.keyboard.settings.SavedItemDefaults.PREF, // what saved items do with new defaults: one switch for all
    ) + Group.entries.map { it.prefKey } +
        // which pool of learned words each keyboard has, its buttons, the old switch (read by the update only)
        helium314.keyboard.latin.personalization.LearnedKeyboards.appWideKeys

    /**
     * The top-level menus whose settings can be the keyboard's own or shared by all keyboards (App settings > Per
     * keyboard). A setting on two menus (auto-space after a swipe: Swipe and Text correction) is shared only when both
     * are; when one is per keyboard it belongs to that one and the shared menu doesn't show it ([hiddenOn]).
     * Learned & blacklisted words are files, not settings: always each keyboard's own (LearnedKeyboards).
     */
    enum class Group(val prefKey: String, val sharedByDefault: Boolean) {
        LAYOUT("share_group_layout", false),
        APPEARANCE("share_group_appearance", false),
        SWIPE("share_group_swipe", false),
        TEXT_CORRECTION("share_group_correction", false),
        REFINE("share_group_refine", true), // "Fine-tune suggestions": your hand, so one set by default
        LEARNING("share_group_learning", true); // "Fine-tune learning" (split from Refine 2026-10-10): your words, the same

        // (a "Custom" label follows its menu: one shared copy, one label; review 2026-10-11 D1. Here, not in the
        // screens' scopes: a label is no setting of a saved Layout or theme, and no change of the screen's draft)
        fun contains(key: String): Boolean = when (this) {
            LAYOUT -> helium314.keyboard.settings.LayoutDraft.inScope(key) || key in layoutLabelKeys
            APPEARANCE -> helium314.keyboard.settings.AppearanceLooks.onScreen(key) || key in themeLabelKeys
            SWIPE -> key in helium314.keyboard.settings.screens.swipeSettingKeys
            TEXT_CORRECTION -> key in helium314.keyboard.settings.screens.correctionKeys
            REFINE -> key in refineKeys
            LEARNING -> key in learningKeys
        }
    }

    // (a deleted origin's name goes with its label, 2026-10-11)
    private val layoutLabelKeys get() = with(helium314.keyboard.settings.CustomLabels.Kind.LAYOUT) { setOf(key, fromKey) } +
        with(helium314.keyboard.settings.CustomLabels.Kind.POPUPS) { setOf(key, fromKey) }
    private val themeLabelKeys get() = with(helium314.keyboard.settings.CustomLabels.Kind.THEME) { setOf(key, fromKey) }

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
     * [group] becomes shared by all keyboards ([shared]) or each keyboard's own. Shared, its settings are read from and
     * written to the main keyboard's own set ([mainId]), its pictures too; per keyboard, each keyboard reads its own set
     * again. Nothing is copied, nothing deleted (owner 2026-10-11: "no separate common set"): the main keyboard keeps
     * what was changed meanwhile, those were its own, and every other keyboard's own values stay as they were.
     */
    @Synchronized
    fun setGroupShared(real: SharedPreferences, group: Group, shared: Boolean) {
        real.edit().putBoolean(group.prefKey, shared).commit()
        sharedGroups = if (shared) sharedGroups + group else sharedGroups - group
        helium314.keyboard.latin.utils.SettingsEventLog.log("group ${group.name} ${if (shared) "shared, the main keyboard's" else "per keyboard"}")
    }

    /** "keyboard_shared_from", the old record of where shared settings came from (before 2026-10-11): unread. */
    private const val PREF_SHARED_FROM = "keyboard_shared_from"

    // ---- the main keyboard (owner 2026-10-11: "Not the keyboard you pick, but the first keyboard in our list") ----

    /** The first keyboard in the list (the Keyboards screen's order): its own set is the common settings. The list is
     *  stored from the first start on (SubtypeSettings stores the default keyboard, owner 2026-10-11); found empty all
     *  the same (defensive, a read writes nothing): the keyboards loaded, else the default keyboard, else the selected. */
    fun mainKeyboard(real: SharedPreferences): SettingsSubtype {
        val enabled = real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!
        enabled.split(helium314.keyboard.latin.common.Constants.Separators.SETS).firstOrNull { it.isNotEmpty() }?.let { return it.toSettingsSubtype() }
        return runCatching { helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes(true).firstOrNull()?.toSettingsSubtype() }.getOrNull()
            ?: real.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype()
    }

    /** The set id of [mainKeyboard], given one if it has none yet ([create]; else [NO_SET]: a read gives out no id). */
    fun mainId(real: SharedPreferences, create: Boolean = true): Int {
        val enabled = real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)
        val idsNow = real.getString(PREF_IDS, null)
        mainIdMemo?.let { if (it.selected == enabled && it.ids == idsNow && !enabled.isNullOrEmpty()) return it.id }
        val keyboard = mainKeyboard(real)
        val id = if (create) idFor(real, keyboard)
            else ids(real).let { map -> keyboard.toPref().takeIf { map.has(it) }?.let { map.getInt(it) } } ?: return NO_SET
        mainIdMemo = MainMemo(enabled ?: "", real.getString(PREF_IDS, null), id) // (idFor may have added it)
        return id
    }

    /** No set: nothing is stored under it (the main keyboard without an id yet, for a read). */
    private const val NO_SET = Int.MIN_VALUE
    // (every preference read asks: the list and the ids aren't parsed again while neither changed)
    @Volatile private var mainIdMemo: MainMemo? = null

    fun isMain(real: SharedPreferences, keyboard: SettingsSubtype) = keyboard == mainKeyboard(real)

    /** Whether some keyboard reads the main keyboard's settings now: separate settings off, or a menu shared. */
    fun mainIsCommon(real: SharedPreferences) = !isSeparate(real) || Group.entries.any { isShared(real, it) }

    /** [keyboard] is the main one and its settings are the common ones: it can't be deleted (owner 2026-10-11: "can't
     *  delete when common settings are selected. User should be asked to push it down in prio order first."). */
    fun mainBlocksDelete(real: SharedPreferences, keyboard: SettingsSubtype) = mainIsCommon(real) && isMain(real, keyboard)

    /** The old "Refine swipe and learning" menu (2026-10-04), split in two on 2026-10-10 ([refineKeys], [learningKeys]);
     *  the one-time moves below still work on the whole of it. */
    private val learningSwipingKeys: Set<String> get() = setOf(
        Settings.PREF_GESTURE_TURN_WEIGHT, Settings.PREF_GESTURE_SLOWDOWN_WEIGHT, Settings.PREF_GESTURE_KUSHLER_WEIGHT,
        Settings.PREF_GESTURE_HISTORY_BOOST, Settings.PREF_GESTURE_FAST_COMMON_WORDS, Settings.PREF_GESTURE_CORNER_MISS, Settings.PREF_GESTURE_FAST_SPEED,
        Settings.PREF_SUGGESTION_COUNT, Settings.PREF_SUGGESTION_RULES, // Customize suggestions (from Text correction, 2026-10-06)
        Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT,
    )

    /** "Fine-tune learning" (2026-10-10): how your own words are learned and trusted ([Group.LEARNING]). */
    private val learningKeys: Set<String> get() = setOf(Settings.PREF_AUTOCORRECT_FREQUENT_WORDS, Settings.PREF_TRUST_TYPED_COUNT) +
        helium314.keyboard.latin.personalization.LearnableText.keys // what a space and the strip learn

    /** "Fine-tune suggestions": the rest of the old Refine menu ([Group.REFINE]). */
    private val refineKeys: Set<String> get() = learningSwipingKeys - learningKeys
    // ("share_user_history_": the retired per-language share switch, kept global so old keys stay where they are)
    // (language priorities are the keyboard's since 2026-10-07: a keyboard's own set holds its own when settings are
    // separate; the plain value is what the shared set, and every keyboard without its own, reads)
    private val globalPrefixes = listOf(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, "share_user_history_", LanguagePriority.PREF_ADDED_PREFIX, "debug_", "gesture_stats")

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

    /** A key stored plain, never in a set: [isAppWide], a one-time step's flag (and a "default" mark of one). */
    private fun isPlain(key: String) = key.removePrefix(TOMBSTONE).let { isAppWide(it) || isOneTimeFlag(it) }

    /**
     * The set [key] of set [id] is in: null for a plain key ([isPlain]); the main keyboard's own set ([mainId]) for the
     * common settings, i.e. [SHARED], every per-keyboard setting while separate settings is off (not with [ownSet]: a
     * keyboard's own set as it is), and a shared menu's settings; else [id]'s own.
     */
    fun setOf(real: SharedPreferences, id: Int, key: String, ownSet: Boolean = false, create: Boolean = true): Int? {
        if (isPlain(key)) return null
        return if (id == SHARED || sharedByGroups(key.removePrefix(TOMBSTONE)) || (!ownSet && !isSeparate(real))) mainId(real, create) else id
    }

    /** [key] as stored for set [id] ([setOf]; [create] false: for a read). */
    fun prefixedKey(real: SharedPreferences, id: Int, key: String, ownSet: Boolean = false, create: Boolean = true): String =
        setOf(real, id, key, ownSet, create)?.let { ownKey(it, key) } ?: key

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

    /** Every stored copy of [key] (each keyboard's own, the main's for all; a plain one of before) that reads [old] becomes [new] (null: removed):
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

    /** Every stored copy of [key] that reads [old] goes, in every set (hidden ones too): the shared one is removed, a
     *  keyboard's own is removed and marked "at its default" ([TOMBSTONE]), so that keyboard reads nothing chosen, not
     *  the shared choice (another theme, Layout or popup set) nor one saved later under [old] (2026-10-07: a delete
     *  cleared it only in the keyboard being edited; [replaceValueEverywhere] with null leaves no mark). */
    fun forgetValueEverywhere(real: SharedPreferences, key: String, old: String) {
        val editor = real.edit()
        for ((stored, value) in real.all) {
            if (value != old) continue
            if (stored == key) editor.remove(stored)
            else if (unprefixedKey(stored, key) != null)
                editor.remove(stored).putBoolean(stored.dropLast(key.length) + TOMBSTONE + key, true)
        }
        editor.apply()
    }

    /** The keyboards (in [keyboards]' order) that use a saved item through [keys], for the delete warnings (2026-10-08):
     *  what each reads now (separate settings off or the menu shared: the shared value; a mark: nothing chosen), or what
     *  its own set keeps, hidden or not (a mark there: nothing chosen). [matches] gets the plain key and the value (null:
     *  nothing chosen). Reads only: no keyboard gets an id here. [edited]: the keyboard a settings screen edits and the
     *  set it edits ([editingId]), which wins over the ids map: while a Layout is previewed the keyboard has the
     *  previewed identity, and an old entry for that identity names another set ([onKeyboardChanged] moves nothing). */
    fun keyboardsUsing(real: SharedPreferences, keyboards: List<SettingsSubtype>, keys: Collection<String>,
                       edited: Pair<SettingsSubtype, Int>? = null,
                       matches: (key: String, value: String?) -> Boolean): List<SettingsSubtype> {
        val all = real.all
        return keyboards.filter { keyboard ->
            val id = edited?.takeIf { it.first == keyboard }?.second ?: anyIdIn(all, keyboard) ?: SHARED
            val now = ProfilePreferences(real) { id } // (separate settings off or a shared menu: the main's set)
            keys.any { key ->
                if (matches(key, now.getString(key, null))) return@any true
                if (id == SHARED) return@any false
                val own = ownKey(id, key)
                when {
                    real.contains(own) -> matches(key, real.getString(own, null))
                    real.contains(ownKey(id, TOMBSTONE + key)) -> matches(key, null)
                    else -> false
                }
            }
        }
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
        // (never one in use: a map without its next id, an old backup's, gave the next keyboard another one's set)
        val id = maxOf(real.getInt(PREF_NEXT_ID, 1), (map.keys().asSequence().maxOfOrNull { map.optInt(it, SHARED) } ?: 0) + 1)
        map.put(pref, id)
        real.edit().putString(PREF_IDS, map.toString()).putInt(PREF_NEXT_ID, id + 1).apply()
        return id
    }

    /** The next profile id to be given out. */
    fun nextProfileId(real: SharedPreferences): Int = real.getInt(PREF_NEXT_ID, 1)

    /** Every keyboard identity with its profile id (reads only). */
    fun idEntries(real: SharedPreferences): Map<String, Int> = ids(real).let { map -> map.keys().asSequence().associateWith { map.optInt(it, SHARED) } }

    /** A keyboard changed identity (layout, languages ...): its settings follow it, and its learned words. */
    @Synchronized
    fun onKeyboardChanged(real: SharedPreferences, from: SettingsSubtype, to: SettingsSubtype) {
        if (from == to) return
        helium314.keyboard.latin.personalization.LearnedKeyboards.onKeyboardChanged(real, from, to)
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

    /** A keyboard was deleted: its set goes with it (and which learned words it had). */
    @Synchronized
    fun onKeyboardDeleted(real: SharedPreferences, keyboard: SettingsSubtype) {
        helium314.keyboard.latin.personalization.LearnedKeyboards.onKeyboardDeleted(real, keyboard)
        val map = ids(real)
        val pref = keyboard.toPref()
        // apps that remembered it remember nothing now (review 2026-10-07: the entry stayed, naming a deleted keyboard)
        real.all.filter { (k, v) -> k.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX) && v.toString().toSettingsSubtype() == keyboard }
            .keys.takeIf { it.isNotEmpty() }?.let { gone -> real.edit().apply { gone.forEach { remove(it) } }.apply() }
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

    /** Set [toId] becomes what set [fromId] reads as its own: its entries, a shared menu's from the main keyboard's set,
     *  its pictures; [SHARED] is the main's. What [toId] had goes. */
    @Synchronized
    fun copy(real: SharedPreferences, fromId: Int, toId: Int) {
        val from = if (fromId == SHARED) mainId(real) else fromId
        val to = if (toId == SHARED) mainId(real) else toId
        if (from == to) return
        val all = real.all
        val values = HashMap<String, Any?>() // (as stored in the set, a mark keeping its TOMBSTONE)
        for ((stored, value) in all) {
            val (set, raw) = splitOwnKey(stored) ?: continue
            val plain = raw.removePrefix(TOMBSTONE)
            if (isAppWide(plain)) continue
            // (a one-time step's flag: the source set's own, as its steps ran on it)
            val source = if (isOneTimeFlag(plain)) from else setOf(real, from, plain, ownSet = true) ?: continue
            if (set == source) values[raw] = value
        }
        // a value of its own wins over a mark left behind; a mark on a one-time flag means nothing (review 2026-10-08 area 1 #5)
        values.keys.filter { it.startsWith(TOMBSTONE) && it.removePrefix(TOMBSTONE).let { p -> values.containsKey(p) || isOneTimeFlag(p) } }
            .forEach { values.remove(it) }
        val editor = real.edit()
        all.keys.filter { it.startsWith("$PREFIX$to$SEPARATOR") }.forEach { editor.remove(it) }
        for ((raw, value) in values) put(editor, ownKey(to, raw), value)
        editor.commit()
        copyFiles(if (Group.APPEARANCE in sharedGroups) mainId(real) else from, to)
        Log.i("KeyboardProfiles", "copied settings $from -> $to")
    }

    // one-time upgrade flags ("fonts_follow_migrated", "defaults_feedback_on_done"): bookkeeping, never marked
    private val upgradeFlag = Regex(".*(_migrated|_done)")
    private val oneTimeFlags = setOf("learning_swiping_global", "suggestions_refine_moved", "moved_markers_removed")

    /** A one-time step's flag (an upgrade or a settings move, done once): bookkeeping, not a setting; a keyboard's own
     *  copy (p<id>/…) doesn't count. */
    fun isOneTimeFlag(key: String) = splitOwnKey(key) == null && (upgradeFlag.matches(key) || key in oneTimeFlags)

    /** Set [id] at every default: nothing of its own (a keyboard added with "every default"). */
    @Synchronized
    fun clearSet(real: SharedPreferences, id: Int) {
        val editor = real.edit()
        real.all.keys.filter { it.startsWith("$PREFIX$id$SEPARATOR") }.forEach { editor.remove(it) }
        editor.commit()
        deleteFiles(id)
    }

    /**
     * A restore of one keyboard: [settings] (plain keys) become set [id]'s own entries for every key [takes] (a partly
     * replaced keyboard: its ticked menus); what it had for those keys goes, its entries for the other keys stay. A
     * shared menu's keys too: the main keyboard's set is the common settings, so restoring it changes what every keyboard
     * sees there (owner 2026-10-11: "yep"); another keyboard's values there wait, hidden, in its own set (owner
     * 2026-10-11: "restore should only apply to that keyboard's settings. Regardless of whether it is shared with others
     * or hidden or separate"). Never another set's keys. Its one-time steps' flags stay the phone's.
     */
    @Synchronized
    fun restoreSet(real: SharedPreferences, id: Int, settings: Map<String, Any?>, takes: (String) -> Boolean = { true }) {
        if (id == SHARED) return
        val editor = real.edit()
        for (stored in real.all.keys) {
            val plain = splitOwnKey(stored)?.takeIf { it.first == id }?.second?.removePrefix(TOMBSTONE) ?: continue
            if (!isPlain(plain) && takes(plain)) editor.remove(stored)
        }
        for ((key, value) in settings) if (!isPlain(key) && !key.startsWith(TOMBSTONE) && takes(key)) put(editor, ownKey(id, key), value)
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

    /** [keyboard]'s own set inside a backed-up preference map, plain keys (marks keep their [TOMBSTONE]); null when the
     *  backup kept no set for it. */
    fun ownSettingsIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?>? {
        val id = anyIdIn(backup, keyboard) ?: return null
        val start = "$PREFIX$id$SEPARATOR"
        return backup.filterKeys { it.startsWith(start) }.mapKeys { it.key.substring(start.length) }.filterKeys { !isPlain(it) }
    }

    /** [keyboard]'s set id inside a backed-up preference map, or null when the backup has none for it (every keyboard
     *  has its own set now, whether its settings were separate or not). */
    fun idIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Int? = anyIdIn(backup, keyboard)

    /** [keyboard]'s profile id inside a backed-up preference map, whether or not its settings were separate (the id
     *  also names a keyboard's own learned words, see LearnedStores); null if the backup has none for it. */
    fun anyIdIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Int? {
        val map = try { JSONObject(backup[PREF_IDS] as? String ?: "{}") } catch (e: Exception) { JSONObject() }
        return keyboard.toPref().takeIf { map.has(it) }?.let { map.getInt(it) }
    }

    /** What [keyboard]'s own set holds in a backed-up preference map (or the phone's): plain keys, no marks, shared
     *  menus' values too (hidden while shared: what it reads once they are per keyboard). An old backup's common set
     *  (its plain keys) is ignored (owner 2026-10-11: "fine to trash common set with upgrade and restore"). */
    fun effectiveSettingsIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?> =
        ownSettingsIn(backup, keyboard).orEmpty().filterKeys { !it.startsWith(TOMBSTONE) }

    /** Turn separate settings on: every keyboard reads its own set again (each has one; nothing is copied). */
    @Synchronized
    fun enable(real: SharedPreferences) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("separate settings ON")
        real.edit().putBoolean(PREF_SEPARATE, true).remove(PREF_START_FROM).apply()
    }

    // ---- the settings a keyboard starts from (the Add keyboard dialog, 2026-10-10) ----

    /** Before 2026-10-11, a keyboard added with one set for all kept its pick here for later: unread, removed. */
    private const val PREF_START_FROM = "keyboard_start_from"

    /** Start from every default rather than another set. */
    const val START_DEFAULTS = -1

    /** Keyboard set [id] starts from set [from] ([SHARED]: the main keyboard's; [START_DEFAULTS]: every default), at once,
     *  whether settings are separate or not (owner 2026-10-11: every keyboard always has its own set); [noPopups]: not
     *  the source's popups, which are for another script's letters. */
    fun startKeyboardFrom(real: SharedPreferences, id: Int, from: Int, noPopups: Boolean) {
        if (from == START_DEFAULTS) clearSet(real, id) else copy(real, from, id)
        real.edit().apply {
            remove(PREF_START_FROM)
            if (noPopups) listOf("key_popups", "key_popup_set_selected", Settings.PREF_SYMBOL_POPUP_MAP)
                .forEach { remove(ownKey(id, it)); remove(ownKey(id, TOMBSTONE + it)) }
        }.commit()
    }

    /** Turn separate settings off: every keyboard reads the main keyboard's set ([mainKeyboard]); nothing is copied. */
    @Synchronized
    fun disable(real: SharedPreferences) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("separate settings OFF, the main keyboard's: ${mainKeyboard(real).toPref()}")
        real.edit().putBoolean(PREF_SEPARATE, false).apply()
    }

    /** Profile of the keyboard in use (the IME's view). Refreshed on every keyboard switch. */
    @Volatile var imeId: Int = SHARED
        private set

    /** The set the settings screen of [context] edits: the keyboard of the screen's own navigation entry
     *  ([KeyboardScopeContext]), else [mainEditingId]. No global that screens set (2026-10-09: "who drew last wins" left
     *  a screen editing another keyboard during a slide). Navigation keeps the entry's keyboard over process death. */
    fun editingId(context: android.content.Context): Int {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)
        return KeyboardScopeContext.idOf(context)?.let { scopedId(real, it) } ?: mainEditingId(real)
    }

    /** Where no keyboard's own screen is (the main screen, App settings, their search): the keyboard in use when settings
     *  are separate, else the common settings ([SHARED]: the main keyboard's set). Worked out on every call, never stored. */
    fun mainEditingId(real: SharedPreferences): Int {
        if (!isSeparate(real)) return SHARED
        val selected = real.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!
        val idsNow = real.getString(PREF_IDS, null)
        mainMemo?.let { if (it.selected == selected && it.ids == idsNow) return it.id }
        val id = idFor(real, selected.toSettingsSubtype())
        mainMemo = MainMemo(selected, real.getString(PREF_IDS, null), id) // (idFor may have added it)
        return id
    }
    // (every preference read asks: the ids aren't parsed again while neither changed)
    private class MainMemo(val selected: String, val ids: String?, val id: Int)

    @Volatile private var mainMemo: MainMemo? = null

    /** Set [id] of a keyboard's screen while a keyboard has it; one that is gone (the keyboard deleted, a factory reset or
     *  a restore) gives [mainEditingId], so nothing is written under an id no keyboard has. */
    fun scopedId(real: SharedPreferences, id: Int): Int {
        if (!isSeparate(real)) return SHARED
        val idsNow = real.getString(PREF_IDS, null)
        val known = knownMemo?.takeIf { it.first == idsNow }?.second
            ?: ids(real).let { map -> map.keys().asSequence().map { map.optInt(it, SHARED) }.toHashSet() }
                .also { knownMemo = idsNow to it }
        if (id in known) return id
        if (goneLogged.add(id)) Log.i("KeyboardProfiles", "set $id is gone: its screens edit the main set")
        return mainEditingId(real)
    }

    @Volatile private var knownMemo: Pair<String?, Set<Int>>? = null
    private val goneLogged: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        if (imeId != old) helium314.keyboard.latin.utils.LanguagePriority.clearCache() // (priorities are per keyboard)
        // another keyboard's background picture (see profileFile) and emoji font choice (emoji_font: the fonts are shared,
        // FontLibrary, but each keyboard picks its own)
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
        // (the common pictures are the main keyboard's; the keyboard's reads give out no set id, NO_SET names no file;
        // a settings screen, which can write the picture, gives one)
        val id = if (!isSeparate(real) || Group.APPEARANCE in sharedGroups) SHARED
            else if (context.getActivity() != null || KeyboardScopeContext.idOf(context) != null) editingId(context) else imeId
        return java.io.File(dir, name + suffix(if (id == SHARED) mainId(real, create = context.getActivity() != null) else id))
    }

    /** "_p<id>"; the plain names ([SHARED]) are the old common set's pictures, read only by the steps of before. */
    private fun suffix(id: Int) = if (id == SHARED) "" else "_p$id"

    /** Set [id]'s background picture files, each followed by its framing file (whether they exist or not); [SHARED]:
     *  the main keyboard's. */
    fun pictureFiles(context: android.content.Context, id: Int): List<java.io.File> {
        val dir = helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(context)
        val set = if (id == SHARED) mainId(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)) else id
        return profileFileNames.flatMap { name -> java.io.File(dir, name + suffix(set)).let {
            listOf(it, helium314.keyboard.latin.common.PictureFraming.fileFor(it)) } }
    }

    /** The files of set [fromId] over those of [toId] (one missing in [fromId] is removed in [toId]). */
    private fun copyFiles(fromId: Int, toId: Int) {
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) {
            val from = java.io.File(dir, name + suffix(fromId) + ext)
            val to = java.io.File(dir, name + suffix(toId) + ext)
            runCatching { if (from.exists()) from.copyTo(to, overwrite = true) else to.delete() }
        }
    }

    private fun hasOwnFiles(id: Int): Boolean {
        val dir = filesDir ?: return false
        return profileFileNames.any { java.io.File(dir, it + suffix(id)).exists() }
    }

    /** The files of set [id] (not the shared set's). */
    fun deleteFiles(id: Int) {
        if (id == SHARED) return
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) java.io.File(dir, name + suffix(id) + ext).delete()
    }

    /** Every keyboard's own picture files and the old common set's (a factory reset of the settings). */
    fun deleteAllFiles() {
        val dir = filesDir ?: return
        dir.listFiles { f -> profileFileNames.any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    /** The backup file names [restoreFiles] can use for set [fromId] (its own, and the plain ones). */
    fun restoreFileNames(fromId: Int): Set<String> =
        profileFileNames.flatMap { n -> listOf("", ".framing").flatMap { e -> listOf(n + suffix(fromId) + e, n + e) } }.toSet()

    /** Backup restore of one keyboard: its pictures from the backup's files (named for the backup's id [fromId]; null:
     *  the backup has no set for it), given as name -> bytes, become set [toId]'s. */
    fun restoreFiles(files: Map<String, ByteArray>, fromId: Int?, toId: Int, perKeyboardBackup: Boolean) {
        val dir = filesDir ?: return
        // a backup made since the pictures are per keyboard ([perKeyboardBackup]: it has "profile_files_migrated") says
        // exactly what the keyboard had, a missing file = no picture (no set of its own: none, the backup's common
        // pictures are ignored, as its common settings); an older one has only the plain pictures (what every keyboard
        // showed then), and without any the phone's are kept
        val source = if (perKeyboardBackup) fromId ?: -1 else SHARED
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
        splitLearningFromRefine(real)
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

    /** Once (2026-10-10): "Fine-tune learning" split from Refine. A phone that keeps Refine per keyboard keeps the
     *  learning settings per keyboard too: their own values stay where they are, read through the new group. */
    fun splitLearningFromRefine(real: SharedPreferences) {
        if (real.getBoolean("learning_group_split_done", false)) return
        real.edit().apply {
            if (real.contains(Group.REFINE.prefKey) && !real.contains(Group.LEARNING.prefKey))
                putBoolean(Group.LEARNING.prefKey, isShared(real, Group.REFINE))
            putBoolean("learning_group_split_done", true)
        }.commit()
        loadGroups(real)
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

    /** Set once [dropCommonSet] ran. */
    const val NO_COMMON_SET_DONE = "no_common_set_done"

    /**
     * Once (owner 2026-10-11: no separate common set, "just nuke common settings. Let the main keyboard keep whatever it
     * had"): every keyboard in the list without a set of its own (the main one too) gets a copy of the common set, what it
     * read so far, so it keeps what it sees ([fromCommon]; false, after a whole restore of a backup from before: the
     * main keyboard's own set instead, the backup's common set ignored: "fine to trash common set with upgrade and
     * restore"), its pictures too; then the common set's keyboard settings and pictures go. A keyboard with a set of its
     * own keeps it as it is, the main one and the others alike (owner 2026-10-11: "Main keyboard got the right treatment
     * when common was trashed. Other keyboards should havr gotten the same."): no gap-filling from the common set, and no
     * copies of the common colour palettes either (owner 2026-10-11: deleted with the common set). An Add keyboard pick
     * kept for later ("keyboard_start_from", before 2026-10-11) is dropped, not applied (owner 2026-10-11: "don't care about keyboards before this update really").
     * App-wide keys and one-time flags stay.
     */
    @Synchronized
    fun dropCommonSet(real: SharedPreferences, fromCommon: Boolean) {
        if (real.getBoolean(NO_COMMON_SET_DONE, false)) return
        val keyboards = helium314.keyboard.latin.utils.SubtypeSettings.createSettingsSubtypes(
            real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!).ifEmpty { listOf(mainKeyboard(real)) }
        val without = keyboards.filter { !hasOwnSettings(real, it) }.map { idFor(real, it) }
        val main = mainId(real)
        val all = real.all
        // (one-time flags too, as a copy always took them: a set's own steps mustn't run again on it)
        val common = all.filterKeys { splitOwnKey(it) == null && !isAppWide(it) && !it.startsWith(TOMBSTONE) }
        val editor = real.edit()
        for (id in without) {
            if (fromCommon) for ((key, value) in common) put(editor, ownKey(id, key), value)
            else if (id != main) for ((stored, value) in all) {
                val (set, raw) = splitOwnKey(stored) ?: continue
                if (set == main) put(editor, ownKey(id, raw), value)
            }
        }
        // (the old bookkeeping; a pending Add keyboard pick isn't applied: a keyboard without a set got the common one)
        editor.remove(PREF_START_FROM).remove(PREF_SHARED_FROM)
        // (owner 2026-10-11: the common set's palettes are deleted at the upgrade, no copies: "fine to delete due to our
        // early stages"; a keyboard without a set has them in its copy above)
        for (key in common.keys) if (!isOneTimeFlag(key)) editor.remove(key)
        editor.putBoolean(NO_COMMON_SET_DONE, true)
        editor.commit()
        for (id in without) if (fromCommon) copyFiles(SHARED, id) else if (id != main) copyFiles(main, id)
        filesDir?.let { dir -> for (name in profileFileNames) for (ext in listOf("", ".framing")) java.io.File(dir, name + ext).delete() }
        Log.i("KeyboardProfiles", "no common set: ${without.size} keyboards got ${if (fromCommon) "the common set" else "the main's"}")
    }

    /** The keyboard whose settings set [id] holds (separate settings on), or null (shared settings). */
    fun editingKeyboard(real: SharedPreferences, id: Int): SettingsSubtype? {
        if (id == SHARED || !isSeparate(real)) return null
        val map = ids(real)
        return map.keys().asSequence().firstOrNull { map.optInt(it, -1) == id }?.toSettingsSubtype()
    }

    fun selectedKeyboard(real: SharedPreferences): SettingsSubtype =
        real.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype()
}

/**
 * The preferences every part of the app talks to. Reads and writes go to the set [KeyboardProfiles.setOf] names for
 * each key: the active profile's own, the main keyboard's for the common settings (separate settings off, a shared
 * menu), plain for app-wide keys; listeners get the un-prefixed key names. [activeId] is looked up on every call.
 * [ownSet]: the profile's own set with separate settings off too (an update reaching a switched-off keyboard's set).
 * No fallback to another set (owner 2026-10-11: no separate common set): what a set doesn't store is at its default.
 */
class ProfilePreferences(private val real: SharedPreferences, private val ownSet: Boolean,
                         private val activeId: () -> Int) : SharedPreferences {
    constructor(real: SharedPreferences, activeId: () -> Int) : this(real, false, activeId)

    private fun id() = activeId()
    // (a read gives out no set id: a main keyboard without one yet reads nothing)
    private fun k(key: String) = KeyboardProfiles.prefixedKey(real, id(), key, ownSet, create = false)

    override fun getAll(): MutableMap<String, *> {
        // what the reads give: the plain app-wide keys, and for every other key the set it is read from
        val id = id()
        val result = HashMap<String, Any?>()
        for ((key, value) in real.all) {
            val own = KeyboardProfiles.splitOwnKey(key)
            if (own == null) {
                if (!key.startsWith(KeyboardProfiles.TOMBSTONE) && KeyboardProfiles.setOf(real, id, key, ownSet, create = false) == null) result[key] = value
                continue
            }
            val (set, plain) = own
            if (plain.startsWith(KeyboardProfiles.TOMBSTONE)) continue
            if (KeyboardProfiles.setOf(real, id, plain, ownSet, create = false) == set) result[plain] = value
        }
        return result
    }
    override fun getString(key: String, defValue: String?) = real.getString(k(key), defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = real.getStringSet(k(key), defValues)
    override fun getInt(key: String, defValue: Int) = real.getInt(k(key), defValue)
    override fun getLong(key: String, defValue: Long) = real.getLong(k(key), defValue)
    override fun getFloat(key: String, defValue: Float) = real.getFloat(k(key), defValue)
    override fun getBoolean(key: String, defValue: Boolean) = real.getBoolean(k(key), defValue)
    override fun contains(key: String) = real.contains(k(key))

    override fun edit(): SharedPreferences.Editor = Editor(real.edit(), id())

    private inner class Editor(private val e: SharedPreferences.Editor, private val id: Int) : SharedPreferences.Editor {
        private fun k(key: String) = KeyboardProfiles.prefixedKey(real, id, key, ownSet)
        private fun inSet(key: String) = KeyboardProfiles.setOf(real, id, key, ownSet) != null
        // a value written: no longer "at its default"; removed in a set: marked "at its default" (what pickers and the
        // saved items' checks tell from nothing chosen)
        private fun alive(key: String) { if (inSet(key)) e.remove(k(KeyboardProfiles.TOMBSTONE + key)) }
        override fun putString(key: String, value: String?) = apply { e.putString(k(key), value); alive(key) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { e.putStringSet(k(key), values); alive(key) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(k(key), value); alive(key) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(k(key), value); alive(key) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(k(key), value); alive(key) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(k(key), value); alive(key) }
        override fun remove(key: String) = apply {
            e.remove(k(key))
            if (inSet(key)) e.putBoolean(k(KeyboardProfiles.TOMBSTONE + key), true)
        }
        override fun clear() = apply { e.clear() }
        override fun commit() = e.commit()
        override fun apply() = e.apply()
    }

    private val listeners = HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // a set's key: only that of the set this view reads it from; a plain key always
            val own = if (key == null) null else KeyboardProfiles.splitOwnKey(key)
            val plain = if (own == null) key
                else own.second.removePrefix(KeyboardProfiles.TOMBSTONE).takeIf { KeyboardProfiles.setOf(real, id(), it, ownSet, create = false) == own.first }
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

    /** The set [key] is read from here (null: plain; [KeyboardProfiles.setOf]). */
    fun setOf(key: String): Int? = KeyboardProfiles.setOf(real, id(), key, ownSet, create = false)

    /** Whether [key] written here is what other keyboards read too: app-wide, or the main keyboard's set as the common
     *  settings (separate settings off, its menu shared), not a keyboard's own. */
    fun writesShared(key: String): Boolean {
        val set = KeyboardProfiles.setOf(real, id(), key, ownSet, create = false) ?: return true
        val id = id()
        return set == KeyboardProfiles.mainId(real, create = false) && (id == KeyboardProfiles.SHARED || (!ownSet && !KeyboardProfiles.isSeparate(real))
            || KeyboardProfiles.isGlobal(key))
    }
}

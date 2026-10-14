// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import helium314.keyboard.latin.utils.getActivity
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.common.Constants.Separators

/**
 * Separate settings per keyboard. Every keyboard has its own set of settings under its id (KeyboardIds; owner
 * 2026-10-11: "do IDs now"), keys "p<id>/<key>" in the same preferences file; the plain keys are the app-wide ones only. There is no common set
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
    const val SHARED = "shared"

    private const val PREF_SEPARATE = "separate_settings_per_keyboard"
    // (the old map, definition -> set number, and its next number: read by the one-time upgrade only, KeyboardIdUpgrade)
    private const val OLD_IDS = KeyboardIdUpgrade.OLD_IDS
    private const val OLD_NEXT_ID = "keyboard_profile_next_id"
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
        Settings.PREF_LOG_SETTINGS, // and of the settings changes, the keyboards' history
        Settings.PREF_LEAVING_KEEPS_CHANGES, // how the settings screens are left: the app's, not a keyboard's
        PREF_SEPARATE, OLD_IDS, OLD_NEXT_ID, PREF_START_FROM, PREF_SHARED_FROM, // (all but the first: old bookkeeping)
        "key_popup_sets", // saved popup sets are meant to be reused across keyboards
        "appearance_looks", // saved looks too
        "layout_presets", // and saved Layouts
        helium314.keyboard.settings.SavedItemDefaults.PREF, // what saved items do with new defaults: one switch for all
        // a full restore that didn't finish: the device's, never backed up (owner 2026-10-12: "Yes add this")
        helium314.keyboard.settings.preferences.FullRestore.PREF_UNFINISHED,
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

    /** What a factory reset keeping the keyboards keeps of their sets (owner 2026-10-11, ticks mean keep): separate
     *  settings and the shared menus, so each keyboard reads its set as before (owner 2026-10-11: "yes, OK"); which set
     *  is whose is the keyboards' ids, in the list. */
    val setStructureKeys: Set<String> get() = setOf(PREF_SEPARATE) + Group.entries.map { it.prefKey }

    /** "keyboard_shared_from", the old record of where shared settings came from (before 2026-10-11): unread. */
    private const val PREF_SHARED_FROM = "keyboard_shared_from"

    // ---- the main keyboard (owner 2026-10-11: "Not the keyboard you pick, but the first keyboard in our list") ----

    /** The first keyboard in the list (the Keyboards screen's order): its own set is the common settings. The list is
     *  stored from the first start on (SubtypeSettings stores the default keyboard, owner 2026-10-11); found empty all
     *  the same (defensive, a read writes nothing): the keyboards loaded, else the default keyboard, else the selected. */
    fun mainKeyboard(real: SharedPreferences): SettingsSubtype {
        val enabled = real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!
        enabled.split(Separators.SETS).firstOrNull { it.isNotEmpty() }?.let { return it.toSettingsSubtype() }
        return runCatching { helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes(true).firstOrNull()?.toSettingsSubtype() }.getOrNull()
            ?: real.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype()
    }

    /** The id of [mainKeyboard], [NO_SET] when it has none (not given one yet by the net: a read gives out no set). */
    fun mainId(real: SharedPreferences): String {
        val enabled = real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)
        mainIdMemo?.let { if (it.selected == enabled && !enabled.isNullOrEmpty()) return it.id }
        val id = mainKeyboard(real).keyboardId ?: return NO_SET
        mainIdMemo = MainMemo(enabled ?: "", id)
        return id
    }

    /** No set: nothing is stored under it (a keyboard without an id, for a read); a write to it is dropped. */
    const val NO_SET = "none"
    // (every preference read asks: the list isn't parsed again while it didn't change)
    @Volatile private var mainIdMemo: MainMemo? = null

    fun isMain(real: SharedPreferences, keyboard: SettingsSubtype) = keyboard.sameKeyboard(mainKeyboard(real))

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
    // (the palettes: one app-wide list, as saved themes and Layouts; owner 2026-10-11: "palettes are one app-wide list".
    // A keyboard's light and dark colors name one; helium314.keyboard.settings.Palettes)
    private val globalPrefixes = listOf(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, "share_user_history_", LanguagePriority.PREF_ADDED_PREFIX, "debug_", "gesture_stats",
        Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX,
        // a language's dictionary choice, the same on every keyboard (2026-10-12, owner: "A lang will always have a single dict across keyboards")
        helium314.keyboard.latin.dictionary.DictionarySelection.PREF_PREFIX)

    /** A key of the app itself (never a keyboard's), whatever the menus' sharing: the keyboard list, the saved themes and
     *  Layouts, the logs' switches… (re-review 2026-10-07: what a shared menu holds for all keyboards is not app-wide,
     *  a saved Layout still carries it). */
    fun isAppWide(key: String) = key in globalKeys || globalPrefixes.any { key.startsWith(it) } || (key.startsWith(PREFIX) && key.contains(SEPARATOR))

    /** Stored once for all keyboards: [isAppWide], or on menus that are all shared. */
    fun isGlobal(key: String) = isAppWide(key) || sharedByGroups(key)

    fun isSeparate(real: SharedPreferences) = real.getBoolean(PREF_SEPARATE, false)

    /** A stored key of a keyboard's own set as its keyboard id and plain key; null for an app-wide one. */
    fun splitOwnKey(stored: String): Pair<String, String>? {
        if (!stored.startsWith(PREFIX)) return null
        val sep = stored.indexOf(SEPARATOR)
        if (sep < 0) return null
        val id = stored.substring(PREFIX.length, sep).takeIf { KeyboardIds.isId(it) } ?: return null
        return id to stored.substring(sep + 1)
    }

    /** [key] in the set of keyboard [id], whether or not the key is shared now (as stored, unlike [prefixedKey]). */
    fun ownKey(id: String, key: String) = "$PREFIX$id$SEPARATOR$key"

    /** A key stored plain, never in a set: [isAppWide], a one-time step's flag. */
    private fun isPlain(key: String) = isAppWide(key) || isOneTimeFlag(key)

    /**
     * The set [key] of set [id] is in: null for a plain key ([isPlain]); the main keyboard's own set ([mainId]) for the
     * common settings, i.e. [SHARED], every per-keyboard setting while separate settings is off (not with [ownSet]: a
     * keyboard's own set as it is), and a shared menu's settings; else [id]'s own.
     */
    fun setOf(real: SharedPreferences, id: String, key: String, ownSet: Boolean = false): String? {
        if (isPlain(key)) return null
        return if (id == SHARED || sharedByGroups(key) || (!ownSet && !isSeparate(real))) mainId(real) else id
    }

    /** [key] as stored for set [id] ([setOf]). */
    fun prefixedKey(real: SharedPreferences, id: String, key: String, ownSet: Boolean = false): String =
        setOf(real, id, key, ownSet)?.let { ownKey(it, key) } ?: key

    /** The key as the app knows it, or null if [key] belongs to another profile than [id]. */
    fun unprefixedKey(id: String, key: String): String? {
        val (keyId, plain) = splitOwnKey(key) ?: return key
        return if (keyId == id) plain else null
    }

    // ---- ids (the keyboards' own, in their definitions: KeyboardIds) ----

    /** [keyboard]'s id, the name of its set (null: a definition without one, a built-in keyboard). A lookup. */
    fun idOf(keyboard: SettingsSubtype): String? = keyboard.keyboardId

    /** The ids with a set of their own in [all] (stored preferences): entries under "p<id>/". */
    fun setIds(all: Map<String, *>): Set<String> = all.keys.mapNotNullTo(LinkedHashSet()) { splitOwnKey(it)?.first }

    /** Every stored copy of [key] (each keyboard's own, the main's for all; a plain one of before) that reads [old] becomes [new] (null: removed):
     *  a saved theme, Layout or popup set renamed or deleted is followed by every keyboard that chose it, not only the
     *  one being edited (review 2026-10-06 Low). */
    fun replaceValueEverywhere(real: SharedPreferences, key: String, old: String, new: String?) {
        val editor = real.edit()
        for ((stored, value) in real.all) {
            if (value != old) continue
            if (stored != key && splitOwnKey(stored)?.second != key) continue
            if (new == null) editor.remove(stored) else editor.putString(stored, new)
        }
        editor.apply()
    }

    /** The keyboards (in [keyboards]' order) that use a saved item through [keys], for the delete warnings (2026-10-08):
     *  what each reads now (separate settings off or the menu shared: the main keyboard's value), or what its own set
     *  keeps, hidden or not (nothing stored there: nothing chosen, not asked again). [matches] gets the plain key and the
     *  value (null: nothing chosen). Reads only. [edited]: the keyboard a settings screen edits and the set it edits
     *  ([editingId]), which wins over the keyboard's own id (the screen's set). */
    fun keyboardsUsing(real: SharedPreferences, keyboards: List<SettingsSubtype>, keys: Collection<String>,
                       edited: Pair<SettingsSubtype, String>? = null,
                       matches: (key: String, value: String?) -> Boolean): List<SettingsSubtype> {
        return keyboards.filter { keyboard ->
            val id = edited?.takeIf { it.first.sameKeyboard(keyboard) }?.second ?: keyboard.keyboardId ?: SHARED
            val now = ProfilePreferences(real) { id } // (separate settings off or a shared menu: the main's set)
            keys.any { key ->
                if (matches(key, now.getString(key, null))) return@any true
                if (id == SHARED) return@any false
                val own = ownKey(id, key)
                real.contains(own) && matches(key, real.getString(own, null))
            }
        }
    }

    /** The keyboards (in [keyboards]' order) whose own stored set names a saved item through [keys] ([matches] gets the
     *  plain key and the value), shown or hidden behind the common settings: the ones an update question asks
     *  (owner 2026-10-12: "our saving policies should not be affected by the common/separate toggle at all."). Each
     *  keyboard keeps its own set ("p<id>/<key>") with separate settings off and a menu shared too, so the same ones
     *  are asked in every mode. Not the set being edited ([editingId], and the set its writes go to, [setOf]: the main
     *  keyboard's for the common settings), nor [edited]; one that only reads the item through the common settings
     *  reads the edited set, no user of its own. A keyboard without an id has no set: never one. Reads only. */
    fun ownSetUsers(real: SharedPreferences, keyboards: List<SettingsSubtype>, keys: Collection<String>, editingId: String,
                    edited: SettingsSubtype?, matches: (key: String, value: String?) -> Boolean): List<SettingsSubtype> {
        val editedSets = keys.associateWith { setOf(real, editingId, it) }
        return keyboards.filter { keyboard ->
            val id = keyboard.keyboardId ?: return@filter false
            if (id == editingId || keyboard.sameKeyboard(edited)) return@filter false
            keys.any { key ->
                val own = ownKey(id, key)
                id != editedSets[key] && real.contains(own) && matches(key, real.getString(own, null))
            }
        }
    }

    /** Whether set [id] has anything stored of its own. */
    fun hasOwnEntries(real: SharedPreferences, id: String): Boolean = real.all.keys.any { it.startsWith("$PREFIX$id$SEPARATOR") }

    /** Whether [keyboard] is in the keyboard list as stored, by its id (a definition without one: by its definition). */
    fun isListed(real: SharedPreferences, keyboard: SettingsSubtype): Boolean =
        helium314.keyboard.latin.utils.SubtypeSettings.createSettingsSubtypes(
            real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!).any { it.sameKeyboard(keyboard) }

    /** Whether [keyboard] is one of the phone's: in the list, or switched off (in the added list only). */
    fun isOnPhone(real: SharedPreferences, keyboard: SettingsSubtype): Boolean =
        KeyboardIds.phoneKeyboards(real).any { it.sameKeyboard(keyboard) }

    /** [keyboard]'s set for a write a user asked for (a screen's tap, an update dialog): its id while it is one of the
     *  phone's keyboards ([isOnPhone]); null for a keyboard gone or without an id (nothing is written: no ghost set). */
    fun idForListed(real: SharedPreferences, keyboard: SettingsSubtype): String? =
        keyboard.keyboardId?.takeIf { isOnPhone(real, keyboard) }

    /**
     * The net (owner 2026-10-11: "Ok, A + net"): every keyboard in the list without an id gets one (KeyboardIds, before
     * the list is loaded), and without a pool one, at app start (App.onCreate) and when the list is loaded again or added
     * to (SubtypeSettings.reloadEnabledSubtypes, addEnabledSubtype). The only automatic creation: a keyboard not in the
     * list never gets records here, so a lookup's ghost can't appear. Not under this object's lock (the pools take
     * LearnedKeyboards' own). @return whether anything was created
     */
    fun ensureListedRecords(real: SharedPreferences): Boolean {
        val gaveIds = KeyboardIds.ensureListedIds(real)
        var created = gaveIds
        val listed = helium314.keyboard.latin.utils.SubtypeSettings.createSettingsSubtypes(
            real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!).distinct()
        for (keyboard in listed)
            if (helium314.keyboard.latin.personalization.LearnedKeyboards.ensurePool(real, keyboard)) created = true
        if (created) Log.i("KeyboardProfiles", "the net: records for listed keyboards without them")
        // (the keyboard in use may have had none: its own id now)
        if (gaveIds && isSeparate(real)) refreshImeId(real)
        return created
    }

    /** A keyboard was deleted: its set goes with it (and which learned words it had). */
    @Synchronized
    fun onKeyboardDeleted(real: SharedPreferences, keyboard: SettingsSubtype) {
        helium314.keyboard.latin.personalization.LearnedKeyboards.onKeyboardDeleted(real, keyboard)
        // apps that remembered it remember nothing now (review 2026-10-07: the entry stayed, naming a deleted keyboard)
        real.all.filter { (k, v) -> k.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX) && v.toString().toSettingsSubtype().sameKeyboard(keyboard) }
            .keys.takeIf { it.isNotEmpty() }?.let { gone -> real.edit().apply { gone.forEach { remove(it) } }.apply() }
        val id = keyboard.keyboardId ?: return
        val editor = real.edit()
        real.all.keys.filter { it.startsWith("$PREFIX$id$SEPARATOR") }.forEach { editor.remove(it) }
        editor.apply()
        deleteFiles(id) // its own pictures: no keyboard uses them any more
    }

    fun hasOwnSettings(real: SharedPreferences, keyboard: SettingsSubtype): Boolean =
        keyboard.keyboardId?.let { hasOwnEntries(real, it) } == true

    /** The set of keyboard id [from] becomes [to]'s (a restore gave the phone's keyboard a new id, KeyboardIds.giveNewId):
     *  its entries and its pictures move; what [to] had goes. */
    @Synchronized
    fun moveSet(real: SharedPreferences, from: String, to: String) {
        if (from == to) return
        val all = real.all
        val editor = real.edit()
        all.keys.filter { it.startsWith("$PREFIX$to$SEPARATOR") }.forEach { editor.remove(it) }
        for ((stored, value) in all) {
            val (id, plain) = splitOwnKey(stored) ?: continue
            if (id != from) continue
            editor.remove(stored)
            put(editor, ownKey(to, plain), value)
        }
        editor.commit()
        filesDir?.let { moveFiles(it, from, to) }
    }

    // ---- copying sets ----

    /** Set [toId] becomes what set [fromId] reads as its own: its entries, a shared menu's from the main keyboard's set,
     *  its pictures; [SHARED] is the main's. What [toId] had goes. */
    @Synchronized
    fun copy(real: SharedPreferences, fromId: String, toId: String) {
        val from = if (fromId == SHARED) mainId(real) else fromId
        val to = if (toId == SHARED) mainId(real) else toId
        if (from == to) return
        val all = real.all
        val values = HashMap<String, Any?>() // (plain keys, as stored in the set)
        for ((stored, value) in all) {
            val (set, plain) = splitOwnKey(stored) ?: continue
            if (isAppWide(plain)) continue
            // (a one-time step's flag: the source set's own, as its steps ran on it)
            val source = if (isOneTimeFlag(plain)) from else setOf(real, from, plain, ownSet = true) ?: continue
            if (set == source) values[plain] = value
        }
        val editor = real.edit()
        all.keys.filter { it.startsWith("$PREFIX$to$SEPARATOR") }.forEach { editor.remove(it) }
        for ((raw, value) in values) put(editor, ownKey(to, raw), value)
        editor.commit()
        copyFiles(if (Group.APPEARANCE in sharedGroups) mainId(real) else from, to)
        Log.i("KeyboardProfiles", "copied settings $from -> $to")
    }

    // one-time upgrade flags ("fonts_follow_migrated", "defaults_feedback_on_done"): bookkeeping
    private val upgradeFlag = Regex(".*(_migrated|_done)")
    private val oneTimeFlags = setOf("learning_swiping_global", "suggestions_refine_moved", "moved_markers_removed")

    /** A one-time step's flag (an upgrade or a settings move, done once): bookkeeping, not a setting; a keyboard's own
     *  copy (p<id>/…) doesn't count. */
    fun isOneTimeFlag(key: String) = splitOwnKey(key) == null && (upgradeFlag.matches(key) || key in oneTimeFlags)

    /** Set [id] at every default: nothing of its own (a keyboard added with "every default"). */
    @Synchronized
    fun clearSet(real: SharedPreferences, id: String) {
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
     * or hidden or separate"). Never another set's keys. Its one-time steps' flags stay the phone's. A key [takes] that
     * [settings] lacks is removed: nothing stored, the default (owner 2026-10-11: no default marks).
     */
    @Synchronized
    fun restoreSet(real: SharedPreferences, id: String, settings: Map<String, Any?>, takes: (String) -> Boolean = { true }) {
        if (id == SHARED || id == NO_SET) return
        val editor = real.edit()
        for (stored in real.all.keys) {
            val plain = splitOwnKey(stored)?.takeIf { it.first == id }?.second ?: continue
            if (!isPlain(plain) && takes(plain)) editor.remove(stored)
        }
        for ((key, value) in settings) if (!isPlain(key) && takes(key)) put(editor, ownKey(id, key), value)
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

    /** [keyboard]'s own set inside a backed-up preference map (or the phone's), plain keys, by its id (a backup is
     *  given ids when it is read, KeyboardIdUpgrade); null for a keyboard without one. An older backup's default marks
     *  ([TOMBSTONE]) are left out: nothing stored (owner 2026-10-11: no default marks). */
    fun ownSettingsIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?>? {
        val id = keyboard.keyboardId ?: return null
        val start = "$PREFIX$id$SEPARATOR"
        return backup.filterKeys { it.startsWith(start) }.mapKeys { it.key.substring(start.length) }
            .filterKeys { !isPlain(it) && !it.startsWith(TOMBSTONE) }
    }

    /** What [keyboard]'s own set holds in a backed-up preference map (or the phone's): plain keys, shared
     *  menus' values too (hidden while shared: what it reads once they are per keyboard). An old backup's common set
     *  (its plain keys) is ignored (owner 2026-10-11: "fine to trash common set with upgrade and restore"). */
    fun effectiveSettingsIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?> =
        ownSettingsIn(backup, keyboard).orEmpty()

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
    const val START_DEFAULTS = "defaults"

    /** Keyboard set [id] starts from set [from] ([SHARED]: the main keyboard's; [START_DEFAULTS]: every default), at once,
     *  whether settings are separate or not (owner 2026-10-11: every keyboard always has its own set); [noPopups]: not
     *  the source's popups, which are for another script's letters. */
    fun startKeyboardFrom(real: SharedPreferences, id: String, from: String, noPopups: Boolean) {
        if (from == START_DEFAULTS) clearSet(real, id) else copy(real, from, id)
        real.edit().apply {
            remove(PREF_START_FROM)
            if (noPopups) listOf("key_popups", "key_popup_set_selected", Settings.PREF_SYMBOL_POPUP_MAP)
                .forEach { remove(ownKey(id, it)) }
        }.commit()
    }

    /** Turn separate settings off: every keyboard reads the main keyboard's set ([mainKeyboard]); nothing is copied. */
    @Synchronized
    fun disable(real: SharedPreferences) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("separate settings OFF, the main keyboard's: ${mainKeyboard(real).toPref()}")
        real.edit().putBoolean(PREF_SEPARATE, false).apply()
    }

    /** Profile of the keyboard in use (the IME's view). Refreshed on every keyboard switch. */
    @Volatile var imeId: String = SHARED
        @androidx.annotation.VisibleForTesting internal set // (tests: the keyboard on screen)

    /** The set the settings screen of [context] edits: the keyboard of the screen's own navigation entry
     *  ([KeyboardScopeContext]), else [mainEditingId]. No global that screens set (2026-10-09: "who drew last wins" left
     *  a screen editing another keyboard during a slide). Navigation keeps the entry's keyboard over process death. */
    fun editingId(context: android.content.Context): String {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)
        return KeyboardScopeContext.idOf(context)?.let { scopedId(real, it) } ?: mainEditingId(real)
    }

    /** Where no keyboard's own screen is (the main screen, App settings, their search): the keyboard in use when settings
     *  are separate, else the common settings ([SHARED]: the main keyboard's set). Worked out on every call, never stored. */
    fun mainEditingId(real: SharedPreferences): String {
        if (!isSeparate(real)) return SHARED
        val selected = real.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!
        mainMemo?.let { if (it.selected == selected) return it.id }
        // its id, a lookup (owner 2026-10-11: lookups never create records); none yet (the net gives every listed keyboard
        // one): no set, reads give defaults, writes are dropped
        val id = selected.toSettingsSubtype().keyboardId ?: return NO_SET
        mainMemo = MainMemo(selected, id)
        return id
    }
    // (every preference read asks: the selected keyboard isn't parsed again while it didn't change)
    private class MainMemo(val selected: String, val id: String)

    @Volatile private var mainMemo: MainMemo? = null

    /** Set [id] of a keyboard's screen while one of the phone's keyboards has it (listed or switched off); one that is
     *  gone (the keyboard deleted, a factory reset or a restore) gives [mainEditingId], so nothing is written under an id
     *  no keyboard has. */
    fun scopedId(real: SharedPreferences, id: String): String {
        if (!isSeparate(real)) return SHARED
        if (id in phoneIds(real)) return id
        if (goneLogged.add(id)) Log.i("KeyboardProfiles", "set $id is gone: its screens edit the main set")
        return mainEditingId(real)
    }

    /** The ids of the phone's keyboards as stored ([KeyboardIds.phoneKeyboards]: listed or switched off). Every write
     *  through a [ProfilePreferences] view asks too (one for a set of another id is dropped, 2026-10-12), so the lists
     *  aren't parsed again while they didn't change. */
    fun phoneIds(real: SharedPreferences): Set<String> {
        val enabled = real.getString(Settings.PREF_ENABLED_SUBTYPES, null)
        val added = real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, null)
        knownMemo?.let { if (it.enabled == enabled && it.added == added) return it.ids }
        val ids = KeyboardIds.phoneKeyboards(enabled, added).mapNotNullTo(HashSet()) { it.keyboardId }
        knownMemo = KnownMemo(enabled, added, ids)
        return ids
    }

    private class KnownMemo(val enabled: String?, val added: String?, val ids: Set<String>)
    @Volatile private var knownMemo: KnownMemo? = null
    private val goneLogged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    // (a write dropped for a set no keyboard of the phone has: logged once per set, ProfilePreferences)
    internal val droppedLogged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether the look (Appearance settings and background pictures) of set [a] differs from set [b]'s: a keyboard
     *  switch reloads the theme only then (re-review 2026-10-07: it reloaded, with a blink, on every switch). Shared
     *  Appearance: never. */
    fun looksDiffer(real: SharedPreferences, a: String, b: String): Boolean {
        if (a == b || !isSeparate(real) || Group.APPEARANCE in sharedGroups) return false
        fun look(id: String): Map<String, Any?> = ProfilePreferences(real) { id }.all.filterKeys { helium314.keyboard.settings.AppearanceLooks.onScreen(it) }
        if (look(a) != look(b)) return true
        val dir = filesDir ?: return false
        fun pictures(id: String) = profileFileNames.map { name -> java.io.File(dir, name + suffix(id)).let { if (it.isFile) it.length() to it.lastModified() else null } }
        return pictures(a) != pictures(b)
    }

    fun refreshImeId(real: SharedPreferences) {
        loadGroups(real) // (a restore can bring other group choices)
        // the learned words of the keyboard in use too, when each keyboard has its own
        helium314.keyboard.latin.personalization.LearnedStores.refresh(real)
        val old = imeId
        // (its id, a lookup, owner 2026-10-11; a rename keeps it; none yet: no set, the net gives a listed keyboard its own)
        imeId = if (!isSeparate(real)) SHARED else selectedKeyboard(real).keyboardId ?: NO_SET
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

    /** The old default mark, "p<id>/~<key>" (before 2026-10-11: "at its default, not the common set's value"). None is
     *  written any more (owner 2026-10-11: no default marks; nothing stored = the default). Still known to the steps
     *  that run on data from before ([removeDefaultMarks] and the older steps before it) and to an older backup's read. */
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
        return java.io.File(dir, name + suffix(if (id == SHARED) mainId(real) else id))
    }

    /** "_p<id>"; the plain names ([SHARED]) are the old common set's pictures, read only by the steps of before. */
    private fun suffix(id: String) = if (id == SHARED) "" else "_p$id"

    /** Set [id]'s background picture files, each followed by its framing file (whether they exist or not); [SHARED]:
     *  the main keyboard's. */
    fun pictureFiles(context: android.content.Context, id: String): List<java.io.File> {
        val dir = helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(context)
        val set = if (id == SHARED) mainId(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)) else id
        return profileFileNames.flatMap { name -> java.io.File(dir, name + suffix(set)).let {
            listOf(it, helium314.keyboard.latin.common.PictureFraming.fileFor(it)) } }
    }

    /** The files of set [fromId] over those of [toId] (one missing in [fromId] is removed in [toId]). */
    private fun copyFiles(fromId: String, toId: String) {
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) {
            val from = java.io.File(dir, name + suffix(fromId) + ext)
            val to = java.io.File(dir, name + suffix(toId) + ext)
            runCatching { if (from.exists()) from.copyTo(to, overwrite = true) else to.delete() }
        }
    }

    private fun hasOwnFiles(id: String): Boolean {
        val dir = filesDir ?: return false
        return profileFileNames.any { java.io.File(dir, it + suffix(id)).exists() }
    }

    /** The files of set [id] (not the shared set's). */
    fun deleteFiles(id: String) {
        if (id == SHARED) return
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) java.io.File(dir, name + suffix(id) + ext).delete()
    }

    /** Every keyboard's own picture files and the old common set's (a factory reset of the settings). */
    fun deleteAllFiles() {
        val dir = filesDir ?: return
        dir.listFiles { f -> profileFileNames.any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    /** Set [from]'s pictures (with their framing) in [dir] become set [to]'s ([from]: an id, or an old set number as
     *  text, KeyboardIdUpgrade); what [to] had goes. */
    fun moveFiles(dir: java.io.File, from: String, to: String) {
        if (from == to) return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) {
            val source = java.io.File(dir, name + suffix(from) + ext)
            val target = java.io.File(dir, name + suffix(to) + ext)
            runCatching { if (source.exists()) { source.copyTo(target, overwrite = true); source.delete() } else target.delete() }
        }
    }

    /** The backup file names [restoreFiles] can use for set [fromId] (its own, and the plain ones). */
    fun restoreFileNames(fromId: String): Set<String> =
        profileFileNames.flatMap { n -> listOf("", ".framing").flatMap { e -> listOf(n + suffix(fromId) + e, n + e) } }.toSet()

    /** Backup restore of one keyboard: its pictures from the backup's files (named for the backup's id [fromId]; null:
     *  the backup has no set for it), given as name -> bytes, become set [toId]'s. */
    fun restoreFiles(files: Map<String, ByteArray>, fromId: String?, toId: String, perKeyboardBackup: Boolean) {
        val dir = filesDir ?: return
        // a backup made since the pictures are per keyboard ([perKeyboardBackup]: it has "profile_files_migrated") says
        // exactly what the keyboard had, a missing file = no picture (no set of its own: none, the backup's common
        // pictures are ignored, as its common settings); an older one has only the plain pictures (what every keyboard
        // showed then), and without any the phone's are kept
        val source = if (perKeyboardBackup) fromId ?: NO_SET else SHARED
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
    private fun SharedPreferences.Editor.takeOwn(all: Map<String, *>, inUse: String, key: String) {
        if (inUse == SHARED || inUse == NO_SET) return
        when (val v = all["$PREFIX$inUse$SEPARATOR$key"]) {
            is Boolean -> putBoolean(key, v)
            is Int -> putInt(key, v)
            is Long -> putLong(key, v)
            is Float -> putFloat(key, v)
            is String -> putString(key, v)
            else -> if (all.containsKey("$PREFIX$inUse$SEPARATOR$TOMBSTONE$key")) remove(key)
        }
    }

    /** The one-time settings moves (each checks its own flag): at app start, and right after a restore, whose backup may
     *  be from before them (re-review 2026-10-07: they ran at the next start and overwrote changes made in between). */
    fun settingsMoves(real: SharedPreferences) {
        migrateLearningSwiping(real)
        migrateSuggestionsToRefine(real)
        splitLearningFromRefine(real)
        forgetSetting(real, "gesture_space_aware")
    }

    /** Once: the settings that became the same for every keyboard ([learningSwipingKeys]) take the values of the keyboard
     *  in use (with separate settings), and the keyboards' own copies go. */
    fun migrateLearningSwiping(real: SharedPreferences) {
        if (real.getBoolean("learning_swiping_global", false)) return
        val inUse = if (isSeparate(real)) selectedKeyboard(real).keyboardId ?: NO_SET else SHARED
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
        val inUse = if (isSeparate(real)) selectedKeyboard(real).keyboardId ?: NO_SET else SHARED
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
        for (id in setIds(real.all)) {
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
     * copies of the common color palettes either (owner 2026-10-11: deleted with the common set). An Add keyboard pick
     * kept for later ("keyboard_start_from", before 2026-10-11) is dropped, not applied (owner 2026-10-11: "don't care about keyboards before this update really").
     * App-wide keys and one-time flags stay.
     */
    @Synchronized
    fun dropCommonSet(real: SharedPreferences, fromCommon: Boolean) {
        if (real.getBoolean(NO_COMMON_SET_DONE, false)) return
        val keyboards = helium314.keyboard.latin.utils.SubtypeSettings.createSettingsSubtypes(
            real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!).ifEmpty { listOf(mainKeyboard(real)) }
        val without = keyboards.filter { !hasOwnSettings(real, it) }.mapNotNull { it.keyboardId }
        val main = mainId(real)
        val all = real.all
        // (one-time flags too, as a copy always took them: a set's own steps mustn't run again on it)
        // (the palettes too: app-wide since 2026-10-11, but plain ones here are the common set's of before, copied and
        // deleted as before; Palettes.centralize, after this, moves the sets' copies into the one list)
        val common = all.filterKeys { splitOwnKey(it) == null && (!isAppWide(it) || isPaletteKey(it)) && !it.startsWith(TOMBSTONE) }
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

    /** Set once [removeDefaultMarks] ran. */
    const val NO_DEFAULT_MARKS_DONE = "no_default_marks_done"

    /** Once (owner 2026-10-11, "go now with the cleanup"): every keyboard's default mark ("p<id>/~<key>") goes. With no
     *  common set and no fallback read a mark meant just what nothing stored means: the default. After [dropCommonSet]
     *  and the palettes' move, the last steps that read marks; a restored backup from before has the flag not set and
     *  runs it again. */
    @Synchronized
    fun removeDefaultMarks(real: SharedPreferences) {
        if (real.getBoolean(NO_DEFAULT_MARKS_DONE, false)) return
        val marks = real.all.keys.filter { splitOwnKey(it)?.second?.startsWith(TOMBSTONE) == true }
        real.edit().apply { marks.forEach { remove(it) } }.putBoolean(NO_DEFAULT_MARKS_DONE, true).commit()
        if (marks.isNotEmpty()) Log.i("KeyboardProfiles", "default marks removed: ${marks.size}")
    }

    private fun isPaletteKey(key: String) = key.startsWith(Settings.PREF_USER_COLORS_PREFIX)
        || key.startsWith(Settings.PREF_USER_ALL_COLORS_PREFIX) || key.startsWith(Settings.PREF_USER_MORE_COLORS_PREFIX)

    /** The keyboard whose settings set [id] holds (separate settings on): the phone's keyboard with that id, as it is
     *  now; or null (shared settings, or none has it). */
    fun editingKeyboard(real: SharedPreferences, id: String): SettingsSubtype? {
        if (id == SHARED || !isSeparate(real)) return null
        return KeyboardIds.phoneKeyboards(real).firstOrNull { it.keyboardId == id }
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
                         private val activeId: () -> String) : SharedPreferences {
    constructor(real: SharedPreferences, activeId: () -> String) : this(real, false, activeId)

    private fun id() = activeId()
    // (a read gives out no set id: a main keyboard without one yet reads nothing)
    private fun k(key: String) = KeyboardProfiles.prefixedKey(real, id(), key, ownSet)

    override fun getAll(): MutableMap<String, *> {
        // what the reads give: the plain app-wide keys, and for every other key the set it is read from
        val id = id()
        val result = HashMap<String, Any?>()
        for ((key, value) in real.all) {
            val own = KeyboardProfiles.splitOwnKey(key)
            if (own == null) {
                if (KeyboardProfiles.setOf(real, id, key, ownSet) == null) result[key] = value
                continue
            }
            val (set, plain) = own
            if (KeyboardProfiles.setOf(real, id, plain, ownSet) == set) result[plain] = value
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

    private inner class Editor(private val e: SharedPreferences.Editor, private val id: String) : SharedPreferences.Editor {
        private fun k(key: String) = KeyboardProfiles.prefixedKey(real, id, key, ownSet)
        // (a keyboard without a set: nothing written, owner 2026-10-11: lookups never create records)
        // (nor a set no keyboard of the phone has, listed or switched off, 2026-10-12 U9: during a whole restore the
        // keyboard in use, typing in another app, wrote its emoji recents and toolbar state to its id of before the
        // backup, or with separate settings off to the main keyboard of the list in memory; a backup without that
        // keyboard left "p<id>/" records of no keyboard, a ghost: its id taken, carried by later backups. The common
        // settings' set is the first listed keyboard's, so it is one; restoreSet, put and the copies write the stored
        // file itself, never through here)
        private fun noSet(key: String): Boolean {
            val set = KeyboardProfiles.setOf(real, id, key, ownSet) ?: return false
            if (set == KeyboardProfiles.NO_SET) {
                Log.w("ProfilePreferences", "no set of its own: $key not written")
                return true
            }
            if (set in KeyboardProfiles.phoneIds(real)) return false
            if (KeyboardProfiles.droppedLogged.add(set)) Log.i("ProfilePreferences", "set $set is no keyboard's: its writes are dropped")
            return true
        }
        override fun putString(key: String, value: String?) = apply { if (!noSet(key)) { e.putString(k(key), value) } }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { if (!noSet(key)) { e.putStringSet(k(key), values) } }
        override fun putInt(key: String, value: Int) = apply { if (!noSet(key)) { e.putInt(k(key), value) } }
        override fun putLong(key: String, value: Long) = apply { if (!noSet(key)) { e.putLong(k(key), value) } }
        override fun putFloat(key: String, value: Float) = apply { if (!noSet(key)) { e.putFloat(k(key), value) } }
        override fun putBoolean(key: String, value: Boolean) = apply { if (!noSet(key)) { e.putBoolean(k(key), value) } }
        // (just removed: nothing stored is the default; owner 2026-10-11, no default marks)
        override fun remove(key: String) = apply { if (!noSet(key)) e.remove(k(key)) }
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
                else own.second.takeIf { KeyboardProfiles.setOf(real, id(), it, ownSet) == own.first }
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
    fun setOf(key: String): String? = KeyboardProfiles.setOf(real, id(), key, ownSet)

    /** Whether [key] written here is what other keyboards read too: app-wide, or the main keyboard's set as the common
     *  settings (separate settings off, its menu shared), not a keyboard's own. */
    fun writesShared(key: String): Boolean {
        val set = KeyboardProfiles.setOf(real, id(), key, ownSet) ?: return true
        val id = id()
        return set == KeyboardProfiles.mainId(real) && (id == KeyboardProfiles.SHARED || (!ownSet && !KeyboardProfiles.isSeparate(real))
            || KeyboardProfiles.isGlobal(key))
    }
}

/**
 * Keyboard set [id] exactly as stored: every key of a set is "p<id>/<key>" ([KeyboardProfiles.ownKey]), whatever
 * separate settings and the menus' sharing say (a [ProfilePreferences] view reads and writes the main keyboard's set
 * there); app-wide keys plain. What an update or a kept old version of a saved item writes on another keyboard: its own
 * set, shown or hidden behind the common settings (owner 2026-10-12: "our saving policies should not be affected by the
 * common/separate toggle at all."). Listeners get the keys as stored.
 */
class OwnSetPreferences(private val real: SharedPreferences, private val id: String) : SharedPreferences {
    // (a plain key: [KeyboardProfiles.setOf] names no set for it, whatever the id)
    private fun k(key: String) = if (KeyboardProfiles.setOf(real, id, key) == null) key else KeyboardProfiles.ownKey(id, key)

    override fun getAll(): MutableMap<String, *> {
        val result = HashMap<String, Any?>()
        for ((key, value) in real.all) {
            val own = KeyboardProfiles.splitOwnKey(key)
            if (own == null) { if (KeyboardProfiles.setOf(real, id, key) == null) result[key] = value }
            else if (own.first == id) result[own.second] = value
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

    override fun edit(): SharedPreferences.Editor = Editor(real.edit())

    private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { e.putString(k(key), value) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { e.putStringSet(k(key), values) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(k(key), value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(k(key), value) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(k(key), value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(k(key), value) }
        // (just removed: nothing stored is the default; owner 2026-10-11, no default marks)
        override fun remove(key: String) = apply { e.remove(k(key)) }
        // (this set's own entries only, never the whole file)
        override fun clear() = apply { real.all.keys.filter { KeyboardProfiles.splitOwnKey(it)?.first == id }.forEach { e.remove(it) } }
        override fun commit() = e.commit()
        override fun apply() = e.apply()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        real.registerOnSharedPreferenceChangeListener(listener)
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        real.unregisterOnSharedPreferenceChangeListener(listener)
}

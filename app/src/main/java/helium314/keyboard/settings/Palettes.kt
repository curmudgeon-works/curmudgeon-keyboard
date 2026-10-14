// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.settings.preferences.RestorePlan

/**
 * The user's palettes: one app-wide list, like the saved themes and Layouts (owner 2026-10-11: "palettes are one
 * app-wide list"). Stored plain, app-wide ([KeyboardProfiles.isAppWide]), in the color formats of before: [prefixes]
 * + name. A keyboard's light and dark colors ([slots], in its set) and a saved theme name one; nobody holds a copy
 * (owner: "agree with your suggestion", themes name palettes). The built-in palettes are code: an app update changing
 * one reaches every keyboard and theme naming it, nothing of them is stored.
 * Everything here is done at once; the open Appearance screen's Discard undoes it everywhere (its snapshot holds the
 * list, every set's color choices and the saved themes; owner 2026-10-11: "everything shuld be held for save/discard").
 */
object Palettes {
    val prefixes = listOf(Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX)
    /** A keyboard's (and a saved theme's) light and dark colors: a palette's name. */
    val slots = listOf(Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT)

    fun isKey(key: String) = prefixes.any { key.startsWith(it) }
    fun keys(name: String) = prefixes.map { it + name }
    private fun nameOf(key: String): String? = prefixes.firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) }

    /** Palette [name]'s colors in [values] (plain keys), prefix -> value; "main colors" (the default of more colors)
     *  left out, so two palettes compare by what they show. */
    fun colorsIn(values: Map<String, *>, name: String): Map<String, Any?> =
        prefixes.mapNotNull { p -> values[p + name]?.let { p to it } }
            .filter { (p, v) -> p != Settings.PREF_USER_MORE_COLORS_PREFIX || v != Defaults.PREF_USER_MORE_COLORS }.toMap()

    /** The list in [values] (stored keys: the plain ones), name -> colors. */
    fun list(values: Map<String, *>): Map<String, Map<String, Any?>> =
        values.keys.mapNotNullTo(sortedSetOf<String>()) { if (KeyboardProfiles.splitOwnKey(it) == null) nameOf(it) else null }
            .associateWith { colorsIn(values, it) }

    private fun put(editor: SharedPreferences.Editor, name: String, colors: Map<String, Any?>) {
        for ((prefix, value) in colors) KeyboardProfiles.put(editor, prefix + name, value)
    }

    /** The saved themes naming one of [names] (light or dark colors). */
    fun themesUsing(real: SharedPreferences, names: Collection<String>): List<String> =
        AppearanceLooks.load(real).filter { look -> slots.any { look.values[it] in names } }.map { it.name }

    /** [look] with each slot naming [from] naming [to] (null: at its default, nothing remembered: the app's default
     *  colors whatever "Saved items take new defaults" says). */
    private fun withSlots(look: AppearanceLooks.Look, from: String, to: String?): AppearanceLooks.Look {
        val naming = slots.filter { look.values[it] == from }
        if (naming.isEmpty()) return look
        return AppearanceLooks.Look(look.name, look.values + naming.associateWith { to },
            if (to == null) look.remembered - naming.toSet() else look.remembered)
    }

    private fun replaceInThemes(real: SharedPreferences, from: String, to: String?) {
        val looks = AppearanceLooks.load(real)
        val changed = looks.map { withSlots(it, from, to) }
        if (changed.zip(looks).any { (a, b) -> a !== b }) AppearanceLooks.save(real, changed, now = true)
    }

    // ---- edit, delete, rename ----

    /** What another keyboard or saved theme using an edited palette takes (owner 2026-10-11: "use the new edited; use
     *  app default; use 'Warm N', not 'Custom N'"). */
    enum class Choice { NEW, DEFAULT, KEEP_OLD }

    /** The name the keepers' copy of [name]'s old colors gets: the next free one counting up, "Warm 2" (owner
     *  2026-10-11: "keep the existing version called Warm 2"; [RestorePlan.nextFreeName]). */
    fun keeperName(real: SharedPreferences, name: String): String =
        RestorePlan.nextFreeName(name, list(real.all).keys + name)

    /**
     * Palette [name] was saved with new colors in the palette editor; the keyboard edited takes them. [old]: its
     * colors when the editor opened (prefix -> value). Each other keyboard ([keyboards]) and saved theme ([themes])
     * using it takes what was chosen for it, in every slot naming it (owner 2026-10-11: "one palette, edits reach every
     * slot using it"): the new colors (nothing to do), the app's default colors, or the old colors as one new palette
     * on the list shared by all keepers ([keeperName]); none keeping them, no such palette is made (owner 2026-10-11:
     * "Would this still cause Warm 2 to be created if no users exist for it?": no).
     * @return the keepers' palette, null when nobody keeps the old colors
     */
    fun afterEdit(real: SharedPreferences, name: String, old: Map<String, Any?>,
                  keyboards: Map<SettingsSubtype, Choice>, themes: Map<String, Choice>): String? {
        val keeper = if (Choice.KEEP_OLD in keyboards.values || Choice.KEEP_OLD in themes.values) keeperName(real, name) else null
        val editor = real.edit()
        if (keeper != null) put(editor, keeper, old)
        for ((keyboard, choice) in keyboards) {
            if (choice == Choice.NEW) continue
            // (owner 2026-10-11: lookups never create records; a listed one gets its set from the net)
            val id = KeyboardProfiles.idForListed(real, keyboard) ?: continue
            for (slot in slots) {
                // (its own set's choice first: with one set of settings the view reads the main keyboard's, and a
                // switched-off keyboard is asked by its own choice; owner 2026-10-11)
                val own = KeyboardProfiles.ownKey(id, slot)
                val chosen = if (real.contains(own)) real.getString(own, null) else ProfilePreferences(real) { id }.getString(slot, null)
                if (chosen != name) continue
                // (the default: nothing stored, owner 2026-10-11: no default marks)
                if (choice == Choice.KEEP_OLD) editor.putString(own, keeper) else editor.remove(own)
            }
        }
        editor.commit()
        if (themes.values.any { it != Choice.NEW }) AppearanceLooks.save(real, AppearanceLooks.load(real).map { look ->
            when (themes[look.name]) {
                Choice.KEEP_OLD -> withSlots(look, name, keeper)
                Choice.DEFAULT -> withSlots(look, name, null)
                else -> look
            }
        }, now = true)
        helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
        return keeper
    }

    /**
     * Deletes palette [name] (owner 2026-10-11: "Move all to default, Or keep all on Warm and move this to default").
     * [onlyHere] false: every keyboard (each set, switched-off and hidden ones too) and saved theme naming it goes to
     * the app's default colors, and it leaves the list; nobody else using it, this is the plain delete. True: only the
     * keyboard edited ([prefs], its view) goes to the default colors, the palette stays for the others.
     */
    fun delete(real: SharedPreferences, prefs: SharedPreferences, name: String, onlyHere: Boolean) {
        if (onlyHere) {
            prefs.edit().apply { for (slot in slots) if (prefs.getString(slot, null) == name) remove(slot) }.commit()
        } else {
            real.edit().apply { for (key in keys(name)) remove(key) }.commit()
            for (slot in slots) KeyboardProfiles.replaceValueEverywhere(real, slot, name, null)
            replaceInThemes(real, name, null)
        }
        helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    /** Palette [from] is called [to]: every keyboard (each set, hidden ones too) and saved theme naming it follows
     *  (owner 2026-10-11: "Rename makes sense"). False: [to] blank or another palette's. */
    fun rename(real: SharedPreferences, from: String, to: String): Boolean {
        if (to.isBlank()) return false
        if (to == from) return true
        val all = real.all
        if (prefixes.any { all.containsKey(it + to) }) return false
        real.edit().apply {
            for (prefix in prefixes) all[prefix + from]?.let { KeyboardProfiles.put(this, prefix + to, it); remove(prefix + from) }
        }.commit()
        for (slot in slots) KeyboardProfiles.replaceValueEverywhere(real, slot, from, to)
        replaceInThemes(real, from, to)
        return true
    }

    // ---- into the list: the one-time move, a restore ----

    /** Set once [centralize] ran. */
    const val CENTRAL_DONE = "central_palettes_done"
    /** The kept copies of before ("Custom N", off the list; until 2026-10-11). */
    private const val OLD_KEPT = "~custom:"
    /** What an old kept copy is called on the list: "Palette N" (owner 2026-10-11 brief). */
    private const val KEPT_BASE = "Palette"

    private fun listName(name: String) =
        if (name.startsWith(OLD_KEPT)) "$KEPT_BASE ${name.removePrefix(OLD_KEPT).toIntOrNull() ?: 1}" else name

    /**
     * Where palette [name] with [colors] lands in [list] (added to it in place): [byContent], the one with these colors
     * whatever its name (a restore, as saved themes); else the one of that name, or one numbered from it ([origins]),
     * with these colors. None: [name] when free (and not [reserved]), else the next free number counting up, "Warm 2".
     * An old kept copy "~custom:N" is "Palette N".
     */
    private fun land(list: MutableMap<String, Map<String, Any?>>, origins: MutableMap<String, MutableSet<String>>, name: String,
                     colors: Map<String, Any?>, byContent: Boolean, reserved: Set<String> = emptySet()): String {
        val base = listName(name)
        val match = if (byContent) list.entries.firstOrNull { it.value == colors }?.key
            else (listOf(base) + origins[base].orEmpty()).firstOrNull { it in list && list[it] == colors }
        if (match != null) return match
        val landed = RestorePlan.nextFreeName(base, list.keys + reserved)
        list[landed] = colors
        origins.getOrPut(base) { HashSet() } += landed
        return landed
    }

    /** [values] (a keyboard's settings or a saved theme's, plain keys) without palette colors, its slots naming
     *  palettes as [renames] says. */
    fun renamedIn(values: Map<String, Any?>, renames: Map<String, String>): Map<String, Any?> =
        values.filterKeys { !isKey(it) }.mapValues { (key, value) -> if (key in slots && value is String) renames[value] ?: value else value }

    /**
     * Once (owner 2026-10-11: "Backward one-time move into the list: minimal; same name other colors → numbered"): the
     * palettes in the keyboards' sets ("p<id>/user_*colors_*") and in the saved themes move into the list, in this
     * order: the list as it is, the sets by id, the themes as listed. Same name and colors: one; same name, other
     * colors: the later one numbered ("Warm 2"), and the set or theme naming it names that; an old kept copy
     * "~custom:N": "Palette N" (or the next free number), its users following. The copies go (their old marks with
     * [KeyboardProfiles.removeDefaultMarks], after this). After
     * [KeyboardProfiles.dropCommonSet] (the common set's palettes are deleted there, owner: "fine to delete due to our
     * early stages"). Run again (stopped half way): what moved already is found by name and colors.
     */
    fun centralize(real: SharedPreferences) {
        if (real.getBoolean(CENTRAL_DONE, false)) return
        val all = real.all
        val before = list(all)
        val list = LinkedHashMap(before)
        val origins = HashMap<String, MutableSet<String>>()
        // where each name landed first: for a slot naming an old kept copy its own set doesn't hold
        val first = HashMap<String, String>()
        fun landed(name: String, colors: Map<String, Any?>) = land(list, origins, name, colors, byContent = false).also { if (name !in first) first[name] = it }
        val sets = java.util.TreeMap<Int, HashMap<String, Any?>>() // set id -> its stored entries, plain keys
        for ((stored, value) in all) {
            val (id, key) = KeyboardProfiles.splitOwnKey(stored) ?: continue
            sets.getOrPut(id) { HashMap() }[key] = value
        }
        val editor = real.edit()
        val setRenames = sets.mapValues { (_, own) ->
            own.keys.mapNotNullTo(sortedSetOf<String>()) { nameOf(it) }.associateWith { landed(it, colorsIn(own, it)) } }
        val looks = AppearanceLooks.load(real)
        val lookRenames = looks.map { look -> look.values.keys.mapNotNullTo(sortedSetOf<String>()) { nameOf(it) }.associateWith { landed(it, colorsIn(look.values, it)) } }
        fun renamed(value: Any?, renames: Map<String, String>): Any? =
            (value as? String)?.let { renames[it] ?: first[it].takeIf { _ -> it.startsWith(OLD_KEPT) } } ?: value
        for ((id, own) in sets) {
            for (slot in slots) {
                val now = own[slot] ?: continue
                val to = renamed(now, setRenames.getValue(id))
                if (to != now) KeyboardProfiles.put(editor, KeyboardProfiles.ownKey(id, slot), to)
            }
            for (key in own.keys) if (isKey(key)) editor.remove(KeyboardProfiles.ownKey(id, key))
        }
        for ((name, colors) in list) if (name !in before) put(editor, name, colors)
        editor.commit()
        val moved = looks.mapIndexed { i, look ->
            val renames = lookRenames[i]
            if (renames.isEmpty() && slots.none { (look.values[it] as? String)?.startsWith(OLD_KEPT) == true }) look
            else AppearanceLooks.Look(look.name, look.values.filterKeys { !isKey(it) }.mapValues { (key, value) ->
                if (key in slots) renamed(value, renames) else value }, look.remembered)
        }
        if (moved.zip(looks).any { (a, b) -> a !== b }) AppearanceLooks.save(real, moved, now = true)
        real.edit().putBoolean(CENTRAL_DONE, true).commit()
        helium314.keyboard.latin.utils.Log.i("Palettes", "one list: ${list.size - before.size} palettes moved in")
    }

    /** A restore's palettes ([merge]): backup name -> name on the phone, the phone's renamed with their users
     *  following (in order), the clashes the restore dialog asks about. */
    internal class Merged(val names: Map<String, String>, val phoneRenames: List<Pair<String, String>>,
                 val clashes: List<helium314.keyboard.settings.preferences.SavedItemsMerge.Clash>)

    /**
     * Restoring some keyboards: the backup's palettes come into the one list as its saved themes do (owner 2026-10-11:
     * "palette with same name but different colours in the backup should get priority and be called Sunset, while
     * existing palette in the app still remains and becomes Sunset 2 ... if user checks the palette's box"; "palette with
     * different name but same everything else should also appear as a checkbox ... the backup's name should overwrite
     * the name that's live in app in all keyboards and themes that use them"): see [SavedItemsMerge.mergeKind], [choices].
     * From the backup's list ([backup], plain keys), and from an older backup its sets' and saved themes' copies
     * ([backupLooks]), the first of a name counting; an old kept copy "~custom:N" asks for "Palette N", matched by colors
     * or numbered, never asked about. [phone]: the phone's stored settings. [write]: the list written to [real] (the
     * users of a renamed one follow in [SavedItemsMerge.merge]); else the names only, for the restore question.
     */
    internal fun merge(real: SharedPreferences, phone: Map<String, *>, backup: Map<String, *>, backupLooks: List<AppearanceLooks.Look>,
              choices: helium314.keyboard.settings.preferences.SavedItemsMerge.Choices, write: Boolean): Merged {
        val before = list(phone)
        val items = ArrayList<helium314.keyboard.settings.preferences.SavedItemsMerge.In<Map<String, Any?>>>()
        fun take(name: String, colors: Map<String, Any?>) {
            items += helium314.keyboard.settings.preferences.SavedItemsMerge.In(name, listName(name), colors, plain = name.startsWith(OLD_KEPT))
        }
        for ((name, colors) in list(backup)) take(name, colors)
        val sets = java.util.TreeMap<Int, HashMap<String, Any?>>()
        for ((stored, value) in backup) {
            val (id, key) = KeyboardProfiles.splitOwnKey(stored) ?: continue
            if (isKey(key)) sets.getOrPut(id) { HashMap() }[key] = value
        }
        for (own in sets.values) for (name in own.keys.mapNotNullTo(sortedSetOf<String>()) { nameOf(it) }) take(name, colorsIn(own, name))
        for (look in backupLooks) for (name in look.values.keys.mapNotNullTo(sortedSetOf<String>()) { nameOf(it) }) take(name, colorsIn(look.values, name))
        val result = helium314.keyboard.settings.preferences.SavedItemsMerge.mergeKind(
            helium314.keyboard.settings.preferences.SavedItemsMerge.Kind.PALETTES, before.toList(), items, emptySet(), choices) { a, b -> a == b }
        val after = result.list.toMap()
        if (write && after != before) real.edit().apply {
            // (the old names first: a name taken over gets its new colors after)
            for ((name, colors) in before) if (after[name] != colors) for (key in keys(name)) remove(key)
            for ((name, colors) in after) if (before[name] != colors) put(this, name, colors)
        }.commit()
        return Merged(result.names, result.phoneRenames, result.clashes)
    }
}

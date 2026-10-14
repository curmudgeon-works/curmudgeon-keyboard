// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.folder
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.Palettes
import helium314.keyboard.settings.SavedItemDefaults
import java.io.File

/**
 * Restoring some keyboards brings in the backup's saved items (owner 2026-10-11: "partial restore -- keep everything
 * existing and also add whatever was in the backup", "Items inside items must be renamed together"): every palette,
 * popup set, Layout and theme of the backup comes in, unless the phone has the same one (same name and content).
 * A clash is asked about in the restore dialog (owner 2026-10-11: "backup wins the name if ticked"; ticked to start
 * with, [Choices]):
 * - same name, other content: ticked, the backup's takes the name and the phone's gets the next free number
 *   ([RestorePlan.nextFreeName], "Sunset 2"); every keyboard and saved item naming it then names the backup's, so the
 *   phone's "Sunset 2" is unused. Unticked, the backup's doesn't come in ("backup's doesn't come in at all"): the
 *   restored keyboards naming it name the phone's.
 * - the same content under another name: ticked, the phone's is renamed to the backup's name everywhere; unticked,
 *   nothing is renamed and the restored keyboards name the phone's.
 * A built-in name can't be won: the backup's comes in numbered. An older backup's kept palette copy ("~custom:N") is
 * matched by content, else numbered, never asked about. Before (until 2026-10-11): the backup's got "Sunset 2", or with
 * "bring them in as copies" off wasn't brought in and its keyboards named none.
 * Inner items first (palettes, popup sets), then the outer ones (Layouts, themes) with their references named as they
 * are after the restore, so a second restore of the same backup adds nothing. A restored Layout's custom key layouts
 * (its keys' names and content) come in too ([KeyLayoutFiles], R4). Not [write]: the same outcome worked out, nothing
 * written (R3, R6: the restore question uses it).
 */
internal object SavedItemsMerge {
    /** The kinds of saved item the restore dialog asks about, in its order. */
    enum class Kind { PALETTES, THEMES, LAYOUTS, POPUP_SETS }

    /** A custom key layout [name] of [type] a restored Layout names, the phone's with other keys: ticked (the Layout's
     *  tick), the phone's becomes [phoneNewName] with every keyboard and saved Layout using it following, and the
     *  Layout's keys take [name] (owner 2026-10-11, R4). */
    data class FileClash(val type: LayoutType, val name: String, val phoneNewName: String)

    /**
     * A backup item clashing with one of the phone's. [phoneName] == [backupName]: same name, other content; ticked the
     * phone's becomes [phoneNewName]. [phoneName] another name: the same content; ticked the phone's is renamed
     * [backupName]. [phoneName] null: a Layout whose name doesn't clash, only its key layouts ([files]).
     */
    data class Clash(val kind: Kind, val backupName: String, val phoneName: String?, val phoneNewName: String? = null,
                     val files: List<FileClash> = emptyList()) {
        val key get() = kind to backupName
        val sameName get() = phoneName == backupName
    }

    /** The restore dialog's ticks: the clashes unticked, by [Clash.key]; anything else is ticked (the start). */
    data class Choices(val unticked: Set<Pair<Kind, String>> = emptySet()) {
        fun wins(kind: Kind, backupName: String) = (kind to backupName) !in unticked
    }

    class Renames(val popupSets: Map<String, String>, val layouts: Map<String, String>, val themes: Map<String, String>,
                  val fonts: Map<String, String> = emptyMap(), val keyLayouts: Map<LayoutType, Map<String, String>> = emptyMap(),
                  val palettes: Map<String, String> = emptyMap(),
                  /** the phone's items renamed with their users following, in order, by the key naming them */
                  val phoneRenames: Map<String, List<Pair<String, String>>> = emptyMap(),
                  val clashes: List<Clash> = emptyList(),
                  /** the phone's key layout files to rename first, their users following ([KeyLayoutFiles]) */
                  val fileRenames: List<FileClash> = emptyList()) {
        /** A restored keyboard's settings ([values], plain keys) naming its items as they are called on the phone now. */
        fun apply(values: Map<String, Any?>): Map<String, Any?> = Palettes.renamedIn(values, palettes).mapValues { (key, value) ->
            val map = when (key) {
                KeyPopupOverrides.PREF_SELECTED_SET -> popupSets
                LayoutPresets.PREF_SELECTED -> layouts
                AppearanceLooks.PREF_SELECTED -> themes
                else -> defaultLayoutType(key)?.let { keyLayouts[it] }
            }
            if (key in helium314.keyboard.keyboard.FontLibrary.fontPrefs) return@mapValues renamedFont(value, fonts)
            (value as? String)?.let { map?.get(it) } ?: value
        }

        /** A phone keyboard's settings ([values], plain keys) naming its items as they are called after the restore. */
        fun phoneApply(values: Map<String, Any?>): Map<String, Any?> =
            if (phoneRenames.isEmpty()) values else values.mapValues { (key, value) -> renamed(value, phoneRenames[key]) }
    }

    /** [value] after [renames], one after the other (as they are written). */
    private fun renamed(value: Any?, renames: List<Pair<String, String>>?): Any? =
        if (value !is String || renames == null) value else renames.fold(value) { v, (from, to) -> if (v == from) to else v }

    /** The layout type whose default [key] is (Settings.PREF_LAYOUT_PREFIX + type), or null. */
    private fun defaultLayoutType(key: String): LayoutType? =
        if (!key.startsWith(Settings.PREF_LAYOUT_PREFIX)) null else LayoutType.entries.firstOrNull { key == Settings.PREF_LAYOUT_PREFIX + it.name }

    /** A Layout's values naming the custom key layouts as they came in ([keyLayouts], by type): its keys ("layout:<type>")
     *  and its defaults for the types. */
    private fun renamedKeyLayouts(values: Map<String, Any?>, keyLayouts: Map<LayoutType, Map<String, String>>): Map<String, Any?> =
        if (keyLayouts.isEmpty()) values else values.mapValues { (key, value) ->
            val type = defaultLayoutType(key) ?: LayoutType.entries.firstOrNull { key == LayoutPresets.LAYOUT + it.name }
            (value as? String)?.let { type?.let { t -> keyLayouts[t]?.get(it) } } ?: value
        }

    /** A font choice ("font:<file>") naming [fonts]' new name. */
    fun renamedFont(value: Any?, fonts: Map<String, String>): Any? {
        val name = (value as? String)?.takeIf { it.startsWith(helium314.keyboard.keyboard.FontLibrary.PREFIX) }
            ?.removePrefix(helium314.keyboard.keyboard.FontLibrary.PREFIX) ?: return value
        return fonts[name]?.let { helium314.keyboard.keyboard.FontLibrary.PREFIX + it } ?: value
    }

    /** A theme's values with its fonts and library pictures named as they came in ([fonts], [pictures]). */
    private fun renamedFiles(values: Map<String, Any?>, fonts: Map<String, String>, pictures: Map<String, String>): Map<String, Any?> =
        values.mapValues { (key, value) -> when {
            key in helium314.keyboard.keyboard.FontLibrary.fontPrefs -> renamedFont(value, fonts)
            key == AppearanceLooks.PICTURES && pictures.isNotEmpty() && (value as? String)?.startsWith("lib:") == true -> runCatching {
                val o = org.json.JSONObject(value.removePrefix("lib:"))
                for (slot in o.keys()) o.optJSONObject(slot)?.let { s -> pictures[s.optString("p")]?.let { s.put("p", it) } }
                "lib:$o"
            }.getOrDefault(value)
            else -> value
        } }

    /** The next free name for [name]: itself if free, else the next number counting up ([RestorePlan.nextFreeName]). */
    fun freeName(name: String, taken: Set<String>): String = RestorePlan.nextFreeName(name, taken)

    // ---- one kind ----

    /** A backup item: [key] its name in the backup, [name] the name it asks for (an old kept palette: "Palette N"),
     *  [plain]: never asked about (an old kept palette copy). */
    class In<C>(val key: String, val name: String, val content: C, val plain: Boolean = false)

    /** One kind after the restore: [list], the phone's (in place, renamed) then the backup's brought in; [names], backup
     *  key -> name; [phoneRenames], the phone's renamed with their users following, in order; [added], the backup keys
     *  brought in, with the content as they came in. */
    class KindResult<C>(val list: List<Pair<String, C>>, val names: Map<String, String>, val phoneRenames: List<Pair<String, String>>,
                        val added: Map<String, C>, val clashes: List<Clash>)

    private class Entry<C>(var name: String, val content: C, val phone: Boolean)

    /** [backup]'s items into [phone]'s (name, content; in order), [same] content, [reserved] the built-in names, as
     *  [choices] says (see the object's doc). */
    fun <C> mergeKind(kind: Kind, phone: List<Pair<String, C>>, backup: List<In<C>>, reserved: Set<String>, choices: Choices,
                      same: (C, C) -> Boolean): KindResult<C> {
        val list = phone.map { Entry(it.first, it.second, phone = true) }.toMutableList()
        val claimed = HashSet<Entry<C>>() // (the phone's ones a backup item is: renamed once only)
        val names = LinkedHashMap<String, Entry<C>>() // (entries: a later rename reaches the backup items naming them)
        val renames = ArrayList<Pair<String, String>>()
        val added = LinkedHashMap<String, C>()
        val clashes = ArrayList<Clash>()
        fun taken() = list.mapTo(HashSet()) { it.name } + reserved
        fun add(item: In<C>, name: String) { val e = Entry(name, item.content, phone = false); list += e; names[item.key] = e; added[item.key] = item.content }
        for (item in backup) {
            if (item.key in names) continue // (the first of a name counts)
            val match = list.firstOrNull { same(it.content, item.content) }
            if (match != null) {
                val asked = match.phone && match !in claimed && match.name != item.name && !item.plain && item.name !in reserved
                if (asked) clashes += Clash(kind, item.name, match.name)
                if (asked && choices.wins(kind, item.name)) {
                    // (another of the phone's holding the name moves to the next number; its users stay on the name)
                    list.firstOrNull { it.name == item.name }?.let { it.name = RestorePlan.nextFreeName(item.name, taken()) }
                    renames += match.name to item.name
                    match.name = item.name
                }
                claimed += match
                names[item.key] = match
                continue
            }
            val holder = list.firstOrNull { it.name == item.name }
            if (holder == null && item.name !in reserved) { add(item, item.name); continue }
            if (holder == null || !holder.phone || item.plain) { add(item, RestorePlan.nextFreeName(item.name, taken())); continue }
            val next = RestorePlan.nextFreeName(item.name, taken())
            clashes += Clash(kind, item.name, item.name, next)
            if (!choices.wins(kind, item.name)) { claimed += holder; names[item.key] = holder; continue }
            holder.name = next // (its users stay on the name: the backup's now)
            add(item, item.name)
        }
        return KindResult(list.map { it.name to it.content }, names.mapValues { it.value.name }, renames, added, clashes)
    }

    // ---- everything ----

    /**
     * [backup]: the backup's stored settings; [phone]: the phone's stored settings, its palettes ([Palettes.merge]);
     * [fonts], [pictures], [keyLayouts]: the files as they came in; [skipFiles]: the custom key layouts the restored
     * keyboards bring themselves (type to backup name). Not [write]: the renames only, the phone's lists untouched
     * (review 2026-10-11 R3: the restore question compares with them).
     */
    fun merge(ctx: Context, real: SharedPreferences, backup: Map<String, Any?>, choices: Choices = Choices(),
              fonts: Map<String, String> = emptyMap(), pictures: Map<String, String> = emptyMap(),
              keyLayouts: Map<LayoutType, Map<String, String>> = emptyMap(), skipFiles: Set<Pair<LayoutType, String>> = emptySet(),
              phone: Map<String, Any?> = real.all, write: Boolean = true): Renames {
        // the backup's lists, read as the phone reads its own (a scratch store, gone afterwards)
        val scratch = ctx.getSharedPreferences(if (write) "restore_saved_items" else "restore_saved_items_dry", Context.MODE_PRIVATE)
        scratch.edit(commit = true) {
            clear()
            for (key in listOf(KeyPopupOverrides.PREF_SETS, LayoutPresets.PREF, AppearanceLooks.PREF))
                (backup[key] as? String)?.let { putString(key, it) }
        }
        try {
            // palettes first: the themes name them
            val backupLooks = AppearanceLooks.load(scratch)
            val palettes = Palettes.merge(real, phone, backup, backupLooks, choices, write)

            // popup sets: by everything but the name (a field at its default keeps its remembered value in place, so
            // that is compared too)
            fun content(s: KeyPopupOverrides.UserSet) = listOf(s.morePopups, s.symbolsLayout, s.overrides, s.symbolMap, s.popupOrder, s.atDefault)
            fun KeyPopupOverrides.UserSet.named(name: String) = KeyPopupOverrides.UserSet(name, morePopups, symbolsLayout, overrides, symbolMap, popupOrder, atDefault)
            val sets = mergeKind(Kind.POPUP_SETS, KeyPopupOverrides.loadSets(real).map { it.name to it },
                KeyPopupOverrides.loadSets(scratch).map { In(it.name, it.name, it) }, emptySet(), choices) { a, b -> content(a) == content(b) }
            if (write) KeyPopupOverrides.saveSets(real, sets.list.map { (name, set) -> set.named(name) })

            // Layouts: their popup set and custom key layouts named as after the restore, then by content (values, and
            // the remembered ones: SavedItemDefaults.sameContent); the phone's popup sets renamed reach the phone's too
            val setKey = KeyPopupOverrides.PREF_SELECTED_SET
            val phoneLayouts = LayoutPresets.load(real).map { p ->
                p.name to if (sets.phoneRenames.isEmpty() || p.values[setKey] == null) p
                    else LayoutPresets.Preset(p.name, p.values + (setKey to renamed(p.values[setKey], sets.phoneRenames)), remembered = p.remembered) }
            val backupLayouts = LayoutPresets.load(scratch)
            val layouts = mergeKind(Kind.LAYOUTS, phoneLayouts, backupLayouts.map { preset ->
                val values = renamedKeyLayouts(preset.values, keyLayouts).mapValues { (key, value) ->
                    if (key == setKey) (value as? String)?.let { sets.names[it] ?: it } else value }
                In(preset.name, preset.name, LayoutPresets.Preset(preset.name, values, remembered = preset.remembered))
            }, LayoutPresets.builtIn(ctx).mapTo(HashSet()) { it.name }, choices) { a, b -> SavedItemDefaults.sameContent(a.values, a.remembered, b.values, b.remembered) }
            if (write) LayoutPresets.save(real, layouts.list.map { (name, p) -> LayoutPresets.Preset(name, p.values, remembered = p.remembered) })
            val files = KeyLayoutFiles(ctx, choices, write)
            for ((key, preset) in layouts.added) files.bring(key, backupLayouts.first { it.name == key }.values, preset.values, skipFiles)

            // themes, by content (values and remembered, likewise); their fonts, pictures and palettes named as they came
            // in (no palette copies), the phone's naming its palettes as renamed
            val slots = Palettes.slots
            val phoneThemes = AppearanceLooks.load(real).map { look ->
                look.name to if (palettes.phoneRenames.isEmpty() || slots.none { look.values[it] != null }) look
                    else AppearanceLooks.Look(look.name, look.values.mapValues { (key, value) -> if (key in slots) renamed(value, palettes.phoneRenames) else value }, look.remembered) }
            val themes = mergeKind(Kind.THEMES, phoneThemes, backupLooks.map { look ->
                In(look.name, look.name, AppearanceLooks.Look(look.name, Palettes.renamedIn(renamedFiles(look.values, fonts, pictures), palettes.names), look.remembered))
            }, AppearanceLooks.builtIn(ctx).mapTo(HashSet()) { it.name }, choices) { a, b -> SavedItemDefaults.sameContent(a.values, a.remembered, b.values, b.remembered) }
            if (write) AppearanceLooks.save(real, themes.list.map { (name, look) -> AppearanceLooks.Look(name, look.values, look.remembered) }, now = true)

            val phoneRenames = buildMap<String, List<Pair<String, String>>> {
                if (sets.phoneRenames.isNotEmpty()) put(setKey, sets.phoneRenames)
                if (layouts.phoneRenames.isNotEmpty()) put(LayoutPresets.PREF_SELECTED, layouts.phoneRenames)
                if (themes.phoneRenames.isNotEmpty()) put(AppearanceLooks.PREF_SELECTED, themes.phoneRenames)
                if (palettes.phoneRenames.isNotEmpty()) for (slot in slots) put(slot, palettes.phoneRenames)
            }
            // every keyboard naming a phone item renamed follows, each set, hidden and switched-off ones too
            if (write) for ((key, renames) in phoneRenames) for ((from, to) in renames) KeyboardProfiles.replaceValueEverywhere(real, key, from, to)
            val layoutClashes = layouts.clashes.map { c -> files.clashes[c.backupName]?.let { c.copy(files = it) } ?: c } +
                files.clashes.filterKeys { name -> layouts.clashes.none { it.backupName == name } }.map { (name, f) -> Clash(Kind.LAYOUTS, name, null, files = f) }
            return Renames(sets.names, layouts.names, themes.names, fonts, keyLayouts, palettes.names, phoneRenames,
                palettes.clashes + themes.clashes + layoutClashes + sets.clashes, files.renames)
        } finally {
            scratch.edit(commit = true) { clear() }
        }
    }

    /**
     * The custom key layouts a restored Layout names (owner 2026-10-11, R4: "bringing in the names of key groups if any"):
     * its keys' name ("layout:<type>", a named one) with the keys it holds ("layoutText:<type>"). None on the phone:
     * written so. The phone's with the same keys: the phone's. Other keys: the Layout's tick; ticked the phone's file
     * gets the next number ([LayoutUtilsCustom.numberedLayoutName]) and the Layout's keys take the name ([renames], done
     * by the restore first, its users following), unticked nothing (the Layout's own copy, applied as an unsaved one).
     * One the restored keyboards bring ([merge]'s skipFiles) is theirs. Not [write]: nothing written.
     */
    private class KeyLayoutFiles(ctx: Context, val choices: Choices, val write: Boolean) {
        private val dir = DeviceProtectedUtils.getFilesDir(ctx)
        private val planned = HashSet<String>() // (paths taken by this restore)
        val renames = ArrayList<FileClash>()
        val clashes = LinkedHashMap<String, List<FileClash>>() // by Layout

        fun bring(layout: String, backupValues: Map<String, Any?>, values: Map<String, Any?>, skip: Set<Pair<LayoutType, String>>) {
            val found = ArrayList<FileClash>()
            for (type in LayoutType.entries) {
                val name = values[LayoutPresets.LAYOUT + type.name] as? String ?: continue
                if (!LayoutUtilsCustom.isCustomLayout(name) || LayoutUtilsCustom.isPrivateLayout(name)) continue
                if ((backupValues[LayoutPresets.LAYOUT + type.name] as? String)?.let { (type to it) in skip } == true) continue
                val text = values[LayoutPresets.LAYOUT_TEXT + type.name] as? String ?: continue
                val folder = File(dir, type.folder)
                val file = File(folder, name)
                if (!file.canonicalPath.startsWith(folder.canonicalPath + File.separator)) continue // (never outside)
                if (file.path in planned) continue // (another restored Layout's)
                if (!file.exists()) {
                    planned += file.path
                    if (write) { folder.mkdirs(); file.writeText(text); LayoutUtilsCustom.onLayoutFileChanged() }
                    continue
                }
                if (file.readText() == text) continue
                val number = generateSequence(2) { it + 1 }.map { LayoutUtilsCustom.numberedLayoutName(name, type, it) }
                    .first { val f = File(folder, it); !f.exists() && f.path !in planned }
                val clash = FileClash(type, name, number)
                found += clash
                if (choices.wins(Kind.LAYOUTS, layout)) { renames += clash; planned += file.path; planned += File(folder, number).path }
            }
            if (found.isNotEmpty()) clashes[layout] = found
        }
    }
}

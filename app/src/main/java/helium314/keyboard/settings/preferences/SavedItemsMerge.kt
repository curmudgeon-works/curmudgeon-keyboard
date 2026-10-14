// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.Palettes
import helium314.keyboard.settings.SavedItemDefaults
import java.io.File

/**
 * Restoring some keyboards brings in the backup's saved items (owner 2026-10-11: "partial restore -- keep everything
 * existing and also add whatever was in the backup", "Items inside items must be renamed together"): palettes, popup
 * sets, Layouts, themes, loaded fonts and pictures. Owner 2026-10-12 (the restore redesign, M4): one plan ([plan]), made
 * once when the backup is read; the restore dialog, its questions and the restore run all read it, nothing is worked
 * out again ([apply]). Every backup item is a row of the restore dialog, ticked to start with; an unticked one doesn't
 * come in (a ticked keyboard's items are always ticked: "Required for"). The device's with the same name and content is
 * the device's, nothing asked. A backup item with candidates on the device ("Merge required": the device's of the same
 * name with other content first, then the device's with the same content under another name) is asked about
 * ([Answer]):
 * - Replace one of them: it becomes exactly the backup's (the same name: its content the backup's, and the keyboards
 *   using it take it; another name: it is renamed to the backup's, its users following).
 * - Keep both: the backup's takes its name, the device's of that name gets the next free number ("Sunset 2"), every
 *   keyboard and saved item using it following, so they keep their look.
 * (Overrides owner 2026-10-11 "backup wins the name if ticked": the device's users stayed on the name and took the
 * backup's; an unticked clash named the device's.) Whatever the answer, the backup's keeps its name ([ItemPlan.after]):
 * a built-in name, or an older backup's kept palette copy ("~custom:N", matched by content, else "Palette N"), comes
 * in numbered, never asked. Inner items first (palettes, fonts, pictures, popup sets), then the outer ones (Layouts,
 * themes) naming them as after the restore, so a second restore of the same backup adds nothing. The custom key layouts
 * the restored keyboards and Layouts name ([KeyLayouts]) keep the backup's names too, the device's of that name with
 * other keys renamed first ("My numrow 2", owner 2026-10-12).
 */
internal object SavedItemsMerge {
    /** The kinds of saved item, in the order their clashes are listed. */
    enum class Kind { PALETTES, THEMES, LAYOUTS, POPUP_SETS, FONTS, PICTURES }

    /** A custom key layout [name] of [type] the backup brings, the device's with other keys: the device's becomes
     *  [phoneNewName] first, every keyboard and saved Layout using it following, and the backup's takes [name]
     *  (owner 2026-10-12: "Backup's My numrow; yours becomes My numrow 2"). */
    data class FileClash(val type: LayoutType, val name: String, val phoneNewName: String)

    /**
     * A backup item with candidates on the device ("Merge required"): [candidates], the device's names, the same name
     * first; [phoneName], the first of them (the suggested one); [phoneNewName], what the device's of the same name
     * becomes with Keep both ("Sunset 2"; null: none has the name). A Layout's [files]: its custom key layouts whose
     * device copy is renamed ([FileClash]).
     */
    data class Clash(val kind: Kind, val backupName: String, val phoneName: String?, val phoneNewName: String? = null,
                     val files: List<FileClash> = emptyList(), val candidates: List<String> = listOfNotNull(phoneName)) {
        val key get() = kind to backupName
        val sameName get() = phoneName == backupName
    }

    /** The answer to a backup item's question (owner 2026-10-12). */
    sealed class Answer {
        /** The device's [device] (its name before the restore) becomes exactly the backup's. */
        data class Replace(val device: String) : Answer()
        /** The backup's comes in under its name; the device's of that name becomes "… 2". */
        data object KeepBoth : Answer()
    }

    /** The restore dialog's rows and answers: the items [unticked] (by kind and backup key; anything else is ticked) and
     *  the [answers] to their questions (none: the suggested one, [ItemPlan.suggested]). */
    data class Choices(val unticked: Set<Pair<Kind, String>> = emptySet(), val answers: Map<Pair<Kind, String>, Answer> = emptyMap()) {
        fun ticked(kind: Kind, key: String) = (kind to key) !in unticked
        fun answer(plan: ItemPlan<*>): Answer = answers[plan.kind to plan.key] ?: plan.suggested
    }

    /** A loaded font's or picture's content, compared by its size and digest. */
    data class FileDigest(val size: Long, val digest: String)

    /** A backup item: [key] its name in the backup, [name] the name it asks for (an old kept palette: "Palette N"),
     *  [plain]: never asked about (an old kept palette copy). */
    class In<C>(val key: String, val name: String, val content: C, val plain: Boolean = false)

    /**
     * What the restore does with one backup item, worked out once ([planKind]): [exact], the device's with the same name
     * and content (an old kept copy: the same content, any name), which it is; else [holder], the device's of its name
     * (other content), and [sameContent], the device's with its content under other names: its candidates. With Keep both
     * the device's [holder] becomes [keepBothName]; with no candidate it comes in as [landName] (its name, numbered when
     * built-in or taken by an earlier backup item).
     */
    class ItemPlan<C>(val kind: Kind, val key: String, val name: String, val content: C, val plain: Boolean,
                      val exact: String?, val holder: String?, val sameContent: List<String>, val keepBothName: String?,
                      val landName: String) {
        val candidates: List<String> get() = listOfNotNull(holder) + sameContent
        /** "Merge required": the restore dialog asks about it. */
        val mergeRequired get() = candidates.isNotEmpty()
        /** The answer picked to start with: the first candidate replaced (the same name first). */
        val suggested: Answer get() = Answer.Replace(candidates.first())
        /** Its name after the restore, whatever the answer (owner 2026-10-12: the backup's keeps its name). */
        val after: String get() = exact ?: if (mergeRequired) name else landName
    }

    /** The next free name for [name]: itself if free, else the next number counting up ([RestorePlan.nextFreeName]). */
    fun freeName(name: String, taken: Set<String>): String = RestorePlan.nextFreeName(name, taken)

    /** A file's next free name: "Mine.ttf", "Mine 2.ttf", "Mine 3.ttf" (as before for fonts and pictures). */
    fun freeFileName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(2) { it + 1 }.map { "$base $it$ext" }.first { it !in taken }
    }

    // ---- one kind ----

    /**
     * The plans of [backup]'s items of [kind] against the device's [phone] (name, content), [same] content, [reserved]
     * the built-in names, [numbered] the next free name. The names given ([ItemPlan.keepBothName], [ItemPlan.landName])
     * are none the device or the backup has, nor one given before: they don't depend on the answers, so the restore
     * dialog shows what the run does.
     */
    fun <C> planKind(kind: Kind, phone: List<Pair<String, C>>, backup: List<In<C>>, reserved: Set<String>, same: (C, C) -> Boolean,
                     numbered: (String, Set<String>) -> String = ::freeName): List<ItemPlan<C>> {
        val seen = HashSet<String>()
        val items = backup.filter { seen.add(it.key) } // (the first of a name counts)
        val deviceNames = phone.mapTo(HashSet()) { it.first }
        val used = HashSet<String>(deviceNames).apply { addAll(reserved); items.forEach { add(it.name) } }
        // (the device's that is a backup item already, same name and content: never another one's candidate)
        val identical = items.filter { !it.plain }.mapNotNullTo(HashSet()) { item ->
            phone.firstOrNull { it.first == item.name && same(it.second, item.content) }?.first }
        val landed = HashSet<String>()
        return items.map { item ->
            if (item.plain) {
                val exact = phone.firstOrNull { same(it.second, item.content) }?.first
                val land = exact ?: numbered(item.name, used + landed).also { used += it }
                return@map ItemPlan(kind, item.key, item.name, item.content, true, exact, null, emptyList(), null, land)
            }
            if (item.name in identical)
                return@map ItemPlan(kind, item.key, item.name, item.content, false, item.name, null, emptyList(), null, item.name)
            val builtIn = item.name in reserved
            val holder = if (builtIn) null else phone.firstOrNull { it.first == item.name }?.first
            val sameContent = if (builtIn) emptyList() else
                phone.filter { it.first != item.name && it.first !in identical && same(it.second, item.content) }.map { it.first }
            val keepBoth = holder?.let { numbered(item.name, used).also { n -> used += n } }
            val land = if (builtIn || item.name in landed) numbered(item.name, used + landed).also { used += it } else item.name
            landed += land
            ItemPlan(kind, item.key, item.name, item.content, false, null, holder, sameContent, keepBoth, land)
        }
    }

    /** One kind after the restore: [list], the device's (in place, renamed) then the backup's brought in; [names], backup
     *  key -> name; [phoneRenames], the device's renamed with their users following, in order; [added], the backup keys
     *  brought in as new items, with their content; [replaced], the device's (by their name after) that took the backup's
     *  content. */
    class KindResult<C>(val list: List<Pair<String, C>>, val names: Map<String, String>, val phoneRenames: List<Pair<String, String>>,
                        val added: Map<String, C>, val replaced: List<String>)

    private class Entry<C>(val original: String?, var name: String, var content: C)

    /** [plans] on the device's [phone] (name, content; as it is now, in order) as [choices] says; [contentOf]: a backup
     *  item's content as it comes in. Answers name the device's items as they were called before the restore. */
    fun <C> applyKind(plans: List<ItemPlan<C>>, phone: List<Pair<String, C>>, choices: Choices,
                      contentOf: (ItemPlan<C>) -> C = { it.content }): KindResult<C> {
        val list = phone.map { Entry(it.first, it.first, it.second) }.toMutableList()
        val claimed = HashSet<Entry<C>>()
        val names = LinkedHashMap<String, String>()
        val renames = ArrayList<Pair<String, String>>()
        val replaced = ArrayList<String>()
        val added = LinkedHashMap<String, C>()
        fun device(original: String) = list.firstOrNull { it.original == original }
        fun rename(e: Entry<C>, to: String) { if (e.name != to) { renames += e.name to to; e.name = to } }
        fun add(p: ItemPlan<C>, name: String) {
            val content = contentOf(p)
            list += Entry(null, name, content); names[p.key] = name; added[p.key] = content
        }
        // the device's still called [p]'s name moves to its Keep-both name, its users following
        fun clearName(p: ItemPlan<C>) {
            val holder = list.firstOrNull { it.name == p.name && it.original != null } ?: return
            rename(holder, p.keepBothName ?: freeName(p.name, list.mapTo(HashSet()) { it.name }))
        }
        for (p in plans) {
            if (!choices.ticked(p.kind, p.key)) continue
            if (p.exact != null) { names[p.key] = device(p.exact)?.name ?: p.exact; continue }
            if (!p.mergeRequired) { add(p, p.landName); continue }
            // (a device item another backup item took already: Keep both)
            val target = (choices.answer(p) as? Answer.Replace)?.device?.takeIf { it in p.candidates }?.let(::device)?.takeIf { it !in claimed }
            if (target == null) { clearName(p); add(p, p.name); continue }
            claimed += target
            if (target.original == p.holder && target.name == p.name) { target.content = contentOf(p); replaced += p.name }
            else { clearName(p); rename(target, p.name) }
            names[p.key] = p.name
        }
        return KindResult(list.map { it.name to it.content }, names, renames, added, replaced)
    }

    // ---- custom key layouts ----

    /**
     * The custom key layouts of the backup ([contents]: type and name -> keys; its layout files, and the keys its saved
     * Layouts keep) and the device's of the same name with other keys: the name each gets ([deviceRenames], owner
     * 2026-10-12: the backup's keeps its name, the device's becomes "My numrow 2"; an unsaved one a new unsaved name).
     * Worked out once with the plan.
     */
    class KeyLayouts(val contents: Map<Pair<LayoutType, String>, ByteArray> = emptyMap(),
                     val deviceRenames: Map<Pair<LayoutType, String>, String> = emptyMap()) {
        fun deviceRename(type: LayoutType, name: String) = deviceRenames[type to name]
    }

    // ---- everything ----

    /** The plan of every saved item of a backup ([plan]); [deviceFonts], [devicePictures]: the device's files as they
     *  were read. */
    class Plan(
        val palettes: List<ItemPlan<Map<String, Any?>>> = emptyList(),
        val popupSets: List<ItemPlan<KeyPopupOverrides.UserSet>> = emptyList(),
        val layouts: List<ItemPlan<LayoutPresets.Preset>> = emptyList(),
        val themes: List<ItemPlan<AppearanceLooks.Look>> = emptyList(),
        val fonts: List<ItemPlan<FileDigest>> = emptyList(),
        val pictures: List<ItemPlan<FileDigest>> = emptyList(),
        val keyLayouts: KeyLayouts = KeyLayouts(),
        val deviceFonts: List<Pair<String, FileDigest>> = emptyList(),
        val devicePictures: List<Pair<String, FileDigest>> = emptyList(),
    ) {
        fun of(kind: Kind): List<ItemPlan<*>> = when (kind) {
            Kind.PALETTES -> palettes
            Kind.THEMES -> themes
            Kind.LAYOUTS -> layouts
            Kind.POPUP_SETS -> popupSets
            Kind.FONTS -> fonts
            Kind.PICTURES -> pictures
        }
        fun item(kind: Kind, key: String): ItemPlan<*>? = of(kind).firstOrNull { it.key == key }

        /** The custom key layouts (type, name) a backup Layout names (saved ones: an unsaved one comes from the keys it
         *  keeps when applied). */
        fun layoutFiles(layout: LayoutPresets.Preset): List<Pair<LayoutType, String>> = LayoutType.entries.mapNotNull { type ->
            (layout.values[LayoutPresets.LAYOUT + type.name] as? String)?.takeIf {
                helium314.keyboard.latin.utils.LayoutUtilsCustom.isCustomLayout(it) && !helium314.keyboard.latin.utils.LayoutUtilsCustom.isPrivateLayout(it)
            }?.let { type to it }
        }

        /** The items the restore dialog asks about, by kind ([Kind]'s order). */
        val clashes: List<Clash> by lazy {
            Kind.entries.flatMap { kind -> of(kind).filter { it.mergeRequired }.map { p ->
                val files = if (kind != Kind.LAYOUTS) emptyList() else layoutFiles(p.content as LayoutPresets.Preset)
                    .mapNotNull { (type, name) -> keyLayouts.deviceRename(type, name)?.let { FileClash(type, name, it) } }
                Clash(kind, p.name, p.candidates.first(), p.keepBothName, files, p.candidates)
            } }
        }
    }

    /** By everything but the name (a field at its default keeps its remembered value in place, so that is compared too). */
    private fun content(s: KeyPopupOverrides.UserSet) = listOf(s.morePopups, s.symbolsLayout, s.overrides, s.symbolMap, s.popupOrder, s.atDefault)
    private fun KeyPopupOverrides.UserSet.named(name: String) = KeyPopupOverrides.UserSet(name, morePopups, symbolsLayout, overrides, symbolMap, popupOrder, atDefault)

    /**
     * The plan ([Plan]): [backup], the backup's stored settings; [phone], the device's (its palettes); [fonts] and
     * [pictures], the backup's loaded files, [deviceFonts] and [devicePictures] the device's; [keyLayouts] as planned.
     * Reads only.
     */
    fun plan(ctx: Context, real: SharedPreferences, backup: Map<String, Any?>,
             fonts: List<In<FileDigest>> = emptyList(), pictures: List<In<FileDigest>> = emptyList(),
             deviceFonts: List<Pair<String, FileDigest>> = emptyList(), devicePictures: List<Pair<String, FileDigest>> = emptyList(),
             keyLayouts: KeyLayouts = KeyLayouts(), phone: Map<String, Any?> = real.all): Plan {
        // the backup's lists, read as the device reads its own (a scratch store, gone afterwards)
        val scratch = ctx.getSharedPreferences("restore_saved_items_plan", Context.MODE_PRIVATE)
        scratch.edit(commit = true) {
            clear()
            for (key in listOf(KeyPopupOverrides.PREF_SETS, LayoutPresets.PREF, AppearanceLooks.PREF))
                (backup[key] as? String)?.let { putString(key, it) }
        }
        try {
            val backupLooks = AppearanceLooks.load(scratch)
            val palettes = planKind(Kind.PALETTES, Palettes.list(phone).toList(), Palettes.backupItems(backup, backupLooks), emptySet()) { a, b -> a == b }
            val fontPlans = planKind(Kind.FONTS, deviceFonts, fonts, emptySet(), { a, b -> a == b }, ::freeFileName)
            val picturePlans = planKind(Kind.PICTURES, devicePictures, pictures, emptySet(), { a, b -> a == b }, ::freeFileName)
            val sets = planKind(Kind.POPUP_SETS, KeyPopupOverrides.loadSets(real).map { it.name to it },
                KeyPopupOverrides.loadSets(scratch).map { In(it.name, it.name, it) }, emptySet()) { a, b -> content(a) == content(b) }
            // Layouts: their popup set named as after the restore; compared by content (values, and the remembered ones:
            // SavedItemDefaults.sameContent)
            val setKey = KeyPopupOverrides.PREF_SELECTED_SET
            val setNames = sets.associate { it.key to it.after }
            val layouts = planKind(Kind.LAYOUTS, LayoutPresets.load(real).map { it.name to it }, LayoutPresets.load(scratch).map { preset ->
                val values = preset.values.mapValues { (key, value) -> if (key == setKey) (value as? String)?.let { setNames[it] ?: it } else value }
                In(preset.name, preset.name, LayoutPresets.Preset(preset.name, values, remembered = preset.remembered))
            }, LayoutPresets.builtIn(ctx).mapTo(HashSet()) { it.name }) { a, b -> SavedItemDefaults.sameContent(a.values, a.remembered, b.values, b.remembered) }
            // themes: their fonts, pictures and palettes named as after the restore (no palette copies)
            val paletteNames = palettes.associate { it.key to it.after }
            val fontNames = fontPlans.associate { it.key to it.after }
            val pictureNames = picturePlans.associate { it.key to it.after }
            val themes = planKind(Kind.THEMES, AppearanceLooks.load(real).map { it.name to it }, backupLooks.map { look ->
                In(look.name, look.name, AppearanceLooks.Look(look.name, Palettes.renamedIn(renamedFiles(look.values, fontNames, pictureNames), paletteNames), look.remembered))
            }, AppearanceLooks.builtIn(ctx).mapTo(HashSet()) { it.name }) { a, b -> SavedItemDefaults.sameContent(a.values, a.remembered, b.values, b.remembered) }
            return Plan(palettes, sets, layouts, themes, fontPlans, picturePlans, keyLayouts, deviceFonts, devicePictures)
        } finally {
            scratch.edit(commit = true) { clear() }
        }
    }

    /** What [apply] did: [renames] for the keyboards' settings, the device's items that took the backup's content
     *  ([replaced], by kind and name) and the backup's brought in as new ones ([added]). */
    class Applied(val renames: Renames, val replaced: List<Pair<Kind, String>>, val added: List<Pair<Kind, String>>)

    /**
     * [plan] done as [choices] says, on the device's lists as they are now (the device's keyboards and key layouts got
     * their new ids and names first). [write]: the lists and loaded files written ([files] gives a backup file's bytes by
     * kind and key), and every keyboard (each set, hidden and switched-off ones too) and saved item naming a renamed
     * device item follows; else the names only, for "What to merge" (review 2026-10-11 R3).
     */
    fun apply(ctx: Context, real: SharedPreferences, plan: Plan, choices: Choices, write: Boolean,
              files: ((Kind, String) -> ByteArray?)? = null, phone: Map<String, Any?> = real.all): Applied {
        val phonePalettes = Palettes.list(phone)
        val palettes = applyKind(plan.palettes, phonePalettes.toList(), choices)
        if (write) Palettes.writeList(real, phonePalettes, palettes.list.toMap())
        val fonts = applyKind(plan.fonts, plan.deviceFonts, choices)
        val pictures = applyKind(plan.pictures, plan.devicePictures, choices)
        if (write && files != null) {
            val dir = DeviceProtectedUtils.getFilesDir(ctx)
            writeFiles(File(dir, "fonts"), Kind.FONTS, fonts, files)
            writeFiles(File(dir, "pictures"), Kind.PICTURES, pictures, files)
        }

        val sets = applyKind(plan.popupSets, KeyPopupOverrides.loadSets(real).map { it.name to it }, choices)
        // (a list is written only when something of it changed: nothing ticked, nothing written)
        fun KindResult<*>.changed() = added.isNotEmpty() || replaced.isNotEmpty() || phoneRenames.isNotEmpty()
        if (write && sets.changed()) KeyPopupOverrides.saveSets(real, sets.list.map { (name, set) -> set.named(name) })

        // the device's Layouts naming its popup sets as renamed
        val setKey = KeyPopupOverrides.PREF_SELECTED_SET
        val phoneLayouts = LayoutPresets.load(real).map { p ->
            p.name to if (sets.phoneRenames.isEmpty() || p.values[setKey] == null) p
                else LayoutPresets.Preset(p.name, p.values + (setKey to renamed(p.values[setKey], sets.phoneRenames)), remembered = p.remembered) }
        val layouts = applyKind(plan.layouts, phoneLayouts, choices)
        if (write && (layouts.changed() || sets.phoneRenames.isNotEmpty()))
            LayoutPresets.save(real, layouts.list.map { (name, p) -> LayoutPresets.Preset(name, p.values, remembered = p.remembered) })

        // the device's themes naming its palettes, fonts and pictures as renamed
        val fontRenames = fonts.phoneRenames.map { (a, b) -> FontLibrary.PREFIX + a to FontLibrary.PREFIX + b }
        val slots = Palettes.slots
        val phoneThemes = AppearanceLooks.load(real).map { look ->
            if (palettes.phoneRenames.isEmpty() && fontRenames.isEmpty() && pictures.phoneRenames.isEmpty()) return@map look.name to look
            val values = look.values.mapValues { (key, value) -> when (key) {
                in slots -> renamed(value, palettes.phoneRenames)
                in FontLibrary.fontPrefs -> renamed(value, fontRenames)
                AppearanceLooks.PICTURES -> renamedPictures(value, pictures.phoneRenames)
                else -> value
            } }
            look.name to AppearanceLooks.Look(look.name, values, look.remembered)
        }
        val themes = applyKind(plan.themes, phoneThemes, choices)
        if (write && (themes.changed() || palettes.phoneRenames.isNotEmpty() || fontRenames.isNotEmpty() || pictures.phoneRenames.isNotEmpty()))
            AppearanceLooks.save(real, themes.list.map { (name, look) -> AppearanceLooks.Look(name, look.values, look.remembered) }, now = true)

        val phoneRenames = buildMap<String, List<Pair<String, String>>> {
            if (sets.phoneRenames.isNotEmpty()) put(setKey, sets.phoneRenames)
            if (layouts.phoneRenames.isNotEmpty()) put(LayoutPresets.PREF_SELECTED, layouts.phoneRenames)
            if (themes.phoneRenames.isNotEmpty()) put(AppearanceLooks.PREF_SELECTED, themes.phoneRenames)
            if (palettes.phoneRenames.isNotEmpty()) for (slot in slots) put(slot, palettes.phoneRenames)
            if (fontRenames.isNotEmpty()) for (key in FontLibrary.fontPrefs) put(key, fontRenames)
        }
        // every keyboard naming a device item renamed follows, each set, hidden and switched-off ones too
        if (write) for ((key, renames) in phoneRenames) for ((from, to) in renames) KeyboardProfiles.replaceValueEverywhere(real, key, from, to)
        val results = listOf(Kind.PALETTES to palettes, Kind.FONTS to fonts, Kind.PICTURES to pictures, Kind.POPUP_SETS to sets,
            Kind.LAYOUTS to layouts, Kind.THEMES to themes)
        return Applied(
            Renames(sets.names, layouts.names, themes.names, fonts.names, emptyMap(), palettes.names, phoneRenames, plan.clashes),
            results.flatMap { (kind, r) -> r.replaced.map { kind to it } },
            results.flatMap { (kind, r) -> r.added.keys.mapNotNull { key -> r.names[key]?.let { kind to it } } })
    }

    /** A kind of loaded file ([dir]) after [result]: the device's renamed first, then the device's that take the
     *  backup's content and the backup's brought in written ([files]: their bytes). Never outside [dir]. */
    private fun writeFiles(dir: File, kind: Kind, result: KindResult<FileDigest>, files: (Kind, String) -> ByteArray?) {
        fun inside(f: File) = f.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        for ((from, to) in result.phoneRenames) {
            val old = File(dir, from); val new = File(dir, to)
            if (!inside(old) || !inside(new) || !old.isFile || new.exists()) continue
            if (!old.renameTo(new)) { old.copyTo(new); old.delete() }
        }
        // (by key: a file's key is its name in the backup)
        val written = result.replaced.mapNotNull { name -> result.names.entries.firstOrNull { it.value == name }?.key?.let { it to name } } +
            result.added.keys.mapNotNull { key -> result.names[key]?.let { key to it } }
        for ((key, name) in written) {
            val target = File(dir, name)
            if (!inside(target)) continue
            val bytes = files(kind, key) ?: continue
            try { dir.mkdirs(); target.writeBytes(bytes) } catch (e: Exception) { Log.w("SavedItemsMerge", "could not write ${kind.name.lowercase()} $name", e) }
        }
    }

    class Renames(val popupSets: Map<String, String>, val layouts: Map<String, String>, val themes: Map<String, String>,
                  val fonts: Map<String, String> = emptyMap(), val keyLayouts: Map<LayoutType, Map<String, String>> = emptyMap(),
                  val palettes: Map<String, String> = emptyMap(),
                  /** the device's items renamed with their users following, in order, by the key naming them */
                  val phoneRenames: Map<String, List<Pair<String, String>>> = emptyMap(),
                  val clashes: List<Clash> = emptyList()) {
        /** A restored keyboard's settings ([values], plain keys) naming its items as they are called on the device now. */
        fun apply(values: Map<String, Any?>): Map<String, Any?> = Palettes.renamedIn(values, palettes).mapValues { (key, value) ->
            val map = when (key) {
                KeyPopupOverrides.PREF_SELECTED_SET -> popupSets
                LayoutPresets.PREF_SELECTED -> layouts
                AppearanceLooks.PREF_SELECTED -> themes
                else -> defaultLayoutType(key)?.let { keyLayouts[it] }
            }
            if (key in FontLibrary.fontPrefs) return@mapValues renamedFont(value, fonts)
            (value as? String)?.let { map?.get(it) } ?: value
        }

        /** A device keyboard's settings ([values], plain keys) naming its items as they are called after the restore. */
        fun phoneApply(values: Map<String, Any?>): Map<String, Any?> =
            if (phoneRenames.isEmpty()) values else values.mapValues { (key, value) -> renamed(value, phoneRenames[key]) }
    }

    /** [value] after [renames], one after the other (as they are written). */
    private fun renamed(value: Any?, renames: List<Pair<String, String>>?): Any? =
        if (value !is String || renames == null) value else renames.fold(value) { v, (from, to) -> if (v == from) to else v }

    /** A theme's pictures ("lib:" + JSON) naming library pictures as [renames] renamed them, one after the other. */
    private fun renamedPictures(value: Any?, renames: List<Pair<String, String>>): Any? {
        if (renames.isEmpty() || (value as? String)?.startsWith("lib:") != true) return value
        return runCatching {
            val o = org.json.JSONObject(value.removePrefix("lib:"))
            for (slot in o.keys()) o.optJSONObject(slot)?.let { s -> s.optString("p").takeIf { it.isNotEmpty() }?.let { s.put("p", renamed(it, renames)) } }
            "lib:$o"
        }.getOrDefault(value)
    }

    /** The layout type whose default [key] is (Settings.PREF_LAYOUT_PREFIX + type), or null. */
    private fun defaultLayoutType(key: String): LayoutType? =
        if (!key.startsWith(Settings.PREF_LAYOUT_PREFIX)) null else LayoutType.entries.firstOrNull { key == Settings.PREF_LAYOUT_PREFIX + it.name }

    /** A font choice ("font:<file>") naming [fonts]' new name. */
    fun renamedFont(value: Any?, fonts: Map<String, String>): Any? {
        val name = (value as? String)?.takeIf { it.startsWith(FontLibrary.PREFIX) }?.removePrefix(FontLibrary.PREFIX) ?: return value
        return fonts[name]?.let { FontLibrary.PREFIX + it } ?: value
    }

    /** A theme's values with its fonts and library pictures named as they came in ([fonts], [pictures]). */
    private fun renamedFiles(values: Map<String, Any?>, fonts: Map<String, String>, pictures: Map<String, String>): Map<String, Any?> =
        values.mapValues { (key, value) -> when {
            key in FontLibrary.fontPrefs -> renamedFont(value, fonts)
            key == AppearanceLooks.PICTURES && pictures.isNotEmpty() && (value as? String)?.startsWith("lib:") == true -> runCatching {
                val o = org.json.JSONObject(value.removePrefix("lib:"))
                for (slot in o.keys()) o.optJSONObject(slot)?.let { s -> pictures[s.optString("p")]?.let { s.put("p", it) } }
                "lib:$o"
            }.getOrDefault(value)
            else -> value
        } }

    // ---- what uses what ----

    /** The backup items (kind, key) the settings [values] (a keyboard's, plain keys) name directly. */
    fun namedBy(plan: Plan, values: Map<String, Any?>): Set<Pair<Kind, String>> {
        val out = HashSet<Pair<Kind, String>>()
        fun add(kind: Kind, value: Any?) { (value as? String)?.let { name -> plan.item(kind, name)?.let { out += kind to it.key } } }
        add(Kind.POPUP_SETS, values[KeyPopupOverrides.PREF_SELECTED_SET])
        add(Kind.LAYOUTS, values[LayoutPresets.PREF_SELECTED])
        add(Kind.THEMES, values[AppearanceLooks.PREF_SELECTED])
        for (slot in Palettes.slots) add(Kind.PALETTES, values[slot])
        for (key in FontLibrary.fontPrefs) add(Kind.FONTS, (values[key] as? String)?.takeIf { it.startsWith(FontLibrary.PREFIX) }?.removePrefix(FontLibrary.PREFIX))
        return out
    }

    /** The backup items (kind, key) a backup Layout or theme ([item]) names: a Layout its popup set, a theme its palettes,
     *  fonts and library pictures. */
    fun namedByItem(plan: Plan, item: ItemPlan<*>): Set<Pair<Kind, String>> {
        val out = HashSet<Pair<Kind, String>>()
        fun add(kind: Kind, value: Any?) { (value as? String)?.let { name -> plan.of(kind).firstOrNull { it.after == name || it.key == name }?.let { out += kind to it.key } } }
        when (val c = item.content) {
            is LayoutPresets.Preset -> add(Kind.POPUP_SETS, c.values[KeyPopupOverrides.PREF_SELECTED_SET])
            is AppearanceLooks.Look -> {
                for (slot in Palettes.slots) add(Kind.PALETTES, c.values[slot])
                for (key in FontLibrary.fontPrefs) add(Kind.FONTS, (c.values[key] as? String)?.takeIf { it.startsWith(FontLibrary.PREFIX) }?.removePrefix(FontLibrary.PREFIX))
                (c.values[AppearanceLooks.PICTURES] as? String)?.takeIf { it.startsWith("lib:") }?.let { refs ->
                    runCatching { org.json.JSONObject(refs.removePrefix("lib:")) }.getOrNull()?.let { o ->
                        for (slot in o.keys()) o.optJSONObject(slot)?.optString("p")?.takeIf { it.isNotEmpty() }?.let { add(Kind.PICTURES, it) } } }
            }
        }
        return out
    }

    /** Whether the device keyboard's settings [values] (plain keys) name the device's item [name] of [kind] (pictures:
     *  only themes name them). */
    fun names(kind: Kind, name: String, values: Map<String, Any?>): Boolean = when (kind) {
        Kind.POPUP_SETS -> values[KeyPopupOverrides.PREF_SELECTED_SET] == name
        Kind.LAYOUTS -> values[LayoutPresets.PREF_SELECTED] == name
        Kind.THEMES -> values[AppearanceLooks.PREF_SELECTED] == name
        Kind.PALETTES -> Palettes.slots.any { values[it] == name }
        Kind.FONTS -> FontLibrary.fontPrefs.any { values[it] == FontLibrary.PREFIX + name }
        Kind.PICTURES -> false
    }
}

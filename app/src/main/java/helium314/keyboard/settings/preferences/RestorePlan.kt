// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.settings.screens.withKeyboardName

/**
 * What a restore of some keyboards does with each backup keyboard, as the restore dialog asked it (owner 2026-10-11:
 * "only check if language is same"; "first ask the user if they want to merge this restored keyboard with the existing
 * one? Give the option to pick either one if there are multiple (just one though, can't merge with multiple), or keep it
 * as a separate keyboard"). The real restore does exactly this (review R1: it matched again on its own, and a keyboard
 * kept apart could replace the phone's).
 */
internal sealed class RestoreDecision {
    /** [name]: the name box's text (null: the name it has, unchanged). */
    abstract val name: String?

    /** Into the phone's [phone]: the backup's [parts] and [menus] (null: all of them, the backup's whole keyboard), and
     *  with [learnedWords] its learned words (added to the phone's). */
    data class Merge(val phone: SettingsSubtype, val parts: Set<RestoreMatch.Part>? = null,
                     val menus: Set<KeyboardProfiles.Group>? = null, val learnedWords: Boolean = true,
                     override val name: String? = null) : RestoreDecision() {
        val replace get() = RestoreMatch.Replace(phone, parts, menus)
        val whole get() = parts == null && menus == null && learnedWords
    }

    /** A keyboard of its own, the backup's whole. */
    data class Separate(override val name: String? = null) : RestoreDecision()

    /** Not restored ("Nothing to restore"). */
    data object Skip : RestoreDecision() { override val name: String? get() = null }

    companion object {
        /**
         * [keyboard] named [text] as the Keyboards screen's Rename names it: its own name for the text, none for the
         * name its languages give (the Rename box empty) or a blank text; null [text]: as it is.
         */
        fun named(keyboard: SettingsSubtype, text: String?, nameOf: (SettingsSubtype) -> String): SettingsSubtype {
            if (text == null || text.trim() == nameOf(keyboard)) return keyboard
            val plain = keyboard.without(ExtraValue.KEYBOARD_NAME)
            return if (text.isBlank() || text.trim() == nameOf(plain)) plain else withKeyboardName(keyboard, text)
        }

        /** What the phone's keyboard becomes merged with [incoming] (the backup's keyboard as it comes in), before its name
         *  box: the ticked parts the backup's, its name and copy number the phone's, and its id (owner 2026-10-11: a merge
         *  keeps the phone keyboard's id). */
        fun merged(merge: Merge, incoming: SettingsSubtype): SettingsSubtype {
            val keyboard = RestoreMatch.definition(merge.replace, incoming)
            if (merge.parts != null) return keyboard.withIdOf(merge.phone).toPref().toSettingsSubtype()
            // (the backup's whole: under the phone keyboard's name, as the name box shows it)
            var kb = keyboard
            for (key in listOf(ExtraValue.KEYBOARD_NAME, ExtraValue.KEYBOARD_COPY))
                kb = merge.phone.getExtraValueOf(key)?.let { kb.with(key, it) } ?: kb.without(key)
            return kb.withIdOf(merge.phone).toPref().toSettingsSubtype() // (sorted, as stored)
        }

        /** The keyboard [decision] makes of [incoming] (null: not restored). */
        fun result(decision: RestoreDecision?, incoming: SettingsSubtype, nameOf: (SettingsSubtype) -> String): SettingsSubtype? = when (decision) {
            is Merge -> named(merged(decision, incoming), decision.name, nameOf)
            is Separate -> named(incoming, decision.name, nameOf)
            Skip -> null
            null -> incoming
        }
    }
}

/**
 * The restore's plan of a backup (no Compose screens here), made once when the backup is read; the restore dialog, its
 * questions and the restore run all read it, nothing is worked out again (owner 2026-10-12, the restore redesign: before,
 * the dialog planned the key layouts' names over every backup keyboard and the run again over the ticked ones only, and
 * the two could differ, M4). [incoming], each backup keyboard as it comes in (as in the backup: the backup's key layouts
 * keep their names, owner 2026-10-12); the device's keyboards, [enabled] and [switchedOff] (owner 2026-10-11: switched-off
 * keyboards are treated like switched-on ones); [nameOf], a keyboard's name as the screens show it. For "What to merge"
 * (review 2026-10-11 R3): [sameLayout], whether two key layouts of a type are the same keys (one name whose device file
 * the restore renames is not; an unsaved one compared by its keys); [backupSettings], a backup keyboard's settings with
 * its saved items named as the restore names them, and [phoneSettings], a device keyboard's, for the dialog's rows and
 * answers ([SavedItemsMerge.Choices]); [clashes], the saved items asked about; [dictionaryClashes], the languages (tags)
 * whose added dictionary in the backup differs from the device's (owner 2026-10-12: the backup's is selected, the
 * device's kept aside as "…_prev"). [items]: every saved item of the backup ([SavedItemsMerge.Plan]); [usedBy], the
 * backup keyboards using each (by kind and key; directly, or through a Layout or theme): "Required for"; [usedByItems],
 * the backup's Layouts and themes using each; [deviceUsers], the device keyboards using each device item (by kind and
 * name), which take the backup's when it is replaced. [unsavedSave]: the name a device keyboard's unsaved key layout is
 * saved as when a ticked row leaves it unused, or null (A1b); [unsavedDefaultSavesAfter], the same for one it reaches
 * through its own set's default (owner 2026-10-13, 20261013_M1). Both count the device keyboards that still name the
 * layout after the restore, by the other backup keyboards' answers (owner 2026-10-13, 20261013_L6).
 */
internal class RestorePlan(
    val incoming: Map<SettingsSubtype, SettingsSubtype>,
    val enabled: List<SettingsSubtype>,
    val switchedOff: List<SettingsSubtype>,
    val nameOf: (SettingsSubtype) -> String,
    val sameLayout: (helium314.keyboard.latin.utils.LayoutType, String, String) -> Boolean = { _, a, b -> a == b },
    val backupSettings: (SettingsSubtype, SavedItemsMerge.Choices) -> Map<String, Any?> = { _, _ -> emptyMap() },
    val phoneSettings: (Map<String, Any?>, SavedItemsMerge.Choices) -> Map<String, Any?> = { values, _ -> values },
    val clashes: List<SavedItemsMerge.Clash> = emptyList(),
    val dictionaryClashes: Set<String> = emptySet(),
    // the backup's dictionaries as the restore would bring each against the device's (R-DICT: the rows' subtexts)
    val dictionarySteps: List<RestoreDictionaries.Step> = emptyList(),
    val items: SavedItemsMerge.Plan = SavedItemsMerge.Plan(),
    val usedBy: Map<Pair<SavedItemsMerge.Kind, String>, List<SettingsSubtype>> = emptyMap(),
    val usedByItems: Map<Pair<SavedItemsMerge.Kind, String>, Set<Pair<SavedItemsMerge.Kind, String>>> = emptyMap(),
    val deviceUsers: Map<Pair<SavedItemsMerge.Kind, String>, List<SettingsSubtype>> = emptyMap(),
    // (both by the other backup keyboards' answers and whether settings come: another device keyboard counts as a user
    // only when it still names the layout after the restore, owner 2026-10-13, 20261013_L6)
    val unsavedSave: (SettingsSubtype, helium314.keyboard.latin.utils.LayoutType, String, String?, Map<SettingsSubtype, RestoreDecision>, Boolean) -> String? =
        { _, _, _, _, _, _ -> null },
    // the device keyboard's unsaved key layouts it reaches through its own set's defaults, saved as an own pick's are
    // when the merge of the backup keyboard takes that setting (owner 2026-10-13, 20261013_M1): by the device keyboard
    // and the backup's
    val unsavedDefaultSavesAfter: (SettingsSubtype, SettingsSubtype, Map<SettingsSubtype, RestoreDecision>, Boolean) -> List<DefaultSave> =
        { _, _, _, _ -> emptyList() },
) {
    /** An unsaved key layout [name] the device keyboard has as its own set's [type] default, the backup keyboard's
     *  default there ([backup], null: none), and the name its keys are saved as ([saved]) when a merge replaces that
     *  default (owner 2026-10-13, 20261013_M1: "exactly like an own pick", the same warning and temp_MMDD save). */
    data class DefaultSave(val type: helium314.keyboard.latin.utils.LayoutType, val name: String, val backup: String?, val saved: String)

    /** [unsavedSave] for the device's [target] ("What to merge"'s warnings), the other backup keyboards answered as
     *  [decisions] (the restore dialog's: settings come, [withSettings]; owner 2026-10-13, 20261013_L6: a keyboard
     *  another answer switches away from the layout is no user of it). */
    fun unsavedSaves(target: SettingsSubtype, decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap(),
                     withSettings: Boolean = true): (helium314.keyboard.latin.utils.LayoutType, String, String?) -> String? =
        { type, phone, backup -> unsavedSave(target, type, phone, backup, decisions, withSettings) }

    /** [unsavedDefaultSavesAfter] of the device's [target] merged with the backup's [keyboard], the other backup
     *  keyboards answered as [decisions] (20261013_L6). */
    fun unsavedDefaultSaves(target: SettingsSubtype, keyboard: SettingsSubtype, decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap(),
                            withSettings: Boolean = true): List<DefaultSave> = unsavedDefaultSavesAfter(target, keyboard, decisions, withSettings)

    val phone = (enabled + switchedOff).distinct()

    /** The languages of [keyboards] (their main and other languages, in order) whose dictionary clashes. */
    fun dictionaryClashLanguages(keyboards: Collection<SettingsSubtype>): List<String> =
        keyboards.flatMap { listOf(it.locale) + helium314.keyboard.latin.utils.getSecondaryLocales(it.extraValues) }
            .map { it.toLanguageTag() }.distinct().filter { it in dictionaryClashes }

    /** The device's dictionaries [keyboard]'s restore keeps aside for the backup's of other content, by language (tag)
     *  and name ("main_prev"), in its languages' order (R-DICT: "Dictionary differs: yours kept as main_prev"; only a
     *  clash's: a match's say so in [dictionariesSet], owner 2026-10-13, 20261013_L3). */
    fun dictionariesKept(keyboard: SettingsSubtype): List<Pair<String, String>> {
        val tags = backupKeyboardLanguages(listOf(keyboard))
        return tags.flatMap { tag -> dictionarySteps.filter { it.tag == tag && it.action == RestoreDictionaries.Action.CLASH }
            .mapNotNull { s -> s.kept?.let { tag to RestoreDictionaries.shownName(it) } } }
    }

    /** [keyboard]'s matching device copies the restore selects, in its languages' order; not one already in use, nothing
     *  changes then (owner 2026-10-13, 20261013_L3: "Backup's dictionary set as main, yours kept as main_prev"). */
    fun dictionariesSet(keyboard: SettingsSubtype): List<RestoreDictionaries.Step> {
        val tags = backupKeyboardLanguages(listOf(keyboard))
        return tags.flatMap { tag -> dictionarySteps.filter { it.tag == tag && it.action == RestoreDictionaries.Action.SAME && !it.unchanged } }
    }

    /** The backup's dictionaries of languages none of [keyboards] (the backup's, ticked or not) has: the row "Other
     *  dictionaries" (R-DICT), by language (tag), with the device's dictionaries each keeps aside for one of other
     *  content ("main_prev"). Not a language whose only ones are matches already in use: nothing would change (owner
     *  2026-10-13, 20261013_L3). */
    fun otherDictionaries(keyboards: Collection<SettingsSubtype>): Map<String, List<String>> {
        val tags = backupKeyboardLanguages(keyboards)
        val result = LinkedHashMap<String, MutableList<String>>()
        for (s in dictionarySteps) if (s.tag !in tags && !s.unchanged) result.getOrPut(s.tag) { ArrayList() }.apply {
            if (s.action == RestoreDictionaries.Action.CLASH) s.kept?.let { add(RestoreDictionaries.shownName(it)) } }
        return result
    }

    fun isSwitchedOff(keyboard: SettingsSubtype) = enabled.none { it.sameKeyboard(keyboard) }

    /** Whether the device's [phone] is the backup's [keyboard] itself, the same id: picked to start with on the first
     *  question, "Suggested: same keyboard as in the backup" (owner 2026-10-12; was a hint only, owner 2026-10-11). */
    fun sameKeyboard(keyboard: SettingsSubtype, phone: SettingsSubtype) = keyboard.keyboardId != null && keyboard.keyboardId == phone.keyboardId

    private fun sameLanguage(a: SettingsSubtype, b: SettingsSubtype) = a.locale.toLanguageTag() == b.locale.toLanguageTag()

    /** Whether [keyboard] is asked about: the phone has a keyboard of its language. Else it comes in as new. */
    fun asks(keyboard: SettingsSubtype) = phone.any { sameLanguage(it, keyboard) }

    /** The phone keyboards [keyboard] can merge into: its language, and not the one another backup keyboard merges
     *  into ([decisions]): one keyboard each. */
    fun candidates(keyboard: SettingsSubtype, decisions: Map<SettingsSubtype, RestoreDecision>): List<SettingsSubtype> {
        val taken = decisions.filterKeys { it != keyboard }.values.mapNotNull { (it as? RestoreDecision.Merge)?.phone }.toSet()
        return phone.filter { sameLanguage(it, keyboard) && taken.none { t -> t.sameKeyboard(it) } } // (by id)
    }

    /** The keyboard [decision] makes of [keyboard] (null: not restored). */
    fun result(keyboard: SettingsSubtype, decision: RestoreDecision?) =
        RestoreDecision.result(decision, incoming.getValue(keyboard), nameOf)

    /**
     * The phone's keyboards after the restore, but the one [keyboard] becomes: those no other backup keyboard merges into
     * (or [decision]'s own), and what the other [chosen] keyboards become ([decisions]; one not asked comes in as new).
     */
    fun others(keyboard: SettingsSubtype, decision: RestoreDecision, chosen: Collection<SettingsSubtype>,
               decisions: Map<SettingsSubtype, RestoreDecision>): List<SettingsSubtype> {
        val rest = chosen.filter { it != keyboard }
        val targets = rest.mapNotNull { (decisions[it] as? RestoreDecision.Merge)?.phone }.toSet() +
            listOfNotNull((decision as? RestoreDecision.Merge)?.phone)
        return phone.filter { p -> targets.none { it.sameKeyboard(p) } } + rest.mapNotNull { other ->
            if (other in decisions) result(other, decisions[other]) else if (!asks(other)) result(other, null) else null }
    }

    /** The name box's first text. Merged: the device keyboard's current name, never a numbered one (owner 2026-10-12:
     *  "yes, when merging the name should be current name"). Kept both: the backup keyboard's own name, the device's of
     *  that name becoming "… 2" in the restore (owner 2026-10-12: Keep both, "the backup's keeps its name"; this flips
     *  owner 2026-10-11 "same Name (or language in absence of name) makes it <name> 2", which numbered the backup's).
     *  ([chosen] and [decisions]: the other keyboards and their answers, not needed since the flip.) */
    @Suppress("UNUSED_PARAMETER")
    fun prefillName(keyboard: SettingsSubtype, decision: RestoreDecision, chosen: Collection<SettingsSubtype>,
                    decisions: Map<SettingsSubtype, RestoreDecision>): String = when (decision) {
        is RestoreDecision.Merge -> nameOf(decision.phone)
        is RestoreDecision.Separate -> nameOf(result(keyboard, RestoreDecision.Separate())!!)
        RestoreDecision.Skip -> ""
    }

    // (owner 2026-10-11, keyboards have unique ids: no "fully identical" block any more, a result set up like another
    // keyboard is another keyboard; its name box starts at the next free name, [prefillName])

    companion object {
        private val copyNumber = Regex("""^(.*) \((\d+)\)$""")
        private val plainNumber = Regex("""^(.*) (\d+)$""")

        /** [name], or when [taken] has it the next free one, counting up a number it ends with (owner 2026-10-11: "Yes,
         *  count up pls."): a copy's "English (US) (2)" gives "English (US) (3)", "Work 2" gives "Work 3", "Sunset"
         *  gives "Sunset 2". */
        fun nextFreeName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val copy = copyNumber.find(name)
            val plain = plainNumber.find(name)
            val first = (copy ?: plain)?.groupValues?.get(2)?.toInt()?.plus(1) ?: 2
            fun numbered(n: Int) = when {
                copy != null -> "${copy.groupValues[1]} ($n)"
                plain != null -> "${plain.groupValues[1]} $n"
                else -> "$name $n"
            }
            return generateSequence(first) { it + 1 }.map(::numbered).first { it !in taken }
        }
    }

    /**
     * The answer of "what to merge": [ticked] of [differences] (with [learnedRow], the learned and blacklisted words row, ticked:
     * [learnedTicked]) under [name]. Nothing ticked is not restored (owner 2026-10-11: "it essentially means nothing is
     * restored"); every one ticked, or nothing differing at all, is the backup's whole keyboard.
     */
    fun mergeDecision(phone: SettingsSubtype, differences: List<RestoreMatch.Difference>, ticked: Collection<RestoreMatch.Difference>,
                      learnedRow: Boolean, learnedTicked: Boolean, name: String?): RestoreDecision {
        val learned = learnedRow && learnedTicked
        if (differences.isEmpty() && !learnedRow) return RestoreDecision.Merge(phone, name = name)
        if (ticked.isEmpty() && !learned) return RestoreDecision.Skip
        if (ticked.containsAll(differences) && (learned || !learnedRow)) return RestoreDecision.Merge(phone, name = name)
        return RestoreDecision.Merge(phone, ticked.filterIsInstance<RestoreMatch.Difference.Definition>().mapTo(HashSet()) { it.part },
            ticked.filterIsInstance<RestoreMatch.Difference.Menu>().mapTo(HashSet()) { it.group }, learned, name)
    }
}

/** The first question for one backup keyboard: merge into which device keyboard, or keep both. Picked to start with
 *  (owner 2026-10-12: "the same-id candidate PRESELECTED", Next always enabled; overrides owner 2026-10-11 "nothing
 *  preselected"): the device's with the backup keyboard's id, "Suggested: same keyboard as in the backup"; with none,
 *  Keep both (it changes nothing of the device's keyboards but their names). */
internal class MergeQuestion(val plan: RestorePlan, val keyboard: SettingsSubtype, val chosen: Collection<SettingsSubtype>,
                             val decisions: Map<SettingsSubtype, RestoreDecision>) {
    /** The device keyboards it can merge into (then "Keep both"). */
    val options: List<SettingsSubtype> = plan.candidates(keyboard, decisions)
    var picked: Pick? by mutableStateOf(Pick(options.firstOrNull { plan.sameKeyboard(keyboard, it) }))
    /** The name box of "Keep both". */
    var separateName: String by mutableStateOf(plan.prefillName(keyboard, RestoreDecision.Separate(), chosen, decisions))

    /** Merge into [phone], or (null) keep both. */
    data class Pick(val phone: SettingsSubtype?)

    val separate get() = RestoreDecision.Separate(separateName)
    val canGoOn get() = picked != null
    /** Whether the device's [phone] is this backup keyboard itself (its id): "Suggested: same keyboard as in the backup". */
    fun isSameKeyboard(phone: SettingsSubtype) = plan.sameKeyboard(keyboard, phone)
}

/**
 * "What to merge" of [question]'s backup keyboard into the phone's [target]: [differences] each unticked (a ticked one
 * takes the backup's, an unticked one keeps the phone's), with [wordsRow] the learned and blacklisted words (one row:
 * the restore brings both together, ticked), and the name
 * box, which follows the ticks until it's typed in.
 */
internal class MergeChoice(val question: MergeQuestion, val target: SettingsSubtype, val differences: List<RestoreMatch.Difference>,
                           val wordsRow: Boolean) {
    val ticked = androidx.compose.runtime.mutableStateListOf<RestoreMatch.Difference>()
    // ticked to start with (owner 2026-10-11: "Yes learned and blacklisted words should both come, ticked on by default.
    // Not off."): words alone are something to restore, never "Nothing differs"
    var wordsTicked by mutableStateOf(wordsRow)
    var typed: String? by mutableStateOf(null)
    private val plan get() = question.plan

    /** Nothing differs and no words come: only Done. */
    val nothingDiffers get() = differences.isEmpty() && !wordsRow
    private fun decision(name: String?) = plan.mergeDecision(target, differences, ticked, wordsRow, wordsTicked, name)
    val name: String get() = typed ?: decision(null).let {
        if (it is RestoreDecision.Skip) "" else plan.prefillName(question.keyboard, it, question.chosen, question.decisions) }
    val decision get() = decision(name)

    /** The menus ticked ("<menu> settings" rows). */
    val tickedMenus get() = ticked.filterIsInstance<RestoreMatch.Difference.Menu>().map { it.group }

    /** The note on which ticked menus are common (R-4, owner 2026-10-11 wording), or null. */
    fun commonNote(ctx: Context): String? =
        if (decision is RestoreDecision.Skip) null else commonMenusNote(ctx, target, tickedMenus)

    /** The line near the top whenever something is ticked, else null (owner 2026-10-11: one line, no per-copy warnings:
     *  "If someone is overwriting items they should generally know what they're talking about"). */
    fun replacesNote(ctx: Context): String? =
        if (decision is RestoreDecision.Skip) null else ctx.getString(R.string.restore_replaces_settings)
}

/**
 * What "What to merge" says when ticked [menus] of the device's [target] are common to all keyboards (R-4, owner
 * 2026-10-11, mockup QvEiPwiBdyBCe8YUtXL7JJ; "for a non-first keyboard too you should communicate that you're indeed
 * restoring the backup, but settings won't show because common is selected"): menus shared (App settings › Per
 * keyboard), or all of them with separate settings off; [target] first in the list (its settings are the common ones)
 * or not. Null: none of them is common.
 */
internal fun commonMenusNote(ctx: Context, target: SettingsSubtype, menus: Collection<KeyboardProfiles.Group>): String? {
    if (menus.isEmpty()) return null
    val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
    val separate = KeyboardProfiles.isSeparate(real)
    val common = KeyboardProfiles.Group.entries.filter { it in menus && (!separate || KeyboardProfiles.isShared(real, it)) }
    if (common.isEmpty()) return null
    val names = common.joinToString(", ") { ctx.getString(helium314.keyboard.settings.screens.groupTitle(it)) }
    val first = KeyboardProfiles.mainKeyboard(real)
    val kb = helium314.keyboard.settings.screens.keyboardName(target, ctx)
    val res = ctx.resources
    return when {
        separate && target.sameKeyboard(first) -> res.getQuantityString(R.plurals.restore_common_first, common.size, names, kb)
        separate -> res.getQuantityString(R.plurals.restore_common_other, common.size, names,
            helium314.keyboard.settings.screens.keyboardName(first, ctx), kb)
        target.sameKeyboard(first) -> res.getQuantityString(R.plurals.restore_all_common_first, common.size, kb, names)
        else -> res.getQuantityString(R.plurals.restore_all_common_other, common.size,
            helium314.keyboard.settings.screens.keyboardName(first, ctx), names, kb)
    }
}

/**
 * The restore dialog's top choice (owner 2026-10-11: "At the top should be a checkbox that says "Delete app data and
 * restore from backup", checked by default. Which makes keyboard list greyed out. If unchecked, all keyboards'
 * checkboxes become non-grey, and selected"). Checked ([wipe]): the keyboard list and the rows of the keyboard by
 * keyboard restore are greyed out, the line under them is hidden, and OK asks before the full restore that wipes.
 * Unchecked: every backup keyboard is ticked the first time, the user's ticks kept when it's checked and unchecked
 * again; the line shows, and OK goes keyboard by keyboard. A backup without a keyboard list can only be restored
 * whole: checked, and the checkbox can't be unchecked ([canPickKeyboards]).
 */
internal class RestoreDialogState(private val keyboards: List<SettingsSubtype>) {
    enum class Ok { ConfirmWipe, KeyboardByKeyboard }

    val canPickKeyboards = keyboards.isNotEmpty()
    var wipe by mutableStateOf(true)
        private set
    /** The keyboards ticked for the keyboard by keyboard restore. */
    val selected = androidx.compose.runtime.mutableStateListOf<SettingsSubtype>()
    private var unchecked = false

    fun check(on: Boolean) {
        if (!on && !canPickKeyboards) return
        wipe = on
        if (!on && !unchecked) { unchecked = true; selected.clear(); selected.addAll(keyboards) }
    }

    /** The keyboard rows, and the rows that only mean something keyboard by keyboard, can be changed. */
    val rowsEnabled get() = !wipe
    /** The rows below the box show (owner 2026-10-12: "with the box ticked, hide every row below it"); "Tricky merging
     *  ahead!" only with a ticked row that needs merging ([RestoreFlow.trickyAhead]). */
    val showsMergeLine get() = !wipe
    /** A keyboard row's tick: greyed out, every keyboard comes back. */
    fun ticked(keyboard: SettingsSubtype) = wipe || keyboard in selected
    fun ok() = if (wipe) Ok.ConfirmWipe else Ok.KeyboardByKeyboard
}

/**
 * The restore dialog keyboard by keyboard (owner 2026-10-12, the restore redesign; no Compose screens here): screen 1's
 * rows, the backup's keyboards ([top]) and its saved items by section ([sections], names as in the backup), and the
 * questions after it ([questions]): one per ticked row that needs merging, the parts first, then the items using them,
 * then the keyboards, as the restore writes them (owner 2026-10-12 A4). Every row starts ticked; an item a ticked keyboard uses (or a ticked Layout
 * or theme) is ticked and can't be unticked, "Required for …" ([requiredFor]), and is free again once nothing ticked uses
 * it. The answers are kept through Back and through unticking: a row unticked drops its question, ticked again its
 * earlier answer comes back. All of it reads [plan], made once when the backup was read.
 */
internal class RestoreFlow(val plan: RestorePlan, val keyboards: List<SettingsSubtype>,
                           val hasClipboard: Boolean = false, val hasPersonalWords: Boolean = false) {
    val top = RestoreDialogState(keyboards)

    // ---- "Also restore", screen 1's last section (owner 2026-10-13, R-DICT): a row each the backup has, ticked to start
    /** The backup's dictionaries of languages no backup keyboard has, by language, with what each keeps aside. */
    val otherDictionaries = plan.otherDictionaries(keyboards)
    var otherDictionariesTicked by mutableStateOf(true)
    var clipboardTicked by mutableStateOf(true)
    var personalWordsTicked by mutableStateOf(true)
    /** The "Also restore" rows ticked: what each brings. */
    val bringsOtherDictionaries get() = !top.wipe && otherDictionaries.isNotEmpty() && otherDictionariesTicked
    val bringsClipboard get() = !top.wipe && hasClipboard && clipboardTicked
    val bringsPersonalWords get() = !top.wipe && hasPersonalWords && personalWordsTicked
    /** The items unticked on screen 1, by kind and backup key (a required one stays ticked whatever is here). */
    val unticked = androidx.compose.runtime.mutableStateListOf<Pair<SavedItemsMerge.Kind, String>>()
    /** The keyboard questions' answers, by backup keyboard. */
    private val keyboardAnswers = HashMap<SettingsSubtype, KeyboardAnswer>()
    /** The item questions' answers, by kind and backup key. */
    private val itemAnswers = HashMap<Pair<SavedItemsMerge.Kind, String>, SavedItemsMerge.Answer>()
    /** The question showing, by its place in [questions]; -1: screen 1. */
    var index by mutableStateOf(-1)
        private set
    /** The keyboard question showing is on its "What to merge". */
    var choosing by mutableStateOf(false)

    /** Screen 1's sections of saved items, in order (owner 2026-10-12). */
    class Section(val title: Int, val kinds: List<SavedItemsMerge.Kind>)
    val sections = listOf(
        Section(R.string.restore_section_layouts, listOf(SavedItemsMerge.Kind.LAYOUTS, SavedItemsMerge.Kind.POPUP_SETS)),
        Section(R.string.restore_section_themes, listOf(SavedItemsMerge.Kind.THEMES)),
        Section(R.string.restore_section_palettes, listOf(SavedItemsMerge.Kind.PALETTES, SavedItemsMerge.Kind.PICTURES)),
        Section(R.string.restore_section_fonts, listOf(SavedItemsMerge.Kind.FONTS)),
    )
    fun items(section: Section): List<SavedItemsMerge.ItemPlan<*>> = section.kinds.flatMap { plan.items.of(it) }

    /** The ticked keyboards, in the backup's order. */
    fun restoring(): List<SettingsSubtype> = if (top.wipe) emptyList() else keyboards.filter { it in top.selected }

    /** The ticked keyboards and Layouts or themes that use the item [key] (kind, backup key), by name: "Required for …"
     *  (owner 2026-10-12); empty: it can be unticked. */
    fun requiredFor(key: Pair<SavedItemsMerge.Kind, String>): List<String> {
        if (top.wipe) return emptyList()
        val byKeyboards = plan.usedBy[key].orEmpty().filter { it in top.selected }.map(plan.nameOf)
        // (a Layout or theme is used by no other item: ticked by its row, or required by a keyboard)
        val byItems = plan.usedByItems[key].orEmpty().filter { user -> user !in unticked || plan.usedBy[user].orEmpty().any { it in top.selected } }
            .mapNotNull { plan.items.item(it.first, it.second)?.name }
        return (byKeyboards + byItems).distinct()
    }

    /** Whether the item [key] comes in: required, or not unticked. */
    fun ticked(key: Pair<SavedItemsMerge.Kind, String>) = key !in unticked || requiredFor(key).isNotEmpty()

    fun tick(key: Pair<SavedItemsMerge.Kind, String>, on: Boolean) {
        if (on) unticked.remove(key) else if (key !in unticked) unticked.add(key)
    }

    /** A question: a backup keyboard's, or a saved item's (kind, backup key). */
    sealed class Question {
        data class Keyboard(val keyboard: SettingsSubtype) : Question()
        data class Item(val kind: SavedItemsMerge.Kind, val key: String) : Question()
    }

    /** One per ticked row that needs merging ("Merge required"): the parts (palettes, fonts, pictures, popup sets), then
     *  the items using them (Layouts, themes), then the keyboards, the order the restore writes them in, so a keyboard's
     *  "What to merge" shows the names the item answers give (owner 2026-10-12 A4: "asking sub-items, then items, then
     *  keyboards makes sense"; before, keyboards first, their previews assumed Replace for every item). */
    val questions: List<Question> get() =
        SavedItemsMerge.applyOrder.flatMap { plan.items.of(it) }.filter { it.mergeRequired && ticked(it.kind to it.key) }
            .map { Question.Item(it.kind, it.key) } + restoring().filter { plan.asks(it) }.map { Question.Keyboard(it) }

    /** Screen 1's Restore / Next can be tapped: the full restore ticked, or a keyboard, item or "Also restore" row ticked
     *  (owner 2026-10-12 A6: with every row unticked, Restore ran anyway and merged the clipboard, Android's personal
     *  dictionary and new dictionaries without asking; overrides "Restore enabled with nothing ticked"; 2026-10-13
     *  R-DICT: the "Also restore" rows count too). */
    val anythingTicked get() = top.wipe || restoring().isNotEmpty() ||
        sections.any { section -> items(section).any { ticked(it.kind to it.key) } } ||
        bringsOtherDictionaries || bringsClipboard || bringsPersonalWords

    /** "Tricky merging ahead!": the full restore unticked and a ticked row that needs merging (owner 2026-10-12). */
    val trickyAhead get() = !top.wipe && questions.isNotEmpty()

    val current: Question? get() = if (index < 0) null else questions.getOrNull(index)
    val isLast get() = index >= 0 && index == questions.size - 1

    /** Screen 1's Next: the first question; true when there is none (the restore runs). */
    fun begin(): Boolean {
        if (questions.isEmpty()) return true
        index = 0; choosing = false
        return false
    }

    /** A question's Next: the next one; true after the last one (its button is Restore: the restore runs). */
    fun next(): Boolean {
        if (index + 1 >= questions.size) return true
        index++; choosing = false
        return false
    }

    /** Back: from "What to merge" to its question, else to the question before (from the first: screen 1). Every answer
     *  and every tick is kept. */
    fun back() {
        if (choosing) { choosing = false; return }
        index = (index - 1).coerceAtLeast(-1)
    }

    // ---- the answers ----

    private fun answer(keyboard: SettingsSubtype) = keyboardAnswers.getOrPut(keyboard) { KeyboardAnswer() }

    /** The ticked keyboards' answers (one asked about and not answered yet has none). */
    fun decisions(): Map<SettingsSubtype, RestoreDecision> =
        restoring().filter { plan.asks(it) }.mapNotNull { kb -> keyboardAnswers[kb]?.decision?.let { kb to it } }.toMap()

    /** The rows and answers of the items, as the restore takes them. */
    fun choices(): SavedItemsMerge.Choices = SavedItemsMerge.Choices(unticked.filterTo(HashSet()) { !ticked(it) }, itemAnswers.toMap())

    /** [keyboard]'s first question, as it was last answered (a device keyboard picked that another keyboard merges into
     *  now: the start's pick again). */
    fun mergeQuestion(keyboard: SettingsSubtype): MergeQuestion {
        val question = MergeQuestion(plan, keyboard, restoring(), decisions() - keyboard)
        keyboardAnswers[keyboard]?.let { a ->
            a.pick?.takeIf { pick -> pick.phone == null || question.options.any { it.sameKeyboard(pick.phone) } }?.let { question.picked = it }
            a.separateName?.let { question.separateName = it }
        }
        return question
    }

    /** [question]'s answer kept (Next and Back); Keep both is its decision, a merge's comes with "What to merge". */
    fun keep(question: MergeQuestion) {
        val a = answer(question.keyboard)
        a.pick = question.picked
        a.separateName = question.separateName
        if (question.picked?.phone == null) a.decision = question.separate
    }

    /** "What to merge" of [question] into [target], with the ticks it had when last shown for the same differences. */
    fun mergeChoice(question: MergeQuestion, target: SettingsSubtype, differences: List<RestoreMatch.Difference>, wordsRow: Boolean): MergeChoice {
        val choice = MergeChoice(question, target, differences, wordsRow)
        keyboardAnswers[question.keyboard]?.merges?.get(target)?.takeIf { it.differences == differences && it.wordsRow == wordsRow }?.let { t ->
            choice.ticked.clear(); choice.ticked.addAll(t.ticked)
            choice.wordsTicked = t.wordsTicked
            choice.typed = t.typed
        }
        return choice
    }

    /** [choice]'s ticks kept (Next and Back); with [decide] (Next) its decision is the keyboard's. */
    fun keep(choice: MergeChoice, decide: Boolean) {
        val a = answer(choice.question.keyboard)
        a.merges[choice.target] = MergeTicks(choice.differences, choice.ticked.toList(), choice.wordsRow, choice.wordsTicked, choice.typed)
        if (decide) a.decision = choice.decision
    }

    /** The item question of [question], as it was last answered (none, or a device item an earlier question took since:
     *  the suggested answer), with the device items earlier questions took (review 2026-10-12 A2). */
    fun itemQuestion(question: Question.Item): ItemQuestion? = plan.items.item(question.kind, question.key)?.let { item ->
        // (the questions before it: the ticked items of its kind, in order, as answered)
        val answered = SavedItemsMerge.answers(plan.items.of(item.kind), choices())[item.key]
        ItemQuestion(item, answered?.answer, answered?.taken.orEmpty())
    }

    fun keep(question: ItemQuestion) { itemAnswers[question.item.kind to question.item.key] = question.picked }
}

/** A keyboard question's answer: its first question's pick and name box, its "What to merge" ticks by the device
 *  keyboard merged into, and the decision (kept through Back, owner 2026-10-12). */
internal class KeyboardAnswer {
    var pick: MergeQuestion.Pick? = null
    var separateName: String? = null
    val merges = HashMap<SettingsSubtype, MergeTicks>()
    var decision: RestoreDecision? = null
}

/** "What to merge"'s ticks, for the [differences] it showed. */
internal class MergeTicks(val differences: List<RestoreMatch.Difference>, val ticked: List<RestoreMatch.Difference>,
                          val wordsRow: Boolean, val wordsTicked: Boolean, val typed: String?)

/** A saved item's question (owner 2026-10-12): Replace one of its candidates on the device, or Keep both; [answer]: as
 *  answered before (none: the suggested one, [SavedItemsMerge.ItemPlan.suggested]); [taken]: the device items earlier
 *  questions merge into, by the backup item's name that took them, greyed out and not picked (review 2026-10-12 A2). */
internal class ItemQuestion(val item: SavedItemsMerge.ItemPlan<*>, answer: SavedItemsMerge.Answer?, val taken: Map<String, String> = emptyMap()) {
    var picked: SavedItemsMerge.Answer by mutableStateOf(answer ?: item.suggested(taken.keys))
    /** The answer picked to start with (its candidate's line says "Suggested"). */
    val suggested get() = item.suggested(taken.keys)
    /** Whether Keep both renames the device's of the item's name: not when an earlier question took it (A2). */
    val keepBothRenames get() = item.keepBothName != null && item.holder !in taken
}

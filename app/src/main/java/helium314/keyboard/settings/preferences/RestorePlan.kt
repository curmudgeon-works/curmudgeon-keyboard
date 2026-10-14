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
         *  box: the ticked parts the backup's, its name and copy number the phone's. */
        fun merged(merge: Merge, incoming: SettingsSubtype): SettingsSubtype {
            val keyboard = RestoreMatch.definition(merge.replace, incoming)
            if (merge.parts != null) return keyboard
            // (the backup's whole: under the phone keyboard's name, as the name box shows it)
            var kb = keyboard
            for (key in listOf(ExtraValue.KEYBOARD_NAME, ExtraValue.KEYBOARD_COPY))
                kb = merge.phone.getExtraValueOf(key)?.let { kb.with(key, it) } ?: kb.without(key)
            return kb.toPref().toSettingsSubtype() // (sorted, as stored)
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
 * The restore dialog's questions for a backup's keyboards (no Compose screens here): [incoming], each backup keyboard as
 * it would come in (its custom key layouts named as the restore names them); the phone's keyboards, [enabled] and
 * [switchedOff] (owner 2026-10-11: switched-off keyboards are treated like switched-on ones); [nameOf], a keyboard's
 * name as the screens show it.
 */
internal class RestorePlan(
    val incoming: Map<SettingsSubtype, SettingsSubtype>,
    val enabled: List<SettingsSubtype>,
    val switchedOff: List<SettingsSubtype>,
    val nameOf: (SettingsSubtype) -> String,
) {
    val phone = (enabled + switchedOff).distinct()

    fun isSwitchedOff(keyboard: SettingsSubtype) = keyboard !in enabled

    private fun sameLanguage(a: SettingsSubtype, b: SettingsSubtype) = a.locale.toLanguageTag() == b.locale.toLanguageTag()

    /** Whether [keyboard] is asked about: the phone has a keyboard of its language. Else it comes in as new. */
    fun asks(keyboard: SettingsSubtype) = phone.any { sameLanguage(it, keyboard) }

    /** The phone keyboards [keyboard] can merge into: its language, and not the one another backup keyboard merges
     *  into ([decisions]): one keyboard each. */
    fun candidates(keyboard: SettingsSubtype, decisions: Map<SettingsSubtype, RestoreDecision>): List<SettingsSubtype> {
        val taken = decisions.filterKeys { it != keyboard }.values.mapNotNull { (it as? RestoreDecision.Merge)?.phone }.toSet()
        return phone.filter { sameLanguage(it, keyboard) && it !in taken }
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
        return phone.filter { it !in targets } + rest.mapNotNull { other ->
            if (other in decisions) result(other, decisions[other]) else if (!asks(other)) result(other, null) else null }
    }

    /** The name box's first text: the keyboard's name ([decision] with its name unchanged), or the next free one when
     *  another keyboard after the restore has it ([nextFreeName], among the keyboards [sameAs] compares with; owner
     *  2026-10-11: "same Name (or language in absence of name) makes it <name> 2"). */
    fun prefillName(keyboard: SettingsSubtype, decision: RestoreDecision, chosen: Collection<SettingsSubtype>,
                    decisions: Map<SettingsSubtype, RestoreDecision>): String {
        val unnamed = when (decision) {
            is RestoreDecision.Merge -> decision.copy(name = null)
            is RestoreDecision.Separate -> RestoreDecision.Separate()
            RestoreDecision.Skip -> return ""
        }
        val name = nameOf(result(keyboard, unnamed)!!)
        return nextFreeName(name, others(keyboard, decision, chosen, decisions).map(nameOf).toSet())
    }

    /** The other keyboard [decision] would make [keyboard] the same as (the same definition: one identity for two,
     *  blocked; owner 2026-10-11: "Yes, keep fully identical block"), or null. */
    fun sameAs(keyboard: SettingsSubtype, decision: RestoreDecision, chosen: Collection<SettingsSubtype>,
               decisions: Map<SettingsSubtype, RestoreDecision>): SettingsSubtype? {
        val result = result(keyboard, decision) ?: return null
        return others(keyboard, decision, chosen, decisions).firstOrNull { it == result }
    }

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

/** The first question for one backup keyboard: merge into which phone keyboard, or keep it apart; nothing picked to
 *  start with (owner 2026-10-11: "nothing preselected. Else we'll go into rabbit hole of what it means to be closest"). */
internal class MergeQuestion(val plan: RestorePlan, val keyboard: SettingsSubtype, val chosen: Collection<SettingsSubtype>,
                             val decisions: Map<SettingsSubtype, RestoreDecision>) {
    /** The phone keyboards it can merge into (then "Keep as a separate keyboard"). */
    val options: List<SettingsSubtype> = plan.candidates(keyboard, decisions)
    var picked: Pick? by mutableStateOf(null)
    /** The name box of "Keep as a separate keyboard". */
    var separateName: String by mutableStateOf(plan.prefillName(keyboard, RestoreDecision.Separate(), chosen, decisions))

    /** Merge into [phone], or (null) keep it apart. */
    data class Pick(val phone: SettingsSubtype?)

    val separate get() = RestoreDecision.Separate(separateName)
    /** The keyboard keeping it apart would be the same as (blocked), or null. */
    val separateSameAs get() = plan.sameAs(keyboard, separate, chosen, decisions)
    val canGoOn get() = picked.let { it != null && (it.phone != null || separateSameAs == null) }
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
    /** The keyboard it would be the same as (blocked), or null. */
    val sameAs get() = decision.let { if (it is RestoreDecision.Skip) null else plan.sameAs(question.keyboard, it, question.chosen, question.decisions) }

    /** The line near the top whenever something is ticked, else null (owner 2026-10-11: one line, no per-copy warnings:
     *  "If someone is overwriting items they should generally know what they're talking about"). */
    fun replacesNote(ctx: Context): String? =
        if (decision is RestoreDecision.Skip) null else ctx.getString(R.string.restore_replaces_settings)
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
    /** "App data won't be deleted. Each selected keyboard will be merged or added, …" */
    val showsMergeLine get() = !wipe
    /** A keyboard row's tick: greyed out, every keyboard comes back. */
    fun ticked(keyboard: SettingsSubtype) = wipe || keyboard in selected
    fun ok() = if (wipe) Ok.ConfirmWipe else Ok.KeyboardByKeyboard
}

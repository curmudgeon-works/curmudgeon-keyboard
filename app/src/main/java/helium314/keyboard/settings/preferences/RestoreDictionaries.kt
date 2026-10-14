// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The backup's added dictionaries in a partial restore (owner 2026-10-12, "DICTIONARY CHOICE" and "RESTORE DIALOG v3"),
 * per language (its folder "dicts/<tag>") and kind (the file name's start, "main" of "main_user.dict"):
 * - the backup's dictionary of a kind is the one it had selected ([inUse]); one the backup didn't use stays out;
 * - the same content anywhere for that language and kind on the device: nothing ([Action.SAME]);
 * - none of that kind on the device: it comes in ([Action.NEW]);
 * - content differs: the backup's comes in and is selected, the device's of that name is renamed on disk to
 *   "…_prev" (the next free one: "_prev2" …), the ".user.dict" ending kept so the app still lists it, and stays
 *   selectable ([Action.CLASH]).
 * A language no restored keyboard has gets its NEW ones silently, never a CLASH (the device's stays).
 * The selection (per language and kind, app-wide; its owner is the dictionary dialog and the engine): [selectedKey] =
 * the selected file's name or [INTERNAL]; no value: the engine uses "<kind>_user.dict".
 */
internal object RestoreDictionaries {
    const val INTERNAL = "internal"
    private val suffix = "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX

    /** The app-wide selection of language [tag]'s dictionary of [kind] (e.g. "dict_selected_hi_main"). */
    fun selectedKey(tag: String, kind: String) = "dict_selected_${tag}_$kind"

    fun kindOf(name: String) = name.substringBefore("_")

    /** The name the engine uses when nothing is selected: "main_user.dict". */
    fun defaultName(kind: String) = kind + suffix

    /** The [n]th kept-aside name: "main_prev_user.dict", then "main_prev2_user.dict" … */
    fun prevName(kind: String, n: Int) = kind + "_prev" + (if (n <= 1) "" else n.toString()) + suffix

    /** A backup's dictionary file, "dicts/<tag>/<name>". */
    data class Entry(val tag: String, val name: String) {
        val kind get() = kindOf(name)
    }

    /** The dictionary in the backup [path] (as the zip names it, "unprotected/" removed), null if it isn't one. */
    fun entryOf(path: String): Entry? {
        if (!path.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)) return null
        val parts = path.split(File.separator)
        if (parts.size != 3 || parts[0] != "dicts") return null
        val (_, tag, name) = parts
        if (tag.isEmpty() || tag == "." || tag == ".." || name.isEmpty() || !name.contains("_")) return null
        return Entry(tag, name)
    }

    /** The dictionary of [kind] that [selected] (the selection's value, null: none) leaves in use among [names]: the
     *  selected file, else the default name; null: the built-in one. */
    fun inUse(selected: String?, kind: String, names: Collection<String>): String? {
        if (selected == INTERNAL) return null
        if (selected != null && selected in names) return selected
        return defaultName(kind).takeIf { it in names }
    }

    enum class Action { SAME, NEW, CLASH }

    /** What the restore does with the backup's [backupName] of [tag] and its kind: [target], its name on the device;
     *  [renames], the device files moved aside first (from -> to); [select], whether it is selected after. */
    data class Step(val tag: String, val kind: String, val backupName: String, val action: Action, val target: String,
                    val renames: List<Pair<String, String>> = emptyList()) {
        val select get() = action == Action.CLASH
    }

    /**
     * The step for the backup's [backupName] (content [backupDigest]) of [tag], against [device]: the device's files of
     * that language, name -> digest (any kind; the plan looks at its kind only).
     */
    fun plan(tag: String, backupName: String, backupDigest: String, device: Map<String, String>): Step {
        val kind = kindOf(backupName)
        val sameKind = device.filterKeys { kindOf(it) == kind }
        val target = defaultName(kind)
        if (sameKind.values.any { it == backupDigest }) return Step(tag, kind, backupName, Action.SAME, target)
        if (sameKind.isEmpty()) return Step(tag, kind, backupName, Action.NEW, target)
        // (the device's of the default name, the one in use unless another was selected, moved aside; one already
        // under another name stays as it is, selectable)
        val renames = if (target in device) {
            val prev = generateSequence(1) { it + 1 }.map { prevName(kind, it) }.first { it !in device }
            listOf(target to prev)
        } else emptyList()
        return Step(tag, kind, backupName, Action.CLASH, target, renames)
    }

    /** The steps for the backup's dictionaries of each language: [backup], each file's digest by entry; the backup's
     *  selections in [backupPrefs]; [device], a language's files on the device (name -> digest). One step per language
     *  and kind: the backup's dictionary in use (none when it used the built-in one). */
    fun plan(backup: Map<Entry, String>, backupPrefs: Map<String, Any?>, device: (String) -> Map<String, String>): List<Step> {
        val steps = ArrayList<Step>()
        for ((tag, entries) in backup.keys.groupBy { it.tag }) {
            val onDevice = device(tag)
            for ((kind, ofKind) in entries.groupBy { it.kind }) {
                val names = ofKind.map { it.name }
                val name = inUse(backupPrefs[selectedKey(tag, kind)] as? String, kind, names) ?: continue
                steps += plan(tag, name, backup.getValue(Entry(tag, name)), onDevice)
            }
        }
        return steps
    }

    /** The languages of [steps] whose dictionary clashes (the restore dialog's subtext). */
    fun clashTags(steps: List<Step>): Set<String> = steps.filter { it.action == Action.CLASH }.mapTo(LinkedHashSet()) { it.tag }

    /** Carries out [step] in the language's folder [dir], the backup's file read from [source]; with [real], selects
     *  it on a clash. */
    fun apply(step: Step, dir: File, source: File, real: SharedPreferences) {
        if (step.action == Action.SAME) return
        dir.mkdirs()
        for ((from, to) in step.renames) {
            val f = File(dir, from)
            if (f.isFile && !File(dir, to).exists()) f.renameTo(File(dir, to))
        }
        val target = File(dir, step.target)
        // (never outside the folder, whatever the zip says)
        if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return
        source.copyTo(target, overwrite = true)
        if (step.select) real.edit(commit = true) { putString(selectedKey(step.tag, step.kind), step.target) }
    }

    /** The digests of a language folder's added dictionaries. */
    fun deviceDigests(dir: File): Map<String, String> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }
            ?.associate { f -> f.name to f.inputStream().use { digest(it) } }.orEmpty()

    fun digest(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

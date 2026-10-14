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
 * - the same content anywhere for that language and kind on the device ([Action.SAME]): that copy is selected and
 *   renamed to the backup's name, a device file of that name with other content moved aside to the first free
 *   "…_prev" ("_prev2" …) once the copy has left its own name (B3b: no gap; the copy steps aside under a free "…_prev"
 *   too, 20261013_L7_2) (owner 2026-10-12 B4: "When backups dict matches, yes select that copy and also rename that
 *   copy to backups"; before, nothing, and a match kept aside, e.g. "emoji_prev", stayed unused);
 * - none of that kind on the device: it comes in ([Action.NEW]);
 * - content differs: the backup's comes in and is selected, the device's of that name is renamed on disk to
 *   "…_prev" (the next free one: "_prev2" …), the ".user.dict" ending kept so the app still lists it, and stays
 *   selectable ([Action.CLASH]).
 * A language no restored keyboard has gets its NEW ones silently, never a CLASH nor a SAME's selection (the device's
 * stays).
 * The selection (per language and kind, app-wide; its owner is the dictionary dialog and the engine): [selectedKey] =
 * the selected file's name or [INTERNAL]; no value: the engine uses "<kind>_user.dict".
 */
internal object RestoreDictionaries {
    const val INTERNAL = "internal"
    /** The backup's copy while it waits beside the device's (not listed either). */
    private const val RESTORING = ".restoring"
    private val suffix = "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX

    /** The app-wide selection of language [tag]'s dictionary of [kind] (e.g. "dict_selected_hi_main"). */
    fun selectedKey(tag: String, kind: String) = "dict_selected_${tag}_$kind"

    fun kindOf(name: String) = name.substringBefore("_")

    /** A dictionary file's name as the dialogs show it: "main_prev" of "main_prev_user.dict". */
    fun shownName(name: String) = name.removeSuffix(suffix)

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
     *  [renames], the device files renamed first, in order (from -> to; SAME: the device's matching copy last, to
     *  [target]); [select], whether [target] is selected after; [kept], the device's dictionary of that kind the
     *  backup's takes over from, by its name after (a clash: the one moved to "…_prev", else the one already under
     *  another name; a match: the one moved aside for it), null when none (R-DICT: the restore dialog and summary say it);
     *  [unchanged], a match already under the backup's name and in use on the device: nothing changes, and the dialog
     *  and summary say nothing of it (owner 2026-10-13, 20261013_L3). */
    data class Step(val tag: String, val kind: String, val backupName: String, val action: Action, val target: String,
                    val renames: List<Pair<String, String>> = emptyList(), val kept: String? = null, val unchanged: Boolean = false) {
        val select get() = action == Action.CLASH || action == Action.SAME
    }

    /** The first kept-aside name of [kind] not among [taken]. */
    private fun nextPrev(kind: String, taken: Collection<String>) =
        generateSequence(1) { it + 1 }.map { prevName(kind, it) }.first { it !in taken }

    /**
     * The step for the backup's [backupName] (content [backupDigest]) of [tag], against [device]: the device's files of
     * that language, name -> digest (any kind; the plan looks at its kind only); [selected], the device's selection for
     * that language and kind (null: none).
     */
    fun plan(tag: String, backupName: String, backupDigest: String, device: Map<String, String>, selected: String? = null): Step {
        val kind = kindOf(backupName)
        val sameKind = device.filterKeys { kindOf(it) == kind }
        val target = defaultName(kind)
        val same = sameKind.filterValues { it == backupDigest }.keys
        if (same.isNotEmpty()) {
            // (B4: the copy of the backup's name if one matches, else the first matching by name, takes the backup's
            // name, the device's of that name with other content moved aside first; then selected)
            if (backupName in same) return Step(tag, kind, backupName, Action.SAME, backupName,
                unchanged = inUse(selected, kind, device.keys) == backupName)
            val match = same.sorted().first()
            // (the one of that name moved to the first prev free once the match has left its own name, review B3b: picked
            // before, "main_prev" matching left "main_prev" empty and the old "main" went to "main_prev2"; so the match
            // steps aside first when that prev is its own name, under the first free prev: only listed names, so a restore
            // killed between two renames leaves every file listed and selectable, nothing to recover (round-6 review
            // 20261013_L7_2, owner 2026-10-13; before, "<name>.moving", unlisted, and a recovery that named it "…_prev2"
            // with "main" free)
            val renames = if (backupName in device) {
                val kept = nextPrev(kind, device.keys - match)
                if (kept != match) listOf(backupName to kept, match to backupName)
                else nextPrev(kind, device.keys).let { aside -> listOf(match to aside, backupName to match, aside to backupName) }
            } else listOf(match to backupName)
            return Step(tag, kind, backupName, Action.SAME, backupName, renames, kept = renames.firstOrNull { it.first == backupName }?.second)
        }
        if (sameKind.isEmpty()) return Step(tag, kind, backupName, Action.NEW, target)
        // (the device's of the default name, the one in use unless another was selected, moved aside; one already
        // under another name stays as it is, selectable)
        val renames = if (target in device) listOf(target to nextPrev(kind, device.keys)) else emptyList()
        return Step(tag, kind, backupName, Action.CLASH, target, renames, kept = renames.firstOrNull()?.second ?: sameKind.keys.sorted().first())
    }

    /** The steps for the backup's dictionaries of each language: [backup], each file's digest by entry; the backup's
     *  selections in [backupPrefs]; [device], a language's files on the device (name -> digest); [deviceSelected], the
     *  device's selection by language and kind. One step per language and kind: the backup's dictionary in use (none when
     *  it used the built-in one). */
    fun plan(backup: Map<Entry, String>, backupPrefs: Map<String, Any?>, device: (String) -> Map<String, String>,
             deviceSelected: (String, String) -> String? = { _, _ -> null }): List<Step> {
        val steps = ArrayList<Step>()
        for ((tag, entries) in backup.keys.groupBy { it.tag }) {
            val onDevice = device(tag)
            for ((kind, ofKind) in entries.groupBy { it.kind }) {
                val names = ofKind.map { it.name }
                val name = inUse(backupPrefs[selectedKey(tag, kind)] as? String, kind, names) ?: continue
                steps += plan(tag, name, backup.getValue(Entry(tag, name)), onDevice, deviceSelected(tag, kind))
            }
        }
        return steps
    }

    /** The languages of [steps] whose dictionary clashes (the restore dialog's subtext). */
    fun clashTags(steps: List<Step>): Set<String> = steps.filter { it.action == Action.CLASH }.mapTo(LinkedHashSet()) { it.tag }

    /** Carries out [step] in the language's folder [dir], the backup's file read from [source] (SAME: not read, the
     *  device's copy is renamed); with [real], selects it on a clash or a match. Returns whether it was carried out. */
    fun apply(step: Step, dir: File, source: File, real: SharedPreferences, selectNew: Boolean = false): Boolean {
        val target = File(dir, step.target)
        // (never outside the folder, whatever the zip says)
        fun inside(f: File) = f.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        if (!inside(target)) return false
        dir.mkdirs()
        // the backup's is copied in beside first, under a name the app doesn't list: nothing of the device's moves before
        // the copy succeeded (review L3.4)
        val incoming = if (step.action == Action.SAME) null else File(dir, step.target + RESTORING).also {
            it.delete()
            try { source.copyTo(it, overwrite = true) } catch (e: Exception) { it.delete(); throw e }
        }
        // a rename that can't be done (or fails) puts back the ones done before it, and nothing more happens: no file
        // is written over or left under a name the app doesn't list (review B3b, L3.4's spirit)
        val done = ArrayList<Pair<File, File>>()
        for ((from, to) in step.renames) {
            val f = File(dir, from)
            val t = File(dir, to)
            if (inside(f) && inside(t) && f.isFile && !t.exists() && f.renameTo(t)) { done += f to t; continue }
            for ((a, b) in done.asReversed()) if (!a.exists()) b.renameTo(a)
            incoming?.delete()
            return false
        }
        if (incoming == null) {
            if (!target.isFile) return false // (the rename failed: the selection stays as it was)
        } else {
            // a device file still under the target's name (its rename failed) is never written over: it stays in use
            if (target.exists()) { incoming.delete(); return false }
            if (!incoming.renameTo(target)) {
                try { helium314.keyboard.latin.utils.AtomicFiles.copy(incoming, target) } finally { incoming.delete() } // (B4w.3)
            }
        }
        // ([selectNew]: a new one selected too, over a built-in one the device selected; R-DICT, the other dictionaries)
        if (step.select || (selectNew && step.action == Action.NEW)) real.edit(commit = true) { putString(selectedKey(step.tag, step.kind), step.target) }
        return true
    }

    /**
     * What a restore killed mid-step left in the language folders under [root] (owner 2026-10-13, 20261013_L5): the
     * backup's copy "<name>.restoring" is deleted (only the backup's: nothing of the device's lost). Run when the restore
     * dialog opens, before planning. (A device dictionary stepped aside under "<name>.moving" was listed again here too,
     * until its renames went through listed names only: round-6 review 20261013_L7_2, owner 2026-10-13.)
     */
    fun recoverLeftovers(root: File) {
        for (dir in root.listFiles()?.filter { it.isDirectory }.orEmpty())
            for (f in dir.listFiles()?.filter { it.isFile && it.name.endsWith(RESTORING) }.orEmpty()) f.delete()
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

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.CustomLabels

/**
 * Restoring some keyboards (review C4, owner "yes"): a restored keyboard's "Custom N" labels and kept palette copies
 * ("~custom:N") come in under the next free number on the phone when the phone uses that number for another copy
 * already, so two different copies never both show "Custom 1". Plain counters, as [CustomLabels.number] and
 * [KeyboardTheme.nextHiddenPalette] give them. [phone]: the phone's stored settings without the sets of the keyboards
 * being replaced by the restore.
 */
internal object RestoredCustomCopies {
    private val colorPrefixes = listOf(Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX)
    private val colorChoices = listOf(Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT)
    private val hiddenNumber = Regex(Regex.escape(KeyboardTheme.HIDDEN_PALETTE) + "(\\d+)")

    /** The restored keyboards' settings ([restored], plain keys) with each Custom label number another set on the
     *  phone has for that kind replaced by the next free one. */
    fun <K> labels(phone: Map<String, Any?>, restored: Map<K, Map<String, Any?>>): Map<K, Map<String, Any?>> {
        var result = restored
        for (kind in CustomLabels.Kind.entries) {
            val onPhone = phone.filterKeys { (KeyboardProfiles.splitOwnKey(it)?.second ?: it) == kind.key }.values.filterIsInstance<Int>().toSet()
            val numbers = restored.values.mapNotNull { (it[kind.key] as? Int)?.takeIf { n -> n > 0 } }
            val taken = (onPhone + numbers).toMutableSet()
            val kept = HashSet<Int>()
            result = result.mapValues { (_, values) ->
                val n = (values[kind.key] as? Int)?.takeIf { it > 0 } ?: return@mapValues values
                if (n !in onPhone && kept.add(n)) return@mapValues values
                val next = generateSequence(1) { it + 1 }.first { it !in taken }
                taken += next
                values + (kind.key to next)
            }
        }
        return result
    }

    /** The colours of kept palette [name] in [values] (plain keys), by prefix. */
    private fun colors(values: Map<String, Any?>, name: String) =
        colorPrefixes.mapNotNull { p -> values[p + name]?.let { p to it } }.toMap()

    private fun numbersIn(texts: Iterable<String>) = texts.flatMap { t -> hiddenNumber.findAll(t).map { it.groupValues[1].toInt() } }.toSet()

    /**
     * New names for the kept palettes in [backup] (the restored keyboards' settings and the saved themes the restore
     * brings in, plain keys): a "~custom:N" the phone ([phone], and its saved themes [phoneThemes]) uses with other
     * colours, or names without them, gets the next free "~custom:M"; one the phone has with the same colours, or
     * doesn't use, keeps its name.
     */
    fun paletteRenames(phone: Map<String, Any?>, phoneThemes: List<Map<String, Any?>>, backup: List<Map<String, Any?>>): Map<String, String> {
        val backupNames = backup.flatMap { values -> numbersIn(values.keys + values.values.filterIsInstance<String>()) }.toSortedSet()
        if (backupNames.isEmpty()) return emptyMap()
        // the phone's sets, plain keys, and its saved themes
        val phoneSets = phone.entries.groupBy({ KeyboardProfiles.splitOwnKey(it.key)?.first ?: KeyboardProfiles.SHARED },
            { (KeyboardProfiles.splitOwnKey(it.key)?.second ?: it.key) to it.value }).values.map { it.toMap() } + phoneThemes
        val onPhone = numbersIn(phoneSets.flatMap { values -> values.keys + values.values.filterIsInstance<String>() })
        val taken = (onPhone + backupNames).toMutableSet()
        val renames = HashMap<String, String>()
        for (n in backupNames) {
            if (n !in onPhone) continue
            val name = KeyboardTheme.HIDDEN_PALETTE + n
            val mine = backup.map { colors(it, name) }.firstOrNull { it.isNotEmpty() } ?: emptyMap()
            val theirs = phoneSets.map { colors(it, name) }.filter { it.isNotEmpty() }
            if (theirs.isNotEmpty() && theirs.all { it == mine }) continue // (the same copy: the phone's)
            val next = generateSequence(1) { it + 1 }.first { it !in taken }
            taken += next
            renames[name] = KeyboardTheme.HIDDEN_PALETTE + next
        }
        return renames
    }

    /** [values] (plain keys) with the kept palettes renamed by [renames]: their colour keys and the light/dark choices. */
    fun renamePalettes(values: Map<String, Any?>, renames: Map<String, String>): Map<String, Any?> {
        if (renames.isEmpty()) return values
        return values.entries.associate { (key, value) ->
            val renamedKey = colorPrefixes.firstNotNullOfOrNull { p -> renames[key.removePrefix(p)]?.takeIf { key.startsWith(p) }?.let { p + it } } ?: key
            renamedKey to if (key in colorChoices && value is String) renames[value] ?: value else value
        }
    }
}

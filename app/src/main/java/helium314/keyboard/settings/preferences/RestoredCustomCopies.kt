// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings

/**
 * Restoring some keyboards (review C4, owner "yes"): a restored keyboard's kept palette copies ("~custom:N") come in
 * under the next free number on the phone when the phone uses that number for another copy already, so two different
 * palettes never share a name. Plain counters, as [KeyboardTheme.nextHiddenPalette] gives them. The "Custom" labels
 * of Layouts, themes and popups come in as they are: no number (owner 2026-10-11).
 */
internal object RestoredCustomCopies {
    private val colorPrefixes = listOf(Settings.PREF_USER_COLORS_PREFIX, Settings.PREF_USER_ALL_COLORS_PREFIX, Settings.PREF_USER_MORE_COLORS_PREFIX)
    private val colorChoices = listOf(Settings.PREF_THEME_COLORS, Settings.PREF_THEME_COLORS_NIGHT)
    private val hiddenNumber = Regex(Regex.escape(KeyboardTheme.HIDDEN_PALETTE) + "(\\d+)")

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

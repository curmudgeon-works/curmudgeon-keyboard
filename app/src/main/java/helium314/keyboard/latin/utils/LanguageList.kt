// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.ScriptUtils.script
import java.util.Locale

/**
 * The simple-mode view of languages: one flat list, each language Off or at a priority, no subtypes to pick.
 * Underneath it still drives subtypes, which is what the keyboard runs on: languages of the same script
 * share one keyboard whose main language is the highest-priority one and the others are its
 * multilingual-typing secondaries; each script gets its own keyboard (the language key switches between them).
 */
object LanguageList {
    const val OFF = 0

    /** All languages the list offers: enabled ones first, then those with a dictionary, then every language with a layout. */
    fun languages(context: Context): List<Locale> {
        val locales = LinkedHashSet<Locale>()
        SubtypeSettings.getEnabledSubtypes(true).forEach { subtype ->
            locales.add(subtype.locale())
            locales.addAll(getSecondaryLocales(subtype.extraValue))
        }
        val withDictionary = getDictionaryLocales(context)
        locales.addAll(withDictionary)
        locales.addAll(SubtypeSettings.getAvailableSubtypeLocales())
        return locales.sortedWith(compareBy({ priority(context, it) == OFF }, { it !in withDictionary },
            { it.localizedDisplayName(context.resources) }))
    }

    fun hasDictionary(context: Context, locale: Locale) = locale in getDictionaryLocales(context)

    /** [LanguagePriority] of an enabled language, [OFF] if it is in no enabled keyboard. */
    fun priority(context: Context, locale: Locale): Int {
        val enabled = SubtypeSettings.getEnabledSubtypes(true).any { subtype ->
            subtype.locale() == locale || locale in getSecondaryLocales(subtype.extraValue)
        }
        return if (enabled) LanguagePriority.get(context.prefs(), locale) else OFF
    }

    fun setPriority(context: Context, locale: Locale, priority: Int) {
        val prefs = context.prefs()
        val on = languages(context).filter { priority(context, it) != OFF }.toMutableSet()
        if (priority == OFF) on.remove(locale) else {
            LanguagePriority.set(prefs, locale, priority)
            on.add(locale)
        }
        applyToSubtypes(context, on)
    }

    /**
     * Rebuild the enabled keyboards from the set of languages that are on: one per script, main language =
     * highest priority (an existing keyboard's main language wins a tie, so nothing changes without reason),
     * its layout and other settings kept when it already had a keyboard.
     */
    private fun applyToSubtypes(context: Context, on: Set<Locale>) {
        val prefs = context.prefs()
        val enabledBefore = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
        val selectedBefore = SubtypeSettings.getSelectedSubtype(prefs).toSettingsSubtype()
        val wanted = ArrayList<SettingsSubtype>()
        for ((_, group) in on.groupBy { it.script() }) {
            val main = group.maxWithOrNull(compareBy(
                { LanguagePriority.get(prefs, it) },
                { locale -> enabledBefore.any { it.locale == locale } },
                { -it.toLanguageTag().length } // stable tie-break
            )) ?: continue
            val secondaries = group.filter { it != main }.sortedBy { it.toLanguageTag() }
            val existing = enabledBefore.firstOrNull { it.locale == main }
                ?: SubtypeUtilsAdditional.createDefaultSubtype(main).toSettingsSubtype()
            wanted.add(
                if (secondaries.isEmpty()) existing.without(ExtraValue.SECONDARY_LOCALES)
                else existing.with(ExtraValue.SECONDARY_LOCALES, secondaries.joinToString(Separators.KV) { it.toLanguageTag() })
            )
        }
        // replace: keyboards that changed are edited in place (keeps their selection and per-app memory), the rest added / removed
        for (before in enabledBefore) {
            val after = wanted.firstOrNull { it.locale == before.locale }
            if (after == null) SubtypeSettings.removeEnabledSubtype(context, before.toAdditionalSubtype())
            else if (after != before) SubtypeUtilsAdditional.changeAdditionalSubtype(before, after, context)
        }
        for (after in wanted) {
            if (enabledBefore.none { it.locale == after.locale }) {
                SubtypeUtilsAdditional.changeAdditionalSubtype(after, after, context) // registers it as additional subtype unless it equals a built-in one
                SubtypeSettings.addEnabledSubtype(prefs, after.toAdditionalSubtype())
            }
        }
        // the selected keyboard may be gone
        if (SubtypeSettings.getEnabledSubtypes(true).none { it.toSettingsSubtype() == selectedBefore })
            SubtypeSettings.getEnabledSubtypes(true).firstOrNull()?.let { SubtypeSettings.setSelectedSubtype(prefs, it) }
        SubtypeSettings.reloadEnabledSubtypes(context)
    }
}

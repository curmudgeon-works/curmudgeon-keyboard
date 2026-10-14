// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.displayNameId
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.settings.KnownDefaults
import helium314.keyboard.settings.REFINE_MENU_SHOWN

/**
 * A backup's keyboard and this phone's in a restore of some keyboards (review item C1, owner 2026-10-11). The same
 * keyboard is the one of the same language, custom name and copy number, whatever its keys, languages or accents now:
 * restoring it replaces the phone's (before, a keyboard changed since the backup counted as new, and came in as a second
 * one). When the two differ, the restore asks; "Choose what to replace" lists only what differs: each part of the
 * keyboard's definition, and each menu whose settings differ. A ticked item takes the backup's, an unticked one keeps
 * the phone's. No Compose here: the restore dialog only shows what these give.
 */
internal object RestoreMatch {

    /** A part of a keyboard's definition taken on its own: an extra value ([Extra]), or one layout of its set ([Layout]). */
    sealed class Part {
        data class Extra(val key: String) : Part()
        data class Layout(val type: LayoutType) : Part()
    }

    /** What differs between the phone's keyboard and the backup's. */
    sealed class Difference {
        /** A part of the definition, with its value on the phone and in the backup (null: not set). */
        data class Definition(val part: Part, val phone: String?, val backup: String?) : Difference()
        /** The settings of a menu. */
        data class Menu(val group: KeyboardProfiles.Group) : Difference()
    }

    /**
     * How a backup keyboard already on the phone ([phone]) is restored: [parts] and [menus] null = the backup's whole
     * keyboard ("Replace with the backup's"); else the phone's keyboard with only the ticked parts and menus the backup's.
     */
    class Replace(val phone: SettingsSubtype, val parts: Set<Part>? = null, val menus: Set<KeyboardProfiles.Group>? = null) {
        val whole get() = parts == null && menus == null
        fun takes(part: Part) = parts == null || part in parts
        fun takes(menu: KeyboardProfiles.Group) = menus == null || menu in menus
    }

    /** The same keyboard: same language, same custom name (none = none), same copy number (none = none). */
    fun sameKeyboard(a: SettingsSubtype, b: SettingsSubtype): Boolean =
        a.locale.toLanguageTag() == b.locale.toLanguageTag()
            && a.getExtraValueOf(ExtraValue.KEYBOARD_NAME) == b.getExtraValueOf(ExtraValue.KEYBOARD_NAME)
            && a.getExtraValueOf(ExtraValue.KEYBOARD_COPY) == b.getExtraValueOf(ExtraValue.KEYBOARD_COPY)

    /**
     * Each of the [backup]'s keyboards that is on the phone ([phone]), with the phone's: an identical one first, then a
     * same keyboard with the same keys, then any same keyboard; a phone keyboard is matched once at most (two English
     * keyboards without names on both sides pair up by their keys). The others are new.
     */
    fun match(backup: List<SettingsSubtype>, phone: List<SettingsSubtype>): Map<SettingsSubtype, SettingsSubtype> {
        val result = LinkedHashMap<SettingsSubtype, SettingsSubtype>()
        val free = phone.toMutableList()
        val passes = listOf<(SettingsSubtype, SettingsSubtype) -> Boolean>(
            { a, b -> a == b },
            { a, b -> sameKeyboard(a, b) && a.layoutName(LayoutType.MAIN) == b.layoutName(LayoutType.MAIN) },
            ::sameKeyboard,
        )
        for (pass in passes) for (keyboard in backup) {
            if (keyboard in result) continue
            val onPhone = free.firstOrNull { pass(keyboard, it) } ?: continue
            result[keyboard] = onPhone
            free.remove(onPhone)
        }
        return result
    }

    // the extra values by key, as written ("Key=value", or "Key" for a flag)
    private fun entries(keyboard: SettingsSubtype): Map<String, String> =
        keyboard.extraValues.split(",").filter { it.isNotBlank() }.associateBy { it.substringBefore("=") }

    // the parts a keyboard's screens change (the name and copy number are its identity; the layout set is compared layout
    // by layout; the rest, such as the language's own flags, isn't the user's: it comes with a whole replace only)
    private val firstParts = listOf(ExtraValue.SECONDARY_LOCALES, ExtraValue.MORE_POPUPS, ExtraValue.POPUP_ORDER)
    private val laterParts = listOf(ExtraValue.HINT_ORDER, ExtraValue.LOCALIZED_NUMBER_ROW)

    /** What differs in the definitions: the keys, the languages, accents, popup order, the other layouts, hint order and
     *  the localized number row. */
    fun definitionDifferences(phone: SettingsSubtype, backup: SettingsSubtype): List<Difference.Definition> {
        val out = mutableListOf<Difference.Definition>()
        fun layout(type: LayoutType) {
            val p = phone.layoutName(type); val b = backup.layoutName(type)
            if (p != b) out += Difference.Definition(Part.Layout(type), p, b)
        }
        val p = entries(phone); val b = entries(backup)
        fun extra(key: String) {
            if (p[key] != b[key]) out += Difference.Definition(Part.Extra(key), p[key]?.substringAfter("=", ""), b[key]?.substringAfter("=", ""))
        }
        layout(LayoutType.MAIN)
        firstParts.forEach(::extra)
        LayoutType.entries.filter { it != LayoutType.MAIN }.forEach(::layout)
        laterParts.forEach(::extra)
        return out
    }

    /** The menus the settings screens show (Fine-tune suggestions and learning only where they're shown). */
    fun shownMenus(): List<KeyboardProfiles.Group> = KeyboardProfiles.Group.entries.filter {
        REFINE_MENU_SHOWN || (it != KeyboardProfiles.Group.REFINE && it != KeyboardProfiles.Group.LEARNING)
    }

    /** The shown menus [key] is on (the number of suggestions is on Text correction while Fine-tune isn't shown). */
    fun menusOf(key: String): Set<KeyboardProfiles.Group> {
        val shown = shownMenus()
        val menus = KeyboardProfiles.groupsOf(key).filterTo(HashSet()) { it in shown }
        if (!REFINE_MENU_SHOWN && key == Settings.PREF_SUGGESTION_COUNT) menus.add(KeyboardProfiles.Group.TEXT_CORRECTION)
        return menus
    }

    /** The menus whose settings differ between [phone] and [backup] (plain keys, no marks; not stored = the default). */
    fun menuDifferences(phone: Map<String, Any?>, backup: Map<String, Any?>): List<Difference.Menu> {
        val differing = HashSet<KeyboardProfiles.Group>()
        for (key in phone.keys + backup.keys) {
            if (key.startsWith(KeyboardProfiles.TOMBSTONE)) continue
            val menus = menusOf(key)
            if (menus.isEmpty() || differing.containsAll(menus)) continue
            if (!KnownDefaults.same(key, phone[key], backup[key])) differing.addAll(menus)
        }
        return shownMenus().filter { it in differing }.map { Difference.Menu(it) }
    }

    /** What the phone's keyboard [keyboard] reads now: the shared set with its own on top, plain keys. */
    fun phoneSettings(real: SharedPreferences, keyboard: SettingsSubtype): Map<String, Any?> =
        KeyboardProfiles.effectiveSettingsIn(real.all, keyboard)

    /** Everything that differs: the definitions, and with [withSettings] the menus' settings ([phoneSettings] and
     *  [backupSettings]). Empty: nothing to ask. */
    fun differences(phone: SettingsSubtype, backup: SettingsSubtype, withSettings: Boolean,
                    phoneSettings: () -> Map<String, Any?>, backupSettings: () -> Map<String, Any?>): List<Difference> =
        definitionDifferences(phone, backup) + if (withSettings) menuDifferences(phoneSettings(), backupSettings()) else emptyList()

    /** The definition the replaced keyboard gets: the backup's whole, or the phone's with the ticked parts the backup's. */
    fun definition(replace: Replace, backup: SettingsSubtype): SettingsSubtype {
        val parts = replace.parts ?: return backup
        var keyboard = replace.phone
        val b = entries(backup)
        for (part in parts) keyboard = when (part) {
            is Part.Extra -> b[part.key]?.let { if (it.contains('=')) keyboard.with(part.key, it.substringAfter("=")) else keyboard.with(part.key) }
                ?: keyboard.without(part.key)
            is Part.Layout -> backup.layoutName(part.type)?.let { keyboard.withLayout(part.type, it) } ?: keyboard.withoutLayout(part.type)
        }
        return keyboard.toPref().toSettingsSubtype() // (sorted, as stored)
    }

    /** The settings the replaced keyboard gets: the [backup]'s for the ticked menus (all for a whole replace), the
     *  [phone]'s for the rest, and for settings of no menu. */
    fun settings(replace: Replace, phone: Map<String, Any?>, backup: Map<String, Any?>): Map<String, Any?> {
        val menus = replace.menus ?: return backup
        fun taken(key: String) = menusOf(key).any { it in menus }
        return phone.filterKeys { !taken(it) } + backup.filterKeys { taken(it) }
    }

    // ---- what the dialogs say ----

    /** A row of "Choose what to replace": what it is, with the phone's and the backup's value. */
    fun rowText(ctx: Context, difference: Difference, backup: SettingsSubtype): String = when (difference) {
        is Difference.Menu -> ctx.getString(R.string.restore_differs_menu, ctx.getString(helium314.keyboard.settings.screens.groupTitle(difference.group)))
        is Difference.Definition -> {
            val label = when (val part = difference.part) {
                is Part.Layout -> if (part.type == LayoutType.MAIN) ctx.getString(R.string.restore_part_keys) else ctx.getString(part.type.displayNameId)
                is Part.Extra -> when (part.key) {
                    ExtraValue.SECONDARY_LOCALES -> ctx.getString(R.string.restore_part_languages)
                    ExtraValue.MORE_POPUPS -> ctx.getString(R.string.restore_part_accents)
                    ExtraValue.POPUP_ORDER -> ctx.getString(R.string.restore_part_popup_order)
                    ExtraValue.HINT_ORDER -> ctx.getString(R.string.restore_part_hint_order)
                    ExtraValue.LOCALIZED_NUMBER_ROW -> ctx.getString(R.string.localized_number_row)
                    else -> part.key
                }
            }
            val phone = valueText(ctx, difference.part, difference.phone, backup)
            val inBackup = valueText(ctx, difference.part, difference.backup, backup)
            if (phone == inBackup) ctx.getString(R.string.restore_differs_plain, label)
            else ctx.getString(R.string.restore_differs_row, label, phone, inBackup)
        }
    }

    private fun valueText(ctx: Context, part: Part, value: String?, keyboard: SettingsSubtype): String = when (part) {
        is Part.Layout -> when {
            value == null -> ctx.getString(R.string.restore_value_default)
            LayoutUtilsCustom.isCustomLayout(value) -> LayoutUtilsCustom.getDisplayName(value)
            part.type == LayoutType.MAIN -> SubtypeLocaleUtils.getLayoutDisplayNameInSystemLocale(value, keyboard.locale)
            else -> value
        }
        is Part.Extra -> when {
            part.key == ExtraValue.SECONDARY_LOCALES ->
                (listOf(keyboard.locale) + value.let { if (it.isNullOrEmpty()) emptyList() else getSecondaryLocales("${ExtraValue.SECONDARY_LOCALES}=$it") })
                    .joinToString(" + ") { it.localizedDisplayName(ctx.resources) }
            value == null -> ctx.getString(R.string.restore_value_general)
            part.key == ExtraValue.MORE_POPUPS -> ctx.getString(morePopupKeysResId(value))
            part.key == ExtraValue.POPUP_ORDER || part.key == ExtraValue.HINT_ORDER -> ctx.getString(R.string.restore_value_own)
            part.key == ExtraValue.LOCALIZED_NUMBER_ROW ->
                ctx.getString(if (value.toBoolean()) R.string.restore_value_on else R.string.restore_value_off)
            value.isEmpty() -> ctx.getString(R.string.restore_value_on)
            else -> value
        }
    }
}

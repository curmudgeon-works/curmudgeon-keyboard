// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import android.os.Build
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_LAYOUT_SET
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.toExtraValue
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.locale
import java.util.Locale

// some kind of intermediate between the string stored in preferences and an InputMethodSubtype
// todo: consider using a hashMap or sortedMap instead of a string if we run into comparison issues once again
data class SettingsSubtype(val locale: Locale, val extraValues: String) {

    fun toPref() = locale.toLanguageTag() + Separators.SET + extraValues

    /** Creates an additional subtype from the SettingsSubtype.
     *  Resulting InputMethodSubtypes are equal if SettingsSubtypes are equal */
    fun toAdditionalSubtype(): InputMethodSubtype {
        val asciiCapable = locale.script() == ScriptUtils.SCRIPT_LATIN
        return SubtypeUtilsAdditional.createAdditionalSubtype(locale, extraValues, asciiCapable, true)
    }

    fun mainLayoutName() = LayoutType.getMainLayoutFromExtraValue(extraValues)

    fun layoutName(type: LayoutType) = LayoutType.getLayoutMap(getExtraValueOf(KEYBOARD_LAYOUT_SET) ?: "")[type]

    fun with(extraValueKey: String, extraValue: String? = null): SettingsSubtype {
        val newList = extraValues.split(",")
            .filterNot { it.isBlank() || it.startsWith("$extraValueKey=") || it == extraValueKey }
        val newValue = if (extraValue == null) extraValueKey else "$extraValueKey=$extraValue"
        val newValues = (newList + newValue).sorted().joinToString(",")
        return copy(extraValues = newValues)
    }

    fun without(extraValueKey: String): SettingsSubtype {
        val newValues = extraValues.split(",")
            .filterNot { it.isBlank() || it.startsWith("$extraValueKey=") || it == extraValueKey }
            .joinToString(",")
        return copy(extraValues = newValues)
    }

    fun getExtraValueOf(extraValueKey: String): String? = extraValues.getExtraValueOf(extraValueKey)

    fun hasExtraValueOf(extraValueKey: String): Boolean = extraValues.hasExtraValueOf(extraValueKey)

    fun withLayout(type: LayoutType, name: String): SettingsSubtype {
        val map = LayoutType.getLayoutMap(getExtraValueOf(KEYBOARD_LAYOUT_SET) ?: "")
        map[type] = name
        return with(KEYBOARD_LAYOUT_SET, map.toExtraValue())
    }

    fun withoutLayout(type: LayoutType): SettingsSubtype {
        val map = LayoutType.getLayoutMap(getExtraValueOf(KEYBOARD_LAYOUT_SET) ?: "")
        map.remove(type)
        return if (map.isEmpty()) without(KEYBOARD_LAYOUT_SET)
        else with(KEYBOARD_LAYOUT_SET, map.toExtraValue())
    }

    // (a keyboard with an id: by its id, whatever its definition now; owner 2026-10-11, keyboards have unique ids)
    fun isAdditionalSubtype(prefs: SharedPreferences) =
        prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!
            .split(Separators.SETS).any { it.isNotEmpty() && it.toSettingsSubtype().sameKeyboard(this) }

    fun isSameAsDefault() = SubtypeSettings.getResourceSubtypesForLocale(locale).any { it.toSettingsSubtype() == this.toPref().toSettingsSubtype() }

    fun toEnabledSubtype(): InputMethodSubtype? =
        SubtypeSettings.getEnabledSubtypes().firstOrNull { it.toSettingsSubtype().sameKeyboard(this) }

    /** The keyboard's id (owner 2026-10-11: "do IDs now"), null for a definition without one (a built-in keyboard of the
     *  language list). Equality stays the definition: [sameKeyboard] is "this keyboard". */
    val keyboardId: String? get() = getExtraValueOf(ExtraValue.KEYBOARD_ID)

    /** The id of the keyboard this one came from (a copy's original), null if none: kept, not used. */
    val keyboardFrom: String? get() = getExtraValueOf(ExtraValue.KEYBOARD_FROM)

    /** Whether [other] is the same keyboard: the same id; two definitions without one, the same definition. */
    fun sameKeyboard(other: SettingsSubtype?): Boolean {
        if (other == null) return false
        val id = keyboardId
        val otherId = other.keyboardId
        return if (id != null || otherId != null) id == otherId else this == other
    }

    /** This definition with keyboard id [id] and came-from [from] (none: no KeyboardFrom). */
    fun withKeyboardId(id: String, from: String? = null): SettingsSubtype =
        with(ExtraValue.KEYBOARD_ID, id).let { if (from == null) it.without(ExtraValue.KEYBOARD_FROM) else it.with(ExtraValue.KEYBOARD_FROM, from) }

    /** This definition with the id and came-from of [keyboard] (what a change of a keyboard keeps). */
    fun withIdOf(keyboard: SettingsSubtype): SettingsSubtype {
        val id = keyboard.keyboardId ?: return without(ExtraValue.KEYBOARD_ID).without(ExtraValue.KEYBOARD_FROM)
        return withKeyboardId(id, keyboard.keyboardFrom)
    }

    /** The definition without its id and came-from: what the keyboard is set up as. */
    fun withoutIds(): SettingsSubtype = without(ExtraValue.KEYBOARD_ID).without(ExtraValue.KEYBOARD_FROM)

    companion object {
        fun String.toSettingsSubtype(): SettingsSubtype =
            SettingsSubtype(
                substringBefore(Separators.SET).constructLocale(),
                // we want a sorted string for reliable comparison
                substringAfter(Separators.SET).split(",").sorted().joinToString(",")
            )

        fun String.getExtraValueOf(extraValueKey: String) = split(",")
            .firstOrNull { it.startsWith("$extraValueKey=") }?.substringAfter("$extraValueKey=")

        fun String.hasExtraValueOf(extraValueKey: String) = split(",")
            .any { it.startsWith("$extraValueKey=") || it == extraValueKey }

        /** Creates a SettingsSubtype from the given InputMethodSubtype.
         *  Will strip some extra values that are set when creating the InputMethodSubtype from SettingsSubtype */
        fun InputMethodSubtype.toSettingsSubtype(): SettingsSubtype {
            if (DebugFlags.DEBUG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && locale().toLanguageTag() == "und") {
                @Suppress("deprecation") // it's debug logging, better get all information
                (Log.e(
                    SettingsSubtype::class.simpleName,
                    "unknown language, should not happen ${locale}, $languageTag, $extraValue, ${hashCode()}, $nameResId"
                ))
            }
            val filteredExtraValue = extraValue.split(",").filterNot {
                it.isBlank()
                        || it == ExtraValue.ASCII_CAPABLE
                        || it == ExtraValue.EMOJI_CAPABLE
                        || it == ExtraValue.IS_ADDITIONAL_SUBTYPE
                        || it.startsWith(ExtraValue.UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME)
            }.sorted().joinToString(",")
            require(!filteredExtraValue.contains(Separators.SETS) && !filteredExtraValue.contains(Separators.SET))
            { "extra value contains not allowed characters $filteredExtraValue" }
            return SettingsSubtype(locale(), filteredExtraValue)
        }
    }
}

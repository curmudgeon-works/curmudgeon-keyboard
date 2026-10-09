// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.widget.Toast
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.keyboard.internal.keyboard_parser.addLocaleKeyTextsToParams
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.KEYBOARD_LAYOUT_SET
import helium314.keyboard.latin.common.decodeBase36
import helium314.keyboard.latin.common.encodeBase36
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType.Companion.folder
import helium314.keyboard.latin.utils.ScriptUtils.script
import kotlinx.serialization.SerializationException
import java.io.File
import java.util.EnumMap
import java.util.Locale

object LayoutUtilsCustom {

    fun checkLayout(layoutContent: String, context: Context): Boolean {
        if (Settings.getValues() == null)
            Settings.getInstance().loadSettings(context)
        val params = KeyboardParams()
        params.mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardId.ELEMENT_ALPHABET)
        params.mPopupKeyTypes.add(POPUP_KEYS_LAYOUT)
        addLocaleKeyTextsToParams(context, params, POPUP_KEYS_NORMAL)
        try {
            if (layoutContent.trimStart().startsWith("[") || layoutContent.trimStart().startsWith("//")) {
                val keys = LayoutParser.parseJsonString(layoutContent).map { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }
                return checkKeys(keys)
            }
        } catch (e: SerializationException) {
            Log.w(TAG, "json parsing error", e)
            if (layoutContent.trimEnd().endsWith("]") && layoutContent.contains("},"))
                return false // we're sure enough it's a json
        } catch (e: Exception) {
            Log.w(TAG, "json layout parsed, but considered invalid", e)
            return false
        }
        try {
            val keys = LayoutParser.parseSimpleString(layoutContent).map { row -> row.map { it.toKeyParams(params) } }
            return checkKeys(keys)
        } catch (e: Exception) { Log.w(TAG, "error parsing custom simple layout", e) }
        if (layoutContent.trimStart().startsWith("[") && layoutContent.trimEnd().endsWith("]")) {
            // layout can't be loaded, assume it's json -> load json layout again because the error message shown to the user is from the most recent error
            try {
                LayoutParser.parseJsonString(layoutContent).map { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }
            } catch (e: Exception) { Log.w(TAG, "json parsing error", e) }
        }
        return false
    }

    fun checkKeys(keys: List<List<Key.KeyParams>>): Boolean {
        if (keys.isEmpty() || keys.any { it.isEmpty() }) {
            Log.w(TAG, "empty rows")
            return false
        }
        if (keys.size > 8) {
            Log.w(TAG, "too many rows")
            return false
        }
        if (keys.any { row -> row.size > 20 }) {
            Log.w(TAG, "too many keys in one row")
            return false
        }
        if (keys.any { row -> row.any {
                if ((it.mLabel?.length ?: 0) > 20) {
                    Log.w(TAG, "too long text on key: ${it.mLabel}")
                    true
                } else false
            } }) {
            return false
        }
        if (keys.any { row -> row.any {
                if ((it.mPopupKeys?.size ?: 0) > 20) {
                    Log.w(TAG, "too many popup keys on key ${it.mLabel}")
                    true
                } else false
            } }) {
            return false
        }
        if (keys.any { row -> row.any { true == it.mPopupKeys?.any { popupKey ->
                if ((popupKey.mLabel?.length ?: 0) > 10) {
                    Log.w(TAG, "too long text on popup key: ${popupKey.mLabel}")
                    true
                } else false
            } } }) {
            return false
        }
        return true
    }

    fun getLayoutFiles(layoutType: LayoutType, context: Context, locale: Locale? = null): List<File> {
        val layouts = customLayoutMap.getOrPut(layoutType) {
            File(DeviceProtectedUtils.getFilesDir(context), layoutType.folder).listFiles()?.toList() ?: emptyList()
        }
        if (layoutType != LayoutType.MAIN || locale == null)
            return layouts
        if (locale.script() == ScriptUtils.SCRIPT_LATIN)
            return layouts.filter { it.name.startsWith(CUSTOM_LAYOUT_PREFIX + ScriptUtils.SCRIPT_LATIN + ".") }
        return layouts.filter { it.name.startsWith(CUSTOM_LAYOUT_PREFIX + locale.toLanguageTag() + ".") }
    }

    fun onLayoutFileChanged() {
        customLayoutMap.clear()
    }

    /**
     * Deleting a layout takes its name off the list, not its keys from the keyboards using it: each of them gets a copy
     * of its own, unnamed ("Unsaved layout", offered to no other keyboard), which goes when that keyboard does or picks
     * another layout. Keyboards that only used it as the default for its type go back to the built-in one.
     */
    fun deleteLayout(layoutName: String, layoutType: LayoutType, context: Context) {
        val prefs = context.prefs()
        val users = (SubtypeSettings.createSettingsSubtypes(prefs.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
            + SubtypeSettings.createSettingsSubtypes(prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!))
            .distinct().filter { it.layoutName(layoutType) == layoutName }
        for (keyboard in users)
            SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, keyboard.withLayout(layoutType, makePrivateCopy(layoutName, layoutType, context)), context)
        getLayoutFile(layoutName, layoutType, context).delete()
        onLayoutFileChanged()
        SubtypeSettings.onRenameLayout(layoutType, layoutName, null, context)
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    fun getDisplayName(layoutName: String) =
        if (isPrivateLayout(layoutName))
            Settings.getCurrentContext()?.getString(helium314.keyboard.latin.R.string.unsaved_layout) ?: "Unsaved layout"
        else try {
            if (layoutName.count { it == '.' } == 3) // main layout: "custom.<locale or script>.<name>.", other: custom.<name>.
                decodeBase36(layoutName.substringAfter(CUSTOM_LAYOUT_PREFIX).substringAfter(".").substringBeforeLast("."))
            else decodeBase36(layoutName.substringAfter(CUSTOM_LAYOUT_PREFIX).substringBeforeLast("."))
        } catch (_: NumberFormatException) {
            layoutName
        }

    /** @return layoutName for given [displayName]. If [layoutType ]is MAIN, non-null [locale] must be supplied */
    fun getLayoutName(displayName: String, layoutType: LayoutType, locale: Locale? = null): String {
        if (layoutType != LayoutType.MAIN)
            return CUSTOM_LAYOUT_PREFIX + encodeBase36(displayName) + "."
        if (locale == null) throw IllegalArgumentException("locale for main layout not specified")
        return if (locale.script() == ScriptUtils.SCRIPT_LATIN)
            CUSTOM_LAYOUT_PREFIX + ScriptUtils.SCRIPT_LATIN + "." + encodeBase36(displayName) + "."
        else CUSTOM_LAYOUT_PREFIX + locale.toLanguageTag() + "." + encodeBase36(displayName) + "."
    }

    fun isCustomLayout(layoutName: String) = layoutName.startsWith(CUSTOM_LAYOUT_PREFIX)

    /** A keyboard's own copy of a deleted layout: "custom.<scope>.~<n>." (main) or "custom.~<n>." */
    fun isPrivateLayout(layoutName: String) = isCustomLayout(layoutName) && layoutName.contains(".$PRIVATE_MARK")

    /** The layouts a picker offers: the named ones, plus [current] when it's the keyboard's own unnamed copy. */
    fun listedLayoutNames(layoutType: LayoutType, context: Context, locale: Locale? = null, current: String? = null): List<String> =
        getLayoutFiles(layoutType, context, locale).map { it.name }.filterNot { isPrivateLayout(it) } +
            listOfNotNull(current?.takeIf { isPrivateLayout(it) })

    /** A private copy of [layoutName] (same keys), for one keyboard; returns its name. */
    fun makePrivateCopy(layoutName: String, layoutType: LayoutType, context: Context): String =
        makePrivateLayout(getLayoutFile(layoutName, layoutType, context).readText(), layoutType, scopeOf(layoutName, layoutType), context)

    /** [keyboard] with its own copies of the private (unnamed) layouts it names: for a copy of a keyboard, so editing one
     *  keyboard's keys doesn't change the other's (re-review 2026-10-07: copies shared the source's file). */
    fun withOwnPrivateLayouts(keyboard: helium314.keyboard.latin.settings.SettingsSubtype, context: Context): helium314.keyboard.latin.settings.SettingsSubtype {
        var kb = keyboard
        for (type in LayoutType.entries) {
            val name = kb.layoutName(type) ?: continue
            if (isPrivateLayout(name) && getLayoutFile(name, type, context).isFile) kb = kb.withLayout(type, makePrivateCopy(name, type, context))
        }
        return kb
    }

    /** For a main layout: what its name is scoped to ("latn" for latin-script languages, else the language tag). */
    fun scopeOf(layoutName: String, layoutType: LayoutType) =
        if (layoutType == LayoutType.MAIN) layoutName.removePrefix(CUSTOM_LAYOUT_PREFIX).substringBefore(".") else ""

    /** The scope a main layout for [locale] has (see [scopeOf]). */
    fun scopeFor(locale: Locale) = scopeOf(getLayoutName("x", LayoutType.MAIN, locale), LayoutType.MAIN)

    /** A private layout (one keyboard's, unnamed) with [text] as its keys; returns its name. */
    fun makePrivateLayout(text: String, layoutType: LayoutType, scope: String, context: Context): String {
        val scopePart = if (layoutType == LayoutType.MAIN) "$scope." else ""
        var number = System.currentTimeMillis()
        var name: String
        do { name = CUSTOM_LAYOUT_PREFIX + scopePart + PRIVATE_MARK + number++.toString(36) + "." } while (getLayoutFile(name, layoutType, context).exists())
        getLayoutFile(name, layoutType, context).writeText(text)
        onLayoutFileChanged()
        return name
    }

    /** The keyboards (but [except]) whose [type] keys are [name]: their own pick, or their default (per keyboard with
     *  separate settings). */
    fun keyboardsUsing(context: Context, type: LayoutType, name: String, except: helium314.keyboard.latin.settings.SettingsSubtype?):
            List<helium314.keyboard.latin.settings.SettingsSubtype> {
        val real = context.realPrefs()
        val separate = helium314.keyboard.latin.settings.KeyboardProfiles.isSeparate(real)
        return SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }.distinct().filter { kb ->
            if (kb == except) return@filter false
            val own = kb.layoutName(type)
            if (own != null) return@filter own == name
            if (type == LayoutType.MAIN) return@filter false // (no own pick: the language's built-in letters)
            val prefs = if (separate) helium314.keyboard.latin.settings.ProfilePreferences(real) {
                helium314.keyboard.latin.settings.KeyboardProfiles.idFor(real, kb) } else context.prefs()
            Settings.readDefaultLayoutName(type, prefs) == name
        }
    }

    /** Whether going from [before] to [after] leaves one of [before]'s private (unsaved) layouts unused. */
    fun dropsUnsaved(before: helium314.keyboard.latin.settings.SettingsSubtype, after: helium314.keyboard.latin.settings.SettingsSubtype): Boolean {
        val kept = LayoutType.entries.mapNotNull { after.layoutName(it) }.toSet()
        return LayoutType.entries.mapNotNull { before.layoutName(it) }.any { isPrivateLayout(it) && it !in kept }
    }

    /** Deletes the private layouts no keyboard uses any more (its keyboard deleted, or switched to another layout). */
    fun removeUnusedPrivateLayouts(context: Context) {
        if (cleanupHeld > 0) return // a preview is open: Cancel may need them again (it calls this when it closes)
        val prefs = context.prefs()
        val used = (SubtypeSettings.createSettingsSubtypes(prefs.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
            + SubtypeSettings.createSettingsSubtypes(prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!)
            + prefs.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype())
            .flatMap { kb -> LayoutType.entries.mapNotNull { kb.layoutName(it) } }.toSet()
        var removed = false
        for (type in LayoutType.entries)
            for (file in getLayoutFiles(type, context))
                if (isPrivateLayout(file.name) && file.name !in used) { file.delete(); removed = true }
        if (removed) onLayoutFileChanged()
    }

    fun getLayoutFile(layoutName: String, layoutType: LayoutType, context: Context): File {
        val file = File(DeviceProtectedUtils.getFilesDir(context), layoutType.folder + File.separator + layoutName)
        file.parentFile?.mkdirs()
        return file
    }

    // remove layouts without a layout file from custom subtypes and settings
    // should not be necessary, but better fall back to default instead of crashing when encountering a bug
    fun removeMissingLayouts(context: Context) {
        val prefs = context.prefs()
        fun remove(type: LayoutType, name: String) {
            val message = "removing custom layout ${getDisplayName(name)} / $name without file"
            if (DebugFlags.DEBUG_ENABLED)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            Log.w(TAG, message)
            SubtypeSettings.onRenameLayout(type, name, null, context)
        }
        // the default for each type in every set (shared, each keyboard's own, hidden ones too), not only this view's
        val real = context.realPrefs()
        LayoutType.entries.forEach { type ->
            val key = Settings.PREF_LAYOUT_PREFIX + type.name
            real.all.filter { (stored, _) -> stored == key || helium314.keyboard.latin.settings.KeyboardProfiles.splitOwnKey(stored)?.second == key }
                .values.filterIsInstance<String>().distinct().forEach { name ->
                    if (!isCustomLayout(name) || getLayoutFiles(type, context).any { it.name.startsWith(name) })
                        return@forEach
                    remove(type, name)
                }
        }
        prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!
            .split(Separators.SETS).forEach outer@{
                val subtype = it.toSettingsSubtype()
                LayoutType.getLayoutMap(subtype.getExtraValueOf(KEYBOARD_LAYOUT_SET) ?: "").forEach { (type, name) ->
                    if (!isCustomLayout(name) || getLayoutFiles(type, context).any { it.name.startsWith(name) })
                        return@forEach
                    remove(type, name)
                    // recursive call: additional subtypes must have changed, so we repeat until nothing needs to be deleted
                    removeMissingLayouts(context)
                    return
                }
            }
    }

    // this goes into prefs and file names, so do not change!
    const val CUSTOM_LAYOUT_PREFIX = "custom."
    /** While above 0 (a list that previews keyboards, e.g. Layouts), private layouts aren't cleaned up. */
    @Volatile var cleanupHeld = 0
    private const val PRIVATE_MARK = "~"
    private const val TAG = "LayoutUtilsCustom"
    private val customLayoutMap = EnumMap<LayoutType, List<File>>(LayoutType::class.java)
}

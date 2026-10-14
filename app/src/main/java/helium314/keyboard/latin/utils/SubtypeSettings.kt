// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.edit
import helium314.keyboard.compat.locale
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardIds
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.ScriptUtils.script
import java.util.Locale

object SubtypeSettings {
    /** @return enabled subtypes. If none are enabled and [fallback]: the default keyboard ([getDefaultEnabledSubtypes]),
     *  only before [init] stored it (2026-10-11: the list is never left empty, see [loadEnabledSubtypes]). */
    fun getEnabledSubtypes(fallback: Boolean = false): List<InputMethodSubtype> {
        if (fallback && enabledSubtypes.isEmpty())
            return getDefaultEnabledSubtypes()
        return enabledSubtypes
    }

    // (the default counts only while nothing is listed: a default keyboard taken out of the list is not enabled; before
    // 2026-10-11 it stayed "enabled" and switching could go back to it)
    fun isEnabled(subtype: InputMethodSubtype?): Boolean =
        subtype in enabledSubtypes || (enabledSubtypes.isEmpty() && subtype in getDefaultEnabledSubtypes())

    fun getAllAvailableSubtypes(): List<InputMethodSubtype> =
        resourceSubtypesByLocale.values.flatten() + additionalSubtypes

    fun getMatchingMainLayoutNameForLocale(locale: Locale): String {
        val subtypes = resourceSubtypesByLocale.values.flatten()
        val name = LocaleUtils.getBestMatch(locale, subtypes) { it.locale() }?.mainLayoutName()
        if (name != null) return name
        return when (locale.script()) {
            ScriptUtils.SCRIPT_LATIN -> "qwerty"
            ScriptUtils.SCRIPT_ARMENIAN -> "armenian_phonetic"
            ScriptUtils.SCRIPT_CYRILLIC -> "ru"
            ScriptUtils.SCRIPT_GREEK -> "greek"
            ScriptUtils.SCRIPT_HEBREW -> "hebrew"
            ScriptUtils.SCRIPT_GEORGIAN -> "georgian"
            ScriptUtils.SCRIPT_BENGALI -> "bengali_unijoy"
            else -> throw RuntimeException("Wrong script supplied: ${locale.script()}")
        }
    }

    /** Adds [newSubtype] to the list. One without an id (a built-in keyboard of the language list) is a new keyboard: it
     *  gets one, as a default stored first does (owner 2026-10-11, keyboards have unique ids); each is registered as an
     *  added keyboard. @return the keyboard as listed */
    fun addEnabledSubtype(prefs: SharedPreferences, newSubtype: InputMethodSubtype): SettingsSubtype {
        val real = (prefs as? ProfilePreferences)?.underlying ?: prefs
        val subtype = newSubtype.toSettingsSubtype().let { if (it.keyboardId == null) KeyboardIds.fresh(real, it) else it }
        helium314.keyboard.latin.utils.SettingsEventLog.log("keyboard added: ${subtype.toPref()}")
        // (the list is stored from the first start on, see [loadEnabledSubtypes]; found empty all the same: the default
        // keyboard is stored first, as at a first start, so the settings made on it stay with a listed keyboard)
        val stored = createSettingsSubtypes(real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
        val default = if (stored.isEmpty() && enabledSubtypes.isEmpty())
            KeyboardIds.fresh(real, getDefaultEnabledSubtypes().first().toSettingsSubtype(), alsoTaken = listOfNotNull(subtype.keyboardId))
            else null
        val subtypes = (if (default != null) listOf(default) else stored) + subtype
        real.edit { putString(Settings.PREF_ENABLED_SUBTYPES, createPrefSubtypes(subtypes)) }
        // (registered after the list: the added list's write reloads the keyboards, Settings' listener, and finds them)
        register(real, listOfNotNull(default, subtype))

        // adding a keyboard: its records now (the net, owner 2026-10-11), before anything reads it in the list
        KeyboardProfiles.ensureListedRecords(real)
        if (default != null && enabledSubtypes.none { it.toSettingsSubtype() == default }) enabledSubtypes.add(0, default.toAdditionalSubtype())
        val added = subtype.toAdditionalSubtype()
        if (enabledSubtypes.none { it == added && it.extraValue == added.extraValue }) {
            enabledSubtypes.removeAll { it == added } // (another definition of the same keyboard, by its id)
            enabledSubtypes.add(added)
            // order = the order keyboards were added (also the switching order); the pref string keeps it across restarts
            RichInputMethodManager.getInstance().refreshSubtypeCaches()
        } else if (default != null) RichInputMethodManager.getInstance().refreshSubtypeCaches()
        return subtype
    }

    /** [keyboards] in the added list (a keyboard with an id is always the user's own), each once, by its id. */
    private fun register(real: SharedPreferences, keyboards: List<SettingsSubtype>) {
        val added = createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!)
        val missing = keyboards.filter { kb -> added.none { it == kb } }
        if (missing.isEmpty()) return
        real.edit { putString(Settings.PREF_ADDITIONAL_SUBTYPES, createPrefSubtypes(added.filter { a -> missing.none { it.sameKeyboard(a) } } + missing)) }
        loadAdditionalSubtypes(real)
    }

    /** @return whether subtype was actually removed */
    fun removeEnabledSubtype(context: Context, subtype: InputMethodSubtype): Boolean {
        helium314.keyboard.latin.utils.SettingsEventLog.log("keyboard removed: ${subtype.toSettingsSubtype().toPref()}")
        val prefs = context.prefs()
        if (!removeEnabledSubtype(prefs, subtype.toSettingsSubtype())) {
            if (SubtypeUtilsAdditional.isAdditionalSubtype(subtype) || subtype.toSettingsSubtype().keyboardId != null)
                return false
            // We want to disable a built-in subtype, but can't. This might be because it was changed in method.xml, and we definitely should disable it
            val enabledFromSettings = createSettingsSubtypes(context.prefs().getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
            val match = enabledFromSettings.firstOrNull {
                !it.isAdditionalSubtype(context.prefs())
                    && it.locale == subtype.locale()
                    && it.mainLayoutName() == subtype.mainLayoutNameOrQwerty()
            }
            // the match is done on locale and main layout name, like in loadEnabledSubtypes
            if (match == null || !removeEnabledSubtype(prefs, match)) {
                Log.e(TAG, "tried to disable built-in ${subtype.toSettingsSubtype()}, but failed")
                return false
            }
            else {
                Log.w(TAG, "had to do some workaround to actually disable $match")
            }
        }
        if (!enabledSubtypes.remove(subtype)) reloadEnabledSubtypes(context)
        else RichInputMethodManager.getInstance().refreshSubtypeCaches()
        return true
    }

    fun getSelectedSubtype(prefs: SharedPreferences): InputMethodSubtype {
        val selectedSubtype = prefs.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype()
        // the keyboard as it is now, by its id (a rename keeps the selection; owner 2026-10-11, keyboards have unique ids)
        enabledSubtypes.firstOrNull { it.toSettingsSubtype().sameKeyboard(selectedSubtype) }?.let { return it }
        if (selectedSubtype.isAdditionalSubtype(prefs))
            return selectedSubtype.toAdditionalSubtype()
        // no additional subtype, must be a resource subtype

        val subtype = enabledSubtypes.firstOrNull { it.toSettingsSubtype() == selectedSubtype }
        if (subtype != null) {
            return subtype
        } else if (enabledSubtypes.isNotEmpty()) {
            Log.w(TAG, "selected subtype $selectedSubtype / ${prefs.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)} not found")
        }
        if (enabledSubtypes.isNotEmpty())
            return enabledSubtypes.first()
        val defaultSubtypes = getDefaultEnabledSubtypes()
        return defaultSubtypes.firstOrNull { it.locale() == selectedSubtype.locale && it.mainLayoutName() == it.mainLayoutName() }
            ?: defaultSubtypes.firstOrNull { it.locale().language == selectedSubtype.locale.language }
            ?: defaultSubtypes.first()
    }

    fun setSelectedSubtype(prefs: SharedPreferences, subtype: InputMethodSubtype) {
        val settingsSubtype = subtype.toSettingsSubtype()
        if (settingsSubtype.locale.toLanguageTag().isEmpty()) {
            Log.w(TAG, "tried to set subtype with empty locale: $settingsSubtype")
            return
        }
        prefs.edit { putString(Settings.PREF_SELECTED_SUBTYPE, settingsSubtype.toPref()) }
    }

    fun isAdditionalSubtype(subtype: InputMethodSubtype): Boolean = subtype in additionalSubtypes

    fun getAdditionalSubtypes(): List<InputMethodSubtype> = additionalSubtypes.toList()

    fun reloadSystemLocales(context: Context) {
        systemLocales.clear()
        try {
            val localeList = LocaleManagerCompat.getSystemLocales(context)
            (0 until localeList.size()).forEach {
                val locale = localeList[it]
                if (locale != null) systemLocales.add(locale)
            }
        } catch (_: Throwable) {
            systemLocales.add(context.resources.configuration.locale())
        }
        systemSubtypes.clear()
    }

    fun getSystemLocales(): List<Locale> = systemLocales.toList()

    fun getResourceSubtypesForLocale(locale: Locale): List<InputMethodSubtype> = resourceSubtypesByLocale[locale].orEmpty()

    fun getAvailableSubtypeLocales(): List<Locale> = resourceSubtypesByLocale.keys.toList()

    /**
     * Update subtypes that contain the layout. If new name is null (layout deleted) and the
     * subtype is now identical to a resource subtype, remove the subtype from additional subtypes.
     */
    @SuppressLint("UseKtx") // easier to read
    fun onRenameLayout(type: LayoutType, from: String, to: String?, context: Context) {
        val prefs = context.prefs()
        val editor = prefs.edit() // calling apply for each separate setting would result in an invalid intermediate state
        // the keyboards that change, switched-off ones too (in the additional list only; owner 2026-10-11: treated like
        // switched-on ones); each keeps its id, so its set and learned words stay its own (owner 2026-10-11, keyboards
        // have unique ids: before, they moved to the new definition)
        val lists = LinkedHashMap<String, String>()
        listOf(
            Settings.PREF_ADDITIONAL_SUBTYPES to Defaults.PREF_ADDITIONAL_SUBTYPES,
            Settings.PREF_ENABLED_SUBTYPES to Defaults.PREF_ENABLED_SUBTYPES,
            Settings.PREF_SELECTED_SUBTYPE to Defaults.PREF_SELECTED_SUBTYPE
        ).forEach { (key, default) ->
            val new = prefs.getString(key, default)!!.split(Separators.SETS).mapNotNullTo(mutableSetOf()) {
                if (it.isEmpty()) return@mapNotNullTo null
                val subtype = it.toSettingsSubtype()
                if (subtype.layoutName(type) == from) {
                    if (to == null) {
                        val defaultLayout = if (type !== LayoutType.MAIN) null
                            // if we just delete a main layout, we may end up with something like Hindi (QWERTY)
                            // so better replace it with a default layout for that locale
                            else resourceSubtypesByLocale[subtype.locale]?.first()?.mainLayoutName()
                        val newSubtype = if (defaultLayout == null) subtype.withoutLayout(type)
                            else subtype.withLayout(type, defaultLayout)
                        if (newSubtype.isSameAsDefault() && key == Settings.PREF_ADDITIONAL_SUBTYPES) null
                        else newSubtype.toPref()
                    }
                    else subtype.withLayout(type, to).toPref()
                }
                else subtype.toPref()
            }.joinToString(Separators.SETS)
            lists[key] = new
        }
        val real = context.realPrefs()
        for ((key, new) in lists) editor.putString(key, new)
        editor.apply()
        // the default for its type, in every set (shared, each keyboard's own, hidden ones too); 2026-10-07: only the
        // keyboard being edited followed, the others fell back to the built-in keys (rename) or kept the gone name and
        // took a layout saved later under it (delete). Deleted: a keyboard's own default goes back to the built-in one.
        val key = Settings.PREF_LAYOUT_PREFIX + type.name
        helium314.keyboard.latin.settings.KeyboardProfiles.replaceValueEverywhere(real, key, from, to)
        helium314.keyboard.settings.LayoutPresets.onLayoutRenamed(real, type, from, to) // and the saved Layouts (review 2026-10-08 area 4 #4)
        reloadEnabledSubtypes(context)
    }

    fun reloadEnabledSubtypes(context: Context) {
        enabledSubtypes.clear()
        loadAdditionalSubtypes(context.prefs())
        // the net (owner 2026-10-11), not for a default stored as the list was found empty: in a reload that is a moment
        // of a list being rewritten (cleared, reset, restored), and records for it stayed behind as a switched-off
        // keyboard once the list was written; a default that stays gets them at the next start, load or add
        if (!loadEnabledSubtypes(context)) KeyboardProfiles.ensureListedRecords(context.realPrefs())
        if (RichInputMethodManager.isInitialized())
            RichInputMethodManager.getInstance().refreshSubtypeCaches()
    }

    fun createSettingsSubtypes(prefSubtypes: String): List<SettingsSubtype> =
        prefSubtypes.split(Separators.SETS).mapNotNull {
            if (it.isEmpty()) null
            else it.toSettingsSubtype()
        }

    // order is kept: it is the order of the keyboards list and the switching order (upstream sorted here)
    fun createPrefSubtypes(subtypes: Collection<SettingsSubtype>): String =
        subtypes.map { it.toPref() }.distinct().joinToString(Separators.SETS)

    fun init(context: Context) {
        SubtypeLocaleUtils.init(context) // necessary to get the correct getKeyboardLayoutSetName

        // necessary to set system locales at start, because for some weird reason (bug?)
        // LocaleManagerCompat.getSystemLocales(context) sometimes doesn't return all system locales
        reloadSystemLocales(context)

        loadResourceSubtypes(context.resources)
        builtInDictLocales.clear()
        DictionaryInfoUtils.getAssetsDictionaryList(context).orEmpty().filter { it.startsWith(DictionaryInfoUtils.MAIN_DICT_PREFIX) }
            .mapNotNullTo(builtInDictLocales) { runCatching { DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it) }.getOrNull() }
        systemSubtypes.clear()
        loadAdditionalSubtypes(context.prefs())
        enabledSubtypes.clear() // loading twice must not list a keyboard twice
        loadEnabledSubtypes(context)
    }

    /**
     * The default keyboard, one (owner 2026-10-11: at the first start one real keyboard is stored, no "list empty, a
     * default is shown" any more): the first of the phone's languages (its language list, in order) the app has a
     * built-in dictionary for, as the "No dictionary" check matches them (a language-level one counts, see
     * getUserAndInternalDictionaries), its first built-in keyboard; else English (US).
     */
    private fun getDefaultEnabledSubtypes(): List<InputMethodSubtype> {
        if (systemSubtypes.isNotEmpty()) return systemSubtypes
        val subtype = systemLocales.firstNotNullOfOrNull { locale ->
            if (LocaleUtils.getBestMatch(locale, builtInDictLocales) { it } == null) return@firstNotNullOfOrNull null
            (resourceSubtypesByLocale[locale]
                ?: LocaleUtils.getBestMatch(locale, resourceSubtypesByLocale.keys) { it }?.let { resourceSubtypesByLocale[it] })
                ?.firstOrNull()
        } ?: resourceSubtypesByLocale[Locale.US]!!.first()
        systemSubtypes.add(subtype)
        return systemSubtypes
    }

    private fun loadResourceSubtypes(resources: Resources) {
        getResourceSubtypes(resources).forEach {
            resourceSubtypesByLocale.getOrPut(it.locale()) { ArrayList(2) }.add(it)
        }
    }

    private fun loadAdditionalSubtypes(prefs: SharedPreferences) {
        additionalSubtypes.clear()
        val additionalSubtypeString = prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!
        val subtypes = SubtypeUtilsAdditional.createAdditionalSubtypes(additionalSubtypeString)
        additionalSubtypes.addAll(subtypes)
    }

    // requires loadResourceSubtypes to be called before. @return whether the list was found empty and the default stored
    private fun loadEnabledSubtypes(context: Context): Boolean {
        val prefs = context.prefs()
        // every listed keyboard has its id, given before the list is read (the net, owner 2026-10-11: keyboards have
        // unique ids); its write may have loaded the keyboards already (Settings' listener): loaded once here
        if (KeyboardIds.ensureListedIds(context.realPrefs())) loadAdditionalSubtypes(prefs)
        enabledSubtypes.clear()
        val settingsSubtypes = createSettingsSubtypes(prefs.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
        for (settingsSubtype in settingsSubtypes) {
            // (a keyboard with an id is the user's own: from its definition)
            if (settingsSubtype.keyboardId != null || settingsSubtype.isAdditionalSubtype(prefs)) {
                enabledSubtypes.add(settingsSubtype.toAdditionalSubtype())
                continue
            }
            val subtypesForLocale = resourceSubtypesByLocale[settingsSubtype.locale]
            if (subtypesForLocale == null) {
                val message = "no resource subtype for $settingsSubtype"
                Log.w(TAG, message)
                if (DebugFlags.DEBUG_ENABLED)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                else // don't remove in debug mode
                    removeEnabledSubtype(prefs, settingsSubtype)
                continue
            }

            val subtype = subtypesForLocale.firstOrNull { it.mainLayoutNameOrQwerty() == (settingsSubtype.mainLayoutName() ?: SubtypeLocaleUtils.QWERTY) }
            if (subtype == null) {
                val message = "subtype $settingsSubtype could not be loaded"
                Log.w(TAG, message)
                if (DebugFlags.DEBUG_ENABLED)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                else // don't remove in debug mode
                    removeEnabledSubtype(prefs, settingsSubtype)
                continue
            }

            enabledSubtypes.add(subtype)
        }
        // nothing listed (the first start, or the list found empty): the default keyboard is stored, an ordinary keyboard
        // whose own settings are the main ones (owner 2026-10-11); before, the list stayed empty and a default was shown.
        // (a list whose keyboards only failed to load in debug mode stays as it is)
        if (enabledSubtypes.isEmpty() && createSettingsSubtypes(prefs.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!).isEmpty()) {
            // (a new keyboard: its own new id, owner 2026-10-11; registered as an added keyboard after the list is written:
            // that write reloads the keyboards, Settings' listener, and an empty list would store another default)
            val real = context.realPrefs()
            val default = KeyboardIds.fresh(real, getDefaultEnabledSubtypes().first().toSettingsSubtype())
            Log.i(TAG, "no keyboard in the list: storing the default ${default.toPref()}")
            prefs.edit {
                putString(Settings.PREF_ENABLED_SUBTYPES, createPrefSubtypes(listOf(default)))
                // (a selected keyboard of before, of no keyboard listed now: the default; the same definition: its id)
                val selected = prefs.getString(Settings.PREF_SELECTED_SUBTYPE, null)?.toSettingsSubtype()
                if (selected == null || selected.keyboardId == null || selected.withoutIds() == default.withoutIds())
                    putString(Settings.PREF_SELECTED_SUBTYPE, default.toPref())
            }
            register(real, listOf(default))
            enabledSubtypes.clear()
            enabledSubtypes.add(default.toAdditionalSubtype())
            return true
        }
        return false
    }

    /** @return whether pref was changed */
    private fun removeEnabledSubtype(prefs: SharedPreferences, subtype: SettingsSubtype): Boolean = removeEnabledSubtypeFromPrefs(prefs, subtype)

    internal fun removeEnabledSubtypeFromPrefs(prefs: SharedPreferences, subtype: SettingsSubtype): Boolean {
        val oldSubtypes = createSettingsSubtypes(prefs.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
        // (by its id: whatever its definition now; owner 2026-10-11, keyboards have unique ids)
        val newSubtypes = oldSubtypes.filter { !it.sameKeyboard(subtype) }
        if (oldSubtypes == newSubtypes)
            return false // already removed
        prefs.edit { putString(Settings.PREF_ENABLED_SUBTYPES, createPrefSubtypes(newSubtypes)) }
        if (subtype.sameKeyboard(prefs.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype())) {
            // switch subtype if the currently used one has been disabled
            try {
                val nextSubtype = RichInputMethodManager.getInstance().getNextSubtypeInThisIme(true)
                if (subtype.sameKeyboard(nextSubtype?.toSettingsSubtype()))
                    KeyboardSwitcher.getInstance().switchToSubtype(getDefaultEnabledSubtypes().first())
                else
                    KeyboardSwitcher.getInstance().switchToSubtype(nextSubtype)
            } catch (_: Exception) { } // do nothing if RichInputMethodManager isn't initialized
            // the keyboard not running, the switch did nothing: the next keyboard is the one in use (review 2026-10-07:
            // the deleted one stayed selected, and the keyboard came up on a new, empty settings set for it)
            if (subtype.sameKeyboard(prefs.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype()))
                newSubtypes.firstOrNull()?.let { next -> prefs.edit { putString(Settings.PREF_SELECTED_SUBTYPE, next.toPref()) } }
        }
        return true
    }

    private val enabledSubtypes = mutableListOf<InputMethodSubtype>()
    private val resourceSubtypesByLocale = LinkedHashMap<Locale, MutableList<InputMethodSubtype>>(100)
    private val additionalSubtypes = mutableListOf<InputMethodSubtype>()
    private val systemLocales = mutableListOf<Locale>()
    private val systemSubtypes = mutableListOf<InputMethodSubtype>() // (the default keyboard, once worked out)
    private val builtInDictLocales = mutableListOf<Locale>() // the languages of the built-in (asset) main dictionaries
    private val TAG = SubtypeSettings::class.simpleName
}

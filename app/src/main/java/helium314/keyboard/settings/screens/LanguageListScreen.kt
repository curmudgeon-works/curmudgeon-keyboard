// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.utils.realPrefs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.translit.TranslitSpec
import helium314.keyboard.latin.translit.Transliteration
import helium314.keyboard.latin.utils.MissingDictionaryDialog
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getDictionaryLocales
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.dialogs.DictionaryDialog
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.gesture.OwnGestureDecoder
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.Setting
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.NextScreenIcon
import java.util.Locale

/**
 * Languages of ONE keyboard (its layout and swipe screens are on the main screen): every language as a row — the keyboard's own languages first with their
 * Off / L / M / H priority, the others off. Turning a language on adds it to this keyboard (same script only;
 * a language of another script needs its own keyboard, so its row is faded and locked).
 */
@Composable
fun LanguageListScreen(
    initialKeyboard: SettingsSubtype,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var keyboard by remember { mutableStateOf(initialKeyboard) }
    var generation by remember { mutableIntStateOf(0) } // bumped after every change so the list re-sorts
    val (ownLanguages, otherLanguages) = remember(keyboard, generation) { languagesFor(ctx, keyboard) }
    // the buttons "Use lists from other keyboards": only with other keyboards in the list (what's stored stays)
    val severalKeyboards = remember(keyboard, generation) {
        SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype().toPref() }.distinct().size > 1 }
    fun setKeyboard(new: SettingsSubtype) {
        // (owner 2026-10-11, keyboards have unique ids: no "already set up like this" block, the change is always made)
        SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, new, ctx)
        keyboard = new
        generation++
    }
    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.languages_title))
                Text(
                    keyboardName(keyboard, ctx),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        // (the layout and swipe screens are on the main screen)
        // above the languages: the keyboard's learned words (2026-10-10)
        filteredItems = { term ->
            if (term.isBlank()) listOfNotNull(if (severalKeyboards) UseOthersItem else null, LearnedWordsItem) + ownLanguages + otherLanguages
            else (ownLanguages + otherLanguages).filter { locale ->
                locale.localizedDisplayName(ctx.resources).replace("(", "")
                    .splitOnWhitespace().any { it.startsWith(term, true) }
                    // or its Suggest in … row ("हिंदी", "suggest in"), shown while it is on the keyboard
                    || (locale in ownLanguages && Transliteration.specFor(locale)
                        ?.let { ctx.getString(R.string.suggest_in_script, it.name).contains(term.trim(), true) } == true)
            }
        },
        itemContent = { item ->
            when (item) {
                UseOthersItem -> UseOthersRow(keyboard)
                LearnedWordsItem -> Preference(
                    name = stringResource(R.string.learned_words),
                    onClick = { SettingsDestination.navigateTo(SettingsDestination.withKeyboard(SettingsDestination.LearnedWords, keyboard)) },
                ) { NextScreenIcon() }
                else -> LanguageRow(item as Locale, keyboard, generation, ::setKeyboard)
            }
        },
    )
}

private object UseOthersItem
private object LearnedWordsItem

/**
 * "Use lists from other keyboards" with its two buttons [Learned] [Blacklisted] (2026-10-10): this keyboard reads the
 * other keyboards' learned words, and their blacklists apply on it (LearnedStores.LearnedView). The label and the
 * buttons share a row if they fit; else both buttons go to the next row together.
 */
@Composable
internal fun UseOthersRow(keyboard: SettingsSubtype) {
    val ctx = LocalContext.current
    val real = ctx.realPrefs()
    @Composable
    fun UseOthersButton(pref: String, label: Int) {
        var on by remember(keyboard) { mutableStateOf(LearnedKeyboards.usesOthers(real, pref, keyboard)) }
        UseOthersToggle(stringResource(label), on) {
            on = !on
            LearnedKeyboards.setUsesOthers(real, pref, keyboard, on) // (the keyboard follows: LearnedStores' listener)
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.learned_use_others), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(USE_OTHERS_BUTTONS_TAG)) {
            UseOthersButton(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, R.string.learned_words_list_learned)
            UseOthersButton(LearnedKeyboards.PREF_USE_OTHERS_BLACKLISTED, R.string.learned_words_list_blacklisted)
        }
    }
}

internal const val USE_OTHERS_BUTTONS_TAG = "use_others_buttons"

/** The Learned / Blacklisted buttons of the keyboard's learned words screen, as a toggle: filled with a tick when on. */
@Composable
private fun UseOthersToggle(label: String, on: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick,
        modifier = Modifier.semantics { selected = on },
        colors = if (on) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
        if (on) Icon(painterResource(R.drawable.ic_check), null, Modifier.padding(end = 4.dp).size(18.dp))
        Text(label)
    }
}

/** The keyboard's languages by priority, the same priority in the order they were added; and every other language:
 *  same script first, then with a dictionary, then alphabetically. All of them with or without Advanced (owner
 *  2026-10-18: "fine to show all langs even without dict in non-advanced"; until then only those with a dictionary,
 *  built in, added or to download): one turned on without a dictionary says so ([languageSubtext]). */
private fun languagesFor(ctx: android.content.Context, keyboard: SettingsSubtype): Pair<List<Locale>, List<Locale>> {
    val prefs = ctx.prefs()
    val own = listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)
    val others = (SubtypeSettings.getAvailableSubtypeLocales() + getDictionaryLocales(ctx)).distinct().filter { it !in own }
    // the same test the rows use for fading (a language-level dictionary counts, e.g. English (India) has English's)
    fun hasDictionary(locale: Locale): Boolean {
        val (dicts, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
        return hasInternal || dicts.isNotEmpty()
    }
    // (sorting is stable: languages added before the adding time was recorded keep the keyboard's own order)
    return LanguagePriority.inListOrder(prefs, own) to
        others.sortedWith(compareBy({ it.script() != keyboard.locale.script() }, { !hasDictionary(it) },
            { it.localizedDisplayName(ctx.resources) }))
}

/**
 * A language row's dictionaries as its subtext lists them: the added ones by their raw names ("main", "main_prev"), the
 * built-in one first when it is in use or there is no added main one; each with whether it is the one in use of its
 * kind ([helium314.keyboard.latin.dictionary.DictionarySelection.inUse]; review L3.3: shown highlighted).
 */
internal fun languageRowDictionaries(ctx: android.content.Context, locale: Locale): List<Pair<String, Boolean>> {
    val (dicts, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
    val selection = helium314.keyboard.latin.dictionary.DictionarySelection
    val kinds = (dicts.map { selection.typeOf(it) } + Dictionary.TYPE_MAIN).distinct()
    val inUse = kinds.associateWith { selection.inUse(ctx, locale, dicts, hasInternal && it == Dictionary.TYPE_MAIN, it) }
    val entries = dicts.mapTo(mutableListOf()) {
        it.name.substringBefore("_${DictionaryInfoUtils.USER_DICTIONARY_SUFFIX}") to (inUse[selection.typeOf(it)] == it.name)
    }
    val internalInUse = inUse[Dictionary.TYPE_MAIN] == selection.INTERNAL
    if (hasInternal && (internalInUse || entries.none { it.first == Dictionary.TYPE_MAIN }))
        entries.add(0, ctx.getString(R.string.internal_dictionary_summary) to internalInUse)
    return entries
}

@Composable
private fun LanguageRow(locale: Locale, keyboard: SettingsSubtype, generation: Int, setKeyboard: (SettingsSubtype) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val own = listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)
    // read again after every change: a second language's new priority leaves the keyboard equal, so without
    // [generation] the row kept showing the old one and ignored a tap on it (owner 2026-10-18: "stuck on M")
    val priority = remember(locale, keyboard, generation) { if (locale in own) LanguagePriority.get(prefs, locale) else OFF }
    var showNoDictDialog by remember { mutableStateOf(false) }
    var showDictionaryDialog by remember { mutableStateOf(false) }
    var dictGeneration by remember { mutableIntStateOf(0) }
    val dictionaryTypes = remember(locale, dictGeneration) { languageRowDictionaries(ctx, locale) }
    val hasDictionary = dictionaryTypes.isNotEmpty()
    val sameScript = locale.script() == keyboard.locale.script()
    // no dictionary = no suggestions for it; another script = can't be on this keyboard: both faded
    val nameColor = if (hasDictionary && sameScript) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // tapping the language manages its dictionaries
            Column(modifier = Modifier.weight(1f).clickable { showDictionaryDialog = true }) {
                Text(locale.localizedDisplayName(ctx.resources), style = MaterialTheme.typography.bodyLarge, color = nameColor)
                Text(
                    when {
                        !sameScript -> androidx.compose.ui.text.AnnotatedString(stringResource(R.string.language_other_script))
                        // the raw names ("main, main_prev"), the one in use of each kind in bold (review L3.3)
                        hasDictionary -> androidx.compose.ui.text.buildAnnotatedString {
                            dictionaryTypes.forEachIndexed { i, (name, inUse) ->
                                if (i > 0) append(", ")
                                if (inUse) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append(name) }
                                else append(name)
                            }
                        }
                        else -> androidx.compose.ui.text.AnnotatedString(languageSubtext(ctx, on = priority != OFF))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = nameColor
                )
            }
            val options = listOf(
                OFF to stringResource(R.string.language_priority_off),
                LanguagePriority.LOW to stringResource(R.string.language_priority_low),
                LanguagePriority.MEDIUM to stringResource(R.string.language_priority_medium),
                LanguagePriority.HIGH to stringResource(R.string.language_priority_high),
            )
            SingleChoiceSegmentedButtonRow {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = priority == value,
                        enabled = sameScript && (value != OFF || own.size > 1), // the last language stays
                        onClick = {
                            if (priority != value) {
                                if (priority == OFF && !hasDictionary) showNoDictDialog = true
                                setKeyboard(withLanguagePriority(ctx, keyboard, locale, value))
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        label = { Text(label) }
                    )
                }
            }
        }
        // Suggest in हिंदी: under a language with a table while it is on the keyboard (no subtext line)
        val translit = Transliteration.specFor(locale)
        if (translit != null && priority != OFF)
            SuggestInScriptRow(locale, translit, keyboard, generation)
        if (showNoDictDialog)
            MissingDictionaryDialog({ showNoDictDialog = false }, locale)
        if (showDictionaryDialog)
            DictionaryDialog({ showDictionaryDialog = false; dictGeneration++ }, locale, keyboard)
        // (no "learned words count for all languages" switch any more: learned words are per script, shared by the
        // script's languages; see LearnedStores)
    }
}

private const val OFF = 0

/** "Suggest in हिंदी" with its Off / Some / All, just under its language, a little indented; the keyboard's own setting
 *  (Transliteration, stored like the language's priority). */
@Composable
internal fun SuggestInScriptRow(locale: Locale, spec: TranslitSpec, keyboard: SettingsSubtype, generation: Int,
                                modifier: Modifier = Modifier.padding(start = 16.dp, top = 6.dp)) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var mode by remember(locale, keyboard, generation) { mutableIntStateOf(Transliteration.get(prefs, locale)) }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(modifier).testTag(SUGGEST_IN_SCRIPT_TAG)) {
        Text(stringResource(R.string.suggest_in_script, spec.name), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp))
        val options = listOf(
            Transliteration.OFF to stringResource(R.string.language_priority_off),
            Transliteration.SOME to stringResource(R.string.suggest_in_script_some),
            Transliteration.ALL to stringResource(R.string.suggest_in_script_all),
        )
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = {
                        if (mode != value) {
                            Transliteration.set(prefs, locale, value)
                            mode = value
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label) }
                )
            }
        }
    }
}

internal const val SUGGEST_IN_SCRIPT_TAG = "suggest_in_script"

/** The Suggest in … rows as settings of the main screen's search ("हिंदी", "suggest in"), one per table, each for the
 *  keyboard the main screen's results edit; found while that keyboard has the language ([suggestInScriptItems]). */
fun createLanguageListSettings(context: android.content.Context): List<Setting> = Transliteration.specs.map { spec ->
    Setting(context, suggestInScriptKey(spec), R.string.suggest_in_script, titleArgs = arrayOf(spec.name)) {
        val keyboard = mainSearchKeyboard(LocalContext.current)
        SuggestInScriptRow(Locale.forLanguageTag(spec.latinTag), spec, keyboard, 0,
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

private fun suggestInScriptKey(spec: TranslitSpec) = Settings.PREF_SUGGEST_IN_SCRIPT_PREFIX + spec.latinTag

/** The Suggest in … rows the main screen's keyboard ([mainSearchKeyboard]) shows on its Languages screen now. */
internal fun suggestInScriptItems(ctx: android.content.Context): List<String> {
    val keyboard = mainSearchKeyboard(ctx)
    return (listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues))
        .mapNotNull { Transliteration.specFor(it) }.map { suggestInScriptKey(it) }
}

/** The line under a language with no dictionary here (built in or added; one to download counts as none until it's
 *  downloaded): turned on for the keyboard, what that means (owner 2026-10-18: ""No dictionary: swipe and suggestions
 *  won't work" <- yes need this"); turned off, "No dictionary" as before. */
internal fun languageSubtext(ctx: android.content.Context, on: Boolean): String =
    ctx.getString(if (on) R.string.no_dictionary_on else R.string.no_dictionary_short)

/**
 * Apply a priority change to a keyboard: [OFF] removes the language, a priority adds it if needed and stores it;
 * the keyboard's main language becomes the highest-priority one (ties keep the current main). The layout and
 * the other settings travel with the keyboard.
 */
fun withLanguagePriority(context: android.content.Context, subtype: SettingsSubtype, locale: Locale, priority: Int): SettingsSubtype {
    val prefs = context.prefs()
    val languages = (listOf(subtype.locale) + getSecondaryLocales(subtype.extraValues)).toMutableList()
    if (priority == OFF) languages.remove(locale) else {
        LanguagePriority.set(prefs, locale, priority)
        if (locale !in languages) {
            languages.add(locale)
            LanguagePriority.markAdded(prefs, locale)
        }
    }
    val main = languages.maxWithOrNull(compareBy({ LanguagePriority.get(prefs, it) }, { it == subtype.locale })) ?: subtype.locale
    val secondaries = languages.filter { it != main }
    val withSecondaries = if (secondaries.isEmpty()) subtype.without(ExtraValue.SECONDARY_LOCALES)
        else subtype.with(ExtraValue.SECONDARY_LOCALES, secondaries.joinToString(Separators.KV) { it.toLanguageTag() })
    return if (main == subtype.locale) withSecondaries
        else SettingsSubtype(main, withSecondaries.extraValues)
}

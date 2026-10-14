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
    val advanced by helium314.keyboard.settings.SettingsMode.state(ctx)
    val (ownLanguages, otherLanguages) = remember(keyboard, generation, advanced) { languagesFor(ctx, keyboard, advanced) }
    // the buttons "Use lists from other keyboards": only with other keyboards in the list (what's stored stays)
    val severalKeyboards = remember(keyboard, generation) {
        SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype().toPref() }.distinct().size > 1 }
    fun setKeyboard(new: SettingsSubtype) {
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
            }
        },
        itemContent = { item ->
            when (item) {
                UseOthersItem -> UseOthersRow(keyboard)
                LearnedWordsItem -> Preference(
                    name = stringResource(R.string.learned_words),
                    onClick = { SettingsDestination.navigateTo(SettingsDestination.withKeyboard(SettingsDestination.LearnedWords, keyboard)) },
                ) { NextScreenIcon() }
                else -> LanguageRow(item as Locale, keyboard, ::setKeyboard)
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
 *  same script first, then with a dictionary, then alphabetically. Without [advanced] only the other languages that
 *  have a dictionary, built in, added or to download (owner 2026-10-10: "make sure langs without dict don't appear
 *  without advanced"). */
private fun languagesFor(ctx: android.content.Context, keyboard: SettingsSubtype, advanced: Boolean): Pair<List<Locale>, List<Locale>> {
    val prefs = ctx.prefs()
    val own = listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)
    val others = (SubtypeSettings.getAvailableSubtypeLocales() + getDictionaryLocales(ctx)).distinct().filter { it !in own }
        .filter { advanced || dictionaryAvailable(ctx, it) }
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

@Composable
private fun LanguageRow(locale: Locale, keyboard: SettingsSubtype, setKeyboard: (SettingsSubtype) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val own = listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)
    val priority = if (locale in own) LanguagePriority.get(prefs, locale) else OFF
    var showNoDictDialog by remember { mutableStateOf(false) }
    var showDictionaryDialog by remember { mutableStateOf(false) }
    var dictGeneration by remember { mutableIntStateOf(0) }
    val dictionaryTypes = remember(locale, dictGeneration) {
        val (dicts, hasInternal) = getUserAndInternalDictionaries(ctx, locale)
        val types = dicts.mapTo(mutableListOf()) { it.name.substringBefore("_${DictionaryInfoUtils.USER_DICTIONARY_SUFFIX}") }
        if (hasInternal && !types.contains(Dictionary.TYPE_MAIN)) types.add(0, ctx.getString(R.string.internal_dictionary_summary))
        types
    }
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
                        !sameScript -> stringResource(R.string.language_other_script)
                        hasDictionary -> dictionaryTypes.joinToString(", ")
                        else -> stringResource(R.string.no_dictionary_short)
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
        if (showNoDictDialog)
            MissingDictionaryDialog({ showNoDictDialog = false }, locale)
        if (showDictionaryDialog)
            DictionaryDialog({ showDictionaryDialog = false; dictGeneration++ }, locale, keyboard,
                onTurnOff = { showDictionaryDialog = false; setKeyboard(withLanguagePriority(ctx, keyboard, locale, OFF)) })
        // (no "learned words count for all languages" switch any more: learned words are per script, shared by the
        // script's languages; see LearnedStores)
    }
}

private const val OFF = 0

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

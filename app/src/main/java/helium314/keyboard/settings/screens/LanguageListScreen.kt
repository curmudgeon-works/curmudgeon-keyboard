// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.NextScreenIcon
import java.util.Locale

/**
 * Languages & layouts of ONE keyboard: a layout row on top (opening the keyboard screen with layout, popup order,
 * secondary layouts, dictionaries), then every language as a row — the keyboard's own languages first with their
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
    val languages = remember(keyboard, generation) { languagesFor(ctx, keyboard) }
    fun setKeyboard(new: SettingsSubtype) {
        SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, new, ctx)
        keyboard = new
        generation++
    }
    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.language_and_layouts_title))
                Text(
                    keyboardName(keyboard, ctx),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        // the layout row is the first list item (a content block would replace the list)
        filteredItems = { term ->
            val matching = languages.filter { locale ->
                locale.localizedDisplayName(ctx.resources).replace("(", "")
                    .splitOnWhitespace().any { it.startsWith(term, true) }
            }
            if (term.isBlank()) listOf<Any>(LayoutRowMarker) + matching else matching
        },
        itemContent = { item ->
            if (item === LayoutRowMarker)
                Preference(
                    name = stringResource(R.string.keyboard_layout_set),
                    description = keyboard.mainLayoutName()?.getStringResourceOrName("layout_", ctx) ?: "",
                    onClick = { SettingsDestination.navigateTo(SettingsDestination.Subtype + keyboard.toPref()) },
                    icon = R.drawable.ic_settings_languages
                ) { NextScreenIcon() }
            else LanguageRow(item as Locale, keyboard, ::setKeyboard)
        },
    )
}

/** The keyboard's languages in priority order, then every other language: with a dictionary first, alphabetically. */
private fun languagesFor(ctx: android.content.Context, keyboard: SettingsSubtype): List<Locale> {
    val prefs = ctx.prefs()
    val own = listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)
    val withDictionary = getDictionaryLocales(ctx)
    val others = (SubtypeSettings.getAvailableSubtypeLocales() + withDictionary).distinct().filter { it !in own }
    return own.sortedByDescending { LanguagePriority.get(prefs, it) } +
        others.sortedWith(compareBy({ it.script() != keyboard.locale.script() }, { it !in withDictionary },
            { it.localizedDisplayName(ctx.resources) }))
}

@Composable
private fun LanguageRow(locale: Locale, keyboard: SettingsSubtype, setKeyboard: (SettingsSubtype) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val own = listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)
    val priority = if (locale in own) LanguagePriority.get(prefs, locale) else OFF
    var shared by remember(locale) { mutableStateOf(LanguagePriority.sharesUserHistory(prefs, locale)) }
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
            DictionaryDialog({ showDictionaryDialog = false; dictGeneration++ }, locale)
        if (priority != OFF) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.share_user_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = shared,
                    onCheckedChange = {
                        shared = it
                        LanguagePriority.setSharesUserHistory(prefs, locale, it)
                    }
                )
            }
        }
    }
}

private const val OFF = 0
private object LayoutRowMarker

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
        if (locale !in languages) languages.add(locale)
    }
    val main = languages.maxWithOrNull(compareBy({ LanguagePriority.get(prefs, it) }, { it == subtype.locale })) ?: subtype.locale
    val secondaries = languages.filter { it != main }
    val withSecondaries = if (secondaries.isEmpty()) subtype.without(ExtraValue.SECONDARY_LOCALES)
        else subtype.with(ExtraValue.SECONDARY_LOCALES, secondaries.joinToString(Separators.KV) { it.toLanguageTag() })
    return if (main == subtype.locale) withSecondaries
        else SettingsSubtype(main, withSecondaries.extraValues)
}

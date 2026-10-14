// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.provider.UserDictionary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearningEventLog
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.latin.utils.FrequentLongWords
import helium314.keyboard.latin.utils.HotWords
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.PersonalDictionaryChoice
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.preferences.LocalRowStart
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * "Learned & blacklisted words" of one keyboard (on the keyboard's screen, 2026-10-10): per script (Latin, Devanagari,
 * …), as the keyboard keeps them (one store of learned words and one blacklist per script, shared by its languages: see
 * LearnedStores). Each keyboard has its own (LearnedKeyboards): only those are listed, not the other keyboards' it reads
 * with its buttons "Use lists from other keyboards". Per script:
 * - Your words: the script's learned words, merged with the Android personal dictionary's words of the script (from
 *   their letters; the personal dictionary stays per language, Android's), each word once (case-insensitive, shown in
 *   its most typed spelling). Delete: gone as if never typed (owner 2026-10-10: "Just as if the word never existed on
 *   the list"): out of the learned words and the Android personal dictionary, its recent uses forgotten, NOT blacklisted
 *   (long-press Remove in the strip is what blacklists). A word in Android's personal dictionary: the dialog's box "Also
 *   delete them from your device's personal dictionary" (ticked) says whether it goes from there too (owner 2026-10-12).
 *   An allowed row (RemovedWords: a borrowed block lifted on this keyboard) stays: the delete forgets the count only.
 * - Blacklisted words: the script's long-press Remove list (see RemovedWords), with how often each was removed (its
 *   strikes, which say how it comes back; a word allowed here keeps its own strikes in its row and is listed with them,
 *   owner 2026-10-12: one row holds both). Delete: the word and its strikes, as if never removed (an allowed part of
 *   its row stays: the borrowed block stays lifted).
 * A further section (another list of the kind) is one more Kind with its action and one more section() in rows().
 * The personal dictionary's own screens (shortcuts, weights, "for all languages", editing) stay reachable from the
 * first screen, and Add a word here is its add dialog.
 */

const val LIST_LEARNED = "learned"
const val LIST_BLACKLISTED = "blacklisted"

/** One row of the scripts list: [script] with its [locales], in learned-words [pool]. */
internal class ScriptRowData(val script: String, val locales: List<Locale>, val pool: Int)

/** The scripts of [keyboard]'s languages, each with its two lists (2026-10-04): Learned (learned words and the
 *  personal dictionary together) and Blacklisted. The personal dictionary's own screens (shortcuts, words for all
 *  languages) stay reachable below, in advanced mode. */
@Composable
fun LearnedWordsScriptsScreen(onClickBack: () -> Unit, keyboard: helium314.keyboard.latin.settings.SettingsSubtype) {
    val ctx = LocalContext.current
    val advanced by helium314.keyboard.settings.SettingsMode.state(ctx)
    val rows = remember(keyboard) { scriptRows(ctx, keyboard) }
    fun open(row: ScriptRowData, list: String) =
        SettingsDestination.navigateTo("${SettingsDestination.LearnedWordsOfScript}${row.script}/$list/${row.pool}", ctx)
    @Composable
    fun ScriptRow(row: ScriptRowData) {
        Preference(
            name = scriptName(row.script),
            description = row.locales.joinToString(", ") { it.getLocaleDisplayNameForUserDictSettings(ctx) },
            onClick = { open(row, LIST_LEARNED) },
        ) {
            androidx.compose.material3.OutlinedButton({ open(row, LIST_LEARNED) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                Text(stringResource(R.string.learned_words_list_learned)) }
            androidx.compose.material3.OutlinedButton({ open(row, LIST_BLACKLISTED) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                Text(stringResource(R.string.learned_words_list_blacklisted)) }
        }
    }
    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.learned_words))
                Text(keyboardName(keyboard, ctx), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        showKeyboardName = false, // (the keyboard is this one, whichever settings are being edited)
        filteredItems = { term -> rows.filter { s -> scriptName(s.script).startsWith(term, true)
            || s.locales.any { it.getLocaleDisplayNameForUserDictSettings(ctx).startsWith(term, true) } } },
        itemContent = { ScriptRow(it) },
    ) {
        rows.forEach { ScriptRow(it) }
        if (advanced) HorizontalDivider()
        if (advanced) Preference(
            name = stringResource(R.string.edit_personal_dictionary),
            description = stringResource(R.string.learned_words_personal_dictionary_summary),
            onClick = { SettingsDestination.navigateTo(SettingsDestination.PersonalDictionaries, ctx) },
        ) { NextScreenIcon() }
    }
}

/** The rows of [keyboard]'s scripts list: its own learned words (2026-10-10). */
internal fun scriptRows(context: Context, keyboard: helium314.keyboard.latin.settings.SettingsSubtype): List<ScriptRowData> {
    // (a lookup, owner 2026-10-11: a keyboard without a pool has no learned words to show)
    val pool = LearnedKeyboards.poolOrNull(context.realPrefs(), keyboard) ?: return emptyList()
    return (listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)).groupBy { it.script() }
        .map { (script, locales) -> ScriptRowData(script, locales, pool) }
}

/*
 * Batch delete (owner 2026-10-11: "longpress to start multi-select and then tap tap tap tap to select multiples and
 * delete all at once"): a long-press on a word starts selection mode with it ticked; a tap then ticks / unticks a row
 * (no word ticked: the mode ends). The selection is of this screen's one list (Learned or Blacklisted words of the
 * script); Select all ticks the words shown (those the search finds, while searching). Delete asks once, as the single
 * delete; with every word of the list ticked a second time (owner 2026-10-11: "select all and delete should have
 * additional warning for are you sure?"). Then each word goes exactly as by the single delete, in one background job.
 * Back, the back arrow and the cross leave selection mode first. Numbers / edit / batch edit: later.
 */
@Composable
fun LearnedWordsScreen(onClickBack: () -> Unit, script: String, blacklisted: Boolean, pool: Int = LearnedStores.SHARED) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data: WordLists? by remember { mutableStateOf(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { data = withContext(Dispatchers.IO) { readWordLists(ctx, script, pool) } }
    var selected: Item? by remember { mutableStateOf(null) }
    var adding by remember { mutableStateOf(false) }
    val locales = remember(script) { scriptLocales()[script].orEmpty() }
    // selection mode: the keys (Entry.key) of the words ticked; none: not selecting
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var askDelete by remember { mutableStateOf(false) }
    var askDeleteAll by remember { mutableStateOf(false) }
    val selecting = picked.isNotEmpty()
    val kind = if (blacklisted) Kind.BLACKLISTED else Kind.YOURS
    val listEntries = data?.let { if (blacklisted) it.blacklisted else it.yours }.orEmpty()
    // the search term the list was last shown for (Select all ticks what's shown)
    val shownTerm = remember { arrayOf("") }

    // the delete dialog's "Also delete them from your device's personal dictionary" (owner 2026-10-12 "DM22 STRIP
    // REMOVE"): ticked each time a dialog opens, shown only when a word to delete is in Android's personal dictionary
    var alsoPersonal by remember { mutableStateOf(PersonalDictionaryChoice.SETTINGS_DEFAULT) }

    fun run(items: List<Item>) {
        val personal = PersonalDictionaryChoice.deletesFromPersonal(asksAboutPersonalDictionary(items), alsoPersonal)
        // one job for all of them, off the main thread
        scope.launch {
            withContext(Dispatchers.IO) { deleteWords(ctx, script, pool, locales, items, alsoFromPersonalDictionary = personal) }
            picked = emptySet()
            reload++
        }
    }
    @Composable
    fun DeleteContent(message: Int, items: List<Item>) {
        Column {
            Text(stringResource(message))
            if (asksAboutPersonalDictionary(items)) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(top = 8.dp).clickable { alsoPersonal = !alsoPersonal },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = alsoPersonal, onCheckedChange = { alsoPersonal = it })
                    Text(stringResource(R.string.learned_words_delete_also_personal))
                }
                if (!alsoPersonal) Text(stringResource(R.string.learned_words_delete_personal_stays),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    fun toggle(key: String) { picked = if (key in picked) picked - key else picked + key }
    fun pickedItems() = listEntries.filter { it.key in picked }.map { Item(it, kind) }

    SearchScreen(
        // (read at the time of Back: the state now, not as composed)
        onClickBack = { if (picked.isNotEmpty()) picked = emptySet() else onClickBack() },
        title = {
            if (selecting) Text(pluralStringResource(R.plurals.learned_words_selected, picked.size, picked.size))
            else Column {
                Text(stringResource(if (blacklisted) R.string.learned_words_section_blacklisted else R.string.learned_words_section_yours))
                Text(scriptName(script), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        showAdvancedSwitch = !selecting,
        icon = if (selecting) ({}) else null, // (no search button while selecting)
        leadingActions = {
            if (selecting) {
                IconButton({ picked = WordLists.matching(listEntries, shownTerm[0]).mapTo(HashSet()) { it.key } }) {
                    Icon(painterResource(R.drawable.ic_select_all), stringResource(R.string.learned_words_select_all)) }
                IconButton({ alsoPersonal = PersonalDictionaryChoice.SETTINGS_DEFAULT; askDelete = true }) {
                    Icon(painterResource(R.drawable.ic_bin), stringResource(R.string.delete)) }
                IconButton({ picked = emptySet() }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.learned_words_selection_close)) }
            }
        },
        // Add a word: the list's first row (as Add keyboard on the keyboards list), not while searching or selecting
        filteredItems = { term -> shownTerm[0] = term;
            (if (term.isBlank() && !blacklisted && !selecting) listOf(Row.Add) else emptyList()) +
            (data?.rows(term, blacklisted) ?: listOf(Row.Note(R.string.learned_words_loading))) },
        itemContent = { row ->
            when (row) {
                Row.Add -> Preference(name = stringResource(R.string.user_dict_add_word_button), onClick = { adding = true },
                    icon = R.drawable.ic_plus)
                is Row.Heading -> PreferenceCategory(stringResource(row.title))
                is Row.Note -> Text(stringResource(row.text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
                is Item -> CompositionLocalProvider(LocalRowStart provides 22.dp) {
                    val key = row.entry.key
                    Preference(name = row.entry.word, description = row.description(),
                        // (the state at the time of the tap)
                        onClick = { if (picked.isNotEmpty()) toggle(key)
                            else { alsoPersonal = PersonalDictionaryChoice.SETTINGS_DEFAULT; selected = row } },
                        onLongClick = { if (picked.isNotEmpty()) toggle(key) else picked = setOf(key) },
                        value = if (!selecting) null else ({ Checkbox(checked = key in picked, onCheckedChange = { toggle(key) }) }),
                    )
                }
            }
        },
    )

    selected?.let { item ->
        ConfirmationDialog(
            onDismissRequest = { selected = null },
            onConfirmed = { run(listOf(item)) },
            title = { Text(item.entry.word) },
            content = { DeleteContent(if (item.kind == Kind.YOURS) R.string.learned_words_delete_message
                else R.string.learned_words_unblacklist_message, listOf(item)) },
            confirmButtonText = stringResource(R.string.delete),
        )
    }
    if (askDelete && selecting) {
        val n = picked.size
        ConfirmationDialog(
            onDismissRequest = { askDelete = false },
            onConfirmed = {
                // every word of the list: asked once more
                if (listEntries.all { it.key in picked }) askDeleteAll = true else run(pickedItems())
            },
            title = { Text(pluralStringResource(R.plurals.learned_words_delete_selected, n, n)) },
            content = { DeleteContent(if (blacklisted) R.string.learned_words_unblacklist_message
                else R.string.learned_words_delete_message, pickedItems()) },
            confirmButtonText = stringResource(R.string.delete),
        )
    }
    if (askDeleteAll && selecting) {
        val n = picked.size
        val list = stringResource(if (blacklisted) R.string.learned_words_section_blacklisted else R.string.learned_words_section_yours)
        ConfirmationDialog(
            onDismissRequest = { askDeleteAll = false },
            onConfirmed = { run(pickedItems()) },
            title = { Text(pluralStringResource(R.plurals.learned_words_delete_all_title, n, n, list, scriptName(script))) },
            content = { DeleteContent(R.string.learned_words_delete_all_message, pickedItems()) },
            confirmButtonText = stringResource(R.string.learned_words_delete_all_button),
        )
    }
    if (adding) // the personal dictionary's own add dialog
        EditWordDialog(Word("", null, null), locales.firstOrNull()) { adding = false; reload++ }
}

// ------------------------------- data -------------------------------

internal enum class Kind { YOURS, BLACKLISTED }

internal class PersonalEntry(val word: Word, val locale: Locale?)

/** One word of the list, merged over its spellings (case-insensitive). */
internal class Entry(
    val word: String, // the most typed spelling
    val spellings: Set<String>,
    val typed: Int, // how often typed in all, 0: not learned
    val personal: List<PersonalEntry>,
    val listed: List<String>, // blacklist rows: the spelling of each
    val strikes: Int, // how often removed (the most of its rows), 0: not blacklisted
) {
    /** The word's key in its list (the lists are case-insensitive): what selection mode ticks. */
    val key: String get() = word.lowercase()
}

internal sealed interface Row {
    object Add : Row
    class Heading(val title: Int) : Row
    class Note(val text: Int) : Row
}

internal class Item(val entry: Entry, val kind: Kind) : Row {
    @Composable
    fun description(): String? = when (kind) {
        Kind.YOURS -> listOfNotNull(
            when (entry.typed) {
                0 -> null
                1 -> stringResource(R.string.learned_words_typed_once)
                else -> stringResource(R.string.learned_words_typed_times, entry.typed)
            },
            if (entry.personal.isEmpty()) null else stringResource(R.string.learned_words_in_personal_dictionary),
        ).joinToString(" · ").ifEmpty { null }
        // (what the strikes mean is said once, under the section's heading)
        Kind.BLACKLISTED -> if (entry.strikes <= 1) stringResource(R.string.learned_words_removed_once)
            else stringResource(R.string.learned_words_removed_times, entry.strikes)
    }
}

internal class WordLists(val yours: List<Entry>, val blacklisted: List<Entry>) {
    /** One list: the learned words with the personal dictionary's, or the blacklisted words (the page's title says which). */
    fun rows(term: String, ofBlacklist: Boolean): List<Row> {
        val result = mutableListOf<Row>()
        val entries = if (ofBlacklist) blacklisted else yours
        val shown = matching(entries, term)
        if (ofBlacklist && shown.isNotEmpty() && term.isBlank()) result.add(Row.Note(R.string.learned_words_blacklisted_summary))
        if (shown.isEmpty()) result.add(Row.Note(R.string.learned_words_none))
        shown.mapTo(result) { Item(it, if (ofBlacklist) Kind.BLACKLISTED else Kind.YOURS) }
        return result
    }

    companion object {
        /** The [entries] the search for [term] finds (all for a blank term). */
        fun matching(entries: List<Entry>, term: String): List<Entry> =
            if (term.isBlank()) entries else entries.filter { e -> e.spellings.any { it.contains(term, true) } }
    }
}

/** The keyboard's languages (as for the personal dictionary) by script. */
private fun scriptLocales(): Map<String, List<Locale>> = getSortedDictionaryLocales().groupBy { it.script() }

private fun scriptName(script: String): String =
    runCatching { Locale.Builder().setScript(script).build().displayScript }.getOrNull()?.ifEmpty { null } ?: script

/** The learned words of [script] in [pool] with how often each was typed. */
private fun readLearned(context: Context, script: String, pool: Int): List<Pair<String, Int>> {
    // (no store yet: don't create one by asking)
    if (PersonalizationHelper.getCachedUserHistoryDictionary(script, pool) == null
        && !LearnedStores.storeFile(context.filesDir, script, pool).exists())
        return emptyList()
    return try {
        // all of it, however long the read takes (the quick dump gives up after 100 ms, on a cold start every time)
        val props = PersonalizationHelper.getUserHistoryDictionary(context, script, pool).allWordPropertiesBlocking
            ?: return emptyList()
        props.mapNotNull { wp ->
            val word = wp.mWord
            if (word.isNullOrBlank() || wp.mIsBeginningOfSentence || wp.mIsNotAWord) null
            // a word is stored at count 0 by its first use (see DictionaryFacilitatorImpl.isTrustedWord): typed count + 1 times
            else word to (wp.mProbabilityInfo.mCount.coerceAtLeast(0) + 1)
        }
    } catch (e: Exception) {
        Log.w("LearnedWordsScreen", "could not read the learned words of $script", e)
        emptyList()
    }
}

private fun readPersonal(context: Context, locale: Locale?): List<PersonalEntry> {
    val select = if (locale == null) "${UserDictionary.Words.LOCALE} is null" else "${UserDictionary.Words.LOCALE}=?"
    // (the locale string, not the language tag, for the Android personal dictionary)
    val args = if (locale == null) null else arrayOf(locale.toString())
    val projection = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT, UserDictionary.Words.FREQUENCY)
    val result = mutableListOf<PersonalEntry>()
    try {
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI, projection, select, args, null)?.use { c ->
            while (c.moveToNext()) {
                val word = c.getString(0) ?: continue
                result.add(PersonalEntry(Word(word, c.getString(1), c.getInt(2)), locale))
            }
        }
    } catch (e: Exception) {
        Log.w("LearnedWordsScreen", "could not read the personal dictionary", e)
    }
    return result
}

private class Builder(val lower: String) {
    val counts = LinkedHashMap<String, Int>() // spelling -> times typed
    val personal = mutableListOf<PersonalEntry>()
    val listed = mutableListOf<String>()
    var strikes = 0
    fun build(): Entry {
        val spellings = counts.keys + personal.map { it.word.word } + listed
        val word = counts.maxByOrNull { it.value }?.key ?: personal.firstOrNull()?.word?.word ?: listed.firstOrNull() ?: lower
        return Entry(word, spellings.toSet(), counts.values.sum(), personal, listed, strikes)
    }
}

private fun readWordLists(context: Context, script: String, pool: Int): WordLists {
    // the personal dictionary is Android's, per language: its words of this script from every language
    val personal = scriptLocales().flatMap { (localeScript, locales) -> locales.flatMap { locale ->
        readPersonal(context, locale).map { it to localeScript } } } +
        readPersonal(context, null).map { it to ScriptUtils.SCRIPT_LATIN } // for all languages
    return buildWordLists(script, readLearned(context, script, pool), personal,
        RemovedWords.blacklist(context, script, pool).apply { reload() }.entries())
}

/**
 * The two lists of [script]: its [learned] words (with how often each was typed) together with the [personal]
 * dictionary's words of the script (each with the script of its language, for a word without letters), and the
 * [blacklist]; each word once, case-insensitive.
 */
internal fun buildWordLists(script: String, learned: List<Pair<String, Int>>, personal: List<Pair<PersonalEntry, String>>,
                            blacklist: Map<String, RemovedWords.Entry>): WordLists {
    val yours = HashMap<String, Builder>()
    val blacklisted = HashMap<String, Builder>()
    fun MutableMap<String, Builder>.of(word: String) = word.lowercase().let { getOrPut(it) { Builder(it) } }
    // (the store is the script's: its words are of the script, a word without letters too)
    for ((word, typed) in learned) yours.of(word).apply { counts[word] = (counts[word] ?: 0) + typed }
    for ((p, localeScript) in personal)
        if (ScriptUtils.scriptOfWord(p.word.word, localeScript) == script) yours.of(p.word.word).personal.add(p)
    for ((word, removed) in blacklist) blacklisted.of(word).apply {
        listed.add(word)
        strikes = maxOf(strikes, removed.strikes)
    }
    fun Map<String, Builder>.sorted() = values.sortedBy { it.lower }.map { it.build() }
    return WordLists(yours.sorted(), blacklisted.sorted())
}

// ------------------------------- edits -------------------------------
// The keyboard sees them at once: Remove goes through the running keyboard's own dictionaries if it has them loaded
// (DictionaryFacilitatorImpl.removeWords), the learned words are the same UserHistoryDictionary objects it uses
// (PersonalizationHelper's cache), the personal dictionary is watched by its UserBinaryDictionary, and the blacklists
// are the same RemovedWords objects it checks (settings and keyboard: one process).

/** The delete dialog shows "Also delete them from your device's personal dictionary": a word of [items] (Your words) is
 *  in Android's personal dictionary. */
internal fun asksAboutPersonalDictionary(items: List<Item>): Boolean =
    items.any { it.kind == Kind.YOURS && it.entry.personal.isNotEmpty() }

/** A row of Android's personal dictionary deleted (for its language, or for all languages). */
private fun deletePersonalRow(context: Context, p: PersonalEntry) {
    if (p.locale == null)
        context.contentResolver.delete(UserDictionary.Words.CONTENT_URI,
            "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE} is null", arrayOf(p.word.word))
    else
        context.contentResolver.delete(UserDictionary.Words.CONTENT_URI,
            "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE}=?", arrayOf(p.word.word, p.locale.toString()))
}

/**
 * Deletes [items], each exactly as the single delete does (call off the main thread). [alsoFromPersonalDictionary]
 * false (the dialog's box unticked): Android's personal dictionary keeps them, in the keyboard's copy too.
 * [deletePersonal]: how a row of it is deleted (tests).
 */
internal fun deleteWords(context: Context, script: String, pool: Int, scriptLocales: List<Locale>, items: List<Item>,
                         alsoFromPersonalDictionary: Boolean = true,
                         deletePersonal: (Context, PersonalEntry) -> Unit = ::deletePersonalRow) {
    for (item in items) apply(context, script, pool, scriptLocales, item, alsoFromPersonalDictionary, deletePersonal)
}

private fun apply(context: Context, script: String, pool: Int, scriptLocales: List<Locale>, item: Item,
                  alsoFromPersonalDictionary: Boolean, deletePersonal: (Context, PersonalEntry) -> Unit) {
    val entry = item.entry
    when (item.kind) {
        Kind.YOURS -> {
            // every capitalization of each spelling out of the script's learned words, no strike (a plain delete); the
            // languages of the script (and those whose personal dictionary has it) say how it's listed
            val locales = (scriptLocales + entry.personal.mapNotNull { it.locale }).distinct()
            try {
                if (LearningEventLog.isEnabled()) {
                    val counts = LearningEventLog.countsIn(PersonalizationHelper.getUserHistoryDictionary(context, script, pool))
                    for (word in entry.spellings)
                        LearningEventLog.log(LearningEventLog.REMOVED, LearningEventLog.SETTINGS, word, "",
                            LearnedStores.label(script, pool), counts)
                }
                DictionaryFacilitatorImpl.removeWords(context, script, pool, locales, entry.spellings, blacklist = false,
                    personal = alsoFromPersonalDictionary)
            } catch (e: Exception) {
                Log.w("LearnedWordsScreen", "could not remove a word of $script", e)
            }
            // and its rows in the Android personal dictionary (each spelling found, for its language or all), unless
            // the box was unticked (they stay there, and so on this list); an allowed row (RemovedWords) stays either
            // way: this forgets the count only
            if (alsoFromPersonalDictionary) for (p in entry.personal)
                try { deletePersonal(context, p) } catch (e: Exception) { Log.w("LearnedWordsScreen", "could not delete a personal dictionary word", e) }
            // its recent and frequent uses go too (as LatinIME.removeSuggestion)
            entry.spellings.forEach { HotWords.forget(it); FrequentLongWords.forget(it) }
        }
        Kind.BLACKLISTED -> entry.listed.forEach { word ->
            // its strikes go with it (and it's swipeable again)
            if (RemovedWords.blacklist(context, script, pool).remove(word)) {
                helium314.keyboard.latin.gesture.GestureDecoderVocabulary.onWordUnblacklisted(LearnedStores.storeLocale(script), word)
                if (LearningEventLog.isEnabled())
                    LearningEventLog.log(LearningEventLog.RESTORED, LearningEventLog.SETTINGS, "", word, LearnedStores.label(script, pool),
                        LearningEventLog.countsIn(PersonalizationHelper.getUserHistoryDictionary(context, script, pool)))
            }
        }
    }
}

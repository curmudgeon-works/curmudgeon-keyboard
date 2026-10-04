// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.provider.UserDictionary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.personalization.UserHistoryDictionary
import helium314.keyboard.latin.utils.FrequentLongWords
import helium314.keyboard.latin.utils.HotWords
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
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
import java.io.File
import java.util.Locale

/*
 * "Learned & blacklisted words" (Text correction): per script (Latin, Devanagari, …), not per language, since the
 * languages of one script share their words in practice (English and Hinglish both Latin). Per script:
 * - Your words: what the keyboard learned (user history) in every language of the script, merged with the Android
 *   personal dictionary's words of those languages (and its "for all languages" ones), each word once (case-insensitive,
 *   shown in its most typed spelling); a word's script comes from its letters, so a Latin word learned in a Devanagari
 *   language is listed under Latin. Remove: the same as long-press Remove (DictionaryFacilitatorImpl.removeWord), in
 *   every language of the script, plus its rows in the Android personal dictionary
 * - Blacklisted words: the long-press Remove lists (filesDir/blacklists/<tag>.txt, see RemovedWords). Un-blacklist.
 * A further section (another list of the kind) is one more Kind with its action and one more section() in rows().
 * The personal dictionary's own screens (shortcuts, weights, "for all languages", editing) stay reachable from the
 * first screen, and Add a word here is its add dialog.
 */

/** The scripts of the keyboard's languages, then the personal dictionary's own screens. */
@Composable
fun LearnedWordsScriptsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val scripts = remember { scriptLocales().toList() }
    @Composable
    fun ScriptRow(script: Pair<String, List<Locale>>) {
        Preference(
            name = scriptName(script.first),
            description = script.second.joinToString(", ") { it.getLocaleDisplayNameForUserDictSettings(ctx) },
            onClick = { SettingsDestination.navigateTo(SettingsDestination.LearnedWordsOfScript + script.first) },
        ) { NextScreenIcon() }
    }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.learned_words)) },
        filteredItems = { term -> scripts.filter { s -> scriptName(s.first).startsWith(term, true)
            || s.second.any { it.getLocaleDisplayNameForUserDictSettings(ctx).startsWith(term, true) } } },
        itemContent = { ScriptRow(it) },
    ) {
        scripts.forEach { ScriptRow(it) }
        HorizontalDivider()
        Preference(
            name = stringResource(R.string.edit_personal_dictionary),
            description = stringResource(R.string.learned_words_personal_dictionary_summary),
            onClick = { SettingsDestination.navigateTo(SettingsDestination.PersonalDictionaries) },
        ) { NextScreenIcon() }
    }
}

@Composable
fun LearnedWordsScreen(onClickBack: () -> Unit, script: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data: WordLists? by remember { mutableStateOf(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { data = withContext(Dispatchers.IO) { readWordLists(ctx, script) } }
    var selected: Item? by remember { mutableStateOf(null) }
    var adding by remember { mutableStateOf(false) }
    val locales = remember(script) { scriptLocales()[script].orEmpty() }

    fun run(item: Item) {
        scope.launch {
            withContext(Dispatchers.IO) { apply(ctx, locales, item) }
            reload++
        }
    }

    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.learned_words))
                Text(scriptName(script), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        filteredItems = { term -> data?.rows(term) ?: listOf(Row.Note(R.string.learned_words_loading)) },
        itemContent = { row ->
            when (row) {
                is Row.Heading -> PreferenceCategory(stringResource(row.title))
                is Row.Note -> Text(stringResource(row.text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, end = 12.dp, top = 4.dp, bottom = 8.dp))
                is Item -> CompositionLocalProvider(LocalRowStart provides 22.dp) {
                    Preference(name = row.entry.word, description = row.description(), onClick = { selected = row })
                }
            }
        },
    )
    ExtendedFloatingActionButton(
        onClick = { adding = true },
        text = { Text(stringResource(R.string.user_dict_add_word_button)) },
        icon = { Icon(painter = painterResource(R.drawable.ic_edit), stringResource(R.string.user_dict_add_word_button)) },
        modifier = Modifier.wrapContentSize(Alignment.BottomEnd).padding(all = 12.dp).then(Modifier.safeDrawingPadding())
    )

    selected?.let { item ->
        ConfirmationDialog(
            onDismissRequest = { selected = null },
            onConfirmed = { run(item) },
            title = { Text(item.entry.word) },
            content = { Text(stringResource(if (item.kind == Kind.YOURS) R.string.learned_words_remove_message
                else R.string.learned_words_unblacklist_message)) },
            confirmButtonText = stringResource(if (item.kind == Kind.YOURS) R.string.remove else R.string.learned_words_action_unblacklist),
        )
    }
    if (adding) // the personal dictionary's own add dialog
        EditWordDialog(Word("", null, null), locales.firstOrNull()) { adding = false; reload++ }
}

// ------------------------------- data -------------------------------

private enum class Kind { YOURS, BLACKLISTED }

private class PersonalEntry(val word: Word, val locale: Locale?)

/** One word of the list, merged over the script's languages and its spellings (case-insensitive). */
private class Entry(
    val word: String, // the most typed spelling
    val spellings: Set<String>,
    val typed: Int, // how often typed in all, 0: not learned
    val learnedIn: Set<Locale>,
    val personal: List<PersonalEntry>,
    val listedIn: List<Pair<Locale, String>>, // blacklist rows: the language and spelling of each
)

private sealed interface Row {
    class Heading(val title: Int) : Row
    class Note(val text: Int) : Row
}

private class Item(val entry: Entry, val kind: Kind) : Row {
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
        Kind.BLACKLISTED -> null // (what the section means is said once, under its heading)
    }
}

private class WordLists(val yours: List<Entry>, val blacklisted: List<Entry>) {
    fun rows(term: String): List<Row> {
        val result = mutableListOf<Row>()
        fun section(title: Int, summary: Int?, entries: List<Entry>, kind: Kind) {
            val shown = if (term.isBlank()) entries else entries.filter { e -> e.spellings.any { it.contains(term, true) } }
            if (term.isNotBlank() && shown.isEmpty()) return
            result.add(Row.Heading(title))
            if (summary != null && shown.isNotEmpty()) result.add(Row.Note(summary))
            if (shown.isEmpty()) result.add(Row.Note(R.string.learned_words_none))
            shown.mapTo(result) { Item(it, kind) }
        }
        section(R.string.learned_words_section_yours, null, yours, Kind.YOURS)
        section(R.string.learned_words_section_blacklisted, R.string.learned_words_blacklisted_summary, blacklisted, Kind.BLACKLISTED)
        return result
    }
}

/** The keyboard's languages (as for the personal dictionary) by script. */
private fun scriptLocales(): Map<String, List<Locale>> = getSortedDictionaryLocales().groupBy { it.script() }

private fun scriptName(script: String): String =
    runCatching { Locale.Builder().setScript(script).build().displayScript }.getOrNull()?.ifEmpty { null } ?: script

private val knownScripts = listOf(
    ScriptUtils.SCRIPT_LATIN, ScriptUtils.SCRIPT_CYRILLIC, ScriptUtils.SCRIPT_GREEK, ScriptUtils.SCRIPT_ARMENIAN,
    ScriptUtils.SCRIPT_ARABIC, ScriptUtils.SCRIPT_HEBREW, ScriptUtils.SCRIPT_DEVANAGARI, ScriptUtils.SCRIPT_BENGALI,
    ScriptUtils.SCRIPT_GUJARATI, ScriptUtils.SCRIPT_TAMIL, ScriptUtils.SCRIPT_TELUGU, ScriptUtils.SCRIPT_KANNADA,
    ScriptUtils.SCRIPT_MALAYALAM, ScriptUtils.SCRIPT_SINHALA, ScriptUtils.SCRIPT_THAI, ScriptUtils.SCRIPT_LAO,
    ScriptUtils.SCRIPT_KHMER, ScriptUtils.SCRIPT_MYANMAR, ScriptUtils.SCRIPT_GEORGIAN, ScriptUtils.SCRIPT_HANGUL,
)

/** The script of the word's first letter; [fallback] (its language's) for a word without letters of a known script. */
private fun wordScript(word: String, fallback: String): String {
    var i = 0
    while (i < word.length) {
        val cp = word.codePointAt(i)
        if (Character.isLetter(cp)) knownScripts.firstOrNull { ScriptUtils.isLetterPartOfScript(cp, it) }?.let { return it }
        i += Character.charCount(cp)
    }
    return fallback
}

private const val READ_ATTEMPTS = 10
private const val READ_RETRY_DELAY_MS = 300L

/** The learned words of [locale] with their counts; the dump gives up after 100 ms on a cold start, hence the retries. */
private fun readLearned(context: Context, locale: Locale): List<Pair<String, Int>> {
    // (no store yet: don't create one by asking)
    if (!File(context.filesDir, UserHistoryDictionary.NAME + "." + locale.toLanguageTag() + ".dict").exists())
        return emptyList()
    return try {
        val history = PersonalizationHelper.getUserHistoryDictionary(context, locale)
        var props = history.wordPropertiesForSyncing
        var attempts = 0
        while (props.isEmpty() && attempts++ < READ_ATTEMPTS) {
            Thread.sleep(READ_RETRY_DELAY_MS)
            props = history.wordPropertiesForSyncing
        }
        props.mapNotNull { wp ->
            val word = wp.mWord
            if (word.isNullOrBlank() || wp.mIsBeginningOfSentence || wp.mIsNotAWord) null
            // a word is stored at count 0 by its first use (see DictionaryFacilitatorImpl.isTrustedWord): typed count + 1 times
            else word to (wp.mProbabilityInfo.mCount.coerceAtLeast(0) + 1)
        }
    } catch (e: Exception) {
        Log.w("LearnedWordsScreen", "could not read the learned words of ${locale.toLanguageTag()}", e)
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
    val learnedIn = mutableSetOf<Locale>()
    val personal = mutableListOf<PersonalEntry>()
    val listedIn = mutableListOf<Pair<Locale, String>>()
    fun build(): Entry {
        val spellings = counts.keys + personal.map { it.word.word } + listedIn.map { it.second }
        val word = counts.maxByOrNull { it.value }?.key ?: personal.firstOrNull()?.word?.word ?: listedIn.firstOrNull()?.second ?: lower
        return Entry(word, spellings.toSet(), counts.values.sum(), learnedIn, personal, listedIn)
    }
}

private fun readWordLists(context: Context, script: String): WordLists {
    val all = scriptLocales()
    val yours = HashMap<String, Builder>()
    val blacklisted = HashMap<String, Builder>()
    fun MutableMap<String, Builder>.of(word: String) = word.lowercase().let { getOrPut(it) { Builder(it) } }
    // every language: a word of this script may be learned in a language of another one
    for ((localeScript, locales) in all) for (locale in locales) {
        for ((word, typed) in readLearned(context, locale)) {
            if (wordScript(word, localeScript) != script) continue
            yours.of(word).apply { counts[word] = (counts[word] ?: 0) + typed; learnedIn.add(locale) }
        }
        for (p in readPersonal(context, locale))
            if (wordScript(p.word.word, localeScript) == script) yours.of(p.word.word).personal.add(p)
        for (word in RemovedWords.blacklist(context, locale).apply { reload() }.words())
            if (wordScript(word, localeScript) == script) blacklisted.of(word).listedIn.add(locale to word)
    }
    for (p in readPersonal(context, null)) // for all languages
        if (wordScript(p.word.word, ScriptUtils.SCRIPT_LATIN) == script) yours.of(p.word.word).personal.add(p)
    fun Map<String, Builder>.sorted() = values.sortedBy { it.lower }.map { it.build() }
    return WordLists(yours.sorted(), blacklisted.sorted())
}

// ------------------------------- edits -------------------------------
// The keyboard sees them at once: Remove goes through the running keyboard's own dictionaries of the language if it has
// them loaded (DictionaryFacilitatorImpl.removeWord), the learned words are the same UserHistoryDictionary objects it
// uses (PersonalizationHelper's cache), the personal dictionary is watched by its UserBinaryDictionary, and the
// blacklists are the same RemovedWords objects its dictionaries check (settings and keyboard: one process).

private fun apply(context: Context, scriptLocales: List<Locale>, item: Item) {
    val entry = item.entry
    when (item.kind) {
        Kind.YOURS -> {
            // as long-press Remove (every capitalization of each spelling), in every language of the script holding it
            val locales = (scriptLocales + entry.learnedIn + entry.personal.mapNotNull { it.locale }).distinct()
            for (locale in locales) {
                try {
                    DictionaryFacilitatorImpl.removeWords(context, locale, entry.spellings)
                } catch (e: Exception) {
                    Log.w("LearnedWordsScreen", "could not remove a word in ${locale.toLanguageTag()}", e)
                }
            }
            // and its rows in the Android personal dictionary (each spelling found, for its language or all)
            for (p in entry.personal) {
                if (p.locale == null)
                    context.contentResolver.delete(UserDictionary.Words.CONTENT_URI,
                        "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE} is null", arrayOf(p.word.word))
                else
                    context.contentResolver.delete(UserDictionary.Words.CONTENT_URI,
                        "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE}=?", arrayOf(p.word.word, p.locale.toString()))
            }
            // its recent and frequent uses go too (as LatinIME.removeSuggestion)
            entry.spellings.forEach { HotWords.forget(it); FrequentLongWords.forget(it) }
        }
        Kind.BLACKLISTED -> entry.listedIn.forEach { (locale, word) ->
            // (and swipeable again)
            if (RemovedWords.blacklist(context, locale).remove(word))
                helium314.keyboard.latin.gesture.GestureDecoderVocabulary.onWordUnblacklisted(locale, word)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.provider.UserDictionary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
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
 * Groups (see ListView): a tap on a heading folds / unfolds it, a long-press ticks its words (all of them ticked
 * already: unticks them; owner 2026-10-17: "I should be able to press and hold the header to select all itmes in that
 * group too"). Selection mode keeps the rows above the words and the title's height as they are, so the word pressed
 * stays where it is (owner 2026-10-17: "the word I pressed moves up when the checkmarks come on").
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
    var view by remember { mutableStateOf(ListView.read(ctx, blacklisted)) }
    fun setView(new: ListView) { view = new; ListView.write(ctx, blacklisted, new) }
    var folded by remember { mutableStateOf(emptySet<String>()) }
    var showSortMenu by remember { mutableStateOf(false) }

    // the delete dialog's "Also delete them from your device's personal dictionary" (owner 2026-10-12 "DM22 STRIP
    // REMOVE"): ticked each time a dialog opens, shown only when a word to delete is in Android's personal dictionary
    var alsoPersonal by remember { mutableStateOf(PersonalDictionaryChoice.SETTINGS_DEFAULT) }

    fun run(items: List<Item>) {
        val personal = PersonalDictionaryChoice.deletesFromPersonal(asksAboutPersonalDictionary(items), alsoPersonal)
        // one job for all of them, off the main thread
        scope.launch {
            withContext(Dispatchers.IO) { deleteWords(ctx, script, pool, locales, items, alsoFromPersonalDictionary = personal) }
            // only the deleted words' ticks go: words ticked while it ran stay ticked (review L4.4)
            picked = picked - items.map { it.entry.key }.toSet()
            reload++
        }
    }
    @Composable
    fun DeleteContent(message: String, items: List<Item>) {
        Column {
            Text(message)
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
            // (two lines in selection mode too: the list doesn't move up when it starts)
            Column {
                Text(if (selecting) pluralStringResource(R.plurals.learned_words_selected, picked.size, picked.size)
                    else stringResource(if (blacklisted) R.string.learned_words_section_blacklisted else R.string.learned_words_section_yours))
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
            } else androidx.compose.foundation.layout.Box {
                IconButton({ showSortMenu = true }) {
                    Icon(painterResource(if (view.sort == SortBy.DATE) R.drawable.ic_sort_chronologically else R.drawable.ic_sort_alphabetically),
                        stringResource(R.string.learned_words_sort_menu)) }
                SortMenu(showSortMenu, { showSortMenu = false }, view, blacklisted) { setView(it) }
            }
        },
        // Add a word: the list's first row (as Add keyboard on the keyboards list), not while searching; greyed while
        // selecting (not taken away: the words below would move up)
        filteredItems = { term -> shownTerm[0] = term;
            (if (term.isBlank() && !blacklisted) listOf(Row.Add) else emptyList()) +
            (data?.rows(term, blacklisted, view, folded) ?: listOf(Row.Note(R.string.learned_words_loading))) },
        itemContent = { row ->
            when (row) {
                Row.Add -> Preference(name = stringResource(R.string.user_dict_add_word_button),
                    onClick = { if (picked.isEmpty()) adding = true }, icon = R.drawable.ic_plus,
                    modifier = if (selecting) Modifier.alpha(0.38f) else Modifier)
                is Row.Heading -> PreferenceCategory(stringResource(row.title))
                is Row.Group -> GroupHeading(row,
                    onClick = { folded = if (row.id in folded) folded - row.id else folded + row.id },
                    // (the state at the time of the press)
                    onLongClick = { picked = if (picked.containsAll(row.keys)) picked - row.keys.toSet() else picked + row.keys })
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
            content = { DeleteContent(stringResource(if (item.kind == Kind.YOURS) R.string.learned_words_delete_message
                else R.string.learned_words_unblacklist_message), listOf(item)) },
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
            // (plural wording, review L4.3)
            content = { DeleteContent(pluralStringResource(if (blacklisted) R.plurals.learned_words_unblacklist_message_batch
                else R.plurals.learned_words_delete_message_batch, n, n), pickedItems()) },
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
            content = { DeleteContent(stringResource(R.string.learned_words_delete_all_message), pickedItems()) },
            confirmButtonText = stringResource(R.string.learned_words_delete_all_button),
        )
    }
    if (adding) // the personal dictionary's own add dialog
        EditWordDialog(Word("", null, null), locales.firstOrNull()) { adding = false; reload++ }
}

/** The sort and group menu: the sort in use shows its order (picked again: turned round), the grouping a tick. */
@Composable
private fun SortMenu(expanded: Boolean, onDismiss: () -> Unit, view: ListView, blacklisted: Boolean, onPick: (ListView) -> Unit) {
    // (its own window: the screen's keyboard all the same, as SearchScreen's menu)
    val keyboardId = helium314.keyboard.latin.settings.KeyboardScopeContext.idOf(LocalContext.current)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { helium314.keyboard.settings.KeyboardScope(keyboardId) { Column {
        @Composable
        fun Label(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        @Composable
        fun Choice(text: Int, chosen: Boolean, mark: String, pick: () -> Unit) = DropdownMenuItem(
            text = { Text(stringResource(text)) }, onClick = { pick(); onDismiss() },
            trailingIcon = if (chosen) ({ Text(mark) }) else null)
        Label(R.string.learned_words_sort_heading)
        val sorts = listOf(SortBy.ALPHA to R.string.learned_words_sort_alpha,
            SortBy.COUNT to (if (blacklisted) R.string.learned_words_sort_strikes else R.string.learned_words_sort_typed),
            SortBy.DATE to (if (blacklisted) R.string.learned_words_sort_last_removed else R.string.learned_words_sort_last_typed))
        for ((by, text) in sorts) Choice(text, view.sort == by, if (view.descending) "↓" else "↑") { onPick(view.sortedBy(by)) }
        // (the blacklist has one grouping only, the sort's: no choice to show)
        if (!blacklisted) {
            HorizontalDivider()
            Label(R.string.learned_words_group_heading)
            val groups = listOf(GroupBy.SORT to R.string.learned_words_group_same_as_sort, GroupBy.SOURCE to R.string.learned_words_group_source)
            for ((by, text) in groups) Choice(text, view.group == by, "✓") { onPick(view.copy(group = by)) }
        }
    } } }
}

/** A group's heading, as the list's other headings, with its number of words and whether it's folded. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupHeading(group: Row.Group, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        HorizontalDivider()
        androidx.compose.foundation.layout.Row(
            Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${group.title.text()} · ${group.keys.size}", Modifier.weight(1f),
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleMedium)
            // pointing down while open, right while folded
            Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.rotate(if (group.folded) 180f else -90f),
                tint = MaterialTheme.colorScheme.secondary)
        }
    }
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
    val lastTyped: Long = 0, // when last typed (seconds, the latest of its spellings), 0: not learned
    val removedAt: Long = 0, // when last removed (seconds, the latest of its rows), 0: not known
    val inDictionary: Boolean = false, // a main dictionary of the script's languages has it (a spelling of it)
    val confirmed: Boolean = false, // removed 3 times and brought back with the strip's "+"
) {
    /** The word's key in its list (the lists are case-insensitive): what selection mode ticks. */
    val key: String get() = word.lowercase()
}

internal sealed interface Row {
    object Add : Row
    class Heading(val title: Int) : Row
    class Note(val text: Int) : Row
    /** A group's heading: [keys] are its words (shown by the search), [folded]: its words aren't listed. */
    class Group(val id: String, val title: GroupTitle, val keys: List<String>, val folded: Boolean) : Row
}

/** What a group heading says. */
internal sealed interface GroupTitle {
    class Letter(val letter: String) : GroupTitle // "#" for a word not starting with a letter
    class Typed(val from: Int, val to: Int?) : GroupTitle // to null: more than [from] - 1
    object NotTyped : GroupTitle // in the personal dictionary only
    class Strikes(val strikes: Int, val confirmed: Boolean) : GroupTitle
    object New : GroupTitle
    object Dictionary : GroupTitle
    object ThisWeek : GroupTitle // last typed / removed in the last 7 days
    object ThisMonth : GroupTitle // in the last 30 days, before that
    object Older : GroupTitle
    object DateUnknown : GroupTitle

    @Composable
    fun text(): String = when (this) {
        is Letter -> letter
        is Typed -> when {
            to == null -> stringResource(R.string.learned_words_group_typed_more, from - 1)
            from == 1 && to == 1 -> stringResource(R.string.learned_words_group_typed_once)
            from == to -> stringResource(R.string.learned_words_group_typed_exactly, from)
            else -> stringResource(R.string.learned_words_group_typed_range, from, to)
        }
        NotTyped -> stringResource(R.string.learned_words_group_not_typed)
        is Strikes -> when {
            confirmed -> stringResource(R.string.learned_words_group_removed_confirmed)
            strikes <= 1 -> stringResource(R.string.learned_words_removed_once)
            else -> stringResource(R.string.learned_words_removed_times, strikes)
        }
        New -> stringResource(R.string.learned_words_group_new)
        Dictionary -> stringResource(R.string.learned_words_group_dictionary)
        ThisWeek -> stringResource(R.string.learned_words_group_this_week)
        ThisMonth -> stringResource(R.string.learned_words_group_this_month)
        Older -> stringResource(R.string.learned_words_group_older)
        DateUnknown -> stringResource(R.string.learned_words_group_date_unknown)
    }
}

/*
 * Sorting and grouping (owner 2026-10-17: "Learned & blacklisted words page should have options to sort (1) learned by
 * typed count and by date late typed, both back and fwd; (2) blacklisted by strikes and by last deleted date"; "lists
 * should also be able to be grouped by these -- typed count, strike count"; ranges "1, 2, 3, 4, 5-10, 11-20, 21-50,
 * 50+"; "Heading should be first letter of the word by default, can be changed to count group"; "Agree we should have a
 * grouping for personal word / dictionary word"). The choice is remembered per list. Picking the sort in use again
 * turns its order round. Inside a group the sort applies; words without a count or date (personal dictionary only, a
 * removal from before dates were kept) go last either way, and equal ones A–Z.
 * Groups follow the sort (owner 2026-10-18: "groups follow sort"): A–Z gives first letters, Times typed / Strikes the
 * count groups, Last typed / Last removed date groups (this week, this month, older); "New or dictionary word" stays as
 * the one other grouping (Your words only).
 */
internal enum class SortBy { ALPHA, COUNT, DATE }
/** SORT: the groups of the sort in use (letters, counts or dates). */
internal enum class GroupBy { SORT, SOURCE }

internal data class ListView(val sort: SortBy = SortBy.ALPHA, val descending: Boolean = false, val group: GroupBy = GroupBy.SORT) {
    /** [by] picked in the menu: the sort in use turned round, another one from its usual end (A–Z; most, latest first). */
    fun sortedBy(by: SortBy) = if (by == sort) copy(descending = !descending) else copy(sort = by, descending = by != SortBy.ALPHA)

    fun encode() = "$sort,$descending,$group"

    companion object {
        fun decode(value: String?): ListView = try {
            val (sort, descending, group) = value!!.split(',')
            // (the groupings before they followed the sort, first letter and count: the sort's now)
            ListView(SortBy.valueOf(sort), descending.toBooleanStrict(),
                if (group == "LETTER" || group == "COUNT") GroupBy.SORT else GroupBy.valueOf(group))
        } catch (e: Exception) { ListView() }

        // (a view setting of this screen, not a keyboard setting: its own file, not in the settings or their backups)
        private fun prefs(context: Context) = context.getSharedPreferences("learned_words_view", Context.MODE_PRIVATE)
        fun read(context: Context, blacklisted: Boolean) = decode(prefs(context).getString(key(blacklisted), null))
        fun write(context: Context, blacklisted: Boolean, view: ListView) = prefs(context).edit().putString(key(blacklisted), view.encode()).apply()
        private fun key(blacklisted: Boolean) = if (blacklisted) LIST_BLACKLISTED else LIST_LEARNED
    }
}

/** The learned-words count groups (owner 2026-10-17): the group of a word typed [typed] times, 0: not typed. */
internal fun typedGroup(typed: Int): GroupTitle = when {
    typed <= 0 -> GroupTitle.NotTyped
    typed <= 4 -> GroupTitle.Typed(typed, typed)
    typed <= 10 -> GroupTitle.Typed(5, 10)
    typed <= 20 -> GroupTitle.Typed(11, 20)
    typed <= 50 -> GroupTitle.Typed(21, 50)
    else -> GroupTitle.Typed(51, null)
}

/** The date groups: the group of a word last typed / removed at [seconds] (0: not known), [now] in seconds. */
internal fun dateGroup(seconds: Long, now: Long): GroupTitle = when {
    seconds <= 0 -> GroupTitle.DateUnknown
    now - seconds <= 7 * DAY_SECONDS -> GroupTitle.ThisWeek
    now - seconds <= 30 * DAY_SECONDS -> GroupTitle.ThisMonth
    else -> GroupTitle.Older
}

private const val DAY_SECONDS = 24 * 60 * 60L

internal class Item(val entry: Entry, val kind: Kind) : Row {
    @Composable
    fun description(): String? = when (kind) {
        Kind.YOURS -> listOfNotNull(
            when (entry.typed) {
                0 -> null
                1 -> stringResource(R.string.learned_words_typed_once)
                else -> stringResource(R.string.learned_words_typed_times, entry.typed)
            },
            // (owner 2026-10-17: "date visible always")
            if (entry.lastTyped <= 0) null else stringResource(R.string.learned_words_last_typed, dateText(entry.lastTyped)),
            if (entry.personal.isEmpty()) null else stringResource(R.string.learned_words_in_personal_dictionary),
        ).joinToString(" · ").ifEmpty { null }
        // (what the strikes mean is said once, under the section's heading)
        Kind.BLACKLISTED -> listOfNotNull(
            if (entry.strikes <= 1) stringResource(R.string.learned_words_removed_once)
                else stringResource(R.string.learned_words_removed_times, entry.strikes),
            if (entry.removedAt <= 0) null else stringResource(R.string.learned_words_last_removed, dateText(entry.removedAt)),
        ).joinToString(" · ")
    }
}

private fun dateText(seconds: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(seconds * 1000))

internal class WordLists(val yours: List<Entry>, val blacklisted: List<Entry>) {
    /**
     * One list: the learned words with the personal dictionary's, or the blacklisted words (the page's title says
     * which), grouped and sorted as [view] says; the words of a group in [folded] aren't listed (while searching all are).
     */
    fun rows(term: String, ofBlacklist: Boolean, view: ListView = ListView(), folded: Set<String> = emptySet(),
             now: Long = System.currentTimeMillis() / 1000): List<Row> {
        val result = mutableListOf<Row>()
        val entries = if (ofBlacklist) blacklisted else yours
        val shown = matching(entries, term)
        if (ofBlacklist && shown.isNotEmpty() && term.isBlank()) result.add(Row.Note(R.string.learned_words_blacklisted_summary))
        if (shown.isEmpty()) result.add(Row.Note(R.string.learned_words_none))
        val kind = if (ofBlacklist) Kind.BLACKLISTED else Kind.YOURS
        for ((id, title, words) in groups(shown, ofBlacklist, view, now)) {
            val fold = term.isBlank() && id in folded
            result.add(Row.Group(id, title, words.map { it.key }, fold))
            if (!fold) words.mapTo(result) { Item(it, kind) }
        }
        return result
    }

    companion object {
        /** The [entries] the search for [term] finds (all for a blank term). */
        fun matching(entries: List<Entry>, term: String): List<Entry> =
            if (term.isBlank()) entries else entries.filter { e -> e.spellings.any { it.contains(term, true) } }

        /** [entries] in their groups (id, heading, words), each sorted, in [view]'s order; [now]: in seconds, for the
         *  date groups. */
        fun groups(entries: List<Entry>, ofBlacklist: Boolean, view: ListView,
                   now: Long = System.currentTimeMillis() / 1000): List<Triple<String, GroupTitle, List<Entry>>> {
            fun count(e: Entry) = if (ofBlacklist) e.strikes else e.typed
            fun date(e: Entry) = if (ofBlacklist) e.removedAt else e.lastTyped
            // A–Z, or by count or date with the ones without either last (whichever way round); equal ones A–Z
            val alpha = compareBy<Entry> { it.key }.thenBy { it.word }
            val inGroup: Comparator<Entry> = when (view.sort) {
                SortBy.ALPHA -> if (view.descending) alpha.reversed() else alpha
                SortBy.COUNT -> compareBy<Entry> { count(it) <= 0 }.thenComparator { a, b ->
                    if (view.descending) count(b).compareTo(count(a)) else count(a).compareTo(count(b)) }.then(alpha)
                SortBy.DATE -> compareBy<Entry> { date(it) <= 0 }.thenComparator { a, b ->
                    if (view.descending) date(b).compareTo(date(a)) else date(a).compareTo(date(b)) }.then(alpha)
            }
            // the groups, the sort's unless New or dictionary word: letters A–Z (Z–A with the words), counts and dates
            // in the words' order (most / latest first, or fewest / earliest), new words before dictionary words; "#",
            // "Not typed yet" and "Date unknown" last
            val group = if (ofBlacklist || view.group == GroupBy.SORT) view.sort else null
            val countUp = !view.descending
            val byGroup = when (group) {
                SortBy.ALPHA -> entries.groupBy { e -> e.word.firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#" }
                    .entries.sortedWith(compareBy<Map.Entry<String, List<Entry>>> { it.key == "#" }.thenComparator { a, b ->
                        if (view.descending) b.key.compareTo(a.key) else a.key.compareTo(b.key) })
                    .map { Triple("letter:${it.key}", GroupTitle.Letter(it.key) as GroupTitle, it.value) }
                SortBy.DATE -> {
                    val order = listOf<GroupTitle>(GroupTitle.Older, GroupTitle.ThisMonth, GroupTitle.ThisWeek)
                        .let { if (view.descending) it.reversed() else it } + GroupTitle.DateUnknown
                    val ids = mapOf(GroupTitle.ThisWeek to "week", GroupTitle.ThisMonth to "month", GroupTitle.Older to "older",
                        GroupTitle.DateUnknown to "unknown")
                    val byDate = entries.groupBy { dateGroup(date(it), now) }
                    order.mapNotNull { title -> byDate[title]?.let { Triple("date:${ids[title]}", title, it) } }
                }
                SortBy.COUNT -> if (ofBlacklist)
                    entries.groupBy { it.strikes to (it.strikes == 3 && it.confirmed) }.entries
                        .sortedWith(compareBy<Map.Entry<Pair<Int, Boolean>, List<Entry>>> { it.key.first }.thenBy { it.key.second }
                            .let { if (countUp) it else it.reversed() })
                        .map { Triple("strikes:${it.key.first}:${it.key.second}", GroupTitle.Strikes(it.key.first, it.key.second) as GroupTitle, it.value) }
                else entries.groupBy { typedGroup(it.typed).let { t -> if (t is GroupTitle.Typed) t.from else 0 } }.entries
                        .sortedWith(compareBy<Map.Entry<Int, List<Entry>>> { it.key == 0 }.thenComparator { a, b ->
                            if (countUp) a.key.compareTo(b.key) else b.key.compareTo(a.key) })
                        .map { Triple("typed:${it.key}", typedGroup(it.value.first().typed), it.value) }
                null -> entries.groupBy { it.inDictionary }.entries.sortedBy { it.key }
                    .map { Triple("source:${it.key}", (if (it.key) GroupTitle.Dictionary else GroupTitle.New), it.value) }
            }
            return byGroup.map { (id, title, words) -> Triple(id, title, words.sortedWith(inGroup)) }
        }
    }
}

/** The keyboard's languages (as for the personal dictionary) by script. */
private fun scriptLocales(): Map<String, List<Locale>> = getSortedDictionaryLocales().groupBy { it.script() }

private fun scriptName(script: String): String =
    runCatching { Locale.Builder().setScript(script).build().displayScript }.getOrNull()?.ifEmpty { null } ?: script

/** The learned words of [script] in [pool] with how often each was typed and when last (seconds, 0: not known). */
private fun readLearned(context: Context, script: String, pool: Int): List<Triple<String, Int, Long>> {
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
            else Triple(word, wp.mProbabilityInfo.mCount.coerceAtLeast(0) + 1, wp.mProbabilityInfo.mTimestamp.toLong().coerceAtLeast(0))
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
    var confirmed = false
    var lastTyped = 0L
    var removedAt = 0L
    fun build(inDictionary: Set<String>): Entry {
        val spellings = counts.keys + personal.map { it.word.word } + listed
        val word = counts.maxByOrNull { it.value }?.key ?: personal.firstOrNull()?.word?.word ?: listed.firstOrNull() ?: lower
        return Entry(word, spellings.toSet(), counts.values.sum(), personal, listed, strikes, lastTyped, removedAt,
            spellings.any { it in inDictionary }, confirmed)
    }
}

private fun readWordLists(context: Context, script: String, pool: Int): WordLists {
    // the personal dictionary is Android's, per language: its words of this script from every language
    val personal = scriptLocales().flatMap { (localeScript, locales) -> locales.flatMap { locale ->
        readPersonal(context, locale).map { it to localeScript } } } +
        readPersonal(context, null).map { it to ScriptUtils.SCRIPT_LATIN } // for all languages
    val learned = readLearned(context, script, pool)
    // which of Your words a dictionary has (the group New or dictionary word): the script's languages' dictionaries
    val inDictionary = try {
        DictionaryFacilitatorImpl.inMainDictionaries(context, scriptLocales()[script].orEmpty(),
            learned.map { it.first } + personal.map { it.first.word.word })
    } catch (e: Exception) {
        Log.w("LearnedWordsScreen", "could not look the words of $script up in the dictionaries", e)
        emptySet()
    }
    return buildWordLists(script, learned.map { it.first to it.second }, personal,
        RemovedWords.blacklist(context, script, pool).apply { reload() }.entries(),
        lastTyped = learned.associate { it.first to it.third }, inDictionary = inDictionary)
}

/**
 * The two lists of [script]: its [learned] words (with how often each was typed) together with the [personal]
 * dictionary's words of the script (each with the script of its language, for a word without letters), and the
 * [blacklist]; each word once, case-insensitive. [lastTyped]: when each learned spelling was last typed (seconds);
 * [inDictionary]: the spellings a dictionary of the script's languages has.
 */
internal fun buildWordLists(script: String, learned: List<Pair<String, Int>>, personal: List<Pair<PersonalEntry, String>>,
                            blacklist: Map<String, RemovedWords.Entry>, lastTyped: Map<String, Long> = emptyMap(),
                            inDictionary: Set<String> = emptySet()): WordLists {
    val yours = HashMap<String, Builder>()
    val blacklisted = HashMap<String, Builder>()
    fun MutableMap<String, Builder>.of(word: String) = word.lowercase().let { getOrPut(it) { Builder(it) } }
    // (the store is the script's: its words are of the script, a word without letters too)
    for ((word, typed) in learned) yours.of(word).apply {
        counts[word] = (counts[word] ?: 0) + typed
        this.lastTyped = maxOf(this.lastTyped, lastTyped[word] ?: 0) // (the parameter: the map)
    }
    for ((p, localeScript) in personal)
        if (ScriptUtils.scriptOfWord(p.word.word, localeScript) == script) yours.of(p.word.word).personal.add(p)
    for ((word, removed) in blacklist) blacklisted.of(word).apply {
        listed.add(word)
        // (the row with the most strikes says whether it was brought back, as RemovedWords.Entry.max)
        if (removed.strikes > strikes) confirmed = removed.confirmed else if (removed.strikes == strikes) confirmed = confirmed || removed.confirmed
        strikes = maxOf(strikes, removed.strikes)
        removedAt = maxOf(removedAt, removed.removedAt)
    }
    fun Map<String, Builder>.sorted() = values.sortedBy { it.lower }.map { it.build(inDictionary) }
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
 * All at once: one Remove for the learned words of the same languages (one load of their dictionaries), one rebuild of
 * the swipe lists (a batch of ~300 words loaded the dictionaries and started a rebuild per word; 2026-10-12).
 */
internal fun deleteWords(context: Context, script: String, pool: Int, scriptLocales: List<Locale>, items: List<Item>,
                         alsoFromPersonalDictionary: Boolean = true,
                         deletePersonal: (Context, PersonalEntry) -> Unit = ::deletePersonalRow) {
    // a word's capitalizations as the lists have them now, not as the screen read them (review L4.5: one learned or
    // removed since the screen loaded stayed); each list read once, when needed
    val learnedNow by lazy { readLearned(context, script, pool).map { it.first }.groupBy { it.lowercase() } }
    val listedNow by lazy { RemovedWords.blacklist(context, script, pool).entries().keys.groupBy { it.lowercase() } }
    fun spellingsOf(item: Item) = (item.entry.spellings + learnedNow[item.entry.key].orEmpty()).distinct()
    val yours = items.filter { it.kind == Kind.YOURS }
    // every capitalization of each spelling out of the script's learned words, no strike (a plain delete); the
    // languages of the script (and those whose personal dictionary has it) say how it's listed
    for ((locales, sameLocales) in yours.groupBy { (scriptLocales + it.entry.personal.mapNotNull { p -> p.locale }).distinct() }) {
        val spellings = LinkedHashSet<String>()
        for (item in sameLocales) spellings.addAll(spellingsOf(item))
        try {
            if (LearningEventLog.isEnabled()) {
                val counts = LearningEventLog.countsIn(PersonalizationHelper.getUserHistoryDictionary(context, script, pool))
                for (word in spellings)
                    LearningEventLog.log(LearningEventLog.REMOVED, LearningEventLog.SETTINGS, word, "",
                        LearnedStores.label(script, pool), counts)
            }
            // (a word that fails is skipped there, the others go on)
            DictionaryFacilitatorImpl.removeWords(context, script, pool, locales, spellings, blacklist = false,
                personal = alsoFromPersonalDictionary)
        } catch (e: Exception) {
            Log.w("LearnedWordsScreen", "could not remove words of $script", e)
        }
    }
    for (item in yours) {
        val entry = item.entry
        // and its rows in the Android personal dictionary (each spelling found, for its language or all), unless
        // the box was unticked (they stay there, and so on this list); an allowed row (RemovedWords) stays either
        // way: this forgets the count only
        if (alsoFromPersonalDictionary) for (p in entry.personal)
            try { deletePersonal(context, p) } catch (e: Exception) { Log.w("LearnedWordsScreen", "could not delete a personal dictionary word", e) }
        // its recent and frequent uses go too (as LatinIME.removeSuggestion)
        spellingsOf(item).forEach { HotWords.forget(it); FrequentLongWords.forget(it) }
    }
    // its strikes go with it (and it's swipeable again: one rebuild for all of them, after the last)
    var unblacklisted: String? = null
    for (item in items) if (item.kind == Kind.BLACKLISTED) (item.entry.listed + listedNow[item.entry.key].orEmpty()).distinct().forEach { word ->
        try {
            if (RemovedWords.blacklist(context, script, pool).remove(word)) {
                unblacklisted = word
                if (LearningEventLog.isEnabled())
                    LearningEventLog.log(LearningEventLog.RESTORED, LearningEventLog.SETTINGS, "", word, LearnedStores.label(script, pool),
                        LearningEventLog.countsIn(PersonalizationHelper.getUserHistoryDictionary(context, script, pool)))
            }
        } catch (e: Exception) {
            Log.w("LearnedWordsScreen", "could not take a word of $script off the blacklist", e)
        }
    }
    unblacklisted?.let { helium314.keyboard.latin.gesture.GestureDecoderVocabulary.onWordUnblacklisted(LearnedStores.storeLocale(script), it) }
}

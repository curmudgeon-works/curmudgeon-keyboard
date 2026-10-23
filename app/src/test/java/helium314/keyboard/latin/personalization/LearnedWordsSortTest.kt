// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.settings.screens.GroupBy
import helium314.keyboard.settings.screens.GroupTitle
import helium314.keyboard.settings.screens.Item
import helium314.keyboard.settings.screens.ListView
import helium314.keyboard.settings.screens.Row
import helium314.keyboard.settings.screens.SortBy
import helium314.keyboard.settings.screens.WordLists
import helium314.keyboard.settings.screens.buildWordLists
import helium314.keyboard.settings.screens.typedGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sorting and grouping of "Learned & blacklisted words" (owner 2026-10-17). */
class LearnedWordsSortTest {
    private val lists = buildWordLists("Latn",
        learned = listOf("apple" to 12, "Apple" to 1, "avocado" to 1, "banana" to 60, "cherry" to 3, "date" to 7),
        personal = emptyList(),
        blacklist = mapOf("teh" to RemovedWords.Entry(1, false, 300), "hte" to RemovedWords.Entry(3, true, 100),
            "adn" to RemovedWords.Entry(2, false, 0), "wrod" to RemovedWords.Entry(3)),
        lastTyped = mapOf("apple" to 50L, "Apple" to 90L, "avocado" to 10L, "banana" to 20L, "date" to 70L),
        inDictionary = setOf("Apple", "banana"))

    private fun learned(view: ListView) = WordLists.groups(lists.yours, false, view).map { (_, title, words) -> title to words.map { it.word } }
    private fun blacklisted(view: ListView) = WordLists.groups(lists.blacklisted, true, view).map { (_, title, words) -> title to words.map { it.word } }
    private fun letters(groups: List<Pair<GroupTitle, List<String>>>) = groups.map { (it.first as GroupTitle.Letter).letter to it.second }

    @Test fun `by default A-Z under first letters, spellings merged with the latest time`() {
        assertEquals(listOf("A" to listOf("apple", "avocado"), "B" to listOf("banana"), "C" to listOf("cherry"), "D" to listOf("date")),
            letters(learned(ListView())))
        val apple = lists.yours.first { it.key == "apple" }
        assertEquals(13, apple.typed)
        assertEquals(90L, apple.lastTyped)
        assertTrue(apple.inDictionary) // (one spelling is enough)
    }

    @Test fun `picking the sort in use turns it round, another starts from its usual end`() {
        assertEquals(ListView(SortBy.ALPHA, true), ListView().sortedBy(SortBy.ALPHA))
        assertEquals(ListView(SortBy.COUNT, true), ListView().sortedBy(SortBy.COUNT))
        assertEquals(ListView(SortBy.COUNT, false), ListView(SortBy.COUNT, true).sortedBy(SortBy.COUNT))
        assertEquals(ListView(SortBy.DATE, true, GroupBy.SOURCE), ListView(SortBy.COUNT, false, GroupBy.SOURCE).sortedBy(SortBy.DATE))
        // remembered as text; anything else read: the default
        assertEquals(ListView(SortBy.DATE, false, GroupBy.SOURCE), ListView.decode(ListView(SortBy.DATE, false, GroupBy.SOURCE).encode()))
        assertEquals(ListView(), ListView.decode("nonsense"))
        assertEquals(ListView(), ListView.decode(null))
    }

    @Test fun `Z-A turns the letters round too`() {
        assertEquals(listOf("D", "C", "B", "A"), letters(learned(ListView(SortBy.ALPHA, true))).map { it.first })
        assertEquals(listOf("avocado", "apple"), letters(learned(ListView(SortBy.ALPHA, true)))[3].second)
    }

    @Test fun `by date latest first or earliest first, a word without one last either way`() {
        // New: date 70, avocado 10, cherry never typed (0); Dictionary: apple 90, banana 20
        assertEquals(listOf(listOf("date", "avocado", "cherry"), listOf("apple", "banana")),
            learned(ListView(SortBy.DATE, true, GroupBy.SOURCE)).map { it.second })
        assertEquals(listOf(listOf("avocado", "date", "cherry"), listOf("banana", "apple")),
            learned(ListView(SortBy.DATE, false, GroupBy.SOURCE)).map { it.second })
        // the same for counts: most or fewest first inside a group
        assertEquals(listOf(listOf("date", "cherry", "avocado"), listOf("banana", "apple")),
            learned(ListView(SortBy.COUNT, true, GroupBy.SOURCE)).map { it.second })
    }

    @Test fun `count groups are the agreed ranges, most first unless sorted fewest first`() {
        assertEquals(listOf("1-1", "2-2", "3-3", "4-4", "5-10", "5-10", "11-20", "21-50", "51-null", "51-null", "0"),
            listOf(1, 2, 3, 4, 5, 10, 11, 50, 51, 999, 0).map { typedGroup(it).let { t -> if (t is GroupTitle.Typed) "${t.from}-${t.to}" else "0" } })
        val most = learned(ListView(SortBy.COUNT, true))
        assertEquals(listOf(listOf("banana"), listOf("apple"), listOf("date"), listOf("cherry"), listOf("avocado")), most.map { it.second })
        val fewest = learned(ListView(SortBy.COUNT, false))
        assertEquals(listOf(listOf("avocado"), listOf("cherry"), listOf("date"), listOf("apple"), listOf("banana")), fewest.map { it.second })
    }

    @Test fun `strike groups apart from a brought-back 3rd strike, and the blacklist has no dictionary grouping`() {
        val groups = blacklisted(ListView(SortBy.COUNT, true))
        assertEquals(listOf(3 to true, 3 to false, 2 to false, 1 to false),
            groups.map { (it.first as GroupTitle.Strikes).let { t -> t.strikes to t.confirmed } })
        assertEquals(listOf(listOf("hte"), listOf("wrod"), listOf("adn"), listOf("teh")), groups.map { it.second })
        assertTrue(blacklisted(ListView(group = GroupBy.SOURCE)).all { it.first is GroupTitle.Letter })
    }

    @Test fun `new words before dictionary words`() {
        val groups = learned(ListView(group = GroupBy.SOURCE))
        assertEquals(listOf(GroupTitle.New, GroupTitle.Dictionary), groups.map { it.first })
        assertEquals(listOf(listOf("avocado", "cherry", "date"), listOf("apple", "banana")), groups.map { it.second })
    }

    // (groups follow the sort, owner 2026-10-18)
    private val day = 24 * 60 * 60L
    private val now = 1_000_000_000L
    private val dated = buildWordLists("Latn",
        learned = listOf("apple" to 12, "avocado" to 1, "banana" to 60, "cherry" to 3, "date" to 7),
        personal = emptyList(),
        blacklist = mapOf("teh" to RemovedWords.Entry(1, false, now - 2 * day), "adn" to RemovedWords.Entry(2, false, 0)),
        lastTyped = mapOf("apple" to now - day, "avocado" to now - 3 * day, "banana" to now - 10 * day, "date" to now - 100 * day))

    @Test fun `by default the groups follow the sort`() {
        assertTrue(learned(ListView(SortBy.COUNT, true)).all { it.first is GroupTitle.Typed })
        assertTrue(blacklisted(ListView(SortBy.COUNT, true)).all { it.first is GroupTitle.Strikes })
        assertEquals(listOf(GroupTitle.ThisWeek to listOf("apple", "avocado"), GroupTitle.ThisMonth to listOf("banana"),
            GroupTitle.Older to listOf("date"), GroupTitle.DateUnknown to listOf("cherry")),
            WordLists.groups(dated.yours, false, ListView(SortBy.DATE, true), now).map { (_, title, words) -> title to words.map { it.word } })
        assertEquals(listOf(GroupTitle.ThisWeek to listOf("teh"), GroupTitle.DateUnknown to listOf("adn")),
            WordLists.groups(dated.blacklisted, true, ListView(SortBy.DATE, true), now).map { (_, title, words) -> title to words.map { it.word } })
        // New or dictionary word: still a choice of its own
        assertEquals(listOf(GroupTitle.New, GroupTitle.Dictionary), learned(ListView(SortBy.COUNT, true, GroupBy.SOURCE)).map { it.first })
    }

    @Test fun `earliest first turns the date groups round, the unknown ones last either way`() {
        assertEquals(listOf(GroupTitle.Older to listOf("date"), GroupTitle.ThisMonth to listOf("banana"),
            GroupTitle.ThisWeek to listOf("avocado", "apple"), GroupTitle.DateUnknown to listOf("cherry")),
            WordLists.groups(dated.yours, false, ListView(SortBy.DATE, false), now).map { (_, title, words) -> title to words.map { it.word } })
    }

    @Test fun `date groups fold by their ids`() {
        val rows = dated.rows("", false, ListView(SortBy.DATE, true), folded = setOf("date:week"), now = now)
        val groups = rows.filterIsInstance<Row.Group>()
        assertEquals(listOf("date:week", "date:month", "date:older", "date:unknown"), groups.map { it.id })
        assertTrue(groups.first().folded)
        assertEquals(listOf("apple", "avocado"), groups.first().keys)
        // the folded group's words aren't listed, the others' are
        assertEquals(listOf("banana", "date", "cherry"), rows.filterIsInstance<Item>().map { it.entry.word })
    }

    @Test fun `a stored first letter or count grouping is read as the sort's`() {
        assertEquals(ListView(SortBy.COUNT, true, GroupBy.SORT), ListView.decode("COUNT,true,LETTER"))
        assertEquals(ListView(SortBy.ALPHA, false, GroupBy.SORT), ListView.decode("ALPHA,false,COUNT"))
        assertEquals(ListView(SortBy.DATE, true, GroupBy.SOURCE), ListView.decode("DATE,true,SOURCE"))
        assertEquals(GroupBy.SORT, ListView().group)
    }
}

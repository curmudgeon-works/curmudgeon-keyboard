// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.settings.screens.GroupBy
import helium314.keyboard.settings.screens.GroupTitle
import helium314.keyboard.settings.screens.ListView
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
        assertEquals(ListView(SortBy.DATE, true, GroupBy.COUNT), ListView(SortBy.COUNT, false, GroupBy.COUNT).sortedBy(SortBy.DATE))
        // remembered as text; anything else read: the default
        assertEquals(ListView(SortBy.DATE, false, GroupBy.SOURCE), ListView.decode(ListView(SortBy.DATE, false, GroupBy.SOURCE).encode()))
        assertEquals(ListView(), ListView.decode("nonsense"))
        assertEquals(ListView(), ListView.decode(null))
    }

    @Test fun `Z-A turns the letters round too, other sorts keep them A-Z`() {
        assertEquals(listOf("D", "C", "B", "A"), letters(learned(ListView(SortBy.ALPHA, true))).map { it.first })
        assertEquals(listOf("avocado", "apple"), letters(learned(ListView(SortBy.ALPHA, true)))[3].second)
        assertEquals(listOf("A", "B", "C", "D"), letters(learned(ListView(SortBy.DATE, true))).map { it.first })
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
        val most = learned(ListView(SortBy.ALPHA, false, GroupBy.COUNT))
        assertEquals(listOf(listOf("banana"), listOf("apple"), listOf("date"), listOf("cherry"), listOf("avocado")), most.map { it.second })
        val fewest = learned(ListView(SortBy.COUNT, false, GroupBy.COUNT))
        assertEquals(listOf(listOf("avocado"), listOf("cherry"), listOf("date"), listOf("apple"), listOf("banana")), fewest.map { it.second })
    }

    @Test fun `strike groups apart from a brought-back 3rd strike, and the blacklist has no dictionary grouping`() {
        val groups = blacklisted(ListView(SortBy.ALPHA, false, GroupBy.COUNT))
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
}

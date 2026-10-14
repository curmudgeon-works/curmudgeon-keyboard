// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.settings.preferences.RestoreDictionaries
import helium314.keyboard.settings.preferences.RestoreDictionaries.Action
import helium314.keyboard.settings.preferences.RestoreDictionaries.Entry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The restore's plan for a backup's added dictionaries (owner 2026-10-12): per language and kind, the same content
 *  anywhere: nothing; new: comes in; differs: the backup's selected, the device's renamed "…_prev", "…_prev2" … */
@RunWith(RobolectricTestRunner::class)
class RestoreDictionariesPlanTest {
    @Test fun `same content anywhere of that kind is a match, another kind's doesn't count`() {
        assertEquals(Action.SAME, RestoreDictionaries.plan("hi", "main_user.dict", "x", mapOf("main_prev_user.dict" to "x")).action)
        assertEquals(Action.NEW, RestoreDictionaries.plan("hi", "main_user.dict", "x", mapOf("emoji_user.dict" to "x")).action)
    }

    // owner 2026-10-12 B4: the matching copy takes the backup's name and is selected; a device file of that name with other
    // content moves to the next free prev first. Fail before: SAME did nothing, nothing selected or renamed
    @Test fun `a match is renamed to the backup's name and selected, the one of that name moved aside first`() {
        val kept = RestoreDictionaries.plan("hi", "main_user.dict", "x", mapOf("main_prev_user.dict" to "x"))
        assertEquals(listOf("main_prev_user.dict" to "main_user.dict"), kept.renames)
        assertEquals("main_user.dict", kept.target)
        assertEquals(true, kept.select)
        val taken = RestoreDictionaries.plan("hi", "main_user.dict", "x", mapOf("main_user.dict" to "a", "main_prev_user.dict" to "x"))
        // (B3b: the match steps aside first, so the one of that name takes the prev it leaves free; 20261013_L7_2: aside
        // under the first free prev, a listed name)
        assertEquals(listOf("main_prev_user.dict" to "main_prev2_user.dict", "main_user.dict" to "main_prev_user.dict",
            "main_prev2_user.dict" to "main_user.dict"), taken.renames)
        assertEquals(true, taken.select)
        // already under the backup's name: only selected
        val named = RestoreDictionaries.plan("hi", "emoji_user.dict", "x", mapOf("emoji_user.dict" to "x", "emoji_prev_user.dict" to "x"))
        assertEquals(Action.SAME, named.action)
        assertEquals(emptyList(), named.renames)
        assertEquals("emoji_user.dict", named.target)
        assertEquals(true, named.select)
    }

    // round-6 review 20261013_L7_2 (owner 2026-10-13): every name a step renames through is a listed one ("…_user.dict"),
    // so a restore killed between two renames leaves every dictionary listed and selectable. Fail before: the match
    // stepped aside as "main_user.dict.moving", unlisted until a recovery that ran only when a restore dialog opened
    @Test fun `a match's renames go through listed names only`() {
        for (device in listOf(mapOf("main_user.dict" to "a", "main_prev_user.dict" to "x"),
                mapOf("main_user.dict" to "a", "main_prev_user.dict" to "b", "main_prev2_user.dict" to "x"),
                mapOf("main_user.dict" to "a", "main_custom_user.dict" to "x"))) {
            val step = RestoreDictionaries.plan("hi", "main_user.dict", "x", device)
            val names = step.renames.flatMap { listOf(it.first, it.second) }
            assertTrue(names.all { it.endsWith("_user.dict") }, "$names")
            // each rename's target free when it runs
            val now = device.keys.toMutableSet()
            for ((from, to) in step.renames) { assertTrue(from in now && to !in now, "$from -> $to in $now"); now -= from; now += to }
            // (the match takes the backup's name and the one there moves aside: names change, the count doesn't)
            assertEquals(device.size, now.size, "a file lost or added")
        }
    }

    @Test fun `a clash moves the default-named one to the next free prev, keeping the user dict ending`() {
        val first = RestoreDictionaries.plan("hi", "main_user.dict", "b", mapOf("main_user.dict" to "a"))
        assertEquals(Action.CLASH, first.action)
        assertEquals(listOf("main_user.dict" to "main_prev_user.dict"), first.renames)
        assertEquals("main_user.dict", first.target)
        val second = RestoreDictionaries.plan("hi", "main_user.dict", "c", mapOf("main_user.dict" to "b", "main_prev_user.dict" to "a"))
        assertEquals(listOf("main_user.dict" to "main_prev2_user.dict"), second.renames)
        // (only kept-aside copies: nothing to move, the backup's comes in under the default name, selected)
        val aside = RestoreDictionaries.plan("hi", "main_user.dict", "c", mapOf("main_prev_user.dict" to "a"))
        assertEquals(Action.CLASH, aside.action)
        assertEquals(emptyList(), aside.renames)
        assertEquals(true, aside.select)
    }

    @Test fun `the backup's dictionary of a kind is the one it selected, none when it used the built-in one`() {
        val backup = mapOf(Entry("hi", "main_user.dict") to "a", Entry("hi", "main_prev_user.dict") to "b")
        assertEquals(listOf("main_user.dict"), RestoreDictionaries.plan(backup, emptyMap(), device = { emptyMap() }).map { it.backupName })
        assertEquals(listOf("main_prev_user.dict"),
            RestoreDictionaries.plan(backup, mapOf("dict_selected_hi_main" to "main_prev_user.dict"), device = { emptyMap() }).map { it.backupName })
        assertEquals(emptyList(), RestoreDictionaries.plan(backup, mapOf("dict_selected_hi_main" to "internal"), device = { emptyMap() }))
    }

    @Test fun `only dicts of a language folder are dictionaries, never a path outside it`() {
        assertEquals(Entry("hi", "main_user.dict"), RestoreDictionaries.entryOf("dicts/hi/main_user.dict"))
        assertNull(RestoreDictionaries.entryOf("dicts/../main_user.dict"))
        assertNull(RestoreDictionaries.entryOf("dicts/hi/x/main_user.dict"))
        assertNull(RestoreDictionaries.entryOf("dicts/hi/main.dict"))
    }
}

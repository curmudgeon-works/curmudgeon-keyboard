// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The second re-review's Low items (R9 2026-10-11: one damaged saved item emptied its whole list, and the next save
 *  wrote over the good ones). */
@RunWith(RobolectricTestRunner::class)
class ReReview2LowTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun aside(key: String) = real.all.keys.filter { it.startsWith(key + "_broken_") }

    // R9: popup sets (the bad one has no "morePopups")
    @Test fun `a damaged popup set is skipped, kept aside on the next save, and counted until then`() {
        val stored = """[{"name":"Good","morePopups":"all","symbolsLayout":"","overrides":{"a":["á"]}},{"name":"Bad","overrides":{}}]"""
        real.edit().putString(KeyPopupOverrides.PREF_SETS, stored).commit()
        val loaded = KeyPopupOverrides.loadSets(real)
        assertEquals(listOf("Good"), loaded.map { it.name })
        assertEquals(mapOf("a" to listOf("á")), loaded[0].overrides)
        assertEquals(1, KeyPopupOverrides.skippedSets(real))
        KeyPopupOverrides.saveSets(real, loaded + KeyPopupOverrides.UserSet("New", "main", null, emptyMap()))
        assertEquals(1, aside(KeyPopupOverrides.PREF_SETS).size)
        assertEquals(stored, real.getString(aside(KeyPopupOverrides.PREF_SETS)[0], null))
        assertEquals(listOf("Good", "New"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals(0, KeyPopupOverrides.skippedSets(real))
        // a readable list: nothing more put aside
        KeyPopupOverrides.saveSets(real, emptyList())
        assertEquals(1, aside(KeyPopupOverrides.PREF_SETS).size)
    }

    // R9: saved Layouts (the bad one has no "values")
    @Test fun `a damaged saved Layout is skipped, kept aside on the next save, and counted until then`() {
        val stored = """[{"name":"Good","values":{}},{"name":"Bad"},42]"""
        real.edit().putString(LayoutPresets.PREF, stored).commit()
        val loaded = LayoutPresets.load(real)
        assertEquals(listOf("Good"), loaded.map { it.name })
        assertEquals(2, LayoutPresets.skipped(real))
        LayoutPresets.save(real, loaded + LayoutPresets.Preset("New", emptyMap()))
        assertEquals(1, aside(LayoutPresets.PREF).size)
        assertEquals(stored, real.getString(aside(LayoutPresets.PREF)[0], null))
        assertEquals(listOf("Good", "New"), LayoutPresets.load(real).map { it.name })
        assertEquals(0, LayoutPresets.skipped(real))
    }

    // R9: saved themes (the bad one's value isn't an object)
    @Test fun `a damaged saved theme is skipped, kept aside on the next save, and counted until then`() {
        val stored = """[{"name":"Bad","values":{"x":5}},{"name":"Good","values":{"theme_style":{"s":"Material"}}}]"""
        real.edit().putString(AppearanceLooks.PREF, stored).commit()
        val loaded = AppearanceLooks.load(real)
        assertEquals(listOf("Good"), loaded.map { it.name })
        assertEquals("Material", loaded[0].values["theme_style"])
        assertEquals(1, AppearanceLooks.skipped(real))
        AppearanceLooks.save(real, loaded + AppearanceLooks.Look("New", mapOf("theme_style" to "Rounded")))
        assertEquals(1, aside(AppearanceLooks.PREF).size)
        assertEquals(stored, real.getString(aside(AppearanceLooks.PREF)[0], null))
        assertEquals(listOf("Good", "New"), AppearanceLooks.load(real).map { it.name })
        assertEquals(0, AppearanceLooks.skipped(real))
    }

    // R9: text that isn't a list at all still goes aside, and counts as one; nothing stored counts none
    @Test fun `a saved list that can't be read at all counts as one and is still kept aside`() {
        assertEquals(0, AppearanceLooks.skipped(real))
        real.edit().putString(LayoutPresets.PREF, "{not json").commit()
        assertEquals(emptyList(), LayoutPresets.load(real))
        assertEquals(1, LayoutPresets.skipped(real))
        LayoutPresets.save(real, emptyList())
        assertEquals("{not json", real.getString(aside(LayoutPresets.PREF).single(), null))
        assertEquals(0, LayoutPresets.skipped(real))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.storeKeyboards
import helium314.keyboard.withId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The setup's third step as tiles (2026-10-24): Pick a theme (Android 12 and up), Pick a layout, All settings, swiped
 * through one at a time, starting on the first, no wrap; landing on them writes nothing; Minimal from the layout tile
 * writes its values on every keyboard, Dense again puts them back; the All settings tile does what the Configure row did;
 * the checklist row reads "Pick a theme & layout". Fail before: no tiles (compile), the Configure row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class SetupTilesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val us = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first().toSettingsSubtype().withId(1)
    private val de = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(2)
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private var state: PagerState? = null
    private var scope: CoroutineScope? = null
    private var closed = 0

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, listOf(us, de))
    }
    @After fun tearDown() {
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun idle() { compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle() }

    private fun open() {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent {
                val s = rememberPagerState { setupTiles().size }
                state = s
                scope = rememberCoroutineScope()
                SetupTiles(Color.DarkGray, close = { closed++ }, state = s)
            }
        }
        idle()
    }

    private fun scrollTo(page: Int) {
        compose.runOnIdle { scope!!.launch { state!!.scrollToPage(page) } }
        idle()
    }

    private val tiles get() = setupTiles()

    @Test fun `the tiles in order, starting on the first, no wrap at either end`() {
        assertEquals(listOf(SetupTile.LAYOUT, SetupTile.SETTINGS), setupTiles(Build.VERSION_CODES.R)) // Android 11: no theme tile
        assertEquals(listOf(SetupTile.THEME, SetupTile.LAYOUT, SetupTile.SETTINGS), setupTiles(Build.VERSION_CODES.S))
        open()
        for (tile in tiles) compose.onNodeWithTag(setupTileTag(tile)).assertExists()
        assertEquals(tiles.size, state!!.pageCount)
        assertEquals(0, state!!.currentPage)
        assertFalse(state!!.canScrollBackward)
        scrollTo(tiles.size - 1)
        assertEquals(tiles.size - 1, state!!.currentPage)
        assertFalse(state!!.canScrollForward, "the last tile is the end")
        scrollTo(tiles.size + 2) // past the end: stays on the last
        assertEquals(tiles.size - 1, state!!.currentPage)
        assertTrue(state!!.canScrollBackward)
    }

    @Test fun `landing writes nothing - Minimal writes on every keyboard - Dense again puts it back`() {
        val before = real.all.toMap()
        open()
        assertEquals(before, real.all.toMap(), "landing on the tiles")
        scrollTo(tiles.indexOf(SetupTile.LAYOUT))
        compose.onNodeWithTag(setupLayoutWordTag(SetupLayout.Pick.MINIMAL)).performClick()
        idle()
        for (n in 1..2) {
            assertEquals(ctx.getString(R.string.layout_preset_classic), real.all[idKey(n, LayoutPresets.PREF_SELECTED)], "keyboard $n")
            assertEquals(false, real.all[idKey(n, Settings.PREF_SHOW_NUMBER_ROW)], "keyboard $n")
            assertEquals("", real.all[idKey(n, Settings.PREF_SYMBOL_POPUP_MAP)], "keyboard $n")
            assertEquals(false, real.all[idKey(n, Settings.PREF_SHOW_HINTS)], "keyboard $n")
        }
        compose.onNodeWithTag(setupLayoutWordTag(SetupLayout.Pick.DENSE)).performClick()
        idle()
        assertEquals(before, real.all.toMap(), "Dense again")
    }

    @Test fun `the All settings tile does what the Configure row did`() {
        open()
        scrollTo(tiles.indexOf(SetupTile.SETTINGS))
        compose.onNodeWithTag(setupTileTag(SetupTile.SETTINGS)).performClick()
        idle()
        assertEquals(1, closed)
    }

    @Test fun `the checklist row says theme and layout`() {
        assertEquals("Pick a theme & layout", ctx.getString(R.string.setup_theme_layout_title))
        assertEquals("Pick a layout", ctx.getString(R.string.setup_layout_title))
        assertEquals("All settings", ctx.getString(R.string.setup_all_settings))
        assertEquals("You're all set!", ctx.getString(R.string.setup_done_title)) // unchanged
    }
}

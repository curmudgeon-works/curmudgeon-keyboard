// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Owner 2026-10-14: "You should retain the order of how well they match then, just move to the top any items that
 * start with the search term, preserving their internal order too." Fail before: "Accent color" (the whole word) scored
 * above "Colors" (only its beginning) and came first.
 */
@RunWith(RobolectricTestRunner::class)
class SearchStartsWithFirstTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun setting(key: String, title: Int) = Setting(ctx, key, title) { }

    private val accent = setting("accent", R.string.gesture_trail_color) // "Accent color"
    private val chooser = setting("chooser", R.string.auto_user_color) // "Choose color automatically"
    private val colors = setting("colors", R.string.theme_colors) // "Colors"
    private val dark = setting("dark", R.string.theme_colors_night) // "Colors (dark)"
    private val navbar = setting("navbar", R.string.theme_navbar) // "Color navigation bar"

    private fun search(query: String, vararg settings: Setting) = SettingsSearch.search(query, settings.toList()).map { it.key }

    @Test fun `a title starting with the typed text moves above a better match`() {
        // "color" is a whole word in "Accent color" (better score), only the beginning of "Colors"
        assertEquals(listOf("colors", "accent"), search("color", accent, colors))
    }

    @Test fun `both groups keep their order by how well they match`() {
        // starting with "color": "Color navigation bar" (whole word) before "Colors" / "Colors (dark)" (beginning, in the
        // order given); then the rest, likewise: "Accent color", "Choose color automatically"
        assertEquals(listOf("navbar", "colors", "dark", "accent", "chooser"),
            search("color", accent, colors, chooser, dark, navbar))
    }

    @Test fun `typed text as typed - partial, any case, spaces around`() {
        assertEquals(listOf("colors", "dark", "accent"), search("  COL ", accent, colors, dark))
        // the whole typed text: both match "colors" as well, but "colors (" starts only "Colors (dark)"
        assertEquals(listOf("dark", "colors"), search("colors (", colors, dark))
    }
}

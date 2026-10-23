// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.SubtypeSettings
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Owner 2026-10-18 ("yes, add the search fix to this round"): the settings search found the Languages entry for
 * "hindi" and "hinglish" only (synonyms of "language"), not for "german" or "spanish". Now any language's name, or
 * its beginning, finds it, as the language list's own search matches a name.
 */
@RunWith(RobolectricTestRunner::class)
class LanguageNameSearchTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val languages = Setting(ctx, "keyboard_menu_languages", R.string.languages_title) { }
    private val colors = Setting(ctx, "colors", R.string.theme_colors) { }

    @Before fun setUp() { SubtypeSettings.init(ctx) }

    private fun search(query: String) =
        SettingsSearch.search(query, listOf(colors, languages), SettingsSearch.languageNames(ctx)).map { it.key }

    @Test fun `a language's name or its beginning finds the Languages entry`() {
        for (query in listOf("german", "German", "hinglish", "hindi", "spa", "spanish keyboard"))
            assertEquals(listOf("keyboard_menu_languages"), search(query), query)
    }

    @Test fun `an unrelated word finds nothing of it, and a word too short isn't a language`() {
        assertEquals(emptyList(), search("vibration"))
        assertEquals(emptyList(), search("sp"))
        assertEquals(listOf("colors"), search("colors"))
    }
}

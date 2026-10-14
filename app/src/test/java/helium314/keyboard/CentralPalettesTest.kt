// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.ColorSetting
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.Palettes
import helium314.keyboard.settings.Palettes.Choice
import helium314.keyboard.settings.preferences.keptOnReset
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.paletteUsers
import helium314.keyboard.settings.screens.userNames
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Palettes are one app-wide list (owner 2026-10-11: "palettes are one app-wide list", like saved themes and Layouts): a
 * keyboard's light and dark colours and a saved theme name one, nobody holds a copy. Edit: each other user takes the new
 * colours, the default ones, or the old ones as "Warm 2" (one for all keepers); delete: all to default, or only this
 * keyboard; rename: everyone follows; Appearance's Discard undoes it all; the one-time move gathers the sets' and themes'
 * copies; the factory reset's 4th tick covers them.
 * Keyboard A ("Work", enabled, edited) = set 1, B (French, enabled) = set 2, C (German, switched off) = set 3; separate
 * settings, Appearance per keyboard.
 */
@RunWith(RobolectricTestRunner::class)
class CentralPalettesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work")
    // (French: no built-in keyboard for fr-FR, the language's own locale has them; as GhostRecordsTest)
    private val b = SubtypeSettings.getAvailableSubtypeLocales().first { it.language == "fr" }
        .let { SubtypeSettings.getResourceSubtypesForLocale(it).first().toSettingsSubtype() }
    private val c = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private val day = Settings.PREF_THEME_COLORS
    private val night = Settings.PREF_THEME_COLORS_NIGHT
    private val warm = Settings.PREF_USER_COLORS_PREFIX + "Warm"
    private val warm2 = Settings.PREF_USER_COLORS_PREFIX + "Warm 2"
    private val old = mapOf(Settings.PREF_USER_COLORS_PREFIX to "old")
    private fun mark(id: Int, key: String) = own(id, KeyboardProfiles.TOMBSTONE + key)
    private fun paletteKeys() = real.all.keys.filter { Palettes.isKey(KeyboardProfiles.splitOwnKey(it)?.second ?: it) }.sorted()

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        assertEquals(1, KeyboardProfiles.idFor(real, a))
        assertEquals(2, KeyboardProfiles.idFor(real, b))
        assertEquals(3, KeyboardProfiles.idFor(real, c))
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    /** "Warm" on the list with the new colours, chosen by A (day), B (day and night) and C (day); theme "Sunset" names it. */
    private fun everyoneOnWarm() {
        real.edit {
            putString(warm, "new")
            putString(own(1, day), "Warm")
            putString(own(2, day), "Warm"); putString(own(2, night), "Warm")
            putString(own(3, day), "Warm")
        }
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(day to "Warm"))), now = true)
    }
    private fun sunset() = AppearanceLooks.load(real).single { it.name == "Sunset" }

    // ---- one list ----

    @Test fun `a palette made on keyboard A is on keyboard B's list`() {
        KeyboardTheme.writeUserColors(view(1), "Mine", listOf(ColorSetting(KeyboardTheme.COLOR_BACKGROUND, false, -1)))
        assertTrue(view(2).all.containsKey(Settings.PREF_USER_COLORS_PREFIX + "Mine"))
        assertEquals(listOf(Settings.PREF_USER_COLORS_PREFIX + "Mine"), paletteKeys()) // (plain: no set's copy)
        assertTrue("Mine" in Palettes.list(real.all))
    }

    @Test fun `the edit question names the other keyboards, a switched-off one as such, and the saved themes`() {
        everyoneOnWarm()
        val (keyboards, themes) = paletteUsers(ctx, view(1), "Warm", "Warm")
        assertEquals(listOf(keyboardName(b, ctx), "${keyboardName(c, ctx)} (switched off)"), userNames(ctx, keyboards))
        assertEquals(listOf("Sunset"), themes)
    }

    @Test fun `nobody else uses it - no question`() {
        real.edit { putString(warm, "new"); putString(own(1, day), "Warm") }
        val (keyboards, themes) = paletteUsers(ctx, view(1), "Warm", "Warm")
        assertTrue(keyboards.isEmpty() && themes.isEmpty())
    }

    // ---- edit: three options each ----

    @Test fun `edit - B takes the new colours`() {
        everyoneOnWarm()
        assertNull(Palettes.afterEdit(real, "Warm", old, mapOf(b to Choice.NEW), emptyMap()))
        assertEquals("Warm", real.getString(own(2, day), null)); assertEquals("Warm", real.getString(own(2, night), null))
        assertEquals(listOf(warm), paletteKeys())
    }

    // (owner 2026-10-11: "Would this still cause Warm 2 to be created if no users exist for it?": no)
    @Test fun `edit - every user on the new colours, the question's starting choice - no Warm 2 on the list`() {
        everyoneOnWarm()
        assertNull(Palettes.afterEdit(real, "Warm", old, mapOf(b to Choice.NEW, c to Choice.NEW), mapOf("Sunset" to Choice.NEW)))
        assertEquals(listOf(warm), paletteKeys())
        assertEquals(listOf("Warm", "Warm", "Warm"), listOf(own(2, day), own(2, night), own(3, day)).map { real.getString(it, null) })
        assertEquals("Warm", sunset().values[day])
    }

    @Test fun `edit - B goes to the default colours, both its slots`() {
        everyoneOnWarm()
        Palettes.afterEdit(real, "Warm", old, mapOf(b to Choice.DEFAULT), emptyMap())
        assertNull(real.getString(own(2, day), null)); assertNull(real.getString(own(2, night), null))
        assertTrue(real.getBoolean(mark(2, day), false)); assertTrue(real.getBoolean(mark(2, night), false))
        assertEquals("Warm", real.getString(own(1, day), null)) // (the edited one: the new colours)
        assertEquals(listOf(warm), paletteKeys())
    }

    @Test fun `edit - B keeps the old colours as Warm 2, both its slots`() {
        everyoneOnWarm()
        assertEquals("Warm 2", Palettes.afterEdit(real, "Warm", old, mapOf(b to Choice.KEEP_OLD), emptyMap()))
        assertEquals("Warm 2", real.getString(own(2, day), null)); assertEquals("Warm 2", real.getString(own(2, night), null))
        assertEquals("old", real.getString(warm2, null)); assertEquals("new", real.getString(warm, null))
        assertEquals("Warm", real.getString(own(3, day), null)) // (C not asked here: as it was)
    }

    @Test fun `edit - all keepers share one Warm 2, the switched-off keyboard and the theme too`() {
        everyoneOnWarm()
        Palettes.afterEdit(real, "Warm", old, mapOf(b to Choice.KEEP_OLD, c to Choice.KEEP_OLD), mapOf("Sunset" to Choice.KEEP_OLD))
        assertEquals(listOf("Warm 2", "Warm 2", "Warm 2"), listOf(real.getString(own(2, day), null), real.getString(own(3, day), null), sunset().values[day]))
        assertEquals(listOf(warm, warm2), paletteKeys()) // (one palette for all of them)
    }

    @Test fun `edit - a theme goes to the default colours or takes the new`() {
        everyoneOnWarm()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(day to "Warm")),
            AppearanceLooks.Look("Dusk", mapOf(day to "Warm", night to "Warm"))), now = true)
        Palettes.afterEdit(real, "Warm", old, emptyMap(), mapOf("Sunset" to Choice.DEFAULT, "Dusk" to Choice.NEW))
        assertTrue(sunset().values.containsKey(day)); assertNull(sunset().values[day]) // (at its default, nothing remembered)
        assertFalse(day in sunset().remembered)
        assertEquals("Warm", AppearanceLooks.load(real).single { it.name == "Dusk" }.values[night])
    }

    @Test fun `edit - the old colours' palette counts up past a taken Warm 2`() {
        everyoneOnWarm()
        real.edit { putString(warm2, "other") }
        assertEquals("Warm 3", Palettes.afterEdit(real, "Warm", old, mapOf(b to Choice.KEEP_OLD), emptyMap()))
        assertEquals("other", real.getString(warm2, null))
    }

    @Test fun `Discard on Appearance undoes the whole edit`() {
        everyoneOnWarm()
        real.edit { putString(warm, "before") }
        val before = real.all
        val looks = real.getString(AppearanceLooks.PREF, null)
        AppearanceDraft.of(ctx) // Appearance opens
        real.edit { putString(warm, "new") } // the editor's change
        Palettes.afterEdit(real, "Warm", mapOf(Settings.PREF_USER_COLORS_PREFIX to "before"),
            mapOf(b to Choice.KEEP_OLD, c to Choice.DEFAULT), mapOf("Sunset" to Choice.KEEP_OLD))
        assertTrue(AppearanceDraft.activeOrNull()!!.hasChanges(ctx))
        AppearanceDraft.activeOrNull()!!.reject(ctx) // Appearance's Discard
        assertEquals(looks, real.getString(AppearanceLooks.PREF, null))
        assertEquals(before.filterKeys { it != AppearanceLooks.PREF }, real.all.filterKeys { it != AppearanceLooks.PREF })
    }

    // ---- delete ----

    @Test fun `delete a - all to default, the palette leaves the list`() {
        everyoneOnWarm()
        Palettes.delete(real, view(1), "Warm", onlyHere = false)
        assertEquals(emptyList(), paletteKeys())
        for (id in 1..3) { assertNull(real.getString(own(id, day), null)); assertTrue(real.getBoolean(mark(id, day), false)) }
        assertNull(real.getString(own(2, night), null))
        assertNull(sunset().values[day])
    }

    @Test fun `delete b - only this keyboard goes to the default, the others keep it`() {
        everyoneOnWarm()
        Palettes.delete(real, view(1), "Warm", onlyHere = true)
        assertNull(real.getString(own(1, day), null))
        assertEquals("Warm", real.getString(own(2, day), null)); assertEquals("Warm", real.getString(own(3, day), null))
        assertEquals("Warm", sunset().values[day])
        assertEquals(listOf(warm), paletteKeys())
    }

    // ---- rename ----

    @Test fun `rename - every keyboard, switched-off ones too, and every saved theme follow`() {
        everyoneOnWarm()
        assertTrue(KeyboardTheme.renameUserColors("Warm", "Hot", view(2)))
        assertEquals(listOf(Settings.PREF_USER_COLORS_PREFIX + "Hot"), paletteKeys())
        assertEquals(listOf("Hot", "Hot", "Hot", "Hot"), listOf(own(1, day), own(2, day), own(2, night), own(3, day)).map { real.getString(it, null) })
        assertEquals("Hot", sunset().values[day])
        assertFalse(real.contains(warm)) // (nothing left under the old name)
    }

    @Test fun `rename - a name on the list already is refused`() {
        everyoneOnWarm()
        real.edit { putString(Settings.PREF_USER_COLORS_PREFIX + "Cool", "c") }
        assertFalse(KeyboardTheme.renameUserColors("Warm", "Cool", view(1)))
        assertEquals("Warm", real.getString(own(2, day), null))
    }

    // ---- saved themes name palettes ----

    @Test fun `a saved theme names the palette, with no copy of its colours`() {
        everyoneOnWarm()
        val values = AppearanceLooks.snapshot(view(1))
        assertEquals("Warm", values[day])
        assertTrue(values.keys.none { Palettes.isKey(it) }, values.keys.toString())
    }

    @Test fun `applying a saved theme sets the slot and never writes over the palette`() {
        everyoneOnWarm()
        // (a theme saved before 2026-10-11 still carrying a copy: it's not applied)
        val look = AppearanceLooks.Look("Old", mapOf(day to "Warm", night to "Warm", warm to "the theme's copy"))
        real.edit { remove(own(2, day)); remove(own(2, night)) }
        AppearanceLooks.apply(ctx, AppearanceLooks.current(view(2)) + AppearanceLooks.applied(look, true), view(2))
        assertEquals("Warm", real.getString(own(2, day), null)); assertEquals("Warm", real.getString(own(2, night), null))
        assertEquals("new", real.getString(warm, null))
        assertEquals(listOf(warm), paletteKeys())
    }

    // ---- the one-time move ----

    @Test fun `the move gathers the sets' and themes' palettes into the list`() {
        val x = "[x]"; val y = "[y]"; val z = "[z]"; val k = "[k]"
        real.edit {
            putString(own(1, warm), x); putString(own(1, day), "Warm")
            putString(own(2, warm), x); putString(own(2, day), "Warm") // same name, same colours: one
            putString(own(3, warm), y); putString(own(3, day), "Warm") // other colours: numbered
            putInt(own(3, Settings.PREF_USER_MORE_COLORS_PREFIX + "Warm"), 0) // ("main colours", the default: the same)
            putString(own(2, Settings.PREF_USER_COLORS_PREFIX + "~custom:1"), k); putString(own(2, night), "~custom:1") // a kept copy
            putBoolean(own(1, KeyboardProfiles.TOMBSTONE + Settings.PREF_USER_COLORS_PREFIX + "Gone"), true) // (a mark of one)
        }
        val sunsetJson = org.json.JSONArray().put(org.json.JSONObject().put("name", "Sunset").put("values", org.json.JSONObject()
            .put(day, org.json.JSONObject().put("s", "Warm")).put(warm, org.json.JSONObject().put("s", y))
            .put(Settings.PREF_USER_COLORS_PREFIX + "Cool", org.json.JSONObject().put("s", z))))
        real.edit { putString(AppearanceLooks.PREF, sunsetJson.toString()) }
        Palettes.centralize(real)
        assertEquals(mapOf("Palette 1" to k, "Warm" to x, "Warm 2" to y, "Cool" to z),
            Palettes.list(real.all).mapValues { it.value[Settings.PREF_USER_COLORS_PREFIX] })
        assertEquals(listOf("Warm", "Warm", "Warm 2"), (1..3).map { real.getString(own(it, day), null) })
        assertEquals("Palette 1", real.getString(own(2, night), null))
        assertEquals("Warm 2", sunset().values[day]) // (its copy had Warm 2's colours)
        assertTrue(sunset().values.keys.none { Palettes.isKey(it) })
        assertTrue(real.all.keys.none { k2 -> KeyboardProfiles.splitOwnKey(k2)?.second?.removePrefix(KeyboardProfiles.TOMBSTONE)?.let { Palettes.isKey(it) } == true })
        assertTrue(real.getBoolean(Palettes.CENTRAL_DONE, false))
        // once only
        val after = real.all
        real.edit { putString(own(1, Settings.PREF_USER_COLORS_PREFIX + "Late"), "[l]") }
        Palettes.centralize(real)
        assertEquals(after + (own(1, Settings.PREF_USER_COLORS_PREFIX + "Late") to "[l]"), real.all)
    }

    @Test fun `the move gives a kept copy the next free Palette number`() {
        real.edit {
            putString(Settings.PREF_USER_COLORS_PREFIX + "Palette 1", "[mine]")
            putString(own(1, Settings.PREF_USER_COLORS_PREFIX + "~custom:1"), "[kept]"); putString(own(1, day), "~custom:1")
        }
        Palettes.centralize(real)
        assertEquals("Palette 2", real.getString(own(1, day), null))
        assertEquals("[kept]", real.getString(Settings.PREF_USER_COLORS_PREFIX + "Palette 2", null))
        assertEquals("[mine]", real.getString(Settings.PREF_USER_COLORS_PREFIX + "Palette 1", null))
    }

    // ---- factory reset ----

    @Test fun `factory reset - palettes kept unticked, removed ticked`() {
        for (key in Palettes.keys("Warm")) {
            assertTrue(keptOnReset(key, keyboards = true, custom = false), key)
            assertFalse(keptOnReset(key, keyboards = true, custom = true), key)
        }
        assertTrue(ctx.getString(helium314.keyboard.latin.R.string.factory_reset_custom).contains("palettes"))
    }
}

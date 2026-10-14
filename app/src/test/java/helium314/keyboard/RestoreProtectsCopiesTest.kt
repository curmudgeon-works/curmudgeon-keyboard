// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.preferences.MergeChoice
import helium314.keyboard.settings.preferences.MergeQuestion
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.restoredSettings
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.keyboardName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A restore of some keyboards never trashes what the phone holds unsaved (owner 2026-10-11: "main concern is to make
 * sure nothing gets trashed"): (1) "What to merge" says once that selected items replace the keyboard's settings,
 * unsaved changes too (owner 2026-10-11: no per-copy warnings); (2) a palette the phone has under that name with other
 * colors is never written over: the backup's comes into the one list under the next number (owner 2026-10-11:
 * palettes are one app-wide list; an older backup's kept copy "~custom:1" is "Palette 1"; before, "~custom:2").
 */
@RunWith(RobolectricTestRunner::class)
class RestoreProtectsCopiesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "protect-${System.nanoTime()}.zip") }
    private lateinit var qwerty: SettingsSubtype
    private lateinit var dvorak: SettingsSubtype
    private lateinit var colemak: SettingsSubtype
    private lateinit var english: String
    private val hidden1 = "~custom:1" // (an older backup's kept copy)
    private val colors1 = Settings.PREF_USER_COLORS_PREFIX + hidden1
    // the phone's list: "Palette 1" (the name an old kept copy gets), and the next number
    private val listed1 = Settings.PREF_USER_COLORS_PREFIX + "Palette 1"
    private val listed2 = Settings.PREF_USER_COLORS_PREFIX + "Palette 2"
    private var backupValues: Map<String, Any?> = emptyMap()
    // two palettes' colors as the app stores them (a JSON list): since the first keyboard's own set is the common one
    // (owner 2026-10-11: no separate common set), the phone keyboard's palette is what the keyboard's settings read
    // when they reload, so the colors must be readable (2026-10-11; "A" / "B" before broke that reload)
    private val paletteA = """[{"name":"background","auto":false,"color":-16777216}]"""
    private val paletteB = """[{"name":"background","auto":false,"color":-1}]"""

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        qwerty = available("en-US")
        dvorak = qwerty.withLayout(LayoutType.MAIN, "dvorak").toPref().toSettingsSubtype()
        colemak = qwerty.withLayout(LayoutType.MAIN, "colemak").toPref().toSettingsSubtype()
        english = keyboardName(qwerty, ctx)
    }
    @After fun tearDown() {
        zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx)
        // the work queued on the main thread done here (2026-10-11): the "What to merge" ticks are Compose state, and once
        // an earlier class had drawn a screen, writing them queues Compose's notice of the change on the main thread;
        // dropped at the end of the test, it left the next class's settings screens deaf to taps (EditingKeyboardTest's
        // keyboard B didn't unfold, as SwitchedOffTileTest's before)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The phone: QWERTY, separate settings on, [own] in its own set, [list] app-wide (the palettes). */
    private fun phone(own: Map<String, Any?>, list: Map<String, Any?> = emptyMap()) {
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(qwerty)))
            .putBoolean("separate_settings_per_keyboard", true).apply { for ((k, v) in list) KeyboardProfiles.put(this, k, v) }.commit()
        useKeyboards(ctx, real, qwerty)
        val id = KeyboardProfiles.idFor(real, qwerty)
        real.edit().apply { for ((key, value) in own) KeyboardProfiles.put(this, KeyboardProfiles.ownKey(id, key), value) }.commit()
        KeyboardProfiles.loadGroups(real)
    }
    private fun phoneId() = KeyboardProfiles.idEntries(real).getValue(qwerty.toPref())
    private fun own(id: Int, key: String) = real.all[KeyboardProfiles.ownKey(id, key)]

    /** The backup: [sets] (each keyboard with its own set), separate settings on, [extra] backup-wide. */
    private fun backup(sets: Map<SettingsSubtype, Map<String, Any?>>, extra: Map<String, Any?> = emptyMap()) {
        val keyboards = sets.keys.toList()
        val values = HashMap<String, Any?>()
        values[Settings.PREF_ENABLED_SUBTYPES] = SubtypeSettings.createPrefSubtypes(keyboards)
        values[Settings.PREF_ADDITIONAL_SUBTYPES] = SubtypeSettings.createPrefSubtypes(keyboards)
        values[Settings.PREF_SELECTED_SUBTYPE] = keyboards.first().toPref()
        values["separate_settings_per_keyboard"] = true
        val ids = JSONObject()
        keyboards.forEachIndexed { i, kb -> ids.put(kb.toPref(), i + 1) }
        values["keyboard_profile_ids"] = ids.toString()
        values[helium314.keyboard.latin.PICKED_ONLY_DONE] = true
        values[helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE] = true
        values[helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE] = true
        keyboards.forEachIndexed { i, kb -> for ((key, value) in sets.getValue(kb)) values[KeyboardProfiles.ownKey(i + 1, key)] = value }
        values.putAll(extra)
        backupValues = values
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(HashMap<String?, Any?>(values), z)
            z.closeEntry()
        }
    }

    /** "What to merge" of the backup's Dvorak into the phone's QWERTY. */
    private fun choice(): MergeChoice {
        val plan = restorePlanFrom(ctx, zip)
        val question = MergeQuestion(plan, dvorak, listOf(dvorak), emptyMap())
        val differences = RestoreMatch.differences(qwerty, plan.incoming.getValue(dvorak), true,
            { RestoreMatch.phoneSettings(real, qwerty) }, { restoredSettings(ctx, backupValues, dvorak) })
        return MergeChoice(question, qwerty, differences, wordsRow = false)
    }
    private fun MergeChoice.menu(group: KeyboardProfiles.Group) = differences.single { it == RestoreMatch.Difference.Menu(group) }
    private fun MergeChoice.keys() = differences.single { it is RestoreMatch.Difference.Definition && it.part == RestoreMatch.Part.Layout(LayoutType.MAIN) }

    // ---- (1) the one line ----

    private val replacesLine = "Selected items replace this keyboard's settings, including any unsaved changes."

    @Test fun `the line shows while something is ticked, not while nothing is`() {
        phone(mapOf(CustomLabels.Kind.THEME.key to 1, Settings.PREF_THEME_COLORS to "x2"))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to "y")))
        val choice = choice()
        assertNull(choice.replacesNote(ctx))
        val appearance = choice.menu(KeyboardProfiles.Group.APPEARANCE)
        choice.ticked.add(appearance)
        assertEquals(replacesLine, choice.replacesNote(ctx))
        choice.ticked.add(choice.keys())
        assertEquals(replacesLine, choice.replacesNote(ctx))
        choice.ticked.clear()
        assertNull(choice.replacesNote(ctx))
        // confirmed, the unsaved theme is replaced
        choice.ticked.add(appearance)
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to choice.decision))
        val merged = KeyboardProfiles.idEntries(real).values.single { it != KeyboardProfiles.SHARED }
        assertEquals("y", own(merged, Settings.PREF_THEME_COLORS))
        assertFalse(CustomLabels.has(helium314.keyboard.latin.settings.ProfilePreferences(real) { merged }, CustomLabels.Kind.THEME))
    }

    @Test fun `learned words alone ticked count as selected`() {
        phone(mapOf(Settings.PREF_THEME_COLORS to "x2"))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to "y")))
        val plan = restorePlanFrom(ctx, zip)
        val question = MergeQuestion(plan, dvorak, listOf(dvorak), emptyMap())
        val choice = MergeChoice(question, qwerty, emptyList(), wordsRow = true)
        assertEquals(replacesLine, choice.replacesNote(ctx))
        choice.wordsTicked = false
        assertNull(choice.replacesNote(ctx))
    }

    // ---- (2) kept palettes ----

    private val keysOnly get() = RestoreDecision.Merge(qwerty, parts = setOf(RestoreMatch.Part.Layout(LayoutType.MAIN)), menus = emptySet(), learnedWords = false)

    private fun phoneOnPalette1() = phone(mapOf(Settings.PREF_THEME_COLORS to "Palette 1"), list = mapOf(listed1 to paletteA))

    @Test fun `a merge keeping the phone's look - another restored keyboard's palette of that name comes in under the next number`() {
        phoneOnPalette1()
        backup(mapOf(dvorak to emptyMap(), colemak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to paletteB)))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak, colemak), withSettings = true,
            decisions = mapOf(dvorak to keysOnly, colemak to RestoreDecision.Separate()))
        val ids = KeyboardProfiles.idEntries(real)
        val merged = ids.getValue(dvorak.toPref())
        assertEquals("Palette 1", own(merged, Settings.PREF_THEME_COLORS))
        assertEquals(paletteA, real.all[listed1])
        val added = ids.getValue(colemak.toPref())
        assertEquals("Palette 2", own(added, Settings.PREF_THEME_COLORS))
        assertEquals(paletteB, real.all[listed2])
        assertNull(own(added, colors1)) // (no copy in a set)
    }

    @Test fun `a merge keeping the phone's look - a restored saved theme with that palette name comes in under the next number`() {
        phoneOnPalette1()
        val look = JSONObject().put("name", "Dusk").put("values", JSONObject()
            .put(colors1, JSONObject().put("s", paletteB)).put(Settings.PREF_THEME_COLORS, JSONObject().put("s", hidden1)))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to paletteB)),
            extra = mapOf(AppearanceLooks.PREF to JSONArray(listOf(look)).toString()))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to keysOnly))
        assertEquals(paletteA, real.all[listed1])
        val dusk = AppearanceLooks.load(real).single { it.name == "Dusk" }
        assertEquals("Palette 2", dusk.values[Settings.PREF_THEME_COLORS])
        assertEquals(paletteB, real.all[listed2])
        assertNull(dusk.values[colors1]) // (a theme names its palette, no copy)
    }

    @Test fun `a separate keyboard's palette of a name the phone keyboard uses comes in under the next number`() {
        phoneOnPalette1()
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to paletteB)))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to RestoreDecision.Separate()))
        assertEquals("Palette 1", own(phoneId(), Settings.PREF_THEME_COLORS))
        assertEquals(paletteA, real.all[listed1])
        val added = KeyboardProfiles.idEntries(real).getValue(dvorak.toPref())
        assertEquals("Palette 2", own(added, Settings.PREF_THEME_COLORS))
        assertEquals(paletteB, real.all[listed2])
    }

    @Test fun `the same colors under the same name are the phone's palette - no second one`() {
        phoneOnPalette1()
        backup(mapOf(dvorak to emptyMap(), colemak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to paletteA)))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak, colemak), withSettings = true,
            decisions = mapOf(dvorak to keysOnly, colemak to RestoreDecision.Separate()))
        val added = KeyboardProfiles.idEntries(real).getValue(colemak.toPref())
        assertEquals("Palette 1", own(added, Settings.PREF_THEME_COLORS))
        assertFalse(real.all.keys.any { it.endsWith(listed2) })
    }
}

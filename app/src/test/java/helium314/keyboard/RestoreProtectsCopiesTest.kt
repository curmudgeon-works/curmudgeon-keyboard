// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A restore of some keyboards never trashes what the phone holds unsaved (owner 2026-10-11: "main concern is to make
 * sure nothing gets trashed"; "Go!"): (1) "What to merge" warns when a ticked row replaces an unsaved Custom copy on
 * the phone keyboard (theme, Layout, popups); (2) a kept palette "~custom:N" the phone uses for other colours is never
 * written over: the backup's comes in under the next free number.
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
    private val hidden1 = KeyboardTheme.HIDDEN_PALETTE + "1"
    private val hidden2 = KeyboardTheme.HIDDEN_PALETTE + "2"
    private val colors1 = Settings.PREF_USER_COLORS_PREFIX + hidden1
    private val colors2 = Settings.PREF_USER_COLORS_PREFIX + hidden2
    private var backupValues: Map<String, Any?> = emptyMap()

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        qwerty = available("en-US")
        dvorak = qwerty.withLayout(LayoutType.MAIN, "dvorak").toPref().toSettingsSubtype()
        colemak = qwerty.withLayout(LayoutType.MAIN, "colemak").toPref().toSettingsSubtype()
        english = keyboardName(qwerty, ctx)
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** The phone: QWERTY, separate settings on, [own] in its own set. */
    private fun phone(own: Map<String, Any?>) {
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(qwerty)))
            .putBoolean("separate_settings_per_keyboard", true).commit()
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

    // ---- (1) the warning ----

    @Test fun `Appearance ticked over the phone keyboard's unsaved theme warns under that row, unticked doesn't`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Work", mapOf(Settings.PREF_THEME_COLORS to "x"))), now = true)
        phone(mapOf(CustomLabels.Kind.THEME.key to 1, AppearanceLooks.PREF_SELECTED to "Work", Settings.PREF_THEME_COLORS to "x2"))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to "y")))
        val choice = choice()
        val appearance = choice.menu(KeyboardProfiles.Group.APPEARANCE)
        assertNull(choice.warning(ctx, appearance))
        choice.ticked.add(appearance)
        assertEquals("$english's unsaved theme (Custom · from Work) will be replaced. Save it as a theme first if you want to keep it.",
            choice.warning(ctx, appearance))
        // the keys don't touch a theme
        choice.ticked.add(choice.keys())
        assertNull(choice.warning(ctx, choice.keys()))
        // a warning only: confirmed, the merge goes ahead
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to choice.decision))
        val merged = KeyboardProfiles.idEntries(real).values.single { it != KeyboardProfiles.SHARED }
        assertEquals("y", own(merged, Settings.PREF_THEME_COLORS))
        assertFalse(CustomLabels.has(helium314.keyboard.latin.settings.ProfilePreferences(real) { merged }, CustomLabels.Kind.THEME))
    }

    @Test fun `a theme whose saved item is gone is just Custom`() {
        phone(mapOf(CustomLabels.Kind.THEME.key to 1, Settings.PREF_THEME_COLORS to "x2"))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to "y")))
        val choice = choice()
        val appearance = choice.menu(KeyboardProfiles.Group.APPEARANCE)
        choice.ticked.add(appearance)
        assertEquals("$english's unsaved theme (Custom) will be replaced. Save it as a theme first if you want to keep it.",
            choice.warning(ctx, appearance))
    }

    @Test fun `the Layout and typing menu or the keys over an unsaved Layout and popups warn, once`() {
        phone(mapOf(CustomLabels.Kind.LAYOUT.key to 1, CustomLabels.Kind.POPUPS.key to 1, Settings.PREF_SHOW_NUMBER_ROW to false))
        backup(mapOf(dvorak to mapOf(Settings.PREF_SHOW_NUMBER_ROW to true)))
        val choice = choice()
        val layout = choice.menu(KeyboardProfiles.Group.LAYOUT)
        val keys = choice.keys()
        assertNull(choice.warning(ctx, layout))
        val layoutLine = "$english's unsaved Layout (Custom) will be replaced. Save it as a Layout first if you want to keep it."
        val popupsLine = "$english's unsaved popups (Custom) will be replaced. Save them as a popup set first if you want to keep them."
        choice.ticked.add(keys)
        assertEquals(layoutLine, choice.warning(ctx, keys))
        choice.ticked.add(layout)
        // (the Layout's line stays under the first ticked row that replaces it)
        assertEquals(popupsLine, choice.warning(ctx, layout))
        choice.ticked.remove(keys)
        assertEquals("$layoutLine\n$popupsLine", choice.warning(ctx, layout))
    }

    @Test fun `no Custom label on the phone keyboard - no warning`() {
        phone(mapOf(Settings.PREF_THEME_COLORS to "x2"))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to "y")))
        val choice = choice()
        val appearance = choice.menu(KeyboardProfiles.Group.APPEARANCE)
        choice.ticked.add(appearance)
        assertNull(choice.warning(ctx, appearance))
    }

    // ---- (2) kept palettes ----

    private val keysOnly get() = RestoreDecision.Merge(qwerty, parts = setOf(RestoreMatch.Part.Layout(LayoutType.MAIN)), menus = emptySet(), learnedWords = false)

    @Test fun `a merge keeping the phone's look - another restored keyboard's palette of that name comes in under the next number`() {
        phone(mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "A"))
        backup(mapOf(dvorak to emptyMap(), colemak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "B")))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak, colemak), withSettings = true,
            decisions = mapOf(dvorak to keysOnly, colemak to RestoreDecision.Separate()))
        val ids = KeyboardProfiles.idEntries(real)
        val merged = ids.getValue(dvorak.toPref())
        assertEquals(hidden1, own(merged, Settings.PREF_THEME_COLORS))
        assertEquals("A", own(merged, colors1))
        val added = ids.getValue(colemak.toPref())
        assertEquals(hidden2, own(added, Settings.PREF_THEME_COLORS))
        assertEquals("B", own(added, colors2))
        assertNull(own(added, colors1))
    }

    @Test fun `a merge keeping the phone's look - a restored saved theme with that palette name comes in under the next number`() {
        phone(mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "A"))
        val look = JSONObject().put("name", "Dusk").put("values", JSONObject()
            .put(colors1, JSONObject().put("s", "B")).put(Settings.PREF_THEME_COLORS, JSONObject().put("s", hidden1)))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "B")),
            extra = mapOf(AppearanceLooks.PREF to JSONArray(listOf(look)).toString()))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to keysOnly))
        val merged = KeyboardProfiles.idEntries(real).getValue(dvorak.toPref())
        assertEquals("A", own(merged, colors1))
        val dusk = AppearanceLooks.load(real).single { it.name == "Dusk" }
        assertEquals(hidden2, dusk.values[Settings.PREF_THEME_COLORS])
        assertEquals("B", dusk.values[colors2])
        assertNull(dusk.values[colors1])
    }

    @Test fun `a separate keyboard's palette of a name the phone keyboard uses comes in under the next number`() {
        phone(mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "A"))
        backup(mapOf(dvorak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "B")))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to RestoreDecision.Separate()))
        assertEquals("A", own(phoneId(), colors1))
        val added = KeyboardProfiles.idEntries(real).getValue(dvorak.toPref())
        assertEquals(hidden2, own(added, Settings.PREF_THEME_COLORS))
        assertEquals("B", own(added, colors2))
    }

    @Test fun `the same colours under the same name are the phone's palette - no second one`() {
        phone(mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "A"))
        backup(mapOf(dvorak to emptyMap(), colemak to mapOf(Settings.PREF_THEME_COLORS to hidden1, colors1 to "A")))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak, colemak), withSettings = true,
            decisions = mapOf(dvorak to keysOnly, colemak to RestoreDecision.Separate()))
        val added = KeyboardProfiles.idEntries(real).getValue(colemak.toPref())
        assertEquals(hidden1, own(added, Settings.PREF_THEME_COLORS))
        assertEquals("A", own(added, colors1))
        assertFalse(real.all.keys.any { it.endsWith(colors2) })
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.SavedItemsMerge
import helium314.keyboard.settings.preferences.SavedItemsMerge.Clash
import helium314.keyboard.settings.preferences.SavedItemsMerge.Kind
import helium314.keyboard.settings.preferences.readRestoreDialog
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Restoring some keyboards, a backup's saved item clashing with the device's, for palettes, themes, Layouts and popup
 * sets alike (owner 2026-10-12, the restore redesign: asked, Replace or Keep both; to start with, owner 2026-10-12 option
 * D: a device item with the same content merged into, else Keep both; was owner 2026-10-11 "backup wins the name if
 * ticked"):
 * (a) same name, other content: Replace, the device's becomes the backup's, every keyboard and saved item naming it
 * takes it; Keep both, the backup's is "Sunset", the device's "Sunset 2", its users following. Unticked, the backup's
 * doesn't come in and the restored keyboard names the device's Sunset.
 * (b) the same content under another name: Replace, the device's is renamed to the backup's name everywhere; unticked,
 * nothing renamed, the restored keyboard names the device's.
 * The restore dialog's dry run names them as the real run does (R3, R6).
 * (owner 2026-10-11: keyboards have unique ids) The phone's keyboard is "k1", the backup's "k2"; their sets "pk1/", "pk2/".
 */
@RunWith(RobolectricTestRunner::class)
class RestoreBackupWinsNameTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val phoneKeyboard = SettingsSubtype(Locale.GERMANY, "").withId(1)
    private val restored = SettingsSubtype(Locale.US, "").withId(2)
    private val zip by lazy { File(ctx.cacheDir, "backup-wins-${System.nanoTime()}.zip") }
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val numberRow = Settings.PREF_SHOW_NUMBER_ROW
    private val theme = AppearanceLooks.PREF_SELECTED
    private val layout = LayoutPresets.PREF_SELECTED
    private val popups = KeyPopupOverrides.PREF_SELECTED_SET
    private val slot = Settings.PREF_THEME_COLORS
    // (readable colors: a keyboard's settings may reload with them)
    private val colorsA = """[{"name":"background","auto":false,"color":-16777216}]"""
    private val colorsB = """[{"name":"background","auto":false,"color":-1}]"""

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.init(ctx) }
    @After fun tearDown() {
        zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The phone: [phoneKeyboard] ("k1") with [own] in its set, [extra] app-wide; the keyboard list and its
     *  registration in one write (no listener between them). */
    private fun phone(own: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()) {
        real.edit().apply {
            putString(Settings.PREF_ENABLED_SUBTYPES, phoneKeyboard.toPref())
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, phoneKeyboard.toPref())
            putString(Settings.PREF_SELECTED_SUBTYPE, phoneKeyboard.toPref())
            putBoolean("separate_settings_per_keyboard", true)
            for ((key, value) in own) KeyboardProfiles.put(this, idKey(1, key), value)
            for ((key, value) in extra) KeyboardProfiles.put(this, key, value)
        }.commit()
        KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The backup: [restored] ("k2") with a set of its own holding [own], plus [extra] backup-wide. */
    private fun backup(own: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()) {
        val values = HashMap<String?, Any?>()
        values[Settings.PREF_ENABLED_SUBTYPES] = restored.toPref()
        values[Settings.PREF_ADDITIONAL_SUBTYPES] = restored.toPref()
        values["separate_settings_per_keyboard"] = true
        values[KeyboardIdUpgrade.DONE] = true
        values[KeyboardProfiles.NO_COMMON_SET_DONE] = true
        values[helium314.keyboard.latin.PICKED_ONLY_DONE] = true
        values[helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE] = true
        values[helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE] = true
        for ((key, value) in own) values[idKey(2, key)] = value
        values.putAll(extra)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(values, z)
            z.closeEntry()
        }
    }

    private fun look(name: String, gapValue: Double, palette: String? = null) = JSONObject().put("name", name).put("values",
        JSONObject().put(gap, JSONObject().put("f", gapValue)).also { v -> palette?.let { v.put(slot, JSONObject().put("s", it)) } })
    private fun looks(vararg l: JSONObject) = AppearanceLooks.PREF to JSONArray(l.toList()).toString()
    private fun preset(name: String, row: Boolean, set: String? = null) = JSONObject().put("name", name).put("values",
        JSONObject().put(numberRow, JSONObject().put("b", row)).also { v -> set?.let { v.put(popups, JSONObject().put("s", it)) } })
    private fun presets(vararg p: JSONObject) = LayoutPresets.PREF to JSONArray(p.toList()).toString()
    private fun popupSet(name: String, a: String) = JSONObject().put("name", name).put("morePopups", "all").put("symbolsLayout", "")
        .put("overrides", JSONObject().put("a", JSONArray(listOf(a))))
    private fun sets(vararg s: JSONObject) = KeyPopupOverrides.PREF_SETS to JSONArray(s.toList()).toString()

    private fun restoredOwn(key: String) = real.all[idKey(2, key)] // (it keeps the backup's id)
    private fun phoneOwn(key: String) = real.all[idKey(1, key)]
    /** Every stored choice of [key] (any set) naming [name]. */
    private fun naming(key: String, name: String) = real.all.filter { (k, v) -> v == name && (k == key || k.endsWith("/$key")) }.keys
    private fun unticked(vararg keys: Pair<Kind, String>) = SavedItemsMerge.Choices(keys.toSet())
    /** Restores [restored] as [names] says; once on the phone, merged into itself, as it is there (unanswered it would
     *  come in as another keyboard with its id, and the phone's would get a new one; owner 2026-10-11: keyboards have
     *  unique ids). */
    private fun restore(names: SavedItemsMerge.Choices = SavedItemsMerge.Choices()) {
        val decisions = listedById(real, kid(2))?.let { mapOf(restored to RestoreDecision.Merge(it)) } ?: emptyMap()
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true, names = names, decisions = decisions)
    }

    // ---- themes ----

    private fun sunsetClash() {
        // (the phone's keyboard on its Sunset, gap 1; a phone Layout doesn't name themes)
        phone(mapOf(theme to "Sunset", gap to 1f), mapOf(looks(look("Sunset", 1.0))))
        backup(mapOf(theme to "Sunset", gap to 3f), mapOf(looks(look("Sunset", 3.0))))
    }

    private fun replace(kind: Kind, name: String) = SavedItemsMerge.Choices(answers = mapOf((kind to name) to SavedItemsMerge.Answer.Replace(name)))

    // (answered Replace: the same name with other content suggests Keep both, owner 2026-10-12 option D, A2)
    @Test fun `same-name theme replaced - the device's Sunset is the backup's, the device keyboard on it takes its look`() {
        sunsetClash()
        assertEquals(listOf(Clash(Kind.THEMES, "Sunset", "Sunset", "Sunset 2")), restorePlanFrom(ctx, zip).clashes)
        restore(replace(Kind.THEMES, "Sunset"))
        val list = AppearanceLooks.load(real)
        assertEquals(listOf("Sunset"), list.map { it.name })
        assertEquals(3f, list.single().values[gap])
        assertEquals("Sunset", phoneOwn(theme))
        assertEquals(3f, phoneOwn(gap), "they take the backup's look (owner 2026-10-12)")
        assertEquals("Sunset", restoredOwn(theme))
    }

    // owner 2026-10-12: Keep both, "yours becomes Sunset 2 (keyboards using it keep their look)"
    @Test fun `same-name theme kept both - the backup's is Sunset, the device's Sunset 2 with its keyboard on it`() {
        sunsetClash()
        restore(SavedItemsMerge.Choices(answers = mapOf((Kind.THEMES to "Sunset") to SavedItemsMerge.Answer.KeepBoth)))
        val list = AppearanceLooks.load(real)
        assertEquals(listOf("Sunset 2", "Sunset"), list.map { it.name })
        assertEquals(3f, list.single { it.name == "Sunset" }.values[gap])
        assertEquals(1f, list.single { it.name == "Sunset 2" }.values[gap])
        assertEquals("Sunset 2", phoneOwn(theme))
        assertEquals(1f, phoneOwn(gap))
        assertEquals("Sunset", restoredOwn(theme))
    }

    @Test fun `same-name theme unticked - the backup's is absent, the restored keyboard names the phone's Sunset`() {
        sunsetClash()
        restore(unticked(Kind.THEMES to "Sunset"))
        val list = AppearanceLooks.load(real)
        assertEquals(listOf("Sunset"), list.map { it.name })
        assertEquals(1f, list.single().values[gap])
        assertEquals("Sunset", restoredOwn(theme))
        assertEquals(3f, restoredOwn(gap)) // (its own look, as in the backup)
        assertEquals("Sunset", phoneOwn(theme))
    }

    private fun duskSameAsSunset() {
        phone(mapOf(theme to "Sunset"), mapOf(looks(look("Sunset", 1.0))))
        backup(mapOf(theme to "Dusk"), mapOf(looks(look("Dusk", 1.0))))
    }

    @Test fun `different-name same-content theme ticked - the phone's is renamed Dusk everywhere`() {
        duskSameAsSunset()
        assertEquals(listOf(Clash(Kind.THEMES, "Dusk", "Sunset")), restorePlanFrom(ctx, zip).clashes)
        restore()
        assertEquals(listOf("Dusk"), AppearanceLooks.load(real).map { it.name })
        assertEquals("Dusk", phoneOwn(theme))
        assertEquals("Dusk", restoredOwn(theme))
        assertEquals(emptySet(), naming(theme, "Sunset"))
    }

    @Test fun `different-name same-content theme unticked - nothing renamed, the restored keyboard names the phone's Sunset`() {
        duskSameAsSunset()
        restore(unticked(Kind.THEMES to "Dusk"))
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name })
        assertEquals("Sunset", phoneOwn(theme))
        assertEquals("Sunset", restoredOwn(theme))
    }

    @Test fun `identical name and content - the phone's, nothing asked`() {
        phone(mapOf(theme to "Sunset"), mapOf(looks(look("Sunset", 1.0))))
        backup(mapOf(theme to "Sunset"), mapOf(looks(look("Sunset", 1.0))))
        assertEquals(emptyList(), restorePlanFrom(ctx, zip).clashes)
        restore()
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name })
    }

    // ---- palettes: the phone's saved themes naming them follow too ----

    @Test fun `same-name palette - replaced, Warm has the backup's colors, kept both, the device's is Warm 2 with its users`() {
        phone(mapOf(slot to "Warm"), mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to colorsA, looks(look("Mine", 1.0, "Warm"))))
        backup(mapOf(slot to "Warm"), mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to colorsB))
        assertEquals(listOf(Clash(Kind.PALETTES, "Warm", "Warm", "Warm 2")), restorePlanFrom(ctx, zip).clashes)
        restore(replace(Kind.PALETTES, "Warm"))
        assertEquals(colorsB, real.all[Settings.PREF_USER_COLORS_PREFIX + "Warm"])
        assertEquals(null, real.all[Settings.PREF_USER_COLORS_PREFIX + "Warm 2"])
        assertEquals("Warm", phoneOwn(slot))
        assertEquals("Warm", restoredOwn(slot))
        assertEquals("Warm", AppearanceLooks.load(real).single { it.name == "Mine" }.values[slot])
        // kept both (owner 2026-10-12): the device's keyboard and theme follow its Warm 2
        real.edit().clear().commit()
        phone(mapOf(slot to "Warm"), mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to colorsA, looks(look("Mine", 1.0, "Warm"))))
        restore(SavedItemsMerge.Choices(answers = mapOf((Kind.PALETTES to "Warm") to SavedItemsMerge.Answer.KeepBoth)))
        assertEquals(colorsB, real.all[Settings.PREF_USER_COLORS_PREFIX + "Warm"])
        assertEquals(colorsA, real.all[Settings.PREF_USER_COLORS_PREFIX + "Warm 2"])
        assertEquals("Warm 2", phoneOwn(slot))
        assertEquals("Warm", restoredOwn(slot))
        assertEquals("Warm 2", AppearanceLooks.load(real).single { it.name == "Mine" }.values[slot])
    }

    @Test fun `different-name same-colors palette ticked - the phone's is renamed Hot in every keyboard and theme, unticked nothing`() {
        phone(mapOf(slot to "Warm"), mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to colorsA, looks(look("Mine", 1.0, "Warm"))))
        backup(mapOf(slot to "Hot"), mapOf(Settings.PREF_USER_COLORS_PREFIX + "Hot" to colorsA))
        assertEquals(listOf(Clash(Kind.PALETTES, "Hot", "Warm")), restorePlanFrom(ctx, zip).clashes)
        restore(unticked(Kind.PALETTES to "Hot"))
        assertEquals("Warm", phoneOwn(slot))
        assertEquals("Warm", restoredOwn(slot))
        assertEquals(null, real.all[Settings.PREF_USER_COLORS_PREFIX + "Hot"])
        restore()
        assertEquals(colorsA, real.all[Settings.PREF_USER_COLORS_PREFIX + "Hot"])
        assertEquals(null, real.all[Settings.PREF_USER_COLORS_PREFIX + "Warm"])
        assertEquals("Hot", phoneOwn(slot))
        assertEquals("Hot", restoredOwn(slot))
        assertEquals("Hot", AppearanceLooks.load(real).single { it.name == "Mine" }.values[slot])
    }

    // ---- popup sets: the phone's saved Layouts naming them follow too ----

    @Test fun `different-name same-content popup set ticked - the phone's is renamed Mine in keyboards and Layouts, unticked nothing`() {
        val phoneSets = KeyPopupOverrides.PREF_SETS to JSONArray(listOf(popupSet("Ours", "á"))).toString()
        phone(mapOf(popups to "Ours"), mapOf(phoneSets, presets(preset("Home", true, "Ours"))))
        backup(mapOf(popups to "Mine"), mapOf(sets(popupSet("Mine", "á"))))
        assertEquals(listOf(Clash(Kind.POPUP_SETS, "Mine", "Ours")), restorePlanFrom(ctx, zip).clashes)
        restore(unticked(Kind.POPUP_SETS to "Mine"))
        assertEquals(listOf("Ours"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals("Ours", restoredOwn(popups))
        restore()
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals("Mine", phoneOwn(popups))
        assertEquals("Mine", restoredOwn(popups))
        assertEquals("Mine", LayoutPresets.load(real).single { it.name == "Home" }.values[popups])
    }

    @Test fun `same-name popup set replaced - Mine is the backup's, the device's keyboard and Layout stay on Mine`() {
        val phoneSets = KeyPopupOverrides.PREF_SETS to JSONArray(listOf(popupSet("Mine", "à"))).toString()
        phone(mapOf(popups to "Mine"), mapOf(phoneSets, presets(preset("Home", true, "Mine"))))
        backup(mapOf(popups to "Mine"), mapOf(sets(popupSet("Mine", "á"))))
        restore(replace(Kind.POPUP_SETS, "Mine"))
        val list = KeyPopupOverrides.loadSets(real)
        assertEquals(listOf("Mine"), list.map { it.name })
        assertEquals(listOf("á"), list.single().overrides["a"])
        assertEquals("Mine", phoneOwn(popups))
        assertEquals("Mine", LayoutPresets.load(real).single { it.name == "Home" }.values[popups])
        assertEquals(emptySet(), naming(popups, "Mine 2"))
    }

    // ---- Layouts ----

    @Test fun `same-name Layout ticked or unticked`() {
        phone(mapOf(layout to "Work"), mapOf(presets(preset("Work", false))))
        backup(mapOf(layout to "Work"), mapOf(presets(preset("Work", true))))
        assertEquals(listOf(Clash(Kind.LAYOUTS, "Work", "Work", "Work 2")), restorePlanFrom(ctx, zip).clashes)
        restore(unticked(Kind.LAYOUTS to "Work"))
        assertEquals(listOf("Work"), LayoutPresets.load(real).map { it.name })
        assertEquals(false, LayoutPresets.load(real).single().values[numberRow])
        assertEquals("Work", restoredOwn(layout))
        // (replaced, answered: owner 2026-10-12, the device's Work is the backup's, its keyboard on it; Keep both is the
        // suggested answer since option D, A2)
        restore(replace(Kind.LAYOUTS, "Work"))
        val list = LayoutPresets.load(real)
        assertEquals(listOf("Work"), list.map { it.name })
        assertEquals(true, list.single().values[numberRow])
        assertEquals("Work", phoneOwn(layout))
        assertEquals(emptySet(), naming(layout, "Work 2"))
    }

    @Test fun `different-name same-content Layout ticked - the phone's is renamed everywhere`() {
        phone(mapOf(layout to "Home"), mapOf(presets(preset("Home", true))))
        backup(mapOf(layout to "Work"), mapOf(presets(preset("Work", true))))
        assertEquals(listOf(Clash(Kind.LAYOUTS, "Work", "Home")), restorePlanFrom(ctx, zip).clashes)
        restore()
        assertEquals(listOf("Work"), LayoutPresets.load(real).map { it.name })
        assertEquals("Work", phoneOwn(layout))
        assertEquals("Work", restoredOwn(layout))
    }

    // ---- the dry run names them as the real run does ----

    /** All four kinds clash: a palette by colors (Hot = the phone's Warm), a theme by name (Sunset), a Layout by
     *  content (Work = the phone's Home), a popup set by content (Mine = the phone's Ours). */
    private fun everythingClashes() {
        val phoneSets = KeyPopupOverrides.PREF_SETS to JSONArray(listOf(popupSet("Ours", "á"))).toString()
        phone(mapOf(theme to "Sunset", layout to "Home", popups to "Ours", slot to "Warm"), mapOf(phoneSets,
            Settings.PREF_USER_COLORS_PREFIX + "Warm" to colorsA, looks(look("Sunset", 1.0)), presets(preset("Home", true))))
        backup(mapOf(theme to "Sunset", layout to "Work", popups to "Mine", slot to "Hot"), mapOf(sets(popupSet("Mine", "á")),
            Settings.PREF_USER_COLORS_PREFIX + "Hot" to colorsA, looks(look("Sunset", 3.0)), presets(preset("Work", true))))
    }

    /** The work still running in the background (on the keyboard executor, or posted to the main thread: another class's,
     *  or the last restore's) done, so what the dry run is checked for writing is its own. */
    private fun settle() = repeat(2) {
        helium314.keyboard.latin.utils.ExecutorUtils.getBackgroundExecutor(helium314.keyboard.latin.utils.ExecutorUtils.KEYBOARD).submit { }.get()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // (the swipe statistics' write scheduled by another class lands 2 s later on its own timer: written now and
        // dropped from memory, so the scheduled write finds nothing to write)
        helium314.keyboard.latin.gesture.GestureStats.flush(real)
        helium314.keyboard.latin.gesture.GestureStats.reload()
    }

    /** The keys stored differently in [a] and [b], each with its value in [a] and in [b]. */
    private fun changed(a: Map<String, Any?>, b: Map<String, Any?>) = (a.keys + b.keys).filter { a[it] != b[it] }.associateWith { a[it] to b[it] }

    @Test fun `the dry run's names are the real run's, ticked and unticked, and it writes nothing`() {
        val keys = listOf(theme, layout, popups, slot)
        for (names in listOf(SavedItemsMerge.Choices(), unticked(Kind.PALETTES to "Hot", Kind.THEMES to "Sunset",
                Kind.LAYOUTS to "Work", Kind.POPUP_SETS to "Mine"))) {
            real.edit().clear().commit()
            everythingClashes()
            settle()
            val before = HashMap(real.all)
            val plan = readRestoreDialog(ctx, zip).plan
            assertEquals(setOf(Kind.PALETTES to "Hot", Kind.THEMES to "Sunset", Kind.LAYOUTS to "Work", Kind.POPUP_SETS to "Mine"),
                plan.clashes.map { it.key }.toSet())
            val dry = plan.backupSettings(restored, names).filterKeys { it in keys }
            val dryPhone = plan.phoneSettings(keys.associateWith { phoneOwn(it) }, names)
            // (nothing written; the keys that changed, with their values before and after, if any)
            assertEquals(emptyMap(), changed(before, HashMap(real.all)), "the dry run wrote, $names")
            restore(names)
            assertEquals(dry, keys.associateWith { restoredOwn(it) }, "the restored keyboard's names, $names")
            assertEquals(dryPhone, keys.associateWith { phoneOwn(it) }, "the phone keyboard's names, $names")
        }
    }

    @Test fun `a second restore of the same backup adds nothing, ticked`() {
        everythingClashes()
        restore()
        val lists = listOf(LayoutPresets.PREF, AppearanceLooks.PREF, KeyPopupOverrides.PREF_SETS).associateWith { real.all[it] }
        val palettes = real.all.filterKeys { it.startsWith(Settings.PREF_USER_COLORS_PREFIX) }
        assertEquals(emptyList(), restorePlanFrom(ctx, zip).clashes)
        restore()
        assertEquals(lists, listOf(LayoutPresets.PREF, AppearanceLooks.PREF, KeyPopupOverrides.PREF_SETS).associateWith { real.all[it] })
        assertEquals(palettes, real.all.filterKeys { it.startsWith(Settings.PREF_USER_COLORS_PREFIX) })
        assertFalse(AppearanceLooks.load(real).any { it.name == "Sunset 3" })
    }
}

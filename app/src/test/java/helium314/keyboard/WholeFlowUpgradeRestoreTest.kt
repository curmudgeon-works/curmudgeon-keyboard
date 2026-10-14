// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE
import helium314.keyboard.latin.PICKED_ONLY_DONE
import helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardIds
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SavedItemDefaults
import helium314.keyboard.settings.SettingDefaults
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.restoreFollowUp
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review E (2026-10-11): the whole flow from the build on the phones before this batch (3afbeeebc): its settings
 * upgraded twice at app start, and its backup restored in full and in part on a phone of this build. Regression guards:
 * the first upgrade only adds what this batch means to (saved Layouts and themes remember their unpicked settings'
 * values, the one-time flag), the second changes nothing, no keyboard's setting changes and no default is written down.
 * (owner 2026-10-11: keyboards have unique ids) The old build's keyboards get ids at the upgrade (its backup's when it is
 * read or restored in full): their sets "p<n>/" become "p<id>/"; the checks compare with the old settings as the ids'
 * upgrade leaves them.
 */
@RunWith(RobolectricTestRunner::class)
class WholeFlowUpgradeRestoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val us = SettingsSubtype(Locale.US, "")
    private val fr = SettingsSubtype(Locale.FRANCE, "")
    private val zip by lazy { File(ctx.cacheDir, "whole-flow-${System.nanoTime()}.zip") }

    private val longPress = Settings.PREF_KEY_LONGPRESS_TIMEOUT
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val vGap = Settings.PREF_KEY_VERTICAL_GAP
    private val numberRow = Settings.PREF_SHOW_NUMBER_ROW
    private val symbolMap = Settings.PREF_SYMBOL_POPUP_MAP
    private val selectedSet = KeyPopupOverrides.PREF_SELECTED_SET
    private fun own(id: String, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun mark(id: String, key: String) = KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + key)
    // (the old build's set keys: set number n)
    private fun oldOwn(n: Int, key: String) = "p$n/$key"
    private fun oldMark(n: Int, key: String) = oldOwn(n, KeyboardProfiles.TOMBSTONE + key)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { randomIds(); zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    // ---- what the 3afbeeebc build stores on a phone ----

    /** Saved Layout "Work" as 3afbeeebc wrote it: a value at its default is {"d":true}, nothing remembered. */
    private fun oldWork(): JSONObject = JSONObject().put("name", "Work").put("values", JSONObject()
        .put(numberRow, JSONObject().put("b", true))
        .put(selectedSet, JSONObject().put("s", "Mine"))
        .put(longPress, JSONObject().put("d", true))
        .put(symbolMap, JSONObject().put("d", true)))

    /** Saved theme "Sunset" as 3afbeeebc wrote it. */
    private fun oldSunset(): JSONObject = JSONObject().put("name", "Sunset").put("values", JSONObject()
        .put(gap, JSONObject().put("f", 3.0))
        .put(vGap, JSONObject().put("d", true))
        .put(Settings.PREF_THEME_COLORS, JSONObject().put("s", "black")))

    /** User popup set "Mine" as 3afbeeebc saved it: its map and order, no "atDefault". */
    private fun oldMine(): JSONObject = JSONObject().put("name", "Mine").put("morePopups", "all").put("symbolsLayout", "")
        .put("symbolMap", Defaults.CURMUDGEON_SYMBOL_POPUP_MAP).put("popupOrder", Defaults.PREF_POPUP_KEYS_ORDER)
        .put("overrides", JSONObject().put("a", JSONArray(listOf("á", "à"))))

    /**
     * A phone on 3afbeeebc after its app start ran: separate settings on, keyboards US (set 1) and French (set 2) with
     * their own sets; US chose Work, Sunset and Mine; French picked its own long press and reads the default gap (a mark);
     * the shared set keeps what the old feedback and look steps wrote (vibration and sound off, gap 0.5). Every one-time
     * flag that build's start sets is there; the switch "Saved items take new defaults" isn't (that build has none).
     */
    private fun phoneOn3afbeeebc(): Map<String, Any> = mapOf(
        Settings.PREF_VERSION_CODE to BuildConfig.MIGRATION_VERSION,
        Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(us, fr)),
        Settings.PREF_SELECTED_SUBTYPE to us.toPref(),
        "separate_settings_per_keyboard" to true,
        // (the old map, definition -> set number: gone since keyboards have ids)
        KeyboardIdUpgrade.OLD_IDS to JSONObject().put(us.toPref(), 1).put(fr.toPref(), 2).toString(),
        "keyboard_profile_next_id" to 3,
        // the shared set
        Settings.PREF_VIBRATE_ON to false, Settings.PREF_SOUND_ON to false, gap to 0.5f, Settings.PREF_THEME_DAY_NIGHT to true,
        // saved items in the old format
        LayoutPresets.PREF to JSONArray(listOf(oldWork())).toString(),
        AppearanceLooks.PREF to JSONArray(listOf(oldSunset())).toString(),
        KeyPopupOverrides.PREF_SETS to JSONArray(listOf(oldMine())).toString(),
        // US's own set: on Work, Sunset (its look written) and Mine (its map written)
        oldOwn(1, LayoutPresets.PREF_SELECTED) to "Work", oldOwn(1, numberRow) to true,
        oldOwn(1, AppearanceLooks.PREF_SELECTED) to "Sunset", oldOwn(1, gap) to 3f, oldOwn(1, Settings.PREF_THEME_COLORS) to "black",
        oldOwn(1, selectedSet) to "Mine", oldOwn(1, symbolMap) to Defaults.CURMUDGEON_SYMBOL_POPUP_MAP,
        oldOwn(1, "fonts_follow_migrated") to true, oldOwn(1, "trail_thickness_migrated") to true,
        // French's own set
        oldOwn(2, longPress) to 400, oldMark(2, gap) to true, oldOwn(2, Settings.PREF_THEME_COLORS) to "dark",
        oldOwn(2, "fonts_follow_migrated") to true, oldOwn(2, "trail_thickness_migrated") to true,
        // the one-time flags of 3afbeeebc's app start (curmudgeonUpgrades, the picked-only, pins and popup map steps, the
        // settings moves, the files and pictures moves, the learned words per keyboard)
        "fonts_follow_migrated" to true, "trail_thickness_migrated" to true, "key_gap_defaults_migrated" to true,
        "defaults_feedback_on_done" to true, "defaults_look_midnight_done" to true,
        PICKED_ONLY_DONE to true, HELIBOARD_LAYOUT_PINS_DONE to true, USER_POPUP_SETS_MAP_DONE to true,
        "learning_swiping_global" to true, "suggestions_refine_moved" to true, "learning_group_split_done" to true,
        "moved_markers_removed" to true, "profile_files_migrated" to true, "picture_library_migrated" to true,
    )

    /** The keyboard settings of 3afbeeebc's common set (above). */
    private val commonOf3afbeeebc = listOf(Settings.PREF_VIBRATE_ON, Settings.PREF_SOUND_ON, gap, Settings.PREF_THEME_DAY_NIGHT)

    private fun write(prefs: Map<String, Any>) {

        real.edit().apply { for ((key, value) in prefs) KeyboardProfiles.put(this, key, value) }.commit()
        KeyboardProfiles.loadGroups(real)
    }

    /** What app start runs on the settings (App.onCreate: the keyboards' ids, the settings moves, then the version upgrade). */
    private fun appStart() {
        KeyboardIdUpgrade.upgrade(ctx)
        KeyboardProfiles.settingsMoves(real)
        checkVersionUpgrade(ctx)
    }

    /** The keys that are a keyboard's setting (the shared set's or a keyboard's own, marks too): no one-time flag, no
     *  app-wide bookkeeping (keyboard list, saved items, the switch). */
    private fun isLive(stored: String): Boolean {
        val plain = (KeyboardProfiles.splitOwnKey(stored)?.second ?: stored).removePrefix(KeyboardProfiles.TOMBSTONE)
        if (KeyboardProfiles.isOneTimeFlag(plain) || plain.endsWith("_migrated") || plain.endsWith("_done")) return false
        return !KeyboardProfiles.isAppWide(plain)
    }
    private fun live(all: Map<String, *>) = all.filterKeys { isLive(it) }

    /** [prefs] as the keyboards' ids upgrade leaves them, the keyboards getting [ids] in list order. */
    private fun idsUpgraded(prefs: Map<String, Any?>, ids: List<String>): Map<String, Any?> {
        val map = HashMap<String, Any?>(prefs)
        val next = ids.iterator()
        KeyboardIds.source = { next.next() }
        try { KeyboardIdUpgrade.upgrade(map) } finally { randomIds() }
        return map
    }

    /** The ids of US and French in the stored list. */
    private fun usAndFrenchIds(): List<String> = listOf(us, fr).map { kb ->
        storedEnabled(real).single { it.withoutIds() == kb }.keyboardId!! }

    private fun changedKeys(before: Map<String, *>, after: Map<String, *>) =
        (before.keys + after.keys).filterTo(sortedSetOf()) { before[it] != after[it] }

    /** The remembered value of every unpicked setting an item holds: today's default (where it has one). */
    private fun assertRemembersToday(what: String, values: Map<String, Any?>, remembered: Map<String, Any>) {
        for ((key, value) in values) {
            if (value != null) { assertFalse(key in remembered, "$what remembers its picked $key"); continue }
            val today = SettingDefaults.of(key) ?: continue
            assertEquals(today, remembered[key], "$what remembers $key")
        }
    }

    private fun backupOf(prefs: Map<String, Any>) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(prefs.mapKeys { it.key as String? }, z)
            z.closeEntry()
        }
    }

    // ---- 1: upgrade twice ----

    @Test fun `the 3afbeeebc settings upgraded twice - the first adds only remembered values and the flag, the second nothing`() {
        val old = phoneOn3afbeeebc()
        write(old)
        val oldLayouts = LayoutPresets.load(real)
        val oldLooks = AppearanceLooks.load(real)
        val oldSets = KeyPopupOverrides.loadSets(real)
        val before = HashMap(real.all)

        appStart()
        val first = HashMap(real.all)
        // (owner 2026-10-11: keyboards have unique ids) the keyboards got ids, their sets moved to them, the old map went
        val (usId, frId) = usAndFrenchIds()
        assertNotEquals(usId, frId)
        val upgraded = idsUpgraded(before, listOf(usId, frId))
        assertEquals("Work", first[own(usId, LayoutPresets.PREF_SELECTED)])
        assertEquals(400, first[own(frId, longPress)])
        assertNull(first[KeyboardIdUpgrade.OLD_IDS])
        assertTrue(first.keys.none { it.startsWith("p1/") || it.startsWith("p2/") }, "an old set key is left")
        assertEquals(true, first[KeyboardIdUpgrade.DONE])
        // (owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones) the common set
        // goes; US and French, each with a set of its own, keep their sets as they are: not filled up from the common set
        // (owner 2026-10-11, the upgrade decided: no gap-filling; only a keyboard without a set gets a copy of it; owner
        // 2026-10-11: the common set's palettes are deleted at the upgrade, no copies; this phone's common set has none)
        // (owner 2026-10-11: no default marks; nothing stored = the default: French's mark goes, once; the palettes' move
        // sets its flag too)
        assertEquals(sortedSetOf(LayoutPresets.PREF, AppearanceLooks.PREF, SavedItemDefaults.REMEMBERED_DONE, KeyboardProfiles.NO_COMMON_SET_DONE,
            helium314.keyboard.settings.Palettes.CENTRAL_DONE, KeyboardProfiles.NO_DEFAULT_MARKS_DONE, mark(frId, gap))
            + commonOf3afbeeebc, changedKeys(upgraded, first), "the first upgrade changed more than the ids, the saved Layouts and themes")
        assertEquals(true, first[SavedItemDefaults.REMEMBERED_DONE])
        assertFalse(first.containsKey(SavedItemDefaults.PREF), "the switch was written down")
        // no other keyboard's setting changed, and nothing written as a default
        assertEquals(live(upgraded) - commonOf3afbeeebc - mark(frId, gap), live(first))
        // the saved items: picked values as they were, unpicked ones remember today's default
        val layouts = LayoutPresets.load(real)
        assertEquals(oldLayouts.map { it.name to it.values }, layouts.map { it.name to it.values })
        assertTrue(layouts.single().remembered.isNotEmpty())
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, layouts.single().remembered[longPress])
        assertRemembersToday("Work", layouts.single().values, layouts.single().remembered)
        val looks = AppearanceLooks.load(real)
        assertEquals(oldLooks.map { it.name to it.values }, looks.map { it.name to it.values })
        assertEquals(Defaults.PREF_KEY_VERTICAL_GAP, looks.single().remembered[vGap])
        assertRemembersToday("Sunset", looks.single().values, looks.single().remembered)
        // the popup set: as it was (its own map and order are picks), nothing marked at its default
        val set = KeyPopupOverrides.loadSets(real).single()
        assertEquals(oldSets.single().symbolMap, set.symbolMap)
        assertEquals(oldSets.single().popupOrder, set.popupOrder)
        assertEquals(oldSets.single().overrides, set.overrides)
        assertTrue(set.atDefault.isEmpty(), "the old set came out with settings at their default: ${set.atDefault}")
        assertEquals(old[KeyPopupOverrides.PREF_SETS], first[KeyPopupOverrides.PREF_SETS])

        appStart()
        assertEquals(sortedSetOf(), changedKeys(first, HashMap(real.all)), "the second upgrade changed something")
    }

    // ---- 2: full restore ----

    /** A phone of this build: one keyboard (German, id k1), the switch off, its own saved Layout remembering a value. */
    private fun newPhoneSwitchOff() {
        val de = SettingsSubtype(Locale.GERMANY, "").withId(1)
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, de.toPref()).putString(Settings.PREF_ADDITIONAL_SUBTYPES, de.toPref())
            .putString(Settings.PREF_SELECTED_SUBTYPE, de.toPref()).putBoolean(KeyboardIdUpgrade.DONE, true)
            .putBoolean(SavedItemDefaults.PREF, false).putBoolean(SavedItemDefaults.REMEMBERED_DONE, true)
            .putBoolean(PICKED_ONLY_DONE, true).putBoolean(HELIBOARD_LAYOUT_PINS_DONE, true).putBoolean(USER_POPUP_SETS_MAP_DONE, true)
            .putInt(Settings.PREF_VERSION_CODE, BuildConfig.MIGRATION_VERSION).putInt(longPress, 250).commit()
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Home", mapOf(longPress to null), remembered = mapOf(longPress to 300))))
        KeyboardProfiles.loadGroups(real)
    }

    @Test fun `a 3afbeeebc backup restored in full - the phone's switch stays off, saved items remember, settings as in the backup`() {
        val old = phoneOn3afbeeebc()
        backupOf(old)
        newPhoneSwitchOff()

        restoreEverythingFrom(ctx, zip)
        restoreFollowUp(ctx) // (what runRestore does after every restore)

        assertEquals(false, real.all[SavedItemDefaults.PREF], "the phone's switch")
        assertEquals(true, real.all[SavedItemDefaults.REMEMBERED_DONE])
        // the backup's saved items, remembering today's values for their unpicked settings
        val work = LayoutPresets.load(real).single()
        assertEquals("Work", work.name)
        assertEquals(Defaults.PREF_KEY_LONGPRESS_TIMEOUT, work.remembered[longPress])
        assertRemembersToday("Work", work.values, work.remembered)
        val sunset = AppearanceLooks.load(real).single()
        assertEquals("Sunset", sunset.name)
        assertEquals(Defaults.PREF_KEY_VERTICAL_GAP, sunset.remembered[vGap])
        assertRemembersToday("Sunset", sunset.values, sunset.remembered)
        assertEquals(old[KeyPopupOverrides.PREF_SETS], real.all[KeyPopupOverrides.PREF_SETS])
        // every keyboard's settings as in the backup: nothing of the phone's left, nothing written down with the switch off
        // (the backup's common set dropped: owner 2026-10-11, "fine to trash common set with upgrade and restore")
        // (owner 2026-10-11: no default marks; nothing stored = the default: French's mark goes)
        // (owner 2026-10-11: keyboards have unique ids) the backup's keyboards got ids, their sets under them, the old map gone
        val (usId, frId) = usAndFrenchIds()
        val upgraded = idsUpgraded(old, listOf(usId, frId))
        assertEquals(live(upgraded) - commonOf3afbeeebc - mark(frId, gap), live(real.all), "live settings differ from the backup's")
        assertNull(real.all[KeyboardIdUpgrade.OLD_IDS])
        assertEquals(upgraded[Settings.PREF_ENABLED_SUBTYPES], real.all[Settings.PREF_ENABLED_SUBTYPES])
        assertEquals("Work", real.all[own(usId, LayoutPresets.PREF_SELECTED)])

        // the next app start changes nothing more
        val after = HashMap(real.all)
        appStart()
        assertEquals(sortedSetOf(), changedKeys(after, HashMap(real.all)), "the app start after the restore changed something")
    }

    // ---- 3: partial restore ----

    /** A phone of this build: French only (id k1, its own long press, on the phone's Layout "Work": number row off). */
    private fun newPhoneWithOtherWork() {
        val phoneFr = fr.withId(1)
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, phoneFr.toPref()).putString(Settings.PREF_ADDITIONAL_SUBTYPES, phoneFr.toPref())
            .putString(Settings.PREF_SELECTED_SUBTYPE, phoneFr.toPref())
            .putBoolean("separate_settings_per_keyboard", true).putBoolean(KeyboardIdUpgrade.DONE, true)
            .putBoolean(SavedItemDefaults.REMEMBERED_DONE, true)
            .putBoolean(PICKED_ONLY_DONE, true).putBoolean(HELIBOARD_LAYOUT_PINS_DONE, true).putBoolean(USER_POPUP_SETS_MAP_DONE, true)
            .putInt(Settings.PREF_VERSION_CODE, BuildConfig.MIGRATION_VERSION)
            .putInt(idKey(1, longPress), 250).putString(idKey(1, LayoutPresets.PREF_SELECTED), "Work").putBoolean(idKey(1, numberRow), false)
            // (its own set's flags, as this build's app start leaves them)
            .putBoolean(idKey(1, "fonts_follow_migrated"), true).putBoolean(idKey(1, "trail_thickness_migrated"), true)
            .commit()
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", mapOf(numberRow to false, longPress to null),
            remembered = mapOf(longPress to Defaults.PREF_KEY_LONGPRESS_TIMEOUT))))
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx) // (the list as stored, not an earlier test's)
    }

    // (owner 2026-10-12: Work kept both, the backup's keeps the name, the phone's is Work 2 and French follows it, keeping
    // its look; owner 2026-10-11 had French on the name, the backup's; before, the backup's Work came in as "Work 2")
    // (owner 2026-10-11: keyboards have unique ids) the old backup's keyboards get ids when it is read (counted here: US
    // k1000, French k1001); unasked, US comes in as a keyboard of its own with its id; the second restore merges it into
    // the phone's US (unasked, it would come in as a second US keyboard)
    @Test fun `US restored on its own from a 3afbeeebc backup - Work kept both, the phone's is Work 2, again adds nothing, French keeps its look`() {
        backupOf(phoneOn3afbeeebc())
        newPhoneWithOtherWork()
        val phoneWork = LayoutPresets.load(real).single()
        val french = real.all.filterKeys { it.startsWith(idKey(1, "")) }
        val backupUs = us.withId(1000)

        countingIds()
        restoreKeyboardsFrom(ctx, zip, listOf(backupUs), withSettings = true, names = helium314.keyboard.settings.preferences.SavedItemsMerge.Choices(
            answers = mapOf((helium314.keyboard.settings.preferences.SavedItemsMerge.Kind.LAYOUTS to "Work") to
                helium314.keyboard.settings.preferences.SavedItemsMerge.Answer.KeepBoth)))
        restoreFollowUp(ctx)
        // (French follows its Work, now "Work 2": its own values as they were)
        val frenchNow = french + (idKey(1, LayoutPresets.PREF_SELECTED) to "Work 2")

        val layouts = LayoutPresets.load(real)
        assertEquals(listOf("Work 2", "Work"), layouts.map { it.name })
        assertEquals(phoneWork.values, layouts[0].values, "the phone's Work changed")
        assertEquals(true, layouts[1].values[numberRow])
        assertEquals("Mine", layouts[1].values[selectedSet])
        assertNull(layouts[1].values[longPress])
        assertEquals(listOf("Mine"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name })
        val usId = kid(1000)
        val phoneUs = listedById(real, usId)
        assertEquals(us, phoneUs?.withoutIds(), "US isn't listed with the backup's id")
        assertNotEquals(kid(1), usId)
        assertEquals("Work", real.getString(own(usId, LayoutPresets.PREF_SELECTED), null))
        assertEquals("Mine", real.getString(own(usId, selectedSet), null))
        assertEquals("Sunset", real.getString(own(usId, AppearanceLooks.PREF_SELECTED), null))
        assertEquals(true, real.all[own(usId, numberRow)])
        assertEquals(3f, real.all[own(usId, gap)])
        assertEquals(fr.withId(1), listedById(real, kid(1)), "the phone's French keyboard changed")
        assertEquals(frenchNow, real.all.filterKeys { it.startsWith(idKey(1, "")) }, "the phone's French keyboard changed")
        val saved = listOf(LayoutPresets.PREF, AppearanceLooks.PREF, KeyPopupOverrides.PREF_SETS).associateWith { real.all[it] }

        countingIds()
        restoreKeyboardsFrom(ctx, zip, listOf(backupUs), withSettings = true,
            decisions = mapOf(backupUs to RestoreDecision.Merge(phoneUs!!)))
        restoreFollowUp(ctx)

        assertEquals(listOf("Work 2", "Work"), LayoutPresets.load(real).map { it.name }, "the second restore added a Layout")
        assertEquals(1, KeyPopupOverrides.loadSets(real).size, "the second restore added a popup set")
        assertEquals(1, AppearanceLooks.load(real).size, "the second restore added a theme")
        assertEquals(saved, listOf(LayoutPresets.PREF, AppearanceLooks.PREF, KeyPopupOverrides.PREF_SETS).associateWith { real.all[it] })
        assertEquals(2, storedEnabled(real).size, "the second restore added a keyboard")
        assertEquals(us, listedById(real, usId)?.withoutIds(), "the merge didn't keep the phone US's id")
        assertEquals("Work", real.getString(own(usId, LayoutPresets.PREF_SELECTED), null))
        assertEquals(frenchNow, real.all.filterKeys { it.startsWith(idKey(1, "")) }, "the phone's French keyboard changed")
    }
}

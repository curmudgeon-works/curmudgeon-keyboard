// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.storedButton
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardIds
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.SavedItemDefaults
import helium314.keyboard.settings.dialogs.StartSettings
import helium314.keyboard.settings.preferences.MergeQuestion
import helium314.keyboard.settings.preferences.ResetKeep
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.preferences.startFactoryReset
import helium314.keyboard.settings.screens.createKeyboard
import helium314.keyboard.settings.screens.deleteKeyboard
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.newKeyboardFor
import helium314.keyboard.settings.screens.nextNumbered
import helium314.keyboard.settings.screens.renameKeyboard
import helium314.keyboard.settings.screens.withKeyboardName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keyboards have unique ids (owner 2026-10-11: "identifying a list element by it's features feels fundamentally flawed"
 * -> "do IDs now"): a hidden "KeyboardId" in the definition names the keyboard's set ("p<id>/"), its pool and its
 * buttons, Android's subtype id comes from it, and a change of the keyboard keeps it. Test keyboards: en "k1", de "k2".
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardIdsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val filesDirBefore = KeyboardProfiles.filesDir
    private val subtypeIdBefore = KeyboardIds.subtypeIdOf
    private val zip by lazy { File(ctx.cacheDir, "ids-${System.nanoTime()}.zip") }
    private val plain by lazy { SubtypeUtilsAdditional.createDefaultSubtype(Locale.US).toSettingsSubtype() } // (no id)
    private val en by lazy { plain.withId(1) }
    private val de by lazy { available("de-DE").withId(2) }
    private val font = Settings.PREF_KEY_FONT

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
    }
    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        randomIds(); KeyboardIds.subtypeIdOf = subtypeIdBefore
        zip.delete()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = filesDirBefore
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    /** Separate settings, the learned words per keyboard; [keyboards] listed with pools 11, 12, ... the first selected. */
    private fun phone(vararg keyboards: SettingsSubtype) {
        real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putString(LearnedKeyboards.PREF_POOLS, poolsJson(keyboards.withIndex().associate { (i, k) -> k to 11 + i }))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 11 + keyboards.size).putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
        KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, keyboards.toList())
        KeyboardProfiles.refreshImeId(real)
    }

    private fun added() = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!)

    /** A backup with [keyboards] listed and [prefs] (keys as stored), made since the keyboards have ids. */
    private fun backup(keyboards: List<SettingsSubtype>, prefs: Map<String, Any?>) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream((mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(keyboards),
                Settings.PREF_ADDITIONAL_SUBTYPES to SubtypeSettings.createPrefSubtypes(keyboards),
                Settings.PREF_SELECTED_SUBTYPE to keyboards.first().toPref(), KeyboardIdUpgrade.DONE to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) + prefs).mapKeys { it.key as String? }, z)
            z.closeEntry()
        }
    }

    @Test fun `ids are k and 10 base36 characters, all different, and a known one is never given out again`() {
        val ids = (1..300).map { KeyboardIds.random() }
        assertTrue(ids.all { Regex("^k[0-9a-z]{10}$").matches(it) }, ids.first())
        assertEquals(300, ids.toSet().size)
        phone(en, de)
        // (an id the phone knows comes out of the source: another is rolled)
        val rolled = ArrayDeque(listOf("k1", "k2", "k77"))
        KeyboardIds.source = { rolled.removeFirst() }
        assertEquals("k77", KeyboardIds.newId(real))
        randomIds()
        // a keyboard deleted: a keyboard added after it has another id
        deleteKeyboard(ctx, de, null)
        val added = createKeyboard(ctx, newKeyboardFor(ctx, Locale.GERMANY), null, StartSettings.Defaults)
        assertTrue(KeyboardIds.isId(added.keyboardId))
        assertTrue(added.keyboardId !in setOf("k1", "k2"))
        assertEquals(listOf("k1", added.keyboardId), storedEnabled(real).map { it.keyboardId })
    }

    @Test fun `a rename, other keys and other accents keep the id - its set, pool and selection stay, nothing moves`() {
        phone(en, de)
        real.edit().putString(idKey(1, font), "mine").commit()
        val renamed = renameKeyboard(ctx, en, "Work")
        val keys = renamed.withLayout(LayoutType.MAIN, "dvorak")
        SubtypeUtilsAdditional.changeAdditionalSubtype(renamed, keys, ctx)
        val accents = keys.with(ExtraValue.MORE_POPUPS, "all")
        SubtypeUtilsAdditional.changeAdditionalSubtype(keys, accents, ctx)
        assertEquals("k1", accents.keyboardId)
        assertEquals(listOf(accents, de), storedEnabled(real))
        assertEquals(1, added().count { it.keyboardId == "k1" })
        assertEquals(accents, KeyboardProfiles.selectedKeyboard(real))
        assertEquals(accents, SubtypeSettings.getSelectedSubtype(ctx.prefs()).toSettingsSubtype())
        // the same set and pool, nothing left behind
        assertEquals("mine", real.getString(idKey(1, font), null))
        assertEquals(mapOf("k1" to 11, "k2" to 12), storedPools(real))
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real))
        KeyboardProfiles.refreshImeId(real)
        assertEquals("k1", KeyboardProfiles.imeId)
        assertEquals("mine", ProfilePreferences(real) { KeyboardProfiles.mainEditingId(real) }.getString(font, null))
        assertEquals(11, LearnedKeyboards.poolOrNull(real, en)) // (the definition of before finds it too, by its id)
    }

    @Test fun `two keyboards set up alike are two keyboards - each its own set and pool, both listed`() {
        val twin = plain.withId(3)
        assertEquals(en.withoutIds(), twin.withoutIds())
        phone(en, twin)
        assertEquals(2, SubtypeSettings.getEnabledSubtypes().size)
        ProfilePreferences(real) { "k1" }.edit().putString(font, "one").commit()
        ProfilePreferences(real) { "k3" }.edit().putString(font, "two").commit()
        assertEquals("one", ProfilePreferences(real) { "k1" }.getString(font, null))
        assertEquals("two", ProfilePreferences(real) { "k3" }.getString(font, null))
        assertEquals(11, LearnedKeyboards.poolOrNull(real, en))
        assertEquals(12, LearnedKeyboards.poolOrNull(real, twin))
        // one deleted: the other stays as it is
        deleteKeyboard(ctx, twin, null)
        assertEquals(listOf(en), storedEnabled(real))
        assertEquals("one", real.getString(idKey(1, font), null))
        assertNull(real.getString(idKey(3, font), null))
        assertEquals(mapOf("k1" to 11), storedPools(real))
    }

    // (review E1's order: the higher keyboard plain, the lower one with all accents; flipped on, the lower one's accents
    // follow its popup set and it is set up as the higher one: before, the two merged or the alert renamed one)
    @Test fun `the saved-items switch in the E1 order changes the accents - no merge, no rename, each keeps its own`() {
        val a = SettingsSubtype(Locale.US, "").withId(1)
        val b = SettingsSubtype(Locale.US, "").with(ExtraValue.MORE_POPUPS, "all").withId(2)
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(),
            atDefault = setOf(KeyPopupOverrides.ACCENTS))))
        real.edit().putBoolean(SavedItemDefaults.PREF, false)
            .putString(idKey(1, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").putString(idKey(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").commit()
        phone(a, b)
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        val plan = SavedItemDefaults.accentsPlan(real, true)
        val bFollowed = b.without(ExtraValue.MORE_POPUPS)
        assertEquals(listOf(SavedItemDefaults.KeyboardChange(b, bFollowed)), plan.changes)
        SavedItemDefaults.applySwitch(ctx, real, true, plan)
        assertEquals(listOf(a, bFollowed), storedEnabled(real))
        assertEquals(a.withoutIds(), bFollowed.withoutIds()) // (set up alike)
        assertNull(bFollowed.getExtraValueOf(ExtraValue.KEYBOARD_NAME)) // (nothing renamed)
        assertEquals(2, SubtypeSettings.getEnabledSubtypes().size)
        assertEquals(mapOf("k1" to 11, "k2" to 12), storedPools(real))
        assertEquals("Mine", real.getString(idKey(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real))
    }

    @Test fun `Android's subtype id is the keyboard id's - the same across a rename, its own per keyboard, a clash rolls again`() {
        val subtype = en.toAdditionalSubtype()
        val renamed = withKeyboardName(en, "Work").toAdditionalSubtype()
        assertEquals(KeyboardIds.androidSubtypeId("k1"), subtype.hashCode())
        assertTrue(KeyboardIds.androidSubtypeId("k1") > 0)
        assertEquals(subtype, renamed) // (Android: the same keyboard)
        assertNotEquals(subtype.extraValue, renamed.extraValue)
        assertNotEquals(subtype, plain.withId(3).toAdditionalSubtype())
        assertNotEquals(subtype.hashCode(), plain.withId(3).toAdditionalSubtype().hashCode())
        // (owner 2026-10-11, "agree": an id whose Android id another of the phone's keyboards has is not given out)
        phone(en)
        val k1 = subtypeIdBefore("k1")
        KeyboardIds.subtypeIdOf = { if (it == "kclash") k1 else subtypeIdBefore(it) }
        val rolled = ArrayDeque(listOf("kclash", "kfree"))
        KeyboardIds.source = { rolled.removeFirst() }
        assertEquals("kfree", KeyboardIds.newId(real))
    }

    @Test fun `a copy gets a new id that came from the original's, a new keyboard none`() {
        phone(en)
        val copy = createKeyboard(ctx, nextNumbered(ctx, en), en, StartSettings.From(en))
        assertTrue(KeyboardIds.isId(copy.keyboardId))
        assertNotEquals("k1", copy.keyboardId)
        assertEquals("k1", copy.keyboardFrom)
        assertEquals("2", copy.getExtraValueOf(ExtraValue.KEYBOARD_COPY)) // (the naming rule: "English (US) (2)")
        val added = createKeyboard(ctx, newKeyboardFor(ctx, Locale.GERMANY), null, StartSettings.Defaults)
        assertTrue(KeyboardIds.isId(added.keyboardId))
        assertNull(added.keyboardFrom)
        assertEquals(listOf(en, copy, added), storedEnabled(real))
        assertEquals(3, storedPools(real).size)
    }

    @Test fun `a restore merged into a phone keyboard keeps the phone keyboard's id`() {
        phone(en)
        real.edit().putBoolean(idKey(1, Settings.PREF_SHOW_NUMBER_ROW), false).commit()
        val fromBackup = plain.with(ExtraValue.SECONDARY_LOCALES, "de").withId(9)
        backup(listOf(fromBackup), mapOf(idKey(9, Settings.PREF_SHOW_NUMBER_ROW) to true))
        restoreKeyboardsFrom(ctx, zip, listOf(fromBackup), withSettings = true, decisions = mapOf(fromBackup to RestoreDecision.Merge(en)))
        val merged = storedEnabled(real).single()
        assertEquals("k1", merged.keyboardId)
        assertNull(merged.keyboardFrom)
        assertEquals("de", merged.getExtraValueOf(ExtraValue.SECONDARY_LOCALES))
        assertEquals(true, real.getBoolean(idKey(1, Settings.PREF_SHOW_NUMBER_ROW), false))
        assertFalse(real.all.keys.any { it.startsWith("pk9/") })
        assertEquals(mapOf("k1" to 11), storedPools(real))
    }

    @Test fun `kept separate with the phone keyboard's id - the backup's keeps it, the phone's gets a new one with its set and pool`() {
        phone(en)
        real.edit().putString(idKey(1, font), "phone").putString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.app", en.toPref()).commit()
        val fromBackup = plain.with(ExtraValue.MORE_POPUPS, "all").withId(1) // (the same keyboard, changed since)
        backup(listOf(fromBackup), mapOf(idKey(1, font) to "backup"))
        restoreKeyboardsFrom(ctx, zip, listOf(fromBackup), withSettings = true, decisions = mapOf(fromBackup to RestoreDecision.Separate()))
        val (phoneNow, restored) = storedEnabled(real)
        val newId = assertNotNull(phoneNow.keyboardId)
        assertNotEquals("k1", newId)
        assertEquals("k1", phoneNow.keyboardFrom)
        assertEquals(en.withoutIds(), phoneNow.withoutIds())
        assertEquals(fromBackup, restored) // (the backup's id: "backup's ID should take priority")
        // the phone keyboard's set, pool, selection and apps went with it to its new id; the restored one has its own
        assertEquals("phone", real.getString(KeyboardProfiles.ownKey(newId, font), null))
        assertEquals("backup", real.getString(idKey(1, font), null))
        assertEquals(11, storedPools(real)[newId])
        assertNotEquals(11, storedPools(real)["k1"])
        assertEquals(phoneNow, KeyboardProfiles.selectedKeyboard(real))
        assertEquals(newId, real.getString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.app", null)!!.toSettingsSubtype().keyboardId)
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real))
    }

    @Test fun `the first question marks the phone keyboard with the backup keyboard's id, nothing picked`() {
        val twin = plain.withId(3)
        phone(twin, en)
        val fromBackup = plain.with(ExtraValue.MORE_POPUPS, "all").withId(1)
        backup(listOf(fromBackup), emptyMap())
        val plan = restorePlanFrom(ctx, zip)
        val question = MergeQuestion(plan, fromBackup, listOf(fromBackup), emptyMap())
        assertEquals(listOf(twin, en), question.options)
        assertTrue(question.isSameKeyboard(en))
        assertFalse(question.isSameKeyboard(twin))
        assertNull(question.picked) // (a hint only, owner 2026-10-11)
        assertEquals("(same keyboard as in the backup)", ctx.getString(R.string.restore_same_keyboard_hint))
    }

    @Test fun `what differs never lists the id or where it came from`() {
        assertEquals(emptyList(), RestoreMatch.definitionDifferences(en, plain.withId(9)))
        assertEquals(emptyList(), RestoreMatch.definitionDifferences(en, plain.withKeyboardId("k9", "k1")))
        assertEquals(listOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)),
            RestoreMatch.definitionDifferences(en, plain.with(ExtraValue.MORE_POPUPS, "all").withId(9)).map { it.part })
    }

    @Test fun `the id is never shown - names and descriptions are those of the keyboard without it`() {
        val withFrom = plain.withKeyboardId("k5abc", "k1")
        assertEquals(keyboardName(plain, ctx), keyboardName(en, ctx))
        assertEquals(keyboardName(plain, ctx), keyboardName(withFrom, ctx))
        assertFalse(keyboardName(withFrom, ctx).contains("k5abc") || keyboardName(withFrom, ctx).contains("k1"))
        assertEquals("Work", keyboardName(withKeyboardName(en, "Work"), ctx))
        assertEquals(plain.toAdditionalSubtype().mainLayoutName(), withFrom.toAdditionalSubtype().mainLayoutName())
    }

    @Test fun `the one-time upgrade gives every keyboard an id and rewrites sets, lists, pools, buttons and the apps' memory`() {
        val deOld = available("de-DE")
        val off = plain.with(ExtraValue.KEYBOARD_NAME, "Old")
        val map = HashMap<String, Any?>(mapOf(
            Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(plain, deOld)),
            Settings.PREF_SELECTED_SUBTYPE to deOld.toPref(),
            KeyboardIdUpgrade.OLD_IDS to JSONObject(mapOf(plain.toPref() to 1, deOld.toPref() to 2, off.toPref() to 3)).toString(),
            "keyboard_profile_next_id" to 4,
            "p1/$font" to "a", "p2/$font" to "b", "p3/$font" to "c", "p7/$font" to "orphan",
            LearnedKeyboards.PREF_POOLS to JSONObject(mapOf(plain.toPref() to 11, deOld.toPref() to 12, off.toPref() to 13)).toString(),
            LearnedKeyboards.PREF_DONE to true,
            LearnedKeyboards.PREF_USE_OTHERS_LEARNED to JSONArray(listOf(plain.toPref())).toString(),
            Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.a" to plain.toPref(),
            Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.gone" to plain.with(ExtraValue.KEYBOARD_NAME, "Deleted").toPref(),
        ))
        val renames = assertNotNull(KeyboardIdUpgrade.upgrade(map))
        val enabled = SubtypeSettings.createSettingsSubtypes(map[Settings.PREF_ENABLED_SUBTYPES] as String)
        assertEquals(listOf(plain, deOld), enabled.map { it.withoutIds() })
        val (id1, id2) = enabled.map { assertNotNull(it.keyboardId) }
        assertTrue(Regex("^k[0-9a-z]{10}$").matches(id1) && id1 != id2)
        val added = SubtypeSettings.createSettingsSubtypes(map[Settings.PREF_ADDITIONAL_SUBTYPES] as String)
        val offNow = added.single { it.withoutIds() == off }
        val id3 = assertNotNull(offNow.keyboardId)
        assertEquals(enabled.toSet() + offNow, added.toSet()) // (the listed and the switched-off ones, with their ids)
        assertEquals(enabled[1].toPref(), map[Settings.PREF_SELECTED_SUBTYPE])
        assertEquals(mapOf(1 to id1, 2 to id2, 3 to id3), renames)
        assertEquals("a", map["p$id1/$font"]); assertEquals("b", map["p$id2/$font"]); assertEquals("c", map["p$id3/$font"])
        assertFalse(map.keys.any { Regex("^p\\d+/").containsMatchIn(it) }) // (the orphan set gone too)
        assertFalse(KeyboardIdUpgrade.OLD_IDS in map || "keyboard_profile_next_id" in map)
        assertEquals(mapOf(id1 to 11, id2 to 12, id3 to 13),
            JSONObject(map[LearnedKeyboards.PREF_POOLS] as String).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } })
        assertEquals(JSONArray(listOf(id1)).toString(), map[LearnedKeyboards.PREF_USE_OTHERS_LEARNED])
        assertEquals(enabled[0].toPref(), map[Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.a"])
        assertFalse(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.gone" in map)
        assertEquals(true, map[KeyboardIdUpgrade.DONE])
        // once
        val after = HashMap(map)
        assertNull(KeyboardIdUpgrade.upgrade(map))
        assertEquals(after, map)
    }

    @Test fun `the upgrade on the phone - sets, pictures and pools by id, the switched-off one listed as such, once`() {
        val dir = DeviceProtectedUtils.getFilesDir(ctx)
        KeyboardProfiles.filesDir = dir
        val off = plain.with(ExtraValue.KEYBOARD_NAME, "Old")
        // (as a phone had it before: definitions without ids, the id map; the added list as the first start left it)
        real.edit().remove(KeyboardIdUpgrade.DONE).putBoolean("separate_settings_per_keyboard", true)
            .putString(Settings.PREF_ENABLED_SUBTYPES, plain.toPref()).putString(Settings.PREF_SELECTED_SUBTYPE, plain.toPref())
            .putString(KeyboardIdUpgrade.OLD_IDS, JSONObject(mapOf(plain.toPref() to 1, off.toPref() to 3)).toString())
            .putString("p1/$font", "a").putString("p3/$font", "c")
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(plain.toPref() to 11, off.toPref() to 13)).toString())
            .putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
        File(dir, "custom_background_image_p1").writeText("picture")
        assertTrue(KeyboardIdUpgrade.upgrade(real, dir))
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        val kb = storedEnabled(real).single()
        val id = assertNotNull(kb.keyboardId)
        assertEquals(plain, kb.withoutIds())
        assertEquals(kb, KeyboardProfiles.selectedKeyboard(real))
        assertEquals("a", real.getString(KeyboardProfiles.ownKey(id, font), null))
        assertEquals("picture", File(dir, "custom_background_image_p$id").readText())
        assertFalse(File(dir, "custom_background_image_p1").exists())
        assertEquals(11, storedPools(real)[id])
        val switchedOff = SwitchedOffKeyboards.list(real).single()
        assertEquals(off, switchedOff.withoutIds())
        assertEquals("c", real.getString(KeyboardProfiles.ownKey(switchedOff.keyboardId!!, font), null))
        val before = real.all
        assertFalse(KeyboardIdUpgrade.upgrade(real, dir))
        assertEquals(before, real.all)
        File(dir, "custom_background_image_p$id").delete()
    }

    @Test fun `an old backup's keyboards get ids when it is read, and their sets by its own old map`() {
        phone(de)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(Settings.PREF_ENABLED_SUBTYPES to plain.toPref(), Settings.PREF_SELECTED_SUBTYPE to plain.toPref(),
                KeyboardIdUpgrade.OLD_IDS to JSONObject(mapOf(plain.toPref() to 1)).toString(), "p1/$font" to "x",
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
        }
        // (each read gives fresh ids: the same ones here, as the dialog reads the backup once)
        countingIds(500)
        val incoming = restorePlanFrom(ctx, zip).incoming.keys.single()
        assertEquals("k500", incoming.keyboardId)
        assertEquals(plain, incoming.withoutIds())
        countingIds(500)
        restoreKeyboardsFrom(ctx, zip, listOf(incoming), withSettings = true) // (no keyboard of its language: a new one)
        assertEquals(listOf(de, incoming), storedEnabled(real))
        assertEquals("x", real.getString(idKey(500, font), null))
    }

    /** The reset from the dialog's button, run to its end. */
    private fun reset(keep: ResetKeep) {
        var done = false
        startFactoryReset(ctx, keep) { done = true }
        val until = System.currentTimeMillis() + 10_000
        while (!done && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        assertTrue(done, "the reset never ran")
    }

    @Test fun `a factory reset keeping the keyboards keeps their ids, without them the default keyboard gets a new one`() {
        phone(en, de)
        reset(ResetKeep(keyboards = true))
        assertEquals(listOf(en, de), storedEnabled(real))
        reset(ResetKeep())
        val fresh = storedEnabled(real).single()
        assertTrue(KeyboardIds.isId(fresh.keyboardId))
        assertTrue(fresh.keyboardId !in setOf("k1", "k2"))
        assertNull(fresh.keyboardFrom)
        assertTrue(added().any { it == fresh })
    }

    @Test fun `delete finds the keyboard by its id - a definition of before deletes the keyboard as it is now`() {
        phone(de, en)
        real.edit().putString(idKey(1, font), "mine").putString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.app", en.toPref())
            .putString(LearnedKeyboards.PREF_USE_OTHERS_LEARNED, JSONArray(listOf("k1", "k2")).toString()).commit()
        renameKeyboard(ctx, en, "Work")
        deleteKeyboard(ctx, en, null) // (as a screen opened before the rename holds it)
        assertEquals(listOf(de), storedEnabled(real))
        assertFalse(added().any { it.keyboardId == "k1" })
        assertFalse(real.all.keys.any { it.startsWith("pk1/") })
        assertEquals(mapOf("k2" to 11), storedPools(real))
        assertEquals(setOf("k2"), storedButton(real, LearnedKeyboards.PREF_USE_OTHERS_LEARNED))
        assertNull(real.getString(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX + "org.app", null))
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real))
    }
}

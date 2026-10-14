// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.profileIds
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.restoreDifferencesFrom
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * (review 2026-10-11 R3; owner 2026-10-11: "Screen 2 should handle unsaved items as named yes") "What to merge" sees an
 * unsaved key layout ("~N") as the restore would bring it in, compared by its keys; the real run changes the keys only
 * when ticked, and an unsaved file no keyboard uses afterwards goes. Menus are compared with the saved items named as the
 * merge would name them ("Sunset" coming in as "Sunset 2").
 */
@RunWith(RobolectricTestRunner::class)
class ReReview2R3Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val type = LayoutType.NUMBER_ROW
    private val zip by lazy { File(ctx.cacheDir, "r3-${System.nanoTime()}.zip") }
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private val folder get() = file("x").parentFile!!
    private lateinit var hindi: SettingsSubtype
    private lateinit var english: SettingsSubtype

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        folder.deleteRecursively(); folder.mkdirs(); LayoutUtilsCustom.onLayoutFileChanged()
        hindi = available("hi"); english = available("en-US")
    }
    @After fun tearDown() {
        zip.delete(); folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun unsaved(keys: String) = LayoutUtilsCustom.makePrivateLayout(keys, type, "", ctx)
    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }

    /** The phone: [keyboards] (separate settings on, gaps 1), plus [off] switched off; the backup: [inBackup] with the
     *  number row files [files] (name to keys), gaps 3. */
    private fun phoneAndBackup(keyboards: List<SettingsSubtype>, inBackup: SettingsSubtype, files: Map<String, String>,
                               off: SettingsSubtype? = null) {
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards + listOfNotNull(off)))
            .putBoolean("separate_settings_per_keyboard", true).commit()
        useKeyboards(ctx, real, *keyboards.toTypedArray())
        if (off != null) profileIds(real, (keyboards + off).withIndex().associate { it.value to it.index + 1 }, keyboards.size + 2)
        else keyboards.forEach { KeyboardProfiles.idFor(real, it) }
        // (owner 2026-10-11: no separate common set) each keyboard's own gap, here and in the backup
        real.edit().apply { for (k in keyboards) putFloat(KeyboardProfiles.ownKey(KeyboardProfiles.idFor(real, k), Settings.PREF_KEY_HORIZONTAL_GAP), 1f) }.commit()
        val list = SubtypeSettings.createPrefSubtypes(listOf(inBackup))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                "keyboard_profile_ids" to JSONObject().put(inBackup.toPref(), 1).toString(), KeyboardProfiles.NO_COMMON_SET_DONE to true,
                KeyboardProfiles.ownKey(1, Settings.PREF_KEY_HORIZONTAL_GAP) to 3f,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            for ((name, keys) in files) {
                z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$name")); z.write(keys.toByteArray()); z.closeEntry()
            }
        }
    }

    private fun numberRowRow(differences: List<RestoreMatch.Difference>) =
        differences.filterIsInstance<RestoreMatch.Difference.Definition>().singleOrNull { it.part == RestoreMatch.Part.Layout(type) }

    @Test fun `(a) other keys under the same unsaved name - a number row row, unticked the phone keeps its keys`() {
        val a = unsaved("A")
        val phone = hindi.withLayout(type, a).toPref().toSettingsSubtype()
        phoneAndBackup(listOf(phone), phone, mapOf(a to "B"))
        val differences = restoreDifferencesFrom(ctx, zip, phone, phone)
        val row = numberRowRow(differences)
        assertTrue(row != null, "no number row row: $differences")
        val plan = restorePlanFrom(ctx, zip)
        val decision = plan.mergeDecision(phone, differences, differences - row, learnedRow = false, learnedTicked = false, name = null)
        restoreKeyboardsFrom(ctx, zip, listOf(phone), withSettings = true, decisions = mapOf(phone to decision))
        val after = enabled().single()
        assertEquals(a, after.layoutName(type))
        assertEquals("A", file(a).readText())
        assertEquals(3f, KeyboardProfiles.effectiveSettingsIn(real.all, after)[Settings.PREF_KEY_HORIZONTAL_GAP])
        assertEquals(setOf(a), folder.list()!!.toSet()) // (the backup's keys weren't brought for nothing)
    }

    @Test fun `(a) ticked the keyboard gets the backup's keys, and the phone's unused unsaved file goes, a switched-off one's stays`() {
        val a = unsaved("A"); val c = unsaved("C")
        val phone = hindi.withLayout(type, a).toPref().toSettingsSubtype()
        val off = english.withLayout(type, c).toPref().toSettingsSubtype()
        phoneAndBackup(listOf(phone), phone, mapOf(a to "B"), off = off)
        val differences = restoreDifferencesFrom(ctx, zip, phone, phone)
        assertTrue(numberRowRow(differences) != null, "no number row row: $differences")
        val plan = restorePlanFrom(ctx, zip)
        val decision = plan.mergeDecision(phone, differences, differences, learnedRow = false, learnedTicked = false, name = null)
        restoreKeyboardsFrom(ctx, zip, listOf(phone), withSettings = true, decisions = mapOf(phone to decision))
        val now = enabled().single().layoutName(type)!!
        assertTrue(LayoutUtilsCustom.isPrivateLayout(now) && now != a, now)
        assertEquals("B", file(now).readText())
        assertFalse(file(a).exists(), "the phone's old unsaved file is left over")
        assertEquals("C", file(c).readText())
        assertEquals(setOf(now, c), folder.list()!!.toSet())
    }

    @Test fun `(b) the same keys under another unsaved name - no row`() {
        val a = unsaved("A")
        val phone = hindi.withLayout(type, a).toPref().toSettingsSubtype()
        val other = a.replace("~", "~z") // (an unsaved name the phone doesn't have)
        val backup = hindi.withLayout(type, other).toPref().toSettingsSubtype()
        phoneAndBackup(listOf(phone), backup, mapOf(other to "A"))
        val differences = restoreDifferencesFrom(ctx, zip, backup, phone)
        assertEquals(null, numberRowRow(differences), "$differences")
        assertTrue(differences.isNotEmpty()) // (the gaps differ)
        // and the same keys under the same name: no row either
        phoneAndBackup(listOf(phone), phone, mapOf(a to "A"))
        assertEquals(null, numberRowRow(restoreDifferencesFrom(ctx, zip, phone, phone)))
    }

    @Test fun `(d) an unsaved file only a restored keyboard's settings name stays`() {
        val p = "custom.~zzp." // (an unsaved name, the backup's default for the number row)
        useKeyboards(ctx, real, english)
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        val list = SubtypeSettings.createPrefSubtypes(listOf(hindi))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            // (hindi's own set: no separate common set, owner 2026-10-11)
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list,
                "keyboard_profile_ids" to JSONObject().put(hindi.toPref(), 1).toString(), KeyboardProfiles.NO_COMMON_SET_DONE to true,
                KeyboardProfiles.ownKey(1, Settings.PREF_LAYOUT_PREFIX + type.name) to p,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$p")); z.write("P".toByteArray()); z.closeEntry()
        }
        restoreKeyboardsFrom(ctx, zip, listOf(hindi), withSettings = true)
        assertEquals(p, KeyboardProfiles.effectiveSettingsIn(real.all, hindi)[Settings.PREF_LAYOUT_PREFIX + type.name])
        assertEquals("P", file(p).takeIf { it.exists() }?.readText())
    }

    /** The phone: [english] on theme [phoneTheme] (gap [phoneGap]); the backup: [english] on [backupTheme] (gap [backupGap]). */
    private fun themes(phoneTheme: String, phoneGap: Float, backupTheme: String, backupGap: Float) {
        useKeyboards(ctx, real, english)
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look(phoneTheme, mapOf(Settings.PREF_KEY_HORIZONTAL_GAP to phoneGap))), now = true)
        // (english's own choice, here and in the backup: no separate common set, owner 2026-10-11)
        real.edit().putString(KeyboardProfiles.ownKey(KeyboardProfiles.idFor(real, english), AppearanceLooks.PREF_SELECTED), phoneTheme).commit()
        val look = JSONObject().put("name", backupTheme).put("values", JSONObject().put(Settings.PREF_KEY_HORIZONTAL_GAP, JSONObject().put("f", backupGap.toDouble())))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to english.toPref(), Settings.PREF_ADDITIONAL_SUBTYPES to "",
                "keyboard_profile_ids" to JSONObject().put(english.toPref(), 1).toString(), KeyboardProfiles.NO_COMMON_SET_DONE to true,
                AppearanceLooks.PREF to JSONArray(listOf(look)).toString(), KeyboardProfiles.ownKey(1, AppearanceLooks.PREF_SELECTED) to backupTheme,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
        }
    }
    private fun appearanceRow(differences: List<RestoreMatch.Difference>) =
        differences.any { it == RestoreMatch.Difference.Menu(KeyboardProfiles.Group.APPEARANCE) }

    @Test fun `(c) the backup's Sunset with other content comes in as Sunset 2 - Appearance differs`() {
        themes("Sunset", 1f, "Sunset", 3f)
        val before = real.all
        assertTrue(appearanceRow(restoreDifferencesFrom(ctx, zip, english, english)))
        assertEquals(before, real.all) // (nothing written)
        assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name })
    }

    @Test fun `(c) the same content under another name is the phone's - no Appearance row`() {
        themes("Sunset", 1f, "Dawn", 1f)
        assertFalse(appearanceRow(restoreDifferencesFrom(ctx, zip, english, english)))
    }
}

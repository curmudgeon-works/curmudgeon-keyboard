// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
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
 * Restoring one keyboard writes only that keyboard's own set, whether a menu is shared, per keyboard or hidden; the
 * shared set and the other keyboards' sets stay as they are (owner 2026-10-11: "restore should only apply to that
 * keyboard's settings. Regardless of whether it is shared with others or hidden or separate").
 * (owner 2026-10-11: keyboards have unique ids) The phone's English is "k1", its Hindi "k2"; the backup's (another
 * phone's) "k6" and "k7". A merge keeps the phone keyboard's id.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreOnlyThatKeyboardTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "only-that-keyboard-${System.nanoTime()}.zip") }
    private val appearance = KeyboardProfiles.Group.APPEARANCE
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val vgap = Settings.PREF_KEY_VERTICAL_GAP
    private val autoCap = Settings.PREF_AUTO_CAP // (Text correction: per keyboard)
    private val label = CustomLabels.Kind.THEME.key
    private lateinit var en: SettingsSubtype
    private lateinit var hi: SettingsSubtype
    private lateinit var backupEn: SettingsSubtype
    private lateinit var backupHi: SettingsSubtype
    private fun view(n: Int) = ProfilePreferences(real) { kid(n) }
    private fun own(n: Int, key: String) = idKey(n, key)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        en = available("en-US").withId(1); hi = available("hi").withId(2)
        backupEn = available("en-US").withId(6); backupHi = available("hi").withId(7)
        // the phone: English (k1) and Hindi (k2), separate settings, Appearance shared (gap 2: English's, the first
        // keyboard's; owner 2026-10-11: no separate common set; the first keyboard's own settings are the common ones),
        // Hindi's own gap hidden
        useKeyboards(ctx, real, en, hi)
        real.edit().putBoolean("separate_settings_per_keyboard", true).putBoolean(appearance.prefKey, true)
            .putFloat(own(1, gap), 2f).putFloat(own(2, gap), 4f)
            .putBoolean(own(1, autoCap), false).putBoolean(own(2, autoCap), false).commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** The backup: English and Hindi ("k7"), Appearance per keyboard there, Hindi with gap 5 and auto-caps on. */
    private fun backup(extra: Map<String, Any?> = emptyMap()) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(backupEn, backupHi)),
                Settings.PREF_ADDITIONAL_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(backupEn, backupHi)),
                Settings.PREF_SELECTED_SUBTYPE to backupEn.toPref(), "separate_settings_per_keyboard" to true, KeyboardIdUpgrade.DONE to true,
                appearance.prefKey to false, gap to 3f, own(6, gap) to 9f, own(7, gap) to 5f, own(7, autoCap) to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) + extra, z)
            z.closeEntry()
        }
    }

    /** Everything outside Hindi's own set that is a keyboard's setting: the shared set and the other keyboards' sets. */
    private fun outsideHindi() = real.all.filterKeys { stored ->
        KeyboardProfiles.splitOwnKey(stored)?.let { it.first != kid(2) } ?: !KeyboardProfiles.isAppWide(stored) }

    // (owner 2026-10-11: restore matches by language and asks: Hindi answered "merge into the phone's Hindi", all ticked)
    private val whole get() = mapOf(backupHi to helium314.keyboard.settings.preferences.RestoreDecision.Merge(hi))

    private fun unshare() = KeyboardProfiles.setGroupShared(real, appearance, false)

    @Test fun `a shared menu's values go to the keyboard's own hidden set, not the shared one`() {
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(backupHi), withSettings = true, decisions = whole)
        assertEquals(before, outsideHindi())
        assertEquals(2f, real.getFloat(own(1, gap), 0f), "the shared gap changed")
        assertEquals(2f, view(1).getFloat(gap, 0f)); assertEquals(2f, view(2).getFloat(gap, 0f)) // (shared, for all)
        assertEquals(5f, real.getFloat(own(2, gap), 0f), "Hindi's own gap is not the backup's")
        assertTrue(view(2).getBoolean(autoCap, false)) // (a per-keyboard menu, as before)
        unshare()
        assertEquals(5f, view(2).getFloat(gap, 0f))
        assertEquals(2f, view(1).getFloat(gap, 0f))
    }

    @Test fun `a hidden own value the backup's keyboard lacks goes, as the whole set is replaced, and nothing else changes`() {
        real.edit().putFloat(own(2, vgap), 8f).putFloat(own(1, vgap), 6f).commit()
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(backupHi), withSettings = true, decisions = whole)
        assertEquals(before, outsideHindi())
        assertFalse(real.contains(own(2, vgap))) // (the backup's Hindi read the default there)
        assertEquals(6f, view(2).getFloat(vgap, 0f)) // (English's while shared)

        unshare()
        assertEquals(0f, view(2).getFloat(vgap, 0f))
        assertEquals(6f, view(1).getFloat(vgap, 0f))
    }

    @Test fun `choosing a shared menu replaces only the keyboard's own values there`() {
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(backupHi), withSettings = true, decisions = mapOf(backupHi to
            helium314.keyboard.settings.preferences.RestoreDecision.Merge(hi, parts = emptySet(), menus = setOf(appearance))))
        assertEquals(before, outsideHindi())
        assertEquals(5f, real.getFloat(own(2, gap), 0f))
        assertFalse(view(2).getBoolean(autoCap, true)) // (Text correction not ticked: the phone's)
    }

    @Test fun `choosing another menu keeps the keyboard's hidden own values of a shared menu`() {
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(backupHi), withSettings = true, decisions = mapOf(backupHi to
            helium314.keyboard.settings.preferences.RestoreDecision.Merge(hi, parts = emptySet(), menus = setOf(KeyboardProfiles.Group.TEXT_CORRECTION))))
        assertEquals(before, outsideHindi())
        assertTrue(view(2).getBoolean(autoCap, false))
        assertEquals(4f, real.getFloat(own(2, gap), 0f), "Hindi's hidden gap went")
        unshare()
        assertEquals(4f, view(2).getFloat(gap, 0f))
    }

    @Test fun `with one set for all, the restore writes nothing of the shared set or the other keyboards' sets`() {
        real.edit().putBoolean("separate_settings_per_keyboard", false).remove(own(2, gap)).remove(own(2, autoCap)).commit()
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(backupHi), withSettings = true, decisions = whole)
        assertEquals(before, outsideHindi())
        // (owner 2026-10-11: R7, a restore never turns separate settings on)
        assertFalse(KeyboardProfiles.isSeparate(real))
        assertEquals(5f, real.getFloat(own(2, gap), 0f))
        assertTrue(real.getBoolean(own(2, autoCap), false)) // (its own set, read once separate settings are on)
    }

    // (owner 2026-10-12: replaced when ticked; owner 2026-10-11 had the backup win the name; before, with copies off the restore wrote a Custom label for a
    // theme not brought in, and this checked it landed in Hindi's own set; now no label is written, the choice is)
    @Test fun `the restored keyboard's theme choice of a shared menu lands in its own set, the Sunset clash ticked or not`() {
        val sunset = JSONObject().put("name", "Sunset").put("values", JSONObject().put(gap, JSONObject().put("f", 5.0)))
        for (ticked in listOf(true, false)) {
            AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to 1f))), now = true)
            backup(mapOf(AppearanceLooks.PREF to JSONArray(listOf(sunset)).toString(), own(7, AppearanceLooks.PREF_SELECTED) to "Sunset"))
            val before = outsideHindi()
            // (ticked: answered Replace; Keep both is the suggested answer since owner 2026-10-12 option D, A2)
            restoreKeyboardsFrom(ctx, zip, listOf(backupHi), withSettings = true, decisions = whole, names =
                helium314.keyboard.settings.preferences.SavedItemsMerge.Choices(if (ticked) emptySet() else
                    setOf(helium314.keyboard.settings.preferences.SavedItemsMerge.Kind.THEMES to "Sunset"),
                    mapOf((helium314.keyboard.settings.preferences.SavedItemsMerge.Kind.THEMES to "Sunset") to
                        helium314.keyboard.settings.preferences.SavedItemsMerge.Answer.Replace("Sunset"))))
            assertEquals(before, outsideHindi())
            assertFalse(real.contains(own(1, AppearanceLooks.PREF_SELECTED)), "the shared set got Hindi's choice")
            assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
            assertFalse(real.contains(own(2, label)))
            // (owner 2026-10-12: ticked, replaced: the device's Sunset is the backup's)
            assertEquals(listOf("Sunset"), AppearanceLooks.load(real).map { it.name })
            assertEquals(if (ticked) 5f else 1f, AppearanceLooks.load(real).last().values[gap])
        }
    }
}

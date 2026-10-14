// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.profileIds
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.preferences.RestoreMatch
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
    private fun view(id: Int) = ProfilePreferences(real) { id }
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        en = available("en-US"); hi = available("hi")
        // the phone: English (1) and Hindi (2), separate settings, Appearance shared (gap 2), each keyboard's own gap hidden
        useKeyboards(ctx, real, en, hi)
        profileIds(real, mapOf(en to 1, hi to 2), 3)
        real.edit().putBoolean("separate_settings_per_keyboard", true).putBoolean(appearance.prefKey, true)
            .putFloat(gap, 2f).putFloat(own(1, gap), 1f).putFloat(own(2, gap), 4f)
            .putBoolean(own(1, autoCap), false).putBoolean(own(2, autoCap), false).commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** The backup: English and Hindi (its set 7), Appearance per keyboard there, Hindi with gap 5 and auto-caps on. */
    private fun backup(extra: Map<String, Any?> = emptyMap()) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(en, hi)),
                Settings.PREF_SELECTED_SUBTYPE to en.toPref(), "separate_settings_per_keyboard" to true,
                "keyboard_profile_ids" to JSONObject().put(en.toPref(), 6).put(hi.toPref(), 7).toString(), "keyboard_profile_next_id" to 8,
                appearance.prefKey to false, gap to 3f, own(6, gap) to 9f, own(7, gap) to 5f, own(7, autoCap) to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) + extra, z)
            z.closeEntry()
        }
    }

    /** Everything outside Hindi's own set that is a keyboard's setting: the shared set and the other keyboards' sets. */
    private fun outsideHindi() = real.all.filterKeys { stored ->
        KeyboardProfiles.splitOwnKey(stored)?.let { it.first != 2 } ?: !KeyboardProfiles.isAppWide(stored) }

    private fun unshare() = KeyboardProfiles.setGroupShared(real, appearance, false, KeyboardProfiles.SHARED)

    @Test fun `a shared menu's values go to the keyboard's own hidden set, not the shared one`() {
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true)
        assertEquals(before, outsideHindi())
        assertEquals(2f, real.getFloat(gap, 0f), "the shared gap changed")
        assertEquals(2f, view(1).getFloat(gap, 0f)); assertEquals(2f, view(2).getFloat(gap, 0f)) // (shared, for all)
        assertEquals(5f, real.getFloat(own(2, gap), 0f), "Hindi's own gap is not the backup's")
        assertTrue(view(2).getBoolean(autoCap, false)) // (a per-keyboard menu, as before)
        unshare()
        assertEquals(5f, view(2).getFloat(gap, 0f))
        assertEquals(1f, view(1).getFloat(gap, 0f))
    }

    @Test fun `a hidden own value the backup's keyboard lacks goes, as the whole set is replaced, and nothing else changes`() {
        real.edit().putFloat(vgap, 7f).putFloat(own(2, vgap), 8f).putFloat(own(1, vgap), 6f).commit()
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true)
        assertEquals(before, outsideHindi())
        assertFalse(real.contains(own(2, vgap))) // (the backup's Hindi read the default there: a mark)
        assertTrue(real.contains(own(2, KeyboardProfiles.TOMBSTONE + vgap)))
        assertEquals(7f, view(2).getFloat(vgap, 0f)) // (the mark is not read while shared)
        unshare()
        assertEquals(0f, view(2).getFloat(vgap, 0f))
        assertEquals(6f, view(1).getFloat(vgap, 0f))
    }

    @Test fun `choosing a shared menu replaces only the keyboard's own values there`() {
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true, replaces = mapOf(hi to
            RestoreMatch.Replace(hi, parts = emptySet(), menus = setOf(appearance))))
        assertEquals(before, outsideHindi())
        assertEquals(5f, real.getFloat(own(2, gap), 0f))
        assertFalse(view(2).getBoolean(autoCap, true)) // (Text correction not ticked: the phone's)
    }

    @Test fun `choosing another menu keeps the keyboard's hidden own values of a shared menu`() {
        backup()
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true, replaces = mapOf(hi to
            RestoreMatch.Replace(hi, parts = emptySet(), menus = setOf(KeyboardProfiles.Group.TEXT_CORRECTION))))
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
        restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true)
        assertEquals(before, outsideHindi())
        // (it turns separate settings on, the pending review item R7: unchanged here)
        assertTrue(KeyboardProfiles.isSeparate(real))
        assertEquals(5f, real.getFloat(own(2, gap), 0f))
        assertTrue(view(2).getBoolean(autoCap, false))
    }

    @Test fun `the restored keyboard's Custom label of a shared menu lands in its own set`() {
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(gap to 1f))), now = true)
        val sunset = JSONObject().put("name", "Sunset").put("values", JSONObject().put(gap, JSONObject().put("f", 5.0)))
        backup(mapOf(AppearanceLooks.PREF to JSONArray(listOf(sunset)).toString(), own(7, AppearanceLooks.PREF_SELECTED) to "Sunset"))
        val before = outsideHindi()
        restoreKeyboardsFrom(ctx, zip, listOf(hi), withSettings = true, asCopies = false)
        assertEquals(before, outsideHindi())
        assertFalse(real.contains(label), "the shared set got Hindi's label")
        assertTrue(real.getInt(own(2, label), 0) > 0)
        unshare()
        assertTrue(CustomLabels.has(view(2), CustomLabels.Kind.THEME))
        assertFalse(CustomLabels.has(view(1), CustomLabels.Kind.THEME))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
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
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Restoring some keyboards (review C4, owner "yes"): a restored keyboard's kept palette copies ("~custom:N") get the
 * next free number on the phone when the phone already uses that number for another copy, so two different palettes
 * never share a name. Its "Custom" labels come in as they are (owner 2026-10-11: labels are just "Custom", no number; only kept palettes are numbered).
 */
@RunWith(RobolectricTestRunner::class)
class PartialRestoreCustomNumbersTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val phoneKeyboard = SettingsSubtype(Locale.GERMANY, "")
    private val restored = SettingsSubtype(Locale.US, "")
    private val zip by lazy { File(ctx.cacheDir, "partial-custom-${System.nanoTime()}.zip") }
    private val hidden1 = KeyboardTheme.HIDDEN_PALETTE + "1"
    private val hidden2 = KeyboardTheme.HIDDEN_PALETTE + "2"

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    /** The backup: [restored] with a set of its own (id 1) holding [own], plus [extra] backup-wide entries. */
    private fun backup(own: Map<String, Any?>, extra: Map<String?, Any?> = emptyMap()) {
        val values = HashMap<String?, Any?>()
        values[Settings.PREF_ENABLED_SUBTYPES] = restored.toPref()
        values["separate_settings_per_keyboard"] = true
        values["keyboard_profile_ids"] = JSONObject().put(restored.toPref(), 1).toString()
        values[helium314.keyboard.latin.PICKED_ONLY_DONE] = true
        values[helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE] = true
        values[helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE] = true
        for ((key, value) in own) values[KeyboardProfiles.ownKey(1, key)] = value
        values.putAll(extra)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(values, z)
            z.closeEntry()
        }
    }

    /** The phone: [phoneKeyboard] with a set of its own (id 1) holding [own], [shared] in the shared set. */
    private fun phone(own: Map<String, Any?>, shared: Map<String, Any?> = emptyMap()) {
        real.edit().apply {
            putBoolean("separate_settings_per_keyboard", true)
            putString("keyboard_profile_ids", JSONObject().put(phoneKeyboard.toPref(), 1).toString())
            putInt("keyboard_profile_next_id", 2)
            for ((key, value) in own) KeyboardProfiles.put(this, KeyboardProfiles.ownKey(1, key), value)
            for ((key, value) in shared) KeyboardProfiles.put(this, key, value)
        }.commit()
        KeyboardProfiles.loadGroups(real)
    }

    private fun restoredId() = KeyboardProfiles.idEntries(real).getValue(restored.toPref())
    private fun restoredOwn(key: String) = real.all[KeyboardProfiles.ownKey(restoredId(), key)]

    // (owner 2026-10-11: labels are just "Custom", no number; only kept palettes are numbered)
    @Test fun `a restored keyboard's Custom label the phone has too stays as it is`() {
        phone(own = mapOf(CustomLabels.Kind.THEME.key to 1))
        backup(own = mapOf(CustomLabels.Kind.THEME.key to 1))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals(1, restoredOwn(CustomLabels.Kind.THEME.key))
        assertEquals(1, real.all[KeyboardProfiles.ownKey(1, CustomLabels.Kind.THEME.key)])
    }

    // (an older backup's numbered label: still a label, the number as it was)
    @Test fun `a restored Custom label stays`() {
        phone(own = mapOf(CustomLabels.Kind.THEME.key to 1))
        backup(own = mapOf(CustomLabels.Kind.LAYOUT.key to 1, CustomLabels.Kind.THEME.key to 3))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals(1, restoredOwn(CustomLabels.Kind.LAYOUT.key))
        assertEquals(3, restoredOwn(CustomLabels.Kind.THEME.key))
    }

    @Test fun `a restored kept palette whose name the phone uses for other colours comes in under the next free one`() {
        phone(own = emptyMap(), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "x", Settings.PREF_THEME_COLORS to hidden1))
        backup(own = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "y", Settings.PREF_THEME_COLORS to hidden1,
            Settings.PREF_THEME_COLORS_NIGHT to hidden1))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals(hidden2, restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals(hidden2, restoredOwn(Settings.PREF_THEME_COLORS_NIGHT))
        assertEquals("y", restoredOwn(Settings.PREF_USER_COLORS_PREFIX + hidden2))
        assertEquals(null, restoredOwn(Settings.PREF_USER_COLORS_PREFIX + hidden1))
        assertEquals("x", real.getString(Settings.PREF_USER_COLORS_PREFIX + hidden1, null))
    }

    @Test fun `a restored kept palette with the phone's colours keeps its name`() {
        phone(own = emptyMap(), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "x", Settings.PREF_THEME_COLORS to hidden1))
        backup(own = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "x", Settings.PREF_THEME_COLORS to hidden1))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals(hidden1, restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals("x", restoredOwn(Settings.PREF_USER_COLORS_PREFIX + hidden1))
    }

    @Test fun `a saved theme the restore brings in follows the kept palette's new name`() {
        phone(own = emptyMap(), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "x", Settings.PREF_THEME_COLORS to hidden1))
        val look = JSONObject().put("name", "Dusk").put("values", JSONObject()
            .put(Settings.PREF_USER_COLORS_PREFIX + hidden1, JSONObject().put("s", "y"))
            .put(Settings.PREF_THEME_COLORS, JSONObject().put("s", hidden1)))
        backup(own = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "y", Settings.PREF_THEME_COLORS to hidden1),
            extra = mapOf(AppearanceLooks.PREF to JSONArray(listOf(look)).toString()))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        val dusk = AppearanceLooks.load(real).single { it.name == "Dusk" }
        assertEquals(hidden2, dusk.values[Settings.PREF_THEME_COLORS])
        assertEquals("y", dusk.values[Settings.PREF_USER_COLORS_PREFIX + hidden2])
        assertEquals(hidden2, restoredOwn(Settings.PREF_THEME_COLORS))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.SavedItemsMerge
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
 * Restoring some keyboards: the backup's palettes come into the phone's one list (owner 2026-10-11: palettes are one
 * app-wide list; backup wins the name if ticked): the same colors under another name, ticked, rename the phone's; a name
 * the phone uses for other colors, ticked, is the backup's and the phone's gets the next number (before: the backup's
 * was numbered). An older backup's copies in a set or a saved theme come in the same way, its kept
 * copies "~custom:N" as "Palette N" (before: renumbered "~custom:M"). Its "Custom" labels come in as they are (owner
 * 2026-10-11: labels are just "Custom", no number).
 * (owner 2026-10-11: keyboards have unique ids) The phone's German is k1, the backup's US k2, their sets "pk1/" and "pk2/".
 */
@RunWith(RobolectricTestRunner::class)
class PartialRestoreCustomNumbersTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val phoneKeyboard = SettingsSubtype(Locale.GERMANY, "").withId(1)
    private val restored = SettingsSubtype(Locale.US, "").withId(2)
    private val zip by lazy { File(ctx.cacheDir, "partial-custom-${System.nanoTime()}.zip") }
    private val hidden1 = "~custom:1" // (an older backup's kept copy)
    private fun palette(name: String) = real.getString(Settings.PREF_USER_COLORS_PREFIX + name, null)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    /** The backup (of this build): [restored] with a set of its own (k2) holding [own], plus [extra] backup-wide entries. */
    private fun backup(own: Map<String, Any?>, extra: Map<String?, Any?> = emptyMap()) {
        val values = HashMap<String?, Any?>()
        values[Settings.PREF_ENABLED_SUBTYPES] = restored.toPref()
        values["separate_settings_per_keyboard"] = true
        values[KeyboardIdUpgrade.DONE] = true
        values[helium314.keyboard.latin.PICKED_ONLY_DONE] = true
        values[helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE] = true
        values[helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE] = true
        for ((key, value) in own) values[KeyboardProfiles.ownKey(kid(2), key)] = value
        values.putAll(extra)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(values, z)
            z.closeEntry()
        }
    }

    /** The phone: [phoneKeyboard] with a set of its own (k1) holding [own], [shared] plain (app-wide). */
    private fun phone(own: Map<String, Any?>, shared: Map<String, Any?> = emptyMap()) {
        real.edit().apply {
            putBoolean("separate_settings_per_keyboard", true)
            for ((key, value) in own) KeyboardProfiles.put(this, idKey(1, key), value)
            for ((key, value) in shared) KeyboardProfiles.put(this, key, value)
        }.commit()
        storeKeyboards(ctx, real, listOf(phoneKeyboard))
        KeyboardProfiles.loadGroups(real)
    }

    private fun restoredId() = kid(2) // (a keyboard added keeps the backup's id)
    private fun restoredOwn(key: String) = real.all[KeyboardProfiles.ownKey(restoredId(), key)]

    // (owner 2026-10-11: labels are just "Custom", no number; only kept palettes are numbered)
    @Test fun `a restored keyboard's Custom label the phone has too stays as it is`() {
        phone(own = mapOf(CustomLabels.Kind.THEME.key to 1))
        backup(own = mapOf(CustomLabels.Kind.THEME.key to 1))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals(1, restoredOwn(CustomLabels.Kind.THEME.key))
        assertEquals(1, real.all[idKey(1, CustomLabels.Kind.THEME.key)])
    }

    // (an older backup's numbered label: still a label, the number as it was)
    @Test fun `a restored Custom label stays`() {
        phone(own = mapOf(CustomLabels.Kind.THEME.key to 1))
        backup(own = mapOf(CustomLabels.Kind.LAYOUT.key to 1, CustomLabels.Kind.THEME.key to 3))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals(1, restoredOwn(CustomLabels.Kind.LAYOUT.key))
        assertEquals(3, restoredOwn(CustomLabels.Kind.THEME.key))
    }

    @Test fun `an older backup's kept copy comes in as Palette 2 when the phone's Palette 1 has other colors`() {
        phone(own = mapOf(Settings.PREF_THEME_COLORS to "Palette 1"), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Palette 1" to "x"))
        backup(own = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "y", Settings.PREF_THEME_COLORS to hidden1,
            Settings.PREF_THEME_COLORS_NIGHT to hidden1))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals("Palette 2", restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals("Palette 2", restoredOwn(Settings.PREF_THEME_COLORS_NIGHT))
        assertEquals("y", palette("Palette 2")) // (on the list)
        assertEquals("x", palette("Palette 1"))
        assertEquals(null, restoredOwn(Settings.PREF_USER_COLORS_PREFIX + hidden1)) // (no copy in the set)
        assertEquals(null, restoredOwn(Settings.PREF_USER_COLORS_PREFIX + "Palette 2"))
    }

    // (owner 2026-10-11: backup wins the name if ticked; before, the phone's name was kept)
    @Test fun `a restored palette with the phone's colors - ticked the phone's takes the backup's name everywhere, unticked it is the phone's`() {
        phone(own = mapOf(Settings.PREF_THEME_COLORS to "Warm"), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to "x"))
        backup(own = mapOf(Settings.PREF_THEME_COLORS to "Hot"), extra = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Hot" to "x"))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true,
            names = SavedItemsMerge.Choices(setOf(SavedItemsMerge.Kind.PALETTES to "Hot")))
        assertEquals("Warm", restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals(null, palette("Hot")) // (not a second one)
        // (again, into the keyboard now on the phone: unasked it would come in as a second keyboard)
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true,
            decisions = mapOf(restored to RestoreDecision.Merge(restored)))
        assertEquals("Hot", restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals("Hot", real.all[idKey(1, Settings.PREF_THEME_COLORS)]) // (the phone's keyboard follows)
        assertEquals("x", palette("Hot"))
        assertEquals(null, palette("Warm"))
    }

    // (owner 2026-10-11: backup wins the name if ticked; before, the backup's came in as "Warm 2")
    @Test fun `a restored palette whose name the phone uses for other colors - ticked it takes the name, the phone's is numbered`() {
        phone(own = mapOf(Settings.PREF_THEME_COLORS to "Warm"), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to "x"))
        backup(own = mapOf(Settings.PREF_THEME_COLORS to "Warm"), extra = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to "y"))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        assertEquals("Warm", restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals("y", palette("Warm"))
        assertEquals("x", palette("Warm 2"))
        // (the phone's keyboard on Warm now shows the backup's colors, "Warm 2" is unused)
        assertEquals("Warm", real.all[idKey(1, Settings.PREF_THEME_COLORS)])
    }

    @Test fun `a restored palette whose name the phone uses for other colors - unticked it doesn't come in`() {
        phone(own = mapOf(Settings.PREF_THEME_COLORS to "Warm"), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to "x"))
        backup(own = mapOf(Settings.PREF_THEME_COLORS to "Warm"), extra = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Warm" to "y"))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true,
            names = SavedItemsMerge.Choices(setOf(SavedItemsMerge.Kind.PALETTES to "Warm")))
        assertEquals("Warm", restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals("x", palette("Warm"))
        assertEquals(null, palette("Warm 2"))
    }

    @Test fun `a saved theme the restore brings in names the palette as it came in, without a copy`() {
        phone(own = emptyMap(), shared = mapOf(Settings.PREF_USER_COLORS_PREFIX + "Palette 1" to "x"))
        val look = JSONObject().put("name", "Dusk").put("values", JSONObject()
            .put(Settings.PREF_USER_COLORS_PREFIX + hidden1, JSONObject().put("s", "y"))
            .put(Settings.PREF_THEME_COLORS, JSONObject().put("s", hidden1)))
        backup(own = mapOf(Settings.PREF_USER_COLORS_PREFIX + hidden1 to "y", Settings.PREF_THEME_COLORS to hidden1),
            extra = mapOf(AppearanceLooks.PREF to JSONArray(listOf(look)).toString()))
        restoreKeyboardsFrom(ctx, zip, listOf(restored), withSettings = true)
        val dusk = AppearanceLooks.load(real).single { it.name == "Dusk" }
        assertEquals("Palette 2", dusk.values[Settings.PREF_THEME_COLORS])
        assertEquals(setOf(Settings.PREF_THEME_COLORS), dusk.values.keys.filter { it.startsWith("user_") || it == Settings.PREF_THEME_COLORS }.toSet())
        assertEquals("Palette 2", restoredOwn(Settings.PREF_THEME_COLORS))
        assertEquals("y", palette("Palette 2"))
    }
}

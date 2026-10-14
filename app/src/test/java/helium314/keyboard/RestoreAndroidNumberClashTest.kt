// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardIds
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
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

/**
 * Review 2026-10-12 L1#1: a backup keyboard added by a restore keeps its id, and only a device keyboard with that same
 * id got a new one. Another id with the same Android subtype number (one hash for two ids) came in as it was: Android
 * took the two keyboards for one. Now such a backup keyboard comes in under a fresh id, its settings and learned words
 * with it; the device keyboard stays as it is.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreAndroidNumberClashTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val filesDirBefore = KeyboardProfiles.filesDir
    private val subtypeIdBefore = KeyboardIds.subtypeIdOf
    private val zip by lazy { File(ctx.cacheDir, "clash-${System.nanoTime()}.zip") }
    private val plain by lazy { SubtypeUtilsAdditional.createDefaultSubtype(Locale.US).toSettingsSubtype() }
    private val en by lazy { plain.withId(1) }
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

    private fun phone(vararg keyboards: SettingsSubtype) {
        real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putString(LearnedKeyboards.PREF_POOLS, poolsJson(keyboards.withIndex().associate { (i, k) -> k to 11 + i }))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 11 + keyboards.size).putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
        KeyboardProfiles.loadGroups(real)
        storeKeyboards(ctx, real, keyboards.toList())
        KeyboardProfiles.refreshImeId(real)
    }

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

    @Test fun `a backup keyboard whose Android number a device keyboard has comes in under a fresh id, its settings with it`() {
        phone(en)
        real.edit().putString(idKey(1, font), "device").commit()
        // the backup's "k9": another id, the Android number of the device's "k1"
        val k1 = subtypeIdBefore("k1")
        KeyboardIds.subtypeIdOf = { if (it == "k9") k1 else subtypeIdBefore(it) }
        val rolled = ArrayDeque(listOf("k9", "k1", "k77"))
        KeyboardIds.source = { rolled.removeFirstOrNull() ?: KeyboardIds.random() }
        val fromBackup = plain.with(ExtraValue.MORE_POPUPS, "all").withId(9)
        backup(listOf(fromBackup), mapOf(idKey(9, font) to "backup"))
        restoreKeyboardsFrom(ctx, zip, listOf(fromBackup), withSettings = true, decisions = mapOf(fromBackup to RestoreDecision.Separate()))

        val (device, restored) = storedEnabled(real)
        // the device keyboard is as it was: its id, its set, its pool
        assertEquals("k1", device.keyboardId)
        assertEquals("device", real.getString(idKey(1, font), null))
        assertEquals(11, storedPools(real)["k1"])
        // the backup's comes in under the fresh id, with its settings and a pool of its own
        assertEquals("k77", restored.keyboardId)
        assertNull(restored.keyboardFrom)
        assertEquals("all", restored.getExtraValueOf(ExtraValue.MORE_POPUPS))
        assertEquals("backup", real.getString(KeyboardProfiles.ownKey("k77", font), null))
        assertFalse(real.all.keys.any { it.startsWith("pk9/") }, "the clashing id's set was written: ${real.all.keys}")
        assertNotNull(storedPools(real)["k77"])
        assertNotEquals(KeyboardIds.androidSubtypeId("k1"), KeyboardIds.androidSubtypeId(restored.keyboardId!!))
    }

    @Test fun `without a clash the backup keyboard keeps its id`() {
        phone(en)
        val fromBackup = plain.with(ExtraValue.MORE_POPUPS, "all").withId(9)
        backup(listOf(fromBackup), mapOf(idKey(9, font) to "backup"))
        restoreKeyboardsFrom(ctx, zip, listOf(fromBackup), withSettings = true, decisions = mapOf(fromBackup to RestoreDecision.Separate()))
        assertEquals(listOf("k1", "k9"), storedEnabled(real).map { it.keyboardId })
        assertEquals("backup", real.getString(idKey(9, font), null))
    }
}

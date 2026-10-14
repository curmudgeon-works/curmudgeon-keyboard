// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.profileIds
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.dialogs.StartSettings
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.hiddenSettingsNote
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.restoreFollowUp
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.startSettings
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
 * (owner 2026-10-11: "I think we shouldn't have separate common settings. Just keep main keyboard's settings and othrr
 * keyboards point to it.") No common set: the first keyboard in the list is the main one, and its own set is what every
 * keyboard reads with separate settings off, and what a shared menu reads. Nothing is copied on switching; every keyboard
 * has its own set from the start; the old common set goes once, and a backup's is ignored.
 */
@RunWith(RobolectricTestRunner::class)
class NoCommonSetTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "nocommon-${System.nanoTime()}.zip") }
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val vGap = Settings.PREF_KEY_VERTICAL_GAP
    private val appearance = KeyboardProfiles.Group.APPEARANCE
    private lateinit var en: SettingsSubtype
    private lateinit var de: SettingsSubtype
    private lateinit var hi: SettingsSubtype
    private fun view(id: Int) = ProfilePreferences(real) { id }
    /** What keyboard [id]'s own screens edit (KeyboardScopeContext): its set, or the main choice. */
    private fun screen(id: Int) = ProfilePreferences(real) { KeyboardProfiles.scopedId(real, id) }
    private fun own(id: Int, key: String) = KeyboardProfiles.ownKey(id, key)
    private fun separate(on: Boolean) = real.edit().putBoolean("separate_settings_per_keyboard", on).commit()

    @Before fun setUp() {
        real.edit().clear().commit()
        KeyboardProfiles.filesDir = DeviceProtectedUtils.getFilesDir(ctx); KeyboardProfiles.deleteAllFiles()
        SubtypeSettings.init(ctx)
        en = available("en-US"); de = available("de-DE"); hi = available("hi")
        useKeyboards(ctx, real, en, de, hi)
        profileIds(real, mapOf(en to 1, de to 2, hi to 3), 4)
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() {
        zip.delete(); KeyboardProfiles.deleteAllFiles(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Test fun `separate settings off - keyboard 2 reads keyboard 1's own set`() {
        real.edit().putFloat(own(1, gap), 2f).putFloat(own(2, gap), 4f).commit()
        assertEquals(en, KeyboardProfiles.mainKeyboard(real))
        assertEquals(2f, view(2).getFloat(gap, 0f))
        assertEquals(2f, view(KeyboardProfiles.SHARED).getFloat(gap, 0f))
        assertEquals(2f, view(2).all[gap])
    }

    @Test fun `a change from keyboard 2's screen lands in keyboard 1's set`() {
        real.edit().putFloat(own(2, gap), 4f).commit()
        screen(2).edit().putFloat(gap, 3f).commit()
        assertEquals(3f, real.getFloat(own(1, gap), 0f))
        assertEquals(4f, real.getFloat(own(2, gap), 0f), "keyboard 2's own set changed")
        assertFalse(real.contains(gap), "a common set was written")
    }

    @Test fun `reordering makes keyboard 2 the main one, everyone reads its set`() {
        real.edit().putFloat(own(1, gap), 2f).putFloat(own(2, gap), 4f).commit()
        assertEquals(2f, view(3).getFloat(gap, 0f))
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(de, en, hi))).commit()
        assertEquals(de, KeyboardProfiles.mainKeyboard(real))
        assertEquals(4f, view(3).getFloat(gap, 0f))
        assertEquals(4f, view(1).getFloat(gap, 0f))
        assertEquals(2f, real.getFloat(own(1, gap), 0f), "the old main's set changed")
    }

    @Test fun `a shared menu reads the main's set, edits go there, and unshared each reads its own again`() {
        separate(true)
        real.edit().putFloat(own(1, gap), 2f).putFloat(own(2, gap), 4f).commit()
        assertEquals(4f, view(2).getFloat(gap, 0f))
        KeyboardProfiles.setGroupShared(real, appearance, true)
        assertEquals(2f, view(2).getFloat(gap, 0f))
        screen(2).edit().putFloat(gap, 5f).commit()
        assertEquals(5f, real.getFloat(own(1, gap), 0f))
        assertEquals(4f, real.getFloat(own(2, gap), 0f), "keyboard 2's own value changed while shared")
        assertFalse(real.contains(gap), "a common set was written")
        KeyboardProfiles.setGroupShared(real, appearance, false)
        assertEquals(4f, view(2).getFloat(gap, 0f))
        assertEquals(5f, view(1).getFloat(gap, 0f)) // (the main keeps the edits: they were its own)
    }

    @Test fun `the main keyboard can't be deleted while its settings are the common ones`() {
        assertTrue(KeyboardProfiles.mainBlocksDelete(real, en)) // (separate settings off)
        assertFalse(KeyboardProfiles.mainBlocksDelete(real, de))
        separate(true)
        KeyboardProfiles.Group.entries.forEach { KeyboardProfiles.setGroupShared(real, it, false) }
        assertFalse(KeyboardProfiles.mainBlocksDelete(real, en)) // (nothing shared)
        KeyboardProfiles.setGroupShared(real, appearance, true)
        assertTrue(KeyboardProfiles.mainBlocksDelete(real, en))
    }

    @Test fun `a keyboard added gets its own set at once from its start-from choice`() {
        real.edit().putFloat(own(1, gap), 2f).putFloat(own(2, gap), 4f).commit()
        startSettings(ctx, hi, StartSettings.From(de))
        assertEquals(4f, real.getFloat(own(3, gap), 0f))
        startSettings(ctx, hi, StartSettings.Common)
        assertEquals(2f, real.getFloat(own(3, gap), 0f))
        startSettings(ctx, hi, StartSettings.Defaults)
        assertFalse(real.contains(own(3, gap)))
        assertFalse(real.contains("keyboard_start_from"), "the pick still waits for separate settings")
    }

    @Test fun `the upgrade gives keyboards without a set the common one, then the common set goes`() {
        real.edit().putFloat(gap, 7f).putFloat(vGap, 1.5f).putBoolean(Settings.PREF_ADVANCED_SETTINGS, true)
            .putFloat(own(1, gap), 2f) // the main keyboard: a set of its own, without the vertical gap
            .putFloat(own(3, gap), 9f) // keyboard 3: a set of its own; keyboard 2: none
            .commit()
        KeyboardProfiles.dropCommonSet(real, fromCommon = true)
        assertEquals(7f, real.getFloat(own(2, gap), 0f)); assertEquals(1.5f, real.getFloat(own(2, vGap), 0f))
        assertFalse(real.contains(gap)); assertFalse(real.contains(vGap))
        assertEquals(2f, real.getFloat(own(1, gap), 0f)); assertFalse(real.contains(own(1, vGap)), "the main keyboard was gap-filled")
        assertEquals(9f, real.getFloat(own(3, gap), 0f))
        assertTrue(real.getBoolean(Settings.PREF_ADVANCED_SETTINGS, false), "an app-wide key went")
        assertTrue(real.getBoolean(KeyboardProfiles.NO_COMMON_SET_DONE, false))
        // a second run changes nothing
        real.edit().putFloat(gap, 8f).commit()
        val before = real.all
        KeyboardProfiles.dropCommonSet(real, fromCommon = true)
        assertEquals(before, real.all)
    }

    @Test fun `the version check runs the upgrade`() {
        real.edit().putFloat(gap, 7f).putInt(Settings.PREF_VERSION_CODE, BuildConfig.MIGRATION_VERSION).commit()
        checkVersionUpgrade(ctx)
        assertTrue(real.getBoolean(KeyboardProfiles.NO_COMMON_SET_DONE, false))
        assertFalse(real.contains(gap))
        assertEquals(7f, view(2).getFloat(gap, 0f))
    }

    private fun backup(vararg values: Pair<String, Any?>, separate: Boolean) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(en, hi)),
                Settings.PREF_SELECTED_SUBTYPE to en.toPref(), "separate_settings_per_keyboard" to separate,
                Settings.PREF_VERSION_CODE to BuildConfig.MIGRATION_VERSION,
                "keyboard_profile_ids" to JSONObject().put(en.toPref(), 6).put(hi.toPref(), 7).toString(), "keyboard_profile_next_id" to 8,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) + values, z)
            z.closeEntry()
        }
    }

    @Test fun `restoring the main keyboard with separate settings off changes what everyone sees`() {
        real.edit().putFloat(own(1, gap), 2f).commit()
        backup(own(6, gap) to 5f, KeyboardProfiles.NO_COMMON_SET_DONE to true, separate = false)
        val (_, hidden) = restoreKeyboardsFrom(ctx, zip, listOf(en), withSettings = true, decisions = mapOf(en to RestoreDecision.Merge(en)))
        assertEquals(5f, real.getFloat(own(1, gap), 0f))
        assertEquals(5f, view(2).getFloat(gap, 0f))
        assertEquals("${keyboardName(en, ctx)}'s settings were restored. All keyboards use them, as they're the common settings.",
            hiddenSettingsNote(ctx, hidden))
    }

    @Test fun `restoring a keyboard from an old backup ignores the backup's common set`() {
        real.edit().putFloat(own(1, gap), 2f).commit()
        backup(gap to 7f, own(6, vGap) to 1.5f, separate = false)
        restoreKeyboardsFrom(ctx, zip, listOf(en), withSettings = true, decisions = mapOf(en to RestoreDecision.Merge(en)))
        assertFalse(real.contains(own(1, gap)), "the backup's common value came")
        assertEquals(1.5f, real.getFloat(own(1, vGap), 0f))
        assertFalse(real.contains(gap))
        assertEquals(0f, view(2).getFloat(gap, 0f))
    }

    @Test fun `a full restore of an old backup drops its common set, keyboards without a set get the main's`() {
        backup(gap to 7f, vGap to 1.5f, own(6, gap) to 5f, separate = false)
        restoreEverythingFrom(ctx, zip)
        restoreFollowUp(ctx)
        assertFalse(real.contains(gap)); assertFalse(real.contains(vGap))
        assertEquals(5f, real.getFloat(own(6, gap), 0f))
        assertEquals(5f, real.getFloat(own(7, gap), 0f)) // (hindi had none: the main's)
        assertFalse(real.contains(own(7, vGap)))
        assertTrue(real.getBoolean(KeyboardProfiles.NO_COMMON_SET_DONE, false))
    }
}

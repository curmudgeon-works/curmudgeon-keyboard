// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.transferOldPinnedClips
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.latin.personalization.LearnedStoreIo
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On Android 5–6 the protected preferences are the settings file itself: restoring the backup's protected entry
 *  wiped every setting just restored (analysis 2026-10-08, 3010 item 1). Android 7+ stays as it was. */
@RunWith(RobolectricTestRunner::class)
class RestoreProtectedPrefsTest {
    private lateinit var ctx: Context
    private lateinit var real: SharedPreferences
    private lateinit var zip: File

    private val a = SettingsSubtype(Locale.US, "")
    private val b = SettingsSubtype(Locale.GERMANY, "")
    private val keyboards get() = SubtypeSettings.createSettingsSubtypes(SubtypeSettings.createPrefSubtypes(listOf(a, b)))
    private val pinnedClips = """[{"timeStamp":1700000000000,"content":"pinned text","isPinned":true}]"""

    // (the cached preferences, clipboard and database of an earlier test would be another sandbox's files)
    private fun resetCaches() {
        for (name in listOf("prefs", "imePrefs", "mainPrefs"))
            DeviceProtectedUtils::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        (DeviceProtectedUtils::class.java.getDeclaredField("keyboardPrefs").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        Database::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Before fun setUp() {
        resetCaches()
        ctx = ApplicationProvider.getApplicationContext()
        real = ctx.realPrefs()
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.init(ctx)
        zip = File(ctx.cacheDir, "backup-protected.zip")
    }

    @After fun tearDown() {
        real.edit().clear().commit()
        ctx.protectedPrefs().edit().clear().commit()
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        zip.delete()
        resetCaches()
    }

    /** A backup shaped like the P11's: preferences.json, then protected_preferences.json ([protectedLines] as is). */
    private fun writeBackup(settings: Map<String?, Any?>, protectedLines: String) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(settings, z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("protected_preferences.json"))
            z.write(protectedLines.toByteArray())
            z.closeEntry()
        }
    }

    private fun protectedEntry(values: Map<String?, Any?>) =
        java.io.ByteArrayOutputStream().also { settingsToJsonStream(values, it) }.toString()

    /** Two keyboards, separate settings, keyboard 1's own key: what the app writes today. */
    private fun currentSettings(): Map<String?, Any?> = mapOf(
        Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(listOf(a, b)),
        Settings.PREF_VERSION_CODE to 3010,
        "separate_settings_per_keyboard" to true,
        "keyboard_profile_ids" to """{"${a.toPref()}":1,"${b.toPref()}":2}""",
        "keyboard_profile_next_id" to 3,
        "p1/${Settings.PREF_KEY_LONGPRESS_TIMEOUT}" to 450,
    )

    /** An upstream HeliBoard backup from before the keyboard list: some settings, pinned clips in the protected file. */
    private fun upstreamSettings(): Map<String?, Any?> = mapOf(
        Settings.PREF_VERSION_CODE to 1000,
        Settings.PREF_KEY_LONGPRESS_TIMEOUT to 450,
    )

    private fun assertCurrentSettingsKept() {
        val all = real.all
        assertEquals(SubtypeSettings.createPrefSubtypes(listOf(a, b)), all[Settings.PREF_ENABLED_SUBTYPES], "settings gone: $all")
        assertEquals(3010, all[Settings.PREF_VERSION_CODE])
        assertEquals(true, all["separate_settings_per_keyboard"])
        assertEquals(450, all["p1/${Settings.PREF_KEY_LONGPRESS_TIMEOUT}"])
    }

    private fun clipsInDatabase(): List<ClipboardHistoryEntry> {
        val dao = ClipboardDao.getInstance(ctx)!!
        return (0 until dao.count()).map { dao.getAt(it) }
    }

    private fun assertOnePhoneFile() {
        assertEquals(ctx.filesDir, DeviceProtectedUtils.getFilesDir(ctx))
        ctx.protectedPrefs().edit().putString("probe", "x").commit()
        assertEquals("x", real.getString("probe", null), "Android 6: one preferences file")
        real.edit().remove("probe").commit()
    }

    @Config(sdk = [23])
    @Test fun `Android 6, all keyboards and settings from a 7+ backup keep the settings`() {
        assertOnePhoneFile()
        writeBackup(currentSettings(), protectedEntry(mapOf("lib_checksum" to "abc")))
        restoreChosenFrom(ctx, zip, keyboards, settings = true, learnedWords = false, io = LearnedStoreIo.Native)
        assertCurrentSettingsKept()
    }

    @Config(sdk = [23])
    @Test fun `Android 6, Everything from an upstream backup keeps the settings and brings the pinned clips`() {
        assertOnePhoneFile()
        writeBackup(upstreamSettings(), protectedEntry(mapOf("pinned_clips" to pinnedClips)))
        restoreEverythingFrom(ctx, zip)
        assertEquals(1000, real.all[Settings.PREF_VERSION_CODE], "settings gone: ${real.all}")
        assertEquals(450, real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        transferOldPinnedClips(ctx)
        val clips = clipsInDatabase()
        assertEquals(listOf("pinned text"), clips.map { it.text })
        assertTrue(clips.single().isPinned)
        assertNull(real.all["pinned_clips"])
    }

    @Config(sdk = [23])
    @Test fun `Android 6, an unreadable protected entry leaves the restored settings`() {
        assertOnePhoneFile()
        writeBackup(currentSettings(), "boolean settings\n{not json")
        restoreChosenFrom(ctx, zip, keyboards, settings = true, learnedWords = false, io = LearnedStoreIo.Native)
        assertCurrentSettingsKept()
        real.edit().clear().commit()
        writeBackup(upstreamSettings(), "boolean settings\n{not json")
        restoreEverythingFrom(ctx, zip)
        assertEquals(1000, real.all[Settings.PREF_VERSION_CODE], "settings gone: ${real.all}")
        assertEquals(450, real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
    }

    // the real phones (Android 7+): two files in two folders; nothing changes there
    @Test fun `Android 7+, settings go to the settings file and the protected entry to its own, pinned clips restored`() {
        assertNotSame(real, ctx.protectedPrefs())
        assertNotEquals(ctx.filesDir, DeviceProtectedUtils.getFilesDir(ctx))
        ctx.protectedPrefs().edit().putString("stale", "x").commit()
        writeBackup(currentSettings(), protectedEntry(mapOf("lib_checksum" to "abc")))
        restoreChosenFrom(ctx, zip, keyboards, settings = true, learnedWords = false, io = LearnedStoreIo.Native)
        assertCurrentSettingsKept()
        assertNull(real.all["lib_checksum"])
        assertEquals(mapOf<String, Any?>("lib_checksum" to "abc"), ctx.protectedPrefs().all, "the protected file is replaced")

        writeBackup(upstreamSettings(), protectedEntry(mapOf("pinned_clips" to pinnedClips)))
        restoreEverythingFrom(ctx, zip)
        assertEquals(1000, real.all[Settings.PREF_VERSION_CODE])
        assertEquals(450, real.all[Settings.PREF_KEY_LONGPRESS_TIMEOUT])
        assertNull(real.all["pinned_clips"])
        assertEquals(mapOf<String, Any?>("pinned_clips" to pinnedClips), ctx.protectedPrefs().all)
        transferOldPinnedClips(ctx)
        assertEquals(listOf("pinned text"), clipsInDatabase().map { it.text })
        assertNull(ctx.protectedPrefs().all["pinned_clips"])
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.AppearanceLooks
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Locale
import android.os.Looper
import helium314.keyboard.latin.R
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.restoreEverythingFrom
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.preferences.startFactoryReset
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The review of 2026-10-08 (0.3.007 to 0.3.010), fixed 2026-10-10: area 1 #4 and #5, area 2 #3. */
@RunWith(RobolectricTestRunner::class)
class Review1010LearnTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    // ---- area 1 #4: Discard after a saved theme was renamed or deleted on the open Appearance screen ----

    // (2026-10-11: Discard undoes a rename or delete everywhere, the saved themes list with it: "Every action on an
    // editing screen ... is held until Save; Discard undoes it everywhere it reached". Before, the rename stayed and
    // the snapshot followed it, so Discard never named a theme that was gone; the list now comes back with the name.)
    @Test fun `Discard after renaming the chosen theme brings back the old name, in the list too`() {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Mine", emptyMap())), now = true)
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Mine") }
        val draft = AppearanceDraft.of(ctx) // the Appearance screen opens
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Work", emptyMap())), now = true) // Themes -> rename
        AppearanceLooks.choiceRenamed(ctx, "Mine", "Work")
        assertTrue(draft.hasChanges(ctx))
        AppearanceDraft.rejectOpen(ctx) // the cross, or leaving the app
        assertEquals("Mine", ctx.prefs().getString(AppearanceLooks.PREF_SELECTED, null))
        assertEquals("Mine", AppearanceLooks.load(real).single().name)
    }

    @Test fun `Discard after deleting the chosen theme brings it back, chosen`() {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Mine", emptyMap())), now = true)
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Mine") }
        AppearanceDraft.of(ctx)
        AppearanceLooks.save(real, emptyList(), now = true) // Themes -> delete
        AppearanceLooks.choiceRenamed(ctx, "Mine", null)
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Mine", ctx.prefs().getString(AppearanceLooks.PREF_SELECTED, null))
        assertEquals("Mine", AppearanceLooks.load(real).single().name)
    }

    @Test fun `Discard still puts back another theme chosen on the screen`() {
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Old") }
        AppearanceDraft.of(ctx)
        ctx.prefs().edit { putString(AppearanceLooks.PREF_SELECTED, "Mine") } // chosen on the screen
        AppearanceLooks.choiceRenamed(ctx, "Mine", "Work") // then renamed
        AppearanceDraft.rejectOpen(ctx)
        assertEquals("Old", ctx.prefs().getString(AppearanceLooks.PREF_SELECTED, null))
    }

    // ---- area 1 #5: a "defaults only" keyboard marked the one-time flags; separate OFF from it dropped them ----

    private val flags = listOf("learning_swiping_global", "suggestions_refine_moved", "moved_markers_removed")

    private fun separateWithFlags(): SettingsSubtype {
        val keyboard = SettingsSubtype(Locale.US, "")
        real.edit().putBoolean("separate_settings_per_keyboard", true).apply { flags.forEach { putBoolean(it, true) } }.commit()
        return keyboard
    }

    @Test fun `a keyboard with only defaults doesn't mark the one-time flags`() {
        val keyboard = separateWithFlags()
        val id = KeyboardProfiles.idFor(real, keyboard)
        KeyboardProfiles.write(real, id, emptyMap(), markDefaults = true) // Add keyboard -> "defaults only"
        for (flag in flags) assertFalse(real.contains(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + flag)), flag)
        KeyboardProfiles.disable(real, keyboard) // separate settings OFF, shared from that keyboard
        for (flag in flags) assertTrue(real.getBoolean(flag, false), "$flag dropped: its move runs again")
    }

    @Test fun `separate OFF keeps the one-time flags a keyboard marked before the fix`() {
        val keyboard = separateWithFlags()
        val id = KeyboardProfiles.idFor(real, keyboard)
        real.edit().apply { flags.forEach { putBoolean(KeyboardProfiles.ownKey(id, KeyboardProfiles.TOMBSTONE + it), true) } }.commit()
        KeyboardProfiles.disable(real, keyboard)
        for (flag in flags) assertTrue(real.getBoolean(flag, false), "$flag dropped: its move runs again")
    }

    // ---- area 2 #3: what the settings listener would have cleared stays stale after a restore or a reset ----

    private val priorityKey = LanguagePriority.keys(Locale.US).first { LanguagePriority.isPriorityKey(it) }
    private fun factor() = LanguagePriority.forSuggestions(Locale.US) { ctx.prefs() } // (cached once read)

    @Suppress("UNCHECKED_CAST")
    private fun pictureCache() = helium314.keyboard.latin.settings.Settings::class.java.getDeclaredField("sCachedBackgroundImages")
        .apply { isAccessible = true }.get(null) as MutableMap<String, android.graphics.drawable.Drawable>
    private fun typefaceLoaded() = helium314.keyboard.keyboard.KeyboardTypeface::class.java.getDeclaredField("customTypefaceLoaded")
        .apply { isAccessible = true }
    /** Caches as a keyboard in use leaves them: a picture shown, the key font looked up, the priorities read. */
    private fun warmCaches(priority: Int) {
        assertEquals(LanguagePriority.factor(priority), factor())
        synchronized(pictureCache()) { pictureCache()["old picture"] = android.graphics.drawable.ColorDrawable() }
        typefaceLoaded().setBoolean(helium314.keyboard.keyboard.KeyboardTypeface, true)
    }
    private fun assertCachesCleared(what: String, priority: Int) {
        assertEquals(LanguagePriority.factor(priority), factor(), "$what: the old language priority stayed")
        assertTrue(synchronized(pictureCache()) { pictureCache().isEmpty() }, "$what: the old background picture stayed")
        assertFalse(typefaceLoaded().getBoolean(helium314.keyboard.keyboard.KeyboardTypeface), "$what: the old font stayed")
    }

    private fun idle(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a whole restore clears the caches of the settings it replaced`() {
        val zip = File(ctx.cacheDir, "review1010.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf<String?, Any?>(priorityKey to LanguagePriority.LOW), out)
            out.closeEntry()
        }
        warmCaches(LanguagePriority.HIGH)
        try {
            assertTrue(runRestore(ctx, {}, R.string.backup_restored, 1) { restoreEverythingFrom(ctx, zip) })
            idle { !BackupRestoreJob.isRunning }
        } finally { zip.delete() }
        assertEquals(LanguagePriority.LOW, real.getInt(priorityKey, 0), "not restored")
        assertCachesCleared("restore", LanguagePriority.LOW)
    }

    @Test fun `a factory reset clears the caches of the settings it removed`() {
        real.edit().putInt(priorityKey, LanguagePriority.LOW).commit()
        warmCaches(LanguagePriority.LOW)
        var done = false
        startFactoryReset(ctx, keyboards = true, learnedWords = false, clipboard = false, custom = false) { done = true }
        idle { done }
        assertTrue(done, "the reset never ran")
        assertFalse(real.contains(priorityKey), "not reset")
        assertCachesCleared("reset", LanguagePriority.HIGH)
    }
}

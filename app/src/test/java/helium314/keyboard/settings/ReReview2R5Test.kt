// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.kid
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.withId
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.RestoreChoiceDialogFrom
import helium314.keyboard.settings.preferences.RestoreDialogData
import helium314.keyboard.settings.preferences.SavedItemsMerge
import helium314.keyboard.settings.preferences.readRestoreDialog
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * (review 2026-10-11 R5; owner 2026-10-11 "OK") The restore dialog reads the picked backup off the main thread, with
 * "Reading backup…" until it's ready: before, the zip was read whole (and the dry runs made) in composition, and a large
 * backup froze the screen. Cancel while reading closes it, nothing restored; a read that fails closes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class ReReview2R5Test {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real get() = ctx.realPrefs()
    private val zip by lazy { File(ctx.cacheDir, "r5-${System.nanoTime()}.zip") }
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val reading = "Reading backup…"
    // (owner 2026-10-12: the top checkbox reads "Full restore (delete app data)"; was "Delete app data and restore from backup")
    private val top = "Full restore (delete app data)"
    private val type = LayoutType.NUMBER_ROW
    private val folder get() = LayoutUtilsCustom.getLayoutFile("x", type, ctx).parentFile!!

    @Before fun setUp() {
        helium314.keyboard.reviveComposeMainDispatcher()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        folder.deleteRecursively(); folder.mkdirs(); LayoutUtilsCustom.onLayoutFileChanged()
    }
    @After fun tearDown() {
        scenario?.close()
        zip.delete(); folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The phone: Hindi (k1) with an unsaved number row "A"; the backup: the same keyboard (its id), that name with other
     *  keys (so it comes in renamed: the dry runs matter), and French (k2). (owner 2026-10-11: keyboards have unique ids;
     *  the backup's sets are its keyboards' ids, no id map) */
    private fun phoneAndBackup() {
        val a = LayoutUtilsCustom.makePrivateLayout("A", type, "", ctx)
        val hindi = available("hi").withLayout(type, a).withId(1)
        val fr = available("fr").withId(2)
        useKeyboards(ctx, real, hindi)
        val list = SubtypeSettings.createPrefSubtypes(listOf(hindi, fr))
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
                Settings.PREF_SELECTED_SUBTYPE to hindi.toPref(), KeyboardIdUpgrade.DONE to true,
                KeyboardProfiles.NO_COMMON_SET_DONE to true, KeyboardProfiles.ownKey(kid(1), Settings.PREF_KEY_HORIZONTAL_GAP) to 3f), z)
            z.closeEntry()
            z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$a")); z.write("B".toByteArray()); z.closeEntry()
        }
    }

    // (a renamed unsaved layout's new name is made from the clock: two dry runs name it differently)
    private fun unnamed(kb: SettingsSubtype) =
        kb.layoutName(type)?.takeIf { LayoutUtilsCustom.isPrivateLayout(it) }?.let { kb.withLayout(type, "~") } ?: kb
    private fun unnamed(settings: Map<String, Any?>) =
        settings.mapValues { (_, v) -> if (v is String && LayoutUtilsCustom.isPrivateLayout(v)) "~" else v }

    @Test fun `the loader gives the plan the dialog built before`() {
        phoneAndBackup()
        val before = restorePlanFrom(ctx, zip)
        val data = readRestoreDialog(ctx, zip)
        val plan = data.plan
        assertEquals(2, data.pending.keyboards.size)
        assertEquals(before.incoming.keys, plan.incoming.keys)
        assertEquals(before.incoming.mapValues { unnamed(it.value) }, plan.incoming.mapValues { unnamed(it.value) })
        // (owner 2026-10-12: the backup's keeps its name; the device's unsaved number row gets a new one, planned here)
        assertEquals(plan.incoming.keys.toList(), plan.incoming.values.toList())
        assertTrue(plan.items.keyLayouts.deviceRenames.isNotEmpty(), "(the device's number row wasn't planned a new name)")
        assertEquals(before.enabled, plan.enabled)
        assertEquals(before.switchedOff, plan.switchedOff)
        for (k in data.pending.keyboards) {
            assertEquals(before.asks(k), plan.asks(k))
            // (owner 2026-10-11: backup wins the name if ticked; the ticks replace "bring them in as copies")
            for (names in listOf(SavedItemsMerge.Choices(), SavedItemsMerge.Choices(setOf(SavedItemsMerge.Kind.THEMES to "x"))))
                assertEquals(unnamed(before.backupSettings(k, names)), unnamed(plan.backupSettings(k, names)))
        }
        assertEquals(before.clashes, plan.clashes)
        assertTrue(zip.exists(), "(the loader deleted the backup)")
        assertEquals(1, folder.list()!!.size, "the dry runs wrote: ${folder.list()!!.toList()}")
    }

    /** The dialog for [zip], [load] reading it; [onDismiss] when it closes (then gone). */
    private fun show(load: (Context, File) -> RestoreDialogData, onDismiss: () -> Unit = { }) {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent {
                var open by remember { mutableStateOf(true) }
                if (open) RestoreChoiceDialogFrom(zip, load) { open = false; onDismiss() }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            assertTrue(System.currentTimeMillis() < end, what)
            Thread.sleep(20)
        }
    }

    @Test fun `Reading backup shows while the backup is read off the main thread, then the dialog`() {
        phoneAndBackup()
        val gate = CountDownLatch(1)
        val onMain = AtomicReference<Boolean?>(null)
        show({ c, f -> onMain.set(Looper.myLooper() == Looper.getMainLooper()); gate.await(10, TimeUnit.SECONDS); readRestoreDialog(c, f) })
        until("the read never started") { onMain.get() != null }
        assertEquals(false, onMain.get(), "the backup was read on the main thread")
        assertEquals(1, shown(reading), "no \"$reading\" while reading")
        assertEquals(0, shown(top), "the dialog showed before the backup was read")
        gate.countDown()
        until("the dialog never came") { shown(top) == 1 }
        assertEquals(0, shown(reading), "\"$reading\" stayed up")
    }

    @Test fun `Cancel while reading closes it, nothing restored, the backup file deleted`() {
        phoneAndBackup()
        val gate = CountDownLatch(1)
        val started = AtomicBoolean(false)
        var dismissed = false
        val before = real.all.toMap()
        show({ c, f -> started.set(true); gate.await(10, TimeUnit.SECONDS); readRestoreDialog(c, f) }) { dismissed = true }
        until("the read never started") { started.get() && shown(reading) == 1 }
        compose.onNodeWithText(ctx.getString(android.R.string.cancel)).performClick()
        until("Cancel didn't close it") { dismissed && shown(reading) == 0 }
        gate.countDown()
        until("the backup file stayed after the read ended") { !zip.exists() }
        assertEquals(0, shown(top), "the dialog came after Cancel")
        assertEquals(before, real.all.toMap(), "something was restored")
    }

    @Test fun `a read that fails closes it, the backup file deleted`() {
        phoneAndBackup()
        var dismissed = false
        show({ _, _ -> throw IllegalStateException("broken zip") }) { dismissed = true }
        until("a failed read didn't close it") { dismissed }
        assertEquals(0, shown(reading)); assertEquals(0, shown(top))
        assertFalse(zip.exists(), "the backup file stayed")
    }
}

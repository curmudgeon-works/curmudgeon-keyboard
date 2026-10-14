// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.kid
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.PictureLibrary
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.FactoryResetDialog
import helium314.keyboard.settings.preferences.ResetKeep
import helium314.keyboard.settings.preferences.keptOnReset
import helium314.keyboard.settings.preferences.runBackup
import helium314.keyboard.settings.preferences.startFactoryReset
import helium314.keyboard.storedEnabled
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The factory reset runs to the end from the dialog's button, with the learned words' files off the screen thread.
 * Owner 2026-10-11: ticks mean keep; all unticked = full reset ("Select items you DON'T want to reset:"). The learned
 * words' part (merge into the default keyboard's): LearnedKeyboardsResetRestoreTest.
 * (owner 2026-10-11: keyboards have unique ids; sets are "p<id>/", kept keyboards keep their ids, a reset one is a new
 * default with a new id)
 */
@RunWith(RobolectricTestRunner::class)
class FactoryResetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val de: File get() = DeviceProtectedUtils.getFilesDir(ctx)
    private val filesDirBefore = KeyboardProfiles.filesDir
    private var scenario: ActivityScenario<SettingsActivity>? = null

    private val a by lazy { available("en-US").withId(1) }
    private val b by lazy { available("de-DE").withId(2) }
    private val symbols = "custom.mine."
    private fun own(id: Int, key: String) = idKey(id, key)
    private fun picture(id: Int) = File(de, "custom_background_image_p${kid(id)}")
    private val libraryPicture get() = File(PictureLibrary.dir(ctx), "picture_1")
    private val font get() = File(FontLibrary.dir(ctx), "Mine.ttf")
    private val layoutFile get() = LayoutUtilsCustom.getLayoutFile(symbols, LayoutType.SYMBOLS, ctx)

    @Before fun setUp() {
        resetClipboard()
        real.edit().clear().commit()
        KeyboardProfiles.filesDir = de
        SubtypeSettings.init(ctx)
    }

    @After fun tearDown() {
        scenario?.close()
        // (the main looper idle before the next test's setup: work the reset posted lands here, not there)
        shadowOf(Looper.getMainLooper()).idle()
        ClipboardDao.getInstance(ctx)?.clear()
        resetClipboard()
        real.edit().clear().commit()
        listOf(PictureLibrary.dir(ctx), FontLibrary.dir(ctx), File(de, "layouts")).forEach { it.deleteRecursively() }
        KeyboardProfiles.deleteAllFiles()
        LayoutUtilsCustom.onLayoutFileChanged()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.filesDir = filesDirBefore
    }

    private fun resetClipboard() =
        ClipboardDao::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)

    /** Two keyboards with their own sets (b: k2), separate settings on; b uses own items: popups and a popup set, a
     *  custom symbols layout, a palette, a font, pictures; and settings of the plain kind. Own items and files, a clip. */
    private fun phone() {
        useKeyboards(ctx, real, a, b)
        layoutFile.writeText("[[{\"label\": \"x\"}]]")
        LayoutUtilsCustom.onLayoutFileChanged()
        libraryPicture.apply { parentFile!!.mkdirs(); writeText("picture") }
        font.apply { parentFile!!.mkdirs(); writeText("font") }
        picture(2).writeText("b's picture")
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(KeyPopupOverrides.PREF_SETS, "[\"Mine\"]")
            putString(AppearanceLooks.PREF, "[]")
            putString(LayoutPresets.PREF, "[]")
            putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", "[warm]")
            putString(own(2, KeyPopupOverrides.PREF), "{\"a\":\"b\"}")
            putString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine")
            putString(own(2, Settings.PREF_LAYOUT_PREFIX + LayoutType.SYMBOLS.name), symbols)
            putString(own(2, Settings.PREF_THEME_COLORS), "Warm")
            putString(own(2, Settings.PREF_KEY_FONT), FontLibrary.PREFIX + "Mine.ttf")
            // not own items: settings, back to their defaults whatever is ticked
            putString(own(2, Settings.PREF_THEME_COLORS_NIGHT), "dark")
            putInt(own(2, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 999)
            putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 999)
        }
        ClipboardDao.getInstance(ctx)!!.addClip(1L, true, "pinned clip")
    }

    private fun reset(keep: ResetKeep) {
        var done = false
        startFactoryReset(ctx, keep) { done = true }
        // the learned words' part runs on a background thread, the reset is posted to the main one
        val until = System.currentTimeMillis() + 10_000
        while (!done && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        assertTrue(done, "the reset never ran")
    }

    private fun listed() = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype().toPref() }
    private fun clips() = ClipboardDao.getInstance(ctx)!!.count()
    private fun ownItemUses() = listOf(KeyPopupOverrides.PREF, KeyPopupOverrides.PREF_SELECTED_SET,
        Settings.PREF_LAYOUT_PREFIX + LayoutType.SYMBOLS.name, Settings.PREF_THEME_COLORS, Settings.PREF_KEY_FONT).map { own(2, it) }

    @Test fun `the reset runs, and each keyboard keeps which learned words are its own`() {
        real.edit { putInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, 999); putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false) }
        reset(ResetKeep(words = true, clipboard = true, own = true))
        assertFalse(real.contains(Settings.PREF_KEY_LONGPRESS_TIMEOUT))
        // (each keyboard its own learned words first, if not done yet; that bookkeeping isn't a setting)
        assertTrue(helium314.keyboard.latin.personalization.LearnedKeyboards.isDone(real))
        assertTrue(real.contains(helium314.keyboard.latin.personalization.LearnedKeyboards.PREF_POOLS))
    }

    @Test fun `nothing ticked - everything reset - keyboards, own items and files, pictures, palettes, clipboard pins too`() {
        phone()
        reset(ResetKeep())
        assertEquals(1, listed().size, "back to the one default keyboard: ${listed()}")
        val fresh = storedEnabled(real).single().keyboardId
        assertNotNull(fresh, "the default: a new keyboard with its own id")
        assertTrue(fresh != kid(1) && fresh != kid(2), fresh)
        for (key in listOf(KeyPopupOverrides.PREF_SETS, AppearanceLooks.PREF, LayoutPresets.PREF,
                Settings.PREF_USER_COLORS_PREFIX + "Warm", "separate_settings_per_keyboard",
                Settings.PREF_KEY_LONGPRESS_TIMEOUT) + ownItemUses())
            assertFalse(real.contains(key), key)
        assertFalse(real.all.keys.any { KeyboardProfiles.splitOwnKey(it)?.first == kid(2) }, "${real.all.keys}")
        for (file in listOf(picture(2), libraryPicture, font, layoutFile)) assertFalse(file.exists(), file.path)
        assertEquals(0, clips())
    }

    @Test fun `keyboards alone - the list stays, settings and own items go`() {
        phone()
        reset(ResetKeep(keyboards = true))
        assertEquals(listOf(a.toPref(), b.toPref()), listed())
        assertEquals(listOf(a, b), storedEnabled(real), "which set is whose stays: the ids")
        assertFalse(real.contains(KeyPopupOverrides.PREF_SETS))
        for (key in ownItemUses() + own(2, Settings.PREF_KEY_LONGPRESS_TIMEOUT)) assertFalse(real.contains(key), key)
        for (file in listOf(picture(2), libraryPicture, font, layoutFile)) assertFalse(file.exists(), file.path)
        assertEquals(0, clips())
    }

    @Test fun `clipboard alone - the clips stay, everything else goes`() {
        phone()
        reset(ResetKeep(clipboard = true))
        assertEquals(1, clips())
        assertEquals(1, listed().size)
        assertFalse(font.exists())
    }

    @Test fun `own items alone - saved items and files stay, the reset keyboards' uses and pictures go`() {
        phone()
        reset(ResetKeep(own = true))
        for (key in listOf(KeyPopupOverrides.PREF_SETS, AppearanceLooks.PREF, LayoutPresets.PREF, Settings.PREF_USER_COLORS_PREFIX + "Warm"))
            assertTrue(real.contains(key), key)
        for (file in listOf(libraryPicture, font, layoutFile)) assertTrue(file.exists(), file.path)
        assertFalse(picture(2).exists(), "a reset keyboard is a new one: no old picture")
        assertEquals(1, listed().size)
        assertFalse(real.all.keys.any { KeyboardProfiles.splitOwnKey(it)?.first == kid(2) }, "${real.all.keys}")
        assertEquals(0, clips())
    }

    @Test fun `own items with keyboards - each keyboard keeps its popups, layout choice, pictures, palette and font, not its settings`() {
        phone()
        reset(ResetKeep(keyboards = true, own = true))
        assertEquals(listOf(a.toPref(), b.toPref()), listed())
        assertEquals(listOf(a, b), storedEnabled(real))
        assertEquals("{\"a\":\"b\"}", real.getString(own(2, KeyPopupOverrides.PREF), null))
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(symbols, real.getString(own(2, Settings.PREF_LAYOUT_PREFIX + LayoutType.SYMBOLS.name), null))
        assertEquals("Warm", real.getString(own(2, Settings.PREF_THEME_COLORS), null))
        assertEquals(FontLibrary.PREFIX + "Mine.ttf", real.getString(own(2, Settings.PREF_KEY_FONT), null))
        assertTrue(picture(2).exists(), "b's picture")
        assertTrue(real.getBoolean("separate_settings_per_keyboard", false), "each keyboard reads its own set still")
        // settings: back to their defaults
        assertFalse(real.contains(own(2, Settings.PREF_THEME_COLORS_NIGHT)))
        assertFalse(real.contains(own(2, Settings.PREF_KEY_LONGPRESS_TIMEOUT)))
        assertFalse(real.contains(Settings.PREF_KEY_LONGPRESS_TIMEOUT))
    }

    // ---- chosen items applied again (owner 2026-10-11: "yes, c is good for both ticked") ----

    /** One keyboard (set k1, separate settings off) on saved Layout Work (backspace repeat 70 ms) and saved theme Sunset
     *  (key gap 3%), both tweaked since (90 ms, 5%: "Custom · from …"), and a setting of no item. */
    private fun onWorkAndSunset(): Pair<LayoutPresets.Preset, AppearanceLooks.Look> {
        useKeyboards(ctx, real, a)
        val keys = LayoutType.entries.mapNotNull { type -> a.layoutName(type)?.let { "layout:" + type.name to it } }.toMap()
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", keys + mapOf(Settings.PREF_BACKSPACE_REPEAT_INTERVAL to 70))))
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_KEY_HORIZONTAL_GAP to 3f))), now = true)
        real.edit {
            putString(own(1, LayoutPresets.PREF_SELECTED), "Work")
            putInt(own(1, Settings.PREF_BACKSPACE_REPEAT_INTERVAL), 90)
            putInt(own(1, CustomLabels.Kind.LAYOUT.key), CustomLabels.SET)
            putString(own(1, AppearanceLooks.PREF_SELECTED), "Sunset")
            putFloat(own(1, Settings.PREF_KEY_HORIZONTAL_GAP), 5f)
            putInt(own(1, CustomLabels.Kind.THEME.key), CustomLabels.SET)
            putInt(own(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT), 999)
        }
        return LayoutPresets.load(real).single() to AppearanceLooks.load(real).single()
    }

    @Test fun `keyboards and own items kept - each keyboard's saved Layout and theme are applied again, tweaks undone`() {
        val (work, sunset) = onWorkAndSunset()
        reset(ResetKeep(keyboards = true, own = true))
        assertEquals(70, real.getInt(own(1, Settings.PREF_BACKSPACE_REPEAT_INTERVAL), 0), "the Layout's value again")
        assertEquals(3f, real.getFloat(own(1, Settings.PREF_KEY_HORIZONTAL_GAP), 0f), "the theme's value again")
        assertEquals("Work", real.getString(own(1, LayoutPresets.PREF_SELECTED), null))
        assertEquals("Sunset", real.getString(own(1, AppearanceLooks.PREF_SELECTED), null))
        // the rows show Work and Sunset, no "Custom" label
        assertFalse(real.contains(own(1, CustomLabels.Kind.LAYOUT.key)))
        assertFalse(real.contains(own(1, CustomLabels.Kind.THEME.key)))
        val keyboard = SubtypeSettings.getEnabledSubtypes(true).first().toSettingsSubtype()
        assertFalse(LayoutPresets.isTweaked(ctx, keyboard, work), "the Layouts row would say Custom")
        assertFalse(AppearanceLooks.isTweaked(ctx, sunset), "the Themes row would say Custom")
        // a setting of no item: its default
        assertFalse(real.contains(own(1, Settings.PREF_KEY_LONGPRESS_TIMEOUT)))
    }

    @Test fun `keyboards and own items kept - a switched-off keyboard's saved Layout and theme are applied again too`() {
        // a listed; b (set k2) switched off (in the added list only) on its saved Layout Work and theme Sunset, tweaked
        useKeyboards(ctx, real, a)
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b))).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        val keys = LayoutType.entries.mapNotNull { type -> b.layoutName(type)?.let { "layout:" + type.name to it } }.toMap()
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Work", keys + mapOf(Settings.PREF_BACKSPACE_REPEAT_INTERVAL to 70))))
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_KEY_HORIZONTAL_GAP to 3f))), now = true)
        real.edit {
            putString(own(2, LayoutPresets.PREF_SELECTED), "Work")
            putInt(own(2, Settings.PREF_BACKSPACE_REPEAT_INTERVAL), 90)
            putString(own(2, AppearanceLooks.PREF_SELECTED), "Sunset")
            putFloat(own(2, Settings.PREF_KEY_HORIZONTAL_GAP), 5f)
        }
        reset(ResetKeep(keyboards = true, own = true))
        assertEquals(listOf(a.toPref()), listed(), "b stays switched off")
        assertEquals(70, real.getInt(own(2, Settings.PREF_BACKSPACE_REPEAT_INTERVAL), 0), "the Layout's value again")
        assertEquals(3f, real.getFloat(own(2, Settings.PREF_KEY_HORIZONTAL_GAP), 0f), "the theme's value again")
        assertEquals("Work", real.getString(own(2, LayoutPresets.PREF_SELECTED), null))
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
    }

    @Test fun `keyboards alone - the chosen Layout and theme go, their settings back to the defaults`() {
        onWorkAndSunset()
        reset(ResetKeep(keyboards = true))
        for (key in listOf(Settings.PREF_BACKSPACE_REPEAT_INTERVAL, Settings.PREF_KEY_HORIZONTAL_GAP,
                LayoutPresets.PREF_SELECTED, AppearanceLooks.PREF_SELECTED, Settings.PREF_KEY_LONGPRESS_TIMEOUT))
            assertFalse(real.contains(own(1, key)), key)
        assertFalse(real.contains(LayoutPresets.PREF))
    }

    @Test fun `which stored values count as a keyboard's use of own items`() {
        val keep = ResetKeep(keyboards = true, own = true)
        val palettes = setOf("Warm")
        assertTrue(keptOnReset(own(3, Settings.PREF_THEME_COLORS), keep, "Warm", palettes))
        assertFalse(keptOnReset(own(3, Settings.PREF_THEME_COLORS), keep, "dark", palettes), "a built-in palette")
        assertTrue(keptOnReset(own(3, Settings.PREF_HINT_FONT), keep, FontLibrary.PREFIX + "x.ttf"))
        assertFalse(keptOnReset(own(3, Settings.PREF_HINT_FONT), keep, "serif"), "a system font")
        assertTrue(keptOnReset(own(3, Settings.PREF_LAYOUT_PREFIX + LayoutType.MAIN.name), keep, "custom.Latn.x."))
        assertFalse(keptOnReset(own(3, Settings.PREF_LAYOUT_PREFIX + LayoutType.MAIN.name), keep, "qwerty"), "a built-in layout")
        assertFalse(keptOnReset(own(3, KeyPopupOverrides.PREF), ResetKeep(own = true), "{}"), "the keyboard is reset")
        assertFalse(keptOnReset(own(3, KeyPopupOverrides.PREF), ResetKeep(keyboards = true), "{}"), "own items are reset")
        // a keyboard's languages go with the keyboards
        assertTrue(keptOnReset(own(3, "language_priority_de"), ResetKeep(keyboards = true)))
        assertFalse(keptOnReset(own(3, "language_priority_de"), ResetKeep(own = true)))
    }

    // ---- the dialog ----

    private fun showDialog(backedUp: Boolean = false, onReset: (ResetKeep) -> Unit = {}, onBackup: () -> Unit = {}) {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { FactoryResetDialog(backedUp = backedUp, backupRunning = false, onDismiss = {}, onBackup = onBackup, onReset = onReset) }
        }
    }

    @Test fun `the dialog - its texts, all four boxes unticked, Cancel left of Reset, Reset with nothing ticked keeps nothing`() {
        var picked: ResetKeep? = null
        showDialog(onReset = { picked = it })
        compose.onNodeWithText("All settings go back to their defaults. This can't be undone.").assertExists()
        compose.onNodeWithText("Select items you DON'T want to reset:").assertExists()
        for (text in listOf("Keyboards and languages", "Learned and blacklisted words", "Clipboard history",
                "Saved layouts & popups, themes & color palettes, added dictionaries, and loaded fonts and pictures"))
            compose.onNodeWithText(text).assertExists()
        val boxes = compose.onAllNodes(isToggleable())
        assertEquals(4, boxes.fetchSemanticsNodes().size)
        for (i in 0 until 4) boxes[i].assertIsOff()
        val cancel = compose.onNodeWithText(ctx.getString(android.R.string.cancel)).fetchSemanticsNode().boundsInRoot
        val reset = compose.onNodeWithText("Reset").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.left < reset.left, "Cancel left of Reset: $cancel / $reset")
        compose.onNodeWithText("Backup").assertIsEnabled()
        compose.onNodeWithText("Reset").performClick()
        assertEquals(ResetKeep(), picked)
    }

    @Test fun `the dialog - a box ticked is kept`() {
        var picked: ResetKeep? = null
        showDialog(onReset = { picked = it })
        compose.onNodeWithText("Learned and blacklisted words").performClick()
        compose.onNodeWithText("Reset").performClick()
        assertEquals(ResetKeep(words = true), picked)
    }

    @Test fun `the dialog - Backup asks for the backup`() {
        var asked = 0
        showDialog(onBackup = { asked++ })
        compose.onNodeWithText("Backup").performClick()
        assertEquals(1, asked)
    }

    @Test fun `the dialog - Backup is grey once a backup is written`() {
        showDialog(backedUp = true)
        compose.onNodeWithText("Backup").assertIsNotEnabled()
        compose.onNodeWithText("Reset").assertIsEnabled()
    }

    @Test fun `a backup reports done only when written - Backup goes grey then, not after a failure`() {
        val zip = File(ctx.cacheDir, "factory-reset-backup.zip")
        try {
            var result: Boolean? = null
            assertTrue(runBackup(ctx, {}, onDone = { result = it }) { FileOutputStream(zip) })
            idle { result != null && !BackupRestoreJob.isRunning }
            assertEquals(true, result, "written")
            assertTrue(zip.length() > 0)

            result = null
            var error: String? = null
            assertTrue(runBackup(ctx, { error = it }, onDone = { result = it }) { throw java.io.IOException("no space") })
            idle { result != null && !BackupRestoreJob.isRunning }
            assertEquals(false, result, "failed")
            assertTrue(error!!.contains("no space"))

            result = null
            assertTrue(runBackup(ctx, {}, onDone = { result = it }) { null }) // (the file couldn't be opened)
            idle { result != null && !BackupRestoreJob.isRunning }
            assertEquals(false, result)
        } finally { zip.delete() }
        assertNull(BackupRestoreJob.progress)
    }

    private fun idle(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
    }
}

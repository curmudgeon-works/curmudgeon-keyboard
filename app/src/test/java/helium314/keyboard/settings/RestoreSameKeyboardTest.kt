// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.restoreDialogMatchFrom
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review item C1 (owner 2026-10-11): restoring a backup keyboard that is on the phone (same language, name and copy
 * number) replaces the phone's, even when it changed since the backup (its accents level here); it never comes in as a
 * second keyboard.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreSameKeyboardTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "same-keyboard-${System.nanoTime()}.zip") }
    private lateinit var base: SettingsSubtype
    private lateinit var phone: SettingsSubtype
    private lateinit var backup: SettingsSubtype

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        base = available("en-US")
        phone = base.with(ExtraValue.MORE_POPUPS, "all").toPref().toSettingsSubtype()
        backup = base.with(ExtraValue.MORE_POPUPS, "main").with(ExtraValue.SECONDARY_LOCALES, "de").toPref().toSettingsSubtype()
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** The phone: [phone] with its number row off and gaps at 1; the backup: [backup], number row on and gaps at 3. */
    private fun phoneAndBackup() {
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, phone.toPref()).commit() // (a keyboard of the user's)
        useKeyboards(ctx, real, phone)
        assertEquals(listOf(phone), enabled())
        real.edit().putBoolean(Settings.PREF_SHOW_NUMBER_ROW, false).putFloat(Settings.PREF_KEY_HORIZONTAL_GAP, 1f).commit()
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to backup.toPref(), Settings.PREF_SELECTED_SUBTYPE to backup.toPref(),
                Settings.PREF_SHOW_NUMBER_ROW to true, Settings.PREF_KEY_HORIZONTAL_GAP to 3f,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
        }
    }

    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    private fun reads(keyboard: SettingsSubtype) = KeyboardProfiles.effectiveSettingsIn(real.all, keyboard)

    @Test fun `a changed keyboard on the phone is replaced by the backup's, not added a second time`() {
        phoneAndBackup()
        restoreKeyboardsFrom(ctx, zip, listOf(backup), withSettings = true)
        assertEquals(listOf(backup), enabled())
        assertEquals(true, reads(backup)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(3f, reads(backup)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `the same keyboard is the same language, name and copy number, and its differences are listed`() {
        assertTrue(RestoreMatch.sameKeyboard(phone, backup))
        assertFalse(RestoreMatch.sameKeyboard(phone, backup.with(ExtraValue.KEYBOARD_NAME, "Work")))
        assertFalse(RestoreMatch.sameKeyboard(phone, backup.with(ExtraValue.KEYBOARD_COPY, "2")))
        assertFalse(RestoreMatch.sameKeyboard(phone, available("de-DE")))
        // accents "all" on the phone, "main" in the backup: the same keyboard, one accents row
        val accentsOnly = base.with(ExtraValue.MORE_POPUPS, "main").toPref().toSettingsSubtype()
        assertEquals(mapOf(accentsOnly to phone), RestoreMatch.match(listOf(accentsOnly), listOf(phone)))
        val differences = RestoreMatch.definitionDifferences(phone, accentsOnly)
        assertEquals(listOf(RestoreMatch.Difference.Definition(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS), "all", "main")), differences)
        assertEquals("Accents: this phone Add all available variants, backup Add very common variants (default)",
            RestoreMatch.rowText(ctx, differences.single(), accentsOnly))
        // and the languages too for the test's backup
        assertEquals(listOf(RestoreMatch.Part.Extra(ExtraValue.SECONDARY_LOCALES), RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)),
            RestoreMatch.definitionDifferences(phone, backup).map { it.part })
        // an identical one: nothing differs; another language: not matched
        assertEquals(emptyList(), RestoreMatch.definitionDifferences(phone, phone))
        assertEquals(emptyMap(), RestoreMatch.match(listOf(available("de-DE")), listOf(phone)))
    }

    @Test fun `the menus whose settings differ, and only those`() {
        val phoneValues = mapOf<String, Any?>(Settings.PREF_SHOW_NUMBER_ROW to false, Settings.PREF_KEY_HORIZONTAL_GAP to 1f)
        val backupValues = mapOf<String, Any?>(Settings.PREF_SHOW_NUMBER_ROW to false, Settings.PREF_KEY_HORIZONTAL_GAP to 3f)
        assertEquals(listOf(RestoreMatch.Difference.Menu(KeyboardProfiles.Group.APPEARANCE)), RestoreMatch.menuDifferences(phoneValues, backupValues))
        assertEquals(emptyList(), RestoreMatch.menuDifferences(phoneValues, phoneValues))
        assertEquals("Appearance settings", RestoreMatch.rowText(ctx, RestoreMatch.Difference.Menu(KeyboardProfiles.Group.APPEARANCE), backup))
    }

    @Test fun `with only accents chosen, the keyboard takes the backup's accents and keeps the phone's languages and settings`() {
        phoneAndBackup()
        restoreKeyboardsFrom(ctx, zip, listOf(backup), withSettings = true, replaces = mapOf(backup to
            RestoreMatch.Replace(phone, parts = setOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)), menus = emptySet())))
        val restored = base.with(ExtraValue.MORE_POPUPS, "main").toPref().toSettingsSubtype()
        assertEquals(listOf(restored), enabled())
        assertEquals(false, reads(restored)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(1f, reads(restored)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `with only Appearance chosen, the keyboard keeps its definition and other settings and takes the backup's look`() {
        phoneAndBackup()
        val id = KeyboardProfiles.idFor(real, phone)
        restoreKeyboardsFrom(ctx, zip, listOf(backup), withSettings = true, replaces = mapOf(backup to
            RestoreMatch.Replace(phone, parts = emptySet(), menus = setOf(KeyboardProfiles.Group.APPEARANCE))))
        assertEquals(listOf(phone), enabled())
        assertEquals(id, KeyboardProfiles.idFor(real, phone), "its own set")
        assertEquals(false, reads(phone)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(3f, reads(phone)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `every keyboard chosen, one only in parts, is restored keyboard by keyboard and keeps its set id`() {
        phoneAndBackup()
        val id = KeyboardProfiles.idFor(real, phone)
        // (the backup's whole set would replace the phone's: not with a keyboard answered "Choose what to replace")
        restoreChosenFrom(ctx, zip, listOf(backup), settings = true, learnedWords = false, io = LearnedStoreIo.Native,
            replaces = mapOf(backup to RestoreMatch.Replace(phone, parts = setOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)), menus = emptySet())))
        val restored = base.with(ExtraValue.MORE_POPUPS, "main").toPref().toSettingsSubtype()
        assertEquals(listOf(restored), enabled())
        assertEquals(id, KeyboardProfiles.idFor(real, restored), "the set moved with the keyboard")
        assertEquals(false, reads(restored)[Settings.PREF_SHOW_NUMBER_ROW])
    }

    @Test fun `a changed keyboard whose custom layout comes in numbered replaces the phone's, naming the numbered one`() {
        // (review C1 with B4: the phone's en-US keyboard has other accents than the backup's, and the number row "My
        // numrow" both use has other keys on the phone; a German keyboard of the phone uses that "My numrow" too)
        val type = LayoutType.NUMBER_ROW
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        val numrow2 = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
        val folder = LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).parentFile!!
        try {
            folder.deleteRecursively(); folder.mkdirs()
            LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).writeText("A")
            LayoutUtilsCustom.onLayoutFileChanged()
            val phoneKeyboard = phone.withLayout(type, numrow)
            val german = available("de-DE").withLayout(type, numrow)
            val backupKeyboard = base.with(ExtraValue.MORE_POPUPS, "main").withLayout(type, numrow)
            real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(phoneKeyboard, german))).commit()
            useKeyboards(ctx, real, phoneKeyboard, german)
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("preferences.json"))
                settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to backupKeyboard.toPref(), Settings.PREF_ADDITIONAL_SUBTYPES to backupKeyboard.toPref(),
                    helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                    helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
                z.closeEntry()
                z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$numrow")); z.write("B".toByteArray()); z.closeEntry()
            }
            // the dialog: on the phone, and differing in the accents and the number row (the one it would come in as)
            val (incoming, matched) = restoreDialogMatchFrom(ctx, zip)
            assertEquals(mapOf(backupKeyboard to phoneKeyboard), matched)
            assertEquals(listOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS), RestoreMatch.Part.Layout(type)),
                RestoreMatch.definitionDifferences(phoneKeyboard, incoming.getValue(backupKeyboard)).map { it.part })
            assertEquals(numrow2, incoming.getValue(backupKeyboard).layoutName(type), "the name it would come in as")
            assertFalse(LayoutUtilsCustom.getLayoutFile(numrow2, type, ctx).exists(), "the dialog writes nothing")
            // "Replace with the backup's"
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true,
                replaces = mapOf(backupKeyboard to RestoreMatch.Replace(phoneKeyboard)))
            val restored = backupKeyboard.withLayout(type, numrow2)
            assertEquals(listOf(restored, german), enabled(), "one en-US keyboard, in its place, on My numrow 2")
            assertEquals("A", LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).readText(), "the phone's My numrow kept its keys")
            assertEquals("B", LayoutUtilsCustom.getLayoutFile(numrow2, type, ctx).readText())
            // again: the dialog finds the same keyboard, nothing differing; restoring changes nothing
            val (again, matchedAgain) = restoreDialogMatchFrom(ctx, zip)
            assertEquals(mapOf(backupKeyboard to restored), matchedAgain)
            assertEquals(emptyList(), RestoreMatch.definitionDifferences(restored, again.getValue(backupKeyboard)))
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true)
            assertEquals(listOf(restored, german), enabled())
            assertEquals(setOf(numrow, numrow2), folder.list()!!.toSet())
        } finally { folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged() }
    }

    @Test fun `choosing parts, a number row taken from the backup comes in numbered, one kept is the phone's file`() {
        val type = LayoutType.NUMBER_ROW
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        val numrow2 = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
        val folder = LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).parentFile!!
        val phoneKeyboard = phone.withLayout(type, numrow)
        val backupKeyboard = base.with(ExtraValue.MORE_POPUPS, "main").withLayout(type, numrow)
        fun setUpBoth() {
            folder.deleteRecursively(); folder.mkdirs()
            LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).writeText("A")
            LayoutUtilsCustom.onLayoutFileChanged()
            real.edit().clear().putString(Settings.PREF_ADDITIONAL_SUBTYPES, phoneKeyboard.toPref()).commit()
            KeyboardProfiles.loadGroups(real)
            useKeyboards(ctx, real, phoneKeyboard)
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("preferences.json"))
                settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to backupKeyboard.toPref(), Settings.PREF_ADDITIONAL_SUBTYPES to backupKeyboard.toPref(),
                    helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                    helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
                z.closeEntry()
                z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$numrow")); z.write("B".toByteArray()); z.closeEntry()
            }
        }
        try {
            // only the number row ticked: the phone's accents, the backup's keys as "My numrow 2"
            setUpBoth()
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true, replaces = mapOf(backupKeyboard to
                RestoreMatch.Replace(phoneKeyboard, parts = setOf(RestoreMatch.Part.Layout(type)), menus = emptySet())))
            assertEquals(listOf(phoneKeyboard.withLayout(type, numrow2).toPref().toSettingsSubtype()), enabled())
            assertEquals("A", LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).readText())
            assertEquals("B", LayoutUtilsCustom.getLayoutFile(numrow2, type, ctx).readText())
            // only the accents ticked: the phone's "My numrow", and no file comes in
            setUpBoth()
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true, replaces = mapOf(backupKeyboard to
                RestoreMatch.Replace(phoneKeyboard, parts = setOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)), menus = emptySet())))
            assertEquals(listOf(backupKeyboard.toPref().toSettingsSubtype()), enabled())
            assertEquals(setOf(numrow), folder.list()!!.toSet())
        } finally { folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged() }
    }
}

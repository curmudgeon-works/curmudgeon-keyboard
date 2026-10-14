// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.kid
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
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
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.withId
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
 * Review item C1 (owner 2026-10-11): restoring a backup keyboard into the phone's keyboard of its language replaces it,
 * even when it changed since the backup (its accents level here); it never comes in as a second keyboard. (owner
 * 2026-10-11: restore matches by language and asks: "merged into" is the user's answer now, [RestoreDecision.Merge])
 * (owner 2026-10-11: keyboards have unique ids) The phone's keyboard is "k1", the backup's "k2" (another phone's); a
 * merge keeps the phone's id, and nothing is blocked as "the same" any more.
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
        phone = base.with(ExtraValue.MORE_POPUPS, "all").withId(1)
        backup = base.with(ExtraValue.MORE_POPUPS, "main").with(ExtraValue.SECONDARY_LOCALES, "de").withId(2)
    }
    @After fun tearDown() { zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    /** The phone: [phone] with its number row off and gaps at 1; the backup: [backup], number row on and gaps at 3. */
    private fun phoneAndBackup() {
        // (separate settings on: the keyboard's own set is read; owner 2026-10-11: R7, a restore never turns separate
        // settings on)
        // (the list stored with it, before anything else: owner 2026-10-11, the first start stores a default keyboard, and
        // a list replaced after a write gave that default keyboard a set made it a switched-off keyboard of this phone)
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, phone.toPref())
            .putString(Settings.PREF_ENABLED_SUBTYPES, phone.toPref()).putString(Settings.PREF_SELECTED_SUBTYPE, phone.toPref())
            .putBoolean("separate_settings_per_keyboard", true).commit() // (a keyboard of the user's)
        useKeyboards(ctx, real, phone)
        assertEquals(listOf(phone), enabled())
        // (each keyboard's settings its own set, the phone's and the backup's; owner 2026-10-11: no separate common set;
        // the first keyboard's own settings are the common ones)
        real.edit().putBoolean(idKey(1, Settings.PREF_SHOW_NUMBER_ROW), false)
            .putFloat(idKey(1, Settings.PREF_KEY_HORIZONTAL_GAP), 1f).commit()
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to backup.toPref(), Settings.PREF_ADDITIONAL_SUBTYPES to backup.toPref(),
                Settings.PREF_SELECTED_SUBTYPE to backup.toPref(), KeyboardIdUpgrade.DONE to true,
                idKey(2, Settings.PREF_SHOW_NUMBER_ROW) to true, idKey(2, Settings.PREF_KEY_HORIZONTAL_GAP) to 3f,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
            z.closeEntry()
        }
    }

    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    private fun reads(keyboard: SettingsSubtype) = KeyboardProfiles.effectiveSettingsIn(real.all, keyboard)
    /** [keyboard] with the phone keyboard's id, "k1" (what a merge into it keeps). */
    private fun asPhone(keyboard: SettingsSubtype) = keyboard.withIdOf(phone).toPref().toSettingsSubtype()

    @Test fun `a changed keyboard on the phone is replaced by the backup's, not added a second time`() {
        phoneAndBackup()
        // (owner 2026-10-11: restore matches by language and asks: answered "merge", everything ticked)
        restoreKeyboardsFrom(ctx, zip, listOf(backup), withSettings = true, decisions = mapOf(backup to RestoreDecision.Merge(phone)))
        val restored = asPhone(backup)
        assertEquals(listOf(restored), enabled(), "the backup's definition, the phone's id")
        assertEquals(true, reads(restored)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(3f, reads(restored)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `a keyboard of the same language is asked about, and its differences are listed`() {
        // (owner 2026-10-11: restore matches by language and asks; before, the same name and copy number were the same
        // keyboard, another name or copy number a new one)
        phoneAndBackup()
        val plan = restorePlanFrom(ctx, zip)
        assertTrue(plan.asks(backup))
        assertTrue(plan.asks(backup.with(ExtraValue.KEYBOARD_NAME, "Work")))
        assertTrue(plan.asks(backup.with(ExtraValue.KEYBOARD_COPY, "2")))
        assertFalse(plan.asks(available("de-DE")))
        // accents "all" on the phone, "main" in the backup: offered, one accents row
        val accentsOnly = base.with(ExtraValue.MORE_POPUPS, "main").toPref().toSettingsSubtype()
        assertEquals(listOf(phone), plan.candidates(accentsOnly, emptyMap()))
        val differences = RestoreMatch.definitionDifferences(phone, accentsOnly)
        assertEquals(listOf(RestoreMatch.Difference.Definition(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS), "all", "main")), differences)
        assertEquals("Accents: this device Add all available variants, backup Add very common variants (default)",
            RestoreMatch.rowText(ctx, differences.single(), accentsOnly))
        // and the languages too for the test's backup
        assertEquals(listOf(RestoreMatch.Part.Extra(ExtraValue.SECONDARY_LOCALES), RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)),
            RestoreMatch.definitionDifferences(phone, backup).map { it.part })
        // an identical one: nothing differs; another language: not offered
        assertEquals(emptyList(), RestoreMatch.definitionDifferences(phone, phone))
        assertEquals(emptyList(), plan.candidates(available("de-DE"), emptyMap()))
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
        restoreKeyboardsFrom(ctx, zip, listOf(backup), withSettings = true, decisions = mapOf(backup to
            RestoreDecision.Merge(phone, parts = setOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)), menus = emptySet())))
        val restored = base.with(ExtraValue.MORE_POPUPS, "main").withId(1)
        assertEquals(listOf(restored), enabled())
        assertEquals(false, reads(restored)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(1f, reads(restored)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `with only Appearance chosen, the keyboard keeps its definition and other settings and takes the backup's look`() {
        phoneAndBackup()
        restoreKeyboardsFrom(ctx, zip, listOf(backup), withSettings = true, decisions = mapOf(backup to
            RestoreDecision.Merge(phone, parts = emptySet(), menus = setOf(KeyboardProfiles.Group.APPEARANCE))))
        assertEquals(listOf(phone), enabled())
        assertEquals(kid(1), enabled().single().keyboardId, "its own set")
        assertEquals(false, reads(phone)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(3f, reads(phone)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `every keyboard chosen, one only in parts, is restored keyboard by keyboard and keeps its set id`() {
        phoneAndBackup()
        // (the backup's whole set would replace the phone's: not with a keyboard answered "Choose what to replace")
        restoreChosenFrom(ctx, zip, listOf(backup), settings = true, learnedWords = false, io = LearnedStoreIo.Native,
            decisions = mapOf(backup to RestoreDecision.Merge(phone, parts = setOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)), menus = emptySet())))
        val restored = base.with(ExtraValue.MORE_POPUPS, "main").withId(1)
        assertEquals(listOf(restored), enabled())
        // (owner 2026-10-11: keyboards have unique ids; the id is kept, so its set is still its own, nothing moved)
        assertEquals(kid(1), enabled().single().keyboardId, "the set stayed with the keyboard")
        assertEquals(false, reads(restored)[Settings.PREF_SHOW_NUMBER_ROW])
    }

    // (owner 2026-10-12: the backup's key layout keeps its name, the phone's is renamed "My numrow 2" first, its users
    // following; was: the backup's came in as "My numrow 2")
    @Test fun `a changed keyboard whose custom layout clashes replaces the phone's, the phone's layout numbered for its other users`() {
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
            val german = available("de-DE").withId(3).withLayout(type, numrow)
            val backupKeyboard = base.with(ExtraValue.MORE_POPUPS, "main").withLayout(type, numrow).withId(2)
            // (the list stored with the registration, one write: a reload between them, Settings' listener on the added
            // keyboards, gave the default keyboard stored at the start records, a switched-off keyboard; the full run 2026-10-11)
            real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(phoneKeyboard, german)))
                .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(phoneKeyboard, german)))
                .putString(Settings.PREF_SELECTED_SUBTYPE, phoneKeyboard.toPref()).commit()
            useKeyboards(ctx, real, phoneKeyboard, german)
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("preferences.json"))
                settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to backupKeyboard.toPref(), Settings.PREF_ADDITIONAL_SUBTYPES to backupKeyboard.toPref(),
                    KeyboardIdUpgrade.DONE to true, helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                    helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
                z.closeEntry()
                z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$numrow")); z.write("B".toByteArray()); z.closeEntry()
            }
            // the dialog: on the phone, and differing in the accents and the number row (same name, other keys)
            val plan = restorePlanFrom(ctx, zip)
            val incoming = plan.incoming
            assertEquals(listOf(phoneKeyboard), plan.candidates(backupKeyboard, emptyMap()))
            assertEquals(listOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS), RestoreMatch.Part.Layout(type)),
                RestoreMatch.definitionDifferences(phoneKeyboard, incoming.getValue(backupKeyboard), plan.sameLayout).map { it.part })
            assertEquals(numrow, incoming.getValue(backupKeyboard).layoutName(type), "the backup's name")
            assertEquals(numrow2, plan.items.keyLayouts.deviceRename(type, numrow), "the phone's becomes My numrow 2")
            assertFalse(LayoutUtilsCustom.getLayoutFile(numrow2, type, ctx).exists(), "the dialog writes nothing")
            // merged, everything ticked (was "Replace with the backup's")
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true,
                decisions = mapOf(backupKeyboard to RestoreDecision.Merge(phoneKeyboard)))
            val restored = asPhone(backupKeyboard)
            val germanNow = german.withLayout(type, numrow2)
            assertEquals(listOf(restored, germanNow), enabled(), "one en-US keyboard, in its place, on My numrow; German follows its keys")
            assertEquals("B", LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).readText())
            assertEquals("A", LayoutUtilsCustom.getLayoutFile(numrow2, type, ctx).readText(), "the phone's keys, renamed")
            // again: the dialog finds the same keyboard, nothing differing; restoring changes nothing
            val againPlan = restorePlanFrom(ctx, zip)
            assertEquals(listOf(restored), againPlan.candidates(backupKeyboard, emptyMap()))
            assertEquals(emptyList(), RestoreMatch.definitionDifferences(restored, againPlan.incoming.getValue(backupKeyboard), againPlan.sameLayout))
            // unanswered: comes in as a keyboard of its own with the backup's id, on the "My numrow" it brought before
            // (owner 2026-10-11: keyboards have unique ids; was: the same as the phone's, not restored)
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true)
            assertEquals(setOf(restored, germanNow, backupKeyboard), enabled().toSet())
            assertEquals(3, enabled().size)
            assertEquals(setOf(numrow, numrow2), folder.list()!!.toSet())
        } finally { folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged() }
    }

    // (owner 2026-10-12: the backup's keeps the name, the phone's file is renamed first; was: the backup's numbered)
    @Test fun `choosing parts, a number row taken from the backup keeps its name, one kept is the phone's file`() {
        val type = LayoutType.NUMBER_ROW
        val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
        val numrow2 = LayoutUtilsCustom.getLayoutName("My numrow 2", type)
        val folder = LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).parentFile!!
        val phoneKeyboard = phone.withLayout(type, numrow)
        val backupKeyboard = base.with(ExtraValue.MORE_POPUPS, "main").withLayout(type, numrow).withId(2)
        fun setUpBoth() {
            folder.deleteRecursively(); folder.mkdirs()
            LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).writeText("A")
            LayoutUtilsCustom.onLayoutFileChanged()
            // (the list stored with the registration, one write: a reload between them, Settings' listener on the added
            // keyboards, gave the default keyboard stored at the start records, a switched-off keyboard; the full run 2026-10-11)
            real.edit().clear().putString(Settings.PREF_ADDITIONAL_SUBTYPES, phoneKeyboard.toPref())
                .putString(Settings.PREF_ENABLED_SUBTYPES, phoneKeyboard.toPref()).putString(Settings.PREF_SELECTED_SUBTYPE, phoneKeyboard.toPref()).commit()
            KeyboardProfiles.loadGroups(real)
            useKeyboards(ctx, real, phoneKeyboard)
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("preferences.json"))
                settingsToJsonStream(mapOf(Settings.PREF_ENABLED_SUBTYPES to backupKeyboard.toPref(), Settings.PREF_ADDITIONAL_SUBTYPES to backupKeyboard.toPref(),
                    KeyboardIdUpgrade.DONE to true, helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                    helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true), z)
                z.closeEntry()
                z.putNextEntry(ZipEntry("unprotected/layouts/${type.name.lowercase()}/$numrow")); z.write("B".toByteArray()); z.closeEntry()
            }
        }
        try {
            // only the number row ticked: the phone's accents, the backup's keys as "My numrow", the phone's renamed
            setUpBoth()
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true, decisions = mapOf(backupKeyboard to
                RestoreDecision.Merge(phoneKeyboard, parts = setOf(RestoreMatch.Part.Layout(type)), menus = emptySet())))
            assertEquals(listOf(phoneKeyboard.withLayout(type, numrow).toPref().toSettingsSubtype()), enabled())
            assertEquals("B", LayoutUtilsCustom.getLayoutFile(numrow, type, ctx).readText())
            assertEquals("A", LayoutUtilsCustom.getLayoutFile(numrow2, type, ctx).readText())
            // only the accents ticked: the phone's "My numrow", and no file comes in
            setUpBoth()
            restoreKeyboardsFrom(ctx, zip, listOf(backupKeyboard), withSettings = true, decisions = mapOf(backupKeyboard to
                RestoreDecision.Merge(phoneKeyboard, parts = setOf(RestoreMatch.Part.Extra(ExtraValue.MORE_POPUPS)), menus = emptySet())))
            assertEquals(listOf(asPhone(backupKeyboard)), enabled())
            assertEquals(setOf(numrow), folder.list()!!.toSet())
        } finally { folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged() }
    }
}

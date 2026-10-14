// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.idKey
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.settings.screens.customizePopupsSaved
import helium314.keyboard.settings.screens.deletePopupSet
import helium314.keyboard.settings.screens.layoutFilesSaved
import helium314.keyboard.settings.screens.paletteEditorSaved
import helium314.keyboard.storeKeyboards
import helium314.keyboard.storedEnabled
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review E4 (owner 2026-10-11: "On app exit you should only honour the sub-screen's save and discard all else"): a
 * sub-screen's save records only its own change; for a key holding a whole list (the saved themes, popup sets, saved
 * Layouts, the keyboard lists, the learned words' pools) the elements it changed, and leaving the app puts those onto
 * the list as the editing screen's snapshot had it. Before, the whole list was recorded, with the editing screen's own
 * unsaved changes in it: a theme deleted on Appearance stayed deleted when the palette editor had saved meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
class SubScreenOwnChangeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun own(n: Int, key: String) = idKey(n, key) // (keyboard "k<n>"'s own set)
    private val before = """[{"name":"background","auto":false,"color":-1}]"""
    private val edited = """[{"name":"background","auto":false,"color":-16777216}]"""
    private val layouts get() = File(DeviceProtectedUtils.getFilesDir(ctx), "layouts")

    @Before fun setUp() {
        real.edit().clear().putBoolean("separate_settings_per_keyboard", true).commit()
        KeyboardProfiles.loadGroups(real)
    }
    @After fun tearDown() {
        AppearanceDraft.close(); LayoutDraft.close(); layouts.deleteRecursively()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    @Test fun `Appearance - theme Sunset deleted, palette Warm edited and saved, leaving the app brings Sunset back and keeps the Warm edit`() {
        real.edit()
            .putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", before)
            .putString(own(2, AppearanceLooks.PREF_SELECTED), "Sunset")
            .commit()
        AppearanceLooks.save(real, listOf(AppearanceLooks.Look("Sunset", mapOf(Settings.PREF_THEME_COLORS to "Cool")),
            AppearanceLooks.Look("Work", mapOf(Settings.PREF_THEME_COLORS to "Warm"))), now = true)
        AppearanceDraft.of(ctx) // Appearance opens
        AppearanceLooks.save(real, AppearanceLooks.load(real).filter { it.name != "Sunset" }, now = true) // Sunset deleted (unsaved)
        AppearanceLooks.choiceRenamed(ctx, "Sunset", null)
        // the palette editor: Warm's colors changed, theme Work keeps the old ones (as "Warm 2"), then "Save and exit"
        AppearanceDraft.paletteEditorOpenedOpen(ctx)
        real.edit().putString(Settings.PREF_USER_COLORS_PREFIX + "Warm", edited).commit()
        assertEquals("Warm 2", Palettes.afterEdit(real, "Warm", mapOf(Settings.PREF_USER_COLORS_PREFIX to before),
            emptyMap(), mapOf("Work" to Palettes.Choice.KEEP_OLD)))
        paletteEditorSaved("Warm", "Warm")
        AppearanceDraft.rejectOpen(ctx) // the app left

        val looks = AppearanceLooks.load(real)
        assertEquals(listOf("Sunset", "Work"), looks.map { it.name }) // Sunset back, in its place
        assertEquals("Cool", looks[0].values[Settings.PREF_THEME_COLORS])
        assertEquals("Warm 2", looks[1].values[Settings.PREF_THEME_COLORS]) // the editor's change to Work stays
        assertEquals("Sunset", real.getString(own(2, AppearanceLooks.PREF_SELECTED), null))
        assertEquals(edited, real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm", null)) // the Warm edit stays
        assertEquals(before, real.getString(Settings.PREF_USER_COLORS_PREFIX + "Warm 2", null))
    }

    /** Popup sets Mine and Ours; Mine deleted on Layout and Typing (unsaved), then Customize popups edits Ours and saves. */
    private fun popupSetsScenario() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, mapOf("a" to listOf("à"))),
            KeyPopupOverrides.UserSet("Ours", "all", null, mapOf("o" to listOf("ö")))))
        real.edit().putString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), "Mine").commit()
        LayoutDraft.of(ctx, "en_US:") // Layout & Typing opens
        deletePopupSet(ctx, "Mine")
        LayoutDraft.subScreenOpenedOpen(ctx) // Customize popups opens
        KeyPopupOverrides.saveSets(real, KeyPopupOverrides.loadSets(real).map {
            if (it.name == "Ours") KeyPopupOverrides.UserSet("Ours", "all", null, mapOf("o" to listOf("ö", "ó"))) else it })
        customizePopupsSaved()
    }

    private fun assertMineBackOursEdited() {
        val sets = KeyPopupOverrides.loadSets(real)
        assertEquals(listOf("Mine", "Ours"), sets.map { it.name })
        assertEquals(mapOf("a" to listOf("à")), sets[0].overrides)
        assertEquals(mapOf("o" to listOf("ö", "ó")), sets[1].overrides)
        assertEquals("Mine", real.getString(own(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
    }

    @Test fun `popup sets - set Mine deleted, set Ours edited in Customize popups and saved, leaving the app brings Mine back and keeps the Ours edit`() {
        popupSetsScenario()
        LayoutDraft.rejectOpen(ctx) // the app left
        assertMineBackOursEdited()
    }

    @Test fun `popup sets - the same after a crash, the element changes kept on disk`() {
        popupSetsScenario()
        LayoutDraft.forgetActiveForTest() // the process died
        LayoutDraft.recoverAfterCrash(ctx) // the next app start
        assertMineBackOursEdited()
    }

    @Test fun `layout files - keyboard 1 changed on Layout and Typing, the layout files screen changes keyboard 2 and saves, leaving the app undoes only keyboard 1`() {
        val one = SettingsSubtype(Locale.US, "").withId(1)
        val two = SettingsSubtype(Locale.GERMANY, "").withId(2)
        storeKeyboards(ctx, real, listOf(one, two))
        LayoutDraft.of(ctx, one.toPref()) // Layout & Typing opens for keyboard 1
        val oneChanged = one.with(ExtraValue.MORE_POPUPS, "none") // (a change of keyboard 1's definition, unsaved)
        storeKeyboards(ctx, real, listOf(oneChanged, two), selected = null)
        // the layout files screen: a layout file written, keyboard 2's definition changed with it, then Save
        LayoutDraft.subScreenOpenedOpen(ctx)
        val written = File(layouts, "number").apply { mkdirs() }.resolve("new.txt").apply { writeText("new") }
        val twoChanged = two.with(ExtraValue.MORE_POPUPS, "main")
        storeKeyboards(ctx, real, listOf(oneChanged, twoChanged), selected = null)
        layoutFilesSaved()
        LayoutDraft.rejectOpen(ctx) // the app left

        assertEquals(listOf(one.toPref(), twoChanged.toPref()), storedEnabled(real).map { it.toPref() })
        assertEquals(listOf(one.toPref(), twoChanged.toPref()),
            SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!).map { it.toPref() })
        assertTrue(written.exists()) // the file the screen saved stays
    }

    @Test fun `a list element the editing screen made and the sub-screen changed stays out - its change is undone with it`() {
        // (the parent renamed Ours to Theirs, the sub-screen edited Theirs: leaving the app, Ours as it was, no second copy)
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Ours", "all", null, mapOf("o" to listOf("ö")))))
        LayoutDraft.of(ctx, "en_US:")
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Theirs", "all", null, mapOf("o" to listOf("ö")))))
        LayoutDraft.subScreenOpenedOpen(ctx)
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Theirs", "all", null, mapOf("o" to listOf("ó")))))
        customizePopupsSaved()
        LayoutDraft.rejectOpen(ctx)
        assertEquals(listOf("Ours"), KeyPopupOverrides.loadSets(real).map { it.name })
        assertEquals(mapOf("o" to listOf("ö")), KeyPopupOverrides.loadSets(real).single().overrides)
    }

    @Test fun `a list element the sub-screen made is added, one it removed goes`() {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap()),
            KeyPopupOverrides.UserSet("Gone", "all", null, emptyMap())))
        LayoutDraft.of(ctx, "en_US:")
        deletePopupSet(ctx, "Mine") // the parent's, undone
        LayoutDraft.subScreenOpenedOpen(ctx)
        KeyPopupOverrides.saveSets(real, KeyPopupOverrides.loadSets(real).filter { it.name != "Gone" } +
            KeyPopupOverrides.UserSet("New", "all", null, mapOf("n" to listOf("ñ"))))
        customizePopupsSaved()
        LayoutDraft.rejectOpen(ctx)
        assertEquals(listOf("Mine", "New"), KeyPopupOverrides.loadSets(real).map { it.name })
    }

    // review M2: the popup keys are recorded by key, not as the whole map
    private val m1 = mapOf("a" to listOf("à", "á"), "e" to listOf("é"))

    /** Popups M1; a saved Layout applied on Layout and Typing (unsaved) writes its map M2; Customize popups changes "a". */
    private fun popupKeysScenario() {
        KeyPopupOverrides.save(real, m1)
        LayoutDraft.of(ctx, "en_US:")
        KeyPopupOverrides.save(real, mapOf("a" to listOf("ä"), "o" to listOf("ö"))) // (the Layout's popups, unsaved)
        LayoutDraft.subScreenOpenedOpen(ctx)
        KeyPopupOverrides.set(real, "a", listOf("ä", "â"))
        customizePopupsSaved()
    }

    @Test fun `popup keys - a Layout applied, one key edited and saved in Customize popups, leaving the app keeps M1 with only that key`() {
        popupKeysScenario()
        LayoutDraft.rejectOpen(ctx) // the app left
        assertEquals(mapOf("a" to listOf("ä", "â"), "e" to listOf("é")), KeyPopupOverrides.load(real))
    }

    @Test fun `popup keys - the same after a crash, the keys kept on disk`() {
        popupKeysScenario()
        LayoutDraft.forgetActiveForTest() // the process died
        LayoutDraft.recoverAfterCrash(ctx) // the next app start
        assertEquals(mapOf("a" to listOf("ä", "â"), "e" to listOf("é")), KeyPopupOverrides.load(real))
    }

    @Test fun `popup keys - a key the snapshot had none for, edited and saved, is kept`() {
        KeyPopupOverrides.save(real, m1)
        LayoutDraft.of(ctx, "en_US:")
        KeyPopupOverrides.save(real, mapOf("o" to listOf("ö"))) // (the Layout's popups, unsaved)
        LayoutDraft.subScreenOpenedOpen(ctx)
        KeyPopupOverrides.set(real, "o", listOf("ö", "ó"))
        customizePopupsSaved()
        LayoutDraft.rejectOpen(ctx)
        assertEquals(m1 + ("o" to listOf("ö", "ó")), KeyPopupOverrides.load(real))
    }

    // review M2: a keyboard the sub-screen kept is recorded by the fields its save changed, not as the whole entry

    /** Keyboard 1 gets letters "azerty" on Layout and Typing (unsaved), then Customize popups saves accents "all". */
    private fun accentsScenario(): SettingsSubtype {
        val one = SettingsSubtype(Locale.US, "").withId(1)
        storeKeyboards(ctx, real, listOf(one))
        LayoutDraft.of(ctx, one.toPref()) // Layout & Typing opens
        val azerty = one.withLayout(LayoutType.MAIN, "azerty")
        storeKeyboards(ctx, real, listOf(azerty), selected = null)
        LayoutDraft.subScreenOpenedOpen(ctx) // Customize popups opens
        storeKeyboards(ctx, real, listOf(azerty.with(ExtraValue.MORE_POPUPS, "all")), selected = null)
        customizePopupsSaved()
        return one
    }

    private fun assertAccentsOnly(one: SettingsSubtype) {
        val expected = one.with(ExtraValue.MORE_POPUPS, "all").toPref() // the saved accents, the snapshot's letters
        assertEquals(listOf(expected), storedEnabled(real).map { it.toPref() })
        assertEquals(listOf(expected),
            SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!).map { it.toPref() })
    }

    @Test fun `keyboard fields - letters changed on Layout and Typing, accents saved in Customize popups, leaving the app keeps only the accents`() {
        val one = accentsScenario()
        LayoutDraft.rejectOpen(ctx) // the app left
        assertAccentsOnly(one)
    }

    @Test fun `keyboard fields - the same after a crash, the fields kept on disk`() {
        val one = accentsScenario()
        LayoutDraft.forgetActiveForTest() // the process died
        LayoutDraft.recoverAfterCrash(ctx) // the next app start
        assertAccentsOnly(one)
    }

    @Test fun `keyboard fields - a layout file deleted on the layout files screen changes only that layout of the keyboard`() {
        SubtypeSettings.init(ctx)
        val type = LayoutType.NUMBER_ROW
        val x = LayoutUtilsCustom.getLayoutName("x", type)
        LayoutUtilsCustom.getLayoutFile(x, type, ctx).writeText("1 2 3")
        LayoutUtilsCustom.onLayoutFileChanged()
        val one = SettingsSubtype(Locale.US, "").withLayout(type, x).withId(1)
        storeKeyboards(ctx, real, listOf(one))
        LayoutDraft.of(ctx, one.toPref()) // Layout & Typing opens
        storeKeyboards(ctx, real, listOf(one.with(ExtraValue.MORE_POPUPS, "none")), selected = null) // (unsaved)
        LayoutDraft.subScreenOpenedOpen(ctx) // the layout files screen: x deleted, then Save
        LayoutUtilsCustom.deleteLayout(x, type, ctx)
        layoutFilesSaved()
        LayoutDraft.rejectOpen(ctx) // the app left

        val after = storedEnabled(real).single()
        val copy = after.layoutName(type)!!
        assertTrue(LayoutUtilsCustom.isPrivateLayout(copy)) // the delete's change: the keyboard's own unnamed copy
        assertEquals(one.withLayout(type, copy).toPref(), after.toPref()) // the rest as the snapshot had it (no "none")
        assertEquals("1 2 3", LayoutUtilsCustom.getLayoutFile(copy, type, ctx).readText())
        assertTrue(!LayoutUtilsCustom.getLayoutFile(x, type, ctx).exists())
    }
}

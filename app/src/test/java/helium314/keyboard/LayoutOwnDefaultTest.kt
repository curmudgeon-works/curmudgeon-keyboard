// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Owner 2026-10-12: "our saving policies should not be affected by the common/separate toggle at all." A custom key
 * layout edited while other keyboards have it as their default: each keyboard whose own set keeps it as the default,
 * shown or hidden behind the common settings, is asked, the same ones with separate settings off, on, and with the
 * Layout menu shared; answering No, that own set's default becomes the old keys' name. Until then an enabled keyboard
 * was asked only by the default it reads now (the common one with one set of settings or Layout shared), so a hidden
 * own default was never named, and No gave every keyboard a pick instead.
 * A ("Work", enabled, first: its own set is the common one) = k1, B (French, enabled) = k2, C (German, switched off:
 * in the added list only) = k3; none picks a number row layout.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutOwnDefaultTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val type = LayoutType.NUMBER_ROW
    private val key = Settings.PREF_LAYOUT_PREFIX + type.name
    private val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
    private val old = LayoutUtilsCustom.getLayoutName("My numrow_old", type)
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withId(1)
    // (French: no built-in keyboard for fr-FR, the language's own locale has them; as CentralPalettesTest)
    private val b = SubtypeSettings.getAvailableSubtypeLocales().first { it.language == "fr" }
        .let { SubtypeSettings.getResourceSubtypesForLocale(it).first().toSettingsSubtype() }.withId(2)
    private val c = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(3)
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private val folder get() = file(numrow).parentFile!!

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        folder.deleteRecursively(); folder.mkdirs()
        file(numrow).writeText("old keys")
        LayoutUtilsCustom.onLayoutFileChanged()
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b, c)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
            // (B's and C's own sets keep it as the default; A's, the common one, keeps none: the built-in row)
            putString(idKey(2, key), numrow)
            putString(idKey(3, key), numrow)
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun separate(on: Boolean) = real.edit { putBoolean("separate_settings_per_keyboard", on) }
    private fun shareLayout() = KeyboardProfiles.setGroupShared(real, KeyboardProfiles.Group.LAYOUT, true)
    private fun ids(keyboards: List<SettingsSubtype>) = keyboards.map { it.keyboardId }
    private fun users(edited: SettingsSubtype = a) = LayoutUtilsCustom.keyboardsUsing(ctx, type, numrow, edited)
    private fun additional() = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!)
    private fun stored(n: Int) = additional().single { it.keyboardId == kid(n) }

    private fun assertKeptInOwnSets(others: List<SettingsSubtype>) {
        LayoutUtilsCustom.keepOldKeys(numrow, old, type, others, ctx)
        file(numrow).writeText("new keys") // (then the edit is written)
        assertEquals("old keys", file(old).readText())
        assertEquals(old, real.getString(idKey(2, key), null))
        assertEquals(old, real.getString(idKey(3, key), null))
        // (through their own default, no pick; A's, the common one, untouched)
        assertNull(stored(2).layoutName(type)); assertNull(stored(3).layoutName(type))
        assertFalse(real.contains(idKey(1, key)))
    }

    @Test fun `separate settings off - a keyboard whose hidden own default is the layout is asked`() {
        separate(false)
        assertEquals(listOf(kid(2), kid(3)), ids(users()))
    }

    @Test fun `separate settings on with Layout shared - the hidden own default is asked`() {
        shareLayout()
        assertEquals(listOf(kid(2), kid(3)), ids(users()))
    }

    @Test fun `the same keyboards are asked in every mode`() {
        val perKeyboard = ids(users())
        separate(false)
        assertEquals(perKeyboard, ids(users()))
        separate(true); shareLayout()
        assertEquals(perKeyboard, ids(users()))
        assertEquals(listOf(kid(2), kid(3)), perKeyboard)
    }

    @Test fun `answering No with separate settings off, the hidden own defaults keep the old keys`() {
        separate(false)
        assertKeptInOwnSets(users())
    }

    @Test fun `answering No with Layout shared, the hidden own defaults keep the old keys`() {
        shareLayout()
        assertKeptInOwnSets(users())
    }

    @Test fun `answering No, a keyboard reading the common default gets a pick, the common default gets the edit`() {
        // (A's own set, the common one, has it as the default; B, edited, reads it; C's own keeps it)
        separate(false)
        real.edit { putString(idKey(1, key), numrow); remove(idKey(2, key)) }
        val others = users(edited = b)
        assertEquals(listOf(kid(1), kid(3)), ids(others))
        LayoutUtilsCustom.keepOldKeys(numrow, old, type, others, ctx)
        assertEquals(old, stored(1).layoutName(type)) // (A: a pick of its own)
        assertEquals(numrow, real.getString(idKey(1, key), null)) // (the common default: B reads the new keys)
        assertEquals(old, real.getString(idKey(3, key), null))
        assertNull(stored(3).layoutName(type))
    }
}

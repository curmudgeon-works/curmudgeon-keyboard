// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The medium items of the 2026-10-06 review fixed on 2026-10-07: M10 (copied keyboards shared one private layout
 *  file), M15 (a new popup set could take an existing set's name), M16 (popup sets didn't keep the symbol map and order). */
@RunWith(RobolectricTestRunner::class)
class ReReviewMediumTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `a copied keyboard gets its own copy of a private layout`() {
        val text = "[[{ \"label\": \"q\" }, { \"label\": \"w\" }]]"
        val name = LayoutUtilsCustom.makePrivateLayout(text, LayoutType.MAIN, "latn", ctx)
        assertTrue(LayoutUtilsCustom.isPrivateLayout(name))
        val source = SettingsSubtype(Locale.US, "").withLayout(LayoutType.MAIN, name)
        val copy = LayoutUtilsCustom.withOwnPrivateLayouts(source, ctx)
        val copyName = copy.layoutName(LayoutType.MAIN)!!
        assertNotEquals(name, copyName)
        assertTrue(LayoutUtilsCustom.isPrivateLayout(copyName))
        assertEquals(text, LayoutUtilsCustom.getLayoutFile(copyName, LayoutType.MAIN, ctx).readText())
        // editing the copy's keys leaves the source's file alone
        LayoutUtilsCustom.getLayoutFile(copyName, LayoutType.MAIN, ctx).writeText("changed")
        assertEquals(text, LayoutUtilsCustom.getLayoutFile(name, LayoutType.MAIN, ctx).readText())
        // a named (shared) layout is kept as it is: a copy may use the same named layout
        val named = SettingsSubtype(Locale.US, "").withLayout(LayoutType.MAIN, "dvorak")
        assertEquals("dvorak", LayoutUtilsCustom.withOwnPrivateLayouts(named, ctx).layoutName(LayoutType.MAIN))
    }

    @Test fun `a new popup set can't take an existing set's name`() {
        val sets = listOf(KeyPopupOverrides.UserSet("My popups", "all", null, emptyMap()))
        assertFalse(KeyPopupOverrides.isNewSetName(sets, "My popups"))
        assertFalse(KeyPopupOverrides.isNewSetName(sets, " My popups "))
        assertFalse(KeyPopupOverrides.isNewSetName(sets, "  "))
        assertTrue(KeyPopupOverrides.isNewSetName(sets, "My popups 2"))
    }

    @Test fun `popup sets keep their symbol map and popup order, older sets leave them as they are`() {
        val prefs = DeviceProtectedUtils.getRealSharedPreferences(ctx)
        val set = KeyPopupOverrides.UserSet("Mine", "all", "symbols", mapOf("e" to listOf("é", "3")), symbolMap = "e3", popupOrder = "number;true")
        KeyPopupOverrides.saveSets(prefs, listOf(set))
        val back = KeyPopupOverrides.loadSets(prefs).single()
        assertEquals("e3", back.symbolMap)
        assertEquals("number;true", back.popupOrder)
        assertEquals(mapOf("e" to listOf("é", "3")), back.overrides)
        // a set saved before 2026-10-07 has neither: null, "leave as it is"
        prefs.edit().putString(KeyPopupOverrides.PREF_SETS,
            """[{"name":"Old","morePopups":"main","symbolsLayout":"","overrides":{}}]""").apply()
        val old = KeyPopupOverrides.loadSets(prefs).single()
        assertNull(old.symbolMap)
        assertNull(old.popupOrder)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deleting a custom key layout: the warning names the keyboards that keep its keys (their own pick) and the ones that go
 *  back to the default keys (it was only their default), and the real delete does exactly that to each of them. */
@RunWith(RobolectricTestRunner::class)
class LayoutDeleteEffectsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val type = LayoutType.NUMBER_ROW
    private val defaultKey = Settings.PREF_LAYOUT_PREFIX + type.name
    private val x = LayoutUtilsCustom.getLayoutName("x", type)
    private val y = LayoutUtilsCustom.getLayoutName("y", type)
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)

    private fun available(language: String) =
        SubtypeSettings.getAllAvailableSubtypes().map { it.toSettingsSubtype() }.first { it.locale == Locale.forLanguageTag(language) }

    @Before fun setUp() {
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        file(x).parentFile?.mkdirs()
        file(x).writeText("1 2 3"); file(y).writeText("7 8 9"); LayoutUtilsCustom.onLayoutFileChanged()
    }
    @After fun tearDown() {
        file(x).parentFile?.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** Keyboards [keyboards] with ids k1, k2, k3 in this order (owner 2026-10-11: keyboards have unique ids), the first
     *  one in use, all in the added list. @return them as listed (with their ids) */
    private fun keyboards(separate: Boolean, vararg keyboards: SettingsSubtype): List<SettingsSubtype> {
        val listed = keyboards.mapIndexed { i, kb -> kb.withId(i + 1) }
        real.edit().putBoolean("separate_settings_per_keyboard", separate).commit()
        storeKeyboards(ctx, real, listed, reload = false)
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        return listed
    }

    private fun names(list: List<SettingsSubtype>) = list.map { it.locale.toLanguageTag() }
    private val builtIn get() = Settings.readDefaultLayoutName(type, ProfilePreferences(real) { kid(99) })
    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    /** The number row keyboard [index] of the list draws now (its own pick, else its default in its set [id]). */
    private fun keysOf(index: Int, id: Int): String {
        val pick = enabled()[index].layoutName(type)
        return pick?.let { file(it).readText() } ?: Settings.readDefaultLayoutName(type, ProfilePreferences(real) { kid(id) })
    }

    /** Each keyboard named in "keeps" ends with its own unsaved copy of the keys; each in "back to default" with the
     *  built-in keys; the rest as they were. */
    private fun assertOutcome(effects: Pair<List<SettingsSubtype>, List<SettingsSubtype>>, before: List<SettingsSubtype>, unchanged: Map<Int, String>) {
        val (keeps, back) = effects
        for (kb in keeps) {
            val index = before.indexOf(kb)
            // its own pick, or (2026-10-11) the default of its set: an unnamed copy of the keys either way
            val name = enabled()[index].layoutName(type) ?: Settings.readDefaultLayoutName(type, ProfilePreferences(real) { kid(index + 1) })
            assertTrue(LayoutUtilsCustom.isPrivateLayout(name), "${kb.locale} named as keeping the keys, but uses $name")
            assertEquals("1 2 3", file(name).readText())
        }
        for (kb in back) {
            val index = before.indexOf(kb)
            assertNull(enabled()[index].layoutName(type))
            assertEquals(builtIn, keysOf(index, index + 1), "${kb.locale} named as going back to the default keys")
        }
        for ((index, keys) in unchanged) {
            assertTrue(before[index] !in keeps && before[index] !in back)
            assertEquals(keys, keysOf(index, index + 1))
        }
    }

    @Test fun `separate settings on - the pick and the own default keep the keys, another pick is untouched`() {
        val a = available("en-US").withLayout(type, x) // picks x
        val b = available("de-DE") // x is the default in its own set
        val c = available("en-GB").withLayout(type, y) // picks y, its own default x: uses none of x
        val before = keyboards(true, a, b, c)
        real.edit().putString(idKey(2, defaultKey), x).putString(idKey(3, defaultKey), x).commit()
        val effects = LayoutUtilsCustom.deleteEffects(x, type, ctx)
        assertEquals(listOf("en-US", "de-DE"), names(effects.first)) // (2026-10-11: the default's users keep the keys too)
        assertEquals(emptyList(), names(effects.second))
        LayoutUtilsCustom.deleteLayout(x, type, ctx)
        assertOutcome(effects, before, mapOf(2 to "7 8 9"))
    }

    // (owner 2026-10-18, review SP10: "Only show those that actually go back to default (as they would with separate
    // settings)": the ones only reading the first keyboard's default aren't named, the line says they follow it. Fail
    // before: B and C were named too, and no line)
    @Test fun `separate settings off - only the own pick and own default are named, the line says the others follow the first`() {
        val a = available("en-US")
        val b = available("de-DE").withLayout(type, x)
        val c = available("en-GB")
        val before = keyboards(false, a, b, c)
        real.edit().putString(idKey(1, defaultKey), x).commit() // (the first keyboard's: owner 2026-10-11, no separate common set; the first keyboard's own settings are the common ones)
        val effects = LayoutUtilsCustom.deleteEffects(x, type, ctx)
        assertEquals(listOf("de-DE", "en-US"), names(effects.first)) // (the pick, then the own default)
        assertEquals(emptyList(), names(effects.second))
        val (aName, bName) = before.take(2).map { helium314.keyboard.settings.screens.keyboardName(it, ctx) }
        assertEquals("These keyboards keep its keys, each as an unsaved layout of its own:\n$bName\n$aName\n\n" +
            "Settings are common to all your keyboards (App settings › Per keyboard), so every keyboard follows $aName, first in your list.",
            LayoutUtilsCustom.deleteWarning(x, type, ctx))
        // a hidden own default: marked, and the update questions' line (a pick is in the keyboard's definition: never)
        real.edit().putString(idKey(3, defaultKey), x).commit()
        val cName = helium314.keyboard.settings.screens.keyboardName(before[2], ctx)
        assertEquals("These keyboards keep its keys, each as an unsaved layout of its own:\n$bName\n$aName\n$cName (hidden)\n\n" +
            "Settings are common to all your keyboards (App settings › Per keyboard), so every keyboard follows $aName, first in " +
            "your list. Keyboards marked (hidden) keep this change for when settings are per keyboard.",
            LayoutUtilsCustom.deleteWarning(x, type, ctx))
        real.edit().remove(idKey(3, defaultKey)).commit()
        LayoutUtilsCustom.deleteLayout(x, type, ctx)
        assertOutcome(effects, before, emptyMap())
        // C, reading the first keyboard's default, kept the keys all the same
        Settings.readDefaultLayoutName(type, ProfilePreferences(real) { kid(3) }).let { assertTrue(LayoutUtilsCustom.isPrivateLayout(it)); assertEquals("1 2 3", file(it).readText()) }
    }

    @Test fun `separate settings on - no mark, no line`() {
        val a = available("en-US")
        val b = available("de-DE")
        val before = keyboards(true, a, b)
        real.edit().putString(idKey(2, defaultKey), x).commit()
        assertEquals("This keyboard keeps its keys, as an unsaved layout of its own:\n" + helium314.keyboard.settings.screens.keyboardName(before[1], ctx),
            LayoutUtilsCustom.deleteWarning(x, type, ctx))
    }

    @Test fun `separate settings off - a hidden set's default is named and keeps the keys`() {
        val a = available("en-US")
        val b = available("de-DE")
        val c = available("en-GB")
        val before = keyboards(false, a, b, c)
        real.edit().putString(idKey(1, defaultKey), "number_row").putString(idKey(3, defaultKey), x).commit() // C's set kept behind the scenes
        val effects = LayoutUtilsCustom.deleteEffects(x, type, ctx)
        assertEquals(listOf("en-GB"), names(effects.first))
        assertEquals(emptyList(), names(effects.second))
        LayoutUtilsCustom.deleteLayout(x, type, ctx)
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit() // its set in use again
        assertOutcome(effects, before, mapOf(0 to "number_row", 1 to "number_row"))
        // (its default kept the keys, as an unnamed copy)
        Settings.readDefaultLayoutName(type, ProfilePreferences(real) { kid(3) }).let { assertTrue(LayoutUtilsCustom.isPrivateLayout(it)); assertEquals("1 2 3", file(it).readText()) }
    }

    @Test fun `the warning text - none, one, many, both`() {
        assertNull(LayoutUtilsCustom.deleteEffectsText(ctx, emptyList(), emptyList()))
        assertEquals("This keyboard keeps its keys, as an unsaved layout of its own:\nEnglish (US)",
            LayoutUtilsCustom.deleteEffectsText(ctx, listOf("English (US)"), emptyList()))
        assertEquals("These keyboards go back to the default keys:\nA\nB\nC",
            LayoutUtilsCustom.deleteEffectsText(ctx, emptyList(), listOf("A", "B", "C")))
        assertEquals("These keyboards keep its keys, each as an unsaved layout of its own:\nA\nB\n\n" +
            "This keyboard goes back to the default keys:\nC",
            LayoutUtilsCustom.deleteEffectsText(ctx, listOf("A", "B"), listOf("C")))
    }
}

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
import org.json.JSONObject
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

    /** Keyboards [keyboards] (sets 1, 2, 3 in this order), the first one in use; the ones picking a layout are additional. */
    private fun keyboards(separate: Boolean, vararg keyboards: SettingsSubtype) {
        real.edit()
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards.filter { it.layoutName(type) != null }))
            .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards.toList()))
            .putString(Settings.PREF_SELECTED_SUBTYPE, keyboards.first().toPref())
            .putString("keyboard_profile_ids", JSONObject(keyboards.withIndex().associate { (i, kb) -> kb.toPref() to i + 1 }).toString())
            .putInt("keyboard_profile_next_id", keyboards.size + 1)
            .putBoolean("separate_settings_per_keyboard", separate)
            .commit()
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun names(list: List<SettingsSubtype>) = list.map { it.locale.toLanguageTag() }
    private val builtIn get() = Settings.readDefaultLayoutName(type, ProfilePreferences(real) { 99 })
    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    /** The number row keyboard [index] of the list draws now (its own pick, else its default in its set [id]). */
    private fun keysOf(index: Int, id: Int): String {
        val pick = enabled()[index].layoutName(type)
        return pick?.let { file(it).readText() } ?: Settings.readDefaultLayoutName(type, ProfilePreferences(real) { id })
    }

    /** Each keyboard named in "keeps" ends with its own unsaved copy of the keys; each in "back to default" with the
     *  built-in keys; the rest as they were. */
    private fun assertOutcome(effects: Pair<List<SettingsSubtype>, List<SettingsSubtype>>, before: List<SettingsSubtype>, unchanged: Map<Int, String>) {
        val (keeps, back) = effects
        for (kb in keeps) {
            val index = before.indexOf(kb)
            val pick = enabled()[index].layoutName(type)!!
            assertTrue(LayoutUtilsCustom.isPrivateLayout(pick), "${kb.locale} named as keeping the keys, but picks $pick")
            assertEquals("1 2 3", file(pick).readText())
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

    @Test fun `separate settings on - the pick keeps the keys, the own default goes back, another pick is untouched`() {
        val a = available("en-US").withLayout(type, x) // picks x
        val b = available("de-DE") // x is the default in its own set
        val c = available("en-GB").withLayout(type, y) // picks y, its own default x: uses none of x
        keyboards(true, a, b, c)
        real.edit().putString("p2/$defaultKey", x).putString("p3/$defaultKey", x).commit()
        val effects = LayoutUtilsCustom.deleteEffects(x, type, ctx)
        assertEquals(listOf("en-US"), names(effects.first))
        assertEquals(listOf("de-DE"), names(effects.second))
        LayoutUtilsCustom.deleteLayout(x, type, ctx)
        assertOutcome(effects, listOf(a, b, c), mapOf(2 to "7 8 9"))
    }

    @Test fun `separate settings off - the keyboards reading the shared default go back, the pick keeps the keys`() {
        val a = available("en-US").withLayout(type, x)
        val b = available("de-DE")
        val c = available("en-GB")
        keyboards(false, a, b, c)
        real.edit().putString(defaultKey, x).commit()
        val effects = LayoutUtilsCustom.deleteEffects(x, type, ctx)
        assertEquals(listOf("en-US"), names(effects.first))
        assertEquals(listOf("de-DE", "en-GB"), names(effects.second))
        LayoutUtilsCustom.deleteLayout(x, type, ctx)
        assertOutcome(effects, listOf(a, b, c), emptyMap())
    }

    @Test fun `separate settings off - a hidden set's default is named and goes back to the default keys`() {
        val a = available("en-US")
        val b = available("de-DE")
        val c = available("en-GB")
        keyboards(false, a, b, c)
        real.edit().putString(defaultKey, "number_row").putString("p3/$defaultKey", x).commit() // C's set kept behind the scenes
        val effects = LayoutUtilsCustom.deleteEffects(x, type, ctx)
        assertEquals(emptyList(), names(effects.first))
        assertEquals(listOf("en-GB"), names(effects.second))
        LayoutUtilsCustom.deleteLayout(x, type, ctx)
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit() // its set in use again
        assertOutcome(effects, listOf(a, b, c), mapOf(0 to "number_row", 1 to "number_row"))
        assertEquals(builtIn, Settings.readDefaultLayoutName(type, ProfilePreferences(real) { 3 }))
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

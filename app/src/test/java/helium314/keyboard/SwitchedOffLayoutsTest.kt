// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.userNames
import helium314.keyboard.settings.screens.withKeyboardName
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owner 2026-10-11: switched-off keyboards are treated like switched-on ones. A custom key layout renamed (or removed
 *  as missing) or edited reached only the enabled keyboards: a switched-off one's set and learned words stayed under its
 *  old definition, and an edit gave it the new keys unasked. A ("Work", enabled, edited) = set 1, B (German, switched
 *  off, own settings + learned words) = set 2; both pick "My numrow". */
@RunWith(RobolectricTestRunner::class)
class SwitchedOffLayoutsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val type = LayoutType.NUMBER_ROW
    private val numrow = LayoutUtilsCustom.getLayoutName("My numrow", type)
    private val renamed = LayoutUtilsCustom.getLayoutName("Row two", type)
    private val a = withKeyboardName(SettingsSubtype(Locale.US, ""), "Work").withLayout(type, numrow)
    private val b = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withLayout(type, numrow)
    private val own = KeyboardProfiles.ownKey(2, Settings.PREF_AUTO_CAP)
    private fun file(name: String) = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
    private val folder get() = file(numrow).parentFile!!
    private var pool = 0

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        folder.deleteRecursively(); folder.mkdirs()
        file(numrow).writeText("old keys")
        LayoutUtilsCustom.onLayoutFileChanged()
        assertEquals(1, KeyboardProfiles.idFor(real, a))
        assertEquals(2, KeyboardProfiles.idFor(real, b))
        real.edit { putBoolean(LearnedKeyboards.PREF_DONE, true) } // (learned words per keyboard: B has a pool of its own)
        pool = LearnedKeyboards.poolFor(real, b)
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a, b)))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(a)))
            putString(Settings.PREF_SELECTED_SUBTYPE, a.toPref())
            putBoolean(own, false)
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }
    @After fun tearDown() {
        folder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun additional() = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!)
    private fun pools() = JSONObject(real.getString(LearnedKeyboards.PREF_POOLS, "{}")!!)

    /** [to]: B's definition now; its set (id 2, its own setting) and its learned words follow it, nothing stays behind. */
    private fun assertMoved(to: SettingsSubtype) {
        val ids = KeyboardProfiles.idEntries(real)
        assertEquals(2, ids[to.toPref()], ids.toString())
        assertFalse(b.toPref() in ids, ids.toString())
        assertFalse(real.getBoolean(KeyboardProfiles.ownKey(KeyboardProfiles.idFor(real, to), Settings.PREF_AUTO_CAP), true))
        assertEquals(pool, pools().optInt(to.toPref(), -1), pools().toString())
        assertFalse(pools().has(b.toPref()), pools().toString())
    }

    @Test fun `renaming the layout renames it in the switched-off keyboard, its set and learned words follow`() {
        file(numrow).delete() // (as the edit dialog renames)
        SubtypeSettings.onRenameLayout(type, numrow, renamed, ctx)
        file(renamed).writeText("old keys")
        val bNow = b.withLayout(type, renamed)
        assertTrue(bNow in additional(), additional().toString())
        assertFalse(b in additional())
        assertMoved(bNow)
    }

    @Test fun `a missing layout removed, the switched-off keyboard now a built-in one keeps its set`() {
        SubtypeSettings.onRenameLayout(type, numrow, null, ctx) // (removeMissingLayouts after a restore)
        val bNow = b.withoutLayout(type)
        assertTrue(bNow.isSameAsDefault())
        assertFalse(additional().any { it.locale == Locale.GERMANY }, additional().toString())
        assertMoved(bNow)
    }

    @Test fun `editing the keys asks about the switched-off keyboard, named so`() {
        val others = LayoutUtilsCustom.keyboardsUsing(ctx, type, numrow, a)
        assertEquals(listOf(b), others)
        assertEquals(listOf(ctx.getString(helium314.keyboard.latin.R.string.keyboard_switched_off, keyboardName(b, ctx))), userNames(ctx, others))
        assertEquals("${keyboardName(b, ctx)} (switched off)", userNames(ctx, others).single())
    }

    @Test fun `answering No, the switched-off keyboard keeps the old keys under the new name`() {
        val others = LayoutUtilsCustom.keyboardsUsing(ctx, type, numrow, a)
        val old = LayoutUtilsCustom.getLayoutName("My numrow_old", type)
        LayoutUtilsCustom.keepOldKeys(numrow, old, type, others, ctx)
        file(numrow).writeText("new keys") // (then the edit is written)
        val bNow = b.withLayout(type, old)
        assertTrue(bNow in additional(), additional().toString())
        assertEquals("old keys", file(old).readText())
        assertMoved(bNow)
    }

    @Test fun `the delete warning names the switched-off keyboard`() {
        val (keeps, _) = LayoutUtilsCustom.deleteEffects(numrow, type, ctx)
        assertEquals(listOf(a, b), keeps)
    }
}

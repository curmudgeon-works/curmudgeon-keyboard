// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.SavedItemDefaults
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.screens.createAdvancedSettings
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 2026-10-11 S3: flipping "Saved items take new defaults" changes the accents level of keyboards whose popup set
 * holds it at its default. Switched-off keyboards follow the switch too.
 * (owner 2026-10-11: keyboards have unique ids) Two keyboards that end up set up alike stay two keyboards, each with its
 * id, set and pool: no clash, so the "Rename to keep keyboards apart?" alert and the plan's renames are gone.
 */
@RunWith(RobolectricTestRunner::class)
class ReReview2S3Test {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val plain = SettingsSubtype(Locale.US, "")
    private val a = plain.withId(1)
    private val b = plain.with(ExtraValue.MORE_POPUPS, "all").withId(2)
    /** [b] after the switch is turned on: [a]'s definition, its own id. */
    private val bOn = plain.withId(2)
    private val de = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype().withId(3)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.init(ctx) }
    @After fun tearDown() {
        AppearanceDraft.close(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        shadowOf(Looper.getMainLooper()).idle() // (the screen's queued work done here, as SwitchedOffTileTest)
    }

    /** Separate settings; popup set Mine (accents "all", held at its default); [enabled] listed, [off] switched off (in
     *  the added list only); each keyboard's set (its id's) chooses Mine; pool 10+n for keyboard k<n>. */
    private fun keyboards(enabled: List<SettingsSubtype>, off: List<SettingsSubtype> = emptyList(), takeNew: Boolean) {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(),
            atDefault = setOf(KeyPopupOverrides.ACCENTS))))
        val all = enabled + off
        val e = real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putBoolean(SavedItemDefaults.PREF, takeNew)
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(all.associate { it.keyboardId!! to 10 + it.keyboardId!!.drop(1).toInt() } as Map<*, *>).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 20).putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(all))
            .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled))
            .putString(Settings.PREF_SELECTED_SUBTYPE, enabled.first().toPref())
        for (id in all.map { it.keyboardId!! }) e.putString(KeyboardProfiles.ownKey(id, KeyPopupOverrides.PREF_SELECTED_SET), "Mine")
            .putString(KeyboardProfiles.ownKey(id, "test_own_setting"), "set $id")
        e.commit()
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun stored() = storedEnabled(real)
    private fun added() = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "")!!)
    private fun loadedIds() = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype().keyboardId }

    @Test fun `a - on - two English keyboards differing only in accents - both follow and stay two, nothing moves`() {
        keyboards(listOf(a, b), takeNew = false)
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        SavedItemDefaults.applySwitch(ctx, real, true)
        assertEquals(listOf(a, bOn), stored(), "not both listed, or an id changed")
        assertEquals(a.withoutIds(), bOn.withoutIds()) // (set up alike now)
        assertEquals(listOf(kid(1), kid(2)), loadedIds(), "not two keyboards")
        assertEquals(mapOf(kid(1) to 11, kid(2) to 12), storedPools(real))
        assertEquals("set k1", real.getString(idKey(1, "test_own_setting"), null))
        assertEquals("set k2", real.getString(idKey(2, "test_own_setting"), null))
    }

    @Test fun `a - the plan just changes the accents, keeps the ids, and writes nothing`() {
        keyboards(listOf(a, b), takeNew = false)
        val before = real.all
        val plan = SavedItemDefaults.accentsPlan(real, true)
        assertEquals(before, real.all)
        assertEquals(listOf(SavedItemDefaults.KeyboardChange(b, bOn)), plan.changes)
        // the other order: the same change
        keyboards(listOf(b, a), takeNew = false)
        assertEquals(listOf(SavedItemDefaults.KeyboardChange(b, bOn)), SavedItemDefaults.accentsPlan(real, true).changes)
    }

    @Test fun `a - a switched-off keyboard set up like a listed one follows too, and stays its own`() {
        keyboards(listOf(a), off = listOf(b), takeNew = false)
        assertEquals(listOf(SavedItemDefaults.KeyboardChange(b, bOn)), SavedItemDefaults.accentsPlan(real, true).changes)
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        SavedItemDefaults.applySwitch(ctx, real, true)
        assertEquals(listOf(a), stored())
        assertTrue(bOn in added())
        assertEquals(listOf(bOn), SwitchedOffKeyboards.list(real)) // (still switched off, its id kept)
        assertEquals(mapOf(kid(1) to 11, kid(2) to 12), storedPools(real))
        assertEquals("set k2", real.getString(idKey(2, "test_own_setting"), null))
    }

    @Test fun `b - one keyboard - applied as before`() {
        keyboards(listOf(b), takeNew = false)
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        SavedItemDefaults.applySwitch(ctx, real, true)
        assertEquals(listOf(bOn), stored())
        assertEquals(mapOf(kid(2) to 12), storedPools(real)) // (its id kept: the same pool)
    }

    // the switch row itself
    private fun onSwitch(block: () -> Unit) {
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { act ->
            act.setContent { createAdvancedSettings(act).first { it.key == SavedItemDefaults.PREF }.Preference() }
        }.use { block() }
    }
    private val title get() = ctx.getString(R.string.saved_items_take_new_defaults)

    @Test fun `a - the switch flips at once, no alert, then the usual note, both keyboards stay`() {
        keyboards(listOf(a, b), takeNew = false)
        onSwitch {
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText("Rename to keep keyboards apart?").assertDoesNotExist()
            compose.onNodeWithText(ctx.getString(R.string.saved_items_take_new_defaults_when)).assertExists()
            compose.onNodeWithText(ctx.getString(R.string.saved_items_take_new_defaults_on)).assertExists()
        }
        assertTrue(SavedItemDefaults.takeNewDefaults(real))
        assertEquals(listOf(a, bOn), stored())
    }

    @Test fun `c - a switched-off keyboard's accents follow the switch`() {
        keyboards(listOf(a), off = listOf(de), takeNew = true)
        real.edit().putBoolean(SavedItemDefaults.PREF, false).commit()
        SavedItemDefaults.applySwitch(ctx, real, false)
        val deNow = de.with(ExtraValue.MORE_POPUPS, "all")
        val aNow = a.with(ExtraValue.MORE_POPUPS, "all")
        assertEquals(listOf(aNow), stored())
        assertTrue(deNow in added(), "the switched-off keyboard kept its accents")
        assertFalse(de in added())
        assertEquals(mapOf(kid(1) to 11, kid(3) to 13), storedPools(real)) // (ids kept: nothing moved)
        assertFalse(deNow in stored()) // (still switched off)
        assertTrue(real.contains(idKey(3, "test_own_setting")))
    }
}

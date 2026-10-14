// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.SavedItemDefaults
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.screens.createAdvancedSettings
import helium314.keyboard.settings.screens.renameLines
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
 * holds it at its default, and the accents level is in a keyboard's identity: two English keyboards differing only in
 * accents became one identity (a duplicate in the list, one set left behind). Owner 2026-10-11: "Or we could just rename
 * the keyboard lower in order as english 2?", "K, alert first before rename -- that wth just happened, ok/cancel".
 * Switched-off keyboards follow the switch too.
 * (Found 2026-10-11, not fixed here: changing the selected keyboard's definition, any way, gives its old identity a new
 * pool: LearnedStores' listener on the pools reads the selected keyboard before changeAdditionalSubtype writes it.
 * Fixed 2026-10-11: lookups never create records, see GhostRecordsTest.)
 */
@RunWith(RobolectricTestRunner::class)
class ReReview2S3Test {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val a = SettingsSubtype(Locale.US, "")
    private val b = a.with(ExtraValue.MORE_POPUPS, "all")
    private val de = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.init(ctx) }
    @After fun tearDown() {
        AppearanceDraft.close(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        shadowOf(Looper.getMainLooper()).idle() // (the screen's queued work done here, as SwitchedOffTileTest)
    }

    /** Separate settings; popup set Mine (accents "all", held at its default); [enabled] listed; [ids] their sets, each
     *  choosing Mine; pools 10+id. */
    private fun keyboards(enabled: List<SettingsSubtype>, ids: Map<SettingsSubtype, Int>, takeNew: Boolean) {
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(),
            atDefault = setOf(KeyPopupOverrides.ACCENTS))))
        val e = real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putBoolean(SavedItemDefaults.PREF, takeNew)
            .putString("keyboard_profile_ids", JSONObject(ids.mapKeys { it.key.toPref() }).toString())
            .putInt("keyboard_profile_next_id", 10)
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(ids.entries.associate { it.key.toPref() to 10 + it.value }).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 20).putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled.filter { !it.isSameAsDefault() }))
            .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled))
            .putString(Settings.PREF_SELECTED_SUBTYPE, enabled.first().toPref())
        for (id in ids.values) e.putString(KeyboardProfiles.ownKey(id, KeyPopupOverrides.PREF_SELECTED_SET), "Mine")
            .putString(KeyboardProfiles.ownKey(id, "test_own_setting"), "set $id")
        e.commit()
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    private fun storedEnabled() = SubtypeSettings.createSettingsSubtypes(ctx.prefs().getString(Settings.PREF_ENABLED_SUBTYPES, "")!!)
    private fun ids() = KeyboardProfiles.idEntries(real).mapKeys { it.key.toSettingsSubtype() }
    private fun pools() = JSONObject(real.getString(LearnedKeyboards.PREF_POOLS, "{}")!!).let { m ->
        m.keys().asSequence().associate { it.toSettingsSubtype() to m.getInt(it) } }

    @Test fun `a - on - two English keyboards differing only in accents - the lower one is renamed, both follow, nothing mixes`() {
        keyboards(listOf(a, b), mapOf(a to 1, b to 2), takeNew = false)
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        SavedItemDefaults.applySwitch(ctx, real, true)
        val newName = keyboardName(a, ctx) + " 2"
        val renamed = a.with(ExtraValue.KEYBOARD_NAME, java.net.URLEncoder.encode(newName, "UTF-8"))
        assertEquals(listOf(a, renamed), storedEnabled(), "a duplicate in the list, or not renamed")
        assertEquals(newName, keyboardName(renamed, ctx))
        assertEquals(mapOf(a to 1, renamed to 2), ids(), "own set left behind")
        assertEquals(mapOf(a to 11, renamed to 12), pools())
        assertEquals("set 2", real.getString(KeyboardProfiles.ownKey(2, "test_own_setting"), null))
    }

    private fun nameOf(k: SettingsSubtype) = keyboardName(k, ctx)

    @Test fun `a - the plan names the lower keyboard and its new name, and writes nothing`() {
        keyboards(listOf(a, b), mapOf(a to 1, b to 2), takeNew = false)
        val before = real.all
        val plan = SavedItemDefaults.accentsPlan(real, true, ::nameOf)
        assertEquals(before, real.all)
        val newName = nameOf(a) + " 2"
        assertEquals(listOf(SavedItemDefaults.KeyboardChange(b, a.with(ExtraValue.KEYBOARD_NAME,
            java.net.URLEncoder.encode(newName, "UTF-8")), newName)), plan.renames)
        assertEquals(listOf("${nameOf(b)} will be renamed $newName"), renameLines(ctx, plan))
        // the other order: the lower one is the plain one now, renamed and kept as it is
        keyboards(listOf(b, a), mapOf(a to 1, b to 2), takeNew = false)
        assertEquals(listOf(a), SavedItemDefaults.accentsPlan(real, true, ::nameOf).renames.map { it.from })
    }

    @Test fun `a - a switched-off keyboard counts as lowest`() {
        keyboards(listOf(a), mapOf(a to 1, b to 2), takeNew = false) // (b switched off)
        val rename = SavedItemDefaults.accentsPlan(real, true, ::nameOf).renames.single()
        assertEquals(b, rename.from)
        assertTrue(rename.switchedOff)
        assertEquals(listOf("${nameOf(b)} (switched off) will be renamed ${nameOf(a)} 2"),
            renameLines(ctx, SavedItemDefaults.AccentsPlan(listOf(rename))))
    }

    @Test fun `b - no clash - applied as before`() {
        keyboards(listOf(b), mapOf(b to 2), takeNew = false)
        assertTrue(SavedItemDefaults.accentsPlan(real, true, ::nameOf).renames.isEmpty())
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        SavedItemDefaults.applySwitch(ctx, real, true)
        assertEquals(listOf(a), storedEnabled())
        assertEquals(mapOf(a to 2), ids())
        assertEquals(12, pools()[a]) // (the selected one: its old identity also gets a fresh pool, see the class note)
    }

    // the switch row itself: the alert before anything changes
    private fun onSwitch(block: () -> Unit) {
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { act ->
            act.setContent { createAdvancedSettings(act).first { it.key == SavedItemDefaults.PREF }.Preference() }
        }.use { block() }
    }
    private val title get() = ctx.getString(R.string.saved_items_take_new_defaults)
    private val alertTitle get() = ctx.getString(R.string.saved_items_rename_title)
    private val alertText get() = "Changing this makes two keyboards the same. ${nameOf(b)} will be renamed ${nameOf(a)} 2 so they stay separate."

    @Test fun `a - Cancel - the switch and the keyboards stay as they were`() {
        keyboards(listOf(a, b), mapOf(a to 1, b to 2), takeNew = false)
        val before = real.all
        onSwitch {
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(alertTitle).assertExists()
            compose.onNodeWithText(alertText).assertExists()
            compose.onNode(hasText(ctx.getString(android.R.string.cancel)) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText(alertTitle).assertDoesNotExist()
        }
        assertEquals(before, real.all)
        assertFalse(SavedItemDefaults.takeNewDefaults(real))
    }

    @Test fun `a - OK - the switch flips, the lower one is renamed, then the usual note`() {
        keyboards(listOf(a, b), mapOf(a to 1, b to 2), takeNew = false)
        onSwitch {
            compose.onNodeWithText(title).performClick()
            compose.onNode(hasText(ctx.getString(android.R.string.ok)) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText(ctx.getString(R.string.saved_items_take_new_defaults_when)).assertExists()
            compose.onNodeWithText(ctx.getString(R.string.saved_items_take_new_defaults_on)).assertExists()
        }
        assertTrue(SavedItemDefaults.takeNewDefaults(real))
        val renamed = a.with(ExtraValue.KEYBOARD_NAME, java.net.URLEncoder.encode(nameOf(a) + " 2", "UTF-8"))
        assertEquals(listOf(a, renamed), storedEnabled())
        assertEquals(mapOf(a to 1, renamed to 2), ids())
    }

    @Test fun `b - no clash - the switch flips without the alert`() {
        keyboards(listOf(b), mapOf(b to 2), takeNew = false)
        onSwitch {
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(alertTitle).assertDoesNotExist()
            compose.onNodeWithText(ctx.getString(R.string.saved_items_take_new_defaults_when)).assertExists()
        }
        assertTrue(SavedItemDefaults.takeNewDefaults(real))
        assertEquals(listOf(a), storedEnabled())
    }

    @Test fun `c - a switched-off keyboard's accents follow the switch`() {
        keyboards(listOf(a), mapOf(a to 1, de to 3), takeNew = true)
        real.edit().putBoolean(SavedItemDefaults.PREF, false).commit()
        SavedItemDefaults.applySwitch(ctx, real, false)
        val deNow = de.with(ExtraValue.MORE_POPUPS, "all")
        assertEquals(listOf(b), storedEnabled())
        assertEquals(mapOf(b to 1, deNow to 3), ids(), "the switched-off keyboard kept its accents")
        assertEquals(11, pools()[b]); assertEquals(13, pools()[deNow]); assertFalse(de in pools())
        assertFalse(deNow in storedEnabled()) // (still switched off)
        assertTrue(real.contains(KeyboardProfiles.ownKey(3, "test_own_setting")))
    }
}

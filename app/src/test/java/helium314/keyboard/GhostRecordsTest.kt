// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.AppearanceDraft
import helium314.keyboard.settings.SavedItemDefaults
import helium314.keyboard.settings.dialogs.StartSettings
import helium314.keyboard.settings.screens.createKeyboard
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.newKeyboardFor
import helium314.keyboard.settings.screens.nextNumbered
import helium314.keyboard.settings.screens.renameKeyboard
import helium314.keyboard.settings.screens.scriptRows
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ghost records (owner 2026-10-11: "Ok, A + net"): a keyboard's identity keys its set id and its pool. A lookup used to
 * create them, so a listener reading the selected keyboard while a changed keyboard's records were on their way gave
 * its old identity new empty ones: a ghost in "Switched-off keyboards". Now lookups never create records; only adding,
 * copying, a restore, the one-time upgrades and the net (every listed keyboard without them, at start and when the list
 * is loaded again or added to) do. Keyboards: ids 1.., pools 11.., the first one selected unless said.
 */
@RunWith(RobolectricTestRunner::class)
class GhostRecordsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val en = SubtypeUtilsAdditional.createDefaultSubtype(Locale.US).toSettingsSubtype()
    private val de = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private val fr = SubtypeSettings.getResourceSubtypesForLocale(Locale.FRANCE).first().toSettingsSubtype()

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        LearnedStores.init(ctx) // (its listener on the pools: what read the old identity)
    }
    @After fun tearDown() {
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
        LearnedStores.refresh(real)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Separate settings, the update done; [enabled] listed, [selected] in use; each its id (1..) and pool (11..). */
    private fun keyboards(enabled: List<SettingsSubtype>, selected: SettingsSubtype = enabled.first(),
                          extra: (android.content.SharedPreferences.Editor) -> Unit = {}) {
        val ids = enabled.withIndex().associate { (i, k) -> k.toPref() to i + 1 }
        val e = real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject(ids).toString())
            .putInt("keyboard_profile_next_id", enabled.size + 1)
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(ids.mapValues { 10 + it.value }).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 10 + enabled.size + 1).putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled.filter { !it.isSameAsDefault() }))
            .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled))
            .putString(Settings.PREF_SELECTED_SUBTYPE, selected.toPref())
        extra(e)
        e.commit()
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun ids() = KeyboardProfiles.idEntries(real).mapKeys { it.key.toSettingsSubtype() }
    private fun pools() = storedPools(real).mapKeys { it.key.toSettingsSubtype() }
    private fun selected() = KeyboardProfiles.selectedKeyboard(real)
    private fun storedEnabled() = SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ENABLED_SUBTYPES, "")!!)

    /** No ghost: exactly [ids] and their pools (10 + id), nothing switched off. */
    private fun assertRecords(ids: Map<SettingsSubtype, Int>) {
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real), "a ghost in Switched-off keyboards")
        assertEquals(ids, ids())
        assertEquals(ids.mapValues { 10 + it.value }, pools())
    }

    @Test fun `a - the selected keyboard renamed on the Keyboards screen - its records move, no ghost of the old identity`() {
        keyboards(listOf(en, de))
        val renamed = assertNotNull(renameKeyboard(ctx, en, "Work")) // (the Rename dialog's OK)
        assertEquals(renamed, selected())
        assertEquals(listOf(renamed, de), storedEnabled())
        assertRecords(mapOf(renamed to 1, de to 2))
        assertEquals(11, LearnedStores.currentPool)
        KeyboardProfiles.refreshImeId(real)
        assertEquals(1, KeyboardProfiles.imeId)
    }

    @Test fun `b - a layout change of the selected keyboard - its records move, no ghost`() {
        keyboards(listOf(en, de))
        val changed = en.withLayout(LayoutType.MAIN, "dvorak")
        SubtypeUtilsAdditional.changeAdditionalSubtype(en, changed, ctx)
        assertEquals(changed, selected())
        assertRecords(mapOf(changed to 1, de to 2))
        assertEquals(11, LearnedStores.currentPool)
    }

    @Test fun `c - the saved-items switch renames the selected keyboard to keep accents apart - no ghost`() {
        // (as ReReview2S3Test: popup set Mine holds the accents at its default; b differs from a only in accents)
        val a = SettingsSubtype(Locale.US, "")
        val b = a.with(ExtraValue.MORE_POPUPS, "all")
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(),
            atDefault = setOf(KeyPopupOverrides.ACCENTS))))
        keyboards(listOf(a, b), selected = b) { e ->
            e.putBoolean(SavedItemDefaults.PREF, false)
            for (id in 1..2) e.putString(KeyboardProfiles.ownKey(id, KeyPopupOverrides.PREF_SELECTED_SET), "Mine")
        }
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        val plan = SavedItemDefaults.accentsPlan(real, true) { keyboardName(it, ctx) }
        assertEquals(listOf(b), plan.renames.map { it.from }) // (the selected one is renamed, then follows)
        SavedItemDefaults.applySwitch(ctx, real, true, plan)
        val renamed = a.with(ExtraValue.KEYBOARD_NAME, java.net.URLEncoder.encode(keyboardName(a, ctx) + " 2", "UTF-8"))
        assertEquals(listOf(a, renamed), storedEnabled())
        assertEquals(renamed, selected())
        assertRecords(mapOf(a to 1, renamed to 2))
        assertEquals(12, LearnedStores.currentPool)
    }

    @Test fun `d - reading settings and learned words of a keyboard not in the list creates nothing`() {
        keyboards(listOf(en))
        real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, de.toPref()).commit() // (selected, not listed, no records)
        LearnedStores.refresh(real)
        KeyboardProfiles.refreshImeId(real)
        ProfilePreferences(real) { KeyboardProfiles.mainEditingId(real) }.getString(Settings.PREF_KEY_FONT, null)
        ctx.prefs().getString(Settings.PREF_KEY_FONT, null)
        assertTrue(scriptRows(ctx, de).isEmpty())
        real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, en.toPref()).commit()
        assertRecords(mapOf(en to 1))
    }

    @Test fun `e - the net - a listed keyboard without records gets them when the list is loaded or added to, no other`() {
        keyboards(listOf(en, de))
        // (de's records gone; fr's never there and it isn't listed)
        real.edit().putString("keyboard_profile_ids", JSONObject(mapOf(en.toPref() to 1)).toString())
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(en.toPref() to 11)).toString()).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertRecords(mapOf(en to 1, de to 3)) // (new numbers, never one given out before)
        // gone again, and a keyboard added: both get theirs
        real.edit().putString("keyboard_profile_ids", JSONObject(mapOf(en.toPref() to 1)).toString())
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(en.toPref() to 11)).toString()).commit()
        SubtypeSettings.addEnabledSubtype(ctx.prefs(), fr.toAdditionalSubtype())
        assertEquals(listOf(en, de, fr), storedEnabled())
        assertRecords(mapOf(en to 1, de to 4, fr to 5))
    }

    @Test fun `f - adding or copying a keyboard creates its records at once`() {
        keyboards(listOf(en))
        val added = createKeyboard(ctx, newKeyboardFor(ctx, Locale.GERMANY), null, StartSettings.Defaults)
        val copy = createKeyboard(ctx, nextNumbered(ctx, en), en, StartSettings.From(en))
        assertEquals(listOf(en, added, copy), storedEnabled())
        assertEquals(setOf(en, added, copy), ids().keys)
        assertEquals(setOf(en, added, copy), pools().keys)
        assertEquals(3, ids().values.toSet().size)
        assertEquals(3, pools().values.toSet().size)
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real))
    }
}

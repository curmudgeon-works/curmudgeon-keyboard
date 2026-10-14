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
import helium314.keyboard.settings.screens.newKeyboardFor
import helium314.keyboard.settings.screens.nextNumbered
import helium314.keyboard.settings.screens.renameKeyboard
import helium314.keyboard.settings.screens.scriptRows
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ghost records (owner 2026-10-11: "Ok, A + net"): a keyboard's set and pool are found by it. A lookup used to create
 * them, so a listener reading the selected keyboard while a changed keyboard's records were on their way gave its old
 * identity new empty ones: a ghost in "Switched-off keyboards". Now lookups never create records; only adding, copying,
 * a restore, the one-time upgrades and the net (every listed keyboard without them, at start and when the list is
 * loaded again or added to) do. (owner 2026-10-11: keyboards have unique ids) A change keeps the id: nothing moves, so
 * the tests check the ids and pools stay as they were and nothing is switched off. Keyboards: ids k1.., pools 11.., the
 * first one selected unless said.
 */
@RunWith(RobolectricTestRunner::class)
class GhostRecordsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val enPlain = SubtypeUtilsAdditional.createDefaultSubtype(Locale.US).toSettingsSubtype()
    private val dePlain = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()
    private val frPlain = SubtypeSettings.getAvailableSubtypeLocales().first { it.language == "fr" }
        .let { SubtypeSettings.getResourceSubtypesForLocale(it).first().toSettingsSubtype() }
    private val en = enPlain.withId(1)
    private val de = dePlain.withId(2)

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.init(ctx)
        LearnedStores.init(ctx) // (its listener on the pools: what read the old identity)
    }
    @After fun tearDown() {
        randomIds()
        AppearanceDraft.close()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
        LearnedStores.refresh(real)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Separate settings, the update done; [enabled] (with ids k1..) listed, [selected] in use; pools 11.. in order. */
    private fun keyboards(enabled: List<SettingsSubtype>, selected: SettingsSubtype = enabled.first(),
                          extra: (android.content.SharedPreferences.Editor) -> Unit = {}) {
        val e = real.edit().putBoolean("separate_settings_per_keyboard", true)
            .putString(LearnedKeyboards.PREF_POOLS, poolsJson(enabled.withIndex().associate { (i, k) -> k to 11 + i }))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 10 + enabled.size + 1).putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled))
            .putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled))
            .putString(Settings.PREF_SELECTED_SUBTYPE, selected.toPref())
        extra(e)
        e.commit()
        KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.refreshImeId(real)
    }

    private fun selected() = KeyboardProfiles.selectedKeyboard(real)
    private fun storedEnabled() = storedEnabled(real)

    /** No ghost: the listed keyboards are exactly [pools]' keys (by id), each with its pool, no set of another id,
     *  nothing switched off. */
    private fun assertRecords(pools: Map<SettingsSubtype, Int>) {
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real), "a ghost in Switched-off keyboards")
        val ids = pools.keys.mapTo(HashSet()) { it.keyboardId!! }
        assertEquals(ids, storedEnabled().mapNotNullTo(HashSet()) { it.keyboardId })
        assertEquals(pools.mapKeys { it.key.keyboardId!! }, storedPools(real))
        assertTrue(KeyboardProfiles.setIds(real.all).all { it in ids }, "a set of a keyboard not listed")
    }

    @Test fun `a - the selected keyboard renamed on the Keyboards screen - its id stays, no ghost of the old identity`() {
        keyboards(listOf(en, de))
        val renamed = renameKeyboard(ctx, en, "Work") // (the Rename dialog's OK)
        assertEquals(kid(1), renamed.keyboardId)
        assertEquals(renamed, selected())
        assertEquals(listOf(renamed, de), storedEnabled())
        assertRecords(mapOf(renamed to 11, de to 12))
        assertEquals(11, LearnedStores.currentPool)
        KeyboardProfiles.refreshImeId(real)
        assertEquals(kid(1), KeyboardProfiles.imeId)
    }

    @Test fun `b - a layout change of the selected keyboard - its id stays, no ghost`() {
        keyboards(listOf(en, de))
        val changed = en.withLayout(LayoutType.MAIN, "dvorak")
        SubtypeUtilsAdditional.changeAdditionalSubtype(en, changed, ctx)
        assertEquals(changed, selected())
        assertRecords(mapOf(changed to 11, de to 12))
        assertEquals(11, LearnedStores.currentPool)
    }

    // (owner 2026-10-11: keyboards have unique ids) before, the switch renamed the selected keyboard to keep it apart
    // from one set up the same (review S3's alert); now it just changes the accents, both keep their own set and pool
    @Test fun `c - the saved-items switch just changes the selected keyboard's accents - no rename, both keep their own set and pool`() {
        // (as ReReview2S3Test: popup set Mine holds the accents at its default; b differs from a only in accents)
        val a = SettingsSubtype(Locale.US, "").withId(1)
        val b = SettingsSubtype(Locale.US, "").with(ExtraValue.MORE_POPUPS, "all").withId(2)
        KeyPopupOverrides.saveSets(real, listOf(KeyPopupOverrides.UserSet("Mine", "all", null, emptyMap(),
            atDefault = setOf(KeyPopupOverrides.ACCENTS))))
        keyboards(listOf(a, b), selected = b) { e ->
            e.putBoolean(SavedItemDefaults.PREF, false)
            for (n in 1..2) e.putString(idKey(n, KeyPopupOverrides.PREF_SELECTED_SET), "Mine")
        }
        real.edit().putBoolean(SavedItemDefaults.PREF, true).commit()
        val plan = SavedItemDefaults.accentsPlan(real, true)
        val followed = b.without(ExtraValue.MORE_POPUPS) // (a's setup, b's id)
        assertEquals(listOf(SavedItemDefaults.KeyboardChange(b, followed)), plan.changes)
        SavedItemDefaults.applySwitch(ctx, real, true, plan)
        assertEquals(listOf(a, followed), storedEnabled())
        assertEquals(a.withoutIds(), followed.withoutIds()) // (set up alike: two keyboards all the same)
        assertEquals(followed, selected())
        assertRecords(mapOf(a to 11, followed to 12))
        assertEquals("Mine", real.getString(idKey(1, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals("Mine", real.getString(idKey(2, KeyPopupOverrides.PREF_SELECTED_SET), null))
        assertEquals(12, LearnedStores.currentPool)
    }

    @Test fun `d - reading settings and learned words of a keyboard not in the list creates nothing`() {
        keyboards(listOf(en))
        real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, dePlain.toPref()).commit() // (selected, not listed, no id)
        LearnedStores.refresh(real)
        KeyboardProfiles.refreshImeId(real)
        ProfilePreferences(real) { KeyboardProfiles.mainEditingId(real) }.getString(Settings.PREF_KEY_FONT, null)
        ctx.prefs().getString(Settings.PREF_KEY_FONT, null)
        assertTrue(scriptRows(ctx, dePlain).isEmpty())
        real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, en.toPref()).commit()
        assertRecords(mapOf(en to 11))
    }

    @Test fun `e - the net - a listed keyboard without records gets them when the list is loaded or added to, no other`() {
        keyboards(listOf(en, de))
        // (de's pool gone; fr's never there and it isn't listed)
        real.edit().putString(LearnedKeyboards.PREF_POOLS, poolsJson(mapOf(en to 11))).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertRecords(mapOf(en to 11, de to 13)) // (a new number, never one given out before)
        // gone again, and a keyboard added (no id: it gets one): both get theirs
        real.edit().putString(LearnedKeyboards.PREF_POOLS, poolsJson(mapOf(en to 11))).commit()
        countingIds()
        val fr = SubtypeSettings.addEnabledSubtype(ctx.prefs(), frPlain.toAdditionalSubtype())
        assertEquals("k1000", fr.keyboardId)
        assertEquals(frPlain, fr.withoutIds())
        assertEquals(listOf(en, de, fr), storedEnabled())
        assertRecords(mapOf(en to 11, de to 14, fr to 15))
    }

    // (a full test run 2026-10-11: a reload found the list empty while a test rewrote it, stored the default, and the net
    // gave it records that stayed as a switched-off keyboard; a restore later switched it on)
    @Test fun `g - a default stored while the list is found empty gets no records, the list written after it does`() {
        real.edit().clear().putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx) // (found empty: the default stored)
        val default = storedEnabled().single()
        val defaultId = assertNotNull(default.keyboardId) // (a new keyboard: its own id, in the added list)
        assertTrue(defaultId !in KeyboardProfiles.setIds(real.all) && defaultId !in storedPools(real))
        // (the list written, the added list left as it is: the default stays there, with no records)
        real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, de.toPref()).putString(Settings.PREF_SELECTED_SUBTYPE, de.toPref()).commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real))
        assertEquals(setOf(kid(2)), storedPools(real).keys)
        assertTrue(KeyboardProfiles.setIds(real.all).all { it == kid(2) })
    }

    // (owner 2026-10-11 "OK": Layout & typing's Discard puts the learned words' records back with the keyboard's set)
    @Test fun `h - Discard on Layout and typing after a layout change - the keyboard has its own pool again, no ghost`() {
        keyboards(listOf(en, de))
        for (leave in listOf(false, true)) {
            val draft = helium314.keyboard.settings.LayoutDraft.of(ctx, en.toPref())
            val changed = en.withLayout(LayoutType.MAIN, "dvorak")
            SubtypeUtilsAdditional.changeAdditionalSubtype(en, changed, ctx) // (the layout picked on the screen)
            assertEquals(mapOf(kid(1) to 11, kid(2) to 12), storedPools(real)) // (by id: nothing moved)
            if (leave) helium314.keyboard.settings.LayoutDraft.rejectOpen(ctx) // (the app left with the screen open)
            else draft.reject(ctx) // (Discard)
            assertEquals(en, selected(), "leave=$leave")
            assertRecords(mapOf(en to 11, de to 12))
            assertEquals(11, LearnedStores.currentPool, "leave=$leave")
        }
    }

    // (2026-10-12 U9: the keyboard in use writes its emoji recents, emoji page and toolbar state as you type; during a
    // whole restore its id was of before the backup. A write for no keyboard of the phone is dropped, not a ghost set)
    @Test fun `i - a write for the keyboard in use that is no keyboard of the phone creates no set`() {
        keyboards(listOf(en))
        real.edit().putString(Settings.PREF_SELECTED_SUBTYPE, de.toPref()).commit() // (k2: in neither list)
        KeyboardProfiles.refreshImeId(real)
        assertEquals(kid(2), KeyboardProfiles.imeId)
        ctx.prefs().edit().putString(Settings.PREF_EMOJI_RECENT_KEYS, "[]").commit()
        assertFalse(real.all.keys.any { it.startsWith("p${kid(2)}/") }, "a set of a keyboard not on the phone")
    }

    @Test fun `j - the restore window - a write with separate settings off lands in no keyboard of the list in memory`() {
        keyboards(listOf(en)) { it.putBoolean("separate_settings_per_keyboard", false) }
        KeyboardProfiles.refreshImeId(real)
        // (as a whole restore: the preferences cleared, the backup's lines not in yet, nothing reloaded)
        real.edit().clear().commit()
        ctx.prefs().edit().putString(Settings.PREF_EMOJI_RECENT_KEYS, "[]").putBoolean(Settings.PREF_TOOLBAR_OPENED_BY_KEY, true).commit()
        assertEquals(emptySet(), KeyboardProfiles.setIds(real.all), "a set of the keyboard list in memory")
    }

    @Test fun `k - writes for a listed keyboard, a switched-off one and the common settings still land in their sets`() {
        keyboards(listOf(en)) { it.putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listOf(en, de))) }
        ctx.prefs().edit().putString(Settings.PREF_EMOJI_RECENT_KEYS, "[1]").commit() // (the keyboard in use, k1)
        ProfilePreferences(real) { kid(2) }.edit().putString(Settings.PREF_EMOJI_RECENT_KEYS, "[2]").commit() // (switched off)
        assertEquals("[1]", real.getString(idKey(1, Settings.PREF_EMOJI_RECENT_KEYS), null))
        assertEquals("[2]", real.getString(idKey(2, Settings.PREF_EMOJI_RECENT_KEYS), null))
        // separate settings off: the main keyboard's set, the common settings
        real.edit().putBoolean("separate_settings_per_keyboard", false).commit()
        KeyboardProfiles.refreshImeId(real)
        ctx.prefs().edit().putString(Settings.PREF_EMOJI_RECENT_KEYS, "[3]").commit()
        assertEquals("[3]", real.getString(idKey(1, Settings.PREF_EMOJI_RECENT_KEYS), null))
    }

    @Test fun `f - adding or copying a keyboard creates its records at once`() {
        keyboards(listOf(en))
        val added = createKeyboard(ctx, newKeyboardFor(ctx, Locale.GERMANY), null, StartSettings.Defaults)
        val copy = createKeyboard(ctx, nextNumbered(ctx, en), en, StartSettings.From(en))
        assertEquals(listOf(en, added, copy), storedEnabled())
        val ids = listOf(en, added, copy).map { assertNotNull(it.keyboardId) }
        assertEquals(3, ids.toSet().size)
        assertEquals(null, added.keyboardFrom)
        assertEquals(kid(1), copy.keyboardFrom) // (a copy: came from the original)
        assertEquals(ids.toSet(), storedPools(real).keys)
        assertEquals(3, storedPools(real).values.toSet().size)
        assertEquals(emptyList(), SwitchedOffKeyboards.list(real))
    }
}

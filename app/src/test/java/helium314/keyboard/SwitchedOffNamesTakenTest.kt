// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.StartSettings
import helium314.keyboard.settings.screens.createKeyboard
import helium314.keyboard.settings.screens.newKeyboardFor
import helium314.keyboard.settings.screens.nextNumbered
import helium314.keyboard.settings.screens.renameKeyboard
import helium314.keyboard.settings.screens.withKeyboardName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Audit rows 15-17 (owner 2026-10-11: switched-off keyboards count like switched-on ones): Copy and Add number past a
 *  switched-off keyboard set up the same; its own set and pool stay its own. (owner 2026-10-11: keyboards have unique
 *  ids) A rename onto another keyboard's setup is no longer blocked ("Not changed: ... already set up like this" is
 *  gone): the renamed keyboard keeps its id, both stay apart with their own set and pool. Enabled k1.., off k9. */
@RunWith(RobolectricTestRunner::class)
class SwitchedOffNamesTakenTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val enPlain = SubtypeUtilsAdditional.createDefaultSubtype(Locale.US).toSettingsSubtype()
    private val dePlain = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    /** [enabled] (k1..) in the list, each with its pool; [off] (k9) switched off with its own set (a value in it) and pool. */
    private fun keyboards(enabled: List<SettingsSubtype>, off: SettingsSubtype): List<SettingsSubtype> {
        val listed = enabled.mapIndexed { i, k -> k.withId(i + 1) }
        offKeyboard = off.withId(9)
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putBoolean(LearnedKeyboards.PREF_DONE, true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(listed + offKeyboard))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(listed))
            putString(Settings.PREF_SELECTED_SUBTYPE, listed.first().toPref())
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        enabledPools = listed.map { LearnedKeyboards.poolFor(real, it) }.toSet()
        offPool = LearnedKeyboards.poolFor(real, offKeyboard)
        assertFalse(offPool in enabledPools)
        real.edit { putString(ownKey, "font:Mine.ttf") }
        assertEquals(listOf(offKeyboard), SwitchedOffKeyboards.list(real))
        return listed
    }
    private var offKeyboard = enPlain
    private var offPool = 0
    private var enabledPools = emptySet<Int>()
    private val ownKey = idKey(9, Settings.PREF_KEY_FONT)

    private fun enabledNow() = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }

    /** The switched-off keyboard as it was: listed as switched off, its set and pool its own. */
    private fun assertOffUntouched() {
        assertEquals(listOf(offKeyboard), SwitchedOffKeyboards.list(real))
        assertEquals("font:Mine.ttf", real.getString(ownKey, null))
        assertEquals(offPool, LearnedKeyboards.poolOrNull(real, offKeyboard))
    }

    @Test fun `copy of English while a switched-off English 2 exists is English 3, the leftover's set and pool untouched`() {
        val (en) = keyboards(listOf(enPlain), enPlain.with(ExtraValue.KEYBOARD_COPY, "2"))
        val added = nextNumbered(ctx, en)
        assertEquals("3", added.getExtraValueOf(ExtraValue.KEYBOARD_COPY))
        assertNull(added.keyboardId) // (it gets one when it is added)
        val copy = createKeyboard(ctx, added, en, StartSettings.Defaults)
        assertEquals("3", copy.getExtraValueOf(ExtraValue.KEYBOARD_COPY))
        assertEquals(kid(1), copy.keyboardFrom) // (a copy: a new id, came from the original's)
        assertNotEquals(kid(1), assertNotNull(copy.keyboardId))
        assertEquals(listOf(en, copy), enabledNow())
        assertOffUntouched()
        assertFalse(assertNotNull(LearnedKeyboards.poolOrNull(real, copy)) in enabledPools + offPool)
    }

    @Test fun `add English while a switched-off plain English exists gets a copy number, the leftover stays off and untouched`() {
        val (de) = keyboards(listOf(dePlain), enPlain)
        val added = newKeyboardFor(ctx, Locale.US)
        assertEquals("2", added.getExtraValueOf(ExtraValue.KEYBOARD_COPY))
        val created = createKeyboard(ctx, added, null, StartSettings.Defaults)
        assertEquals(added, created.withoutIds())
        assertNull(created.keyboardFrom) // (Add: a new keyboard, from none)
        assertEquals(listOf(de, created), enabledNow())
        assertOffUntouched()
    }

    // (owner 2026-10-11: keyboards have unique ids) before: blocked with "Not changed: Work (switched off) is already set
    // up like this"; now the rename goes through, the two keyboards set up alike stay apart by their ids
    @Test fun `renaming to a switched-off keyboard's setup goes through, both keep their own set and pool`() {
        val (en) = keyboards(listOf(enPlain), withKeyboardName(enPlain, "Work"))
        val pools = real.getString(LearnedKeyboards.PREF_POOLS, null)
        val renamed = renameKeyboard(ctx, en, "Work")
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(kid(1), renamed.keyboardId)
        assertEquals(offKeyboard.withoutIds(), renamed.withoutIds())
        assertEquals(listOf(renamed), enabledNow())
        assertEquals(pools, real.getString(LearnedKeyboards.PREF_POOLS, null)) // (nothing moved)
        assertOffUntouched()
    }

    // (owner 2026-10-11: keyboards have unique ids) before: blocked with "Not changed: Work is already set up like this"
    @Test fun `renaming to another enabled keyboard's setup goes through too, and a free name`() {
        val (en, work) = keyboards(listOf(enPlain, withKeyboardName(enPlain, "Work")), dePlain)
        val renamed = renameKeyboard(ctx, en, "Work")
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(work.withoutIds(), renamed.withoutIds())
        assertNotEquals(work, renamed)
        assertEquals(listOf(renamed, work), enabledNow())
        val home = renameKeyboard(ctx, renamed, "Home")
        assertEquals(kid(1), home.keyboardId)
        assertEquals(listOf(home, work), enabledNow())
        assertOffUntouched()
    }
}

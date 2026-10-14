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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Audit rows 15-17 (owner 2026-10-11: switched-off keyboards count like switched-on ones): a switched-off keyboard's
 *  definition is taken. Copy and Add number past it, rename onto it is blocked; its own set and pool stay its own. */
@RunWith(RobolectricTestRunner::class)
class SwitchedOffNamesTakenTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val en = SubtypeUtilsAdditional.createDefaultSubtype(Locale.US).toSettingsSubtype()
    private val de = SubtypeSettings.getResourceSubtypesForLocale(Locale.GERMANY).first().toSettingsSubtype()

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        KeyboardProfiles.refreshImeId(real)
    }

    /** [enabled] in the list, each with its own set and pool; [off] switched off with its own set (a value in it) and pool. */
    private fun keyboards(enabled: List<SettingsSubtype>, off: SettingsSubtype) {
        real.edit {
            putBoolean("separate_settings_per_keyboard", true)
            putBoolean(LearnedKeyboards.PREF_DONE, true)
            putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes((enabled + off).filter { !it.isSameAsDefault() }))
            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(enabled))
        }
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        val ids = enabled.map { KeyboardProfiles.idFor(real, it) }
        enabledPools = enabled.map { LearnedKeyboards.poolFor(real, it) }.toSet()
        offId = KeyboardProfiles.idFor(real, off)
        offPool = LearnedKeyboards.poolFor(real, off)
        assertFalse(offId in ids); assertFalse(offPool in enabledPools)
        real.edit { putString(ownKey, "font:Mine.ttf") }
    }
    private var offId = 0
    private var offPool = 0
    private var enabledPools = emptySet<Int>()
    private val ownKey get() = "p$offId/${Settings.PREF_KEY_FONT}"

    private fun enabledNow() = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }

    @Test fun `copy of English while a switched-off English 2 exists is English 3, the leftover's set and pool untouched`() {
        val en2 = en.with(ExtraValue.KEYBOARD_COPY, "2")
        keyboards(listOf(en), en2)
        val added = nextNumbered(ctx, en)
        assertEquals("3", added.getExtraValueOf(ExtraValue.KEYBOARD_COPY))
        val copy = createKeyboard(ctx, added, en, StartSettings.Defaults)
        assertEquals("3", copy.getExtraValueOf(ExtraValue.KEYBOARD_COPY))
        assertEquals(listOf(en, copy), enabledNow())
        assertEquals(offId, KeyboardProfiles.idFor(real, en2))
        assertEquals("font:Mine.ttf", real.getString(ownKey, null))
        assertEquals(offPool, LearnedKeyboards.poolFor(real, en2))
        assertFalse(LearnedKeyboards.poolFor(real, copy) in enabledPools + offPool)
    }

    @Test fun `add English while a switched-off plain English exists gets a copy number, the leftover stays off and untouched`() {
        keyboards(listOf(de), en)
        val added = newKeyboardFor(ctx, Locale.US)
        assertEquals("2", added.getExtraValueOf(ExtraValue.KEYBOARD_COPY))
        createKeyboard(ctx, added, null, StartSettings.Defaults)
        assertEquals(listOf(de, added), enabledNow())
        assertEquals(offId, KeyboardProfiles.idFor(real, en))
        assertEquals("font:Mine.ttf", real.getString(ownKey, null))
        assertEquals(offPool, LearnedKeyboards.poolFor(real, en))
    }

    @Test fun `renaming to a switched-off keyboard's exact definition is blocked, nothing changes`() {
        val work = withKeyboardName(en, "Work")
        keyboards(listOf(en), work)
        val ids = KeyboardProfiles.idEntries(real)
        val pools = real.getString(LearnedKeyboards.PREF_POOLS, null)
        val additional = real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, null)
        assertNull(renameKeyboard(ctx, en, "Work"))
        assertEquals("Not changed: Work (switched off) is already set up like this", ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(en), enabledNow())
        assertEquals(ids, KeyboardProfiles.idEntries(real))
        assertEquals(pools, real.getString(LearnedKeyboards.PREF_POOLS, null))
        assertEquals(additional, real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, null))
        assertEquals("font:Mine.ttf", real.getString(ownKey, null))
    }

    @Test fun `renaming to another enabled keyboard's exact definition is blocked too, a free name goes through`() {
        val work = withKeyboardName(en, "Work")
        keyboards(listOf(en, work), de)
        assertNull(renameKeyboard(ctx, en, "Work"))
        assertEquals("Not changed: Work is already set up like this", ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(en, work), enabledNow())
        val home = assertNotNull(renameKeyboard(ctx, en, "Home"))
        assertEquals(listOf(home, work), enabledNow())
    }
}

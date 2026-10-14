// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.idleUntil
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.SwitchedOffKeyboards
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.settings.screens.KeyboardsScreen
import helium314.keyboard.settings.screens.deleteKeyboard
import helium314.keyboard.settings.screens.keyboardName
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Switched-off keyboards (in the ids map or with a pool, not in the list): one tile on the Keyboards screen with
 *  Advanced on, each to switch on or delete (owner 2026-10-11). */
@RunWith(RobolectricTestRunner::class)
class SwitchedOffTileTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private val body = BodyStoreIo()
    private val filesDirBefore = KeyboardProfiles.filesDir
    private val a by lazy { available("en-US") }
    private val b by lazy { available("de-DE") }
    private val c by lazy { available("fr") }
    private val d by lazy { available("it") }
    private val e by lazy { available("es") }
    private val f by lazy { available("nl") }
    private val own = "test_own_setting"

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        KeyboardProfiles.filesDir = File(ctx.cacheDir, "so_${System.nanoTime()}").apply { mkdirs() }
        LearnedKeyboards.storeIo = body
        SubtypeSettings.init(ctx)
        useKeyboards(ctx, real, a, b)
    }

    @After fun tearDown() {
        SettingsMode.set(ctx, Defaults.PREF_ADVANCED_SETTINGS)
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.filesDir = filesDirBefore
        // the work this test queued on the main thread (Compose's notice of the Advanced switch's state written above,
        // the screen's last taps) done here: dropped at the end of the test, it left the next class's settings screens
        // deaf to taps (EditingKeyboardTest's keyboard B didn't unfold)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Ids: a 1, b 2, c 3 (switched off), e 4 (switched off), f the shared set; pools: a 5, b 6, d 8 and e 9 (switched off);
     *  plus an identity that isn't a keyboard. */
    private fun leftovers() {
        real.edit().putString("keyboard_profile_ids", JSONObject(mapOf(a.toPref() to 1, b.toPref() to 2, c.toPref() to 3,
                e.toPref() to 4, f.toPref() to KeyboardProfiles.SHARED, "not a keyboard" to 5)).toString())
            .putInt("keyboard_profile_next_id", 6)
            .putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(a.toPref() to 5, b.toPref() to 6, d.toPref() to 8,
                e.toPref() to 9)).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 10).putBoolean(LearnedKeyboards.PREF_DONE, true)
            .putString(KeyboardProfiles.ownKey(3, own), "c's own").putString(KeyboardProfiles.ownKey(1, own), "a's own")
            .commit()
        body.store(ce, "Latn", 9, word("e9", 2, 100))
        body.store(ce, "Latn", 5, word("a5", 2, 100))
    }

    private fun enabled() = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
    private fun storedEnabled() = SubtypeSettings.createSettingsSubtypes(ctx.prefs().getString(Settings.PREF_ENABLED_SUBTYPES, "")!!)

    @Test fun `the switched-off keyboards - in the ids map, with a pool, or both, not the listed ones nor the shared set`() {
        leftovers()
        assertEquals(setOf(c, d, e), SwitchedOffKeyboards.list(real).toSet())
        assertEquals(3, SwitchedOffKeyboards.list(real).size) // (e once)
    }

    @Test fun `switch on - in the list, with its own settings and words as they were`() {
        leftovers()
        assertTrue(SwitchedOffKeyboards.switchOn(ctx, e))
        assertEquals(listOf(a, b, e), enabled())
        assertEquals(listOf(a, b, e), storedEnabled())
        assertEquals(4, KeyboardProfiles.idFor(real, e))
        assertEquals(9, storedPools(real)[e.toPref()])
        assertEquals(setOf("e9"), body.words(ce, "Latn", 9))
        assertEquals(setOf(c, d), SwitchedOffKeyboards.list(real).toSet())
        // switched on as it is: nothing started from another set
        assertTrue(SwitchedOffKeyboards.switchOn(ctx, c))
        assertEquals("c's own", real.getString(KeyboardProfiles.ownKey(3, own), null))
        assertEquals(3, KeyboardProfiles.idFor(real, c))
        assertEquals(setOf(d), SwitchedOffKeyboards.list(real).toSet())
    }

    @Test fun `switch on - a named keyboard is registered as an added one, one already listed is refused`() {
        val named = c.with(ExtraValue.KEYBOARD_NAME, "Work")
        real.edit().putString("keyboard_profile_ids", JSONObject(mapOf(named.toPref() to 3)).toString()).commit()
        assertEquals(listOf(named), SwitchedOffKeyboards.list(real))
        assertTrue(SwitchedOffKeyboards.switchOn(ctx, named))
        assertTrue(named.isAdditionalSubtype(ctx.prefs()))
        assertEquals(listOf(a, b, named), enabled())
        assertFalse(SwitchedOffKeyboards.switchOn(ctx, named))
        assertFalse(SwitchedOffKeyboards.switchOn(ctx, a))
        assertEquals(listOf(a, b, named), storedEnabled())
    }

    @Test fun `delete a switched-off keyboard - its id, own settings, pool and words go as a listed one's, the list stays`() {
        leftovers()
        deleteKeyboard(ctx, e, null) // (as the delete dialog's confirm calls it)
        deleteKeyboard(ctx, c, null)
        idleUntil { !LearnedStores.storeFile(ce, "Latn", 9).exists() }
        val ids = KeyboardProfiles.idEntries(real)
        assertFalse(e.toPref() in ids)
        assertFalse(c.toPref() in ids)
        assertEquals(null, real.getString(KeyboardProfiles.ownKey(3, own), null))
        assertFalse(e.toPref() in storedPools(real))
        assertFalse(LearnedStores.storeFile(ce, "Latn", 9).exists())
        assertEquals(listOf(d), SwitchedOffKeyboards.list(real))
        // the listed keyboards are untouched
        assertEquals(listOf(a, b), enabled())
        assertEquals(listOf(a, b), storedEnabled())
        assertEquals("a's own", real.getString(KeyboardProfiles.ownKey(1, own), null))
        assertEquals(setOf("a5"), body.words(ce, "Latn", 5))
    }

    private fun onScreen(block: () -> Unit) =
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { KeyboardsScreen { } } }.use { block() }

    private fun tile(n: Int) = ctx.getString(R.string.switched_off_keyboards, n)

    @Test fun `the tile - only with Advanced on, it unfolds the keyboards, and Switch on lists one`() {
        leftovers()
        SettingsMode.set(ctx, false)
        onScreen { compose.onNodeWithText(tile(3)).assertDoesNotExist() }
        SettingsMode.set(ctx, true)
        onScreen {
            compose.onNodeWithText(tile(3)).performScrollTo().performClick()
            compose.onNodeWithText(keyboardName(e, ctx)).performScrollTo().assertExists()
            compose.onNodeWithContentDescription(ctx.getString(R.string.switch_on_keyboard_of, keyboardName(e, ctx))).performClick()
            compose.onNodeWithText(tile(2)).assertExists()
            assertEquals(listOf(a, b, e), enabled())
        }
    }

    @Test fun `the tile - Delete asks as for a listed keyboard, and the tile goes with the last one`() {
        real.edit().putString("keyboard_profile_ids", JSONObject(mapOf(c.toPref() to 3)).toString())
            .putString(KeyboardProfiles.ownKey(3, own), "c's own").commit()
        SettingsMode.set(ctx, true)
        onScreen {
            compose.onNodeWithText(tile(1)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(ctx.getString(R.string.delete_keyboard_of, keyboardName(c, ctx))).performClick()
            compose.onNodeWithText(ctx.getString(R.string.delete_keyboard_message, keyboardName(c, ctx))).assertExists()
            compose.onNode(hasText(ctx.getString(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText(tile(1)).assertDoesNotExist()
        }
        assertFalse(c.toPref() in KeyboardProfiles.idEntries(real))
        assertEquals(null, real.getString(KeyboardProfiles.ownKey(3, own), null))
        assertEquals(listOf(a, b), enabled())
    }
}

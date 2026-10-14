// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.idleUntil
import helium314.keyboard.latin.personalization.readList
import helium314.keyboard.latin.personalization.snapshot
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.personalization.writeList
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.dialogs.ImportWordsDialog
import helium314.keyboard.settings.screens.KeyboardsScreen
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.kid
import helium314.keyboard.poolsJson
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A new keyboard starts with words from the other keyboards when the dialog asked once says so (2026-10-10): through
 * the dialog (ImportWordsDialog) and the Keyboards screen's Add keyboard > Copy of.
 */
@RunWith(RobolectricTestRunner::class)
class LearnedImportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private lateinit var de: File
    private val io = BodyStoreIo()
    private val filesDirBefore = KeyboardProfiles.filesDir
    private val a by lazy { available("en-US").withId(1) }
    private val b by lazy { available("de-DE").withId(2) }
    private val c by lazy { available("fr").withId(3) }

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        de = File(ctx.cacheDir, "de_${System.nanoTime()}").apply { mkdirs() }
        KeyboardProfiles.filesDir = de
        assertNotEquals(ce.canonicalPath, de.canonicalPath)
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
    }

    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.filesDir = filesDirBefore
    }

    /** After the update: [pools] each keyboard's. */
    private fun updated(pools: Map<SettingsSubtype, Int>, next: Int) {
        real.edit().putString(LearnedKeyboards.PREF_POOLS, poolsJson(pools))
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, next).putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
    }

    private fun showDialog(keyboard: SettingsSubtype, copyOf: SettingsSubtype?, onDismiss: () -> Unit = {}) =
        ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { ImportWordsDialog(keyboard, copyOf, onDismiss) }
        }

    private fun title(keyboard: SettingsSubtype) = ctx.getString(R.string.import_words_title, keyboardName(keyboard, ctx))
    private val learnedText get() = ctx.getString(R.string.learned_words_list_learned)
    private val blacklistedText get() = ctx.getString(R.string.learned_words_list_blacklisted)

    /** The two keyboards A (words) and B (a blacklist, and words not to be taken), C added with a few words of its own. */
    private fun phoneWithThree() {
        useKeyboards(ctx, real, a, b, c)
        updated(mapOf(a to 5, b to 6, c to 7), next = 8)
        io.store(ce, "Latn", 5, word("alpha", 2, 100), word("beta", 5, 110), word("zeta", 3, 120))
        writeList(ce, "Latn", 5, "fromA\n")
        io.store(ce, "Latn", 6, word("bonly", 4, 100))
        writeList(ce, "Latn", 6, "bad\t2\nworse\n")
        io.store(ce, "Latn", 7, word("beta", 1, 50), word("zeta", 9, 60))
        writeList(ce, "Latn", 7, "worse\t3\n")
    }

    @Test fun `Import of A's learned words and B's blacklist - C has them, highest count, A and B unchanged, CE only`() {
        phoneWithThree()
        val others = snapshot(ce).filterKeys { "k5" in it || "k6" in it }
        val deBefore = snapshot(de)
        showDialog(c, null).use {
            compose.onNodeWithText(title(c)).assertExists()
            // the boxes in the order of the keyboards: A's Learned, B's Blacklisted
            compose.onAllNodesWithText(learnedText)[0].performClick()
            compose.onAllNodesWithText(blacklistedText)[1].performClick()
            compose.onNodeWithText(ctx.getString(R.string.import_words_button)).performClick()
            idleUntil { ShadowToast.getTextOfLatestToast() != null }
        }
        assertEquals(mapOf("alpha" to (2 to 100), "beta" to (5 to 110), "zeta" to (9 to 120)), io.read(ce, "Latn", 7))
        assertEquals(mapOf("worse" to RemovedWords.Entry(3), "bad" to RemovedWords.Entry(2)), readList(ce, "Latn", 7))
        assertEquals(others, snapshot(ce).filterKeys { "k5" in it || "k6" in it }, "A's or B's files changed")
        assertEquals(deBefore, snapshot(de), "written to device-protected storage")
        // the counts, never the words
        assertEquals(ctx.getString(R.string.import_words_done, 3, 2), ShadowToast.getTextOfLatestToast())
        for (pref in LearnedKeyboards.buttonPrefs) assertFalse(real.contains(pref), "$pref written")
    }

    @Test fun `Cancel - the new keyboard has no files`() {
        useKeyboards(ctx, real, a, b, c)
        updated(mapOf(a to 5, b to 6), next = 8)
        io.store(ce, "Latn", 5, word("alpha", 2, 100))
        writeList(ce, "Latn", 6, "bad\n")
        var dismissed = false
        showDialog(c, null) { dismissed = true }.use {
            compose.onAllNodesWithText(learnedText)[0].performClick()
            compose.onAllNodesWithText(blacklistedText)[1].performClick()
            compose.onNodeWithText(ctx.getString(android.R.string.cancel)).performClick()
            val until = System.currentTimeMillis() + 500
            idleUntil { System.currentTimeMillis() > until } // (anything started would land meanwhile)
        }
        assertTrue(dismissed)
        val pool = storedPools(real)[kid(3)]
        if (pool != null) assertTrue(LearnedStores.scriptsOnDisk(ce, pool).isEmpty(), "files: ${snapshot(ce).keys}")
        assertEquals(setOf(5, 6), LearnedStores.keyboardPoolsOnDisk(ce))
    }

    @Test fun `Copy of A on the Keyboards screen - the dialog comes with A's boxes ticked, Import takes A's words`() {
        useKeyboards(ctx, real, a, b)
        updated(mapOf(a to 5, b to 6), next = 8)
        io.store(ce, "Latn", 5, word("alpha", 2, 100))
        writeList(ce, "Latn", 5, "fromA\n")
        io.store(ce, "Latn", 6, word("bonly", 4, 100))
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { KeyboardsScreen { } } }.use {
            compose.onNodeWithText(ctx.getString(R.string.add_keyboard)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.copy_of_keyboard, keyboardName(a, ctx))).performClick()
            compose.waitForIdle()
            // one dialog (2026-10-10): A's settings and A's two lists picked; nothing added until Add
            assertEquals(2, SubtypeSettings.getEnabledSubtypes(true).size)
            val boxes = compose.onAllNodes(isToggleable() and hasAnyAncestor(isDialog())) // (the screen has switches)
            boxes[0].assertIsOn(); boxes[1].assertIsOn() // A's
            boxes[2].assertIsOff(); boxes[3].assertIsOff() // B's
            compose.onNodeWithText(ctx.getString(R.string.add_keyboard_button)).performClick()
            idleUntil { ShadowToast.getTextOfLatestToast() != null }
            val copy = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }.last()
            assertNotEquals(a, copy)
            val pool = storedPools(real)[copy.keyboardId!!]!!
            assertEquals(setOf("alpha"), io.words(ce, "Latn", pool))
            assertEquals(mapOf("fromA" to RemovedWords.Entry(1)), readList(ce, "Latn", pool))
        }
    }

    @Test fun `no other keyboard - the dialog is not shown`() {
        useKeyboards(ctx, real, c)
        updated(mapOf(c to 7), next = 8)
        var dismissed = false
        showDialog(c, null) { dismissed = true }.use {
            compose.waitForIdle()
            compose.onNodeWithText(title(c)).assertDoesNotExist()
        }
        assertTrue(dismissed)
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}

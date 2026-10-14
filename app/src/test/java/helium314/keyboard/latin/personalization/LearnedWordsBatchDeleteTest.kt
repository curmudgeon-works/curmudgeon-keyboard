// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.reviveComposeMainDispatcher
import helium314.keyboard.withId
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.screens.Item
import helium314.keyboard.settings.screens.Kind
import helium314.keyboard.settings.screens.LearnedWordsScreen
import helium314.keyboard.settings.screens.buildWordLists
import helium314.keyboard.settings.screens.deleteWords
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import helium314.keyboard.latin.makedict.DictionaryHeader
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No main dictionary can be read: a learned word's delete opens the main dictionaries of the script's languages (and
 * the system languages: en-US here) to see how the word is listed, and reading a dictionary's header needs the native
 * library tests don't have (UnsatisfiedLinkError, 2026-10-11). Unreadable: the dictionary is skipped, as on a phone.
 */
@Implements(BinaryDictionaryUtils::class)
object ShadowNoMainDictionary {
    @Implementation
    @JvmStatic
    fun getHeader(dictFile: File): DictionaryHeader = throw IOException("no native library in tests: ${dictFile.name}")
}

/**
 * Batch delete on "Learned & blacklisted words" (owner 2026-10-11: "longpress to start multi-select and then tap tap
 * tap tap to select multiples and delete all at once"; every word of the list: "additional warning for are you
 * sure?"), driven through the screen; and the batch delete itself.
 */
@RunWith(RobolectricTestRunner::class)
// (tall: every row on screen; no main dictionaries, see ShadowNoMainDictionary)
@Config(qualifiers = "w400dp-h2400dp", shadows = [ShadowNoMainDictionary::class])
class LearnedWordsBatchDeleteTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val pool = 5
    private lateinit var store: RemovableHistory
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private var leftScreen = 0
    private val latin = Locale.Builder().setScript("Latn").build().displayScript // (as the screen names the script)
    private val blacklist get() = RemovedWords.blacklist(ctx, "Latn", pool)

    @Before fun setUp() {
        reviveComposeMainDispatcher()
        deleteLearnedFiles(ctx.filesDir)
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        useKeyboards(ctx, real, available("en-US").withId(1))
        store = RemovableHistory(ctx, "Latn", pool)
        PersonalizationHelper.setForTest("Latn", pool, store)
        LearnedStores.storeFile(ctx.filesDir, "Latn", pool).mkdirs()
        GestureDecoderVocabulary.clear()
        LearnedStores.init(ctx)
        LearnedStores.refresh(real)
        writeList(ctx.filesDir, "Latn", pool, "teh\nhte\nadn\t2\n")
        blacklist.reload()
        leftScreen = 0
    }

    @After fun tearDown() {
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
        PersonalizationHelper.setForTest("Latn", pool, null)
        deleteLearnedFiles(ctx.filesDir)
        blacklist.reload()
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        GestureDecoderVocabulary.clear()
    }

    private fun learn(vararg words: String) = words.forEachIndexed { i, w -> store.counts[w] = i + 1 }

    private fun show(blacklisted: Boolean, firstWord: String) {
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity {
            it.setContent { LearnedWordsScreen({ leftScreen++ }, "Latn", blacklisted, pool) } }
        // (the lists are read off the main thread)
        waitFor("the list never came") { compose.onAllNodesWithText(firstWord).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (done()) return
            assertTrue(System.currentTimeMillis() < end, what)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun selected(n: Int) = ctx.resources.getQuantityString(R.plurals.learned_words_selected, n, n)
    private fun hold(word: String) = compose.onNodeWithText(word).performTouchInput { longClick() }
    private fun tap(word: String) = compose.onNodeWithText(word).performClick()
    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
    private fun trash() = compose.onNodeWithContentDescription(ctx.getString(R.string.delete)).performClick()

    @Test fun `long-press starts selection, taps add to it, Delete asks once and deletes only those`() {
        learn("alpha", "beta", "gamma", "delta", "omega")
        show(blacklisted = false, firstWord = "alpha")
        hold("alpha")
        compose.onNodeWithText(selected(1)).assertExists()
        tap("beta"); tap("gamma")
        compose.onNodeWithText(selected(3)).assertExists()
        trash()
        inDialog(ctx.resources.getQuantityString(R.plurals.learned_words_delete_selected, 3, 3)).assertExists()
        inDialog(ctx.getString(R.string.learned_words_delete_message)).assertExists()
        inDialog(ctx.getString(R.string.delete)).performClick()
        // one question only: not every word of the list
        compose.onNodeWithText(ctx.getString(R.string.learned_words_delete_all_button)).assertDoesNotExist()
        waitFor("not deleted") { store.counts.keys == setOf("delta", "omega") }
        waitFor("still selecting") { compose.onAllNodesWithText(selected(3)).fetchSemanticsNodes().isEmpty() }
        waitFor("still listed") { compose.onAllNodesWithText("alpha").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("delta").assertExists()
        compose.onNodeWithText(ctx.getString(R.string.learned_words_section_yours)).assertExists()
        // a plain delete: none of them blacklisted
        assertEquals(setOf("teh", "hte", "adn"), blacklist.entries().keys)
    }

    @Test fun `Back and the cross leave selection mode, not the screen - a tap outside it asks about that word`() {
        learn("alpha", "beta")
        show(blacklisted = false, firstWord = "alpha")
        hold("alpha")
        compose.onNodeWithText(selected(1)).assertExists()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(selected(1)).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.learned_words_section_yours)).assertExists()
        assertEquals(0, leftScreen)
        hold("beta")
        compose.onNodeWithText(selected(1)).assertExists()
        compose.onNodeWithContentDescription(ctx.getString(R.string.learned_words_selection_close)).performClick()
        compose.onNodeWithText(selected(1)).assertDoesNotExist()
        assertEquals(0, leftScreen)
        // outside selection mode: the single word's question, as before
        tap("alpha")
        inDialog("alpha").assertExists()
        inDialog(ctx.getString(R.string.learned_words_delete_message)).assertExists()
        inDialog(ctx.getString(android.R.string.cancel)).performClick()
        assertEquals(setOf("alpha", "beta"), store.counts.keys)
        // and Back now leaves the screen
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, leftScreen)
    }

    @Test fun `Select all, then Delete asks twice - Cancel on the second deletes nothing`() {
        show(blacklisted = true, firstWord = "teh")
        hold("teh")
        compose.onNodeWithContentDescription(ctx.getString(R.string.learned_words_select_all)).performClick()
        compose.onNodeWithText(selected(3)).assertExists()
        trash()
        inDialog(ctx.getString(R.string.learned_words_unblacklist_message)).assertExists()
        inDialog(ctx.getString(R.string.delete)).performClick()
        val all = ctx.resources.getQuantityString(R.plurals.learned_words_delete_all_title, 3, 3,
            ctx.getString(R.string.learned_words_section_blacklisted), latin)
        inDialog(all).assertExists()
        inDialog(ctx.getString(R.string.learned_words_delete_all_message)).assertExists()
        inDialog(ctx.getString(android.R.string.cancel)).performClick()
        compose.onNodeWithText(all).assertDoesNotExist()
        compose.onNodeWithText(selected(3)).assertExists() // (still selecting)
        Thread.sleep(200); compose.waitForIdle()
        assertEquals(setOf("teh", "hte", "adn"), blacklist.entries().keys)
        // through both: all gone
        trash()
        inDialog(ctx.getString(R.string.delete)).performClick()
        inDialog(ctx.getString(R.string.learned_words_delete_all_button)).performClick()
        waitFor("not un-blacklisted") { blacklist.entries().isEmpty() }
    }

    @Test fun `every word ticked by hand asks twice as well`() {
        learn("alpha", "beta")
        show(blacklisted = false, firstWord = "alpha")
        hold("alpha"); tap("beta")
        compose.onNodeWithText(selected(2)).assertExists()
        trash()
        inDialog(ctx.getString(R.string.delete)).performClick()
        inDialog(ctx.resources.getQuantityString(R.plurals.learned_words_delete_all_title, 2, 2,
            ctx.getString(R.string.learned_words_section_yours), latin)).assertExists()
        inDialog(ctx.getString(R.string.learned_words_delete_all_button)).performClick()
        waitFor("not deleted") { store.counts.isEmpty() }
    }

    @Test fun `the batch delete takes each word as the single delete does`() {
        learn("alpha", "beta", "gamma")
        val lists = buildWordLists("Latn", store.counts.map { it.key to it.value }, emptyList(), blacklist.entries())
        val yours = lists.yours.filter { it.word != "gamma" }.map { Item(it, Kind.YOURS) }
        deleteWords(ctx, "Latn", pool, listOf(Locale.US), yours)
        assertEquals(setOf("gamma"), store.counts.keys)
        assertEquals(setOf("teh", "hte", "adn"), blacklist.entries().keys) // (not blacklisted)
        val black = lists.blacklisted.filter { it.word != "adn" }.map { Item(it, Kind.BLACKLISTED) }
        deleteWords(ctx, "Latn", pool, listOf(Locale.US), black)
        assertEquals(setOf("adn"), blacklist.entries().keys)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.gesture.Vocabulary
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.gesture.GestureCorpusRecorder
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary.LocaleSpec
import helium314.keyboard.latin.gesture.SwipeMetrics
import helium314.keyboard.latin.personalization.LearnedStoreMigration
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.settings.preferences.restoreFollowUp
import helium314.keyboard.settings.screens.ownSetSourceFor
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The second eight medium items of the 2026-10-06 review, fixed 2026-10-07: restore runs the settings moves (1), no
 *  wait for the migration under the lock or on the main thread (2), a copy with one set of settings gets no own set (3),
 *  the swipe log follows what was shown (4), theme reload only when the looks differ (5), learned words of a language
 *  without a dictionary swipe (7), the edited keyboard survives process death (8). (6 is view code: phone check.) */
@RunWith(RobolectricTestRunner::class)
class ReReviewMedium2Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        LearnedStoreMigration.holdForTest(null)
        KeyboardProfiles.editingId = KeyboardProfiles.SHARED
    }

    // 1
    @Test fun `a restore runs the settings moves right away`() {
        val turn = Settings.PREF_GESTURE_TURN_WEIGHT
        real.edit().putFloat("p3/$turn", 1.2f).putBoolean("moved_markers_removed", false).commit()
        restoreFollowUp(ctx)
        assertFalse(real.contains("p3/$turn"), "a keyboard's own copy of a moved setting is gone")
        assertTrue(real.getBoolean("learning_swiping_global", false))
        assertTrue(real.getBoolean("suggestions_refine_moved", false))
        assertTrue(real.getBoolean("moved_markers_removed", false))
    }

    // 2
    @Test fun `a strike during the migration neither waits nor blocks other work`() {
        val latch = CountDownLatch(1)
        LearnedStoreMigration.holdForTest(latch)
        val file = File(ctx.filesDir, "strike_test_${System.nanoTime()}.txt").apply { writeText("teh\t2\n") }
        val list = RemovedWords.forFile(file)
        val t0 = System.currentTimeMillis()
        assertEquals(1, list.strike("teh")) // returns at once (the file's strikes aren't known yet)
        assertTrue(System.currentTimeMillis() - t0 < 2000, "strike waited for the migration")
        // a read is waiting for the migration in the background: taking the lock meanwhile mustn't block
        list.reloadAsync()
        Thread.sleep(100)
        val confirmed = arrayOf<Boolean?>(null)
        val t = Thread { confirmed[0] = list.confirm("teh") }
        t.start(); t.join(2000)
        assertFalse(t.isAlive, "confirm blocked behind the waiting read")
        assertEquals(false, confirmed[0])
        latch.countDown()
        Thread.sleep(300)
        assertEquals(3, list.entries()["teh"]?.strikes) // the file's 2 plus the strike made meanwhile (reviewer: not written over)
    }

    // 3
    @Test fun `a copy with one set of settings for all keyboards gets no own set`() {
        val source = SettingsSubtype(Locale.US, "")
        assertNull(ownSetSourceFor(real, source, withOwnSettings = true))
        assertNull(ownSetSourceFor(real, source, withOwnSettings = false))
        real.edit().putBoolean("separate_settings_per_keyboard", true).commit()
        assertEquals(KeyboardProfiles.SHARED, ownSetSourceFor(real, source, withOwnSettings = false))
        assertEquals(KeyboardProfiles.idFor(real, source), ownSetSourceFor(real, source, withOwnSettings = true))
    }

    // 4
    @Test fun `the swipe log follows the word shown first, not the decoder's raw first word`() {
        val results = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "swipe_results.tsv").apply { delete() }
        SwipeMetrics.init(ctx); GestureCorpusRecorder.init(ctx)
        SwipeMetrics.metricsOn = { true }; GestureCorpusRecorder.corpusOn = { false }; SwipeMetrics.privateNow = { false }
        val pointers = InputPointers(4).apply { addPointer(10, 10, 0, 0); addPointer(50, 20, 0, 30); addPointer(90, 30, 0, 60) }
        // the decoder's first word is a removed one; Suggest drops it and shows "hallo" first
        val cands = listOf("hello", "hallo").map { SuggestedWordInfo(it, "", 100, SuggestedWordInfo.KIND_CORRECTION, null, 0, 0) }
        GestureCorpusRecorder.onSwipe(ComposedData(pointers, true, "hello"), Mockito.mock(Keyboard::class.java), cands, "en")
        GestureCorpusRecorder.onShown("hallo")
        GestureCorpusRecorder.onWordCommitted("hallo")
        Thread.sleep(300)
        val line = results.readText()
        assertTrue(line.contains("\t${SwipeMetrics.OUTCOME_KEPT}\t"), "logged: $line")
        assertFalse(line.contains(SwipeMetrics.OUTCOME_EDITED), "logged: $line")
        results.delete()
    }

    // 5
    @Test fun `a keyboard switch reloads the theme only when the looks differ`() {
        real.edit().putBoolean("separate_settings_per_keyboard", true).putString(Settings.PREF_THEME_COLORS, "black").commit()
        KeyboardProfiles.loadGroups(real)
        assertFalse(KeyboardProfiles.looksDiffer(real, 1, 2)) // both read the shared look
        real.edit().putString("p2/${Settings.PREF_THEME_COLORS}", "blue").commit()
        assertTrue(KeyboardProfiles.looksDiffer(real, 1, 2))
        real.edit().putBoolean(KeyboardProfiles.Group.APPEARANCE.prefKey, true).commit()
        KeyboardProfiles.loadGroups(real)
        assertFalse(KeyboardProfiles.looksDiffer(real, 1, 2)) // Appearance shared: one look for all
    }

    // A
    @Test fun `the toolbar visibility row shows what applies without changing the stored value`() {
        assertEquals(Settings.TOOLBAR_FROM_KEY, helium314.keyboard.settings.screens.toolbarVisibilityShown(Settings.TOOLBAR_ABOVE, suggestions = false))
        assertEquals(Settings.TOOLBAR_ABOVE, helium314.keyboard.settings.screens.toolbarVisibilityShown(Settings.TOOLBAR_FROM_KEY, suggestions = true))
        assertEquals(Settings.TOOLBAR_HIDDEN, helium314.keyboard.settings.screens.toolbarVisibilityShown(Settings.TOOLBAR_HIDDEN, suggestions = false))
        assertEquals(Settings.TOOLBAR_ALWAYS, helium314.keyboard.settings.screens.toolbarVisibilityShown(Settings.TOOLBAR_ALWAYS, suggestions = true))
    }

    // 7
    @Test fun `a language without a dictionary still brings its learned words to the swipe list`() {
        val vocab = Vocabulary(emptyList())
        val specs = listOf(LocaleSpec(Locale.US, 1f), LocaleSpec(Locale.forLanguageTag("hi"), 0.85f))
        GestureDecoderVocabulary.mergeInto(vocab, specs, null,
            entriesFor = { if (it == "en-US") listOf("the" to 222) else null },
            withoutExcluded = { _, entries -> entries },
            learnedFor = { locale -> if (locale.language == "hi") listOf("नमस्ते" to 120) else listOf("yaar" to 100) })
        assertTrue(vocab.contains("the"))
        assertTrue(vocab.contains("yaar"))
        assertTrue(vocab.contains("नमस्ते"), "the dictionary-less language's learned words")
    }

    // reviewer 2026-10-07: a restored keyboard's own learned words must not be copied over by the pool refresh (3010 #2: in
    // the learned words' folder, ctx.filesDir, which isn't the pictures' one, KeyboardProfiles.filesDir)
    @Test fun `a restored pool of learned words is kept, not seeded over`() {
        val dir = ctx.filesDir
        val de = File(ctx.cacheDir.parentFile, "restore_pool_de_${System.nanoTime()}").apply { mkdirs() }
        val before = KeyboardProfiles.filesDir
        val runnerBefore = helium314.keyboard.latin.personalization.LearnedStores.seedRunner
        val ioBefore = helium314.keyboard.latin.personalization.LearnedStores.seedIo
        val asked = mutableListOf<Pair<Int, (Boolean) -> Unit>>()
        try {
            KeyboardProfiles.filesDir = de
            real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, false).putInt("keyboard_profile_next_id", 61).commit()
            val own = KeyboardProfiles.idFor(real, KeyboardProfiles.selectedKeyboard(real))
            // what a restore of a whole older backup leaves: the shared store and the keyboard's own, no marker, the
            // one-time marking to do again (restoreEverything)
            helium314.keyboard.latin.personalization.FakeLearnedStoreIo.store(
                helium314.keyboard.latin.personalization.LearnedStores.storeFile(dir, "Latn", 0), helium314.keyboard.latin.personalization.word("shared", 9, 100))
            helium314.keyboard.latin.personalization.FakeLearnedStoreIo.store(
                helium314.keyboard.latin.personalization.LearnedStores.storeFile(dir, "Latn", own), helium314.keyboard.latin.personalization.word("mine", 3, 50))
            real.edit().remove(helium314.keyboard.latin.personalization.LearnedPools.POOLS_MARKED).commit()
            helium314.keyboard.latin.personalization.LearnedStores.seedRunner = { _, pool, done -> asked.add(pool to done) }
            helium314.keyboard.latin.personalization.LearnedStores.seedIo = helium314.keyboard.latin.personalization.FakeLearnedStoreIo()
            restoreFollowUp(ctx)
            helium314.keyboard.latin.personalization.LearnedStores.refresh(real)
            // the copy that runs marks the restored pool first, and leaves it as it is
            assertTrue(helium314.keyboard.latin.personalization.LearnedStores.seedNow(real, asked.single().first))
            asked.single().second(true)
            assertEquals(own, helium314.keyboard.latin.personalization.LearnedStores.currentPool)
            assertEquals(mapOf("mine" to (3 to 50)), helium314.keyboard.latin.personalization.FakeLearnedStoreIo.read(
                helium314.keyboard.latin.personalization.LearnedStores.storeFile(dir, "Latn", own)))
            assertTrue(File(dir, "learned_seeded_k$own").isFile)
            assertTrue(helium314.keyboard.settings.preferences.backupFilePatterns.any { "learned_seeded_k$own".matches(it) })
        } finally {
            real.edit().putBoolean(Settings.PREF_SHARE_LEARNED_WORDS, true).commit()
            asked.forEach { it.second(true) }
            helium314.keyboard.latin.personalization.LearnedStores.refresh(real)
            helium314.keyboard.latin.personalization.LearnedStores.seedRunner = runnerBefore
            helium314.keyboard.latin.personalization.LearnedStores.seedIo = ioBefore
            KeyboardProfiles.filesDir = before
            dir.listFiles()?.filter { it.name.startsWith("UserHistoryDictionary") || it.name.startsWith("learned_") }?.forEach { it.deleteRecursively() }
            de.deleteRecursively()
        }
    }

    // 8 (and review session 2026-10-07: a copy in the settings fired the screens' listener and reset it)
    @Test fun `the edited keyboard is kept by the activity's state, not written to the settings`() {
        KeyboardProfiles.editingId = 7
        assertFalse(real.all.keys.any { it.contains("editing") }, "editingId must not touch the settings: ${real.all.keys}")
        val state = android.os.Bundle().apply { putInt("editing_keyboard_id", KeyboardProfiles.editingId) } // (what the activity saves)
        KeyboardProfiles.editingId = KeyboardProfiles.SHARED // (the process died)
        KeyboardProfiles.editingId = state.getInt("editing_keyboard_id", KeyboardProfiles.SHARED) // (and was recreated)
        assertEquals(7, KeyboardProfiles.editingId)
    }
}

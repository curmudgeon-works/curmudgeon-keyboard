// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.gesture.GestureCorpusRecorder
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.storeOneKeyboard
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner 2026-10-18: "we should truncate swipe corpus yes, but make it flexible in case user wants a longer history
 * retained". The corpus is recorded by default and kept to the newest "Swipes kept" swipes with their follow-up lines,
 * trimmed once it holds a tenth more; its far end keeps all; another reader's minimum wins over a lower setting.
 * Fail before: no setting, nothing trimmed (compile: no PREF_GESTURE_CORPUS_KEPT, KEPT_ALL, minimumKept), and the
 * corpus off by default.
 */
@RunWith(RobolectricTestRunner::class)
class SwipeCorpusKeptTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); storeOneKeyboard(ctx, real)
        GestureCorpusRecorder.init(ctx)
        GestureCorpusRecorder.corpusFile()!!.delete()
        GestureCorpusRecorder.minimumKept = { 0 }
    }

    @After fun tearDown() {
        GestureCorpusRecorder.corpusFile()?.delete()
        GestureCorpusRecorder.minimumKept = { 0 }
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
    }

    private fun kept(n: Int) = ctx.prefs().edit().putInt(Settings.PREF_GESTURE_CORPUS_KEPT, n).commit()

    /** Swipes [from]..[to] as the recorder writes them, each even one followed by its correction. */
    private fun record(from: Int, to: Int) {
        for (id in from..to) {
            GestureCorpusRecorder.append(JSONObject().put("type", "swipe").put("id", id).put("committed", "w$id"))
            if (id % 2 == 0) GestureCorpusRecorder.append(JSONObject().put("type", "final").put("ref", id).put("how", "deleted"))
        }
    }

    private fun lines() = GestureCorpusRecorder.corpusFile()!!.readLines().map { JSONObject(it) }
    private fun swipeIds() = lines().filter { it.getString("type") == "swipe" }.map { it.getInt("id") }

    @Test fun `the newest swipes are kept with their corrections, only once a tenth over`() {
        kept(1000)
        record(1, 1100)
        assertEquals(1100, swipeIds().size) // (a tenth over: not yet)
        record(1101, 1101)
        assertEquals((102..1101).toList(), swipeIds())
        // every correction left is its swipe's, right after it; none of a deleted swipe stayed
        val l = lines()
        for ((i, line) in l.withIndex()) {
            if (line.getString("type") != "final") continue
            assertEquals(line.getInt("ref"), l[i - 1].getInt("id"))
        }
        assertEquals((102..1101).count { it % 2 == 0 }, l.count { it.getString("type") == "final" })
        assertTrue(l.first().getString("type") == "swipe")
    }

    @Test fun `all at the far end never trims`() {
        kept(GestureCorpusRecorder.KEPT_ALL)
        record(1, 2500)
        assertEquals(2500, swipeIds().size)
    }

    @Test fun `a reader that needs more swipes than the setting wins`() {
        kept(1000)
        GestureCorpusRecorder.minimumKept = { 1200 }
        record(1, 1321)
        assertEquals((122..1321).toList(), swipeIds())
    }

    @Test fun `a lowered setting trims at once`() {
        kept(GestureCorpusRecorder.KEPT_ALL)
        record(1, 1500)
        kept(1000)
        GestureCorpusRecorder.onKeptChanged()
        Thread.sleep(500) // (on the corpus' own thread)
        assertEquals((501..1500).toList(), swipeIds())
    }

    @Test fun `the corpus is recorded by default, keeping 3000 swipes`() {
        Settings.init(ctx)
        Settings.getInstance().loadSettings(ctx, Locale.US, InputAttributes(android.view.inputmethod.EditorInfo(), false, ctx.packageName))
        assertTrue(Settings.getValues().mRecordGestureCorpus)
        assertEquals(3000, GestureCorpusRecorder.keptLimit())
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The swipe tuning's key (the label its statistics are kept under) carries the apostrophe skip cost as an A term, only
 * when it is not at its default, like the fast-swipe pair; it is read, parsed and written with the others (2026-10-31).
 * Fail before: no such weight (compile: no apostropheSkip, no PREF_GESTURE_APOSTROPHE_SKIP_COST), " A0.10" not a key.
 */
@RunWith(RobolectricTestRunner::class)
class TuningKeyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the default has no A term, so older statistics keep their label`() {
        assertEquals("T1.0 S0.5 K0.4 H64", OwnGestureDecoder.Tuning.DEFAULT.key)
        assertEquals(0.05f, Defaults.PREF_GESTURE_APOSTROPHE_SKIP_COST)
        assertEquals(0.05f, OwnGestureDecoder.Tuning.DEFAULT.apostropheSkip)
    }

    @Test fun `a changed cost is in the key and parsed back from it`() {
        val tuning = OwnGestureDecoder.Tuning(1f, 0.5f, 0.4f, 64, apostropheSkip = 0.1f)
        assertEquals("T1.0 S0.5 K0.4 H64 A0.10", tuning.key)
        assertEquals(0.1f, OwnGestureDecoder.Tuning.parse(tuning.key)!!.apostropheSkip)
        assertEquals(tuning, OwnGestureDecoder.Tuning.parse(tuning.key))
        // after every other optional term, and without them
        val all = OwnGestureDecoder.Tuning.parse("T0.7 S0.5 K0.4 H64 F0.10 C0.02 V15.0 A0.00")!!
        assertEquals(0f, all.apostropheSkip)
        assertEquals(0.1f, all.fastCommon)
        assertEquals(15f, all.fastFrom)
        assertEquals(0.05f, OwnGestureDecoder.Tuning.parse("T0.7 S0.5 K0.4 H64 V15.0")!!.apostropheSkip)
        assertNull(OwnGestureDecoder.Tuning.parse("T0.7 S0.5 K0.4 H64 A0.10 F0.10")) // (fixed order, as the key writes it)
        assertEquals(8, tuning.shown.size)
        assertEquals(0.5f, tuning.shown[7]) // 0.1 of the 0.2 range
    }

    @Test fun `written to and read from the preferences with the others`() {
        // the stored values themselves (routing to a keyboard's own set is ProfilePreferencesTest's business, and a
        // keyboard without a set drops own-key writes)
        val prefs = ctx.realPrefs()
        prefs.edit().clear().commit()
        try {
            assertEquals(0.05f, OwnGestureDecoder.Tuning.read(prefs).apostropheSkip)
            OwnGestureDecoder.Tuning.write(prefs, OwnGestureDecoder.Tuning(1f, 0.5f, 0.4f, 64, apostropheSkip = 0.1f))
            assertEquals(0.1f, prefs.getFloat(Settings.PREF_GESTURE_APOSTROPHE_SKIP_COST, -1f))
            assertEquals(0.1f, OwnGestureDecoder.Tuning.read(prefs).apostropheSkip)
            assertEquals("T1.0 S0.5 K0.4 H64 A0.10", OwnGestureDecoder.Tuning.read(prefs).key)
            assertFalse(OwnGestureDecoder.Tuning.read(prefs) == OwnGestureDecoder.Tuning.DEFAULT)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}

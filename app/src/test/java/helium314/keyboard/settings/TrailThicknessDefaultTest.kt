// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.realPrefs
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Owner 2026-10-14: "starting trail thickness should be thinner, 60%". The default only (stored = picked): a thickness
 *  picked stays as picked, nothing is written down. */
@RunWith(RobolectricTestRunner::class)
class TrailThicknessDefaultTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun thickness(): Int {
        Settings.init(ctx)
        Settings.getInstance().loadSettings(ctx, Locale.US, InputAttributes(android.view.inputmethod.EditorInfo(), false, ctx.packageName))
        return Settings.getValues().mGestureTrailThickness
    }

    // fail before: 100
    @Test fun `a fresh install draws the trail at 60 percent, a picked thickness stays`() {
        assertEquals(60, thickness())
        assertFalse(real.contains(Settings.PREF_GESTURE_TRAIL_THICKNESS))
        assertEquals(60, SettingDefaults.of(Settings.PREF_GESTURE_TRAIL_THICKNESS)) // (what saved items and backups read)
        real.edit().putInt(Settings.PREF_GESTURE_TRAIL_THICKNESS, 100).commit()
        assertEquals(100, thickness())
    }
}

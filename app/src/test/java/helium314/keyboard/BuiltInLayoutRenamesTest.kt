// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.builtInLayoutRenames
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.CustomLabels
import helium314.keyboard.settings.LayoutPresets
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 2026-10-24: the built-in Layouts are Dense (was Curmudgeon) and Classic (was HeliBoard / HeliBoard extra). A stored
 * choice of an old name, in the plain set or any keyboard's own (in use or not), a "Custom · from" origin naming one,
 * and a saved Layout carrying one all get the new name; values stay (a HeliBoard keyboard shows Custom from Classic).
 * Fail before: no step, the old names stayed stored and matched no built-in.
 */
@RunWith(RobolectricTestRunner::class)
class BuiltInLayoutRenamesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val sel = LayoutPresets.PREF_SELECTED
    private val from = CustomLabels.Kind.LAYOUT.fromKey

    @Before fun setUp() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }
    @After fun tearDown() { real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    @Test fun `stored old names become Classic and Dense everywhere, values stay, other names stay`() {
        real.edit {
            putString(sel, "HeliBoard") // the plain set
            putString(idKey(1, sel), "Curmudgeon") // keyboard 1's own set
            putString(idKey(2, sel), "HeliBoard extra") // keyboard 2, hidden or not
            putBoolean(idKey(2, Settings.PREF_SHOW_NUMBER_ROW), false) // its values are not touched
            putString(idKey(3, sel), "HeliBoard Extra") // 0.3.008's spelling
            putString(idKey(4, sel), "Mine") // a saved Layout: untouched
            putInt(idKey(5, CustomLabels.Kind.LAYOUT.key), CustomLabels.SET)
            putString(idKey(5, from), "HeliBoard") // "Custom · from HeliBoard"
        }
        LayoutPresets.save(real, listOf(LayoutPresets.Preset("Mine", mapOf(sel to "Curmudgeon", Settings.PREF_SHOW_NUMBER_ROW to false))))
        builtInLayoutRenames(ctx, real)
        assertEquals("Classic", real.getString(sel, null))
        assertEquals("Dense", real.getString(idKey(1, sel), null))
        assertEquals("Classic", real.getString(idKey(2, sel), null))
        assertEquals(false, real.all[idKey(2, Settings.PREF_SHOW_NUMBER_ROW)])
        assertEquals("Classic", real.getString(idKey(3, sel), null))
        assertEquals("Mine", real.getString(idKey(4, sel), null))
        assertEquals("Classic", real.getString(idKey(5, from), null))
        val mine = LayoutPresets.load(real).single()
        assertEquals("Dense", mine.values[sel])
        assertEquals(false, mine.values[Settings.PREF_SHOW_NUMBER_ROW])
        // the new names are the built-ins' (the Layouts row finds them)
        assertEquals(listOf(ctx.getString(R.string.layout_preset_dense), ctx.getString(R.string.layout_preset_classic)),
            LayoutPresets.builtIn(ctx).map { it.name })
        // again: nothing to do, nothing changes
        val before = real.all.toMap()
        builtInLayoutRenames(ctx, real)
        assertEquals(before, real.all)
    }

    @Test fun `the app start renames too, after the HeliBoard pins`() {
        real.edit {
            // (an updated device: upstream's steps and the picked-only one have run)
            putInt(Settings.PREF_VERSION_CODE, helium314.keyboard.latin.BuildConfig.MIGRATION_VERSION)
            putBoolean(helium314.keyboard.latin.PICKED_ONLY_DONE, true)
            putString(sel, "HeliBoard Extra"); putString(idKey(1, sel), "Curmudgeon")
        }
        helium314.keyboard.latin.checkVersionUpgrade(ctx)
        // (the plain set went into a keyboard's own set on the same start: no common set, 2026-10-11)
        assertEquals(listOf("Classic"), real.all.filterKeys { it.endsWith("/" + sel) && !it.startsWith("pk1/") }.values.toList())
        assertEquals("Dense", real.getString(idKey(1, sel), null))
    }

    @Test fun `a keyboard restored on its own gets its Layout under the new name`() {
        val keyboard = SettingsSubtype(Locale.US, "").withId(1)
        val backup = mapOf<String, Any?>(helium314.keyboard.latin.PICKED_ONLY_DONE to true,
            helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true, idKey(1, sel) to "HeliBoard extra", idKey(1, from) to "Curmudgeon")
        val restored = helium314.keyboard.settings.preferences.restoredSettings(ctx, backup, keyboard)
        assertEquals("Classic", restored[sel])
        assertEquals("Dense", restored[from])
        assertNull(restored[Settings.PREF_SHOW_NUMBER_ROW]) // (values are not forced)
    }
}

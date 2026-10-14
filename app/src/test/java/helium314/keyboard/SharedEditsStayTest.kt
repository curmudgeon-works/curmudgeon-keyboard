// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.CustomLabels
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Edits made while settings are shared stay with the keyboard whose settings were made the shared ones; the others get
 * their own back (owner 2026-10-11: "Edits made while a menu is shared should only affect the main keyboard who's menu
 * is indeed being changed. So B."). For one menu and for the main switch alike. (owner 2026-10-11: no separate common
 * set; the first keyboard's own settings are the common ones: English, first in the list, is that keyboard, its own set
 * the one edited meanwhile; nothing is handed back.)
 */
@RunWith(RobolectricTestRunner::class)
class SharedEditsStayTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val appearance = KeyboardProfiles.Group.APPEARANCE
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val label = CustomLabels.Kind.THEME.key
    private val english = SettingsSubtype(Locale.US, "")
    private val hindi = SettingsSubtype(Locale.forLanguageTag("hi-IN"), "")
    private fun view(id: Int) = ProfilePreferences(real) { id }

    @Before fun setUp() {
        real.edit().clear()
            .putBoolean("separate_settings_per_keyboard", true)
            .putString("keyboard_profile_ids", JSONObject().put(english.toPref(), 1).put(hindi.toPref(), 2).toString())
            .putInt("keyboard_profile_next_id", 3)
            .putString(Settings.PREF_ENABLED_SUBTYPES, helium314.keyboard.latin.utils.SubtypeSettings.createPrefSubtypes(listOf(english, hindi)))
            .commit()
        KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.filesDir = DeviceProtectedUtils.getFilesDir(ctx)
        KeyboardProfiles.deleteAllFiles()
        view(1).edit().putFloat(gap, 2f).commit()
        view(2).edit().putFloat(gap, 4f).commit()
    }
    @After fun tearDown() { KeyboardProfiles.deleteAllFiles(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real) }

    private fun share(winner: Int) = KeyboardProfiles.setGroupShared(real, appearance, true)
    private fun unshare() = KeyboardProfiles.setGroupShared(real, appearance, false)

    @Test fun `a menu's edits while shared stay with the keyboard it was shared from`() {
        share(1)
        view(2).edit().putFloat(gap, 3f).commit() // (the shared set, whichever keyboard edits it)
        unshare()
        assertEquals(3f, view(1).getFloat(gap, 0f), "English lost the edit")
        assertEquals(4f, view(2).getFloat(gap, 0f))
    }

    @Test fun `the main switch's edits while off stay with the keyboard the settings came from`() {
        KeyboardProfiles.disable(real)
        view(1).edit().putFloat(gap, 3f).commit()
        KeyboardProfiles.enable(real)
        assertEquals(3f, view(1).getFloat(gap, 0f), "English lost the edit")
        assertEquals(4f, view(2).getFloat(gap, 0f))
    }

    @Test fun `the keyboard shared from deleted while shared - nobody takes the edits`() {
        share(1)
        view(2).edit().putFloat(gap, 3f).commit()
        KeyboardProfiles.onKeyboardDeleted(real, english)
        unshare()
        assertEquals(4f, view(2).getFloat(gap, 0f))
        assertEquals(false, real.contains(KeyboardProfiles.ownKey(1, gap)))
    }

    // (owner 2026-10-11: labels are just "Custom", no number; only kept palettes are numbered)
    @Test fun `a Custom label given while shared goes to the keyboard shared from`() {
        CustomLabels.mark(view(2), CustomLabels.Kind.THEME) // Hindi's own
        share(1)
        assertFalse(CustomLabels.has(view(1), CustomLabels.Kind.THEME)) // (English's choice, shared: no label)
        CustomLabels.mark(view(1), CustomLabels.Kind.THEME)
        unshare()
        assertEquals(CustomLabels.SET, view(1).getInt(label, 0))
        assertEquals(CustomLabels.SET, real.getInt(KeyboardProfiles.ownKey(1, label), 0), "English lost the label") // (its own, not a fallback)
        assertEquals(CustomLabels.SET, view(2).getInt(label, 0))
    }

    @Test fun `a picture changed while shared goes to the keyboard shared from`() {
        val dir = KeyboardProfiles.filesDir!!
        fun pic(id: Int) = File(dir, "custom_background_image" + if (id == KeyboardProfiles.SHARED) "" else "_p$id")
        pic(1).writeText("one"); pic(2).writeText("two")
        share(1)
        assertEquals(pic(1), KeyboardProfiles.profileFile(ctx, "custom_background_image")) // (the shared picture: English's)
        pic(1).writeText("new")
        unshare()

        assertEquals("new", pic(1).readText(), "English lost the picture")
        assertEquals("two", pic(2).readText())
    }

    @Test fun `a value reset to its default while shared is the default for the keyboard shared from`() {
        share(1)
        view(1).edit().remove(gap).commit() // ("Default" on the shared set)
        unshare()
        real.edit().putFloat(gap, 7f).commit() // (a later shared value is not its own)
        assertEquals(0f, view(1).getFloat(gap, 0f))
        assertEquals(4f, view(2).getFloat(gap, 0f))
    }
}

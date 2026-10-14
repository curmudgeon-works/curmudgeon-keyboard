// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.AppearanceDraft
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review L5.2: the Appearance snapshot holds the background pictures of the set it edits (its set id, as its values),
 * not the files the context points at: outside a settings screen (the crash undo at app start, a context without an
 * activity) those were the keyboard on screen's, which can be another keyboard than the edited set's.
 */
@RunWith(RobolectricTestRunner::class)
class AppearanceDraftSetFilesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private fun pictures(n: Int) = KeyboardProfiles.pictureFiles(ctx, kid(n))
    private fun clearPictures() = (pictures(1) + pictures(2)).forEach { it.delete() }

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        real.edit { putBoolean("separate_settings_per_keyboard", true) }
        storeKeyboards(ctx, real, listOf(SettingsSubtype(Locale.US, "").withId(1), SettingsSubtype(Locale.GERMANY, "").withId(2)))
        clearPictures()
    }
    @After fun tearDown() {
        AppearanceDraft.close(); clearPictures()
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        KeyboardProfiles.refreshImeId(real)
    }

    // fail before: the snapshot held keyboard 2's pictures (the one on screen), so keyboard 1's picture went untracked
    @Test fun `the snapshot holds the edited set's pictures, not the keyboard on screen's`() {
        KeyboardProfiles.imeId = kid(2) // (on screen: keyboard 2; the edited set, the selected keyboard's: keyboard 1)
        val draft = AppearanceDraft.of(ctx) // (a context without an activity)
        val picture = pictures(1).first()
        picture.writeText("a picture")
        assertEquals(setOf(picture.name), draft.changedFiles())
    }
}

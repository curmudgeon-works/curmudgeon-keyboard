// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.gesture.Vocabulary
import helium314.keyboard.latin.common.PictureFraming
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary.LocaleSpec
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.preferences.backupFilePatterns
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The review of 2026-10-06 before the production upload: items 1–3 (4 needs the running keyboard). */
@RunWith(RobolectricTestRunner::class)
class PreUploadFixesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val en = Locale.US
    private val cs = Locale.forLanguageTag("cs")

    @Test fun `a language without a dictionary doesn't hold up the keyboard's swipe list`() {
        val enList = Vocabulary(listOf("the" to 222))
        val specs = listOf(LocaleSpec(en, 1f), LocaleSpec(cs, 0.85f))
        val asked = mutableListOf<Locale>()
        fun listFor(l: Locale): Vocabulary? { asked.add(l); return if (l == en) enList else null }
        // Czech still building: wait
        assertFalse(GestureDecoderVocabulary.mergeable(specs, ::listFor) { false })
        // Czech has no dictionary: nothing to wait for
        assertTrue(GestureDecoderVocabulary.mergeable(specs, ::listFor) { it == "cs" })
        // every language's build is started, not just up to the first missing one
        assertEquals(listOf(en, cs, en, cs), asked)
    }

    @Test fun `a big photo is shrunk to at most 4096 pixels`() {
        assertEquals(2, PictureFraming.sampleSize(8160, 4096)) // a 50 MP photo: 4080
        assertEquals(2, PictureFraming.sampleSize(8191, 4096)) // loaded at full size before
        assertEquals(2, PictureFraming.sampleSize(4097, 4096))
        assertEquals(1, PictureFraming.sampleSize(4096, 4096))
        assertEquals(1, PictureFraming.sampleSize(2000, 4096))
        assertEquals(4, PictureFraming.sampleSize(12000, 4096))
    }

    @Test fun `backups carry the saved themes' pictures`() {
        val sep = File.separator
        assertTrue(backupFilePatterns.any { "looks${sep}0f2c4e5a-uuid${sep}custom_background_image".matches(it) })
        assertTrue(backupFilePatterns.any { "looks${sep}0f2c4e5a-uuid${sep}custom_background_image_night.framing".matches(it) })
        assertFalse(backupFilePatterns.any { "looks${sep}stray".matches(it) })
    }

    @Test fun `a theme whose pictures are missing leaves the background alone`() {
        val live = Settings.getCustomBackgroundFile(ctx, false, false).apply { parentFile?.mkdirs(); writeText("picture") }
        val look = AppearanceLooks.Look("Restored", mapOf(AppearanceLooks.PICTURES to "no-such-folder"))
        AppearanceLooks.applyPictures(ctx, look)
        assertTrue(live.exists())
        assertEquals("picture", live.readText())
    }
}

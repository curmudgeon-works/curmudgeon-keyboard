// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The blacklists are one object per file (keyboard and settings share them) and really written. */
@RunWith(RobolectricTestRunner::class)
class RemovedWordsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val locale = Locale.forLanguageTag("xx-YY")
    private val file get() = File(ctx.filesDir, "blacklists/xx-YY.txt")

    @Test fun `an edit is seen by every holder and lands in the file`() {
        file.delete()
        val list = RemovedWords.blacklist(ctx, locale)
        list.reload()
        assertSame(list, RemovedWords.blacklist(ctx, locale))
        assertTrue(list.add("pyramidar"))
        assertFalse(list.add("pyramidar"))
        assertTrue(RemovedWords.blacklist(ctx, locale).contains("pyramidar"))
        list.reload() // waits for the write; the word stays
        assertEquals(listOf("pyramidar"), file.readLines())
        assertTrue(list.add("other"))
        assertTrue(list.remove("pyramidar"))
        list.reload()
        assertFalse(list.contains("pyramidar"))
        assertEquals(listOf("other"), file.readLines())
        // a file replaced from outside (a restore) is what counts after a re-read
        file.writeText("restored\n")
        list.reload()
        assertEquals(listOf("restored"), list.words())
    }

}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.keepUnbundledMainDict
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.OnDemandDictionaries
import helium314.keyboard.settings.screens.dictionaryAvailable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dictionaries to download from curmudgeon.works (2026-10-10). */
@RunWith(RobolectricTestRunner::class)
class OnDemandDictionariesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun download(tag: String) = OnDemandDictionaries.forLocale(ctx, tag.constructLocale())?.tag

    @Test fun `a language finds its download - by tag, by language, never another script or region`() {
        assertEquals("th", download("th"))
        assertEquals("et", download("et-EE")) // (the keyboard's Estonian)
        assertEquals("id", download("in")) // (the keyboard's Indonesian)
        assertEquals("en-CA", download("en-CA"))
        assertNull(download("en-IN")) // en-CA is no English (India)
        assertNull(download("hi")) // built in again (2026-10-11), so no download
        assertEquals("https://curmudgeon.works/dictionaries#et-EE", OnDemandDictionaries.pageFor("et-EE".constructLocale()))
    }

    @Test fun `Urdu and Korean keyboards find their downloads`() { // (2026-10-11: release v2)
        assertEquals("ur", download("ur-PK")) // (the keyboard's Urdu)
        assertEquals("ko", download("ko"))
        assertEquals("https://curmudgeon.works/dictionaries#ur-PK", OnDemandDictionaries.pageFor("ur-PK".constructLocale()))
    }

    @Test fun `without advanced a language shows if it has a dictionary here or to download`() {
        assertTrue(dictionaryAvailable(ctx, "en-US".constructLocale())) // built in
        assertTrue(dictionaryAvailable(ctx, "th".constructLocale())) // to download
        assertFalse(dictionaryAvailable(ctx, "zu".constructLocale())) // nothing anywhere
    }

    @Test fun `a dictionary extracted before its language was unbundled stays, as the user's own copy`() {
        val th = File(DictionaryInfoUtils.getCacheDirectoryForLocale("th".constructLocale(), ctx)!!)
        val extracted = File(th, "main.dict").apply { writeText("x") }
        assertTrue(keepUnbundledMainDict(ctx, th, extracted))
        assertTrue(File(th, "main_user.dict").exists())
        for (tag in listOf("en-US", "hi")) { // (Hindi built in again since 2026-10-11)
            val dir = File(DictionaryInfoUtils.getCacheDirectoryForLocale(tag.constructLocale(), ctx)!!)
            val bundledCopy = File(dir, "main.dict").apply { writeText("x") }
            assertFalse(keepUnbundledMainDict(ctx, dir, bundledCopy), tag) // still built in: cleared as before
        }
    }

    @Test fun `the dictionary picker opens in Downloads and shows our downloads, not only generic files`() {
        val intent = OnDemandDictionaries.pickerIntent()
        val types = intent.getStringArrayExtra(android.content.Intent.EXTRA_MIME_TYPES)!!.toList()
        assertTrue(OnDemandDictionaries.MIME_TYPE in types && "application/octet-stream" in types) // (octet-stream alone hid ours)
        assertEquals("content://com.android.externalstorage.documents/document/primary%3ADownload",
            intent.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI).toString())
    }
}

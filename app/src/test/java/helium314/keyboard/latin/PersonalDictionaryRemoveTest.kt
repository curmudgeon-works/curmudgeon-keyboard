// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.UserDictionary
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Remove takes the word out of Android's personal dictionary for real, in any capitalization, for the keyboard's languages. */
@RunWith(RobolectricTestRunner::class)
class PersonalDictionaryRemoveTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `rows in any capitalization for the keyboard's languages and for all languages go, the others stay`() {
        val provider = Robolectric.setupContentProvider(FakeUserDictionary::class.java, UserDictionary.AUTHORITY)
        provider.rows += listOf(
            Row(1, "Pyramidar", "en_US"), Row(2, "pyramidar", null), Row(3, "PYRAMIDAR", "en"),
            Row(4, "pyramidar", "de"), Row(5, "pyramid", "en_US"), Row(6, "pyramidar", "hi_IN")
        )
        val deleted = DictionaryFacilitatorImpl.deleteFromPersonalDictionary(ctx, "pyramidar",
            listOf(Locale.US, Locale.forLanguageTag("hi-Latn")))
        assertEquals(4, deleted)
        assertEquals(listOf(4L, 5L), provider.rows.map { it.id })
    }

    data class Row(val id: Long, val word: String, val locale: String?)

    class FakeUserDictionary : ContentProvider() {
        val rows = mutableListOf<Row>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection).apply {
                for (row in rows) addRow(projection!!.map { when (it) {
                    UserDictionary.Words._ID -> row.id
                    UserDictionary.Words.WORD -> row.word
                    UserDictionary.Words.LOCALE -> row.locale
                    else -> null
                } })
            }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = selectionArgs!![0].toLong()
            return if (rows.removeAll { it.id == id }) 1 else 0
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.BodyStoreIo
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStoreFiles
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.deleteLearnedFiles
import helium314.keyboard.latin.personalization.idleUntil
import helium314.keyboard.latin.personalization.snapshot
import helium314.keyboard.latin.personalization.storedPools
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.personalization.writeList
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.screens.KeyboardsScreen
import helium314.keyboard.settings.screens.keyboardName
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Deleting a keyboard on the Keyboards screen deletes its learned and blacklisted words (2026-10-10), only its own. */
@RunWith(RobolectricTestRunner::class)
class DeleteKeyboardWordsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val ce: File get() = ctx.filesDir
    private lateinit var de: File
    private val body = BodyStoreIo()
    /** The stores of [body]; the store of Latn in [livePool] is held open by the keyboard (its replacements recorded). */
    private var livePool = -1
    private var liveReplaced = 0
    private val io = object : LearnedStoreIo by body {
        override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? {
            if (script != "Latn" || pool != livePool) return null
            return object : LearnedStoreIo.LiveStore {
                override fun readAll(): List<LearnedEntry>? = body.readFile(LearnedStores.storeFile(ce, script, pool), Locale.ROOT)
                override fun replaceWith(dir: File): Boolean {
                    liveReplaced++
                    val target = LearnedStores.storeFile(ce, script, pool)
                    target.deleteRecursively()
                    return dir.renameTo(target)
                }
            }
        }
    }
    private val filesDirBefore = KeyboardProfiles.filesDir
    private val a by lazy { available("en-US") }
    private val b by lazy { available("de-DE") }
    private val c by lazy { available("fr") }

    @Before fun setUp() {
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        de = File(ctx.cacheDir, "de_${System.nanoTime()}").apply { mkdirs() }
        KeyboardProfiles.filesDir = de
        assertNotEquals(ce.canonicalPath, de.canonicalPath)
        LearnedKeyboards.storeIo = io
        SubtypeSettings.init(ctx)
    }

    @After fun tearDown() {
        LearnedKeyboards.storeIo = LearnedStoreIo.Native
        deleteLearnedFiles(ce)
        real.edit().clear().commit()
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        LearnedStores.refresh(real)
        KeyboardProfiles.filesDir = filesDirBefore
    }

    /** Keyboards A, B, C with pools 5, 6, 7, words and a blacklist in each, and the shared ones of before (pool 0). */
    private fun phone() {
        useKeyboards(ctx, real, a, b, c)
        real.edit().putString(LearnedKeyboards.PREF_POOLS, JSONObject(mapOf(a.toPref() to 5, b.toPref() to 6, c.toPref() to 7)).toString())
            .putInt(LearnedKeyboards.PREF_POOL_NEXT, 8).putBoolean(LearnedKeyboards.PREF_DONE, true).commit()
        for (pool in listOf(0, 5, 6, 7)) {
            body.store(ce, "Latn", pool, word("w$pool", 2, 100))
            writeList(ce, "Latn", pool, "bad$pool\n")
        }
        body.store(ce, "Cyrl", 6, word("cyr", 1, 100))
    }

    /** Press and hold [keyboard] on the Keyboards screen, Delete, and confirm, as the user does. */
    private fun deleteOnScreen(keyboard: SettingsSubtype) {
        ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { KeyboardsScreen { } } }.use {
            compose.onNodeWithText(keyboardName(keyboard, ctx)).performTouchInput { longClick() }
            compose.onNodeWithText(ctx.getString(R.string.delete)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.delete_keyboard_message, keyboardName(keyboard, ctx))).assertExists()
            compose.onNode(hasText(ctx.getString(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
            idleUntil { !File(ce, "${LearnedStores.BLACKLIST_DIR}/k6").exists() } // (the last thing deleted)
        }
    }

    @Test fun `delete - that keyboard's learned words and blacklists go, the other keyboards' and pool 0 stay`() {
        phone()
        val others = snapshot(ce).filterKeys { "k6" !in it && !it.startsWith(LearnedStoreFiles.WORK_DIR) }
        deleteOnScreen(b)
        assertFalse(LearnedStores.storeFile(ce, "Latn", 6).exists(), "files: ${snapshot(ce).keys}")
        assertFalse(LearnedStores.storeFile(ce, "Cyrl", 6).exists())
        assertFalse(File(ce, "${LearnedStores.BLACKLIST_DIR}/k6").exists())
        assertEquals(others, snapshot(ce).filterKeys { !it.startsWith(LearnedStoreFiles.WORK_DIR) }, "another keyboard's files changed")
        assertFalse(b.toPref() in storedPools(real))
        assertEquals(setOf(5, 7), LearnedStores.keyboardPoolsOnDisk(ce))
        assertEquals(setOf("w0"), body.words(ce, "Latn", 0))
    }

    @Test fun `delete - a store the keyboard has open is emptied, not deleted`() {
        phone()
        livePool = 6
        deleteOnScreen(b)
        assertEquals(1, liveReplaced)
        assertTrue(LearnedStores.storeFile(ce, "Latn", 6).exists())
        assertEquals(emptySet(), body.words(ce, "Latn", 6))
        assertFalse(LearnedStores.storeFile(ce, "Cyrl", 6).exists())
        assertEquals(setOf("w5"), body.words(ce, "Latn", 5))
    }

}

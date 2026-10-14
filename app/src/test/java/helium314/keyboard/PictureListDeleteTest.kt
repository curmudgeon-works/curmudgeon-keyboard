// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.PictureLibrary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.preferences.namesInSentence
import org.json.JSONObject
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** "DM9 PICTURE LIST REMOVE" (2026-10-12): a picture deleted from "Choose a picture" names the saved themes using it;
 *  "Delete, but keep in themes" gives each its own copy, "Delete from themes too" empties their slots; the keyboards'
 *  own pictures are never touched. */
@RunWith(RobolectricTestRunner::class)
class PictureListDeleteTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val looksDir = File(ctx.filesDir, "looks")
    private fun live() = Settings.getCustomBackgroundFile(ctx, false, false)
    private val slot get() = AppearanceLooks.slotOf(live().name)

    @Before fun clean() {
        looksDir.deleteRecursively()
        PictureLibrary.dir(ctx).deleteRecursively()
        AppearanceLooks.save(ctx.prefs(), emptyList(), now = true)
    }

    private fun libraryPicture(text: String): File {
        val src = File(ctx.cacheDir, "src_$text").apply { writeText(text) }
        return PictureLibrary.file(ctx, PictureLibrary.nameOf(ctx, src)!!)
    }

    private fun look(name: String, vararg slots: Pair<String, String>) = AppearanceLooks.Look(name, mapOf(
        AppearanceLooks.PICTURES to "lib:" + JSONObject().apply { slots.forEach { (s, p) -> put(s, JSONObject().put("p", p).put("f", "fr")) } }))

    private fun refs(name: String): JSONObject? = (AppearanceLooks.load(ctx.prefs()).first { it.name == name }
        .values[AppearanceLooks.PICTURES] as String).takeIf { it.startsWith("lib:") }?.let { JSONObject(it.removePrefix("lib:")) }

    private fun setUp(): File {
        val sunset = libraryPicture("sunset")
        val other = libraryPicture("other")
        AppearanceLooks.save(ctx.prefs(), listOf(
            look("Sunset", slot to sunset.name),
            look("Dusk", slot to sunset.name, (slot + "_night") to other.name),
            look("Other", slot to other.name),
        ), now = true)
        live().apply { parentFile?.mkdirs(); writeText("keyboard's own") }
        return sunset
    }

    @Test fun `the themes using a picture are named`() {
        val sunset = setUp()
        assertEquals(listOf("Sunset", "Dusk"), AppearanceLooks.namesUsingPicture(ctx.prefs(), sunset.name))
        assertEquals("Sunset and Dusk", namesInSentence(ctx, listOf("Sunset", "Dusk")))
        assertEquals("Sunset", namesInSentence(ctx, listOf("Sunset")))
        assertEquals("Sunset, Dusk and Work", namesInSentence(ctx, listOf("Sunset", "Dusk", "Work")))
        assertEquals("Sunset, Dusk, Work and 2 more", namesInSentence(ctx, listOf("Sunset", "Dusk", "Work", "A", "B")))
    }

    @Test fun `delete but keep in themes gives each theme its own copy and the list entry goes`() {
        val sunset = setUp()
        assertTrue(AppearanceLooks.deleteFromList(ctx, sunset, keepInThemes = true))
        assertFalse(sunset.exists(), "the picture left the list")
        assertTrue(PictureLibrary.list(ctx).none { it.readText() == "sunset" })
        val a = refs("Sunset")!!.getJSONObject(slot)
        val b = refs("Dusk")!!.getJSONObject(slot)
        assertFalse(a.has("p")); assertFalse(b.has("p"))
        assertNotEquals(a.getString("t"), b.getString("t"), "each theme its own copy")
        assertEquals("fr", a.getString("f"), "the framing stays")
        assertEquals("other", PictureLibrary.file(ctx, refs("Dusk")!!.getJSONObject(slot + "_night").getString("p")).readText())
        assertTrue(AppearanceLooks.namesUsingPicture(ctx.prefs(), sunset.name).isEmpty())
        assertEquals("keyboard's own", live().readText(), "keyboards untouched")
        // applying such a theme copies its own picture into the keyboard's file, as with a library picture
        AppearanceLooks.applyPictures(ctx, AppearanceLooks.load(ctx.prefs()).first { it.name == "Sunset" })
        assertEquals("sunset", live().readText())
        assertFalse(AppearanceLooks.isTweaked(ctx, AppearanceLooks.load(ctx.prefs()).first { it.name == "Sunset" }))
        // the copies stay while their themes do, and go at app start once no theme names them
        AppearanceLooks.pruneOwnedPictures(ctx, ctx.prefs())
        assertEquals(2, File(looksDir, "own").listFiles()!!.size)
        AppearanceLooks.save(ctx.prefs(), AppearanceLooks.load(ctx.prefs()).filter { it.name != "Sunset" }, now = true)
        AppearanceLooks.pruneOwnedPictures(ctx, ctx.prefs())
        assertEquals(listOf(b.getString("t")), File(looksDir, "own").listFiles()!!.map { it.name })
    }

    @Test fun `delete from themes too empties the slots and leaves nothing of the picture`() {
        val sunset = setUp()
        assertTrue(AppearanceLooks.deleteFromList(ctx, sunset, keepInThemes = false))
        assertFalse(sunset.exists())
        assertEquals("", AppearanceLooks.load(ctx.prefs()).first { it.name == "Sunset" }.values[AppearanceLooks.PICTURES], "no slot left: no pictures")
        val dusk = refs("Dusk")!!
        assertFalse(dusk.has(slot), "the slot emptied")
        assertTrue(dusk.has(slot + "_night"), "the theme's other picture stays")
        assertTrue(refs("Other")!!.has(slot), "a theme not using it is unchanged")
        assertFalse(File(looksDir, "own").exists(), "no copy anywhere")
        assertTrue(PictureLibrary.list(ctx).none { it.readText() == "sunset" })
        assertEquals("keyboard's own", live().readText(), "keyboards untouched")
    }

    @Test fun `a picture no theme uses just leaves the list`() {
        val unused = libraryPicture("unused")
        live().apply { parentFile?.mkdirs(); writeText("unused") } // (a keyboard has its own copy of it)
        assertTrue(AppearanceLooks.namesUsingPicture(ctx.prefs(), unused.name).isEmpty())
        assertTrue(AppearanceLooks.deleteFromList(ctx, unused, keepInThemes = false))
        assertFalse(unused.exists())
        assertEquals("unused", live().readText())
    }
}

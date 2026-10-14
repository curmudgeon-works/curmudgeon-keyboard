// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.countingIds
import helium314.keyboard.idKey
import helium314.keyboard.kid
import helium314.keyboard.latin.personalization.FakeLearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.personalization.word
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.listedById
import helium314.keyboard.randomIds
import helium314.keyboard.settings.preferences.MergeQuestion
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.RestoreFlow
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.SavedItemsMerge
import helium314.keyboard.settings.preferences.SavedItemsMerge.Answer
import helium314.keyboard.settings.preferences.SavedItemsMerge.Kind
import helium314.keyboard.settings.preferences.restoreChosenFrom
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.withId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The restore redesign (owner 2026-10-12, M4): one plan, made when the backup is read, which the dialog, its questions
 * and the run all read; Keep both keeps the backup's name and id, the device's becomes "… 2" with a new id, and every
 * reference to the device's goes through the maps before anything is applied; an item a ticked keyboard uses comes in;
 * Back keeps the answers. The device's English (US) is "k1"; the backup's "k1" too (the same keyboard) unless said.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreOnePlanTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private val zip by lazy { File(ctx.cacheDir, "one-plan-${System.nanoTime()}.zip") }
    private val gap = Settings.PREF_KEY_HORIZONTAL_GAP
    private val theme = AppearanceLooks.PREF_SELECTED
    private val type = LayoutType.NUMBER_ROW
    private val rowFolder get() = LayoutUtilsCustom.getLayoutFile("x", type, ctx).parentFile!!
    private lateinit var english: String

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        english = keyboardName(available("en-US"), ctx)
        rowFolder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
    }
    @After fun tearDown() {
        randomIds(); zip.delete(); rowFolder.deleteRecursively(); LayoutUtilsCustom.onLayoutFileChanged()
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("UserHistoryDictionary") || it.name == LearnedStores.BLACKLIST_DIR }
            ?.forEach { it.deleteRecursively() }
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.refreshImeId(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
    }

    /** The device: [keyboards], separate settings on, [own] in their sets (by id), [extra] app-wide. */
    private fun device(keyboards: List<SettingsSubtype>, own: Map<String, Any?> = emptyMap(), extra: Map<String, Any?> = emptyMap()) {
        real.edit().apply {
            putBoolean("separate_settings_per_keyboard", true)
            for ((key, value) in own + extra) KeyboardProfiles.put(this, key, value)
        }.commit()
        useKeyboards(ctx, real, *keyboards.toTypedArray())
        KeyboardProfiles.loadGroups(real)
    }

    /** The backup: [keyboards] with [values] (stored keys), and [files] (zip entry -> bytes). */
    private fun backup(keyboards: List<SettingsSubtype>, values: Map<String, Any?> = emptyMap(), files: Map<String, ByteArray> = emptyMap()) {
        val list = SubtypeSettings.createPrefSubtypes(keyboards)
        val all = HashMap<String?, Any?>(mapOf(Settings.PREF_ENABLED_SUBTYPES to list, Settings.PREF_ADDITIONAL_SUBTYPES to list,
            Settings.PREF_SELECTED_SUBTYPE to keyboards.first().toPref(), "separate_settings_per_keyboard" to true,
            KeyboardIdUpgrade.DONE to true, KeyboardProfiles.NO_COMMON_SET_DONE to true,
            helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
            helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true))
        all.putAll(values)
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json")); settingsToJsonStream(all, z); z.closeEntry()
            for ((name, bytes) in files) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
        }
    }

    private fun look(name: String, gapValue: Double) = JSONObject().put("name", name).put("values", JSONObject().put(gap, JSONObject().put("f", gapValue)))
    private fun looks(vararg l: JSONObject) = AppearanceLooks.PREF to JSONArray(l.toList()).toString()
    private fun themes() = AppearanceLooks.load(real).associate { it.name to it.values[gap] }
    private fun own(id: String, key: String) = real.all[KeyboardProfiles.ownKey(id, key)]

    // ---- the plan is the run's (M4) ----

    // fail before: the dialog planned the key layouts over every backup keyboard, French's "Row" (other keys than the
    // device's) took "Row 2" there and pushed Spanish's "Row 2" to "Row 2 2"; the run, French unticked, kept "Row 2"
    @Test fun `a key layout clash of an unticked keyboard doesn't change the names the run gives a ticked one`() {
        val row = LayoutUtilsCustom.getLayoutName("Row", type)
        val row2 = LayoutUtilsCustom.getLayoutName("Row 2", type)
        rowFolder.mkdirs(); LayoutUtilsCustom.getLayoutFile(row, type, ctx).writeText("D"); LayoutUtilsCustom.onLayoutFileChanged()
        val german = available("de-DE").withLayout(type, row).withId(1)
        device(listOf(german))
        val french = available("fr").withLayout(type, row).withId(5)
        val spanish = available("es").withLayout(type, row2).withId(6)
        val folder = "unprotected/layouts/${type.name.lowercase()}"
        backup(listOf(french, spanish), files = linkedMapOf("$folder/$row" to "X".toByteArray(), "$folder/$row2" to "Y".toByteArray()))
        val shown = restorePlanFrom(ctx, zip).incoming.getValue(spanish).layoutName(type)
        restoreChosenFrom(ctx, zip, listOf(spanish), settings = true, learnedWords = false, io = LearnedStoreIo.Native)
        val restored = listedById(real, kid(6))!!
        assertEquals(shown, restored.layoutName(type), "the run named Spanish's number row as the dialog showed it")
        assertEquals(row2, restored.layoutName(type))
        assertEquals("Y", LayoutUtilsCustom.getLayoutFile(row2, type, ctx).readText())
        assertEquals("D", LayoutUtilsCustom.getLayoutFile(row, type, ctx).readText(), "the device's Row, untouched")
        assertEquals(row, listedById(real, kid(1))!!.layoutName(type))
    }

    // ---- Keep both ----

    /** The device's English (k1) on its Sunset (gap 1); the backup's English (k1) on the backup's Sunset (gap 3), its own
     *  gap 5 (tweaked after). */
    private fun sunsetOnBoth(): SettingsSubtype {
        val deviceEnglish = available("en-US").withId(1)
        device(listOf(deviceEnglish), own = mapOf(idKey(1, theme) to "Sunset", idKey(1, gap) to 1f), extra = mapOf(looks(look("Sunset", 1.0))))
        val backupEnglish = available("en-US").withId(1)
        backup(listOf(backupEnglish), mapOf(idKey(1, theme) to "Sunset", idKey(1, gap) to 5f, looks(look("Sunset", 3.0))))
        return backupEnglish
    }

    private fun restore(keyboard: SettingsSubtype, decision: RestoreDecision, answer: Answer) {
        countingIds() // (the device's English gets "k1000")
        restoreKeyboardsFrom(ctx, zip, listOf(keyboard), withSettings = true,
            names = SavedItemsMerge.Choices(answers = mapOf((Kind.THEMES to "Sunset") to answer)), decisions = mapOf(keyboard to decision))
    }

    // fail before: Keep both named the backup's "English (US) 2" and Keep both of an item didn't exist (the device's
    // keyboards stayed on the name, the backup's)
    @Test fun `Keep both on English and on Sunset - the backup's English shows the backup's Sunset, English (US) 2 shows Sunset 2`() {
        val backupEnglish = sunsetOnBoth()
        restore(backupEnglish, RestoreDecision.Separate(), Answer.KeepBoth)
        val restored = listedById(real, kid(1))!!
        val renewed = listedById(real, "k1000")!!
        assertEquals(english, keyboardName(restored, ctx))
        assertEquals("$english 2", keyboardName(renewed, ctx))
        assertEquals("Sunset", own(kid(1), theme))
        assertEquals("Sunset 2", own("k1000", theme))
        assertEquals(mapOf("Sunset 2" to 1f, "Sunset" to 3f), themes())
        assertEquals(5f, own(kid(1), gap))
        assertEquals(1f, own("k1000", gap), "the device's keeps its look")
    }

    // fail before: the device's keyboards using a replaced theme kept their own look
    @Test fun `Keep both on English and Replace on Sunset - both show the backup's look`() {
        val backupEnglish = sunsetOnBoth()
        restore(backupEnglish, RestoreDecision.Separate(), Answer.Replace("Sunset"))
        assertEquals(mapOf("Sunset" to 3f), themes())
        assertEquals("Sunset", own(kid(1), theme))
        assertEquals("Sunset", own("k1000", theme))
        assertEquals(3f, own("k1000", gap), "the device's English takes the backup's Sunset")
    }

    // the device's English and the backup's share k1: the plan says the device's k1 uses Sunset. Left on k1, Replace's
    // "they take the backup's look" would land on the backup's English (or nowhere); through the id map it lands on the
    // device's English at its new id. Fail before: nothing took the backup's look
    @Test fun `a reference to the device's keyboard follows it to its new id, never the backup's keyboard with the old one`() {
        val backupEnglish = sunsetOnBoth()
        val plan = restorePlanFrom(ctx, zip)
        assertEquals(listOf(kid(1)), plan.deviceUsers[Kind.THEMES to "Sunset"].orEmpty().map { it.keyboardId })
        restore(backupEnglish, RestoreDecision.Separate(), Answer.Replace("Sunset"))
        assertEquals(3f, own("k1000", gap), "the device's English, at its new id, took the backup's look")
        assertEquals(5f, own(kid(1), gap), "the backup's English keeps its own settings")
        assertEquals(english, keyboardName(listedById(real, kid(1))!!, ctx))
    }

    // fail before: the backup's was "English (US) 2" (the name box's prefill), the device's stayed "English (US)"
    @Test fun `Keep both - the backup's keeps English (US), the device's becomes English (US) 2`() {
        val deviceEnglish = available("en-US").withId(1)
        device(listOf(deviceEnglish))
        val backupEnglish = available("en-US").withLayout(LayoutType.MAIN, "dvorak").withId(2)
        backup(listOf(backupEnglish))
        val plan = restorePlanFrom(ctx, zip)
        val question = MergeQuestion(plan, backupEnglish, listOf(backupEnglish), emptyMap())
        assertEquals(MergeQuestion.Pick(null), question.picked, "no keyboard with its id: Keep both to start with")
        assertEquals(english, question.separateName)
        restoreKeyboardsFrom(ctx, zip, listOf(backupEnglish), withSettings = true, decisions = mapOf(backupEnglish to question.separate))
        assertEquals(english, keyboardName(listedById(real, kid(2))!!, ctx))
        assertEquals("$english 2", keyboardName(listedById(real, kid(1))!!, ctx))
    }

    // owner 2026-10-12: kept both, the backup's keyboard gets its own words into an empty pool, the device's stay as they
    // are (a guard: this was so before too, by the new id's records)
    @Test fun `Keep both - the backup's words land in an empty pool, the device's pool is unchanged`() {
        val io = object : LearnedStoreIo {
            private fun file(dir: File) = File(dir, dir.name + ".body")
            override fun readFile(dir: File, locale: Locale): List<LearnedEntry>? =
                file(dir).takeIf { it.isFile }?.readLines()?.filter { it.isNotEmpty() }?.map { FakeLearnedStoreIo.parse(it) }
            override fun writeFile(dir: File, locale: Locale, entries: List<LearnedEntry>): Boolean {
                dir.mkdirs(); file(dir).writeText(entries.joinToString("") { FakeLearnedStoreIo.format(it) + "\n" }); return true
            }
            override fun live(script: String, pool: Int): LearnedStoreIo.LiveStore? = null
        }
        fun words(pool: Int) = io.readFile(LearnedStores.storeFile(ctx.filesDir, "Latn", pool), Locale.ROOT).orEmpty().map { it.word }.toSet()
        val deviceEnglish = available("en-US").withId(1)
        device(listOf(deviceEnglish), extra = mapOf(LearnedKeyboards.PREF_DONE to true,
            LearnedKeyboards.PREF_POOLS to JSONObject().put(kid(1), 5).toString(), LearnedKeyboards.PREF_POOL_NEXT to 6))
        io.writeFile(LearnedStores.storeFile(ctx.filesDir, "Latn", 5), Locale.ROOT, listOf(word("x", 3, 100)))
        val backupEnglish = available("en-US").withId(1)
        val store = LearnedStores.storeName("Latn", 1) + LearnedStores.DICT_EXTENSION
        backup(listOf(backupEnglish), mapOf(LearnedKeyboards.PREF_DONE to true, LearnedKeyboards.PREF_POOLS to JSONObject().put(kid(1), 1).toString()),
            mapOf("$store/$store.body" to (FakeLearnedStoreIo.format(word("c", 2, 200)) + "\n").toByteArray()))
        countingIds()
        LearnedKeyboards.storeIo = io
        try {
            restoreChosenFrom(ctx, zip, listOf(backupEnglish), settings = true, learnedWords = true, io = io,
                decisions = mapOf(backupEnglish to RestoreDecision.Separate()))
        } finally { LearnedKeyboards.storeIo = LearnedStoreIo.Native }
        assertEquals(5, LearnedKeyboards.poolOrNull(real, listedById(real, "k1000")!!), "the device's English keeps its pool")
        assertEquals(setOf("x"), words(5), "the device's words, unchanged")
        val pool = LearnedKeyboards.poolOrNull(real, listedById(real, kid(1))!!)!!
        assertTrue(pool != 5, "a pool of its own")
        assertEquals(setOf("c"), words(pool), "the backup's words alone")
    }

    // ---- Required for ----

    // owner 2026-10-12: an item a ticked keyboard uses is ticked and can't be unticked, and still comes in when that
    // keyboard's Appearance is unticked on "What to merge"
    @Test fun `an item a ticked keyboard uses comes in, even with that keyboard's Appearance unticked`() {
        val deviceEnglish = available("en-US").withId(1)
        device(listOf(deviceEnglish), own = mapOf(idKey(1, gap) to 1f))
        val backupEnglish = available("en-US").withId(1)
        backup(listOf(backupEnglish), mapOf(idKey(1, theme) to "Dusk", idKey(1, gap) to 3f, looks(look("Dusk", 3.0))))
        val plan = restorePlanFrom(ctx, zip)
        val flow = RestoreFlow(plan, plan.incoming.keys.toList())
        flow.top.check(false)
        val dusk = Kind.THEMES to "Dusk"
        assertEquals(listOf(english), flow.requiredFor(dusk))
        flow.tick(dusk, false)
        assertTrue(flow.ticked(dusk), "required: stays ticked")
        assertEquals(emptySet(), flow.choices().unticked)
        restoreKeyboardsFrom(ctx, zip, listOf(backupEnglish), withSettings = true, names = flow.choices(), decisions = mapOf(backupEnglish to
            RestoreDecision.Merge(deviceEnglish, parts = emptySet(), menus = setOf(KeyboardProfiles.Group.LAYOUT))))
        assertEquals(mapOf("Dusk" to 3f), themes(), "Dusk came in")
        assertEquals(1f, own(kid(1), gap), "English's Appearance unticked: its own look stays")
        // and with the keyboard unticked, the row is free again (unticked, as the user left it)
        flow.top.selected.remove(backupEnglish)
        assertEquals(emptyList(), flow.requiredFor(dusk))
        assertFalse(flow.ticked(dusk))
    }

    // ---- the questions ----

    @Test fun `Back keeps every answer and tick, and an unticked row's answer comes back when ticked again`() {
        val deviceEnglish = available("en-US").withId(1)
        device(listOf(deviceEnglish), extra = mapOf(looks(look("Sunset", 1.0))))
        val backupEnglish = available("en-US").withId(1)
        backup(listOf(backupEnglish), mapOf(looks(look("Sunset", 3.0))))
        val plan = restorePlanFrom(ctx, zip)
        val flow = RestoreFlow(plan, plan.incoming.keys.toList())
        flow.top.check(false)
        val sunset = Kind.THEMES to "Sunset"
        assertEquals(listOf(RestoreFlow.Question.Keyboard(backupEnglish), RestoreFlow.Question.Item(Kind.THEMES, "Sunset")), flow.questions)
        assertTrue(flow.trickyAhead)
        assertFalse(flow.begin())
        assertEquals(0, flow.index)
        // the keyboard: the device's with its id picked to start with; "What to merge" with Appearance ticked, then Back
        val question = flow.mergeQuestion(backupEnglish)
        assertEquals(MergeQuestion.Pick(deviceEnglish), question.picked)
        flow.keep(question)
        flow.choosing = true
        val differences = listOf(RestoreMatch.Difference.Menu(KeyboardProfiles.Group.APPEARANCE), RestoreMatch.Difference.Menu(KeyboardProfiles.Group.LAYOUT))
        val choice = flow.mergeChoice(question, deviceEnglish, differences, wordsRow = false)
        choice.ticked.add(differences[0])
        flow.keep(choice, decide = false)
        flow.back()
        assertFalse(flow.choosing); assertEquals(0, flow.index)
        // Keep both instead, named Mine; Next to the theme, answered Keep both, then Back
        question.picked = MergeQuestion.Pick(null)
        question.separateName = "Mine"
        flow.keep(question)
        assertFalse(flow.next())
        assertEquals(RestoreFlow.Question.Item(Kind.THEMES, "Sunset"), flow.current)
        assertTrue(flow.isLast)
        val item = flow.itemQuestion(flow.current as RestoreFlow.Question.Item)!!
        assertEquals(Answer.KeepBoth, item.picked, "the same name, other content: Keep both suggested (owner 2026-10-12, A2)")
        item.picked = Answer.Replace("Sunset")
        flow.keep(item)
        flow.back()
        assertEquals(0, flow.index)
        val again = flow.mergeQuestion(backupEnglish)
        assertEquals(MergeQuestion.Pick(null), again.picked)
        assertEquals("Mine", again.separateName)
        again.picked = MergeQuestion.Pick(deviceEnglish)
        assertEquals(listOf(differences[0]), flow.mergeChoice(again, deviceEnglish, differences, wordsRow = false).ticked.toList(),
            "What to merge's ticks kept")
        // back to screen 1: the theme unticked drops its question, ticked again its answer is back
        flow.back()
        assertEquals(-1, flow.index)
        flow.tick(sunset, false)
        assertEquals(listOf(RestoreFlow.Question.Keyboard(backupEnglish)), flow.questions)
        flow.tick(sunset, true)
        assertEquals(Answer.Replace("Sunset"), flow.itemQuestion(RestoreFlow.Question.Item(Kind.THEMES, "Sunset"))!!.picked)
        assertEquals(mapOf<SettingsSubtype, RestoreDecision>(backupEnglish to RestoreDecision.Separate("Mine")), flow.decisions())
        assertEquals(Answer.Replace("Sunset"), flow.choices().answers[sunset])
    }

    // ---- one device item, one claimant (review 2026-10-12 A2, owner: option D) ----

    /** The device's English (k1) on its Sunset (gap 1); the backup's English (k1, using no theme) with Sunset (gap 3, other
     *  colors) and Dusk (gap 1, the device Sunset's), in [order]; screen 1 done, the flow at its first theme question. */
    private fun sunsetAndDusk(vararg order: String): Pair<RestoreFlow, SettingsSubtype> {
        val deviceEnglish = available("en-US").withId(1)
        device(listOf(deviceEnglish), own = mapOf(idKey(1, theme) to "Sunset"), extra = mapOf(looks(look("Sunset", 1.0))))
        val gaps = mapOf("Sunset" to 3.0, "Dusk" to 1.0)
        backup(listOf(available("en-US").withId(1)), mapOf(looks(*order.map { look(it, gaps.getValue(it)) }.toTypedArray())))
        val plan = restorePlanFrom(ctx, zip)
        val flow = RestoreFlow(plan, plan.incoming.keys.toList())
        flow.top.check(false)
        assertEquals(order.map { RestoreFlow.Question.Item(Kind.THEMES, it) }, flow.questions.filterIsInstance<RestoreFlow.Question.Item>())
        assertFalse(flow.begin())
        while (flow.current !is RestoreFlow.Question.Item) assertFalse(flow.next())
        return flow to deviceEnglish
    }

    private fun RestoreFlow.itemShowing() = itemQuestion(current as RestoreFlow.Question.Item)!!

    /** The flow's answers run, the backup's English merged into the device's (its settings unchanged). */
    private fun run(flow: RestoreFlow, deviceEnglish: SettingsSubtype) {
        val backupEnglish = flow.keyboards.single()
        restoreKeyboardsFrom(ctx, zip, listOf(backupEnglish), withSettings = true, names = flow.choices(),
            decisions = mapOf(backupEnglish to RestoreDecision.Merge(deviceEnglish, parts = emptySet(), menus = emptySet())))
    }

    // fail before: Sunset's question picked Replace of the device's Sunset, which Dusk's merge had taken already (both
    // pointed at it; the run silently kept both), and Keep both said "yours becomes Sunset 2" for a Sunset gone by then
    @Test fun `Dusk first merges into the device's Sunset; Sunset's question shows it taken by Dusk, Keep both picked, no Sunset 2`() {
        val (flow, deviceEnglish) = sunsetAndDusk("Dusk", "Sunset")
        val dusk = flow.itemShowing()
        assertEquals(emptyMap(), dusk.taken)
        assertEquals(Answer.Replace("Sunset"), dusk.picked, "the same colors: merged into, suggested")
        flow.keep(dusk)
        assertFalse(flow.next())
        val sunset = flow.itemShowing()
        assertEquals(mapOf("Sunset" to "Dusk"), sunset.taken, "the device's Sunset: Already taken by Dusk")
        assertEquals(Answer.KeepBoth, sunset.picked)
        assertFalse(sunset.keepBothRenames, "the device's Sunset is Dusk by then: the backup's comes in as Sunset, no number")
        assertEquals("Already taken by Dusk", ctx.getString(helium314.keyboard.latin.R.string.restore_item_taken, "Dusk"))
        flow.keep(sunset)
        assertTrue(flow.isLast)
        run(flow, deviceEnglish)
        assertEquals(mapOf("Dusk" to 1f, "Sunset" to 3f), themes())
        assertEquals("Dusk", own(kid(1), theme), "the device's English follows its theme, renamed Dusk")
    }

    // owner 2026-10-12: "Same name but different content feels like both will be valuable to have": Keep both picked for
    // Sunset; Keep both takes no device item, so Dusk still merges into the device's Sunset. Answered Replace instead,
    // Sunset takes it: Dusk's question shows it taken by Sunset, Keep both picked
    @Test fun `Sunset first - Keep both picked, Dusk merges into the device's Sunset; Sunset replacing it, Dusk shows it taken`() {
        val (flow, deviceEnglish) = sunsetAndDusk("Sunset", "Dusk")
        val sunset = flow.itemShowing()
        assertEquals(Answer.KeepBoth, sunset.picked, "same name, other colors: Keep both suggested (before: Replace)")
        assertTrue(sunset.keepBothRenames)
        flow.keep(sunset)
        assertFalse(flow.next())
        val dusk = flow.itemShowing()
        assertEquals(emptyMap(), dusk.taken)
        assertEquals(Answer.Replace("Sunset"), dusk.picked)
        flow.keep(dusk)
        // Back to Sunset, answered Replace: the device's Sunset is Sunset's, Dusk's earlier answer can't stand
        flow.back()
        val again = flow.itemShowing()
        again.picked = Answer.Replace("Sunset")
        flow.keep(again)
        assertFalse(flow.next())
        val dusk2 = flow.itemShowing()
        assertEquals(mapOf("Sunset" to "Sunset"), dusk2.taken)
        assertEquals(Answer.KeepBoth, dusk2.picked, "the device's Sunset taken: nothing else to merge into")
        flow.keep(dusk2)
        run(flow, deviceEnglish)
        assertEquals(mapOf("Sunset" to 3f, "Dusk" to 1f), themes())
        assertEquals("Sunset", own(kid(1), theme), "the device's English on Sunset, now the backup's")
    }

    // Keep both picked for Sunset and the device's Sunset merged into by Dusk after it: the device's goes Sunset 2, then
    // Dusk; nothing is left numbered (the run, unanswered: the suggested answers)
    @Test fun `unanswered, Sunset kept both and Dusk merged - the device's Sunset ends as Dusk, no Sunset 2`() {
        val (flow, deviceEnglish) = sunsetAndDusk("Sunset", "Dusk")
        run(flow, deviceEnglish)
        assertEquals(mapOf("Sunset" to 3f, "Dusk" to 1f), themes())
        assertEquals("Dusk", own(kid(1), theme))
        assertEquals(emptySet(), real.all.filterValues { it == "Sunset 2" }.keys)
    }
}

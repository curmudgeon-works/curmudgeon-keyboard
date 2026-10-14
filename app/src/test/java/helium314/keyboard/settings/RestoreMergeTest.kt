// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.countingIds
import helium314.keyboard.idKey
import helium314.keyboard.kid
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardIdUpgrade
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.listedById
import helium314.keyboard.randomIds
import helium314.keyboard.settings.preferences.MergeChoice
import helium314.keyboard.settings.preferences.MergeQuestion
import helium314.keyboard.settings.preferences.RestoreDecision
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.restoreKeyboardsFrom
import helium314.keyboard.settings.preferences.restorePlanFrom
import helium314.keyboard.settings.preferences.settingsToJsonStream
import helium314.keyboard.settings.screens.customKeyboardName
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.settings.screens.withKeyboardName
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A restore of some keyboards matches by language and asks (owner 2026-10-11: "only check if language is same"): merge
 * the backup's keyboard into one phone keyboard of its language (switched-off ones too), then what to merge, or keep it
 * separate; either way with a name. The real restore does exactly what was answered (review R1).
 * (owner 2026-10-11: keyboards have unique ids) QWERTY is "k1", Dvorak "k2", Colemak "k3", in the phone and in the
 * backup alike (a backup of this phone); a merge keeps the phone keyboard's id, one added keeps the backup's.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreMergeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = DeviceProtectedUtils.getRealSharedPreferences(ctx)
    private val zip by lazy { File(ctx.cacheDir, "merge-${System.nanoTime()}.zip") }
    private lateinit var qwerty: SettingsSubtype
    private lateinit var dvorak: SettingsSubtype
    private lateinit var colemak: SettingsSubtype
    private val main = LayoutType.MAIN
    private lateinit var english: String // (the languages' name: "English (US)")

    @Before fun setUp() {
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); KeyboardProfiles.filesDir = null
        SubtypeSettings.init(ctx)
        qwerty = available("en-US").withId(1)
        dvorak = available("en-US").withLayout(main, "dvorak").withId(2)
        colemak = available("en-US").withLayout(main, "colemak").withId(3)
        english = keyboardName(qwerty, ctx)
    }
    @After fun tearDown() { randomIds(); zip.delete(); real.edit().clear().commit(); KeyboardProfiles.loadGroups(real); SubtypeSettings.reloadEnabledSubtypes(ctx) }

    private fun enabled() = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
    private fun reads(keyboard: SettingsSubtype) = KeyboardProfiles.effectiveSettingsIn(real.all, keyboard)
    private fun name(keyboard: SettingsSubtype) = keyboardName(keyboard, ctx)

    /** The phone: [keyboards] (the user's), number row off, gaps 1, separate settings on; the backup: [inBackup], number
     *  row on, gaps 3. */
    private fun phoneAndBackup(keyboards: List<SettingsSubtype>, inBackup: List<SettingsSubtype>,
                               switchedOff: List<SettingsSubtype> = emptyList()) {
        real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards))
            .putBoolean("separate_settings_per_keyboard", true).commit()
        useKeyboards(ctx, real, *keyboards.toTypedArray())
        // ([switchedOff]: in the added list only, as switched off on the Languages screen)
        if (switchedOff.isNotEmpty()) {
            real.edit().putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards + switchedOff)).commit()
            SubtypeSettings.reloadEnabledSubtypes(ctx)
        }
        // (each keyboard's settings its own set, the phone's and the backup's; owner 2026-10-11: no separate common set;
        // the first keyboard's own settings are the common ones)
        val ids = keyboards.map { it.keyboardId!! }
        real.edit().apply { for (id in ids) putBoolean(KeyboardProfiles.ownKey(id, Settings.PREF_SHOW_NUMBER_ROW), false)
            .putFloat(KeyboardProfiles.ownKey(id, Settings.PREF_KEY_HORIZONTAL_GAP), 1f) }.commit()
        writeBackup(inBackup)
    }

    private fun writeBackup(inBackup: List<SettingsSubtype>) {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("preferences.json"))
            settingsToJsonStream(HashMap<String?, Any?>(mapOf(Settings.PREF_ENABLED_SUBTYPES to SubtypeSettings.createPrefSubtypes(inBackup),
                Settings.PREF_ADDITIONAL_SUBTYPES to SubtypeSettings.createPrefSubtypes(inBackup),
                Settings.PREF_SELECTED_SUBTYPE to inBackup.first().toPref(), KeyboardIdUpgrade.DONE to true,
                helium314.keyboard.latin.PICKED_ONLY_DONE to true, helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE to true,
                helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE to true) +
                inBackup.flatMap { k -> listOf(KeyboardProfiles.ownKey(k.keyboardId!!, Settings.PREF_SHOW_NUMBER_ROW) to true,
                    KeyboardProfiles.ownKey(k.keyboardId!!, Settings.PREF_KEY_HORIZONTAL_GAP) to 3f) }), z)

            z.closeEntry()
        }
    }

    /** The phone's settings, but for the keyboard lists a restore may change and the sets of [ids]. */
    private fun settingsOutside(vararg ids: String) = real.all.filterKeys { key ->
        KeyboardProfiles.splitOwnKey(key)?.let { it.first !in ids } ?: (key !in setOf(Settings.PREF_ENABLED_SUBTYPES,
            Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE))
    }

    @Test fun `(a) R1 - only Dvorak kept separate - the phone's QWERTY is untouched, Dvorak comes in as English (US) 2`() {
        phoneAndBackup(listOf(qwerty), listOf(qwerty, dvorak))
        val id = kid(1)
        val qwertySet = real.all.filterKeys { KeyboardProfiles.splitOwnKey(it)?.first == id }
        val plan = restorePlanFrom(ctx, zip)
        assertTrue(plan.asks(dvorak))
        assertEquals(listOf(qwerty), plan.candidates(dvorak, emptyMap()))
        val question = MergeQuestion(plan, dvorak, listOf(dvorak), emptyMap())
        assertEquals("$english 2", question.separateName)
        question.picked = MergeQuestion.Pick(null)
        assertTrue(question.canGoOn)
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to question.separate))
        val added = withKeyboardName(dvorak, "$english 2").toPref().toSettingsSubtype()
        assertEquals(listOf(qwerty, added), enabled())
        assertEquals("$english 2", customKeyboardName(added))
        assertEquals(qwerty, listedById(real, id))
        assertEquals(kid(2), added.keyboardId, "the backup's id")
        assertEquals(qwertySet, real.all.filterKeys { KeyboardProfiles.splitOwnKey(it)?.first == id }, "QWERTY's set changed")
        assertEquals(false, reads(qwerty)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(true, reads(added)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(3f, reads(added)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `(a) R1 - a keyboard chosen without an answer replaces nothing`() {
        phoneAndBackup(listOf(qwerty), listOf(qwerty, dvorak))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true)
        assertEquals(listOf(qwerty, dvorak), enabled())
        assertEquals(false, reads(qwerty)[Settings.PREF_SHOW_NUMBER_ROW])
        // the backup's QWERTY, this phone's own id: comes in as a keyboard of its own with that id, and the phone's QWERTY
        // gets a new one (came from "k1"), its settings with it (owner 2026-10-11: keyboards have unique ids; was: the same
        // as the phone's, it came in as nothing)
        countingIds()
        restoreKeyboardsFrom(ctx, zip, listOf(qwerty), withSettings = true)
        val renewed = qwerty.withKeyboardId("k1000", kid(1)).toPref().toSettingsSubtype()
        assertEquals(3, enabled().size)
        assertEquals(setOf(renewed, dvorak, qwerty), enabled().toSet())
        assertEquals(renewed, listedById(real, "k1000"))
        assertEquals(false, reads(renewed)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(1f, reads(renewed)[Settings.PREF_KEY_HORIZONTAL_GAP])
        assertEquals(true, reads(qwerty)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(3f, reads(qwerty)[Settings.PREF_KEY_HORIZONTAL_GAP])
    }

    @Test fun `(b) Dvorak merged into QWERTY with only Keys ticked - the phone's keyboard gets its keys, nothing else changes`() {
        phoneAndBackup(listOf(qwerty), listOf(qwerty, dvorak))
        val before = real.all
        val plan = restorePlanFrom(ctx, zip)
        val differences = RestoreMatch.differences(qwerty, plan.incoming.getValue(dvorak), true,
            { RestoreMatch.phoneSettings(real, qwerty) }, { helium314.keyboard.settings.preferences.restoredSettings(ctx, emptyMap(), dvorak) })
        val keys = differences.single { it is RestoreMatch.Difference.Definition && it.part == RestoreMatch.Part.Layout(main) }
        val probe = plan.mergeDecision(qwerty, differences, listOf(keys), learnedRow = false, learnedTicked = false, name = null)
        val name = plan.prefillName(dvorak, probe, listOf(dvorak), emptyMap())
        assertEquals(english, name)
        val decision = plan.mergeDecision(qwerty, differences, listOf(keys), learnedRow = false, learnedTicked = false, name = name)
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to decision))
        val merged = qwerty.withLayout(main, "dvorak").toPref().toSettingsSubtype()
        assertEquals(listOf(merged), enabled())
        assertEquals(kid(1), merged.keyboardId, "its own set, the phone keyboard's id kept")
        assertEquals(false, reads(merged)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(1f, reads(merged)[Settings.PREF_KEY_HORIZONTAL_GAP])
        // (but the keyboard list and identities; the saved items' lists the restore writes, empty here, as before)
        fun rest(all: Map<String, *>) = all.filter { (key, value) -> key != Settings.PREF_ENABLED_SUBTYPES
            && key != Settings.PREF_SELECTED_SUBTYPE && key != Settings.PREF_ADDITIONAL_SUBTYPES && !(key !in before && value == "[]") }
        assertEquals(rest(before), rest(real.all))
    }

    @Test fun `(c) nothing ticked restores nothing of it`() {
        phoneAndBackup(listOf(qwerty), listOf(qwerty, dvorak))
        val plan = restorePlanFrom(ctx, zip)
        val differences = RestoreMatch.definitionDifferences(qwerty, plan.incoming.getValue(dvorak))
        assertEquals(RestoreDecision.Skip, plan.mergeDecision(qwerty, differences, emptyList(), learnedRow = true, learnedTicked = false, name = english))
        // every one ticked: the backup's whole keyboard; nothing differing at all: done, the backup's
        assertEquals(RestoreDecision.Merge(qwerty, name = english), plan.mergeDecision(qwerty, differences, differences, false, false, english))
        assertEquals(RestoreDecision.Merge(qwerty, name = english), plan.mergeDecision(qwerty, emptyList(), emptyList(), false, false, english))
        val before = real.all
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to RestoreDecision.Skip))
        assertEquals(before, real.all)
        assertEquals(listOf(qwerty), enabled())
    }

    @Test fun `(d) two English keyboards on the phone are both offered, nothing picked`() {
        phoneAndBackup(listOf(qwerty, colemak), listOf(dvorak))
        val plan = restorePlanFrom(ctx, zip)
        val question = MergeQuestion(plan, dvorak, listOf(dvorak), emptyMap())
        assertEquals(listOf(qwerty, colemak), question.options)
        assertNull(question.picked)
        assertFalse(question.canGoOn)
        question.picked = MergeQuestion.Pick(colemak)
        assertTrue(question.canGoOn)
    }

    @Test fun `(e) a switched-off English keyboard is offered as switched off, and merging switches it on`() {
        val german = available("de-DE").withId(4)
        // QWERTY switched off on the Languages screen: its id and own set kept
        phoneAndBackup(listOf(german), listOf(dvorak), switchedOff = listOf(qwerty))
        real.edit().putFloat(idKey(1, Settings.PREF_KEY_HORIZONTAL_GAP), 7f).commit()
        val plan = restorePlanFrom(ctx, zip)
        assertTrue(plan.asks(dvorak))
        assertEquals(listOf(qwerty), plan.candidates(dvorak, emptyMap()))
        assertTrue(plan.isSwitchedOff(qwerty))
        assertEquals("$english (switched off)", ctx.getString(R.string.keyboard_switched_off, name(qwerty)))
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = true, decisions = mapOf(dvorak to
            RestoreDecision.Merge(qwerty, parts = setOf(RestoreMatch.Part.Layout(main)), menus = emptySet(), learnedWords = false)))
        val merged = qwerty.withLayout(main, "dvorak").toPref().toSettingsSubtype()
        assertEquals(listOf(german, merged), enabled())
        assertEquals(merged, listedById(real, kid(1)), "the switched-off keyboard's id kept")
        assertEquals(7f, real.getFloat(idKey(1, Settings.PREF_KEY_HORIZONTAL_GAP), 0f))
    }

    @Test fun `(f) a taken name is prefilled numbered, and a typed duplicate name is taken when the keyboards still differ`() {
        val second = withKeyboardName(colemak, "$english 2").toPref().toSettingsSubtype()
        phoneAndBackup(listOf(qwerty, second), listOf(dvorak))
        assertEquals("$english 2", name(second))
        val plan = restorePlanFrom(ctx, zip)
        assertEquals("$english 3", plan.prefillName(dvorak, RestoreDecision.Separate(), listOf(dvorak), emptyMap()))
        // the same name as the phone's QWERTY: other keys, another keyboard
        val duplicate = RestoreDecision.Separate(english)
        restoreKeyboardsFrom(ctx, zip, listOf(dvorak), withSettings = false, decisions = mapOf(dvorak to duplicate))
        assertEquals(listOf(qwerty, second, dvorak), enabled())
        assertEquals(english, name(dvorak))
        // a merge keeps the phone keyboard's own name
        assertEquals(english, plan.prefillName(dvorak, RestoreDecision.Merge(qwerty, parts = setOf(RestoreMatch.Part.Layout(main)), menus = emptySet()), listOf(dvorak), emptyMap()))
    }

    // (owner 2026-10-12: "yes, when merging the name should be current name"; was: numbered when another keyboard had
    // it, "English (US) 2") fail before: the merge's box said "English (US) 2"
    @Test fun `merging, the name box is the device keyboard's current name even when another keyboard has it`() {
        val twin = withKeyboardName(colemak, english).toPref().toSettingsSubtype()
        phoneAndBackup(listOf(qwerty, twin), listOf(dvorak))
        assertEquals(english, name(twin))
        val plan = restorePlanFrom(ctx, zip)
        assertEquals(english, plan.prefillName(dvorak, RestoreDecision.Merge(qwerty), listOf(dvorak), emptyMap()))
        assertEquals(english, plan.prefillName(dvorak, RestoreDecision.Merge(qwerty, parts = setOf(RestoreMatch.Part.Layout(main)),
            menus = emptySet()), listOf(dvorak), emptyMap()))
        val work = withKeyboardName(qwerty, "Work").toPref().toSettingsSubtype()
        assertEquals("Work", plan.prefillName(dvorak, RestoreDecision.Merge(work), listOf(dvorak), emptyMap()))
        // kept separate: still the next free name
        assertEquals("$english 2", plan.prefillName(dvorak, RestoreDecision.Separate(), listOf(dvorak), emptyMap()))
        // and what "What to merge" starts with
        val question = MergeQuestion(plan, dvorak, listOf(dvorak), emptyMap())
        val differences = RestoreMatch.definitionDifferences(qwerty, plan.incoming.getValue(dvorak))
        val choice = MergeChoice(question, qwerty, differences, wordsRow = false)
        choice.ticked.addAll(differences)
        assertEquals(english, choice.name)
    }

    // (owner 2026-10-11: keyboards have unique ids; was: a result set up like another keyboard was blocked, "fully
    // identical", and the restore never made one; now nothing is blocked, the two are distinct keyboards)
    @Test fun `(g) a keyboard set up like another is not blocked, and both stay distinct keyboards`() {
        // (the backup's Dvorak another phone's keyboard, "k5", set up as the phone's Dvorak)
        val backupDvorak = dvorak.withoutIds().withId(5)
        phoneAndBackup(listOf(qwerty, dvorak), listOf(backupDvorak))
        val plan = restorePlanFrom(ctx, zip)
        val separate = MergeQuestion(plan, backupDvorak, listOf(backupDvorak), emptyMap())
        separate.picked = MergeQuestion.Pick(null)
        separate.separateName = english
        assertTrue(separate.canGoOn)
        assertFalse(separate.isSameKeyboard(dvorak), "another id: no same-keyboard hint")
        // its keys merged into QWERTY: set up like the phone's Dvorak, still QWERTY's id
        val keysOnly = RestoreDecision.Merge(qwerty, parts = setOf(RestoreMatch.Part.Layout(main)), menus = emptySet())
        restoreKeyboardsFrom(ctx, zip, listOf(backupDvorak), withSettings = true, decisions = mapOf(backupDvorak to keysOnly))
        val merged = qwerty.withLayout(main, "dvorak").toPref().toSettingsSubtype()
        assertEquals(listOf(merged, dvorak), enabled())
        assertEquals(dvorak.withoutIds(), merged.withoutIds())
        assertFalse(merged.sameKeyboard(dvorak))
        assertEquals(false, reads(merged)[Settings.PREF_SHOW_NUMBER_ROW], "QWERTY's own settings")
    }

    @Test fun `(h) a keyboard whose language the phone has none of comes in as new, unasked`() {
        val german = available("de-DE").withId(4)
        phoneAndBackup(listOf(qwerty), listOf(german))
        val plan = restorePlanFrom(ctx, zip)
        assertFalse(plan.asks(german))
        restoreKeyboardsFrom(ctx, zip, listOf(german), withSettings = true)
        assertEquals(listOf(qwerty, german), enabled())
        assertEquals(true, reads(german)[Settings.PREF_SHOW_NUMBER_ROW])
        assertEquals(false, reads(qwerty)[Settings.PREF_SHOW_NUMBER_ROW])
    }

    @Test fun `(i) a phone keyboard takes one backup keyboard at most`() {
        phoneAndBackup(listOf(qwerty), listOf(qwerty, dvorak))
        val plan = restorePlanFrom(ctx, zip)
        val first = mapOf<SettingsSubtype, RestoreDecision>(qwerty to RestoreDecision.Merge(qwerty))
        assertEquals(emptyList(), plan.candidates(dvorak, first))
        assertEquals(emptyList(), MergeQuestion(plan, dvorak, listOf(qwerty, dvorak), first).options)
        assertEquals(listOf(qwerty), plan.candidates(qwerty, first), "its own pick stays offered")
        // kept apart: its name is compared with what the other one becomes
        assertEquals("$english 2", plan.prefillName(dvorak, RestoreDecision.Separate(), listOf(qwerty, dvorak), first))
    }

    @Test fun `a name is a Rename's - its languages' name is no name of its own, another text is`() {
        fun named(kb: SettingsSubtype, text: String?) = RestoreDecision.named(kb, text) { name(it) }
        assertEquals(dvorak, named(dvorak, english))
        assertEquals(dvorak, named(dvorak, " "))
        assertEquals(dvorak, named(dvorak, null))
        assertEquals("Work", customKeyboardName(named(dvorak, "Work")!!))
        val work = named(dvorak, "Work")
        assertEquals(work, named(work, "Work"))
        assertEquals(dvorak, named(work, english))
        assertNotNull(named(qwerty, "$english 2").getExtraValueOf(ExtraValue.KEYBOARD_NAME))
    }

    // owner 2026-10-11: "Yes, count up pls."
    @Test fun `a taken copy's name counts its number up - English (US) (2) gives English (US) (3)`() {
        val copy2 = qwerty.withoutIds().with(ExtraValue.KEYBOARD_COPY, "2").withId(6)
        val backupCopy = dvorak.with(ExtraValue.KEYBOARD_COPY, "2").toPref().toSettingsSubtype()
        phoneAndBackup(listOf(qwerty, copy2), listOf(backupCopy))
        assertEquals("$english (2)", name(copy2))
        val plan = restorePlanFrom(ctx, zip)
        assertEquals("$english (3)", plan.prefillName(backupCopy, RestoreDecision.Separate(), listOf(backupCopy), emptyMap()))
    }

    @Test fun `a taken custom name counts its number up - Work 2 gives Work 3, Sunset gives Sunset 2`() {
        val work2 = withKeyboardName(colemak, "Work 2").toPref().toSettingsSubtype()
        val sunset = withKeyboardName(qwerty, "Sunset").toPref().toSettingsSubtype()
        val backupWork2 = withKeyboardName(dvorak, "Work 2").toPref().toSettingsSubtype()
        val backupSunset = withKeyboardName(dvorak.withoutIds().withLayout(LayoutType.NUMBER_ROW, "x").withId(7), "Sunset").toPref().toSettingsSubtype()
        phoneAndBackup(listOf(work2, sunset), listOf(backupWork2, backupSunset))
        val plan = restorePlanFrom(ctx, zip)
        assertEquals("Work 3", plan.prefillName(backupWork2, RestoreDecision.Separate(), listOf(backupWork2), emptyMap()))
        assertEquals("Sunset 2", plan.prefillName(backupSunset, RestoreDecision.Separate(), listOf(backupSunset), emptyMap()))
    }

    // owner 2026-10-11: "Yes learned and blacklisted words should both come, ticked on by default. Not off."
    @Test fun `the words row starts ticked, and words alone are not dropped as nothing differing`() {
        phoneAndBackup(listOf(qwerty), listOf(qwerty, dvorak))
        val plan = restorePlanFrom(ctx, zip)
        val question = MergeQuestion(plan, dvorak, listOf(dvorak), emptyMap())
        val words = MergeChoice(question, qwerty, emptyList(), wordsRow = true)
        assertTrue(words.wordsTicked)
        assertFalse(words.nothingDiffers)
        assertEquals(RestoreDecision.Merge(qwerty, name = english), words.decision)
        words.wordsTicked = false
        assertEquals(RestoreDecision.Skip, words.decision)
        // differences and words: only the words, untouched, come with the phone's keyboard as it is
        val differences = RestoreMatch.definitionDifferences(qwerty, plan.incoming.getValue(dvorak))
        val some = MergeChoice(question, qwerty, differences, wordsRow = true)
        assertEquals(RestoreDecision.Merge(qwerty, emptySet(), emptySet(), learnedWords = true, name = english), some.decision)
        // no words row and nothing differing: Done, nothing to tick
        assertTrue(MergeChoice(question, qwerty, emptyList(), wordsRow = false).nothingDiffers)
    }
}

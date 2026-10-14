// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.latin.personalization.available
import helium314.keyboard.latin.personalization.useKeyboards
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.KeyboardProfiles.Group
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.preferences.AfterRestoreNote
import helium314.keyboard.settings.preferences.BackupRestoreJob
import helium314.keyboard.settings.preferences.MergeChoice
import helium314.keyboard.settings.preferences.MergeQuestion
import helium314.keyboard.settings.preferences.RestoreMatch
import helium314.keyboard.settings.preferences.RestorePlan
import helium314.keyboard.settings.preferences.commonMenusNote
import helium314.keyboard.settings.preferences.runRestore
import helium314.keyboard.settings.screens.groupTitle
import helium314.keyboard.settings.screens.keyboardName
import helium314.keyboard.withId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Restore screen 2 says which ticked menus are common (R-4, owner 2026-10-11 wording), and what the restore has to say
 * afterwards is a dialog with OK, not a toast (owner 2026-10-12: "ahh. Let's do dialog"). The device: English (US) (k1,
 * first) and Hindi (k2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class RestoreCommonNoteTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val real = ctx.realPrefs()
    private lateinit var en: SettingsSubtype
    private lateinit var hi: SettingsSubtype
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val layout get() = ctx.getString(groupTitle(Group.LAYOUT))
    private val appearance get() = ctx.getString(groupTitle(Group.APPEARANCE))
    private val enName get() = keyboardName(en, ctx)
    private val hiName get() = keyboardName(hi, ctx)

    @Before fun setUp() {
        helium314.keyboard.reviveComposeMainDispatcher()
        real.edit().clear().commit()
        SubtypeSettings.init(ctx)
        en = available("en-US").withId(1); hi = available("hi").withId(2)
        useKeyboards(ctx, real, en, hi)
        separate(true)
    }

    @After fun tearDown() {
        scenario?.close()
        BackupRestoreJob.afterNote = null
        val until = System.currentTimeMillis() + 10_000
        while (BackupRestoreJob.isRunning && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        real.edit().clear().commit(); KeyboardProfiles.loadGroups(real)
        SubtypeSettings.reloadEnabledSubtypes(ctx)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun separate(on: Boolean, vararg shared: Group) {
        val e = real.edit().putBoolean("separate_settings_per_keyboard", on)
        for (g in Group.entries) e.putBoolean(g.prefKey, g in shared)
        e.commit()
        KeyboardProfiles.loadGroups(real)
    }

    @Test fun `separate settings, a shared menu restored into the first keyboard shows on every keyboard`() {
        separate(true, Group.APPEARANCE)
        assertEquals("$appearance is common to all your keyboards (App settings › Per keyboard), and $enName is first in your " +
            "list. The backup's $appearance will show on every keyboard. Each keyboard's own settings stay saved.",
            commonMenusNote(ctx, en, listOf(Group.APPEARANCE, Group.SWIPE)))
        separate(true, Group.LAYOUT, Group.APPEARANCE)
        assertEquals("$layout, $appearance are common to all your keyboards (App settings › Per keyboard), and $enName is first " +
            "in your list. The backup's $layout, $appearance will show on every keyboard. Each keyboard's own settings stay saved.",
            commonMenusNote(ctx, en, listOf(Group.APPEARANCE, Group.LAYOUT)))
    }

    @Test fun `separate settings, a shared menu restored into another keyboard waits in its own settings`() {
        separate(true, Group.APPEARANCE)
        assertEquals("$appearance is common to all your keyboards (App settings › Per keyboard) and comes from $enName, first in " +
            "your list. The backup's $appearance is restored into $hiName's own settings, and shows once that menu is set per keyboard.",
            commonMenusNote(ctx, hi, listOf(Group.APPEARANCE)))
        separate(true, Group.LAYOUT, Group.APPEARANCE)
        assertEquals("$layout, $appearance are common to all your keyboards (App settings › Per keyboard) and come from $enName, " +
            "first in your list. The backup's $layout, $appearance are restored into $hiName's own settings, and show once those " +
            "menus are set per keyboard.", commonMenusNote(ctx, hi, listOf(Group.LAYOUT, Group.APPEARANCE)))
    }

    @Test fun `separate settings off, all settings are common`() {
        separate(false)
        assertEquals("All settings are common to all your keyboards (App settings › Per keyboard: separate settings off), and " +
            "$enName is first in your list. The backup's $layout will show on every keyboard. Each keyboard's own settings stay saved.",
            commonMenusNote(ctx, en, listOf(Group.LAYOUT)))
        assertEquals("All settings are common to all your keyboards (App settings › Per keyboard: separate settings off) and come " +
            "from $enName, first in your list. The backup's $layout is restored into $hiName's own settings, and shows once you " +
            "turn on Separate settings per keyboard.", commonMenusNote(ctx, hi, listOf(Group.LAYOUT)))
    }

    @Test fun `nothing common ticked, nothing to say`() {
        separate(true, Group.APPEARANCE)
        assertNull(commonMenusNote(ctx, hi, listOf(Group.LAYOUT)))
        assertNull(commonMenusNote(ctx, hi, emptyList()))
    }

    @Test fun `What to merge shows the note for the ticked menus only`() {
        separate(true, Group.APPEARANCE)
        val hiInBackup = hi.withId(7)
        val plan = RestorePlan(mapOf(hiInBackup to hiInBackup), listOf(en, hi), emptyList(), nameOf = { keyboardName(it, ctx) })
        val menus = listOf(RestoreMatch.Difference.Menu(Group.APPEARANCE), RestoreMatch.Difference.Menu(Group.LAYOUT))
        val choice = MergeChoice(MergeQuestion(plan, hiInBackup, listOf(hiInBackup), emptyMap()), hi, menus, wordsRow = false)
        assertNull(choice.commonNote(ctx), "nothing ticked: not restored, nothing to say")
        choice.ticked.add(menus[1])
        assertNull(choice.commonNote(ctx), "Layout is per keyboard")
        choice.ticked.add(menus[0])
        assertEquals(commonMenusNote(ctx, hi, listOf(Group.APPEARANCE)), choice.commonNote(ctx))
    }

    // ---- 8. the after-note is a dialog with OK ----

    // fail before: the note was a toast, and BackupRestoreJob had no afterNote
    @Test fun `what the restore has to say is kept for a dialog, not a toast`() {
        assertTrue(runRestore(ctx, {}, R.string.backup_restored, note = { "Hindi's settings were restored" }) { })
        val until = System.currentTimeMillis() + 10_000
        while (BackupRestoreJob.afterNote == null && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals("Hindi's settings were restored", BackupRestoreJob.afterNote)
        assertFalse(ShadowToast.showedToast("Hindi's settings were restored"))
        assertFalse(ShadowToast.showedToast(ctx.getString(R.string.backup_restored)))
    }

    @Test fun `the after-note dialog shows once and OK closes it`() {
        BackupRestoreJob.afterNote = "Hindi's settings were restored"
        scenario = ActivityScenario.launch(SettingsActivity::class.java).onActivity { it.setContent { AfterRestoreNote() } }
        compose.waitForIdle()
        compose.onNodeWithText("Hindi's settings were restored").assertExists()
        compose.onNodeWithText(ctx.getString(android.R.string.ok)).performClick()
        compose.waitForIdle()
        assertNull(BackupRestoreJob.afterNote)
        compose.onNodeWithText("Hindi's settings were restored").assertDoesNotExist()
    }
}

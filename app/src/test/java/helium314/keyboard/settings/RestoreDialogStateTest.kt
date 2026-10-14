// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.settings.preferences.RestoreDialogState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The restore dialog's top checkbox (owner 2026-10-11): "Delete app data and restore from backup", checked by default,
 * greys out the keyboard list; unchecked, every keyboard is enabled and selected and the line shows.
 */
class RestoreDialogStateTest {
    private val en = SettingsSubtype(Locale.US, "")
    private val fr = SettingsSubtype(Locale.FRENCH, "")
    private val de = SettingsSubtype(Locale.GERMANY, "")

    @Test fun `checked by default, keyboards greyed out and shown coming back, no line, OK asks before the wipe`() {
        val s = RestoreDialogState(listOf(en, fr))
        assertTrue(s.wipe)
        assertFalse(s.rowsEnabled)
        assertFalse(s.showsMergeLine)
        assertTrue(s.ticked(en) && s.ticked(fr))
        assertEquals(RestoreDialogState.Ok.ConfirmWipe, s.ok())
    }

    @Test fun `unchecked, every keyboard is enabled and selected, the line shows, OK goes keyboard by keyboard`() {
        val s = RestoreDialogState(listOf(en, fr, de))
        s.check(false)
        assertFalse(s.wipe)
        assertTrue(s.rowsEnabled)
        assertTrue(s.showsMergeLine)
        assertEquals(listOf(en, fr, de), s.selected.toList())
        assertEquals(RestoreDialogState.Ok.KeyboardByKeyboard, s.ok())
    }

    @Test fun `the user's ticks stay when the checkbox is checked and unchecked again`() {
        val s = RestoreDialogState(listOf(en, fr))
        s.check(false)
        s.selected.remove(fr)
        s.check(true)
        assertTrue(s.ticked(fr), "greyed out, every keyboard shows as coming back")
        assertEquals(RestoreDialogState.Ok.ConfirmWipe, s.ok())
        s.check(false)
        assertEquals(listOf(en), s.selected.toList())
        assertFalse(s.ticked(fr))
    }

    @Test fun `a backup without a keyboard list can only be restored whole`() {
        val s = RestoreDialogState(emptyList())
        assertFalse(s.canPickKeyboards)
        s.check(false)
        assertTrue(s.wipe)
        assertFalse(s.showsMergeLine)
        assertEquals(RestoreDialogState.Ok.ConfirmWipe, s.ok())
    }
}

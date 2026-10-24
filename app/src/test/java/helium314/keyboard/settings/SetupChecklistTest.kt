// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** The setup's one checklist (owner 2026-10-14): each row's look and whether it can be tapped, on each of the three
 *  screens (1 not enabled, 2 enabled but not in use, 3 in use). No Configure row since 2026-10-24: its action is the
 *  All settings tile's (SetupTilesTest). */
class SetupChecklistTest {
    private fun look(row: SetupRow, step: Int) = setupRowState(row, step).look
    private fun tappable(row: SetupRow, step: Int) = setupRowState(row, step).clickable

    @Test fun `screen 1 has only Enable to tap, everything after it faint`() {
        assertEquals(SetupRowLook.CURRENT, look(SetupRow.ENABLE, 1))
        assertEquals(true, tappable(SetupRow.ENABLE, 1))
        for (row in listOf(SetupRow.SWITCH, SetupRow.THEME, SetupRow.FINISH)) {
            assertEquals(SetupRowLook.LATER, look(row, 1), "$row")
            assertEquals(false, tappable(row, 1), "$row")
        }
    }

    @Test fun `screen 2 has Enable done, Switch to take, the tiles and Finished faint`() {
        assertEquals(SetupRowLook.DONE, look(SetupRow.ENABLE, 2))
        assertEquals(false, tappable(SetupRow.ENABLE, 2))
        assertEquals(SetupRowLook.CURRENT, look(SetupRow.SWITCH, 2))
        assertEquals(true, tappable(SetupRow.SWITCH, 2))
        assertEquals(SetupRowLook.LATER, look(SetupRow.THEME, 2))
        assertEquals(false, tappable(SetupRow.THEME, 2))
        assertEquals(SetupRowLook.LATER, look(SetupRow.FINISH, 2))
        assertEquals(false, tappable(SetupRow.FINISH, 2))
    }

    @Test fun `screen 3 has both steps done, the tiles open and Finished filled`() {
        assertEquals(SetupRowLook.DONE, look(SetupRow.ENABLE, 3))
        assertEquals(SetupRowLook.DONE, look(SetupRow.SWITCH, 3))
        assertEquals(false, tappable(SetupRow.SWITCH, 3))
        assertEquals(SetupRowLook.OPEN, look(SetupRow.THEME, 3))
        assertEquals(SetupRowLook.FILLED, look(SetupRow.FINISH, 3))
        assertEquals(true, tappable(SetupRow.FINISH, 3))
    }

    @Test fun `the rows are Enable, Switch, the tiles, Finished - no Configure row`() {
        assertEquals(listOf("ENABLE", "SWITCH", "THEME", "FINISH"), SetupRow.entries.map { it.name })
    }
}

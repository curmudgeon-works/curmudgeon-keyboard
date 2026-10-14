// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** The setup's one checklist (owner 2026-10-14): each row's look and whether it can be tapped, on each of the three
 *  screens (1 not enabled, 2 enabled but not in use, 3 in use). */
class SetupChecklistTest {
    private fun look(row: SetupRow, step: Int) = setupRowState(row, step).look
    private fun tappable(row: SetupRow, step: Int) = setupRowState(row, step).clickable

    @Test fun `screen 1 has only Enable to tap, everything after it faint`() {
        assertEquals(SetupRowLook.CURRENT, look(SetupRow.ENABLE, 1))
        assertEquals(true, tappable(SetupRow.ENABLE, 1))
        for (row in listOf(SetupRow.SWITCH, SetupRow.THEME, SetupRow.CONFIGURE, SetupRow.FINISH)) {
            assertEquals(SetupRowLook.LATER, look(row, 1), "$row")
            assertEquals(false, tappable(row, 1), "$row")
        }
    }

    @Test fun `screen 2 has Enable done, Switch to take, Configure faint but tappable, Finished faint`() {
        assertEquals(SetupRowLook.DONE, look(SetupRow.ENABLE, 2))
        assertEquals(false, tappable(SetupRow.ENABLE, 2))
        assertEquals(SetupRowLook.CURRENT, look(SetupRow.SWITCH, 2))
        assertEquals(true, tappable(SetupRow.SWITCH, 2))
        assertEquals(SetupRowLook.LATER, look(SetupRow.THEME, 2))
        assertEquals(false, tappable(SetupRow.THEME, 2))
        assertEquals(SetupRowLook.LATER, look(SetupRow.CONFIGURE, 2))
        assertEquals(true, tappable(SetupRow.CONFIGURE, 2))
        assertEquals(SetupRowLook.LATER, look(SetupRow.FINISH, 2))
        assertEquals(false, tappable(SetupRow.FINISH, 2))
    }

    @Test fun `screen 3 has both steps done, Configure open and Finished filled`() {
        assertEquals(SetupRowLook.DONE, look(SetupRow.ENABLE, 3))
        assertEquals(SetupRowLook.DONE, look(SetupRow.SWITCH, 3))
        assertEquals(false, tappable(SetupRow.SWITCH, 3))
        assertEquals(SetupRowLook.OPEN, look(SetupRow.THEME, 3))
        assertEquals(SetupRowLook.OPEN, look(SetupRow.CONFIGURE, 3))
        assertEquals(true, tappable(SetupRow.CONFIGURE, 3))
        assertEquals(SetupRowLook.FILLED, look(SetupRow.FINISH, 3))
        assertEquals(true, tappable(SetupRow.FINISH, 3))
    }
}

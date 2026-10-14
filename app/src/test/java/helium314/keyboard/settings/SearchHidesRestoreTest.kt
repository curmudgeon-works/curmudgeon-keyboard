// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Owner 2026-10-13, 20261013_M2 (round-5 review C1): Backup and restore and Factory reset are not in settings search.
 * Fail before: search found them, and a restore or reset started there left an edit screen's open Save/Discard draft
 * behind, whose Discard (or leaving the app) put the pre-restore snapshot back. Through the search the screen runs
 * ([SettingsContainer.filter]).
 */
@RunWith(RobolectricTestRunner::class)
class SearchHidesRestoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val hidden = setOf(SettingsWithoutKey.BACKUP_RESTORE, SettingsWithoutKey.FACTORY_RESET)

    @Test fun `searching backup, restore or reset finds neither Backup and restore nor Factory reset`() {
        val container = SettingsContainer(ctx)
        // (both are settings: App settings shows them)
        hidden.forEach { assertTrue(container[it] != null, "$it is a setting") }
        for (term in listOf("backup", "restore", "reset", "factory", ctx.getString(helium314.keyboard.latin.R.string.backup_restore_title),
                ctx.getString(helium314.keyboard.latin.R.string.factory_reset))) {
            val found = container.filter(term).map { it.key }
            assertTrue(found.none { it in hidden }, "\"$term\" found $found")
        }
    }
}

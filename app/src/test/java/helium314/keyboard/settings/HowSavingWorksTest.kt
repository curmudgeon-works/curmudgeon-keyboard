// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.settings.screens.savingGuide
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** App settings > How saving works (owner 2026-10-11): the tabs asked for, App update included, each with rows. */
@RunWith(RobolectricTestRunner::class)
class HowSavingWorksTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the tabs are the actions asked for, in order, each with its rows`() {
        assertEquals(listOf("Choose", "Edit", "Save as new", "Rename", "Delete", "Default", "Add / Import", "Export / Share",
            "Backup", "Partial restore", "Full restore", "App update", "Factory reset", "Separate settings on/off"),
            savingGuide.map { ctx.getString(it.title) })
        for (tab in savingGuide) {
            assertTrue(tab.rows.isNotEmpty(), ctx.getString(tab.title))
            for (row in tab.rows) assertTrue(ctx.getString(row.text).length > 10, ctx.getString(row.kind))
        }
    }
}

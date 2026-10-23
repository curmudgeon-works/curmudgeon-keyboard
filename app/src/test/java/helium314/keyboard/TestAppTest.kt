// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The start-up swipe list build is switched off by the tests' own app, not by a check in the app (2026-10-18). */
@RunWith(RobolectricTestRunner::class)
class TestAppTest {
    @Test fun `the tests run the test app, which builds no swipe lists at the start`() {
        val app = assertIs<TestApp>(ApplicationProvider.getApplicationContext())
        assertFalse(app.buildsSwipeListsAtStart)
    }

    @Test fun `the real app builds them`() = assertTrue(App().buildsSwipeListsAtStart)
}

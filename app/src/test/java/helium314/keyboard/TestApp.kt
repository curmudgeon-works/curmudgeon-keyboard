// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.App

/** The app every unit test starts (robolectric.properties): the real one, starting with no settings loaded and without building the swipe lists at the
 *  start (a build running on would write into the next tests' dictionary folders). */
class TestApp : App() {
    override val buildsSwipeListsAtStart get() = false

    // each test starts with no settings loaded, as a new process would (the Settings singleton outlives the test)
    override fun onCreate() {
        helium314.keyboard.latin.settings.Settings.getInstance().forgetLoadedValues()
        super.onCreate()
    }
}

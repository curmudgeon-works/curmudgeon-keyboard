// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.App

/** The app every unit test starts (robolectric.properties): the real one, without building the swipe lists at the
 *  start (a build running on would write into the next tests' dictionary folders). */
class TestApp : App() {
    override val buildsSwipeListsAtStart get() = false
}

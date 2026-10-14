// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

/**
 * Deleting a word that Android's personal dictionary has: whether it goes from there too (owner 2026-10-12
 * "DM22 STRIP REMOVE"). The personal dictionary is the device's, shared with other keyboards and apps, so:
 * - the strip's Remove never touches it and asks nothing: the word is blocked on this keyboard only, Android's copy
 *   stays (owner 2026-10-12 "don't need option to delete from personal dict when trying to remove a word")
 * - the settings' delete (Learned words list, single and batch) has "Also delete them from your device's personal
 *   dictionary" TICKED, shown only when a selected word is in it (unticked: those stay in Android's dictionary, and so
 *   on the list)
 */
object PersonalDictionaryChoice {
    /** The settings' checkbox, ticked at first. */
    const val SETTINGS_DEFAULT = true

    /** Whether Android's personal dictionary loses the word: as the box says when it was [asked], else yes (a word it
     *  doesn't have loses nothing there). */
    fun deletesFromPersonal(asked: Boolean, ticked: Boolean): Boolean = !asked || ticked
}

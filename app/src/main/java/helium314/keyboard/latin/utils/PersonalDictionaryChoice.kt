// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

/**
 * Removing or deleting a word that Android's personal dictionary has: whether it goes from there too (owner 2026-10-12
 * "DM22 STRIP REMOVE"). The personal dictionary is the device's, shared with other keyboards and apps, so:
 * - the strip's Remove asks only for a word in it, with "Also delete it from your device's personal dictionary"
 *   UNTICKED (unticked: blocked on this keyboard only, Android's copy stays; ticked: deleted device-wide, as before);
 *   a word not in it is removed at once, no question, as before
 * - the settings' delete (Learned words list, single and batch) has "Also delete them from your device's personal
 *   dictionary" TICKED, shown only when a selected word is in it (unticked: those stay in Android's dictionary, and so
 *   on the list)
 */
object PersonalDictionaryChoice {
    /** The strip's checkbox, unticked at first. */
    const val STRIP_DEFAULT = false

    /** The settings' checkbox, ticked at first. */
    const val SETTINGS_DEFAULT = true

    /** The strip's Remove asks (a second card with the checkbox) only for a word in Android's personal dictionary. */
    fun stripAsks(inPersonalDictionary: Boolean): Boolean = inPersonalDictionary

    /** Whether Android's personal dictionary loses the word: as the box says when it was [asked], else as before (yes:
     *  a word it doesn't have loses nothing there). */
    fun deletesFromPersonal(asked: Boolean, ticked: Boolean): Boolean = !asked || ticked
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

/**
 * "Refine suggestions & learning" (2026-10-06): not shown in the public build until it's polished. Its settings keep
 * working at their values (defaults: trust words typed 3 times, the default order of suggestions, logging off); the
 * number of suggestions is on Text correction instead. The private build turns the menu on here.
 */
const val REFINE_MENU_SHOWN = false

/** Settings no screen shows, so search mustn't find them either (re-review 2026-10-07): Refine's while the menu isn't
 *  shown (but the number of suggestions, on Text correction then), and the retired "space enters the middle suggestion"
 *  switch (its Setting stays so the key is known; SettingsValues keeps it off). */
fun hiddenFromSearch(key: String): Boolean =
    key == helium314.keyboard.latin.settings.Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER
        || (!REFINE_MENU_SHOWN && key != helium314.keyboard.latin.settings.Settings.PREF_SUGGESTION_COUNT
            && helium314.keyboard.latin.settings.KeyboardProfiles.Group.REFINE.contains(key))

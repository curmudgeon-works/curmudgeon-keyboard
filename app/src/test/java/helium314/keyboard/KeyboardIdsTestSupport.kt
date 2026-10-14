// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.KeyboardIds
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import org.json.JSONObject

// Test helpers for the keyboards' ids (owner 2026-10-11: "do IDs now"). A test keyboard's id is "k<n>" (the form
// KeyboardIds.isId accepts; a real one is "k" and 10 random characters); its settings are "pk<n>/<key>".

/** Test id n: "k<n>". */
fun kid(n: Int) = "k$n"

/** This definition as the user's keyboard with id "k<n>" (sorted, as stored). */
fun SettingsSubtype.withId(n: Int): SettingsSubtype = withKeyboardId(kid(n)).toPref().toSettingsSubtype()

/** [key] in the set of keyboard "k<n>": "pk<n>/<key>". */
fun idKey(n: Int, key: String) = KeyboardProfiles.ownKey(kid(n), key)

/** [keyboards] (with their ids) stored as the phone's: in the list and in the added list, [selected] in use; then the
 *  list loaded again ([reload]). */
fun storeKeyboards(ctx: Context, real: SharedPreferences, keyboards: List<SettingsSubtype>,
                   selected: SettingsSubtype? = keyboards.firstOrNull(), reload: Boolean = true) {
    val e = real.edit().putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards))
        .putString(Settings.PREF_ADDITIONAL_SUBTYPES, SubtypeSettings.createPrefSubtypes(keyboards))
    if (selected != null) e.putString(Settings.PREF_SELECTED_SUBTYPE, selected.toPref())
    e.commit()
    if (reload) SubtypeSettings.reloadEnabledSubtypes(ctx)
}

/** The pools map as stored, keyboard id -> pool, of [pools] (keyboards with their ids). */
fun poolsJson(pools: Map<SettingsSubtype, Int>): String = JSONObject(pools.mapKeys { it.key.keyboardId!! } as Map<*, *>).toString()

/** New ids "k<from>", "k<from+1>", ... in order instead of random ones (call [randomIds] after the test). */
fun countingIds(from: Int = 1000) { var n = from; KeyboardIds.source = { "k${n++}" } }

/** New ids random again, as in the app. */
fun randomIds() { KeyboardIds.source = KeyboardIds::random }

/** The stored keyboard list. */
fun storedEnabled(real: SharedPreferences): List<SettingsSubtype> =
    SubtypeSettings.createSettingsSubtypes(real.getString(Settings.PREF_ENABLED_SUBTYPES, "")!!)

/** The stored keyboard list's keyboard with id [id] (as it is now), or null. */
fun listedById(real: SharedPreferences, id: String): SettingsSubtype? = storedEnabled(real).firstOrNull { it.keyboardId == id }

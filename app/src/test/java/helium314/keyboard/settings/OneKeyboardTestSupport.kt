// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.storeKeyboards
import helium314.keyboard.withId
import java.util.Locale

/** One keyboard stored and in use (English US, id 1): a keyboard's settings need a keyboard to land in (with nothing
 *  stored, a write through ctx.prefs() has no set to go to and reads back as the default). */
fun storeOneKeyboard(ctx: Context, real: SharedPreferences) {
    val kb = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first().toSettingsSubtype().withId(1)
    storeKeyboards(ctx, real, listOf(kb), selected = kb, reload = false)
    KeyboardProfiles.enable(real)
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    KeyboardProfiles.refreshImeId(real)
}

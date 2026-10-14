// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.DefaultChanges
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.LayoutPresets
import helium314.keyboard.settings.SettingDefaults
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The defaults saved items capture (a Layout, theme or popup set stores "default" for them) as they were when
 * src/test/resources/saved-item-defaults.json was written. A default that changed needs a DefaultChanges row with the
 * old value, so saved items keep it; then the file gets the new value. The current values are written to
 * build/saved-item-defaults.json on every run: copy it over the checked-in one once the rows are in.
 */
@RunWith(RobolectricTestRunner::class)
class SavedItemDefaultsGuardTest {
    private fun typed(value: Any) = "${value::class.simpleName}:$value"

    private fun captured(): Map<String, String> = SettingDefaults.all
        .filterKeys { LayoutPresets.inScope(it) || AppearanceLooks.inScope(it)
            || it == Settings.PREF_SYMBOL_POPUP_MAP || it == Settings.PREF_POPUP_KEYS_ORDER }
        .mapValues { typed(it.value) }.toSortedMap()

    @Test fun `a changed default saved items capture has a DefaultChanges row`() {
        val now = captured()
        File("build/saved-item-defaults.json").writeText(JSONObject(now as Map<*, *>).toString(1) + "\n")
        val file = File("src/test/resources/saved-item-defaults.json")
        if (!file.isFile) fail("no ${file.path}: copy build/saved-item-defaults.json there")
        val snapshot = JSONObject(file.readText())
        val problems = ArrayList<String>()
        for ((key, value) in now) {
            if (!snapshot.has(key)) { problems += "$key is new: add it to the file ($value)"; continue }
            val was = snapshot.getString(key)
            if (was != value && DefaultChanges.rows.none { it.key == key && typed(it.old) == was })
                problems += "$key changed from $was to $value: add a DefaultChanges row (old = $was), then update the file"
        }
        for (key in snapshot.keys()) if (key !in now) problems += "$key is no longer captured: remove it from the file"
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}

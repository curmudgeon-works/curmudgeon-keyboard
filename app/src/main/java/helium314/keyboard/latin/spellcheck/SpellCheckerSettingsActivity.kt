// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.spellcheck

import android.app.Activity
import android.os.Bundle
import helium314.keyboard.settings.SettingsActivity

class SpellCheckerSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // one settings window, as from the settings key: an older one is closed (it landed on top of it, and Back walked
        // through the older window's screens)
        val intent = SettingsActivity.openFromOutside(this)
        intent.putExtra("spellchecker", true)
        startActivity(intent)
        if (!isFinishing) {
            finish()
        }
    }
}

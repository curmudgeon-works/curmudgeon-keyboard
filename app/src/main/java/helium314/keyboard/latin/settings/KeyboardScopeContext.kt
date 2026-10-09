// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.ContextWrapper

/**
 * The context of a settings screen that edits one keyboard's settings: set [keyboardId] (its profile id, see
 * [KeyboardProfiles]). Each such screen gets it from its own navigation entry, so every screen composed at once (two
 * during a slide) and work started from one (a dialog's coroutine) edit their own keyboard. Before 2026-10-09 one global
 * "keyboard being edited" was set by whichever screen drew last, and a Back or a tap during a slide edited the wrong one.
 * [helium314.keyboard.latin.utils.getActivity] looks through it.
 */
class KeyboardScopeContext(base: Context, val keyboardId: Int) : ContextWrapper(base) {
    companion object {
        /** The keyboard set the screen of [context] edits, or null where no keyboard's screen is (the main screen,
         *  App settings, the keyboard itself). */
        @JvmStatic
        fun idOf(context: Context?): Int? {
            var c = context
            while (c is ContextWrapper) {
                if (c is KeyboardScopeContext) return c.keyboardId
                c = c.baseContext
            }
            return null
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import java.util.Locale

/** Reaches MainKeyboardView's package-private helpers from tests in other packages. */
object MainKeyboardViewAccess {
    fun withoutDuplicateLanguages(locales: List<Locale>, mainName: String): List<Locale> =
        MainKeyboardView.withoutDuplicateLanguages(locales, mainName)
    fun shortLanguageName(locale: Locale): String = MainKeyboardView.shortLanguageName(locale, Locale.ENGLISH)
}

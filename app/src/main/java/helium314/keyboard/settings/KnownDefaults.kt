// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.util.concurrent.ConcurrentHashMap

/**
 * The defaults of the settings rows drawn so far (each row notes its own when it appears), for telling a change
 * from a change back: a switch turned on and off again stores "off" where nothing was stored, and nothing stored
 * means the default. Used by the Keep / Discard drafts; a key whose default isn't known counts as changed.
 */
object KnownDefaults {
    private val defaults = ConcurrentHashMap<String, Any>()

    fun note(key: String, default: Any?) { if (default != null) defaults[key] = default }

    /** The default noted for [key], or null. */
    fun of(key: String): Any? = defaults[key]

    /** Whether two stored values of [key] mean the same (null = not stored = the default). */
    fun same(key: String, a: Any?, b: Any?): Boolean {
        if (a == b) return true
        if (a != null && b != null) return equalNumbers(a, b)
        val d = defaults[key] ?: return false
        return equalNumbers(a ?: d, b ?: d)
    }

    private fun equalNumbers(a: Any, b: Any): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
}

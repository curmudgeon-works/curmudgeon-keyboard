// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.SharedPreferences
import java.util.regex.Pattern

/**
 * What a space learns and what a tap in the strip learns (Fine-tune learning, 2026-10-10): every committed text is one
 * [Kind], and each kind has a switch for each way of committing. "Space" is a space, punctuation or an edit; "strip" a
 * word tapped in the suggestion strip (or a next-word prediction). The defaults are the fixed rule of 2026-10-04: a
 * space learns words (with ' - . between letters) and email addresses, the strip learns anything.
 */
object LearnableText {
    enum class Kind(val key: String, val bySpace: Boolean, val byStrip: Boolean) {
        WORD("word", true, true), // don't, well-known, 'til, students'
        DOTTED("dotted", true, true), // google.com, hello.world
        EMAIL("email", true, true),
        NUMBER("number", false, true), // 2026, 3.14, 10:30
        WITH_DIGITS("digits", false, true), // mp3, b2b, 4u
        OTHER("other", false, true); // #tag, c++, a_b, anything else

        val spaceKey get() = "learn_space_$key"
        val stripKey get() = "learn_strip_$key"
    }

    /** Every switch's key (the Fine-tune learning group's). */
    val keys: List<String> = Kind.entries.flatMap { listOf(it.spaceKey, it.stripKey) }

    fun default(key: String): Boolean? = Kind.entries.firstNotNullOfOrNull {
        when (key) { it.spaceKey -> it.bySpace; it.stripKey -> it.byStrip; else -> null }
    }

    // letters, an apostrophe or hyphen between them, an apostrophe at either end
    private val WORD = Pattern.compile("['\u2019]?[\\p{L}\\p{M}]+(?:['\u2019\\-][\\p{L}\\p{M}]+)*['\u2019]?")
    // the same with periods between letters too (the rest of the 2026-10-04 rule's word part)
    private val DOTTED = Pattern.compile("['\u2019]?[\\p{L}\\p{M}]+(?:['\u2019.\\-][\\p{L}\\p{M}]+)*['\u2019]?")
    private val EMAIL = Pattern.compile("[\\p{L}\\p{M}0-9._\\-]+@[\\p{L}\\p{M}0-9._\\-]+")
    // digits, with . , : / - ' between them, no letters
    private val NUMBER = Pattern.compile("\\p{Nd}+(?:[.,:/'\u2019\\-]\\p{Nd}+)*")
    // letters and digits (both), with ' - . between them
    private val WITH_DIGITS = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}]+(?:['\u2019.\\-][\\p{L}\\p{M}\\p{Nd}]+)*")

    fun kindOf(text: String): Kind = when {
        WORD.matcher(text).matches() -> Kind.WORD
        DOTTED.matcher(text).matches() -> Kind.DOTTED
        EMAIL.matcher(text).matches() -> Kind.EMAIL
        NUMBER.matcher(text).matches() -> Kind.NUMBER
        WITH_DIGITS.matcher(text).matches() -> Kind.WITH_DIGITS
        else -> Kind.OTHER
    }

    /** The kinds switched on for a space ([byStrip] false) or the strip, as a bit mask over [Kind.ordinal]. */
    fun mask(prefs: SharedPreferences, byStrip: Boolean): Int {
        var mask = 0
        for (kind in Kind.entries) {
            val on = if (byStrip) prefs.getBoolean(kind.stripKey, kind.byStrip) else prefs.getBoolean(kind.spaceKey, kind.bySpace)
            if (on) mask = mask or (1 shl kind.ordinal)
        }
        return mask
    }

    @JvmStatic
    fun learns(mask: Int, text: String): Boolean = mask and (1 shl kindOf(text).ordinal) != 0
}

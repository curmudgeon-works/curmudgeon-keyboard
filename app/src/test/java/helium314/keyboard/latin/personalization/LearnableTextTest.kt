// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import helium314.keyboard.latin.personalization.LearnableText.Kind
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a space and a strip tap learn (Fine-tune learning, 2026-10-11). */
class LearnableTextTest {
    // the fixed rule it replaces (InputLogic.LEARNED_BY_SPACE, 2026-10-04 to 2026-10-11)
    private val oldRule = Pattern.compile(
        "['’]?[\\p{L}\\p{M}]+(?:['’.\\-][\\p{L}\\p{M}]+)*['’]?"
            + "|[\\p{L}\\p{M}0-9._\\-]+@[\\p{L}\\p{M}0-9._\\-]+")

    private val samples = listOf("hello", "Hello", "don't", "don’t", "'til", "students'", "well-known", "hello.world",
        "google.com", "a.b.c", "sender.first.last@gmail.com", "first_last@x.co.uk", "user2024@gmail.com", "a@b",
        "user2024", "ha0py", "3stimate", "mp3", "b2b", "4u", "2026", "3.14", "10:30", "1,000", "2026-10-11",
        "user:pass@host.com", "sender+tag@gmail.com", "#tag", "c++", "a_b", "@name", "http://x.com", "hello.", ".com",
        "-ish", "well--known", "naïve", "café", "नमस्ते", "किताब-घर", "Straße", "日本語", "x", "'", "", "a b", "a b",
        "ok!", "?", "e.g.", "U.S.A", "1st", "2nd", "π", "🙂", "a🙂")

    private fun defaultMask(byStrip: Boolean) = Kind.entries.fold(0) { m, k ->
        if (if (byStrip) k.byStrip else k.bySpace) m or (1 shl k.ordinal) else m }

    @Test fun `with the default boxes a space learns exactly what the old rule learned`() {
        for (text in samples)
            assertEquals(oldRule.matcher(text).matches(), LearnableText.learns(defaultMask(false), text), "\"$text\"")
    }

    @Test fun `with the default boxes a strip tap learns anything`() {
        for (text in samples) assertEquals(true, LearnableText.learns(defaultMask(true), text), "\"$text\"")
    }

    @Test fun `each example on the screen is its own kind`() {
        val examples = mapOf(
            Kind.WORD to listOf("don't", "well-known"), Kind.DOTTED to listOf("google.com", "hello.world"),
            Kind.EMAIL to listOf("name@mail.com"), Kind.NUMBER to listOf("2026", "3.14", "10:30"),
            Kind.WITH_DIGITS to listOf("mp3", "b2b", "4u"), Kind.OTHER to listOf("#tag", "c++", "a_b"))
        for ((kind, texts) in examples) for (text in texts) assertEquals(kind, LearnableText.kindOf(text), text)
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.text.font.FontFamily
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.settings.Settings

object KeyboardTypeface {
    private val lock = Any()

    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontFamily: FontFamily? = null
    @Volatile
    private var customTypefaceLoaded = false

    private var cachedEmojiTypeface: Typeface? = null
    @Volatile
    private var emojiTypefaceLoaded = false

    private var cachedSuggestionTypeface: Typeface? = null
    @Volatile
    private var suggestionTypefaceLoaded = false

    /** The font the user set for the suggestion strip, or null. */
    @JvmStatic
    fun suggestionTypeface(): Typeface? {
        if (suggestionTypefaceLoaded) return cachedSuggestionTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!suggestionTypefaceLoaded) {
                cachedSuggestionTypeface = runCatching { Typeface.createFromFile(Settings.getCustomSuggestionFontFile(context)) }.getOrNull()
                suggestionTypefaceLoaded = true
            }
            return cachedSuggestionTypeface
        }
    }

    private var cachedHintTypeface: Typeface? = null
    @Volatile
    private var hintTypefaceLoaded = false

    /** The font the user set for the symbols on the keys, or null for the default (bold). */
    @JvmStatic
    fun hintTypeface(): Typeface? {
        if (hintTypefaceLoaded) return cachedHintTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!hintTypefaceLoaded) {
                cachedHintTypeface = runCatching { Typeface.createFromFile(Settings.getCustomHintFontFile(context)) }.getOrNull()
                hintTypefaceLoaded = true
            }
            return cachedHintTypeface
        }
    }

    private fun loadCustomTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomFontFile(context))
        }.getOrNull()
    }

    private fun loadCustomEmojiTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomEmojiFontFile(context))
        }.getOrNull()
    }

    @JvmStatic
    fun customTypeface(): Typeface? {
        if (customTypefaceLoaded) return cachedCustomTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!customTypefaceLoaded) {
                cachedCustomTypeface = loadCustomTypeface(context)
                cachedCustomFontFamily = cachedCustomTypeface?.let(::FontFamily)
                customTypefaceLoaded = true
            }
            return cachedCustomTypeface
        }
    }

    @JvmStatic
    fun emojiTypeface(): Typeface? {
        if (emojiTypefaceLoaded) return cachedEmojiTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!emojiTypefaceLoaded) {
                cachedEmojiTypeface = loadCustomEmojiTypeface(context)
                emojiTypefaceLoaded = true
            }
            return cachedEmojiTypeface
        }
    }

    @JvmStatic
    fun customFontFamily(): FontFamily? {
        if (!customTypefaceLoaded) customTypeface()
        return cachedCustomFontFamily
    }

    @JvmStatic
    fun resolve(
        text: CharSequence?,
        defaultTypeface: Typeface = Typeface.DEFAULT,
    ): Typeface {
        val emojiTypeface = emojiTypeface()
        return if (emojiTypeface != null && text != null && isEmoji(text)) {
            emojiTypeface
        } else {
            customTypeface() ?: defaultTypeface
        }
    }

    @JvmStatic
    fun applyToTextView(textView: TextView) {
        applyToTextView(textView, textView.text, Typeface.DEFAULT)
    }

    @JvmStatic
    fun applyToTextView(textView: TextView, text: CharSequence?, defaultTypeface: Typeface) {
        textView.typeface = resolve(text, defaultTypeface = defaultTypeface)
    }

    /**
     * The typeface for a label under the user's text style: [font] as chosen in the text style dialog ("auto" = the
     * loaded file if any, else [base]), made bold / italic. Emojis keep the emoji font.
     */
    @JvmStatic
    fun styled(text: CharSequence?, base: Typeface, font: String, file: Typeface?, bold: Boolean, italic: Boolean): Typeface {
        val emoji = emojiTypeface()
        if (emoji != null && text != null && isEmoji(text)) return emoji
        val family = when (font) {
            "sans" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            "file" -> file ?: base
            "default" -> base
            else -> file ?: base
        }
        val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return Typeface.create(family, style)
    }

    @JvmStatic
    fun clearCache() {
        synchronized(lock) {
            cachedCustomTypeface = null
            cachedCustomFontFamily = null
            customTypefaceLoaded = false
            cachedEmojiTypeface = null
            emojiTypefaceLoaded = false
            cachedHintTypeface = null
            hintTypefaceLoaded = false
            cachedSuggestionTypeface = null
            suggestionTypefaceLoaded = false
        }
    }
}

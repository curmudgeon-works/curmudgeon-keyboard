// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.text.font.FontFamily
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

object KeyboardTypeface {
    private val lock = Any()

    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontFamily: FontFamily? = null
    @Volatile
    private var customTypefaceLoaded = false

    private var cachedEmojiTypeface: Typeface? = null
    @Volatile
    private var emojiTypefaceLoaded = false

    // the loaded font files by path (see FontLibrary)
    private val fileTypefaces = HashMap<String, Typeface?>()

    /** The typeface of the font file a text style chose ("font:<name>"), or null when it chose none. */
    @JvmStatic
    fun fileTypeface(value: String, slot: String): Typeface? {
        val context = Settings.getCurrentContext() ?: return null
        val file = FontLibrary.fileFor(context, value, slot) ?: return null
        synchronized(lock) {
            return fileTypefaces.getOrPut(file.path) { runCatching { Typeface.createFromFile(file) }.getOrNull() }
        }
    }

    /** A text style's font choice as a typeface without bold / italic; [base] for "default" (and a missing file). */
    @JvmStatic
    fun family(value: String, slot: String, base: Typeface): Typeface = when (value) {
        "sans" -> Typeface.SANS_SERIF
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        "default" -> base
        else -> fileTypeface(value, slot) ?: base
    }

    /** The key text's font ([base], the key style's typeface, when it has none). */
    @JvmStatic
    fun keyTextFamily(keyFont: String, base: Typeface): Typeface = family(keyFont, FontLibrary.SLOT_KEY, base)

    /** The symbols' or suggestions' font: the key text's while "use key text font" is on, else their own choice. */
    @JvmStatic
    fun ownOrKeyFamily(ownFont: String, slot: String, keyFont: String, followsKeyText: Boolean): Typeface =
        if (followsKeyText) keyTextFamily(keyFont, Typeface.DEFAULT) else family(ownFont, slot, Typeface.DEFAULT)

    private fun loadCustomEmojiTypeface(context: Context): Typeface? {
        return runCatching {
            // this keyboard's choice from the font list ("auto": the emoji font of before, if there was one)
            val choice = context.prefs().getString(Settings.PREF_EMOJI_FONT, "auto")!!
            Typeface.createFromFile(FontLibrary.fileFor(context, choice, FontLibrary.SLOT_EMOJI)!!)
        }.getOrNull()
    }

    /** The key text's font file, for what is drawn without a text style of its own (previews, popups, clipboard…). */
    @JvmStatic
    fun customTypeface(): Typeface? {
        if (customTypefaceLoaded) return cachedCustomTypeface
        val context = Settings.getCurrentContext() ?: return null
        val keyFont = context.prefs().getString(Settings.PREF_KEY_FONT, "auto")!!
        val typeface = fileTypeface(keyFont, FontLibrary.SLOT_KEY)
        synchronized(lock) {
            if (!customTypefaceLoaded) {
                cachedCustomTypeface = typeface
                cachedCustomFontFamily = typeface?.let(::FontFamily)
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
     * The typeface for a label under the user's text style: [family] made bold / italic. Emojis keep the emoji font.
     * [keepStyle]: the family's own bold / italic stays (the key style's bold while the Bold switch was never set).
     */
    @JvmStatic
    fun styled(text: CharSequence?, family: Typeface, keepStyle: Boolean, bold: Boolean, italic: Boolean): Typeface {
        val emoji = emojiTypeface()
        if (emoji != null && text != null && isEmoji(text)) return emoji
        val style = (if (keepStyle) family.style else 0) or (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return if (style == family.style) family else Typeface.create(family, style)
    }

    @JvmStatic
    fun clearCache() {
        synchronized(lock) {
            cachedCustomTypeface = null
            cachedCustomFontFamily = null
            customTypefaceLoaded = false
            cachedEmojiTypeface = null
            emojiTypefaceLoaded = false
            fileTypefaces.clear()
        }
        FontLibrary.recheck() // a restored backup can bring back the files of before
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translit

import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary

/**
 * A word in the other script (Suggest in …), from the table, for the Latin word [source] it was looked up for. A commit
 * of it learns [source] (the other script's word goes into no dictionary and no learned words). [inPlace]: offered
 * after [source] was picked from the strip, a tap puts it in place of that word in the text. Drawn on a tint when
 * [tinted] (KIND_FLAG_OTHER_SCRIPT). Not removable from the strip (long press): it is nobody's suggestion but the table's.
 */
class TranslitWordInfo(word: String, @JvmField val source: String, score: Int, tinted: Boolean, @JvmField val inPlace: Boolean = false) :
    SuggestedWordInfo(word, "", score,
        SuggestedWordInfo.KIND_TRANSLIT or (if (tinted) SuggestedWordInfo.KIND_FLAG_OTHER_SCRIPT else 0),
        Dictionary.DICTIONARY_HARDCODED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)

/**
 * The strip with Suggest in … (pipeline: what was typed or swiped -> the Latin word as always -> the table -> the other
 * script). The other script's words are found ONLY by looking the Latin word already chosen up in the table, in the
 * table's order; nothing of how it was typed or swiped (touch points, key or decoder scores) picks or ranks them.
 */
object SuggestInScript {
    /**
     * [words] (typed or swiped) with the other script's word of their top Latin word (the one space types: the
     * auto-correction, else the typed word; for a swipe the first), when the table has it:
     * - [Transliteration.SOME]: right after the top word, on a tint; space still types the Latin word.
     * - [Transliteration.ALL]: in the top word's place (space, or the swipe, puts it in), the Latin word right after it
     *   on a tint, the table's other words for it after that. Not when [inOtherLanguage] (a word of another Latin-script
     *   language of the keyboard, "meeting"), nor for typing with auto-correction off ([autoCorrects]: space types what
     *   was typed): then as with SOME.
     * Predictions, punctuation and anything else come back as they are.
     */
    @JvmStatic
    fun forStrip(words: SuggestedWords, mode: Int, table: TranslitTable, autoCorrects: Boolean,
                 inOtherLanguage: (String) -> Boolean): SuggestedWords {
        if (mode == Transliteration.OFF || words.isEmpty || words.isPunctuationSuggestions) return words
        val batch = words.mInputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH
            || words.mInputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH
        if (!batch && words.mInputStyle != SuggestedWords.INPUT_STYLE_TYPING) return words
        val topIndex = if (!batch && words.mWillAutoCorrect && words.size() > 1) SuggestedWords.INDEX_OF_AUTO_CORRECTION else 0
        val top = words.getInfo(topIndex)
        if (top is TranslitWordInfo || top.isKindOf(SuggestedWordInfo.KIND_WHOLE_RUN) || top.isEmoji) return words
        val scripts = table.lookup(top.mWord)
        if (scripts.isEmpty()) return words
        val infos = ArrayList<SuggestedWordInfo>(words.size() + scripts.size + 1)
        for (i in 0 until words.size()) infos.add(words.getInfo(i))
        if (mode == Transliteration.SOME || (!batch && !autoCorrects) || inOtherLanguage(top.mWord)) {
            infos.add(topIndex + 1, TranslitWordInfo(scripts[0], top.mWord, top.mScore, tinted = true))
            return copy(words, infos, words.mTypedWordInfo, words.mWillAutoCorrect)
        }
        val script = TranslitWordInfo(scripts[0], top.mWord, top.mScore, tinted = false)
        val others = scripts.drop(1).map { TranslitWordInfo(it, top.mWord, top.mScore, tinted = false) }
        if (batch) {
            // the swipe puts in the first word: the other script's; the strip's "typed word" too, which space commits
            infos[0] = script
            infos.add(1, tinted(top))
            infos.addAll(2, others)
            return copy(words, infos, script, false)
        }
        if (topIndex == SuggestedWords.INDEX_OF_AUTO_CORRECTION) {
            infos[topIndex] = script
            infos.add(topIndex + 1, tinted(top))
            infos.addAll(topIndex + 2, others)
        } else {
            // the typed word is the Latin word: it stays first (where the strip keeps the typed word), on the tint, and
            // the other script's word becomes the auto-correction
            infos[0] = tinted(top)
            infos.add(1, script)
            infos.addAll(2, others)
        }
        return copy(words, infos, words.mTypedWordInfo, true)
    }

    /**
     * After the Latin word [picked] was picked from the strip [strip] under [Transliteration.SOME]: its other script's
     * word, offered first on a tint until the next input (a tap puts it in place of [picked]). Null when the strip
     * offered it already (the top word's), or the table has none.
     */
    @JvmStatic
    fun afterPick(picked: String, strip: SuggestedWords, mode: Int, table: TranslitTable): TranslitWordInfo? {
        if (mode != Transliteration.SOME) return null
        for (i in 0 until strip.size()) {
            val info = strip.getInfo(i)
            if (info is TranslitWordInfo && info.source == picked) return null
        }
        val script = table.lookup(picked).firstOrNull() ?: return null
        return TranslitWordInfo(script, picked, SuggestedWordInfo.MAX_SCORE, tinted = true, inPlace = true)
    }

    /** [predictions] with [first] in front (the word offered after a pick, [afterPick]). */
    @JvmStatic
    fun withFirst(predictions: SuggestedWords, first: SuggestedWordInfo): SuggestedWords {
        val infos = ArrayList<SuggestedWordInfo>(predictions.size() + 1)
        infos.add(first)
        for (i in 0 until predictions.size()) infos.add(predictions.getInfo(i))
        return SuggestedWords(infos, predictions.mRawSuggestions, predictions.mTypedWordInfo, false, false,
            predictions.mIsObsoleteSuggestions, predictions.mInputStyle, predictions.mSequenceNumber)
    }

    /** The word to learn for a commit of [word] from the strip [strip]: the Latin word it was looked up for when it is
     *  the other script's word there; else [word]. */
    @JvmStatic
    fun learnedAs(word: String, strip: SuggestedWords): String {
        for (i in 0 until strip.size()) {
            val info = strip.getInfo(i)
            if (info is TranslitWordInfo && info.mWord == word) return info.source
        }
        val typed = strip.mTypedWordInfo
        return if (typed is TranslitWordInfo && typed.mWord == word) typed.source else word
    }

    /** [info] drawn on the tint (the Latin word one tap away under ALL). */
    private fun tinted(info: SuggestedWordInfo): SuggestedWordInfo =
        SuggestedWordInfo(info.mWord, info.mPrevWordsContext, info.mScore, info.mKindAndFlags or SuggestedWordInfo.KIND_FLAG_OTHER_SCRIPT,
            info.mSourceDict, info.mIndexOfTouchPointOfSecondWord, info.mAutoCommitFirstWordConfidence)

    private fun copy(words: SuggestedWords, infos: ArrayList<SuggestedWordInfo>, typed: SuggestedWordInfo?, willAutoCorrect: Boolean) =
        SuggestedWords(infos, words.mRawSuggestions, typed, words.mTypedWordValid, willAutoCorrect,
            words.mIsObsoleteSuggestions, words.mInputStyle, words.mSequenceNumber)
}

/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.personalization;

import android.content.Context;

import androidx.annotation.NonNull;

import com.android.inputmethod.latin.BinaryDictionary;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary;
import helium314.keyboard.latin.NgramContext;
import helium314.keyboard.latin.makedict.DictionaryHeader;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Locally gathers statistics about the words user types and various other signals like
 * auto-correction cancellation or manual picks. This allows the keyboard to adapt to the
 * typist over time.
 */
public class UserHistoryDictionary extends ExpandableBinaryDictionary {
    public static final String NAME = UserHistoryDictionary.class.getSimpleName();

    /**
     * One store of learned words: [dictName] is its file name without ".dict" (see {@link LearnedStores#storeName}),
     * [locale] stands for its script (see {@link LearnedStores#storeLocale}). Get them from {@link PersonalizationHelper}.
     */
    UserHistoryDictionary(final Context context, final String dictName, final Locale locale) {
        super(context, dictName, locale, Dictionary.TYPE_USER_HISTORY, null);
        reloadDictionaryIfRequired();
    }

    /** The learned words of [locale]'s script, see {@link PersonalizationHelper#getUserHistoryDictionary}. */
    public static UserHistoryDictionary getDictionary(final Context context, final Locale locale,
            final File dictFile, final String dictNamePrefix) {
        return PersonalizationHelper.getUserHistoryDictionary(context, locale);
    }

    /** The header a store of learned words is written with (also by {@link LearnedStoreIo} for a store built aside). */
    @NonNull
    static Map<String, String> headerAttributes(final String dictName, final Locale locale) {
        final Map<String, String> attributeMap = new HashMap<>();
        attributeMap.put(DictionaryHeader.DICTIONARY_ID_KEY, dictName);
        attributeMap.put(DictionaryHeader.DICTIONARY_LOCALE_KEY, locale.toString());
        attributeMap.put(DictionaryHeader.DICTIONARY_VERSION_KEY,
                String.valueOf(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())));
        attributeMap.put(DictionaryHeader.USES_FORGETTING_CURVE_KEY, DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        attributeMap.put(DictionaryHeader.HAS_HISTORICAL_INFO_KEY, DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        return attributeMap;
    }

    // the per-language stores of before are being merged into the per-script ones on this start: nothing is read until
    // that's done (the merge writes the files this store opens)
    @Override
    protected void awaitBeforeLoad() {
        LearnedStoreMigration.awaitDone();
    }

    /**
     * Add a word to the user history dictionary.
     *
     * @param userHistoryDictionary the user history dictionary
     * @param ngramContext the n-gram context
     * @param word the word the user inputted
     * @param isValid whether the word is valid or not
     * @param timestamp the timestamp when the word has been inputted
     */
    public static void addToDictionary(final ExpandableBinaryDictionary userHistoryDictionary,
            @NonNull final NgramContext ngramContext, final String word, final boolean isValid,
            final int timestamp) {
        if (word.length() > BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH) {
            return;
        }
        userHistoryDictionary.updateEntriesForWord(ngramContext, word,
                isValid, 1 /* count */, timestamp);
    }

    @Override
    protected Map<String, String> getHeaderAttributeMap() {
        final Map<String, String> attributeMap = super.getHeaderAttributeMap();
        attributeMap.put(DictionaryHeader.USES_FORGETTING_CURVE_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        attributeMap.put(DictionaryHeader.HAS_HISTORICAL_INFO_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        return attributeMap;
    }

    @Override
    protected void loadInitialContentsLocked() {
        // No initial contents.
    }

    @Override
    public boolean isValidWord(final String word) {
        // Strings out of this dictionary should not be considered existing words.
        return false;
    }
}

/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.Context;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.latin.common.ComposedData;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.DictionaryStats;
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion;
import helium314.keyboard.latin.utils.SuggestionResults;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Interface that facilitates interaction with different kinds of dictionaries. Provides APIs to
 * instantiate and select the correct dictionaries (based on language and settings), update entries
 * and fetch suggestions. Currently AndroidSpellCheckerService and LatinIME both use
 * DictionaryFacilitator as a client for interacting with dictionaries.
 */
public interface DictionaryFacilitator {

    /** A word the user removed (long-press Remove) and not back yet (RemovedWords.isRemoved): kept out of the
     *  suggestions, swiped ones too. */
    default boolean isRemovedWord(final String word) { return false; }

    /** A word the user typed often enough to trust ("Trust words you've typed N+ times"): auto-correct leaves it
     *  alone and the spell checker counts it as spelled right. */
    default boolean isTrustedWord(final String word) { return false; }

    /** A dictionary word the user removed that's back (typed again often enough): a real word again (ranked as a new
     *  learned word). */
    default boolean isRestoredWord(final String word) { return false; }

    /** A word removed 3 times, typed often enough since: it comes back when the user taps the "+" the strip shows. */
    default boolean awaitsConfirmation(final String word) { return false; }

    /** The strip's "+" was tapped for a word that {@link #awaitsConfirmation}: it's back. */
    default void confirmRemovedWord(final String word) { }

    String[] ALL_DICTIONARY_TYPES = new String[] {
            Dictionary.TYPE_MAIN,
            Dictionary.TYPE_CONTACTS,
            Dictionary.TYPE_APPS,
            Dictionary.TYPE_USER_HISTORY,
            Dictionary.TYPE_USER};

    String[] DYNAMIC_DICTIONARY_TYPES = new String[] {
            Dictionary.TYPE_CONTACTS,
            Dictionary.TYPE_APPS,
            Dictionary.TYPE_USER_HISTORY,
            Dictionary.TYPE_USER};

    /** The facilitator will put words into the cache whenever it decodes them. */
    void setValidSpellingWordReadCache(final LruCache<String, Boolean> cache);

    /** The facilitator will get words from the cache whenever it needs to check their spelling. */
    void setValidSpellingWordWriteCache(final LruCache<String, Boolean> cache);

    /**
     * Returns whether this facilitator is exactly for this locale.
     *
     * @param locale the locale to test against
     */
    boolean isForLocale(final Locale locale);

    interface DictionaryInitializationListener {
        void onUpdateMainDictionaryAvailability(boolean isMainDictionaryAvailable);
    }

    /**
     * Called every time {@link LatinIME} starts on a new text field.
     * <p>
     * WARNING: The service methods that call start/finish are very spammy.
     */
    void onStartInput();

    /**
     * Called every time the {@link LatinIME} finishes with the current text field.
     * May be followed by {@link #onStartInput} again in another text field,
     * or it may be done for a while.
     * <p>
     * WARNING: The service methods that call start/finish are very spammy.
     */
    void onFinishInput();

    /** whether a dictionary is set */
    boolean isActive();

    /** the locale provided in resetDictionaries */
    @NonNull Locale getMainLocale();

    /** Locales of all loaded dictionary groups, main locale first. */
    @NonNull List<Locale> getLocales();

    /** the most "trusted" locale, differs from getMainLocale only if multilingual typing is used */
    @NonNull Locale getCurrentLocale();

    boolean usesSameSettings(
            @NonNull final List<Locale> locales,
            final boolean contacts,
            final boolean apps,
            final boolean personalization
    );

    /** switches to newLocale, gets secondary locales from current settings, and sets secondary dictionaries */
    void resetDictionaries(
            final Context context,
            final Locale newLocale,
            final boolean useContactsDict,
            final boolean useAppsDict,
            final boolean usePersonalizedDicts,
            final boolean forceReloadMainDictionary,
            final String dictNamePrefix,
            @Nullable final DictionaryInitializationListener listener);

    /** removes the word from all editable dictionaries (Android's personal dictionary too, in any capitalization), and gives
     *  it one more strike on the blacklist (RemovedWords: the more strikes, the harder it comes back) */
    void removeWord(String word);

    void closeDictionaries();

    /** main dictionaries are loaded asynchronously after resetDictionaries */
    boolean hasAtLeastOneInitializedMainDictionary();

    /** main dictionaries are loaded asynchronously after resetDictionaries */
    boolean hasAtLeastOneUninitializedMainDictionary();

    /** main dictionaries are loaded asynchronously after resetDictionaries */
    void waitForLoadingMainDictionaries(final long timeout, final TimeUnit unit)
            throws InterruptedException;

    /** adds the word to user history dictionary, calls adjustConfindences, and might add it to personal dictionary if the setting is enabled */
    void addToUserHistory(final String suggestion, final boolean wasAutoCapitalized,
            @NonNull final NgramContext ngramContext, final long timeStampInSeconds,
            final boolean blockPotentiallyOffensive);

    /** As {@link #addToUserHistory}, and the word counts as used [extraUses] more times (a suggestion the user picked). */
    default void addToUserHistory(final String suggestion, final boolean wasAutoCapitalized,
            @NonNull final NgramContext ngramContext, final long timeStampInSeconds,
            final boolean blockPotentiallyOffensive, final int extraUses) {
        addToUserHistory(suggestion, wasAutoCapitalized, ngramContext, timeStampInSeconds, blockPotentiallyOffensive);
    }

    /** adjust confidences for multilingual typing */
    void adjustConfidences(final String word, final boolean wasAutoCapitalized);

    /** a string with all used locales and their current confidences, null if multilingual typing is not used */
    @Nullable String localesAndConfidences();

    /** A reverted auto-correction takes one use back from the word (see {@link #unlearnOneUse}); deleting words
     *  (EVENT_BACKSPACE) and a deleted fresh swipe (EVENT_REJECTION, never counted) take nothing. */
    void unlearnFromUserHistory(final String word,
            @NonNull final NgramContext ngramContext, final long timeStampInSeconds,
            final int eventType);

    /** Takes one counted use of [word] back from the learned words (an auto-correction reverted, an accepted word
     *  edited later): its count goes down by one, never below 0, and the word stays stored. */
    default void unlearnOneUse(final String word) {}

    /** How often [word] was learned in the language in use (as written, else lowercase), -1 if it isn't stored. */
    default int getLearnedCount(final String word) {
        return -1;
    }

    @NonNull SuggestionResults getSuggestionResults(final ComposedData composedData,
            final NgramContext ngramContext, @NonNull final Keyboard keyboard,
            final SettingsValuesForSuggestion settingsValuesForSuggestion, final int sessionId,
            final int inputStyle);

    boolean isValidSpellingWord(final String word);

    boolean isValidSuggestionWord(final String word);

    /** How often [word] occurs in the main dictionary of [locale] (one of {@link #getLocales()}); negative if it isn't in it. */
    int getMainDictionaryFrequency(final String word, final Locale locale);

    void clearUserHistoryDictionary(final Context context);

    String dump(final Context context);

    void dumpDictionaryForDebug(final String dictName);

    @NonNull List<DictionaryStats> getDictionaryStats(final Context context);
}

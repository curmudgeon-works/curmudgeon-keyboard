/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.personalization;

import android.content.Context;
import helium314.keyboard.latin.utils.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.latin.common.FileUtils;
import helium314.keyboard.latin.utils.ScriptUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.ref.SoftReference;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helps handle and manage personalized dictionaries such as {@link UserHistoryDictionary}.
 */
public class PersonalizationHelper {
    private static final String TAG = PersonalizationHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final ConcurrentHashMap<String, SoftReference<UserHistoryDictionary>>
            sLangUserHistoryDictCache = new ConcurrentHashMap<>();

    /** The learned words of [locale]'s script in the pool in use (see {@link LearnedStores}): every language of a
     *  script shares one store. */
    @NonNull
    public static UserHistoryDictionary getUserHistoryDictionary(final Context context, final Locale locale) {
        return getUserHistoryDictionary(context, ScriptUtils.script(locale), LearnedStores.INSTANCE.getCurrentPool());
    }

    /** The learned words of [script] in [pool]: one object per store for the whole process (keyboard, spell checker,
     *  settings), so what one learns or removes the others see at once. */
    @NonNull
    public static UserHistoryDictionary getUserHistoryDictionary(final Context context, final String script, final int pool) {
        final String lookupStr = LearnedStores.storeName(script, pool);
        synchronized (sLangUserHistoryDictCache) {
            final UserHistoryDictionary cached = getCached(lookupStr);
            if (cached != null) {
                if (DEBUG) {
                    Log.d(TAG, "Use cached UserHistoryDictionary with lookup: " + lookupStr);
                }
                cached.reloadDictionaryIfRequired();
                return cached;
            }
            final UserHistoryDictionary dict = new UserHistoryDictionary(context, lookupStr, LearnedStores.storeLocale(script));
            sLangUserHistoryDictCache.put(lookupStr, new SoftReference<>(dict));
            return dict;
        }
    }

    /** The store of [script] in [pool] if something holds it already, else null (nothing is created or read). */
    @Nullable
    public static UserHistoryDictionary getCachedUserHistoryDictionary(final String script, final int pool) {
        synchronized (sLangUserHistoryDictCache) {
            return getCached(LearnedStores.storeName(script, pool));
        }
    }

    @Nullable
    private static UserHistoryDictionary getCached(final String lookupStr) {
        final SoftReference<UserHistoryDictionary> ref = sLangUserHistoryDictCache.get(lookupStr);
        return ref == null ? null : ref.get();
    }

    /** The files changed underneath (a restore of everything): every store reads its file again on its next use;
     *  what was only in memory is dropped, the files are what counts now. */
    public static void reloadAllFromFiles() {
        synchronized (sLangUserHistoryDictCache) {
            for (final SoftReference<UserHistoryDictionary> ref : sLangUserHistoryDictCache.values()) {
                final UserHistoryDictionary dict = ref == null ? null : ref.get();
                if (dict != null) dict.closeAndWait();
            }
        }
    }

    public static void removeAllUserHistoryDictionaries(final Context context) {
        synchronized (sLangUserHistoryDictCache) {
            for (final ConcurrentHashMap.Entry<String, SoftReference<UserHistoryDictionary>> entry
                    : sLangUserHistoryDictCache.entrySet()) {
                if (entry.getValue() != null) {
                    final UserHistoryDictionary dict = entry.getValue().get();
                    if (dict != null) {
                        dict.clear();
                    }
                }
            }
            sLangUserHistoryDictCache.clear();
            final File filesDir = context.getFilesDir();
            if (filesDir == null) {
                Log.e(TAG, "context.getFilesDir() returned null.");
                return;
            }
            final boolean filesDeleted = FileUtils.deleteFilteredFiles(
                    filesDir, new DictFilter(UserHistoryDictionary.NAME));
            if (!filesDeleted) {
                Log.e(TAG, "Cannot remove dictionary files. filesDir: " + filesDir.getAbsolutePath()
                        + ", dictNamePrefix: " + UserHistoryDictionary.NAME);
            }
            // and the per-language stores of before, kept aside when they were merged per script: forgetting what
            // was learned means those too
            FileUtils.deleteRecursively(new File(filesDir, LearnedStoreMigration.PREMERGE_DIR));
        }
    }

    private static class DictFilter implements FilenameFilter {
        private final String mName;

        DictFilter(final String name) {
            mName = name;
        }

        @Override
        public boolean accept(final File dir, final String name) {
            return name.startsWith(mName);
        }
    }
}

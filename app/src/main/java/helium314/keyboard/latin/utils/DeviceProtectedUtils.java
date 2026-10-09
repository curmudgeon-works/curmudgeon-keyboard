/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import android.content.Context;
import android.content.SharedPreferences;

import helium314.keyboard.latin.settings.KeyboardProfiles;
import helium314.keyboard.latin.settings.KeyboardScopeContext;
import helium314.keyboard.latin.settings.ProfilePreferences;
import android.os.Build;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

public final class DeviceProtectedUtils {

    static final String TAG = DeviceProtectedUtils.class.getSimpleName();
    private static SharedPreferences prefs;
    // views on the same file: the keyboard in use (IME), a keyboard's settings screens (one per keyboard, see
    // KeyboardScopeContext), and the other settings screens (the main choice, worked out on every read)
    private static SharedPreferences imePrefs;
    private static SharedPreferences mainPrefs;
    private static final ConcurrentHashMap<Integer, SharedPreferences> keyboardPrefs = new ConcurrentHashMap<>();

    public static SharedPreferences getSharedPreferences(final Context context) {
        final SharedPreferences real = getRealSharedPreferences(context);
        final Integer keyboardId = KeyboardScopeContext.idOf(context);
        if (keyboardId != null) {
            final int id = keyboardId;
            final SharedPreferences cached = keyboardPrefs.get(id);
            if (cached != null) return cached;
            final SharedPreferences made = new ProfilePreferences(real, () -> KeyboardProfiles.INSTANCE.scopedId(real, id));
            final SharedPreferences raced = keyboardPrefs.putIfAbsent(id, made);
            return raced != null ? raced : made;
        }
        if (KtxKt.getActivity(context) != null) {
            if (mainPrefs == null) mainPrefs = new ProfilePreferences(real, () -> KeyboardProfiles.INSTANCE.mainEditingId(real));
            return mainPrefs;
        }
        if (imePrefs == null) imePrefs = new ProfilePreferences(real, KeyboardProfiles.INSTANCE::getImeId);
        return imePrefs;
    }

    /** The preferences file itself, all profiles included: for backup and the profile bookkeeping. */
    public static SharedPreferences getRealSharedPreferences(final Context context) {
        if (prefs != null)
            return prefs;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            prefs = getDefaultSharedPreferences(context);
            return prefs;
        }
        final Context deviceProtectedContext = getDeviceProtectedContext(context);
        prefs = getDefaultSharedPreferences(deviceProtectedContext);
        if (prefs.getAll() == null)
            return prefs; // happens for compose previews
        if (prefs.getAll().isEmpty()) {
            Log.i(TAG, "Device encrypted storage is empty, copying values from credential encrypted storage");
            deviceProtectedContext.moveSharedPreferencesFrom(context, android.preference.PreferenceManager.getDefaultSharedPreferencesName(context));
        }
        return prefs;
    }

    // keep this private to avoid accidental use of device protected context anywhere in the app
    private static Context getDeviceProtectedContext(final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return context;
        final Context ctx = context.isDeviceProtectedStorage() ? context : context.createDeviceProtectedStorageContext();
        if (ctx == null) return context; // happens for compose previews
        else return ctx;
    }

    private static SharedPreferences getDefaultSharedPreferences(Context context) {
        // from androidx.preference.PreferenceManager
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    public static File getFilesDir(final Context context) {
        return getDeviceProtectedContext(context).getFilesDir();
    }

    private DeviceProtectedUtils() {
        // This utility class is not publicly instantiable.
    }
}

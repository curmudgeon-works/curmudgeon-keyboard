/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import android.annotation.SuppressLint;
import android.app.Application;
import android.os.Build;
import android.text.TextUtils;

import helium314.keyboard.latin.App;
import helium314.keyboard.latin.BuildConfig;
import helium314.keyboard.latin.settings.Settings;

import java.io.File;

@SuppressLint("PrivateApi") // it's a fallback in try/catch
public final class JniUtils {
    private static final String TAG = JniUtils.class.getSimpleName();
    public static final String JNI_LIB_NAME = "jni_latinime";

    static {
        // our own dictionary and suggestion engine (upstream could also load Google's closed swipe library
        // instead; it can't ship, and swipes are decoded by the in-tree :gesture decoder)
        try {
            System.loadLibrary(JNI_LIB_NAME);
        } catch (UnsatisfiedLinkError ul) {
            Log.w(TAG, "Could not load native library " + JNI_LIB_NAME, ul);
        }
    }

    private JniUtils() {
        // This utility class is not publicly instantiable.
    }

    public static void loadNativeLibrary() {
        // Ensures the static initializer is called
    }
}

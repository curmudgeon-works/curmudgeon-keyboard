/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.res.TypedArray;

import helium314.keyboard.latin.R;
import helium314.keyboard.latin.settings.Settings;

/**
 * This class holds parameters to control how a gesture trail is drawn and animated on the screen.
 * <p>
 * On the other hand, {@link GestureStrokeDrawingParams} class controls how each gesture stroke is
 * sampled and interpolated. This class controls how those gesture strokes are displayed as a
 * gesture trail and animated on the screen.
 */
final class GestureTrailDrawingParams {
    private static final int FADEOUT_START_DELAY_FOR_DEBUG = 2000; // millisecond
    private static final int FADEOUT_DURATION_FOR_DEBUG = 200; // millisecond

    public final int mTrailColor;
    public float mTrailStartWidth; // the theme's widths times the thickness setting, see update()
    public float mTrailEndWidth;
    private final float mThemeStartWidth;
    private final float mThemeEndWidth;
    public boolean mFades = true;
    public final float mTrailBodyRatio;
    public final boolean mTrailShadowEnabled;
    public final float mTrailShadowRatio;
    public int mFadeoutStartDelay;
    private final int mThemeFadeoutStartDelay;
    public int mFadeoutDuration;
    public final int mUpdateInterval;

    public int mTrailLingerDuration;
    // the whole trail (not fading on the way) fades this fast once its time after the lift is up
    private static final int WHOLE_TRAIL_FADEOUT_DURATION = 150; // millisecond
    private static final int UNTIL_NEXT_TOUCH = 1_000_000_000; // millisecond, ~11 days

    public GestureTrailDrawingParams(final TypedArray mainKeyboardViewAttr) {
        // the colour picked for this keyboard (Appearance), else the theme's
        mTrailColor = helium314.keyboard.latin.utils.SuggestionColors.gestureTrailColor(
                Settings.getInstance().getPrefs(), Settings.getValues().mColors);
        mThemeStartWidth = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_gestureTrailStartWidth, 0.0f);
        mThemeEndWidth = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_gestureTrailEndWidth, 0.0f);
        final int PERCENTAGE_INT = 100;
        mTrailBodyRatio = (float)mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureTrailBodyRatio, PERCENTAGE_INT)
                / (float)PERCENTAGE_INT;
        final int trailShadowRatioInt = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureTrailShadowRatio, 0);
        mTrailShadowEnabled = (trailShadowRatioInt > 0);
        mTrailShadowRatio = (float)trailShadowRatioInt / (float)PERCENTAGE_INT;
        mThemeFadeoutStartDelay = GestureTrailDrawingPoints.DEBUG_SHOW_POINTS
                ? FADEOUT_START_DELAY_FOR_DEBUG
                : mainKeyboardViewAttr.getInt(
                        R.styleable.MainKeyboardView_gestureTrailFadeoutStartDelay, 0);
        mFadeoutDuration = GestureTrailDrawingPoints.DEBUG_SHOW_POINTS
                ? FADEOUT_DURATION_FOR_DEBUG
                : Settings.getValues().mGestureTrailFadeoutDuration;
        mFadeoutStartDelay = mThemeFadeoutStartDelay;
        mTrailLingerDuration = mFadeoutStartDelay + mFadeoutDuration;
        mUpdateInterval = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_gestureTrailUpdateInterval, 0);
        update();
    }

    /** The trail settings (thickness, fading, lifespan) as they are now: read before every frame, so a change in the
     *  settings shows on the keyboard already up. */
    public void update() {
        final helium314.keyboard.latin.settings.SettingsValues sv = Settings.getValues();
        final float scale = sv.mGestureTrailThickness / 100f;
        mTrailStartWidth = mThemeStartWidth * scale;
        mTrailEndWidth = mThemeEndWidth * scale;
        mFades = !sv.mGestureTrailWhole;
        if (!GestureTrailDrawingPoints.DEBUG_SHOW_POINTS) {
            // fading: each point after the theme's delay, over the lifespan; whole: all of it, the set time after the lift
            // (a negative time: until the next touch only, practically forever)
            mFadeoutStartDelay = mFades ? mThemeFadeoutStartDelay
                    : sv.mGestureTrailWholeLinger < 0 ? UNTIL_NEXT_TOUCH : sv.mGestureTrailWholeLinger;
            mFadeoutDuration = mFades ? sv.mGestureTrailFadeoutDuration : WHOLE_TRAIL_FADEOUT_DURATION;
        }
        mTrailLingerDuration = mFadeoutStartDelay + mFadeoutDuration;
    }
}

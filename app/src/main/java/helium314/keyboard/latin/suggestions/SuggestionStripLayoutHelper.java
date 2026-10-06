/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.suggestions;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.keyboard.FontLibrary;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.latin.PunctuationSuggestions;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.Suggest;
import helium314.keyboard.latin.SuggestedWords;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Defaults;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.SuggestionColors;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.ResourceUtils;

import java.util.ArrayList;

final class SuggestionStripLayoutHelper {
    // Orange used for all suggestion strip words. Roughly matches the warm
    // orange of common terminal status indicators.

    private static final int DEFAULT_MAX_MORE_SUGGESTIONS_ROW = 2;
    private static final int PUNCTUATIONS_IN_STRIP = 5;
    // min width of a word view = this + both paddings, equals config_suggestion_min_width at the default padding
    private static final int MIN_WORD_TEXT_WIDTH_DP = 26;
    // the suggestions should fill the visible part of the strip this many times
    private static final int STRIP_FILL_SCREENS = 2;
    private static final float QUIP_TEXT_SIZE_DP = 14f;
    private static final float QUIP_ALPHA = 0.6f;

    public final int mDividerWidth;
    public final int mSuggestionsStripHeight;
    public final int mMoreSuggestionsRowHeight;
    private int mMaxMoreSuggestionsRow;
    public final float mMinMoreSuggestionsWidth;
    public final int mMoreSuggestionsBottomGap;
    private TextView mQuipView;
    private String[] mQuips;
    private int mQuipIndex;

    // The index of these {@link ArrayList} is the position in the suggestion strip. The indices
    // increase towards the right for LTR scripts and the left for RTL scripts, starting with 0.
    private final ArrayList<TextView> mWordViews;
    private final ArrayList<View> mDividerViews;
    private final ArrayList<TextView> mDebugInfoViews;

    private final int mColorAutoCorrect;
    private final int mColorSuggested;

    private static final CharacterStyle BOLD_SPAN = new StyleSpan(Typeface.BOLD);
    private static final CharacterStyle UNDERLINE_SPAN = new UnderlineSpan();

    private final int mSuggestionStripOptions;
    // These constants are the flag values of
    // {@link R.styleable#SuggestionStripView_suggestionStripOptions} attribute.
    private static final int AUTO_CORRECT_BOLD = 0x01;
    private static final int AUTO_CORRECT_UNDERLINE = 0x02;
    private static final int VALID_TYPED_WORD_BOLD = 0x04;

    public SuggestionStripLayoutHelper(final Context context, final AttributeSet attrs,
            final int defStyle, final ArrayList<TextView> wordViews,
            final ArrayList<View> dividerViews, final ArrayList<TextView> debugInfoViews) {
        mWordViews = wordViews;
        mDividerViews = dividerViews;
        mDebugInfoViews = debugInfoViews;

        final TextView wordView = wordViews.get(0);
        final View dividerView = dividerViews.get(0);
        dividerView.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mDividerWidth = dividerView.getMeasuredWidth();

        final Resources res = wordView.getResources();
        mSuggestionsStripHeight = res.getDimensionPixelSize(
                R.dimen.config_suggestions_strip_height);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.SuggestionStripView, defStyle, R.style.SuggestionStripView);
        mSuggestionStripOptions = a.getInt(R.styleable.SuggestionStripView_suggestionStripOptions, 0);

        final Colors colors = Settings.getValues().mColors;
        mColorAutoCorrect = colors.get(ColorType.SUGGESTION_AUTO_CORRECT);
        mColorSuggested = colors.get(ColorType.SUGGESTED_WORD);

        mMaxMoreSuggestionsRow = a.getInt(
                R.styleable.SuggestionStripView_maxMoreSuggestionsRow,
                DEFAULT_MAX_MORE_SUGGESTIONS_ROW);
        mMinMoreSuggestionsWidth = ResourceUtils.getFraction(a,
                R.styleable.SuggestionStripView_minMoreSuggestionsWidth, 1.0f);
        a.recycle();

        mMoreSuggestionsBottomGap = res.getDimensionPixelOffset(
                R.dimen.config_more_suggestions_bottom_gap);
        mMoreSuggestionsRowHeight = res.getDimensionPixelSize(
                R.dimen.config_more_suggestions_row_height);
    }

    public int getMaxMoreSuggestionsRow() {
        return mMaxMoreSuggestionsRow;
    }

    private int getMoreSuggestionsHeight() {
        return mMaxMoreSuggestionsRow * mMoreSuggestionsRowHeight + mMoreSuggestionsBottomGap;
    }

    public void setMoreSuggestionsHeight(final int remainingHeight) {
        final int currentHeight = getMoreSuggestionsHeight();
        if (currentHeight <= remainingHeight) {
            return;
        }
        mMaxMoreSuggestionsRow = (remainingHeight - mMoreSuggestionsBottomGap) / mMoreSuggestionsRowHeight;
    }

    private CharSequence getStyledSuggestedWord(final SuggestedWords suggestedWords,
            final int indexInSuggestedWords) {
        if (indexInSuggestedWords >= suggestedWords.size()) {
            return null;
        }
        final String word = suggestedWords.getLabel(indexInSuggestedWords);
        // TODO: don't use the index to decide whether this is the auto-correction/typed word, as
        // this is brittle
        final boolean isAutoCorrection = suggestedWords.mWillAutoCorrect
                && indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION;
        final boolean isTypedWordValid = suggestedWords.mTypedWordValid
                && indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD;
        if (!isAutoCorrection && !isTypedWordValid) {
            return word;
        }

        final Spannable spannedWord = new SpannableString(word);
        final int options = mSuggestionStripOptions;
        if ((isAutoCorrection && (options & AUTO_CORRECT_BOLD) != 0)
                || (isTypedWordValid && (options & VALID_TYPED_WORD_BOLD) != 0)) {
            addStyleSpan(spannedWord, BOLD_SPAN);
        }
        if (isAutoCorrection && (options & AUTO_CORRECT_UNDERLINE) != 0) {
            addStyleSpan(spannedWord, UNDERLINE_SPAN);
        }
        return spannedWord;
    }
    private static int applyAlpha(final int color, final float alpha) {
        final int newAlpha = (int)(Color.alpha(color) * alpha);
        return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static void addDivider(final ViewGroup stripView, final View dividerView) {
        stripView.addView(dividerView);
        final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)dividerView.getLayoutParams();
        params.gravity = Gravity.CENTER;
    }

    /**
     * Layout suggestions to the suggestions strip. And returns the start index of more
     * suggestions.
     *
     * @param suggestedWords suggestions to be shown in the suggestions strip.
     * @param stripView the suggestions strip view.
     * @param placerView the view where the debug info will be placed.
     * @return the start index of more suggestions.
     */
    public int layoutAndReturnStartIndexOfMoreSuggestions(
            final Context context,
            final SuggestedWords suggestedWords,
            final ViewGroup stripView,
            final ViewGroup placerView) {
        if (suggestedWords.isPunctuationSuggestions()) {
            return layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
                    (PunctuationSuggestions)suggestedWords, stripView);
        }

        final int countInStrip = mWordViews.size();
        final int startIndexOfMoreSuggestions = setupWordViewsAndReturnStartIndexOfMoreSuggestions(
                suggestedWords, countInStrip);

        // Scrollable strip: candidates shown with natural widths, no weight-based layout.
        // Words are added until they fill the visible strip width twice, the rest is dropped.
        final int viewportWidth = getViewportWidth(stripView);
        final int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int wordsWidth = 0;
        int wordsShown = 0;
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            final TextView wordView = mWordViews.get(positionInStrip);
            if (TextUtils.isEmpty(wordView.getText())) continue;
            // a number of suggestions set by the user is shown in full; otherwise words fill the strip twice over
            if (Settings.getValues().mSuggestionCount == 0 && wordsWidth >= STRIP_FILL_SCREENS * viewportWidth) break;
            if (stripView.getChildCount() > 0) {
                addDivider(stripView, mDividerViews.get(positionInStrip));
                wordsWidth += mDividerWidth;
            }
            layoutWord(context, positionInStrip); // whole words at full width: the strip scrolls
            stripView.addView(wordView);
            wordView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wordView.measure(unspecified, unspecified);
            wordsWidth += wordView.getMeasuredWidth();
            wordsShown++;
        }
        if (wordsShown > 0) {
            // tell Suggest how many words it takes to fill the strip at the current text size and spacing
            final int wordsToFill = STRIP_FILL_SCREENS * viewportWidth * wordsShown / wordsWidth + 2;
            Suggest.setStripFillTarget(Math.min(wordsToFill, SuggestedWords.MAX_SUGGESTIONS));
            addQuip(stripView, Math.max(viewportWidth / 2, STRIP_FILL_SCREENS * viewportWidth - wordsWidth));
        }
        return startIndexOfMoreSuggestions;
    }

    private static int getViewportWidth(final ViewGroup stripView) {
        final int width = stripView.getParent() instanceof View parent ? parent.getWidth() : 0;
        return width > 0 ? width : stripView.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * The easter egg past the end of the suggestions: a grumpy remark that types nothing,
     * starting <code>gap</code> pixels after the last word. Tapping it shows another one.
     */
    private void addQuip(final ViewGroup stripView, final int gap) {
        if (mQuipView == null) {
            final Context context = stripView.getContext();
            mQuips = context.getResources().getStringArray(R.array.suggestion_strip_quips);
            mQuipView = new TextView(context);
            mQuipView.setGravity(Gravity.CENTER_VERTICAL);
            mQuipView.setSingleLine(true);
            mQuipView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, QUIP_TEXT_SIZE_DP);
            mQuipView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            mQuipView.setTextColor(applyAlpha(mColorSuggested, QUIP_ALPHA));
            mQuipView.setOnClickListener(v -> showNextQuip());
        }
        showNextQuip();
        if (mQuipView.getParent() instanceof ViewGroup oldParent) oldParent.removeView(mQuipView);
        mQuipView.setPaddingRelative(gap, 0, mQuipView.getResources().getDimensionPixelSize(
                R.dimen.config_suggestion_text_horizontal_padding) * 2, 0);
        stripView.addView(mQuipView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showNextQuip() {
        if (mQuips.length == 0) return;
        // random, but never the same one twice in a row
        mQuipIndex = (mQuipIndex + 1 + (int) (Math.random() * (mQuips.length - 1))) % mQuips.length;
        mQuipView.setText(mQuips[mQuipIndex]);
    }

    /**
     * Format appropriately the suggested word in {@link #mWordViews} specified by
     * <code>positionInStrip</code>. When the suggested word doesn't exist, the corresponding
     * {@link TextView} will be disabled and never respond to user interaction. The word is shown whole, at full
     * width (the strip scrolls).
     *
     * @param positionInStrip the position in the suggestion strip.
     * @return the {@link TextView} containing the suggested word appropriately formatted.
     */
    private TextView layoutWord(final Context context, final int positionInStrip) {
        final TextView wordView = mWordViews.get(positionInStrip);
        final CharSequence word = wordView.getText();
        wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        // {@link StyleSpan} in a content description may cause an issue of TTS/TalkBack.
        // Use a simple {@link String} to avoid the issue.
        wordView.setContentDescription(
                TextUtils.isEmpty(word)
                    ? context.getResources().getString(R.string.spoken_empty_suggestion)
                    : word.toString());
        wordView.setTextScaleX(1.0f);
        // A <code>wordView</code> should be disabled when <code>word</code> is empty in order to
        // make it unclickable.
        // With accessibility touch exploration on, <code>wordView</code> should be enabled even
        // when it is empty to avoid announcing as "disabled".
        wordView.setEnabled(!TextUtils.isEmpty(word)
                || AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled());
        return wordView;
    }

    private int setupWordViewsAndReturnStartIndexOfMoreSuggestions(
            final SuggestedWords suggestedWords, final int maxSuggestionInStrip) {
        // Clear all suggestions first
        for (int positionInStrip = 0; positionInStrip < maxSuggestionInStrip; ++positionInStrip) {
            final TextView wordView = mWordViews.get(positionInStrip);
            wordView.setText(null);
            wordView.setTag(null);
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(null);
            }
        }
        // Linear placement for the scrollable strip: typed word at position 0
        // (suggestedWords index 0 == INDEX_OF_TYPED_WORD), then auto-correction
        // (index 1), then other suggestions in order. No center-weighted remap.
        int positionInStrip = 0;
        int indexInSuggestedWords;
        // looked up once, not per word: finding the preferences is the slow part
        final SharedPreferences prefs = KtxKt.prefs(mWordViews.get(0).getContext());
        // the colour picked in Appearance → Fonts, else the theme's swipe trail colour (since 0.3.008)
        final int suggestionColor = SuggestionColors.suggestionTextColor(prefs, Settings.getValues().mColors);
        for (indexInSuggestedWords = 0; indexInSuggestedWords < suggestedWords.size()
                && positionInStrip < maxSuggestionInStrip; indexInSuggestedWords++) {
            final TextView wordView = mWordViews.get(positionInStrip);
            wordView.setTag(indexInSuggestedWords);
            wordView.setText(getStyledSuggestedWord(suggestedWords, indexInSuggestedWords));
            wordView.setTextColor(suggestionColor);
            applyCustomSuggestionStyle(wordView, prefs); // sets the font too (emoji font for emojis)
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(suggestedWords.getDebugString(indexInSuggestedWords));
            }
            positionInStrip++;
        }
        return indexInSuggestedWords;
    }

    private int layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
            final PunctuationSuggestions punctuationSuggestions, final ViewGroup stripView) {
        final int countInStrip = Math.min(punctuationSuggestions.size(), PUNCTUATIONS_IN_STRIP);
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            if (positionInStrip != 0) {
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, mDividerViews.get(positionInStrip));
            }

            final TextView wordView = mWordViews.get(positionInStrip);
            final String punctuation = punctuationSuggestions.getLabel(positionInStrip);
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(positionInStrip);
            wordView.setText(punctuation);
            wordView.setContentDescription(punctuation);
            wordView.setTextScaleX(1.0f);
            wordView.setCompoundDrawables(null, null, null, null);
            wordView.setTextColor(mColorAutoCorrect);
            KeyboardTypeface.applyToTextView(wordView);
            stripView.addView(wordView);
            setLayoutWeight(wordView, 1.0f, mSuggestionsStripHeight);
        }
        return countInStrip;
    }

    static void setLayoutWeight(final View v, final float weight, final int height) {
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof final LinearLayout.LayoutParams llp) {
            llp.weight = weight;
            llp.width = 0;
            llp.height = height;
        }
    }

    /**
     * Applies user-configurable styling (text size, bold, italic, underline, padding) to a
     * suggestion-strip word view. Reads the preferences fresh each layout pass so changes take
     * effect on the next suggestion update without restarting the IME.
     */
    private void applyCustomSuggestionStyle(final TextView wordView, final SharedPreferences prefs) {
        final Context context = wordView.getContext();
        final int textSizeDp = prefs.getInt(Settings.PREF_SUGGESTION_TEXT_SIZE, Defaults.PREF_SUGGESTION_TEXT_SIZE);
        final boolean bold = prefs.getBoolean(Settings.PREF_SUGGESTION_BOLD, Defaults.PREF_SUGGESTION_BOLD);
        final boolean italic = prefs.getBoolean(Settings.PREF_SUGGESTION_ITALIC, Defaults.PREF_SUGGESTION_ITALIC);
        final boolean underline = prefs.getBoolean(Settings.PREF_SUGGESTION_UNDERLINE, Defaults.PREF_SUGGESTION_UNDERLINE);
        final int paddingDp = prefs.getInt(Settings.PREF_SUGGESTION_WORD_PADDING, Defaults.PREF_SUGGESTION_WORD_PADDING);

        wordView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, (float) textSizeDp);

        // font as chosen in the Suggestion strip dialog, bold / italic applied to it (also un-bolds, which the old
        // setTypeface(current, style) couldn't)
        final String font = prefs.getString(Settings.PREF_SUGGESTION_FONT, "auto");
        // the key text's font while "use key text font" is on, else the strip's own; emojis get the emoji font
        final Typeface family = KeyboardTypeface.ownOrKeyFamily(font, FontLibrary.SLOT_SUGGESTION,
                prefs.getString(Settings.PREF_KEY_FONT, "auto"),
                prefs.getBoolean(Settings.PREF_FONT_FOLLOWS_KEY_TEXT, Defaults.PREF_FONT_FOLLOWS_KEY_TEXT));
        wordView.setTypeface(KeyboardTypeface.styled(wordView.getText(), family, false, bold, italic));

        if (underline) {
            wordView.setPaintFlags(wordView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        } else {
            wordView.setPaintFlags(wordView.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
        }

        final float density = context.getResources().getDisplayMetrics().density;
        final int paddingPx = (int) (paddingDp * density);
        wordView.setPadding(paddingPx, 0, paddingPx, 0);
        // The style's fixed 46dp min width would keep short words wide no matter how small the
        // spacing is. Let it follow the spacing instead: unchanged at the default 10dp, 26dp at 0.
        wordView.setMinWidth(paddingPx * 2 + (int) (MIN_WORD_TEXT_WIDTH_DP * density));
    }

    private static boolean hasStyleSpan(@Nullable final CharSequence text,
            final CharacterStyle style) {
        if (text instanceof Spanned) {
            return ((Spanned)text).getSpanStart(style) >= 0;
        }
        return false;
    }

    private static void addStyleSpan(@NonNull final Spannable text, final CharacterStyle style) {
        text.removeSpan(style);
        text.setSpan(style, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    private static int getTextWidth(@Nullable final CharSequence text, final TextPaint paint) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        final int length = text.length();
        final float[] widths = new float[length];
        final int count;
        final Typeface savedTypeface = paint.getTypeface();
        try {
            paint.setTypeface(getTextTypeface(text));
            count = paint.getTextWidths(text, 0, length, widths);
        } finally {
            paint.setTypeface(savedTypeface);
        }
        int width = 0;
        for (int i = 0; i < count; i++) {
            width += Math.round(widths[i] + 0.5f);
        }
        return width;
    }

    private static Typeface getTextTypeface(@Nullable final CharSequence text) {
        return hasStyleSpan(text, BOLD_SPAN) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }
}

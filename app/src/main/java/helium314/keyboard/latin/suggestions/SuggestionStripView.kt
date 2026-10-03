/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import androidx.core.content.edit
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.HorizontalScrollView
import android.graphics.Typeface
import android.widget.ImageView
import android.widget.PopupWindow
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.addPinnedKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removeFirst
import helium314.keyboard.latin.utils.removePinnedKey
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import kotlin.math.abs
import kotlin.math.min
import androidx.core.view.isGone
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        /** [word] was removed (long-press card): [remaining] are the suggestions without it, also for what space commits. */
        fun removeSuggestion(word: String?, remaining: SuggestedWords)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            colors.setBackground(word, ColorType.STRIP_BACKGROUND)
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // toolbar views, drawables and setup
    private val toolbar: ViewGroup = findViewById(R.id.toolbar)
    private val toolbarContainer: View = findViewById(R.id.toolbar_container)
    // the expanded toolbar replaces the suggestions in their row instead of opening a row above them
    private val toolbarInRow = Settings.getValues().mToolbarInRow
    // the emoji view shows the toolbar alone (see setToolbarOnly)
    private var toolbarOnlyShown = false
    private val pinnedKeys: ViewGroup = findViewById(R.id.pinned_keys)
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val toolbarExpandKey = findViewById<ImageButton>(R.id.suggestions_strip_toolbar_key)
    private val incognitoIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.INCOGNITO.name, context)
    private val toolbarArrowIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
    private val settingsToolbarIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.SETTINGS.name, context)
    private val defaultToolbarBackground: Drawable = toolbarExpandKey.background
    private val enabledToolKeyBackground = GradientDrawable()
    private var direction = 1 // 1 if LTR, -1 if RTL

    private val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
        LinearLayout.LayoutParams.MATCH_PARENT
    )

    init {
        val colors = Settings.getValues().mColors

        // expand key
        // weird way of setting size (default is config_suggestions_strip_edge_key_width)
        // but better not change it or people will complain
        val toolbarHeight = min(toolbarExpandKey.layoutParams.height, resources.getDimension(R.dimen.config_suggestions_strip_height).toInt())
        toolbarExpandKey.layoutParams.height = toolbarHeight
        toolbarExpandKey.layoutParams.width = toolbarHeight // we want it square
        colors.setBackground(toolbarExpandKey, ColorType.STRIP_BACKGROUND) // necessary because background is re-used for defaultToolbarBackground
        colors.setColor(toolbarExpandKey, ColorType.TOOL_BAR_EXPAND_KEY)
        colors.setColor(toolbarExpandKey.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)

        // the toolbar in place of the suggestions (upstream's way): it moves into the suggestions' row, after the expand key
        if (toolbarInRow) {
            (toolbarContainer.parent as ViewGroup).removeView(toolbarContainer)
            findViewById<LinearLayout>(R.id.suggestions_strip_wrapper)
                .addView(toolbarContainer, 1, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        // background indicator for pinned keys
        val color = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000 // ignore alpha (in Java this is more readable 0xFF000000)
        enabledToolKeyBackground.colors = intArrayOf(color, Color.TRANSPARENT)
        enabledToolKeyBackground.gradientType = GradientDrawable.RADIAL_GRADIENT
        enabledToolKeyBackground.gradientRadius = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f

        val mToolbarMode = if (isGone) ToolbarMode.HIDDEN else Settings.getValues().mToolbarMode
        if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS || (mToolbarMode == ToolbarMode.EXPANDABLE && Settings.getValues().mToolbarAlwaysOpen)) {
            setToolbarVisibility(true) // (always visible: with suggestions on it sits above them, without an arrow)
        } else if (mToolbarMode == ToolbarMode.EXPANDABLE && toolbarVisibleAfterReload) {
            setToolbarVisibility(true) // the strip was rebuilt (a theme change): the toolbar stays as it was
        }
        toolbarVisibleAfterReload = false
        current = this

        // toolbar keys setup
        if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS || mToolbarMode == ToolbarMode.EXPANDABLE) {
            for (key in getEnabledToolbarKeys(context.prefs())) {
                val button = createToolbarKey(context, key)
                button.layoutParams = toolbarKeyLayoutParams
                setupKey(button, colors)
                toolbar.addView(button)
            }
        }
        if (!isGone && !Settings.getValues().mSuggestionStripHiddenPerUserSettings) {
            for (pinnedKey in getPinnedToolbarKeys(context.prefs())) {
                val button = createToolbarKey(context, pinnedKey)
                button.layoutParams = toolbarKeyLayoutParams
                setupKey(button, colors)
                pinnedKeys.addView(button)
            }
        }

        updateKeys()
    }

    private lateinit var listener: Listener
    private var suggestedWords = SuggestedWords.getEmptyInstance()
    private var startIndexOfMoreSuggestions = 0
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && toolbarContainer.visibility != VISIBLE && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
            setExpandKeyDirection(toolbarContainer.visibility == VISIBLE)
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
    }

    fun setToolbarVisibility(toolbarVisible: Boolean) {
        // avoid showing toolbar keys when locked
        val locked = isDeviceLocked(context)
        // always visible (Toolbar visibility): nothing closes it, except the emoji view putting everything back
        val show = !locked && (toolbarVisible || (Settings.getValues().mToolbarAlwaysOpen && !toolbarOnlyShown))
        if (toolbarInRow) {
            // in the suggestions' row: the toolbar takes their place while open
            toolbarContainer.isVisible = show
            (suggestionsStrip.parent as View).isVisible = !show
            pinnedKeys.isVisible = !locked && !toolbarOnlyShown
            setExpandKeyDirection(show)
            return
        }
        // the toolbar slides up above the suggestions, which stay where they are
        if (show && !toolbarContainer.isVisible) {
            toolbarContainer.isVisible = true
            toolbarContainer.translationY = toolbarContainer.layoutParams.height.toFloat()
            toolbarContainer.alpha = 0f
            toolbarContainer.animate().translationY(0f).alpha(1f).setDuration(120).start()
        } else if (!show) {
            toolbarContainer.animate().cancel()
            toolbarContainer.translationY = 0f
            toolbarContainer.alpha = 1f
            toolbarContainer.isVisible = false
        }
        pinnedKeys.isVisible = !locked
        suggestionsStrip.isVisible = true

        if (DEBUG_SUGGESTIONS) {
            for (view in debugInfoViews) {
                view.visibility = suggestionsStrip.visibility
            }
        }

        setExpandKeyDirection(show)
    }

    /**
     * In the emoji view the suggestions row is replaced by the emoji tabs, but the toolbar is still wanted: this shows
     * the strip with only its toolbar, above [tabStrip] (pushed down by a top margin), or puts everything back.
     */
    fun setToolbarOnly(show: Boolean, tabStrip: View?) {
        val wrapper: View = findViewById(R.id.suggestions_strip_wrapper)
        val params = tabStrip?.layoutParams as? ViewGroup.MarginLayoutParams
        toolbarOnlyShown = show
        if (toolbarInRow) {
            // the toolbar lives in the suggestions' row: that row stays, without its expand key (the tabs have one)
            if (show) isVisible = true
            toolbarExpandKey.isVisible = !show
            setToolbarVisibility(show)
            params?.topMargin = if (show) toolbarContainer.layoutParams.height else 0
        } else if (show) {
            isVisible = true
            wrapper.isVisible = false
            setToolbarVisibility(true)
            params?.topMargin = toolbarContainer.layoutParams.height
        } else {
            wrapper.isVisible = true
            params?.topMargin = 0
            setToolbarVisibility(false)
        }
        emojiToolbarKey?.rotation = if (show) 90f else -90f
        tabStrip?.requestLayout()
    }

    /** True while [setToolbarOnly] shows the toolbar alone. */
    val isToolbarOnly: Boolean get() = isVisible && toolbarOnlyShown

    /** A key for the start of the emoji tab strip that opens the toolbar there, drawn like the strip's own expand key. */
    fun createEmojiToolbarKey(onClick: () -> Unit): ImageButton {
        val key = ImageButton(context, null, R.attr.suggestionWordStyle)
        val size = toolbarExpandKey.layoutParams.height
        key.layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_VERTICAL }
        key.background = defaultToolbarBackground.constantState?.newDrawable()?.mutate() ?: defaultToolbarBackground
        key.setImageDrawable(toolbarArrowIcon?.constantState?.newDrawable()?.mutate() ?: toolbarArrowIcon)
        key.scaleType = ImageView.ScaleType.CENTER
        key.contentDescription = resources.getString(R.string.more_keys_strip_description)
        val colors = Settings.getValues().mColors
        colors.setBackground(key, ColorType.STRIP_BACKGROUND)
        colors.setColor(key, ColorType.TOOL_BAR_EXPAND_KEY)
        colors.setColor(key.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)
        key.rotation = if (isToolbarOnly) 90f else -90f
        key.setOnClickListener { onClick() }
        emojiToolbarKey = key
        return key
    }

    // the emoji tab strip's toolbar key: its arrow follows setToolbarOnly
    private var emojiToolbarKey: ImageButton? = null

    /**
     * The expand key's arrow points up while the toolbar is hidden, down while it is shown above the suggestions; when
     * the toolbar opens in place of the suggestions it points sideways instead: > to open, < to go back.
     */
    private fun setExpandKeyDirection(toolbarShown: Boolean) {
        // only the arrow turns: the incognito and settings icons stay upright whatever the toolbar does
        val arrow = (context.prefs().getString(Settings.PREF_TOOLBAR_EXPAND_ICON, Defaults.PREF_TOOLBAR_EXPAND_ICON)
            ?: Defaults.PREF_TOOLBAR_EXPAND_ICON) == "arrow"
        if (!arrow) {
            toolbarExpandKey.rotation = 0f
            toolbarExpandKey.scaleX = 1f
        } else if (toolbarInRow) {
            toolbarExpandKey.rotation = 0f
            toolbarExpandKey.scaleX = if (toolbarShown) -1f else 1f
        } else {
            toolbarExpandKey.scaleX = 1f
            toolbarExpandKey.rotation = if (toolbarShown) 90f else -90f
        }
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        clear()
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        isExternalSuggestionVisible = false
        // new words start at the beginning, not wherever the previous list was scrolled to
        val scrollView = suggestionsStrip.parent as? HorizontalScrollView
        if (isRtlLanguage) scrollView?.post { scrollView.fullScroll(FOCUS_RIGHT) } // the beginning is the right end, known after layout
        else scrollView?.scrollTo(0, 0)
        updateKeys()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = toolbarKeyLayoutParams
            setupKey(closeButton, Settings.getValues().mColors)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else {
            suggestionsStrip.addView(view)
        }
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(pinnedKeys, key)
        setToolbarButtonsActivatedStateOnPrefChange(toolbar, key)
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); updateKeys() }
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        if (view === this)
            suggestionsStrip.visibility = visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        val tag = view.tag
        if (tag is ToolbarKey) {
            val code = getCodeForToolbarKey(tag)
            if (code != KeyCode.UNSPECIFIED) {
                Log.d(TAG, "click toolbar key $tag")
                listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
                return
            }
        }
        if (view === toolbarExpandKey) {
            setToolbarVisibility(toolbarContainer.visibility != VISIBLE)
        }

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            if (tag >= suggestedWords.size()) {
                return
            }
            val wordInfo = suggestedWords.getInfo(tag)
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view)
            return true
        }
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    private fun onLongClickToolbarKey(view: View) {
        val tag = view.tag as? ToolbarKey ?: return
        val longClickCode = getCodeForToolbarKeyLongClick(tag)
        if (longClickCode != KeyCode.UNSPECIFIED) {
            listener.onCodeInput(longClickCode, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
        }
    }

    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        val index = wordView.tag as? Int ?: return false
        if (index >= suggestedWords.size()) return false
        val info = suggestedWords.getInfo(index)
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(wordView)
            return true
        }
        // the word as typed can't be removed from anything
        if (info.mSourceDict == Dictionary.DICTIONARY_USER_TYPED || info.mSourceDict == Dictionary.DICTIONARY_HARDCODED)
            return false
        // a card above the strip instead of upstream's bin icon that needed a second, precise tap
        showRemoveSuggestionCard(wordView, info.word)
        return true
    }

    /**
     * A keyboard-themed card in the middle of the keyboard, clear of the strip: "Remove “word”?", what removing means,
     * Cancel / Remove. It doesn't take the focus: a focused window without a text field made Android hide the keyboard,
     * which hid the card, gave the focus back and showed the keyboard again, in a loop (seen in the settings' preview).
     */
    private fun showRemoveSuggestionCard(wordView: TextView, word: String) {
        val colors = Settings.getValues().mColors
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(colors.get(ColorType.MORE_SUGGESTIONS_BACKGROUND))
            }
            elevation = dp(8).toFloat()
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context))
            colors.setColor(this, ColorType.REMOVE_SUGGESTION_ICON)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.remove_suggestion, word)
            setTextColor(colors.get(ColorType.KEY_TEXT))
            textSize = 19f
        }
        titleRow.addView(icon)
        titleRow.addView(title)
        // what removing means: it comes back when typed, and what it had learned is gone
        val message = TextView(context).apply {
            text = context.getString(R.string.remove_suggestion_message)
            setTextColor(colors.get(ColorType.KEY_TEXT))
            alpha = 0.75f
            textSize = 14f
            setPadding(0, dp(10), dp(8), dp(4))
        }
        fun button(textId: Int, accent: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = context.getString(textId).uppercase()
            setTextColor(colors.get(if (accent) ColorType.SUGGESTION_AUTO_CORRECT else ColorType.KEY_TEXT))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }
        val popup = PopupWindow(card, (width * 0.85f).toInt().coerceAtMost(dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT, false)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            // Cancel left, the action right
            addView(button(android.R.string.cancel, false) { popup.dismiss() })
            addView(button(R.string.remove, true) { removeSuggestion(word); popup.dismiss() })
        }
        card.addView(titleRow)
        card.addView(message)
        card.addView(buttons)
        popup.isOutsideTouchable = true // a tap elsewhere closes it (it gets told without having the focus)
        popup.setOnDismissListener { wordView.isPressed = false }
        // in the middle of the keyboard below the strip
        card.measure(MeasureSpec.makeMeasureSpec(popup.width, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        val stripLocation = IntArray(2).also { getLocationInWindow(it) }
        val keyboardHeight = KeyboardSwitcher.getInstance().mainKeyboardView?.height ?: 0
        val x = stripLocation[0] + (width - popup.width) / 2
        val y = stripLocation[1] + height + ((keyboardHeight - card.measuredHeight) / 2).coerceAtLeast(dp(8))
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size() <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        if (index >= suggestedWords.size()) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        val text = info.mSourceDict.mDictType + ":" + info.mSourceDict.mLocale
        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(word: String) {
        moreSuggestionsView.dismissPopupKeysPanel()
        // the suggestions without the removed word
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>()
        var removedIndex = -1
        for (i in 0..<suggestedWords.size()) {
            val info = suggestedWords.getInfo(i)
            if (info.word != word) suggestedWordInfos.add(info) else if (removedIndex < 0) removedIndex = i
        }
        suggestedWords.mRawSuggestions?.removeFirst { it.word == word }
        // the removed word was the incoming auto-correction (or came before it, moving another word into its slot):
        // nothing is auto-corrected now, so space doesn't commit it
        val willAutoCorrect = suggestedWords.mWillAutoCorrect && (removedIndex < 0 || removedIndex > SuggestedWords.INDEX_OF_AUTO_CORRECTION)
        val newSuggestedWords = SuggestedWords(
            suggestedWordInfos, suggestedWords.mRawSuggestions, suggestedWords.typedWordInfo, suggestedWords.mTypedWordValid,
            willAutoCorrect, suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber
        )
        // the keyboard takes them (what space commits) and shows them
        listener.removeSuggestion(word, newSuggestedWords)
        suggestionsStrip.isVisible = true
    }

    private fun clear() {
        suggestionsStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        if (!toolbarContainer.isVisible)
            suggestionsStrip.isVisible = true
        dismissMoreSuggestionsPanel()
        for (word in wordViews) {
            word.setOnTouchListener(null)
        }
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    fun updateVoiceKey() {
        val show = Settings.getValues().mShowsVoiceInputKey
        toolbar.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        pinnedKeys.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
    }

    private fun updateKeys() {
        updateVoiceKey()
        val settingsValues = Settings.getValues()

        val toolbarIsExpandable = settingsValues.mToolbarMode == ToolbarMode.EXPANDABLE
        if (settingsValues.mIncognitoModeEnabled) {
            // Incognito mode forces the built-in incognito icon regardless of user pref.
            toolbarExpandKey.setImageDrawable(incognitoIcon)
            toolbarExpandKey.isVisible = true
        } else {
            val expandIconChoice = context.prefs().getString(
                Settings.PREF_TOOLBAR_EXPAND_ICON, Defaults.PREF_TOOLBAR_EXPAND_ICON
            ) ?: Defaults.PREF_TOOLBAR_EXPAND_ICON
            when (expandIconChoice) {
                // the icon set's own incognito glyph (hat, glasses, chevron), drawn and tinted like the other toolbar icons
                // (was a picture with its own white disc and dark square, which sat on the strip like a sticker)
                "incognito" -> toolbarExpandKey.setImageDrawable(incognitoIcon)
                "settings" -> toolbarExpandKey.setImageDrawable(settingsToolbarIcon)
                "none" -> { /* hidden below */ }
                else -> toolbarExpandKey.setImageDrawable(toolbarArrowIcon)
            }
            toolbarExpandKey.isVisible = (expandIconChoice != "none") && toolbarIsExpandable && !settingsValues.mToolbarAlwaysOpen
        }
        // opened from the top-left key (suggestions off): the arrow, pointing down, closes it again
        if (settingsValues.mToolbarOpenedByKey) {
            toolbarExpandKey.setImageDrawable(toolbarArrowIcon)
            toolbarExpandKey.isVisible = true
            setExpandKeyDirection(true)
            toolbarExpandKey.setOnClickListener {
                context.prefs().edit { putBoolean(Settings.PREF_TOOLBAR_OPENED_BY_KEY, false) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
            pinnedKeys.visibility = GONE
            isExternalSuggestionVisible = false
            return
        }

        // hide pinned keys if device is locked, and avoid expanding toolbar
        val hideToolbarKeys = isDeviceLocked(context)
        toolbarExpandKey.setOnClickListener(if (hideToolbarKeys || !toolbarIsExpandable) null else this)
        pinnedKeys.visibility = if (hideToolbarKeys) GONE else suggestionsStrip.visibility
        isExternalSuggestionVisible = false
    }

    private fun addKeyToPinnedKeys(pinnedKey: ToolbarKey) {
        val original = toolbar.findViewWithTag<ImageButton>(pinnedKey) ?: return
        // copy the original key to a new ImageButton
        val copy = ImageButton(context, null, R.attr.suggestionWordStyle)
        copy.tag = pinnedKey
        copy.scaleType = original.scaleType
        copy.scaleX = original.scaleX
        copy.scaleY = original.scaleY
        copy.contentDescription = original.contentDescription
        copy.setImageDrawable(original.drawable)
        copy.layoutParams = original.layoutParams
        copy.isActivated = original.isActivated
        setupKey(copy, Settings.getValues().mColors)
        pinnedKeys.addView(copy)
    }

    private fun setupKey(view: ImageButton, colors: Colors) {
        view.setOnClickListener(this)
        view.setOnLongClickListener(this)
        colors.setColor(view, ColorType.TOOL_BAR_KEY)
        colors.setBackground(view, ColorType.STRIP_BACKGROUND)
    }

    companion object {
        @JvmField
        var DEBUG_SUGGESTIONS = false
        private var current: SuggestionStripView? = null
        private var toolbarVisibleAfterReload = false

        /** Called before the theme reloads the input view, so the new strip can show the toolbar again if it was open. */
        @JvmStatic
        fun rememberToolbarForReload() {
            toolbarVisibleAfterReload = current?.toolbarContainer?.isVisible == true
        }
        /** The settings' preview (toolbar keys dialog): the toolbar opens on the live keyboard, or closes again. */
        @JvmStatic
        fun showToolbarForPreview(show: Boolean) {
            val strip = current ?: return
            if (Settings.getValues().mToolbarMode == ToolbarMode.EXPANDABLE && !Settings.getValues().mToolbarAlwaysOpen)
                strip.setToolbarVisibility(show)
        }
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}

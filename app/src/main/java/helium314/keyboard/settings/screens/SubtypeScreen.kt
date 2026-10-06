package helium314.keyboard.settings.screens

import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.dialogs.UnsavedChangesDialog
import helium314.keyboard.settings.dialogs.SaveChangesDialog
import helium314.keyboard.settings.dialogs.DiscardChangesDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.preferences.LocalPendingChange
import helium314.keyboard.settings.LayoutDraft
import helium314.keyboard.latin.utils.LayoutType.Companion.folder
import helium314.keyboard.settings.advancedTint
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import helium314.keyboard.settings.dialogs.LocalBottomBarTop
import helium314.keyboard.settings.dialogs.LocalPreviewKeyboard
import helium314.keyboard.settings.dialogs.LocalKeepKeyboard
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_ALL
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MAIN
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_MORE
import helium314.keyboard.keyboard.internal.keyboard_parser.POPUP_KEYS_NORMAL
import helium314.keyboard.keyboard.internal.keyboard_parser.hasLocalizedNumberRow
import helium314.keyboard.keyboard.internal.keyboard_parser.morePopupKeysResId
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.Links
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.displayNameId
import helium314.keyboard.latin.utils.LayoutUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getDictionaryLocales
import helium314.keyboard.settings.dialogs.DictionaryDialog
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.LanguagePriority
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.LocalCompactPreferences
import helium314.keyboard.latin.utils.NextScreenIcon
import androidx.compose.ui.draw.rotate
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.RichInputMethodManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.htmlToAnnotated
import helium314.keyboard.latin.utils.mainLayoutName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.withHtmlLink
import helium314.keyboard.settings.ActionRow
import helium314.keyboard.latin.utils.DefaultButton
import helium314.keyboard.latin.utils.DeleteButton
import helium314.keyboard.settings.DropDownField
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsMode
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.WithBigTitle
import helium314.keyboard.settings.WithSmallTitle
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.LayoutEditDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.MultiListPickerDialog
import helium314.keyboard.settings.dialogs.ReorderDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.GetIconOrEmpty
import java.util.Locale

@Composable
fun SubtypeScreen(
    initialSubtype: SettingsSubtype,
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    var previewHeight by remember { mutableIntStateOf(0) }
    var holdSpace by remember { mutableStateOf(false) }
    var holdTick by remember { mutableIntStateOf(0) } // every hold restarts the timer, so quick repeated toggles don't lose the space
    LaunchedEffect(holdTick) { if (holdSpace) { delay(700); holdSpace = false } }
    fun holdPreview() { holdSpace = true; holdTick++ }
    val reservedBottom = if (holdSpace) previewHeight else 0
    var currentSubtypeString by rememberSaveable { mutableStateOf(initialSubtype.toPref()) }
    val currentSubtype = currentSubtypeString.toSettingsSubtype()
    // Keep / Discard like Appearance: the screen's settings (and its sub-screens') as they were when it opened
    var draft by remember { mutableStateOf(LayoutDraft.of(ctx, currentSubtypeString)) }
    val changedKeys = draft.changedKeys(ctx)
    val changedFolders = draft.changedLayoutFolders(ctx)
    val changed = changedKeys.isNotEmpty() || changedFolders.isNotEmpty()
    val openedSubtype = draft.subtype.toSettingsSubtype()
    fun keyChanged(vararg keys: String) = keys.any { it in changedKeys }
    fun prefixChanged(prefix: String) = changedKeys.any { it.startsWith(prefix) }
    fun layoutChanged(type: LayoutType) = currentSubtype.layoutName(type) != openedSubtype.layoutName(type)
        || type.folder.substringAfterLast(java.io.File.separator) in changedFolders || keyChanged(Settings.PREF_LAYOUT_PREFIX + type.name)
    var askOnLeave by remember { mutableStateOf(false) }
    // a Discard puts values back behind the rows' backs: the list is built anew, every row reads its value again
    var rebuild by remember { mutableIntStateOf(0) }
    var askReject by remember { mutableStateOf(false) }
    var askAccept by remember { mutableStateOf(false) }
    fun setCurrentSubtype(subtype: SettingsSubtype) {
        // the keyboard being edited must be one in the list: after the app was killed (an update) and the screen came
        // back, or after an undo, it can hold an older definition; changing that one added a second keyboard. Then
        // the change replaces the list's keyboard of the same language instead.
        val enabled = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }
        val from = if (currentSubtype in enabled) currentSubtype
            else enabled.firstOrNull { it.locale == currentSubtype.locale }?.also {
                Log.w("SubtypeScreen", "edited keyboard not in the list (${currentSubtype.toPref()}), changing ${it.toPref()} instead")
            } ?: currentSubtype
        SubtypeUtilsAdditional.changeAdditionalSubtype(from, subtype, ctx)
        currentSubtypeString = subtype.toPref()
        holdPreview()
        // the live keyboard runs the changed definition right away (the try-it preview)
        if (RichInputMethodManager.isInitialized())
            KeyboardSwitcher.getInstance().switchToSubtype(subtype.toAdditionalSubtype())
        reloadPreview()
    }
    LaunchedEffect(currentSubtypeString) {
        if (ScriptUtils.scriptSupportsUppercase(currentSubtype.locale)) return@LaunchedEffect
        // update the noShiftKey extra value
        val mainLayout = currentSubtype.mainLayoutName()
        val noShiftKey = if (mainLayout != null && LayoutUtilsCustom.isCustomLayout(mainLayout)) {
            // determine from layout
            val content = LayoutUtilsCustom.getLayoutFile(mainLayout, LayoutType.MAIN, ctx).readText()
            !content.contains("\"shift_state_selector\"")
        } else {
            // determine from subtype with same layout
            SubtypeSettings.getResourceSubtypesForLocale(currentSubtype.locale)
                .firstOrNull { it.mainLayoutName() == mainLayout }
                ?.containsExtraValueKey(ExtraValue.NO_SHIFT_KEY) ?: false
        }
        if (!noShiftKey && currentSubtype.hasExtraValueOf(ExtraValue.NO_SHIFT_KEY))
            setCurrentSubtype(currentSubtype.without(ExtraValue.NO_SHIFT_KEY))
        else if (noShiftKey && !currentSubtype.hasExtraValueOf(ExtraValue.NO_SHIFT_KEY))
            setCurrentSubtype(currentSubtype.with(ExtraValue.NO_SHIFT_KEY))
    }

    val availableLocalesForScript = getAvailableSecondaryLocales(ctx, currentSubtype.locale).sortedBy { it.toLanguageTag() }
    var showSecondaryLocaleDialog by remember { mutableStateOf(false) }
    // the position survives the rebuild a change causes: the space the preview keyboard takes is held for a
    // moment while it reloads (otherwise the content is short again, maxValue drops to 0 and the position is lost)
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val tryIt = remember { TryItState() }
    // back from Android's settings (sound / vibration switched there): the input rows are worked out again
    var resumed by remember { mutableIntStateOf(0) }
    // and when the phone is silenced or unsilenced with the screen open (volume keys, quick settings)
    val feedbackChanges = helium314.keyboard.settings.preferences.SystemFeedback.rememberChanges()
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // the key sound settings preview on the keyboard like Appearance's: their dialogs bring it up and keep it
    val focusManager = LocalFocusManager.current
    val softKeyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    // the row tapped last is moved above where the preview keyboard will end when a setting brings it up
    val tapReveal = remember { helium314.keyboard.settings.ListTapReveal(scrollState, scope, ctx, view) }
    val preview = remember { PreviewKeyboard(tryIt, scope, showIme = { softKeyboard?.show() }, reveal = tapReveal::reveal) {
        focusManager.clearFocus(force = true); softKeyboard?.hide() } }
    var bottomBarTop by remember { mutableIntStateOf(-1) }
    // a switch whose effect shows on the keyboard brings it up for a moment, as on Appearance (dialogs report themselves):
    // the keyboard's shape, what a key press does (popup, vibration, sound), the emoji key, backspace, popups
    fun shape() = previewedSwitches.map { prefs.all[it] }
    var lastShape by remember { mutableStateOf(shape()) }
    LaunchedEffect(b?.value) {
        val now = shape()
        if (now != lastShape) {
            val changed = previewedSwitches.filterIndexed { i, _ -> now[i] != lastShape[i] }
            lastShape = now
            // the sound switch while Android keeps key sounds off (silent, DND…): it is greyed and there is nothing
            // to hear, so no preview; with sound possible it previews like the rest
            val soundOnlyAndBlocked = changed == listOf(Settings.PREF_SOUND_ON) &&
                helium314.keyboard.settings.preferences.SystemFeedback.soundBlocker(ctx) != null
            if (!soundOnlyAndBlocked) preview.changed(emoji = false)
        }
    }
    DisposableEffect(Unit) { onDispose { (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = Int.MAX_VALUE } }
    // (the named ones, and this keyboard's own unnamed copy of a deleted one)
    val customMainLayouts = LayoutUtilsCustom.listedLayoutNames(LayoutType.MAIN, ctx, currentSubtype.locale, currentSubtype.mainLayoutName())
    // checked when leaving (the top bar's arrow may hold an older copy of this function)
    fun leave() { if (draft.hasChanges(ctx)) askOnLeave = true else { LayoutDraft.close(); onClickBack() } }
    fun discardChanges() {
        draft.reject(ctx)
        LayoutDraft.takeRejectedSubtype() // (handled right here)
        rebuild++
        currentSubtypeString = draft.subtype
        if (RichInputMethodManager.isInitialized())
            KeyboardSwitcher.getInstance().switchToSubtype(draft.subtype.toSettingsSubtype().toAdditionalSubtype())
    }
    // back in the app after leaving it (the changes were undone then): a new snapshot of what is there now
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // undone while away (the app was left): edit the keyboard as it was put back
                LayoutDraft.takeRejectedSubtype()?.let { currentSubtypeString = it }
                // changed by a screen opened from here (the JSON editor saves this keyboard's own keys): follow it
                SubtypeUtilsAdditional.current(currentSubtypeString).let { if (it != currentSubtypeString) { currentSubtypeString = it; rebuild++ } }
                val fresh = LayoutDraft.of(ctx, currentSubtypeString)
                if (fresh !== draft) { currentSubtypeString = fresh.subtype; draft = fresh; rebuild++ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (askReject) DiscardChangesDialog({ askReject = false }) { discardChanges(); draft = LayoutDraft.of(ctx, currentSubtypeString) }
    if (askAccept) SaveChangesDialog({ askAccept = false }) { draft.accept(); draft = LayoutDraft.of(ctx, currentSubtypeString) }
    if (askOnLeave) UnsavedChangesDialog(
        onKeepWorking = { askOnLeave = false },
        onDiscardAndExit = { discardChanges(); askOnLeave = false; onClickBack() },
        onSaveAndExit = { draft.accept(); askOnLeave = false; onClickBack() },
    )
    SearchScreen(
        onClickBack = ::leave,
        // cross and tick: discard or keep everything changed since the screen opened, each asks first
        leadingActions = {
            if (changed) {
                IconButton({ askReject = true }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.appearance_reject)) }
                IconButton({ askAccept = true }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.appearance_accept)) }
            }
        },
        icon = { if (SubtypeSettings.getEnabledSubtypes(true).size > 1 && SubtypeSettings.isEnabled(currentSubtype.toAdditionalSubtype())) DeleteButton {
            LayoutDraft.close() // deleting keeps the changes so far: a Discard mustn't bring the keyboard back
            if (currentSubtype.isAdditionalSubtype(prefs)) SubtypeUtilsAdditional.removeAdditionalSubtype(ctx, currentSubtype.toAdditionalSubtype())
            SubtypeSettings.removeEnabledSubtype(ctx, currentSubtype.toAdditionalSubtype())
            KeyboardProfiles.onKeyboardDeleted(ctx.realPrefs(), currentSubtype)
            onClickBack()
        } },
        // the screen's name (the keyboard's name goes under it when settings are separate per keyboard)
        title = { Text(stringResource(R.string.settings_screen_preferences)) },
        itemContent = { },
        filteredItems = { emptyList<String>() }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Box(Modifier.onSizeChanged { if (it.height > previewHeight) previewHeight = it.height }.onGloballyPositioned {
                    bottomBarTop = it.positionInWindow().y.roundToInt()
                    tapReveal.onBarPlaced(bottomBarTop)
                    // the try-it bar stays usable while a key sound dialog is open
                    (ctx.getActivity() as? SettingsActivity)?.touchPassFromY = bottomBarTop
                }) {
                    TryItBar(currentSubtype, tryIt, onFocus = preview::onFocus, onUsed = preview::onUsed)
                }
            }
        ) { innerPadding ->
            Column(
                modifier = tapReveal.list // where a row was tapped, for the reveal
                    .verticalScroll(scrollState).padding(horizontal = SCREEN_MARGIN)
                    .then(Modifier.padding(innerPadding))
                    .padding(bottom = with(LocalDensity.current) { reservedBottom.toDp() }),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { androidx.compose.runtime.key(rebuild) {
                val advanced by SettingsMode.state(ctx)
                // two groups, one row style (label 10 dp in, rows 56 dp high): see LocalCompactPreferences, SwitchRow
                // ---- the layout, with the toolbar (then typing with backspace, popups, clipboard history): main layout (advanced), shape, emoji key, split, send key, other layouts, bottom row
                // the saved Layouts on top, on their own between two lines: they hold everything on this screen (every
                // group, popups too), not just the Layout group (2026-10-03); the line below is the next heading's
                androidx.compose.material3.HorizontalDivider()
                helium314.keyboard.settings.preferences.LayoutPresetsPreference(currentSubtype) { setCurrentSubtype(it) }
                WithBigTitle(stringResource(R.string.keyboard_layout_set)) {
                    // (the keys, QWERTY, AZERTY, …: first row of Keys & Popups)
                    // the keyboard's shape first (moved from Appearance): height, numbers row;
                    // their dialogs keep the preview keyboard up
                    CompositionLocalProvider(LocalCompactPreferences provides true, LocalKeepKeyboard provides true,
                        LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) {
                        Pending(prefixChanged(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX)) {
                            SettingsActivity.settingsContainer[Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX]?.Preference() }
                        Pending(keyChanged(Settings.PREF_SHOW_NUMBER_ROW)) {
                            SettingsActivity.settingsContainer[Settings.PREF_SHOW_NUMBER_ROW]?.Preference() }
                        if (!prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW))
                            Pending(keyChanged(Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS)) { Box(Modifier.padding(start = 16.dp)) {
                                SettingsActivity.settingsContainer[Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS]?.Preference() } }
                    }
                    // then the emoji key switch, and the voice key's (right of the space bar)
                    Pending(keyChanged(Settings.PREF_SHOW_EMOJI_KEY)) {
                        PrefSwitchRow(Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY, R.string.show_emoji_key) {
                            holdPreview()
                            KeyboardSwitcher.getInstance().reloadKeyboard()
                        }
                    }
                    Pending(keyChanged(Settings.PREF_SHOW_VOICE_KEY)) {
                        PrefSwitchRow(Settings.PREF_SHOW_VOICE_KEY, Defaults.PREF_SHOW_VOICE_KEY, R.string.show_voice_key) {
                            holdPreview()
                            KeyboardSwitcher.getInstance().reloadKeyboard()
                        }
                    }
                    // split keyboard (and its gap), with the preview keyboard
                    CompositionLocalProvider(LocalCompactPreferences provides true, LocalKeepKeyboard provides true,
                        LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) {
                        Pending(keyChanged(Settings.PREF_ENABLE_SPLIT_KEYBOARD, Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE,
                            Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE)) {
                            SettingsActivity.settingsContainer[Settings.PREF_ENABLE_SPLIT_KEYBOARD]?.Preference() }
                        if (listOf(Settings.PREF_ENABLE_SPLIT_KEYBOARD, Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE,
                                Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE)
                                .any { prefs.getBoolean(it, Defaults.PREF_ENABLE_SPLIT_KEYBOARD) })
                            Pending(prefixChanged(Settings.PREF_SPLIT_SPACER_SCALE_PREFIX)) { Box(Modifier.padding(start = 16.dp)) {
                                SettingsActivity.settingsContainer[Settings.PREF_SPLIT_SPACER_SCALE_PREFIX]?.Preference() } }
                    }
                    // the send/enter key on the rows under the emoji and clipboard panels: one switch for both
                    val withAction = (currentSubtype.layoutName(LayoutType.EMOJI_BOTTOM) ?: Settings.readDefaultLayoutName(LayoutType.EMOJI_BOTTOM, prefs)) == "emoji_bottom_row_with_action"
                    // advanced: the send key on the emoji and clipboard panels, long-press space to change input method
                    AdvancedBlock(advanced) {
                        Pending(keyChanged(Settings.PREF_SPACE_TO_CHANGE_LANG)) {
                            CompositionLocalProvider(LocalCompactPreferences provides true) {
                                SettingsActivity.settingsContainer[Settings.PREF_SPACE_TO_CHANGE_LANG]?.Preference() } }
                        Pending(layoutChanged(LayoutType.EMOJI_BOTTOM)) { SwitchRow(stringResource(R.string.bottom_rows_action_key), withAction) { on ->
                            preview.changed(emoji = true) // the send key shows on the emoji panel's bottom row
                            setCurrentSubtype(
                                if (on) currentSubtype.withLayout(LayoutType.EMOJI_BOTTOM, "emoji_bottom_row_with_action").withLayout(LayoutType.CLIPBOARD_BOTTOM, "clip_bottom_row_with_action")
                                else currentSubtype.withLayout(LayoutType.EMOJI_BOTTOM, "emoji_bottom_row").withLayout(LayoutType.CLIPBOARD_BOTTOM, "clip_bottom_row")
                            )
                        } }
                    }
                    // the other layouts, only when there is a choice (custom layout files; Bengali has khipro)
                    val tabletOnly = currentSubtype.locale.script() != ScriptUtils.SCRIPT_BENGALI && LayoutUtilsCustom.getLayoutFiles(LayoutType.FUNCTIONAL, ctx).isEmpty()
                    if (!tabletOnly) Pending(layoutChanged(LayoutType.FUNCTIONAL))
                        { SecondaryLayoutRow(currentSubtype, LayoutType.FUNCTIONAL, ::setCurrentSubtype,
                            builtIns = { all -> all.filter { it != "functional_keys_khipro" || currentSubtype.locale.script() == ScriptUtils.SCRIPT_BENGALI } }) }
                    for (type in listOf(LayoutType.MORE_SYMBOLS, LayoutType.NUMBER, LayoutType.NUMBER_ROW, LayoutType.NUMPAD, LayoutType.NUMPAD_LANDSCAPE, LayoutType.PHONE, LayoutType.PHONE_SYMBOLS))
                        if (LayoutUtilsCustom.getLayoutFiles(type, ctx).isNotEmpty())
                            Pending(layoutChanged(type)) { SecondaryLayoutRow(currentSubtype, type, ::setCurrentSubtype) }
                    // tablet-style bottom row (advanced on phones), then bottom row size and side padding
                    if (tabletOnly) {
                        // (a plain row, not a local composable function: that one was re-created on every recomposition
                        // and its switch replayed the thumb animation back and forth)
                        val tablet = currentSubtype.layoutName(LayoutType.FUNCTIONAL) == "functional_keys_tablet"
                        val onTablet: (Boolean) -> Unit = { on ->
                            preview.changed(emoji = false)
                            setCurrentSubtype(if (on) currentSubtype.withLayout(LayoutType.FUNCTIONAL, "functional_keys_tablet") else currentSubtype.withoutLayout(LayoutType.FUNCTIONAL))
                        }
                        val row = @Composable { Pending(layoutChanged(LayoutType.FUNCTIONAL)) {
                            SwitchRow(stringResource(R.string.bottom_row_tablet), tablet, onChange = onTablet) } }
                        if (Settings.getInstance().isTablet) row() else AdvancedBlock(advanced) { row() }
                    }
                    // advanced: bottom row size (with its padding) and side padding, moved from Appearance
                    AdvancedBlock(advanced) {
                        CompositionLocalProvider(LocalCompactPreferences provides true, LocalKeepKeyboard provides true,
                            LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) {
                        Pending(prefixChanged(Settings.PREF_BOTTOM_ROW_SCALE_PREFIX) || prefixChanged(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX)) {
                            SettingsActivity.settingsContainer[Settings.PREF_BOTTOM_ROW_SCALE_PREFIX]?.Preference() }
                        Pending(prefixChanged(Settings.PREF_SIDE_PADDING_SCALE_PREFIX)) {
                            SettingsActivity.settingsContainer[Settings.PREF_SIDE_PADDING_SCALE_PREFIX]?.Preference() }
                        }
                    }
                    // the toolbar (was its own screen, then its own group): visibility in simple mode, the rest advanced
                    CompositionLocalProvider(LocalCompactPreferences provides true, LocalKeepKeyboard provides true,
                        LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) {
                        Pending(keyChanged(Settings.PREF_TOOLBAR_VISIBILITY)) {
                            SettingsActivity.settingsContainer[Settings.PREF_TOOLBAR_VISIBILITY]?.Preference() }
                    }
                    AdvancedBlock(advanced) {
                        CompositionLocalProvider(LocalCompactPreferences provides true, LocalKeepKeyboard provides true,
                            LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) {
                            toolbarItems(prefs).filterNotNull().filter { it != Settings.PREF_TOOLBAR_VISIBILITY }.forEach {
                                if (it == SettingsWithoutKey.TOOLBAR_KEYS_ALL) {
                                    // the keys list previews on the keyboard (its toolbar opened), like the sound settings
                                    CompositionLocalProvider(LocalKeepKeyboard provides true, LocalPreviewKeyboard provides preview,
                                        LocalBottomBarTop provides bottomBarTop) {
                                        Pending(keyChanged(Settings.PREF_TOOLBAR_KEYS, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS,
                                            Settings.PREF_PINNED_TOOLBAR_KEYS)) { SettingsActivity.settingsContainer[it]?.Preference() }
                                    }
                                } else Pending(keyChanged(it)) { SettingsActivity.settingsContainer[it]?.Preference() }
                            }
                        }
                    }
                }
                // ---- typing: key-press popup, vibration, sound, per-app keyboard, localized number row
                WithBigTitle(stringResource(R.string.settings_category_input)) {
                    CompositionLocalProvider(LocalCompactPreferences provides true) {
                        (if (resumed >= 0 && feedbackChanges >= 0) preferencesInputItems(prefs, ctx) else emptyList()).filter { it !in advancedInputItems }.forEach {
                            if (it !is String) return@forEach
                            CompositionLocalProvider(LocalPendingChange provides keyChanged(it)) {
                            // the rows that appear under Vibrate / Sound when they're on sit a little in; the dialogs
                            // of the long-press delay and the sound / vibration rows keep the keyboard up to try them
                            val row: @Composable () -> Unit = {
                                if (it in dependentInputItems) Box(Modifier.padding(start = 16.dp)) { SettingsActivity.settingsContainer[it]?.Preference() }
                                else SettingsActivity.settingsContainer[it]?.Preference()
                            }
                            val shown: @Composable () -> Unit = {
                                if (it in previewDialogItems) CompositionLocalProvider(LocalKeepKeyboard provides true,
                                    LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) { row() }
                                else row()
                            }
                            // backspace's long press delay: advanced only, right below the long press delay
                            if (it == Settings.PREF_BACKSPACE_LONGPRESS_DELAY) AdvancedBlock(advanced) { shown() } else shown()
                            }
                        }
                    }
                    // (show numbers row and its symbols-page twin are in the Layout group; the localized digits are per language)
                    if (hasLocalizedNumberRow(currentSubtype.locale, ctx)) Pending(
                        currentSubtype.getExtraValueOf(ExtraValue.LOCALIZED_NUMBER_ROW) != openedSubtype.getExtraValueOf(ExtraValue.LOCALIZED_NUMBER_ROW)) {
                        val checked = currentSubtype.getExtraValueOf(ExtraValue.LOCALIZED_NUMBER_ROW)?.toBoolean()
                        SwitchRow(stringResource(R.string.localized_number_row),
                            checked ?: prefs.getBoolean(Settings.PREF_LOCALIZED_NUMBER_ROW, Defaults.PREF_LOCALIZED_NUMBER_ROW),
                            extra = { DefaultButton(checked == null) { setCurrentSubtype(currentSubtype.without(ExtraValue.LOCALIZED_NUMBER_ROW)) } },
                        ) { setCurrentSubtype(currentSubtype.with(ExtraValue.LOCALIZED_NUMBER_ROW, it.toString())) }
                    }
                    // backspace (was its own group): whole words and speeding up in simple mode; the speed-up dialog
                    // keeps the keyboard up so holding backspace can be tried
                    CompositionLocalProvider(LocalCompactPreferences provides true, LocalKeepKeyboard provides true,
                        LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) {
                        Pending(keyChanged(Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS)) {
                            SettingsActivity.settingsContainer[Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS]?.Preference() }
                        Pending(keyChanged(Settings.PREF_BACKSPACE_SPEED_UP, Settings.PREF_BACKSPACE_SPEED_UP_AFTER, Settings.PREF_BACKSPACE_TOP_INTERVAL)) {
                            SettingsActivity.settingsContainer[Settings.PREF_BACKSPACE_SPEED_UP]?.Preference() }
                    }
                    // (the symbol switches — hide on the number row / other keys, long-press dots — are on Appearance)
                    AdvancedBlock(advanced) {
                        CompositionLocalProvider(LocalCompactPreferences provides true, LocalKeepKeyboard provides true,
                            LocalPreviewKeyboard provides preview, LocalBottomBarTop provides bottomBarTop) {
                            // backspace, advanced: swiping left, undoing an autocorrection (a tap after a swipe: on Swiping)
                            Pending(keyChanged(Settings.PREF_DELETE_SWIPE, Settings.PREF_DELETE_SWIPE_SPEED)) {
                                SettingsActivity.settingsContainer[Settings.PREF_DELETE_SWIPE]?.Preference() }
                            if (prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION))
                                Pending(keyChanged(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT)) {
                                    SettingsActivity.settingsContainer[Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT]?.Preference() }
                            advancedInputItems.forEach {
                                val pending = if (it == SettingsWithoutKey.ABC_AFTER) abcAfterKeys.any { k -> keyChanged(k.first) } else keyChanged(it)
                                Pending(pending) { SettingsActivity.settingsContainer[it]?.Preference() }
                            }
                        }
                    }
                }
                // ---- keys & popups: the keys (QWERTY, AZERTY, …, or your own) and what holding them offers (every row is
                // advanced, so the group is too)
                AdvancedBlock(advanced) { WithBigTitle(stringResource(R.string.key_popups_group)) {
                    Pending(layoutChanged(LayoutType.MAIN)) { MainLayoutRow(currentSubtype, customMainLayouts) { setCurrentSubtype(it) } }
                    // preset popup layouts, customize popups, customize keys and popups with JSON (all advanced);
                    // italic as a whole when any of it changed
                    Pending(keyChanged("key_popups", "key_popup_set_selected", "key_popup_sets", Settings.PREF_SYMBOL_POPUP_MAP)
                            || currentSubtype.getExtraValueOf(ExtraValue.MORE_POPUPS) != openedSubtype.getExtraValueOf(ExtraValue.MORE_POPUPS)
                            || layoutChanged(LayoutType.SYMBOLS) || changedFolders.any { it != "main" } || prefixChanged(Settings.PREF_LAYOUT_PREFIX)) {
                        CompositionLocalProvider(LocalKeepKeyboard provides true, LocalPreviewKeyboard provides preview,
                            LocalBottomBarTop provides bottomBarTop) { KeyPopupsSection(currentSubtype) { setCurrentSubtype(it) } } }
                    run {
                        Pending(keyChanged(Settings.PREF_SHOW_TLD_POPUP_KEYS)) {
                            PrefSwitchRow(Settings.PREF_SHOW_TLD_POPUP_KEYS, Defaults.PREF_SHOW_TLD_POPUP_KEYS, R.string.show_tld_popup_keys) { holdPreview(); reloadPreview() } }
                        Pending(keyChanged(Settings.PREF_REMOVE_REDUNDANT_POPUPS)) {
                            PrefSwitchRow(Settings.PREF_REMOVE_REDUNDANT_POPUPS, Defaults.PREF_REMOVE_REDUNDANT_POPUPS, R.string.remove_redundant_popups) { holdPreview(); reloadPreview() } }
                    }
                } }
                // ---- the keyboard's own undo / redo (from Others): how far back, and a word or a character per press
                AdvancedBlock(advanced) { WithBigTitle(stringResource(R.string.undo_redo_group)) {
                    CompositionLocalProvider(LocalCompactPreferences provides true) {
                        listOf(Settings.PREF_UNDO_HISTORY_LENGTH, Settings.PREF_UNDO_UNIT, Settings.PREF_REDO_UNIT).forEach {
                            Pending(keyChanged(it)) { SettingsActivity.settingsContainer[it]?.Preference() } }
                    }
                } }
                // (its own tinted block: the screen's gap between blocks shows as a thin line, like above Backspace)
                AdvancedBlock(advanced) {
                    WithBigTitle(stringResource(R.string.settings_category_clipboard_history)) {
                        CompositionLocalProvider(LocalCompactPreferences provides true) {
                            clipboardHistoryItems(prefs).filterIsInstance<String>().forEach {
                                Pending(keyChanged(it)) { SettingsActivity.settingsContainer[it]?.Preference() } }
                        }
                    }
                }
            }
            }
        }
        if (showSecondaryLocaleDialog)
            ListPickerDialog(
                onDismissRequest = { showSecondaryLocaleDialog = false },
                onItemSelected = { locale ->
                    LanguagePriority.set(prefs, locale, LanguagePriority.MEDIUM)
                    val secondaries = getSecondaryLocales(currentSubtype.extraValues) + locale
                    setCurrentSubtype(currentSubtype.with(ExtraValue.SECONDARY_LOCALES, secondaries.joinToString(Separators.KV) { it.toLanguageTag() }))
                },
                title = { Text(stringResource(R.string.add_language)) },
                items = availableLocalesForScript.filter { it != currentSubtype.locale && it !in getSecondaryLocales(currentSubtype.extraValues) },
                getItemName = { it.localizedDisplayName(ctx.resources) },
                showRadioButtons = false,
            )
    }
}


@Composable
private fun MainLayoutRow(
    currentSubtype: SettingsSubtype,
    customLayouts: List<String>,
    setCurrentSubtype: (SettingsSubtype) -> Unit,
) {
    val ctx = LocalContext.current
    Column {
        val appLayouts = LayoutUtils.getAvailableLayouts(LayoutType.MAIN, ctx, currentSubtype.locale)
        var showAddLayoutDialog by remember { mutableStateOf(false) }
        var showLayoutEditDialog: Pair<String, String?>? by remember { mutableStateOf(null) }
        var keysToConfirm: String? by remember { mutableStateOf(null) } // replacing an unsaved layout: asked first
        fun pickKeys(layout: String) {
            // if the locale defaults to qwerty, use it as implicit default to avoid creating unnecessary additional subtypes
            if (layout == SubtypeLocaleUtils.QWERTY
                && SubtypeSettings.getResourceSubtypesForLocale(currentSubtype.locale).any { it.mainLayoutName() == null })
                setCurrentSubtype(currentSubtype.withoutLayout(LayoutType.MAIN))
            else setCurrentSubtype(currentSubtype.withLayout(LayoutType.MAIN, layout))
        }
        keysToConfirm?.let { layout ->
            ConfirmationDialog(
                onDismissRequest = { keysToConfirm = null },
                title = { Text(stringResource(R.string.layout_keys)) },
                content = { Text(stringResource(R.string.unsaved_layout_will_be_lost)) },
                onConfirmed = { pickKeys(layout); keysToConfirm = null },
            )
        }
        DropDownField(
            items = appLayouts + customLayouts,
            selectedItem = currentSubtype.mainLayoutName() ?: SubtypeLocaleUtils.QWERTY,
            fieldContent = { selected -> Column {
                Text(stringResource(R.string.layout_keys), style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (helium314.keyboard.settings.preferences.LocalPendingChange.current) androidx.compose.ui.text.font.FontStyle.Italic else null)
                Text(SubtypeLocaleUtils.getLayoutDisplayNameInSystemLocale(selected, currentSubtype.locale),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            onSelected = { layout ->
                // other keys than the keyboard's unsaved layout: that one is lost, asked first
                if (currentSubtype.mainLayoutName()?.let { LayoutUtilsCustom.isPrivateLayout(it) } == true && layout != currentSubtype.mainLayoutName())
                    keysToConfirm = layout
                else pickKeys(layout)
            },
            extraButton = {
                IconButton({ showAddLayoutDialog = true }) // (a smaller plus: the row's text is small next to it)
                { Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.button_title_add_custom_layout),
                    Modifier.size(18.dp)) }
            }
        ) {
            var showLayoutDeleteDialog by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.widthIn(min = 200.dp).fillMaxWidth()
            ) {
                Text(SubtypeLocaleUtils.getLayoutDisplayNameInSystemLocale(it, currentSubtype.locale))
                Row (verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ showLayoutEditDialog = it to null }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_layout)) }
                    if (it in customLayouts && !LayoutUtilsCustom.isPrivateLayout(it))
                        IconButton({ showLayoutDeleteDialog = true }) { Icon(painterResource(R.drawable.ic_bin), stringResource(R.string.delete)) }
                }
            }
            if (showLayoutDeleteDialog) {
                val others = SubtypeSettings.getAdditionalSubtypes().filter { st -> st.mainLayoutName() == it }
                    .any() // (this one included: every keyboard using it keeps the keys)
                ConfirmationDialog(
                    onDismissRequest = { showLayoutDeleteDialog = false },
                    confirmButtonText = stringResource(R.string.delete),
                    title = { Text(stringResource(R.string.delete_layout, LayoutUtilsCustom.getDisplayName(it))) },
                    content = { if (others) Text(stringResource(R.string.layout_in_use)) },
                    onConfirmed = {
                        // this keyboard keeps the keys as its own unnamed copy (the others too, in deleteLayout)
                        if (it == currentSubtype.mainLayoutName())
                            setCurrentSubtype(currentSubtype.withLayout(LayoutType.MAIN, LayoutUtilsCustom.makePrivateCopy(it, LayoutType.MAIN, ctx)))
                        LayoutUtilsCustom.deleteLayout(it, LayoutType.MAIN, ctx)
                        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                    }
                )
            }
        }
        if (showLayoutEditDialog != null) {
            val layoutName = showLayoutEditDialog!!.first
            val startContent = showLayoutEditDialog?.second
                ?: if (layoutName in appLayouts) LayoutUtils.getContentWithPlus(layoutName, currentSubtype.locale, ctx)
                else null
            LayoutEditDialog(
                onDismissRequest = { showLayoutEditDialog = null },
                keyboard = currentSubtype,
                layoutType = LayoutType.MAIN,
                initialLayoutName = layoutName,
                startContent = startContent,
                locale = currentSubtype.locale,
                isNameValid = { it !in customLayouts },
                onEdited = {
                    if (layoutName !in customLayouts // edited a built-in layout, set new one as current
                        || layoutName != it && layoutName == currentSubtype.mainLayoutName() // layout name for current subtype changed
                        )
                        setCurrentSubtype(currentSubtype.withLayout(LayoutType.MAIN, it))
                }
            )
        }
        if (showAddLayoutDialog) {
            val wikiLink = stringResource(R.string.dictionary_link_text).withHtmlLink(Links.LAYOUT_WIKI_URL)
            val layoutText = stringResource(R.string.message_add_custom_layout, wikiLink).htmlToAnnotated()
            val discussionLink = stringResource(R.string.discussion_section_link).withHtmlLink(Links.CUSTOM_LAYOUTS)
            val discussionSectionText = stringResource(R.string.get_layouts_message, discussionLink).htmlToAnnotated()
            val annotated = layoutText + AnnotatedString("\n") + discussionSectionText

            ConfirmationDialog(
                onDismissRequest = { showAddLayoutDialog = false },
                title = { Text(stringResource(R.string.button_title_add_custom_layout)) },
                content = { Text(annotated) },
                // an empty editor: a layout comes in by pasting it there (no files)
                onConfirmed = { showLayoutEditDialog = "new layout" to "" },
            )
        }
    }
}

private fun getAvailableSecondaryLocales(context: Context, mainLocale: Locale): List<Locale> =
    getDictionaryLocales(context).filter { it != mainLocale && it.script() == mainLocale.script() }

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            SubtypeScreen(SettingsSubtype(Locale.ENGLISH, "")) { }
        }
    }
}

/** One secondary layout: a drop-down of the built-in variants (filtered) and custom files, with edit and default. */
@Composable
private fun SecondaryLayoutRow(
    currentSubtype: SettingsSubtype,
    type: LayoutType,
    setCurrentSubtype: (SettingsSubtype) -> Unit,
    builtIns: (List<String>) -> List<String> = { it },
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    WithSmallTitle(stringResource(type.displayNameId)) {
        val explicitLayout = currentSubtype.layoutName(type)
        val layout = explicitLayout ?: Settings.readDefaultLayoutName(type, prefs)
        val defaultLayouts = builtIns(LayoutUtils.getAvailableLayouts(type, ctx).toList())
        val customLayouts = LayoutUtilsCustom.listedLayoutNames(type, ctx, current = explicitLayout)
        DropDownField(
            items = defaultLayouts + customLayouts,
            selectedItem = layout,
            onSelected = { setCurrentSubtype(currentSubtype.withLayout(type, it)) },
            extraButton = { DefaultButton(explicitLayout == null) { setCurrentSubtype(currentSubtype.withoutLayout(type)) } },
        ) {
            val displayName = if (LayoutUtilsCustom.isCustomLayout(it)) LayoutUtilsCustom.getDisplayName(it)
                else it.getStringResourceOrName("layout_", ctx)
            var showLayoutEditDialog by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(displayName)
                if (LayoutUtilsCustom.isCustomLayout(it))
                    IconButton({ showLayoutEditDialog = true }) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_layout)) }
            }
            if (showLayoutEditDialog)
                LayoutEditDialog(onDismissRequest = { showLayoutEditDialog = false }, layoutType = type, initialLayoutName = it, keyboard = currentSubtype, isNameValid = null)
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, summary: String? = null, extra: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = ROW_HEIGHT).padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
            Text(title, fontStyle = if (LocalPendingChange.current) androidx.compose.ui.text.font.FontStyle.Italic else null)
            if (summary != null)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
        extra?.invoke()
    }
}

/** The Input items shown only in advanced mode, last in the group (see [AdvancedBlock]). */
/** Settings whose dialogs keep the preview keyboard up: to hear the key sound or feel the vibration while choosing it, to try the long-press delay. */
private val previewDialogItems = setOf(Settings.PREF_KEYPRESS_SOUND, Settings.PREF_KEYPRESS_SOUND_VOLUME, Settings.PREF_VIBRATION_DURATION_SETTINGS,
    Settings.PREF_KEY_LONGPRESS_TIMEOUT, Settings.PREF_BACKSPACE_LONGPRESS_DELAY)

/** Switches that bring the preview keyboard up for a moment when flipped: their effect is seen (or felt, or heard) on a key press. */
private val previewedSwitches = listOf(
    Settings.PREF_SHOW_NUMBER_ROW, Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, Settings.PREF_ENABLE_SPLIT_KEYBOARD,
    Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED, Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE,
    Settings.PREF_SHOW_EMOJI_KEY, Settings.PREF_SHOW_VOICE_KEY,
    Settings.PREF_POPUP_ON, Settings.PREF_VIBRATE_ON, Settings.PREF_SOUND_ON,
    Settings.PREF_BACKSPACE_HOLD_DELETES_WORDS, Settings.PREF_BACKSPACE_SPEED_UP, Settings.PREF_DELETE_SWIPE,
    Settings.PREF_LONG_PRESS_SYMBOL_ACTION, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
)

private val advancedInputItems = listOf(Settings.PREF_LONG_PRESS_SYMBOL_ACTION, SettingsWithoutKey.ABC_AFTER) // (emoji descriptions: Appearance, Emoji group)

/** Advanced items on a slightly different background, so toggling the mode shows what it adds (last in the Input
 *  group, in place elsewhere). */
@Composable
fun AdvancedBlock(visible: Boolean = true, content: @Composable () -> Unit) = helium314.keyboard.settings.AdvancedReveal(visible) {
    // edge to edge: widened by the screen's side margin, the rows inside keep their place; unfolds with the mode switch
    Column(Modifier
        .layout { measurable, constraints ->
            val margin = SCREEN_MARGIN.roundToPx()
            val width = constraints.maxWidth + 2 * margin
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-margin, 0) }
        }
        .background(advancedTint())
        .padding(horizontal = SCREEN_MARGIN)) { content() }
}

/** The side margin of this screen's content. */
private val SCREEN_MARGIN = 12.dp

/** The height of every row on this screen (a switch row: switch plus 4 dp above and below). */
val ROW_HEIGHT = 56.dp

/** A switch bound to a boolean preference (per keyboard when settings are separate). */
@Composable
private fun PrefSwitchRow(key: String, default: Boolean, titleId: Int, summaryId: Int? = null, inverted: Boolean = false, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    helium314.keyboard.settings.KnownDefaults.note(key, default)
    // read each time (a Discard puts values back behind this row's back)
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) Log.v("irrelevant", "recompose on preference change")
    val checked = prefs.getBoolean(key, default) xor inverted
    SwitchRow(stringResource(titleId), checked, summaryId?.let { stringResource(it) }) {
        prefs.edit { putBoolean(key, it xor inverted) }
        onChanged()
    }
}

private fun reloadPreview() = KeyboardSwitcher.getInstance().setThemeNeedsReload()

/** Hold the preview's space for a moment, so a reload doesn't shorten the list and lose the scroll position. */
private fun holdPreviewSpace(set: (Boolean) -> Unit) = set(true)

/** [content] with its title in italics while [pending]: changed since the screen opened, not kept yet. */
@Composable
private fun Pending(pending: Boolean, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPendingChange provides pending, content = content)

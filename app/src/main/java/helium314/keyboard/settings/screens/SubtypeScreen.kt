package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import helium314.keyboard.latin.utils.NextScreenIcon
import androidx.compose.ui.draw.rotate
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.RichInputMethodManager
import androidx.compose.foundation.clickable
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
import helium314.keyboard.settings.layoutFilePicker
import helium314.keyboard.settings.layoutIntent
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
    var currentSubtypeString by rememberSaveable { mutableStateOf(initialSubtype.toPref()) }
    val currentSubtype = currentSubtypeString.toSettingsSubtype()
    fun setCurrentSubtype(subtype: SettingsSubtype) {
        SubtypeUtilsAdditional.changeAdditionalSubtype(currentSubtype, subtype, ctx)
        currentSubtypeString = subtype.toPref()
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
    // the position survives the rebuild a change causes; restored after the new content is laid out
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var keptScroll by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scrollState.value) { if (scrollState.value != 0) keptScroll = scrollState.value }
    LaunchedEffect(currentSubtypeString, keptScroll) {
        if (keptScroll != 0 && scrollState.value != keptScroll) {
            snapshotFlow { scrollState.maxValue }.first { it >= keptScroll }
            scrollState.scrollTo(keptScroll)
        }
    }
    val tryIt = remember { TryItState() }
    val customMainLayouts = LayoutUtilsCustom.getLayoutFiles(LayoutType.MAIN, ctx, currentSubtype.locale).map { it.name }
    SearchScreen(
        onClickBack = onClickBack,
        icon = { if (SubtypeSettings.getEnabledSubtypes(true).size > 1 && SubtypeSettings.isEnabled(currentSubtype.toAdditionalSubtype())) DeleteButton {
            if (currentSubtype.isAdditionalSubtype(prefs)) SubtypeUtilsAdditional.removeAdditionalSubtype(ctx, currentSubtype.toAdditionalSubtype())
            SubtypeSettings.removeEnabledSubtype(ctx, currentSubtype.toAdditionalSubtype())
            KeyboardProfiles.onKeyboardDeleted(ctx.realPrefs(), currentSubtype)
            onClickBack()
        } },
        title = { Text(keyboardName(currentSubtype, ctx)) }, // the same name as in the keyboards list
        itemContent = { },
        filteredItems = { emptyList<String>() }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = { TryItBar(currentSubtype, tryIt) }
        ) { innerPadding ->
            Column(
                modifier = Modifier.verticalScroll(scrollState).padding(horizontal = 12.dp)
                    .then(Modifier.padding(innerPadding)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WithBigTitle(stringResource(R.string.key_popups_title)) {
                    KeyPopupsSection(currentSubtype) { setCurrentSubtype(it) }
                }
                if (hasLocalizedNumberRow(currentSubtype.locale, ctx)) {
                    val checked = currentSubtype.getExtraValueOf(ExtraValue.LOCALIZED_NUMBER_ROW)?.toBoolean()
                    WithSmallTitle(stringResource(R.string.number_row)) {
                        ActionRow {
                            Text(stringResource(R.string.localized_number_row),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            )
                            Switch(
                                checked = checked ?: prefs.getBoolean(
                                    Settings.PREF_LOCALIZED_NUMBER_ROW,
                                    Defaults.PREF_LOCALIZED_NUMBER_ROW
                                ),
                                onCheckedChange = {
                                    setCurrentSubtype(currentSubtype.with(ExtraValue.LOCALIZED_NUMBER_ROW, it.toString()))
                                }
                            )
                            DefaultButton(checked == null) {
                                setCurrentSubtype(currentSubtype.without(ExtraValue.LOCALIZED_NUMBER_ROW))
                            }
                        }
                    }
                }
                // ---- number row and hints, in one place
                WithBigTitle(stringResource(R.string.number_row_and_hints)) {
                    PrefSwitchRow(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW, R.string.number_row_summary) { reloadPreview() }
                    // two independent hint switches; the popups behind long-press stay either way
                    PrefSwitchRow(Settings.PREF_SHOW_NUMBER_ROW_HINTS, Defaults.PREF_SHOW_NUMBER_ROW_HINTS, R.string.hints_number_row) { reloadPreview() }
                    PrefSwitchRow(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS, R.string.hints_other_keys) { reloadPreview() }
                }
                // ---- the layout, with the other layouts of this keyboard under it
                WithBigTitle(stringResource(R.string.keyboard_layout_set)) {
                    MainLayoutRow(currentSubtype, customMainLayouts) { setCurrentSubtype(it) }
                // ---- the other layouts of this keyboard, each with its own treatment
                    // bottom row: a tablet switch when that is the only alternative; the full choice for Bengali (khipro) or custom files
                    if (currentSubtype.locale.script() != ScriptUtils.SCRIPT_BENGALI && LayoutUtilsCustom.getLayoutFiles(LayoutType.FUNCTIONAL, ctx).isEmpty())
                        SwitchRow(stringResource(R.string.bottom_row_tablet), currentSubtype.layoutName(LayoutType.FUNCTIONAL) == "functional_keys_tablet") { on ->
                            setCurrentSubtype(if (on) currentSubtype.withLayout(LayoutType.FUNCTIONAL, "functional_keys_tablet") else currentSubtype.withoutLayout(LayoutType.FUNCTIONAL))
                        }
                    else
                        SecondaryLayoutRow(currentSubtype, LayoutType.FUNCTIONAL, ::setCurrentSubtype,
                            builtIns = { all -> all.filter { it != "functional_keys_khipro" || currentSubtype.locale.script() == ScriptUtils.SCRIPT_BENGALI } })
                    // the send/enter key on the rows under the emoji and clipboard panels: one switch for both
                    val withAction = currentSubtype.layoutName(LayoutType.EMOJI_BOTTOM) == "emoji_bottom_row_with_action"
                    SwitchRow(stringResource(R.string.bottom_rows_action_key), withAction) { on ->
                        setCurrentSubtype(
                            if (on) currentSubtype.withLayout(LayoutType.EMOJI_BOTTOM, "emoji_bottom_row_with_action").withLayout(LayoutType.CLIPBOARD_BOTTOM, "clip_bottom_row_with_action")
                            else currentSubtype.withoutLayout(LayoutType.EMOJI_BOTTOM).withoutLayout(LayoutType.CLIPBOARD_BOTTOM)
                        )
                    }
                    // everything else only when there is a choice (a custom layout file exists)
                    // (the number pad's popups are in the popup editor; these rows only pick layout files)
                    for (type in listOf(LayoutType.MORE_SYMBOLS, LayoutType.NUMBER, LayoutType.NUMBER_ROW, LayoutType.NUMPAD, LayoutType.NUMPAD_LANDSCAPE, LayoutType.PHONE, LayoutType.PHONE_SYMBOLS))
                        if (LayoutUtilsCustom.getLayoutFiles(type, ctx).isNotEmpty())
                            SecondaryLayoutRow(currentSubtype, type, ::setCurrentSubtype)
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


// from ReorderSwitchPreference
@Composable
private fun PopupOrderDialog(
    onDismissRequest: () -> Unit,
    initialValue: String,
    onConfirmed: (String?) -> Unit,
    title: String,
    showDefault: Boolean
) {
    class KeyAndState(var name: String, var state: Boolean)
    val items = initialValue.split(Separators.ENTRY).map {
        KeyAndState(it.substringBefore(Separators.KV), it.substringAfter(Separators.KV).toBoolean())
    }
    val ctx = LocalContext.current
    ReorderDialog(
        onConfirmed = { reorderedItems ->
            val value = reorderedItems.joinToString(Separators.ENTRY) { it.name + Separators.KV + it.state }
            onConfirmed(value)
        },
        onDismissRequest = onDismissRequest,
        onNeutral = { onDismissRequest(); onConfirmed(null) },
        neutralButtonText = if (showDefault) stringResource(R.string.button_default) else null,
        items = items,
        title = { Text(title) },
        displayItem = { item ->
            var checked by rememberSaveable { mutableStateOf(item.state) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyboardIconsSet.instance.GetIconOrEmpty(item.name)
                val text = item.name.lowercase().getStringResourceOrName("popup_keys_", ctx)
                Text(text, Modifier.weight(1f))
                Switch(
                    checked = checked,
                    onCheckedChange = { item.state = it; checked = it }
                )
            }
        },
        getKey = { it.name }
    )
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
        val layoutPicker = layoutFilePicker { content, name ->
            showLayoutEditDialog = (name ?: "new layout") to content
        }
        DropDownField(
            items = appLayouts + customLayouts,
            selectedItem = currentSubtype.mainLayoutName() ?: SubtypeLocaleUtils.QWERTY,
            onSelected = { layout ->
                // if the locale defaults to qwerty, use it as implicit default to avoid creating unnecessary additional subtypes
                if (layout == SubtypeLocaleUtils.QWERTY
                    && SubtypeSettings.getResourceSubtypesForLocale(currentSubtype.locale).any { it.mainLayoutName() == null })
                    setCurrentSubtype(currentSubtype.withoutLayout(LayoutType.MAIN))
                else setCurrentSubtype(currentSubtype.withLayout(LayoutType.MAIN, layout))
            },
            extraButton = {
                IconButton({ showAddLayoutDialog = true })
                { Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.button_title_add_custom_layout)) }
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
                    if (it in customLayouts)
                        IconButton({ showLayoutDeleteDialog = true }) { Icon(painterResource(R.drawable.ic_bin), stringResource(R.string.delete)) }
                }
            }
            if (showLayoutDeleteDialog) {
                val others = SubtypeSettings.getAdditionalSubtypes().filter { st -> st.mainLayoutName() == it }
                    .any { st -> st.toSettingsSubtype() != currentSubtype }
                ConfirmationDialog(
                    onDismissRequest = { showLayoutDeleteDialog = false },
                    confirmButtonText = stringResource(R.string.delete),
                    title = { Text(stringResource(R.string.delete_layout, LayoutUtilsCustom.getDisplayName(it))) },
                    content = { if (others) Text(stringResource(R.string.layout_in_use)) },
                    onConfirmed = {
                        if (it == currentSubtype.mainLayoutName()) {
                            // similar to what is done in SubtypeSettings.onRenameLayout
                            val defaultLayout = SubtypeSettings.getResourceSubtypesForLocale(currentSubtype.locale).firstOrNull()?.mainLayoutName()
                            val newSubtype = if (defaultLayout == null) currentSubtype.withoutLayout(LayoutType.MAIN)
                                else currentSubtype.withLayout(LayoutType.MAIN, defaultLayout)
                            setCurrentSubtype(newSubtype)
                        }
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
                onConfirmed = { showLayoutEditDialog = "new layout" to "" },
                neutralButtonText = stringResource(R.string.button_load_custom),
                onNeutral = {
                    showAddLayoutDialog = false
                    layoutPicker.launch(layoutIntent)
                }
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
        val customLayouts = LayoutUtilsCustom.getLayoutFiles(type, ctx).map { it.name }
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
                LayoutEditDialog(onDismissRequest = { showLayoutEditDialog = false }, layoutType = type, initialLayoutName = it, isNameValid = null)
        }
    }
}

/** A folding group of layout rows; opening it can bring up the matching pad in the try-it field. */
@Composable
private fun FoldableLayoutGroup(titleId: Int, onOpen: () -> Unit, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { open = !open; if (open) onOpen() }.padding(vertical = 8.dp)) {
        Text(stringResource(titleId), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_arrow_left), null, Modifier.rotate(if (open) 90f else -90f))
    }
    if (open) Column(Modifier.padding(start = 12.dp)) { content() }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp)) {
        Text(title, modifier = Modifier.weight(1f).padding(start = 10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A switch bound to a boolean preference (per keyboard when settings are separate). */
@Composable
private fun PrefSwitchRow(key: String, default: Boolean, titleId: Int, onChanged: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    var checked by remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
    SwitchRow(stringResource(titleId), checked) {
        checked = it
        prefs.edit { putBoolean(key, it) }
        onChanged()
    }
}

private fun reloadPreview() = KeyboardSwitcher.getInstance().setThemeNeedsReload()

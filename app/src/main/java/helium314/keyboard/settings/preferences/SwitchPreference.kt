// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Links
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.htmlToAnnotated
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.withHtmlLink
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.InfoDialog
import androidx.core.content.edit

@Composable
fun SwitchPreference(
    setting: Setting,
    default: Boolean,
    allowCheckedChange: (Boolean) -> Boolean = { true },
    inverted: Boolean = false, // the switch shows the opposite of the stored value ("Hide …" for a "show" preference)
    dimmed: Boolean = false, // greyed while something outside the app keeps it from working; it still switches
    onCheckedChange: (Boolean) -> Unit = { }
) {
    SwitchPreference(
        name = setting.title,
        description = setting.description,
        key = setting.key,
        default = default,
        allowCheckedChange = allowCheckedChange,
        inverted = inverted,
        dimmed = dimmed,
        onCheckedChange = onCheckedChange
    )
}

@Composable
fun SwitchPreference(
    name: String,
    modifier: Modifier = Modifier,
    key: String,
    default: Boolean,
    description: String? = null,
    allowCheckedChange: (Boolean) -> Boolean = { true }, // true means ok, usually for showing some dialog
    inverted: Boolean = false,
    dimmed: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = { },
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    helium314.keyboard.settings.KnownDefaults.note(key, default)
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    var value = prefs.getBoolean(key, default) xor inverted
    fun switched(newValue: Boolean) {
        if (!allowCheckedChange(newValue)) {
            value = !newValue
            return
        }
        value = newValue
        prefs.edit { putBoolean(key, newValue xor inverted) }
        onCheckedChange(newValue xor inverted)
    }
    Preference(
        name = name,
        onClick = { switched(!value) },
        modifier = modifier,
        description = description
    ) {
        Switch(
            checked = value,
            onCheckedChange = { switched(it) },
            modifier = if (dimmed) Modifier.alpha(0.38f) else Modifier,
        )
    }
}

@Composable
fun SwitchPreferenceWithEmojiDictWarning(setting: Setting, default: Boolean) {
    val context = LocalContext.current
    var showWarningDialog by rememberSaveable { mutableStateOf(false) }
    val hasEmojiDict = DictionaryInfoUtils.getLocalesWithEmojiDicts(context).isNotEmpty()
    // without an emoji dictionary there is nothing to show: the switch stays off (a stored "on" is reset) and
    // turning it on only explains where to get the dictionary
    if (!hasEmojiDict && context.prefs().getBoolean(setting.key, false))
        context.prefs().edit { putBoolean(setting.key, false) }
    SwitchPreference(setting, default && hasEmojiDict,
        allowCheckedChange = { on -> if (on && !hasEmojiDict) { showWarningDialog = true; false } else true })
    if (showWarningDialog) {
        // emoji_dictionary_required contains "%s" since we didn't supply a formatArg
        val link = stringResource(R.string.dictionary_link_text).withHtmlLink(Links.DICTIONARY_URL + Links.DICTIONARY_DOWNLOAD_SUFFIX.replace("raw", "src")
            + Links.DICTIONARY_EMOJI_CLDR_SUFFIX)
        val message = stringResource(R.string.emoji_dictionary_required, link)
        InfoDialog(message.htmlToAnnotated(), onDismissRequest = { showWarningDialog = false })
    }
}

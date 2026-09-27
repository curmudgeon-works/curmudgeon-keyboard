// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.KeyPopupOverrides
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.folder
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.io.File

/**
 * Factory reset: every setting back to its default, and optionally the keyboards and languages, the learned words,
 * the clipboard history and the user's own layouts, popup layouts and added dictionaries.
 */
@Composable
fun FactoryResetPreference(setting: Setting) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Preference(name = setting.title, description = setting.description, onClick = { showDialog = true })
    if (!showDialog) return
    var keyboards by rememberSaveable { mutableStateOf(true) }
    var learnedWords by rememberSaveable { mutableStateOf(false) }
    var clipboard by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    ThreeButtonAlertDialog(
        onDismissRequest = { showDialog = false },
        onConfirmed = {
            factoryReset(ctx, keyboards, learnedWords, clipboard, custom)
            Toast.makeText(ctx, R.string.factory_reset_done, Toast.LENGTH_LONG).show()
        },
        title = { Text(stringResource(R.string.factory_reset)) },
        confirmFirst = true,
        scrollContent = true,
        content = {
            Column {
                Text(stringResource(R.string.factory_reset_message))
                @Composable fun CheckRow(text: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp)) {
                        Checkbox(checked = checked, onCheckedChange = onChange)
                        Text(stringResource(text))
                    }
                CheckRow(R.string.factory_reset_keyboards, keyboards) { keyboards = it }
                CheckRow(R.string.factory_reset_learned_words, learnedWords) { learnedWords = it }
                CheckRow(R.string.factory_reset_clipboard, clipboard) { clipboard = it }
                CheckRow(R.string.factory_reset_custom, custom) { custom = it }
                Text(stringResource(R.string.factory_reset_backup_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
}

private fun factoryReset(ctx: Context, keyboards: Boolean, learnedWords: Boolean, clipboard: Boolean, custom: Boolean) {
    val prefs = ctx.realPrefs()
    // what survives the settings reset: the version (else every upgrade step runs again), and what isn't being reset
    // (the defaults flag too: after a reset the defaults apply, the upgrade mustn't turn sound / vibration off again)
    fun keep(key: String): Boolean = key == Settings.PREF_VERSION_CODE || key == "defaults_feedback_on_done"
        || (!keyboards && (key == Settings.PREF_ENABLED_SUBTYPES || key == Settings.PREF_ADDITIONAL_SUBTYPES
            || key == Settings.PREF_SELECTED_SUBTYPE || LanguagePriority.isLanguageKey(key)))
        || (!custom && (key == KeyPopupOverrides.PREF || key == KeyPopupOverrides.PREF_SETS
            || key == KeyPopupOverrides.PREF_SELECTED_SET || key.startsWith(Settings.PREF_LAYOUT_PREFIX)))
    Settings.getInstance().stopListener()
    prefs.edit { prefs.all.keys.filterNot(::keep).forEach { remove(it) } }
    KeyboardProfiles.editingId = KeyboardProfiles.SHARED
    if (learnedWords) PersonalizationHelper.removeAllUserHistoryDictionaries(ctx)
    if (clipboard) ClipboardDao.getInstance(ctx)?.clear()
    if (custom) {
        val filesDir = DeviceProtectedUtils.getFilesDir(ctx)
        for (type in LayoutType.entries) File(filesDir, type.folder).deleteRecursively()
        // dictionaries the user added (the built-in ones are in the app itself)
        DictionaryInfoUtils.getCacheDirectories(ctx).forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }?.forEach { it.delete() }
        }
        LayoutUtilsCustom.onLayoutFileChanged()
    }
    Settings.getInstance().startListener()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    GestureDecoderVocabulary.clear()
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

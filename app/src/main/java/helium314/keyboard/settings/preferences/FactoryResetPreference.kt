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
import helium314.keyboard.latin.personalization.LearnedPools
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStores
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
            val app = ctx.applicationContext
            startFactoryReset(ctx, keyboards, learnedWords, clipboard, custom) {
                Toast.makeText(app, R.string.factory_reset_done, Toast.LENGTH_LONG).show()
            }
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

/**
 * The reset, started from the dialog: keyboards with their own learned words go back to one shared set (the setting's
 * default, which the reset brings back), put together first so nothing is lost on the way. That reads every store, so it
 * runs on the app's background executor (review 2026-10-06: it froze the screen), which outlives the dialog (re-review:
 * a task started in the dialog's scope was cancelled when the dialog closed, and nothing was reset); the reset itself
 * then runs on the main thread, and [onDone] after it.
 */
internal fun startFactoryReset(ctx: Context, keyboards: Boolean, learnedWords: Boolean, clipboard: Boolean, custom: Boolean,
                               io: LearnedStoreIo = LearnedStoreIo.Native, onDone: () -> Unit) {
    val real = ctx.realPrefs()
    val filesDir = ctx.filesDir
    helium314.keyboard.latin.utils.ExecutorUtils.getBackgroundExecutor(helium314.keyboard.latin.utils.ExecutorUtils.KEYBOARD).execute {
        // (share notes that no pool is left to mark before a copy, kept through the reset: else an emptied pool counted
        // as filled, and a keyboard on a reused id started empty)
        if (!LearnedStores.isShared(real) && filesDir != null) LearnedPools.share(filesDir, io, real)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            factoryReset(ctx, keyboards, learnedWords, clipboard, custom)
            onDone()
        }
    }
}

/**
 * What survives the settings reset: the version and every one-time step's flag (else they run again on a reset phone:
 * review 2026-10-06, the 0.3.002 step brought back the 0.5% / 0.75% gaps), and what isn't being reset: the keyboards
 * ([keyboards] unticked), your own layouts, popup sets and saved themes and Layouts ([custom] unticked).
 */
internal fun keptOnReset(key: String, keyboards: Boolean, custom: Boolean): Boolean =
    key == Settings.PREF_VERSION_CODE || KeyboardProfiles.isOneTimeFlag(key)
        || (!keyboards && (key == Settings.PREF_ENABLED_SUBTYPES || key == Settings.PREF_ADDITIONAL_SUBTYPES
            || key == Settings.PREF_SELECTED_SUBTYPE || LanguagePriority.isLanguageKey(key)))
        || (!custom && (key == KeyPopupOverrides.PREF || key == KeyPopupOverrides.PREF_SETS
            || key == KeyPopupOverrides.PREF_SELECTED_SET || key.startsWith(Settings.PREF_LAYOUT_PREFIX)
            || key == helium314.keyboard.settings.AppearanceLooks.PREF || key == helium314.keyboard.settings.LayoutPresets.PREF))

private fun factoryReset(ctx: Context, keyboards: Boolean, learnedWords: Boolean, clipboard: Boolean, custom: Boolean) {
    val prefs = ctx.realPrefs()
    // (the keyboards' own learned words were put together before, see the dialog)
    Settings.getInstance().stopListener()
    prefs.edit { prefs.all.keys.filterNot { keptOnReset(it, keyboards, custom) }.forEach { remove(it) } }
    helium314.keyboard.latin.gesture.GestureStats.reload() // (its cached rows would come back over the reset)
    if (keyboards) ctx.filesDir?.let { LearnedPools.forgetSeeded(it) } // (the keyboards' ids start again)
    LearnedStores.refresh(prefs)
    KeyboardProfiles.editingId = KeyboardProfiles.SHARED
    // the background pictures belong to the settings (every keyboard's: the set ids start again after a reset, and a
    // new keyboard mustn't find an old one's picture)
    KeyboardProfiles.deleteAllFiles()
    Settings.clearCachedBackgroundImages()
    if (learnedWords) PersonalizationHelper.removeAllUserHistoryDictionaries(ctx)
    if (clipboard) ClipboardDao.getInstance(ctx)?.clear()
    if (custom) {
        // the saved themes' background pictures (the themes themselves were settings)
        File(ctx.filesDir, "looks").deleteRecursively()
        val filesDir = DeviceProtectedUtils.getFilesDir(ctx)
        for (type in LayoutType.entries) File(filesDir, type.folder).deleteRecursively()
        // the loaded fonts and pictures, offered to every keyboard
        helium314.keyboard.keyboard.FontLibrary.dir(ctx).deleteRecursively()
        helium314.keyboard.latin.common.PictureLibrary.dir(ctx).deleteRecursively()
        helium314.keyboard.keyboard.KeyboardTypeface.clearCache()
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

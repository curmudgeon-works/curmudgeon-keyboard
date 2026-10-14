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
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
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
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.io.File

/** What a factory reset keeps (owner 2026-10-11: ticks mean keep; all unticked = full reset). */
internal data class ResetKeep(
    val keyboards: Boolean = false,
    val words: Boolean = false,
    val clipboard: Boolean = false,
    val own: Boolean = false,
)

/**
 * Factory reset: every setting back to its default, and whatever isn't ticked as kept: the keyboards and languages, the
 * learned and blacklisted words, the clipboard history, the user's own items and files (owner 2026-10-11: "Select items
 * you DON'T want to reset:" ... "All items should be unselected by default"). Backup (owner 2026-10-11) runs the backup
 * of Backup and restore; once one is written it greys out while the dialog stays open.
 */
@Composable
fun FactoryResetPreference(setting: Setting) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    // (owner 2026-10-11: "After a successful backup, the button should become grey (unless the user cancels and clicks
    // factory reset again)"; a cancelled or failed one leaves it on)
    var backedUp by rememberSaveable { mutableStateOf(false) }
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val backupLauncher = backupLauncher(onError = { error = it }, onDone = { written -> if (written) backedUp = true })
    Preference(name = setting.title, description = setting.description, onClick = { backedUp = false; showDialog = true })
    error?.let { InfoDialog(stringResource(R.string.backup_error, it.drop(1))) { error = null } }
    if (!showDialog) return
    FactoryResetDialog(
        backedUp = backedUp,
        backupRunning = BackupRestoreJob.progress != null,
        onDismiss = { showDialog = false },
        onBackup = { backupLauncher.launch(backupIntent(ctx)) },
        onReset = { keep ->
            val app = ctx.applicationContext
            startFactoryReset(ctx, keep) { Toast.makeText(app, R.string.factory_reset_done, Toast.LENGTH_LONG).show() }
        },
    )
}

/** The dialog itself: four keep boxes, all unticked; Backup (grey once [backedUp], and while a backup runs, when Reset
 *  is too), Cancel left of Reset (the app's order). The ticks stay through a backup (the picker and back). */
@Composable
internal fun FactoryResetDialog(backedUp: Boolean, backupRunning: Boolean, onDismiss: () -> Unit, onBackup: () -> Unit,
                                onReset: (ResetKeep) -> Unit) {
    var keyboards by rememberSaveable { mutableStateOf(false) }
    var words by rememberSaveable { mutableStateOf(false) }
    var clipboard by rememberSaveable { mutableStateOf(false) }
    var own by rememberSaveable { mutableStateOf(false) }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
        onConfirmed = { onReset(ResetKeep(keyboards, words, clipboard, own)) },
        title = { Text(stringResource(R.string.factory_reset)) },
        confirmButtonText = stringResource(R.string.factory_reset_button),
        neutralButtonText = stringResource(R.string.button_backup),
        neutralEnabled = !backedUp && !backupRunning,
        onNeutral = onBackup,
        checkOk = { !backupRunning }, // (never a reset while the backup is being written)
        scrollContent = true,
        content = {
            Column {
                Text(stringResource(R.string.factory_reset_message))
                Text(stringResource(R.string.factory_reset_keep), modifier = Modifier.padding(top = 8.dp))
                @Composable fun CheckRow(text: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp)) {
                        Checkbox(checked = checked, onCheckedChange = onChange)
                        Text(stringResource(text))
                    }
                CheckRow(R.string.factory_reset_keyboards, keyboards) { keyboards = it }
                CheckRow(R.string.factory_reset_learned_words, words) { words = it }
                CheckRow(R.string.factory_reset_clipboard, clipboard) { clipboard = it }
                CheckRow(R.string.factory_reset_custom, own) { own = it }
                if (backupRunning) Text(stringResource(R.string.backup_running), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
}

/**
 * The reset, started from the dialog. The learned words' files are changed on the app's background executor (review
 * 2026-10-06: on the screen thread it froze), which outlives the dialog (re-review: a task started in the dialog's scope
 * was cancelled when the dialog closed, and nothing was reset); the reset itself runs on the main thread, and [onDone]
 * after it. Learned words (owner 2026-10-11, data-model #24): not kept, every keyboard's learned words and blacklists
 * go; kept with the keyboards, each keyboard keeps its own; kept without them, the removed keyboards' are merged into
 * the default keyboard's, every script ("b. merge into default keyboard's words, retaining all script-wise lists").
 */
internal fun startFactoryReset(ctx: Context, keep: ResetKeep, onDone: () -> Unit) {
    val executor = helium314.keyboard.latin.utils.ExecutorUtils.getBackgroundExecutor(helium314.keyboard.latin.utils.ExecutorUtils.KEYBOARD)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    executor.execute {
        try { LearnedKeyboards.beforeFactoryReset(ctx, deleteWords = !keep.words) }
        catch (e: Exception) { helium314.keyboard.latin.utils.Log.e("FactoryReset", "learned words not reset", e) }
        main.post {
            factoryReset(ctx, keep)
            if (keep.keyboards) { onDone(); return@post }
            // the keyboard list after the reset: the default keyboard
            val left = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
            executor.execute {
                try { if (keep.words) LearnedKeyboards.mergeRemovedInto(ctx, left) else LearnedKeyboards.keepOnly(ctx, left) }
                catch (e: Exception) { helium314.keyboard.latin.utils.Log.e("FactoryReset", "the removed keyboards' learned words not handled", e) }
                main.post { onDone() }
            }
        }
    }
}

/**
 * What survives the settings reset: the version and every one-time step's flag (else they run again on a reset phone:
 * review 2026-10-06, the 0.3.002 step brought back the 0.5% / 0.75% gaps), and what [keep] keeps (owner 2026-10-11:
 * ticks mean keep):
 * - keyboards: the list (each keyboard's id with it: its set, pictures and words stay its own; owner 2026-10-11), its
 *   languages (each keyboard's priorities too) and how the sets are read
 * - own: the saved popup sets, themes, Layouts and palettes; with the keyboards also each keyboard's use of them
 *   (data-model #6: [isOwnItemUse]; [value] is the stored value, [ownPalettes] the user's palettes' names)
 */
internal fun keptOnReset(key: String, keep: ResetKeep, value: Any? = null, ownPalettes: Set<String> = emptySet()): Boolean {
    if (key == Settings.PREF_VERSION_CODE || KeyboardProfiles.isOneTimeFlag(key)
        || key in LearnedKeyboards.keptOnReset) return true // (which pool each keyboard's words are in: data, not a setting)
    val plain = KeyboardProfiles.splitOwnKey(key)?.second
    if (keep.keyboards) {
        if (key == Settings.PREF_ENABLED_SUBTYPES || key == Settings.PREF_ADDITIONAL_SUBTYPES
            || key == Settings.PREF_SELECTED_SUBTYPE || key in KeyboardProfiles.setStructureKeys) return true
        if (LanguagePriority.isLanguageKey(plain ?: key)) return true
    }
    if (!keep.own) return false
    if (plain == null) return key == KeyPopupOverrides.PREF_SETS || key == helium314.keyboard.settings.AppearanceLooks.PREF
        || key == helium314.keyboard.settings.LayoutPresets.PREF || helium314.keyboard.settings.Palettes.isKey(key)
        // (how the saved items apply, read by the choices applied again below: kept with them, 2026-10-11)
        || key == helium314.keyboard.settings.SavedItemDefaults.PREF
    // (a keyboard reset is a new keyboard: none of the old ones' uses)
    return keep.keyboards && isOwnItemUse(plain, value, ownPalettes)
}

/** A keyboard's setting [plain] (stored [value]) that uses one of the user's own items: its per-key popups, the popup
 *  set chosen and its letter-to-symbol map (and their "Custom" label), a custom key layout as its default of a type, a
 *  palette of the user's ([ownPalettes]) for its colors, a loaded font. (Its pictures are files: kept beside.) */
internal fun isOwnItemUse(plain: String, value: Any?, ownPalettes: Set<String>): Boolean = when (plain) {
    KeyPopupOverrides.PREF, KeyPopupOverrides.PREF_SELECTED_SET, Settings.PREF_SYMBOL_POPUP_MAP,
    helium314.keyboard.settings.CustomLabels.Kind.POPUPS.key, helium314.keyboard.settings.CustomLabels.Kind.POPUPS.fromKey -> true
    in helium314.keyboard.settings.Palettes.slots -> value is String && value in ownPalettes
    in helium314.keyboard.keyboard.FontLibrary.fontPrefs -> value is String && value.startsWith(helium314.keyboard.keyboard.FontLibrary.PREFIX)
    else -> LayoutType.entries.any { plain == Settings.PREF_LAYOUT_PREFIX + it.name }
        && value is String && LayoutUtilsCustom.isCustomLayout(value)
}

/** What a keyboard chose that is applied again after a reset keeping keyboards and own items, as choosing it applies it. */
private val reappliedChoices = listOf(helium314.keyboard.settings.LayoutPresets.PREF_SELECTED,
    KeyPopupOverrides.PREF_SELECTED_SET, helium314.keyboard.settings.AppearanceLooks.PREF_SELECTED)

/** Each set's chosen saved Layout, popup set and theme in [all] (the stored preferences): keyboard id -> key -> name. */
internal fun chosenItems(all: Map<String, *>): Map<String, Map<String, String>> {
    val result = HashMap<String, HashMap<String, String>>()
    for ((key, value) in all) {
        val (id, plain) = KeyboardProfiles.splitOwnKey(key) ?: continue
        if (plain in reappliedChoices && value is String) result.getOrPut(id) { HashMap() }[plain] = value
    }
    return result
}

/**
 * After a reset keeping the keyboards and own items (owner 2026-10-11: "if the keyboard retains its use of saved or
 * preset Layouts, then why do keygaps get reset?" -> "yes, c is good for both ticked"): each listed keyboard with
 * [chosen] items gets them again as choosing them does (built-in Layouts and themes too; "Saved items take new
 * defaults" as set): the Layout (settings and keys), then the popup set, then the theme (settings and pictures).
 * Tweaks on top of an item go back to its values; an item gone since is skipped.
 */
private fun reapplyChosenItems(ctx: Context, chosen: Map<String, Map<String, String>>) {
    if (chosen.isEmpty()) return
    val real = ctx.realPrefs()
    // (looked up again each time: a Layout or popup set can change the keyboard's definition, its id stays; switched-off
    // keyboards too, review round 5 M9: they keep their chosen items as the listed ones do)
    fun keyboardOf(id: String) = helium314.keyboard.latin.settings.KeyboardIds.phoneKeyboards(real).firstOrNull { it.keyboardId == id }
    val layouts = helium314.keyboard.settings.LayoutPresets.builtIn(ctx) + helium314.keyboard.settings.LayoutPresets.load(real)
    val looks = helium314.keyboard.settings.AppearanceLooks.builtIn(ctx) + helium314.keyboard.settings.AppearanceLooks.load(real)
    val sets = KeyPopupOverrides.loadSets(real)
    val takeNew = helium314.keyboard.settings.SavedItemDefaults.takeNewDefaults(real)
    for ((id, items) in chosen) try {
        items[helium314.keyboard.settings.LayoutPresets.PREF_SELECTED]?.let { name -> layouts.firstOrNull { it.name == name } }
            ?.let { preset -> keyboardOf(id)?.let { helium314.keyboard.settings.LayoutPresets.applyToOther(ctx, it, preset) } }
        items[KeyPopupOverrides.PREF_SELECTED_SET]?.let { name -> sets.firstOrNull { it.name == name } }
            ?.let { set -> keyboardOf(id)?.let { helium314.keyboard.settings.screens.applySetToOther(ctx, it, set, takeNew) } }
        items[helium314.keyboard.settings.AppearanceLooks.PREF_SELECTED]?.let { name -> looks.firstOrNull { it.name == name } }
            ?.let { look -> keyboardOf(id)?.let { helium314.keyboard.settings.AppearanceLooks.applyToOther(ctx, it, look) } }
    } catch (e: Exception) { helium314.keyboard.latin.utils.Log.e("FactoryReset", "a keyboard's chosen items not applied again", e) }
}

private fun factoryReset(ctx: Context, keep: ResetKeep) {
    val prefs = ctx.realPrefs()
    // (the learned words were reset before, see the dialog; the buttons to use other keyboards' lists go with the settings)
    Settings.getInstance().stopListener()
    val all = prefs.all
    val ownPalettes = helium314.keyboard.settings.Palettes.list(all).keys
    // each keyboard's saved Layout, popup set and theme, chosen again after the reset (owner 2026-10-11: "c is good for
    // both ticked")
    val chosen = if (keep.keyboards && keep.own) chosenItems(all) else emptyMap()
    prefs.edit { all.filterNot { (key, value) -> keptOnReset(key, keep, value, ownPalettes) }.keys.forEach { remove(it) } }
    KeyboardProfiles.loadGroups(prefs) // (the shared menus as they are now)
    helium314.keyboard.latin.gesture.GestureStats.reload() // (its cached rows would come back over the reset)
    LearnedStores.refresh(prefs)
    // the background pictures: each keyboard's use of the loaded pictures, kept with it and "Your own" (owner
    // 2026-10-11); else they go (the keyboards' sets start again: a new keyboard mustn't find an old one's picture)
    if (!(keep.keyboards && keep.own)) KeyboardProfiles.deleteAllFiles()
    Settings.clearCachedBackgroundImages()
    if (!keep.clipboard) ClipboardDao.getInstance(ctx)?.clear()
    if (!keep.own) {
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
    clearSettingsCaches() // (the fonts and language priorities too, kept or not: review 2026-10-08 area 2 #3)
    Settings.getInstance().startListener()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    reapplyChosenItems(ctx, chosen)
    GestureDecoderVocabulary.clear()
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    // the keyboards' sets start again: no screen stays open on a set id that is gone or now another keyboard's
    (ctx.getActivity() as? SettingsActivity)?.backToMain()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

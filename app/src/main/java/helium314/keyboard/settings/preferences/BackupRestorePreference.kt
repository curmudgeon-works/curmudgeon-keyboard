// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.UserDictionary
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreFiles
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStoreMigration
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.personalization.UserHistoryDictionary
import helium314.keyboard.latin.personalization.byScript
import helium314.keyboard.latin.personalization.countsOf
import helium314.keyboard.latin.personalization.mergeAdding
import helium314.keyboard.latin.utils.ScriptUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.SettingsSubtype
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutType.Companion.folder
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.RemovedWords
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.realPrefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.screens.keyboardName
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.transferOldPinnedClips

@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val backupLauncher = backupLauncher { error = it }
    var pendingRestore: PendingRestore? by remember { mutableStateOf(null) }
    val restoreLauncher = restoreLauncher(onError = { error = it }, onChoose = { pendingRestore = it })
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showDialog = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restoreLauncher.launch(intent)
            },
            onConfirmed = {
                val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        ctx.getString(R.string.english_ime_name)
                            .replace(" ", "_") + "_backup_$currentDate.zip"
                    )
                    .setType("application/zip")
                backupLauncher.launch(intent)
            }
        )
    }
    pendingRestore?.let { pending ->
        RestoreChoiceDialog(pending, onDismiss = { pendingRestore = null }, onError = { error = it })
    }
    BackupRestoreJob.progress?.let { ProgressDialog(it) }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

@Composable
private fun backupLauncher(onError: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val resolver = ctx.getActivity()?.contentResolver ?: return@filePicker
        runBackup(ctx, onError) { resolver.openOutputStream(uri) }
    }
}

/**
 * Writes the backup to the stream [open] gives, on the keyboard executor with the progress dialog up (review
 * 2026-10-08 M2, 2026-10-10: the screen thread waited for it, and a touch meanwhile was an ANR). False when a backup or
 * restore is running already. [onError] is called on the main thread.
 */
internal fun runBackup(ctx: Context, onError: (String) -> Unit, open: () -> OutputStream?): Boolean {
    if (!BackupRestoreJob.start(R.string.backup_running, 1)) return false
    ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
        var failure: String? = null
        try {
            writeBackup(ctx, open)
        } catch (t: Throwable) {
            failure = "b" + t.message
            Log.w("AdvancedScreen", "error during backup", t)
        } finally {
            BackupRestoreJob.post {
                BackupRestoreJob.finish()
                failure?.let(onError)
            }
        }
    }
    return true
}

private fun writeBackup(ctx: Context, open: () -> OutputStream?) {
    // zip all files matching the backup patterns
    // essentially this is the typed words information, and user-added dictionaries
    val filesDir = ctx.filesDir ?: return
    val filesPath = filesDir.path + File.separator
    val files = mutableListOf<File>()
    filesDir.walk().forEach { file ->
        val path = file.path.replace(filesPath, "")
        if (file.isFile && backupFilePatterns.any { path.matches(it) })
            files.add(file)
    }
    val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val protectedFilesPath = protectedFilesDir.path + File.separator
    val protectedFiles = mutableListOf<File>()
    protectedFilesDir.walk().forEach { file ->
        val path = file.path.replace(protectedFilesPath, "")
        if (file.isFile && backupFilePatterns.any { path.matches(it) })
            protectedFiles.add(file)
    }
    // a step per file, the clipboard, the two settings files and the custom words
    BackupRestoreJob.setTotal(files.size + protectedFiles.size + 4)
    open()?.use { os ->
        // write files to zip
        val zipStream = ZipOutputStream(os)
        files.forEach {
            val fileStream = FileInputStream(it).buffered()
            zipStream.putNextEntry(ZipEntry(it.path.replace(filesPath, "")))
            fileStream.copyTo(zipStream, 1024)
            fileStream.close()
            zipStream.closeEntry()
            BackupRestoreJob.step()
        }
        protectedFiles.forEach {
            val fileStream = FileInputStream(it).buffered()
            zipStream.putNextEntry(ZipEntry(it.path.replace(protectedFilesDir.path, "unprotected")))
            fileStream.copyTo(zipStream, 1024)
            fileStream.close()
            zipStream.closeEntry()
            BackupRestoreJob.step()
        }
        val dbFile = ctx.getDatabasePath(Database.NAME)
        if (dbFile.exists()) {
            val fileStream = FileInputStream(dbFile).buffered()
            zipStream.putNextEntry(ZipEntry(Database.NAME))
            fileStream.copyTo(zipStream, 1024)
            fileStream.close()
            zipStream.closeEntry()
        }
        BackupRestoreJob.step()
        zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
        // every keyboard's set, not just the edited one: what is stored (the settings you picked, and the keyboards'
        // "reset to default" marks), so a setting left at its default follows the defaults of the version it's
        // restored on (0.3.008; from 2026-10-03 every default was written out, which froze them)
        settingsToJsonStream(ctx.realPrefs().all.mapKeys { it.key as String? }, zipStream)
        zipStream.closeEntry()
        BackupRestoreJob.step()
        zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
        settingsToJsonStream(ctx.protectedPrefs().all, zipStream)
        zipStream.closeEntry()
        BackupRestoreJob.step()
        // fork: include the system personal dictionary (words added via "edit personal dictionary")
        zipStream.putNextEntry(ZipEntry(PERSONAL_DICT_FILE_NAME))
        zipStream.write(Json.encodeToString(readPersonalDictionary(ctx)).toByteArray())
        zipStream.closeEntry()
        BackupRestoreJob.step()
        zipStream.close()
    }
}

/**
 * The backup or restore running, one at a time app-wide, and how far it got (review 2026-10-08 M2, 2026-10-10). The
 * job runs on the keyboard executor, which outlives the screen; the progress dialog reads [progress], which is only
 * changed on the main thread. A step is a file written or restored, then the learned words, then the settings.
 */
internal object BackupRestoreJob {
    class Progress(val title: Int, val done: Int, val total: Int)

    var progress: Progress? by mutableStateOf(null)
        private set
    /** Called on the main thread with every progress shown (tests). */
    internal var onProgress: ((Progress) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val done = AtomicInteger(0)
    @Volatile private var total = 1
    @Volatile private var title = 0
    private val updatePosted = AtomicBoolean(false)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    val isRunning get() = running.get()

    /** Claims the job (false when one is running) and shows the dialog; called on the main thread. */
    fun start(title: Int, total: Int): Boolean {
        if (!running.compareAndSet(false, true)) return false
        this.title = title
        this.total = total.coerceAtLeast(1)
        done.set(0)
        publish()
        return true
    }

    /** The number of steps, once the worker knows it. */
    fun setTotal(total: Int) {
        if (!running.get()) return
        this.total = total.coerceAtLeast(1)
        update()
    }

    /** One step done (nothing when no job runs: the restore functions are called directly by tests too). */
    fun step() {
        if (!running.get()) return
        done.incrementAndGet()
        update()
    }

    // one update waiting on the main thread at a time: it shows the latest count when it runs
    private fun update() {
        if (updatePosted.compareAndSet(false, true)) main.post { updatePosted.set(false); if (running.get()) publish() }
    }

    private fun publish() {
        val p = Progress(title, done.get(), total)
        progress = p
        onProgress?.invoke(p)
    }

    fun post(action: () -> Unit) { main.post(action) }

    /** The job ended: the dialog closes and another job may start; called on the main thread. */
    fun finish() {
        progress = null
        running.set(false)
    }
}

/** Non-cancellable while the job runs: it closes itself when the job ends. */
@Composable
private fun ProgressDialog(progress: BackupRestoreJob.Progress) {
    Dialog(onDismissRequest = { }, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(progress.title), style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** A picked backup, copied to the cache so it can be read twice: its preferences, the keyboards they list and its entries. */
private class PendingRestore(val file: File, val prefs: Map<String, Any?>, val keyboards: List<SettingsSubtype>, val entries: Set<String>) {
    private val plain = entries.map { it.substringAfter("unprotected${File.separator}") }
    val hasLearnedWords = plain.any { it.startsWith(UserHistoryDictionary.NAME) || it.startsWith("blacklists${File.separator}") }
    val hasDictionaries = plain.any { it.startsWith("dicts${File.separator}") && it.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }
    val hasCustomWords = PERSONAL_DICT_FILE_NAME in entries
    val hasClipboard = Database.NAME in entries
}

/** What the restore dialog was told to bring: [keyboards] with or without their [settings], and the rest by data type. */
private class RestoreChoice(
    val keyboards: List<SettingsSubtype>, val settings: Boolean, val learnedWords: Boolean, val dictionaries: Boolean,
    val customWords: Boolean, val clipboard: Boolean,
    // restoring some keyboards: the backup's saved items whose names the phone uses come in as numbered copies (2026-10-11)
    val asCopies: Boolean = true,
)

@Composable
private fun restoreLauncher(onError: (String) -> Unit, onChoose: (PendingRestore) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        if (BackupRestoreJob.isRunning) return@filePicker // (its file is the one being restored)
        val file = File(ctx.cacheDir, "restore.zip")
        val pending = try {
            ctx.getActivity()?.contentResolver?.openInputStream(uri)?.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
            readBackup(file)
        } catch (t: Throwable) {
            Log.w("AdvancedScreen", "error reading backup", t)
            onError("r" + t.message)
            file.delete()
            return@filePicker
        }
        // a backup without keyboards (older format) has nothing to choose from
        if (pending.keyboards.isEmpty()) {
            // (the file goes when the job is done with it)
            if (!runRestore(ctx, onError, R.string.backup_restored, restoreSteps(pending)) {
                try { restoreEverything(ctx, file) } finally { file.delete() }
            }) file.delete()
        } else onChoose(pending)
    }
}

@Composable
private fun RestoreChoiceDialog(pending: PendingRestore, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    // keyboards not on this phone are new and ticked; those here start unticked: restoring one replaces it (2026-10-11)
    val onPhone = remember(pending) { SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }.toSet() }
    val selected = remember(pending) { mutableStateListOf<SettingsSubtype>().apply { addAll(pending.keyboards.filter { it !in onPhone }) } }
    var asCopies by rememberSaveable { mutableStateOf(true) }
    var settings by rememberSaveable { mutableStateOf(true) }
    var learnedWords by rememberSaveable { mutableStateOf(true) }
    var dictionaries by rememberSaveable { mutableStateOf(true) }
    var customWords by rememberSaveable { mutableStateOf(true) }
    var clipboard by rememberSaveable { mutableStateOf(false) }
    val withKeyboards = selected.isNotEmpty() // the "bring with them" rows mean nothing without a keyboard
    // once the restore started, the job deletes the file when done with it (2026-10-10: it runs after the dialog closed)
    var started by remember { mutableStateOf(false) }
    ThreeButtonAlertDialog(
        onDismissRequest = { if (!started) pending.file.delete(); onDismiss() },
        title = { Text(stringResource(R.string.button_restore)) },
        content = {
            Column {
                Text(stringResource(R.string.restore_choice_message))
                @Composable fun Heading(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                @Composable fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp)) {
                        Checkbox(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
                        Text(text)
                    }
                Heading(R.string.restore_keyboards)
                pending.keyboards.forEach { keyboard ->
                    val name = keyboardName(keyboard, ctx).let { if (keyboard in onPhone) it else stringResource(R.string.restore_keyboard_new, it) }
                    CheckRow(name, keyboard in selected) { if (it) selected.add(keyboard) else selected.remove(keyboard) }
                }
                Heading(R.string.restore_with_keyboards)
                CheckRow(stringResource(R.string.restore_settings), settings, withKeyboards) { settings = it }
                // (only when some keyboards come back: all of them with their settings replace everything)
                if (settings && withKeyboards && selected.toSet() != pending.keyboards.toSet())
                    CheckRow(stringResource(R.string.restore_saved_items_as_copies), asCopies) { asCopies = it }
                if (pending.hasLearnedWords)
                    CheckRow(stringResource(R.string.restore_learned_words), learnedWords, withKeyboards) { learnedWords = it }
                if (pending.hasDictionaries)
                    CheckRow(stringResource(R.string.restore_dictionaries), dictionaries, withKeyboards) { dictionaries = it }
                if (pending.hasCustomWords || pending.hasClipboard) {
                    Heading(R.string.restore_also)
                    if (pending.hasCustomWords) CheckRow(stringResource(R.string.restore_custom_words), customWords) { customWords = it }
                    if (pending.hasClipboard) CheckRow(stringResource(R.string.restore_clipboard), clipboard) { clipboard = it }
                }
                Text(stringResource(R.string.restore_replaces_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        scrollContent = true,
        confirmFirst = true,
        checkOk = { withKeyboards || (customWords && pending.hasCustomWords) || (clipboard && pending.hasClipboard) },
        onConfirmed = {
            val choice = RestoreChoice(selected.toList(), settings, learnedWords && pending.hasLearnedWords,
                dictionaries && pending.hasDictionaries, customWords && pending.hasCustomWords, clipboard && pending.hasClipboard, asCopies)
            started = runRestore(ctx, onError, R.string.backup_restored, restoreSteps(pending)) {
                try { restoreChosen(ctx, pending, choice) } finally { pending.file.delete() }
            }
        },
    )
}

/** The steps of restoring [pending]'s job: each of its entries, then the learned words (the settings: see runRestore). */
private fun restoreSteps(pending: PendingRestore) = pending.entries.size + 1

/**
 * Runs [work] (counting [steps] steps) and what follows a restore on the keyboard executor, with the progress dialog
 * up, then refreshes on the main thread what must be refreshed there, and closes the dialog (review 2026-10-08 M2,
 * 2026-10-10: the screen thread waited for the whole job, and a touch meanwhile was an ANR that could leave half a
 * restore). False when a backup or restore is running already. [onError] is called on the main thread.
 */
internal fun runRestore(ctx: Context, onError: (String) -> Unit, doneMessage: Int, steps: Int = 0, work: () -> Unit): Boolean {
    if (!BackupRestoreJob.start(R.string.restore_running, steps + 1)) return false
    val app = ctx.applicationContext
    ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
        var failure: String? = null
        try {
            work()
        } catch (t: Throwable) {
            failure = "r" + t.message
            Log.w("AdvancedScreen", "error during restore", t)
        }
        // (after a failure too, as before: what was restored by then is followed up)
        try {
            helium314.keyboard.latin.gesture.GestureStats.reload() // (its cached rows would come back over the restored ones)
            restoreFollowUp(ctx)
            transferOldPinnedClips(ctx)
        } catch (t: Throwable) {
            Log.w("AdvancedScreen", "error after restore", t)
        }
        BackupRestoreJob.step() // (the settings)
        BackupRestoreJob.post {
            try {
                restoreRefresh(ctx)
            } catch (t: Throwable) {
                Log.w("AdvancedScreen", "error refreshing after restore", t)
            } finally {
                BackupRestoreJob.finish()
            }
            if (failure != null) onError(failure)
            else Toast.makeText(app, app.getString(doneMessage), Toast.LENGTH_LONG).show()
        }
    }
    return true
}

/**
 * What the settings listener clears on a change, cleared after a change made with it stopped (a restore, a factory
 * reset): the language priorities read for suggestions (cleared there when the preferences are cleared), the background
 * pictures shown and the fonts loaded (which also moves an old backup's font files into the font list now). Each path
 * cleared its own part of it before (review 2026-10-08 area 2 #3: after a whole restore the old fonts, picture and
 * language priorities stayed until a keyboard switch or restart).
 */
internal fun clearSettingsCaches() {
    helium314.keyboard.latin.utils.LanguagePriority.clearCache()
    Settings.clearCachedBackgroundImages()
    helium314.keyboard.keyboard.KeyboardTypeface.clearCache()
}

/** What a restore changes, refreshed on the main thread: the keyboard's view of the settings and the open screens. */
private fun restoreRefresh(ctx: Context) {
    LearnedStores.refresh(ctx.realPrefs()) // (the keyboard's learned words, as restored)
    clearSettingsCaches()
    Settings.getInstance().startListener()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
    ctx.getActivity()?.sendBroadcast(newDictBroadcast)
    LayoutUtilsCustom.onLayoutFileChanged()
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    // the backup's set ids replace the phone's: no screen stays open on an id that is gone or now another keyboard's
    (ctx.getActivity() as? SettingsActivity)?.backToMain()
    SupportedEmojis.load(ctx)
    GestureDecoderVocabulary.clear()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

/** The whole backup replaces everything, as before; a backup from before each keyboard had its own learned words is
 *  then updated as the phone was (2026-10-10). */
private fun restoreEverything(ctx: Context, file: File) = LearnedKeyboards.withFiles {
    replaceEverything(ctx, file)
    LearnedKeyboards.updateOnce(ctx)
}

private fun replaceEverything(ctx: Context, file: File) = keepingPhoneChoices(ctx) {
    val restoredDb = ctx.getDatabasePath(Database.NAME + "_restored")
    ZipInputStream(FileInputStream(file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        val filesDir = ctx.filesDir ?: return@keepingPhoneChoices
        val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
        filesDir.deleteRecursively()
        deviceProtectedFilesDir.deleteRecursively()
        LayoutUtilsCustom.onLayoutFileChanged()
        Settings.getInstance().stopListener()
        while (entry != null) {
            if (entry.name.startsWith("unprotected${File.separator}")) {
                val adjustedName = entry.name.substringAfter("unprotected${File.separator}")
                if (backupFilePatterns.any { adjustedName.matches(it) }) {
                    val file = File(deviceProtectedFilesDir, adjustedName)
                    FileUtils.copyStreamToNewFile(zip, file)
                }
            } else if (backupFilePatterns.any { entry.name.matches(it) }) {
                val file = File(filesDir, entry.name)
                FileUtils.copyStreamToNewFile(zip, file)
            } else if (entry.name == Database.NAME) {
                FileUtils.copyStreamToNewFile(zip, restoredDb)
            } else if (entry.name == PREFS_FILE_NAME) {
                val prefLines = String(zip.readBytes()).split("\n")
                val prefs = ctx.prefs()
                prefs.edit { clear() }
                readJsonLinesToSettings(prefLines, prefs)
            } else if (entry.name == PROTECTED_PREFS_FILE_NAME) {
                val prefLines = String(zip.readBytes()).split("\n")
                restoreProtectedPrefs(ctx, prefLines)
            } else if (entry.name == PERSONAL_DICT_FILE_NAME) {
                // fork: merge backed-up personal dictionary into the system one (never fail the whole restore over it)
                try {
                    restorePersonalDictionary(ctx, String(zip.readBytes()))
                } catch (t: Throwable) {
                    Log.w("AdvancedScreen", "error restoring personal dictionary", t)
                }
            }
            zip.closeEntry()
            BackupRestoreJob.step()
            entry = zip.nextEntry
        }
    }
    Database.copyFromDb(restoredDb, ctx)
    // the learned words open in the keyboard read the restored files; a backup from before they were per script has
    // per-language files, merged per script now as at app start
    PersonalizationHelper.reloadAllFromFiles()
    LearnedStoreMigration.runNow(ctx)
    BackupRestoreJob.step() // (the learned words)
}

/**
 * The backup's protected preferences (pinned clips of an upstream HeliBoard backup, moved into the clipboard database
 * by [transferOldPinnedClips] afterwards). Before Android 7 they are the settings file itself (DeviceProtectedUtils
 * uses device protected storage only from N): clearing it there would wipe the settings just restored, so the entry's
 * keys are only added to it.
 */
private fun restoreProtectedPrefs(ctx: Context, lines: List<String>) {
    val protectedPrefs = ctx.protectedPrefs()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) protectedPrefs.edit { clear() }
    readJsonLinesToSettings(lines, protectedPrefs)
}

/** Restores the whole backup [file] as the restore dialog does for a backup without a keyboard list (tests). */
internal fun restoreEverythingFrom(ctx: Context, file: File) = restoreEverything(ctx, file)

/** Restores from the backup [file] as the restore dialog does, the learned words through [io] (tests). */
internal fun restoreChosenFrom(ctx: Context, file: File, keyboards: List<SettingsSubtype>, settings: Boolean, learnedWords: Boolean,
                               io: LearnedStoreIo) =
    restoreChosen(ctx, readBackup(file), RestoreChoice(keyboards, settings, learnedWords, false, false, false), io)

/**
 * Brings what [choice] asks for out of the backup and leaves the rest of the phone as it is. With every keyboard
 * of the backup chosen together with the settings, the backup's whole preference set (shared or separate, as it
 * was) replaces the phone's; a subset is restored keyboard by keyboard, see [restoreKeyboards]. Learned words and
 * added dictionaries follow the chosen keyboards' languages.
 */
private fun restoreChosen(ctx: Context, pending: PendingRestore, choice: RestoreChoice, io: LearnedStoreIo = LearnedStoreIo.Native) =
    LearnedKeyboards.withFiles {
        // (each keyboard its own learned words first, if this start couldn't do it yet: they're restored by keyboard)
        LearnedKeyboards.updateOnce(ctx)
        restoreChosenLocked(ctx, pending, choice, io)
    }

private fun restoreChosenLocked(ctx: Context, pending: PendingRestore, choice: RestoreChoice, io: LearnedStoreIo) {
    val everyKeyboard = choice.keyboards.toSet() == pending.keyboards.toSet()
    val allSettings = choice.settings && choice.keyboards.isNotEmpty() && everyKeyboard
    // each chosen keyboard as it is on the phone now (its custom key layouts can come in under other names)
    var onPhone: Map<SettingsSubtype, SettingsSubtype> = emptyMap()
    if (choice.keyboards.isNotEmpty()) {
        if (allSettings) restoreAllSettings(ctx, pending)
        else onPhone = restoreKeyboards(ctx, pending, choice.keyboards, choice.settings, choice.asCopies)
    }
    val tags = choice.keyboards.flatMap { listOf(it.locale) + getSecondaryLocales(it.extraValues) }.map { it.toLanguageTag() }.toSet()
    val filesDir = ctx.filesDir ?: return
    val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    // learned words are added to the phone's, never replacing them: the backup's stores are unpacked aside, then put
    // together with the phone's (counts added); the removed-word lists are combined. The backup's stores are per
    // script (shared, or a keyboard's own: UserHistoryDictionary.<script>[.k<id>].dict), or per language in a backup
    // from before (UserHistoryDictionary.<languageTag>.dict); the chosen keyboards' scripts and languages say which.
    val learnedDir = File(ctx.cacheDir, "restore_learned").apply { deleteRecursively() }
    val scripts = choice.keyboards.flatMap { listOf(it.locale) + getSecondaryLocales(it.extraValues) }.map { it.script() }.toSet()
    fun isLearnedWords(path: String): Boolean {
        val top = path.substringBefore(File.separator)
        LearnedStores.parseStoreFileName(top)?.let { return it.first in scripts }
        if (tags.any { path.startsWith("${UserHistoryDictionary.NAME}.$it.") || path == "blacklists${File.separator}$it.txt" }) return true
        if (!path.startsWith("blacklists${File.separator}") || !path.endsWith(".txt")) return false
        return path.substringAfterLast(File.separator).removeSuffix(".txt") in scripts
    }
    fun isDictionary(path: String) = path.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)
        && tags.any { path.startsWith("dicts${File.separator}$it${File.separator}") }
    val restoredDb = ctx.getDatabasePath(Database.NAME + "_restored")
    ZipInputStream(FileInputStream(pending.file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            val protected = name.startsWith("unprotected${File.separator}")
            val path = name.substringAfter("unprotected${File.separator}")
            val target = if (protected) File(deviceProtectedFilesDir, path) else File(filesDir, path)
            when {
                !backupFilePatterns.any { path.matches(it) } -> when {
                    name == Database.NAME && choice.clipboard -> FileUtils.copyStreamToNewFile(zip, restoredDb)
                    name == PROTECTED_PREFS_FILE_NAME && allSettings ->
                        restoreProtectedPrefs(ctx, String(zip.readBytes()).split("\n"))
                    name == PERSONAL_DICT_FILE_NAME && choice.customWords -> try {
                        restorePersonalDictionary(ctx, String(zip.readBytes())) // never fail the whole restore over it
                    } catch (t: Throwable) {
                        Log.w("AdvancedScreen", "error restoring personal dictionary", t)
                    }
                }
                choice.learnedWords && isLearnedWords(path) -> {
                    val aside = File(learnedDir, path)
                    if (aside.canonicalPath.startsWith(learnedDir.canonicalPath + File.separator)) FileUtils.copyStreamToNewFile(zip, aside)
                }
                choice.dictionaries && isDictionary(path) -> FileUtils.copyStreamToNewFile(zip, target)
                allSettings && isSettingsFile(path) -> FileUtils.copyStreamToNewFile(zip, target)
            }
            zip.closeEntry()
            BackupRestoreJob.step()
            entry = zip.nextEntry
        }
    }
    if (choice.clipboard) Database.copyFromDb(restoredDb, ctx)
    if (choice.learnedWords) {
        restoreLearnedWords(ctx, pending.prefs, choice.keyboards.map { it to (onPhone[it] ?: it) }, learnedDir, io)
        learnedDir.deleteRecursively()
    }
    BackupRestoreJob.step() // (the learned words)
    if (allSettings) {
        // a backup from before the pictures were per keyboard (or the picture list existed): what app start does once
        val real = ctx.realPrefs()
        KeyboardProfiles.migrateFiles(real)
        helium314.keyboard.latin.common.PictureLibrary.migrate(ctx, real.getBoolean("picture_library_migrated", false)) {
            real.edit().putBoolean("picture_library_migrated", true).apply() }
        Settings.clearCachedBackgroundImages()
    }
    LayoutUtilsCustom.onLayoutFileChanged()
}

/**
 * Runs [restore], then puts back the phone's own choice of what saved items do with new defaults (owner 2026-10-11,
 * whose switch decides on a restore: "Yes, the phone's").
 */
private fun keepingPhoneChoices(ctx: Context, restore: () -> Unit) {
    val key = helium314.keyboard.settings.SavedItemDefaults.PREF
    val phone = ctx.realPrefs().all[key] as? Boolean
    try { restore() } finally {
        ctx.realPrefs().edit(commit = true) { if (phone == null) remove(key) else putBoolean(key, phone) }
    }
}

/** The backup's preferences replace the phone's, every keyboard's set included. */
private fun restoreAllSettings(ctx: Context, pending: PendingRestore) = keepingPhoneChoices(ctx) {
    // which pool each keyboard's learned words are in names the phone's files: the phone's stays (the backup's words are
    // added keyboard by keyboard, restoreLearnedWords)
    val phone = LearnedKeyboards.phoneState(ctx.realPrefs())
    Settings.getInstance().stopListener()
    // the backup's set ids replace the phone's: pictures of the phone's sets would turn up on the backup's keyboards
    KeyboardProfiles.deleteAllFiles()
    Settings.clearCachedBackgroundImages()
    ctx.realPrefs().edit {
        clear()
        for ((key, value) in pending.prefs) KeyboardProfiles.put(this, key, value)
    }
    val real = ctx.realPrefs()
    // and the buttons of the backup's keyboards (review 2026-10-07: not the phone's list, which isn't reloaded yet)
    LearnedKeyboards.afterFullRestore(real, phone, pending.prefs, pending.keyboards)
    LearnedStores.refresh(real)
}

/** What [keyboard] gets when restored on its own from [backup]: what it read there, left at the defaults where the
 *  backup only wrote defaults down, and with the HeliBoard Layouts' values a backup from before 2026-10-07 lacks. */
internal fun restoredSettings(ctx: Context, backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?> {
    val values = KeyboardProfiles.effectiveSettingsIn(backup, keyboard)
        .let { values -> if (backup[helium314.keyboard.latin.PICKED_ONLY_DONE] == true) values else helium314.keyboard.latin.withoutDefaults(values, oldGaps = backup[helium314.keyboard.latin.KEY_GAP_DEFAULTS_DONE] != true) }
    // (after the defaults went, so a HeliBoard value that equals a default is never stripped as one)
    val pinned = if (backup[helium314.keyboard.latin.HELIBOARD_LAYOUT_PINS_DONE] == true) values
        else helium314.keyboard.latin.withHeliBoardPins(ctx, values)
    return if (backup[helium314.keyboard.latin.USER_POPUP_SETS_MAP_DONE] == true) pinned
        else withUserPopupSetMap(ctx.realPrefs(), backup, pinned)
}

/**
 * A keyboard restored on its own from a backup made before [helium314.keyboard.latin.keepUserPopupSetsSymbolMap]: if it
 * chose a user popup set and keeps no symbol map, it gets the set's (the phone's set of that name, else the backup's,
 * else the Curmudgeon map such a set showed), as the step gives the keyboards on the phone (review 2026-10-11: a
 * partial restore skipped it, and the keyboard lost its symbols again).
 */
internal fun withUserPopupSetMap(real: android.content.SharedPreferences, backup: Map<String, Any?>, values: Map<String, Any?>): Map<String, Any?> {
    val name = values[helium314.keyboard.latin.utils.KeyPopupOverrides.PREF_SELECTED_SET] as? String ?: return values
    if (values[Settings.PREF_SYMBOL_POPUP_MAP] != null) return values
    val onPhone = helium314.keyboard.latin.utils.KeyPopupOverrides.loadSets(real).firstOrNull { it.name == name }
    val inBackup = (backup[helium314.keyboard.latin.utils.KeyPopupOverrides.PREF_SETS] as? String)?.let { json ->
        runCatching { org.json.JSONArray(json) }.getOrNull()?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name") == name } } }
    if (onPhone == null && inBackup == null) return values // (not a user set: a built-in one names its own map)
    val map = onPhone?.symbolMap ?: inBackup?.optString("symbolMap")?.takeIf { inBackup.has("symbolMap") }
        ?: helium314.keyboard.latin.settings.Defaults.CURMUDGEON_SYMBOL_POPUP_MAP
    return values + (Settings.PREF_SYMBOL_POPUP_MAP to map)
}

/** Restores [keyboards] from the backup [file] as the restore dialog does when not every keyboard is chosen (tests). */
internal fun restoreKeyboardsFrom(ctx: Context, file: File, keyboards: List<SettingsSubtype>, withSettings: Boolean, asCopies: Boolean = true) =
    restoreKeyboards(ctx, readBackup(file), keyboards, withSettings, asCopies)

/**
 * Only [chosen] keyboards come out of the backup: each is added (or replaced) with its custom layout files and,
 * [withSettings], its settings and the priorities of its languages. Other keyboards stay as they
 * are. The keyboard's settings need a set of its own, so separate settings per keyboard get switched on if they
 * aren't; the existing keyboards keep the shared set they behave by now. A custom layout the phone has under the same
 * name with other keys comes in numbered ([restoreLayoutFiles]), and the keyboard is restored naming that one: the
 * result is each chosen keyboard as it is on the phone now.
 */
private fun restoreKeyboards(ctx: Context, pending: PendingRestore, chosen: List<SettingsSubtype>, withSettings: Boolean,
                             asCopies: Boolean = true): Map<SettingsSubtype, SettingsSubtype> {
    val real = ctx.realPrefs()
    val prefs = ctx.prefs()
    val backup = pending.prefs
    // its own set when the backup kept one, else the backup's shared set was what it used
    // (the backup's shared set with the keyboard's own on top: what it read on the old phone)
    // (a backup from before 0.3.008 wrote every setting at its default down: those are left out, so they follow the
    // defaults here; see pickedOnlyUpgrade)
    // the backup's saved items come in first (by content, taken names numbered), and the keyboards name them as they're
    // called on the phone now (2026-10-11; before, the names dangled or silently took the phone's same-named item)
    // (the loaded fonts and pictures first: a name the phone has for another file comes in numbered, and the themes and
    // keyboards naming it are rewritten with it; owner 2026-10-11: "for fonts and pictures too do the same pattern")
    val files = if (withSettings) restoreLibraryFiles(ctx, pending) else null
    // (by the keyboard as it was in the backup: the backup's sets are found by it)
    val backupSettings = if (withSettings) chosen.associateWith { restoredSettings(ctx, backup, it) } else emptyMap()
    // the custom key layouts the chosen keyboards use (picked, or their default for a type), owner 2026-10-11: "Yes", the
    // pattern of fonts and pictures: one the phone has under that name with other keys comes in numbered, "My numrow 2",
    // and the keyboards, their defaults and the restored Layouts name that one (before, it was written over the phone's,
    // and every other keyboard using it silently got the backup's keys)
    val wanted = (chosen.flatMap { keyboard -> LayoutType.entries.mapNotNull { type -> keyboard.layoutName(type)?.let { type to it } } } +
        backupSettings.values.flatMap { values -> LayoutType.entries.mapNotNull { type ->
            (values[Settings.PREF_LAYOUT_PREFIX + type.name] as? String)?.let { type to it } } })
        .filter { LayoutUtilsCustom.isCustomLayout(it.second) }.toSet()
    val keyLayouts = if (wanted.isEmpty()) emptyMap() else restoreLayoutFiles(ctx, pending, wanted, chosen)
    val onPhone = chosen.associateWith { keyboard -> LayoutType.entries.fold(keyboard) { kb, type ->
        kb.layoutName(type)?.let { keyLayouts[type]?.get(it) }?.let { kb.withLayout(type, it) } ?: kb } }
    // (their Custom numbers and kept palette copies: the next free ones where the phone's other sets use them, review C4;
    // the sets of the phone keyboards they replace don't count)
    val replacing = KeyboardProfiles.idEntries(real).let { ids -> (chosen + onPhone.values).mapNotNull { ids[it.toPref()] } }.toSet() - KeyboardProfiles.SHARED
    val phone = real.all.filterKeys { KeyboardProfiles.splitOwnKey(it)?.first !in replacing }
    val renames = if (withSettings) SavedItemsMerge.merge(ctx, real, backup, asCopies, files?.first.orEmpty(), files?.second.orEmpty(), keyLayouts,
        chosen.map { KeyboardProfiles.effectiveSettingsIn(backup, it) }, phone) else null
    val settings = RestoredCustomCopies.labels(phone, backupSettings.mapValues { (_, values) -> renames?.apply(values) ?: values })
    if (withSettings && !KeyboardProfiles.isSeparate(real))
        KeyboardProfiles.enable(real, SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }, keepExisting = true)

    if (withSettings) {
        // the languages' adding times are app-wide (the priorities are the keyboard's: written with its set below)
        val editor = real.edit()
        for (keyboard in chosen)
            for (locale in listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues))
                for (key in LanguagePriority.keys(locale)) if (!LanguagePriority.isPriorityKey(key)) backup[key]?.let { KeyboardProfiles.put(editor, key, it) }
        editor.apply()
    }

    // with their settings: the pictures they had, and the loaded fonts and pictures their settings may name (added to
    // the phone's lists, nothing there replaced)
    val pictureFiles = HashMap<String, ByteArray>()
    // only the chosen keyboards' pictures are read (each can be a few MB)
    val wantedPictures = chosen.flatMap { KeyboardProfiles.restoreFileNames(KeyboardProfiles.idIn(backup, it) ?: KeyboardProfiles.SHARED) }.toSet()
    if (withSettings) {
        val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
        ZipInputStream(FileInputStream(pending.file)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfter("unprotected${File.separator}", "")
                when {
                    name in wantedPictures -> pictureFiles[name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    for (keyboard in chosen) {
        val phoneKeyboard = onPhone.getValue(keyboard)
        // registers it unless it equals a built-in one; the phone's same keyboard, if its layouts came in under other
        // names, is replaced by it (in its place, its set and learned words going along)
        val present = SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() } +
            SubtypeSettings.createSettingsSubtypes(prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!)
        val from = if (phoneKeyboard != keyboard && keyboard in present && phoneKeyboard !in present) keyboard else phoneKeyboard
        SubtypeUtilsAdditional.changeAdditionalSubtype(from, phoneKeyboard, ctx)
        if (SubtypeSettings.getEnabledSubtypes().none { it.toSettingsSubtype() == phoneKeyboard })
            SubtypeSettings.addEnabledSubtype(prefs, phoneKeyboard.toAdditionalSubtype())
        if (withSettings) {
            val id = KeyboardProfiles.idFor(real, phoneKeyboard)
            // exactly that set: a setting the backup leaves at its default gets a default mark, so it doesn't read
            // the phone's shared value instead
            KeyboardProfiles.write(real, id, settings.getValue(keyboard), markDefaults = true)
            // an item it chose that wasn't brought in (its name is the phone's for other content, copies off): it names
            // none and shows "Custom 1" (owner 2026-10-11, option 2)
            renames?.customKinds(backupSettings.getValue(keyboard))?.forEach {
                helium314.keyboard.settings.CustomLabels.number(real, helium314.keyboard.latin.settings.ProfilePreferences(real) { id }, it) }
            // its languages' priorities, into its own set (not the shared one, which every other keyboard reads: review
            // session 2026-10-07): the backup's own copy for this keyboard first, else the backup's shared value
            val backupId = KeyboardProfiles.idIn(backup, keyboard)
            real.edit {
                for (locale in listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues))
                    for (key in LanguagePriority.keys(locale).filter { LanguagePriority.isPriorityKey(it) }) {
                        val value = (backupId?.let { backup[KeyboardProfiles.ownKey(it, key)] } ?: backup[key]) as? Int ?: continue
                        putInt(KeyboardProfiles.prefixedKey(id, key), value)
                    }
            }
            // its own pictures in the backup, or the backup's shared ones when it had no set of its own
            KeyboardProfiles.restoreFiles(pictureFiles, KeyboardProfiles.idIn(backup, keyboard) ?: KeyboardProfiles.SHARED, id,
                perKeyboardBackup = backup["profile_files_migrated"] == true)
        }
    }
    if (withSettings) {
        Settings.clearCachedBackgroundImages(); helium314.keyboard.keyboard.KeyboardTypeface.clearCache()
        // whether they use the other keyboards' learned and blacklisted words, as in the backup
        LearnedKeyboards.restoreButtons(real, backup, chosen, onPhone)
    }
    return onPhone
}

/**
 * The custom key layouts [wanted] (type to name) out of the backup, for [chosen] keyboards restored on their own: one the
 * phone has under the same name with the same keys is the phone's; a name the phone uses for other keys gets the next
 * free number ("My numrow 2", reusing a numbered one with these keys, so a second restore adds nothing). An unsaved one
 * (a keyboard's own) gets a new unsaved name, reusing one with these keys only if no keyboard but the same one uses it.
 * Nothing on the phone is written over. The renames, by type.
 */
private fun restoreLayoutFiles(ctx: Context, pending: PendingRestore, wanted: Set<Pair<LayoutType, String>>,
                               chosen: List<SettingsSubtype>): Map<LayoutType, Map<String, String>> {
    val byPath = wanted.associateBy { (type, name) -> type.folder + File.separator + name }
    val contents = LinkedHashMap<Pair<LayoutType, String>, ByteArray>()
    ZipInputStream(FileInputStream(pending.file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            byPath[entry.name.substringAfter("unprotected${File.separator}", "")]?.let { contents[it] = zip.readBytes() }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    val renames = HashMap<LayoutType, HashMap<String, String>>()
    val dir = DeviceProtectedUtils.getFilesDir(ctx)
    // (named ones first: whether an unsaved one's keyboard is the same is seen with its named layouts as they came in)
    for ((key, bytes) in contents.entries.sortedBy { LayoutUtilsCustom.isPrivateLayout(it.key.second) }) {
        val (type, name) = key
        val folderDir = File(dir, type.folder)
        val target = File(folderDir, name)
        // (never outside the app's folder, whatever the zip says)
        if (!target.canonicalPath.startsWith(folderDir.canonicalPath + File.separator)) continue
        if (!target.exists()) { folderDir.mkdirs(); target.writeBytes(bytes); continue }
        if (target.readBytes().contentEquals(bytes)) continue
        val newName = if (LayoutUtilsCustom.isPrivateLayout(name)) privateLayoutFor(ctx, type, name, bytes, chosen, renames)
        else generateSequence(2) { it + 1 }.map { LayoutUtilsCustom.numberedLayoutName(name, type, it) }
            .first { val f = File(folderDir, it); !f.exists() || f.readBytes().contentEquals(bytes) }
            .also { val f = File(folderDir, it); if (!f.exists()) f.writeBytes(bytes) }
        renames.getOrPut(type) { HashMap() }[name] = newName
    }
    LayoutUtilsCustom.onLayoutFileChanged()
    return renames
}

/** For [restoreLayoutFiles]: an unsaved layout of the phone with [bytes] that only [chosen]'s keyboards naming [name] use
 *  (a restore of the same backup before), else a new one; its name. */
private fun privateLayoutFor(ctx: Context, type: LayoutType, name: String, bytes: ByteArray, chosen: List<SettingsSubtype>,
                             renames: Map<LayoutType, Map<String, String>>): String {
    val scope = LayoutUtilsCustom.scopeOf(name, type)
    // a keyboard with its unsaved layouts left out, its named ones as they came in
    fun normalized(kb: SettingsSubtype) = LayoutType.entries.fold(kb) { k, t ->
        val n = k.layoutName(t) ?: return@fold k
        if (LayoutUtilsCustom.isPrivateLayout(n)) k.withLayout(t, "~") else renames[t]?.get(n)?.let { k.withLayout(t, it) } ?: k }
    val owners = chosen.filter { it.layoutName(type) == name }.map { normalized(it) }.toSet()
    val prefs = ctx.prefs()
    val phone = (SubtypeSettings.createSettingsSubtypes(prefs.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!)
        + SubtypeSettings.createSettingsSubtypes(prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!)).distinct()
    val reusable = LayoutUtilsCustom.getLayoutFiles(type, ctx).firstOrNull { file ->
        val candidate = file.name
        candidate != name && LayoutUtilsCustom.isPrivateLayout(candidate) && LayoutUtilsCustom.scopeOf(candidate, type) == scope
            && file.readBytes().contentEquals(bytes)
            && phone.filter { it.layoutName(type) == candidate }.let { users -> users.isNotEmpty() && users.all { normalized(it) in owners } }
    }
    return reusable?.name ?: LayoutUtilsCustom.makePrivateLayout(String(bytes), type, scope, ctx)
}

/**
 * The backup's loaded fonts and pictures (FontLibrary, PictureLibrary) into the phone's lists, for keyboards restored on
 * their own: a file the phone has under the same name with the same content is the phone's; a name the phone uses for
 * another file gets the next free number ("Mine 2.ttf"). The renames, fonts and pictures, by file name.
 */
private fun restoreLibraryFiles(ctx: Context, pending: PendingRestore): Pair<Map<String, String>, Map<String, String>> {
    val fonts = HashMap<String, String>(); val pictures = HashMap<String, String>()
    val dir = DeviceProtectedUtils.getFilesDir(ctx)
    ZipInputStream(FileInputStream(pending.file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val name = entry.name.substringAfter("unprotected${File.separator}", "")
            val folder = name.substringBefore(File.separator, "")
            if ((folder == "fonts" || folder == "pictures") && backupFilePatterns.any { name.matches(it) } && !name.removePrefix("$folder${File.separator}").contains(File.separator)) {
                val bytes = zip.readBytes()
                val fileName = name.removePrefix("$folder${File.separator}")
                val folderDir = File(dir, folder)
                var target = File(folderDir, fileName)
                // (and never outside the app's folder, whatever the zip says)
                if (target.canonicalPath.startsWith(folderDir.canonicalPath + File.separator)) {
                    if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                        if (target.exists()) {
                            // the next free number, reusing a numbered one with this content (a second restore adds nothing)
                            val base = fileName.substringBeforeLast('.', fileName); val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                            target = generateSequence(2) { it + 1 }.map { File(folderDir, "$base $it$ext") }
                                .first { !it.exists() || it.readBytes().contentEquals(bytes) }
                            (if (folder == "fonts") fonts else pictures)[fileName] = target.name
                        }
                        if (!target.exists()) { folderDir.mkdirs(); target.writeBytes(bytes) }
                    }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return fonts to pictures
}

/** The files behind the settings (restored with all of them): custom layouts (a keyboard's layout must exist for it),
 *  the background pictures, the loaded fonts (FontLibrary) and pictures (PictureLibrary), and the saved themes'
 *  pictures (re-review 2026-10-07: themes came back without them). */
internal fun isSettingsFile(path: String) = path.startsWith("layouts${File.separator}") || path.startsWith("custom_")
    || path.startsWith("fonts${File.separator}") || path.startsWith("pictures${File.separator}")
    || path.startsWith("looks${File.separator}")

/** What app start does to older settings, run right after a restore too, so an older backup's settings are moved now
 *  and not at the next start, over changes made in between (re-review 2026-10-07). */
internal fun restoreFollowUp(ctx: Context) {
    // the restored file's menu sharing first: the upgrade steps find each keyboard's own keys by it (re-review
    // 2026-10-07: they ran with the phone's, and a backup's HeliBoard keyboards missed their pins)
    KeyboardProfiles.loadGroups(ctx.realPrefs())
    checkVersionUpgrade(ctx)
    KeyboardProfiles.settingsMoves(ctx.realPrefs())
}

/** Reads the preferences entry of a backup, the keyboards listed in it and the names of all its entries. */
private fun readBackup(file: File): PendingRestore {
    var prefs: Map<String, Any?> = emptyMap()
    val entries = mutableSetOf<String>()
    ZipInputStream(FileInputStream(file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            entries.add(entry.name)
            if (entry.name == PREFS_FILE_NAME)
                prefs = readJsonLinesToMap(String(zip.readBytes()).split("\n"))
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    val keyboards = (prefs[Settings.PREF_ENABLED_SUBTYPES] as? String)?.let { SubtypeSettings.createSettingsSubtypes(it) }.orEmpty()
    return PendingRestore(file, prefs, keyboards, entries)
}

private fun readJsonLinesToMap(list: List<String>): Map<String, Any?> {
    val map = HashMap<String, Any?>()
    val i = list.iterator()
    while (i.hasNext()) {
        when (i.next()) {
            "boolean settings" -> map.putAll(Json.decodeFromString<Map<String, Boolean>>(i.next()))
            "int settings" -> map.putAll(Json.decodeFromString<Map<String, Int>>(i.next()))
            "long settings" -> map.putAll(Json.decodeFromString<Map<String, Long>>(i.next()))
            "float settings" -> map.putAll(Json.decodeFromString<Map<String, Float>>(i.next()))
            "string settings" -> map.putAll(Json.decodeFromString<Map<String, String>>(i.next()))
            "string set settings" -> map.putAll(Json.decodeFromString<Map<String, Set<String>>>(i.next()))
        }
    }
    return map
}

internal fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
    val booleans = settings.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
    val ints = settings.filter { it.key is String && it.value is Int } as Map<String, Int>
    val longs = settings.filter { it.key is String && it.value is Long } as Map<String, Long>
    val floats = settings.filter { it.key is String && it.value is Float } as Map<String, Float>
    val strings = settings.filter { it.key is String && it.value is String } as Map<String, String>
    val stringSets = settings.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>
    // now write
    out.write("boolean settings\n".toByteArray())
    out.write(Json.encodeToString(booleans).toByteArray())
    out.write("\nint settings\n".toByteArray())
    out.write(Json.encodeToString(ints).toByteArray())
    out.write("\nlong settings\n".toByteArray())
    out.write(Json.encodeToString(longs).toByteArray())
    out.write("\nfloat settings\n".toByteArray())
    out.write(Json.encodeToString(floats).toByteArray())
    out.write("\nstring settings\n".toByteArray())
    out.write(Json.encodeToString(strings).toByteArray())
    out.write("\nstring set settings\n".toByteArray())
    out.write(Json.encodeToString(stringSets).toByteArray())
}

private fun readJsonLinesToSettings(list: List<String>, prefs: SharedPreferences): Boolean {
    val i = list.iterator()
    val e = prefs.edit()
    try {
        while (i.hasNext()) {
            when (i.next()) {
                "boolean settings" -> Json.decodeFromString<Map<String, Boolean>>(i.next()).forEach { e.putBoolean(it.key, it.value) }
                "int settings" -> Json.decodeFromString<Map<String, Int>>(i.next()).forEach { e.putInt(it.key, it.value) }
                "long settings" -> Json.decodeFromString<Map<String, Long>>(i.next()).forEach { e.putLong(it.key, it.value) }
                "float settings" -> Json.decodeFromString<Map<String, Float>>(i.next()).forEach { e.putFloat(it.key, it.value) }
                "string settings" -> Json.decodeFromString<Map<String, String>>(i.next()).forEach { e.putString(it.key, it.value) }
                "string set settings" -> Json.decodeFromString<Map<String, Set<String>>>(i.next()).forEach { e.putStringSet(it.key, it.value) }
            }
        }
        e.apply()
        return true
    } catch (e: Exception) {
        return false
    }
}

// fork: personal dictionary backup — each entry is a map with "word", "frequency", and optionally "locale" / "shortcut"
private fun readPersonalDictionary(ctx: Context): List<Map<String, String>> {
    val projection = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY,
        UserDictionary.Words.LOCALE, UserDictionary.Words.SHORTCUT)
    val result = mutableListOf<Map<String, String>>()
    ctx.contentResolver.query(UserDictionary.Words.CONTENT_URI, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val word = cursor.getString(0) ?: continue
            val entry = mutableMapOf("word" to word, "frequency" to (cursor.getInt(1)).toString())
            cursor.getString(2)?.let { entry["locale"] = it }
            cursor.getString(3)?.let { entry["shortcut"] = it }
            result.add(entry)
        }
    }
    return result
}

// fork: merge entries into the system personal dictionary, skipping words that already exist for the same locale
private fun restorePersonalDictionary(ctx: Context, json: String) {
    val entries = Json.decodeFromString<List<Map<String, String>>>(json)
    if (entries.isEmpty()) return
    val existing = mutableSetOf<String>()
    ctx.contentResolver.query(UserDictionary.Words.CONTENT_URI,
        arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE), null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            existing.add("${cursor.getString(0)}|${cursor.getString(1) ?: ""}")
        }
    }
    entries.forEach { entry ->
        val word = entry["word"] ?: return@forEach
        if ("$word|${entry["locale"] ?: ""}" in existing) return@forEach
        val values = ContentValues().apply {
            put(UserDictionary.Words.WORD, word)
            put(UserDictionary.Words.FREQUENCY, entry["frequency"]?.toIntOrNull() ?: 250)
            put(UserDictionary.Words.APP_ID, 0)
            entry["locale"]?.let { put(UserDictionary.Words.LOCALE, it) }
            entry["shortcut"]?.let { put(UserDictionary.Words.SHORTCUT, it) }
        }
        ctx.contentResolver.insert(UserDictionary.Words.CONTENT_URI, values)
    }
}

private const val PREFS_FILE_NAME = "preferences.json"
private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"
private const val PERSONAL_DICT_FILE_NAME = "personal_dictionary.json"

internal val backupFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "dicts${File.separator}.*${File.separator}.*user\\.dict".toRegex(),
    "UserHistoryDictionary.*${File.separator}UserHistoryDictionary.*\\.(body|header)".toRegex(),
    "custom_background_image.*".toRegex(),
    "pictures${File.separator}[^${File.separator}]+".toRegex(), // the picture list shared by all keyboards
    // the saved themes' background pictures, a folder per theme (review 2026-10-06: they were left out, and applying
    // such a theme after a restore deleted the background)
    "looks${File.separator}[^${File.separator}]+${File.separator}[^${File.separator}]+".toRegex(),
    "custom_font".toRegex(), // the text style fonts of before; restored ones move into the list (FontLibrary)
    "fonts${File.separator}[^${File.separator}]+".toRegex(),
    "custom_emoji_font.*".toRegex(), // the emoji font of before, one for every keyboard; restored, it moves into the list (FontLibrary)
    "custom_hint_font".toRegex(),
    "custom_suggestion_font".toRegex(),
) }

/**
 * Adds the learned words of the backup ([learnedDir]: its stores and blacklists, unpacked) for the chosen [keyboards] to
 * the phone's, by script: each keyboard's own pool on the phone (LearnedKeyboards) gets what it had in the backup (its
 * own pool's stores there; in a backup from before each keyboard had its own, the shared ones (a copy for each, as the
 * update does) or its own if sharing was off then; in a backup from before the per-script stores, those of its
 * languages, each word into its script's). Counts add up, the latest last use is kept (mergeAdding); blacklists keep the
 * most strikes. A backup store is put into one phone store once, however many chosen keyboards lead there.
 */
private fun restoreLearnedWords(ctx: Context, backup: Map<String, Any?>, keyboards: List<Pair<SettingsSubtype, SettingsSubtype>>,
                                learnedDir: File, io: LearnedStoreIo) {
    val filesDir = ctx.filesDir ?: return
    val real = ctx.realPrefs()
    // (phone pool, script) -> the backup's stores and lists that go there
    val stores = LinkedHashMap<Pair<Int, String>, MutableSet<File>>()
    val lists = LinkedHashMap<Pair<Int, String>, MutableSet<File>>()
    // (each keyboard as it was in the backup, and as it is on the phone now)
    for ((keyboard, onPhone) in keyboards) {
        val pool = LearnedKeyboards.poolFor(real, onPhone)
        val backupPool = LearnedKeyboards.poolIn(backup, keyboard)
        val locales = listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues)
        if (backupPool != null) for (script in locales.map { it.script() }.distinct()) {
            stores.getOrPut(pool to script) { LinkedHashSet() }.add(LearnedStores.storeFile(learnedDir, script, backupPool))
            lists.getOrPut(pool to script) { LinkedHashSet() }.add(LearnedStores.blacklistFile(learnedDir, script, backupPool))
        }
        // a backup from before: per language, words of any script
        for (locale in locales) {
            stores.getOrPut(pool to "") { LinkedHashSet() }.add(File(learnedDir, "${UserHistoryDictionary.NAME}.${locale.toLanguageTag()}.dict"))
            lists.getOrPut(pool to "") { LinkedHashSet() }.add(File(learnedDir, "blacklists${File.separator}${locale.toLanguageTag()}.txt"))
        }
    }
    // the backup's entries for each phone store
    val toAdd = LinkedHashMap<Pair<Int, String>, MutableList<List<LearnedEntry>>>()
    for ((target, files) in stores) for (file in files) {
        if (!file.exists()) continue
        val (pool, script) = target
        if (script.isNotEmpty()) {
            val entries = io.readFile(file, LearnedStores.storeLocale(script)) ?: continue
            toAdd.getOrPut(target) { mutableListOf() }.add(entries)
        } else {
            val locale = Locale.forLanguageTag(file.name.removePrefix("${UserHistoryDictionary.NAME}.").removeSuffix(".dict"))
            val entries = io.readFile(file, locale) ?: continue
            for ((wordScript, part) in byScript(entries, locale.script()))
                toAdd.getOrPut(pool to wordScript) { mutableListOf() }.add(part)
        }
    }
    for ((target, parts) in toAdd) {
        val (pool, script) = target
        val phone = LearnedStoreFiles.read(io, filesDir, script, pool) ?: continue
        val merged = mergeAdding(listOf(phone) + parts)
        if (!LearnedStoreFiles.write(io, filesDir, script, pool, merged))
            Log.w("AdvancedScreen", "could not restore the learned words of $script")
        Log.i("AdvancedScreen", "restored learned words of ${LearnedStores.label(script, pool)}: ${countsOf(phone)} before, ${countsOf(merged)} after")
    }
    // (through the list objects the keyboard holds: a word on both keeps the most strikes)
    for ((target, files) in lists) for (file in files) {
        if (!file.isFile) continue
        val (pool, script) = target
        if (script.isNotEmpty()) RemovedWords.blacklist(ctx, script, pool).combine(file.readLines())
        else {
            val fallback = Locale.forLanguageTag(file.name.removeSuffix(".txt")).script()
            RemovedWords.parseAll(file.readLines()).entries.groupBy { ScriptUtils.scriptOfWord(it.key, fallback) }
                .forEach { (wordScript, entries) ->
                    RemovedWords.blacklist(ctx, wordScript, pool).combine(entries.map { RemovedWords.format(it.key, it.value) })
                }
        }
    }
}

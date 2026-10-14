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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
    // the chosen keyboards the dialog asked about, as answered: merged into a phone keyboard, kept separate or not
    // restored (owner 2026-10-11: restore matches by language and asks); the others come in as new
    val decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap(),
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
        // (a backup without keyboards, older format, too: it can only be restored whole, the dialog's checkbox stays
        // checked, and the wipe is asked about like any other, never in one tap)
        onChoose(pending)
    }
}

/** The restore dialog for the backup [file] (tests). */
@Composable
internal fun RestoreChoiceDialogFrom(file: File, onDismiss: () -> Unit) {
    val pending = remember(file) { readBackup(file) }
    RestoreChoiceDialog(pending, onDismiss, onError = { })
}

@Composable
private fun RestoreChoiceDialog(pending: PendingRestore, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    // each backup keyboard as it would come in, its custom key layouts named as they would be (B4, R6), and the
    // questions: a keyboard whose language the phone has (switched-off keyboards too) is asked about, merged into one of
    // those or kept separate (owner 2026-10-11: "only check if language is same"); the others are new
    val plan = remember(pending) { restorePlan(ctx, pending) }
    // on top: "Delete app data and restore from backup", checked to start with (owner 2026-10-11); unchecked, every
    // keyboard is ticked
    val top = remember(pending) { RestoreDialogState(pending.keyboards) }
    val selected = top.selected
    // after OK: the chosen keyboards still to be asked about, the phone keyboard picked for the first, and the answers
    var asking: List<SettingsSubtype>? by remember { mutableStateOf(null) }
    var choosing: SettingsSubtype? by remember { mutableStateOf(null) }
    val answers = remember { mutableMapOf<SettingsSubtype, RestoreDecision>() }
    var asCopies by rememberSaveable { mutableStateOf(true) }
    var settings by rememberSaveable { mutableStateOf(true) }
    var learnedWords by rememberSaveable { mutableStateOf(true) }
    var dictionaries by rememberSaveable { mutableStateOf(true) }
    var customWords by rememberSaveable { mutableStateOf(true) }
    var clipboard by rememberSaveable { mutableStateOf(false) }
    val withKeyboards = selected.isNotEmpty() // the "bring with them" rows mean nothing without a keyboard
    // once the restore started, the job deletes the file when done with it (2026-10-10: it runs after the dialog closed)
    var started by remember { mutableStateOf(false) }
    fun close() { if (!started) pending.file.delete(); onDismiss() }
    fun start() {
        val choice = RestoreChoice(selected.toList(), settings, learnedWords && pending.hasLearnedWords,
            dictionaries && pending.hasDictionaries, customWords && pending.hasCustomWords, clipboard && pending.hasClipboard, asCopies,
            answers.filterKeys { it in selected })
        var hidden: List<SettingsSubtype> = emptyList()
        started = runRestore(ctx, onError, R.string.backup_restored, restoreSteps(pending), note = { hiddenSettingsNote(ctx, hidden) }) {
            try { hidden = restoreChosen(ctx, pending, choice) } finally { pending.file.delete() }
        }
    }
    fun differences(keyboard: SettingsSubtype, phone: SettingsSubtype) = restoreDifferences(ctx, plan, keyboard, phone, settings, asCopies)
    // the next keyboard to ask about, or the restore when all are answered
    fun answered(keyboard: SettingsSubtype, decision: RestoreDecision) {
        answers[keyboard] = decision
        choosing = null
        val rest = asking.orEmpty().drop(1)
        asking = rest
        if (rest.isEmpty()) { start(); close() }
    }
    // the full restore, the top checkbox checked (owner 2026-10-11: "User should be given the option to do a full restore
    // which wipes, or partial keyboard by keyboard"): asked once before it wipes, no questions per keyboard
    var everything by remember { mutableStateOf(false) }
    if (everything) {
        ConfirmationDialog(
            onDismissRequest = { everything = false }, // (back to the restore dialog)
            title = { Text(stringResource(R.string.restore_delete_and_restore)) },
            content = { Text(stringResource(R.string.saving_full_everything)) },
            confirmButtonText = stringResource(R.string.restore_delete_and_restore),
            onConfirmed = {
                started = runRestore(ctx, onError, R.string.backup_restored, restoreSteps(pending)) {
                    try { restoreEverything(ctx, pending.file) } finally { pending.file.delete() }
                }
                close()
            },
        )
        return
    }
    val asked = asking?.firstOrNull()
    if (asked != null) {
        // (each keyboard's questions their own state; "what to merge" goes back to its first question)
        androidx.compose.runtime.key(asked) {
            val question = remember { MergeQuestion(plan, asked, selected.toList(), answers.toMap()) }
            val target = choosing
            if (target == null) RestoreMergeDialog(question,
                onCancel = { asking = null; answers.clear() }, // back to the restore dialog, nothing restored (as before)
                onNext = { pick -> if (pick.phone == null) answered(asked, question.separate) else choosing = pick.phone })
            else androidx.compose.runtime.key(target) {
                RestoreChooseDialog(question, target, remember(settings, asCopies) { differences(asked, target) },
                    wordsRow = learnedWords && pending.hasLearnedWords,
                    onCancel = { choosing = null }, // back to the first question
                    onConfirmed = { answered(asked, it) })
            }
        }
        return
    }
    ThreeButtonAlertDialog(
        // (OK closes the dialog after onConfirmed: not while the questions come)
        // (nor while the wipe is asked about)
        onDismissRequest = { if (asking == null && !everything) close() },
        title = { Text(stringResource(R.string.button_restore)) },
        content = {
            Column {
                // (a backup without a keyboard list: checked, it can only be restored whole)
                CheckRow(stringResource(R.string.restore_delete_app_data), top.wipe, top.canPickKeyboards) { top.check(it) }
                // checked: greyed out, every row showing what the full restore brings (all of it); "bring them in as
                // copies" means nothing then, it shows its own tick
                val wipe = top.wipe
                val rows = top.rowsEnabled
                @Composable fun Heading(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                if (pending.keyboards.isNotEmpty()) Heading(R.string.restore_keyboards)
                pending.keyboards.forEach { keyboard ->
                    val name = keyboardName(keyboard, ctx).let { if (plan.asks(keyboard)) it else stringResource(R.string.restore_keyboard_new, it) }
                    CheckRow(name, top.ticked(keyboard), rows) { if (it) selected.add(keyboard) else selected.remove(keyboard) }
                }
                Heading(R.string.restore_with_keyboards)
                CheckRow(stringResource(R.string.restore_settings), wipe || settings, rows && withKeyboards) { settings = it }
                // (every keyboard too: a keyboard restore never wipes, owner 2026-10-11)
                if (wipe || settings && withKeyboards)
                    CheckRow(stringResource(R.string.restore_saved_items_as_copies), asCopies, rows) { asCopies = it }
                if (pending.hasLearnedWords)
                    CheckRow(stringResource(R.string.restore_learned_words), wipe || learnedWords, rows && withKeyboards) { learnedWords = it }
                if (pending.hasDictionaries)
                    CheckRow(stringResource(R.string.restore_dictionaries), wipe || dictionaries, rows && withKeyboards) { dictionaries = it }
                if (pending.hasCustomWords || pending.hasClipboard) {
                    Heading(R.string.restore_also)
                    if (pending.hasCustomWords) CheckRow(stringResource(R.string.restore_custom_words), wipe || customWords, rows) { customWords = it }
                    if (pending.hasClipboard) CheckRow(stringResource(R.string.restore_clipboard), wipe || clipboard, rows) { clipboard = it }
                }
                if (top.showsMergeLine)
                    Text(stringResource(R.string.restore_merge_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        scrollContent = true,
        confirmFirst = true,
        checkOk = { top.wipe || withKeyboards || (customWords && pending.hasCustomWords) || (clipboard && pending.hasClipboard) },
        onConfirmed = {
            // checked: the wipe is asked about first (never in one tap)
            if (top.ok() == RestoreDialogState.Ok.ConfirmWipe) { everything = true; return@ThreeButtonAlertDialog }
            // the chosen keyboards whose language the phone has are asked about first, one by one
            answers.clear()
            val ask = selected.filter { plan.asks(it) }
            if (ask.isEmpty()) start() else asking = ask
        },
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp)) {
        Checkbox(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
        Text(text)
    }

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }

/** The name box of a merged or separate keyboard, and the line saying it would be the same as another keyboard. */
@Composable
private fun NameBox(name: String, sameAs: SettingsSubtype?, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    OutlinedTextField(value = name, onValueChange = onChange, singleLine = true, label = { Text(stringResource(R.string.restore_name)) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    if (sameAs != null)
        Text(stringResource(R.string.restore_same_as, keyboardName(sameAs, ctx)), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

/** A phone keyboard as the questions name it: "<name> (switched off)" for one switched off on the Languages screen. */
private fun phoneKeyboardLabel(ctx: Context, plan: RestorePlan, keyboard: SettingsSubtype) =
    if (plan.isSwitchedOff(keyboard)) ctx.getString(R.string.keyboard_switched_off, keyboardName(keyboard, ctx)) else keyboardName(keyboard, ctx)

/**
 * The first question for a chosen backup keyboard whose language the phone has: merge it into one of those keyboards (one
 * only), or keep it as a separate keyboard, named in a box (owner 2026-10-11). Nothing picked: Next waits for a pick.
 * Cancel goes back to the restore dialog, nothing restored (as before).
 */
@Composable
private fun RestoreMergeDialog(question: MergeQuestion, onCancel: () -> Unit, onNext: (MergeQuestion.Pick) -> Unit) {
    val ctx = LocalContext.current
    // (Next closes the dialog after onConfirmed: the answer moved on already)
    var answered by remember { mutableStateOf(false) }
    ThreeButtonAlertDialog(
        keepKeyboard = false, // (a text field)
        onDismissRequest = { if (!answered) onCancel() },
        title = { Text(stringResource(R.string.restore_merge_title, keyboardName(question.keyboard, ctx))) },
        content = {
            Column {
                Text(stringResource(R.string.restore_merge_message), modifier = Modifier.padding(bottom = 8.dp))
                question.options.forEach { phone ->
                    RadioRow(phoneKeyboardLabel(ctx, question.plan, phone), question.picked?.phone == phone) { question.picked = MergeQuestion.Pick(phone) }
                }
                val separate = question.picked.let { it != null && it.phone == null }
                RadioRow(stringResource(R.string.restore_keep_separate), separate) { question.picked = MergeQuestion.Pick(null) }
                if (separate) NameBox(question.separateName, question.separateSameAs) { question.separateName = it }
            }
        },
        scrollContent = true,
        checkOk = { question.canGoOn },
        confirmButtonText = stringResource(R.string.restore_next),
        onConfirmed = { answered = true; onNext(question.picked!!) },
    )
}

/**
 * What to merge of the backup's keyboard into the phone's [target]: what differs ([differences]), each unticked (a ticked
 * one takes the backup's, an unticked one keeps the phone's), with [wordsRow] its learned and blacklisted words, ticked,
 * and the name box ([MergeChoice]). Nothing ticked: "Don't restore"; nothing differing and no words: Done. A result the
 * same as another keyboard can't be confirmed (owner 2026-10-11: "Yes, keep fully identical block").
 */
@Composable
private fun RestoreChooseDialog(question: MergeQuestion, target: SettingsSubtype, differences: List<RestoreMatch.Difference>,
                                wordsRow: Boolean, onCancel: () -> Unit, onConfirmed: (RestoreDecision) -> Unit) {
    val ctx = LocalContext.current
    val choice = remember(differences) { MergeChoice(question, target, differences, wordsRow) }
    var confirmed by remember { mutableStateOf(false) }
    val decision = choice.decision
    ThreeButtonAlertDialog(
        keepKeyboard = false, // (a text field)
        // (OK closes the dialog after onConfirmed: the answer moved on already)
        onDismissRequest = { if (!confirmed) onCancel() },
        title = { Text(stringResource(R.string.restore_differs_choose, phoneKeyboardLabel(ctx, question.plan, target))) },
        content = {
            Column {
                if (choice.nothingDiffers) {
                    Text(stringResource(R.string.restore_nothing_differs, keyboardName(target, ctx)))
                    NameBox(choice.name, choice.sameAs) { choice.typed = it }
                    return@Column
                }
                Text(stringResource(R.string.restore_differs_choose_message), modifier = Modifier.padding(bottom = 8.dp))
                choice.replacesNote(ctx)?.let { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }
                differences.forEach { difference ->
                    CheckRow(RestoreMatch.rowText(ctx, difference, question.keyboard), difference in choice.ticked) {
                        if (it) choice.ticked.add(difference) else choice.ticked.remove(difference)
                    }
                }
                if (wordsRow) CheckRow(stringResource(R.string.restore_learned_row), choice.wordsTicked) { choice.wordsTicked = it }
                if (decision is RestoreDecision.Skip)
                    Text(stringResource(R.string.restore_nothing_ticked, keyboardName(question.keyboard, ctx)), modifier = Modifier.padding(top = 8.dp))
                else NameBox(choice.name, choice.sameAs) { choice.typed = it }
            }
        },
        scrollContent = true,
        checkOk = { choice.sameAs == null },
        confirmButtonText = stringResource(when {
            choice.nothingDiffers -> R.string.restore_done
            decision is RestoreDecision.Skip -> R.string.restore_dont_restore
            else -> android.R.string.ok
        }),
        onConfirmed = { confirmed = true; onConfirmed(choice.decision) },
    )
}

/** What the restore says when done, if [keyboards] got their settings while one set is for all (owner 2026-10-11, R7:
 *  "should inform so it doesn't look like nothing happened"), else null: the others' wait in their own sets, the main
 *  keyboard's are the common settings, which all keyboards use now (owner 2026-10-11, no separate common set). */
internal fun hiddenSettingsNote(ctx: Context, keyboards: List<SettingsSubtype>): String? {
    if (keyboards.isEmpty()) return null
    val main = KeyboardProfiles.mainKeyboard(ctx.realPrefs()).takeIf { it in keyboards }
    val names = keyboards.filter { it != main }.map { keyboardName(it, ctx) }
    val others = if (names.isEmpty()) null else if (names.size == 1) ctx.getString(R.string.restore_settings_hidden_one, names.single())
        else ctx.getString(R.string.restore_settings_hidden_many, names.joinToString(", "))
    val mainNote = main?.let { ctx.getString(R.string.restore_settings_main, keyboardName(it, ctx)) }
    return listOfNotNull(mainNote, others).joinToString("\n")
}

/** The steps of restoring [pending]'s job: each of its entries, then the learned words (the settings: see runRestore). */
private fun restoreSteps(pending: PendingRestore) = pending.entries.size + 1

/**
 * Runs [work] (counting [steps] steps) and what follows a restore on the keyboard executor, with the progress dialog
 * up, then refreshes on the main thread what must be refreshed there, and closes the dialog (review 2026-10-08 M2,
 * 2026-10-10: the screen thread waited for the whole job, and a touch meanwhile was an ANR that could leave half a
 * restore). False when a backup or restore is running already. [onError] is called on the main thread.
 */
internal fun runRestore(ctx: Context, onError: (String) -> Unit, doneMessage: Int, steps: Int = 0, note: () -> String? = { null },
                        work: () -> Unit): Boolean {
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
            // (what the restore has to say, once: else the done message)
            else Toast.makeText(app, note() ?: app.getString(doneMessage), Toast.LENGTH_LONG).show()
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
                // as stored, not through a keyboard's view (2026-10-11: with no common set the view put the backup's
                // plain keys into the first keyboard's set; the follow-up's upgrade step deals with an old common set)
                val prefs = ctx.realPrefs()
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
                               io: LearnedStoreIo, decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap()) =
    restoreChosen(ctx, readBackup(file), RestoreChoice(keyboards, settings, learnedWords, false, false, false, decisions = decisions), io)

/**
 * Brings what [choice] asks for out of the backup and leaves the rest of the phone as it is: the keyboards keyboard by
 * keyboard, all of them too ([restoreKeyboards]; only the full restore wipes, [restoreEverything]). Learned words and
 * added dictionaries follow the chosen keyboards' languages.
 */
private fun restoreChosen(ctx: Context, pending: PendingRestore, choice: RestoreChoice, io: LearnedStoreIo = LearnedStoreIo.Native): List<SettingsSubtype> =
    LearnedKeyboards.withFiles {
        // (each keyboard its own learned words first, if this start couldn't do it yet: they're restored by keyboard)
        LearnedKeyboards.updateOnce(ctx)
        restoreChosenLocked(ctx, pending, choice, io)
    }

/** The result: the restored keyboards whose settings wait behind the common ones (separate settings off, R7). */
private fun restoreChosenLocked(ctx: Context, pending: PendingRestore, choice: RestoreChoice, io: LearnedStoreIo): List<SettingsSubtype> {
    // (one answered "Don't restore" brings nothing: not its learned words or dictionaries either)
    val restoring = choice.keyboards.filter { choice.decisions[it] != RestoreDecision.Skip }
    // keyboard by keyboard, every keyboard of the backup too (owner 2026-10-11: a keyboard restore never wipes; only the
    // full restore does: "partial keyboard by keyboard where all keyboards are just another case"). Each restored
    // keyboard as it is on the phone now (its custom key layouts can come in under other names; merged: the phone
    // keyboard it went into, its definition the phone's in parts)
    var onPhone: Map<SettingsSubtype, SettingsSubtype> = emptyMap()
    var hidden: List<SettingsSubtype> = emptyList()
    if (choice.keyboards.isNotEmpty())
        restoreKeyboards(ctx, pending, restoring, choice.settings, choice.asCopies, choice.decisions).let { onPhone = it.first; hidden = it.second }
    val tags = restoring.flatMap { listOf(it.locale) + getSecondaryLocales(it.extraValues) }.map { it.toLanguageTag() }.toSet()
    val filesDir = ctx.filesDir ?: return hidden
    val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    // learned words are added to the phone's, never replacing them: the backup's stores are unpacked aside, then put
    // together with the phone's (counts added); the removed-word lists are combined. The backup's stores are per
    // script (shared, or a keyboard's own: UserHistoryDictionary.<script>[.k<id>].dict), or per language in a backup
    // from before (UserHistoryDictionary.<languageTag>.dict); the chosen keyboards' scripts and languages say which.
    val learnedDir = File(ctx.cacheDir, "restore_learned").apply { deleteRecursively() }
    val scripts = restoring.flatMap { listOf(it.locale) + getSecondaryLocales(it.extraValues) }.map { it.script() }.toSet()
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
            }
            zip.closeEntry()
            BackupRestoreJob.step()
            entry = zip.nextEntry
        }
    }
    if (choice.clipboard) Database.copyFromDb(restoredDb, ctx)
    if (choice.learnedWords) {
        // (merged: with its learned words ticked only)
        restoreLearnedWords(ctx, pending.prefs, onPhone.filterKeys { (choice.decisions[it] as? RestoreDecision.Merge)?.learnedWords != false }
            .toList(), learnedDir, io)
        learnedDir.deleteRecursively()
    }
    BackupRestoreJob.step() // (the learned words)
    LayoutUtilsCustom.onLayoutFileChanged()
    return hidden
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
internal fun restoreKeyboardsFrom(ctx: Context, file: File, keyboards: List<SettingsSubtype>, withSettings: Boolean, asCopies: Boolean = true,
                                  decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap()) =
    restoreKeyboards(ctx, readBackup(file), keyboards.filter { decisions[it] != RestoreDecision.Skip }, withSettings, asCopies, decisions)

/**
 * Only [asked] keyboards come out of the backup, each as [decisions] says (owner 2026-10-11: restore matches by language
 * and asks; review R1: no matching of its own here): merged into the phone keyboard picked (in its place, its set and
 * learned words going along; the backup's whole, or the phone's with the ticked parts and menus the backup's, those
 * parts' key layouts named as they came in), or added as a keyboard of its own; named as answered. One without an answer
 * comes in as new, as it is in the backup; one that would be the same as a keyboard on the phone (switched off included)
 * is not restored, never replacing it. Each comes with its custom layout files and, [withSettings], its settings and
 * the priorities of its languages, in its own set only; other keyboards stay as they are. A custom layout the phone has
 * under the same name with other keys comes in numbered ([restoreLayoutFiles]), and the keyboard names that one.
 * With one set of settings for all, separate settings stay off (owner 2026-10-11: R7, a restore never turns separate
 * settings on): the keyboard's own set waits until they are turned on, the main keyboard's is what all keyboards use.
 * The result: each restored keyboard as it is on the phone now, and those restored while settings are one for all.
 */
private fun restoreKeyboards(ctx: Context, pending: PendingRestore, asked: List<SettingsSubtype>, withSettings: Boolean,
                             asCopies: Boolean = true, decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap()
): Pair<Map<SettingsSubtype, SettingsSubtype>, List<SettingsSubtype>> {
    // the unsaved layouts no keyboard uses go once all is restored, not between the keyboards (review 2026-10-11 R3: one
    // only a restored keyboard's settings name went before they were written)
    LayoutUtilsCustom.cleanupHeld++
    try { return restoreKeyboardsHeld(ctx, pending, asked, withSettings, asCopies, decisions) }
    finally { LayoutUtilsCustom.cleanupHeld--; LayoutUtilsCustom.removeUnusedPrivateLayouts(ctx) }
}

private fun restoreKeyboardsHeld(ctx: Context, pending: PendingRestore, asked: List<SettingsSubtype>, withSettings: Boolean,
                                 asCopies: Boolean, decisions: Map<SettingsSubtype, RestoreDecision>
): Pair<Map<SettingsSubtype, SettingsSubtype>, List<SettingsSubtype>> {
    val real = ctx.realPrefs()
    val prefs = ctx.prefs()
    val backup = pending.prefs
    val nameOf = { keyboard: SettingsSubtype -> keyboardName(keyboard, ctx) }
    // never two keyboards of one definition (one identity): compared as they would come in, as the dialog does
    val chosen = run {
        val incoming = incomingKeyboards(pending, dryLayouts(ctx, pending))
        val phone = phoneKeyboards(real).let { (on, off) -> (on + off).toMutableList() }
        asked.filter { keyboard ->
            val decision = decisions[keyboard]
            val target = (decision as? RestoreDecision.Merge)?.phone
            val result = RestoreDecision.result(decision, incoming[keyboard] ?: keyboard, nameOf) ?: return@filter false
            if (phone.any { it == result && it != target }) {
                Log.w("AdvancedScreen", "not restoring ${keyboard.toPref()}: it would be the same as ${result.toPref()}")
                return@filter false
            }
            target?.let { phone.remove(it) }
            phone.add(result)
            true
        }
    }
    if (chosen.isEmpty()) return emptyMap<SettingsSubtype, SettingsSubtype>() to emptyList() // (nothing to bring, nothing touched)
    val replaces = chosen.mapNotNull { keyboard -> (decisions[keyboard] as? RestoreDecision.Merge)?.let { keyboard to it.replace } }.toMap()
    // (a keyboard merged in parts: only its ticked parts and menus come from the backup)
    fun takesLayout(keyboard: SettingsSubtype, type: LayoutType) = replaces[keyboard]?.takes(RestoreMatch.Part.Layout(type)) != false
    fun takesSetting(keyboard: SettingsSubtype, key: String) =
        replaces[keyboard]?.menus?.let { menus -> RestoreMatch.menusOf(key).any { it in menus } } ?: true
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
    // and every other keyboard using it silently got the backup's keys). Only those of the parts and menus taken from
    // the backup (a layout kept as the phone's keeps its file too)
    val wanted = (chosen.flatMap { keyboard -> LayoutType.entries.filter { takesLayout(keyboard, it) }
            .mapNotNull { type -> keyboard.layoutName(type)?.let { type to it } } } +
        backupSettings.flatMap { (keyboard, values) -> LayoutType.entries.mapNotNull { type ->
            val key = Settings.PREF_LAYOUT_PREFIX + type.name
            (values[key] as? String)?.takeIf { takesSetting(keyboard, key) }?.let { type to it } } })
        .filter { LayoutUtilsCustom.isCustomLayout(it.second) }.toSet()
    val keyLayouts = if (wanted.isEmpty()) emptyMap() else restoreLayoutFiles(ctx, pending, wanted, chosen)
    // one mapping, backup keyboard -> the phone keyboard it becomes: its key layouts as they came in (B4), merged or
    // added as answered, named as answered
    val onPhone = chosen.associateWith { keyboard -> RestoreDecision.result(decisions[keyboard], renamedLayouts(keyboard, keyLayouts), nameOf)!! }
    // (their kept palette copies: the next free numbers where the phone's other sets use them, review C4;
    // the sets of the phone keyboards they merge into don't count)
    val replacing = KeyboardProfiles.idEntries(real).let { ids ->
        (chosen + onPhone.values + replaces.values.map { it.phone }).mapNotNull { ids[it.toPref()] } }.toSet() - KeyboardProfiles.SHARED
    val phone = real.all.filterKeys { KeyboardProfiles.splitOwnKey(it)?.first !in replacing }
    // kept palettes: a merge target whose Appearance stays the phone's keeps its "~custom:N" (owner 2026-10-11, R2: "make
    // sure nothing gets trashed"; it didn't count, and another restored keyboard or theme came in under its name)
    val keepsLook = KeyboardProfiles.idEntries(real).let { ids ->
        replaces.values.filter { !it.takes(KeyboardProfiles.Group.APPEARANCE) }.mapNotNull { ids[it.phone.toPref()] } }.toSet()
    val phonePalettes = real.all.filterKeys { key -> KeyboardProfiles.splitOwnKey(key)?.first.let { it !in replacing || it in keepsLook } }
    val renames = if (withSettings) SavedItemsMerge.merge(ctx, real, backup, asCopies, files?.first.orEmpty(), files?.second.orEmpty(), keyLayouts,
        chosen.map { KeyboardProfiles.effectiveSettingsIn(backup, it) }, phonePalettes) else null
    val settings = backupSettings.mapValues { (_, values) -> renames?.apply(values) ?: values }
    val wasSeparate = KeyboardProfiles.isSeparate(real)

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
        val plan = replaces[keyboard]
        // registers it unless it equals a built-in one; merged, the phone keyboard picked is changed into it (in its
        // place, its set and learned words going along, as a Rename does); a switched-off one is switched on (restored)
        SubtypeUtilsAdditional.changeAdditionalSubtype(plan?.phone ?: phoneKeyboard, phoneKeyboard, ctx)
        if (SubtypeSettings.getEnabledSubtypes().none { it.toSettingsSubtype() == phoneKeyboard })
            SubtypeSettings.addEnabledSubtype(prefs, phoneKeyboard.toAdditionalSubtype())
        if (withSettings) {
            val id = KeyboardProfiles.idFor(real, phoneKeyboard) // (a restore brings it: creates, owner 2026-10-11)
            // exactly that set, its own only, a shared menu's values too (owner 2026-10-11: "restore should only apply to
            // that keyboard's settings"; the main keyboard's set is the common settings: restored, everyone sees it, owner
            // 2026-10-11: "yep"). Merged in parts: only its entries of the ticked menus, the others stay as they are
            val restored = settings.getValue(keyboard)
            if (plan?.menus != null) KeyboardProfiles.restoreSet(real, id, restored) { takesSetting(keyboard, it) }
            else KeyboardProfiles.restoreSet(real, id, restored)
            // an item it chose that wasn't brought in (its name is the phone's for other content, copies off): it names
            // none and shows "Custom" (owner 2026-10-11, option 2); of the menus taken from the backup only
            renames?.customKinds(backupSettings.getValue(keyboard).filterKeys { takesSetting(keyboard, it) })?.forEach {
                helium314.keyboard.settings.CustomLabels.markOwn(real, id, it) }
            // its languages' priorities, into its own set (review session 2026-10-07): the backup's own copy for this
            // keyboard (an old backup's common value is ignored, owner 2026-10-11: "fine to trash common set with upgrade
            // and restore"; merged in parts: only when its languages are the backup's)
            val backupId = KeyboardProfiles.idIn(backup, keyboard)
            if (plan?.takes(RestoreMatch.Part.Extra(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.SECONDARY_LOCALES)) != false) real.edit {
                for (locale in listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues))
                    for (key in LanguagePriority.keys(locale).filter { LanguagePriority.isPriorityKey(it) }) {
                        val value = backupId?.let { backup[KeyboardProfiles.ownKey(it, key)] } as? Int ?: continue
                        putInt(KeyboardProfiles.ownKey(id, key), value)
                    }
            }
            // its own pictures in the backup, or the backup's shared ones when it had no set of its own (merged in
            // parts: with Appearance ticked)
            if (plan?.takes(KeyboardProfiles.Group.APPEARANCE) != false)
                KeyboardProfiles.restoreFiles(pictureFiles, KeyboardProfiles.idIn(backup, keyboard), id,
                    perKeyboardBackup = backup["profile_files_migrated"] == true)
        }
    }
    if (withSettings) {
        Settings.clearCachedBackgroundImages(); helium314.keyboard.keyboard.KeyboardTypeface.clearCache()
        // whether they use the other keyboards' learned and blacklisted words, as in the backup (merged in parts: the
        // phone's buttons stay)
        LearnedKeyboards.restoreButtons(real, backup, chosen.filter { replaces[it]?.whole != false }, onPhone)
    }
    // (merged with no menu ticked: none of its settings came, nothing to say)
    return onPhone to (if (withSettings && !wasSeparate) chosen.filter { replaces[it]?.menus?.isEmpty() != true }.map { onPhone.getValue(it) } else emptyList())
}

/** [keyboard] with its custom key layouts named as they came in ([keyLayouts], by type: [restoreLayoutFiles]). */
private fun renamedLayouts(keyboard: SettingsSubtype, keyLayouts: Map<LayoutType, Map<String, String>>) =
    LayoutType.entries.fold(keyboard) { kb, type -> kb.layoutName(type)?.let { keyLayouts[type]?.get(it) }?.let { kb.withLayout(type, it) } ?: kb }

/** The phone's keyboards: the enabled ones, and those switched off on the Languages screen (they keep their id and own
 *  set; owner 2026-10-11: treated like switched-on ones). */
private fun phoneKeyboards(real: SharedPreferences): Pair<List<SettingsSubtype>, List<SettingsSubtype>> {
    val enabled = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
    val off = KeyboardProfiles.idEntries(real).filterValues { it != KeyboardProfiles.SHARED }.keys
        .map { it.toSettingsSubtype() }.filter { it !in enabled }.distinct()
    return enabled to off
}

/** The custom key layouts of a restore of [pending]'s keyboards as [restoreLayoutFiles] would bring them, nothing written:
 *  the [renames], by type, and the files it would write, [planned] (path to keys). */
private class DryLayouts(val renames: Map<LayoutType, Map<String, String>>, private val planned: Map<String, ByteArray>, private val dir: File) {
    private fun keysOf(type: LayoutType, name: String): ByteArray? =
        File(File(dir, type.folder), name).let { f -> planned[f.path] ?: f.takeIf { it.isFile }?.readBytes() }

    /** Whether [a] and [b] are the same keys: one name, or two unsaved layouts with the same keys (review 2026-10-11 R3;
     *  owner 2026-10-11: "Screen 2 should handle unsaved items as named yes"). */
    fun same(type: LayoutType, a: String, b: String) = a == b || (LayoutUtilsCustom.isPrivateLayout(a) && LayoutUtilsCustom.isPrivateLayout(b)
        && keysOf(type, a)?.let { x -> keysOf(type, b)?.contentEquals(x) } == true)
}

/** [DryLayouts] for [pending]'s keyboards: their key layouts and their settings' defaults for the types. An unsaved one
 *  with other keys than the phone's of its name gets its planned new name, as the restore gives it one (R3). */
private fun dryLayouts(ctx: Context, pending: PendingRestore): DryLayouts {
    val wanted = (pending.keyboards.flatMap { keyboard -> LayoutType.entries.mapNotNull { type -> keyboard.layoutName(type)?.let { type to it } } } +
        pending.keyboards.flatMap { keyboard -> val values = restoredSettings(ctx, pending.prefs, keyboard)
            LayoutType.entries.mapNotNull { type -> (values[Settings.PREF_LAYOUT_PREFIX + type.name] as? String)?.let { type to it } } })
        .filter { LayoutUtilsCustom.isCustomLayout(it.second) }.toSet()
    val planned = HashMap<String, ByteArray>()
    val renames = if (wanted.isEmpty()) emptyMap() else try {
        restoreLayoutFiles(ctx, pending, wanted, pending.keyboards, write = false, planned = planned)
    } catch (e: Exception) { planned.clear(); emptyMap() } // (then compared as in the backup)
    return DryLayouts(renames, planned, DeviceProtectedUtils.getFilesDir(ctx))
}

/** For the restore dialog: [pending]'s keyboards as they would come in, their custom key layouts named as [dry] has them. */
private fun incomingKeyboards(pending: PendingRestore, dry: DryLayouts): Map<SettingsSubtype, SettingsSubtype> =
    pending.keyboards.associateWith { renamedLayouts(it, dry.renames) }

/** The restore dialog's questions for [pending]'s keyboards: each as it would come in, and the phone's keyboards. A
 *  backup keyboard's settings are compared with its saved items named as the merge would name them, worked out with
 *  nothing written (R3: before, the backup's "Sunset" coming in as "Sunset 2" compared as "Sunset"). */
private fun restorePlan(ctx: Context, pending: PendingRestore): RestorePlan {
    val real = ctx.realPrefs()
    val (enabled, off) = phoneKeyboards(real)
    val dry = dryLayouts(ctx, pending)
    val renames = HashMap<Boolean, SavedItemsMerge.Renames?>()
    fun renames(asCopies: Boolean) = renames.getOrPut(asCopies) {
        try {
            val files = restoreLibraryFiles(ctx, pending, write = false)
            SavedItemsMerge.merge(ctx, real, pending.prefs, asCopies, files.first, files.second, dry.renames,
                pending.keyboards.map { KeyboardProfiles.effectiveSettingsIn(pending.prefs, it) }, real.all, write = false)
        } catch (e: Exception) { null } // (then compared as in the backup)
    }
    return RestorePlan(incomingKeyboards(pending, dry), enabled, off, nameOf = { keyboardName(it, ctx) }, sameLayout = dry::same,
        backupSettings = { keyboard, asCopies -> restoredSettings(ctx, pending.prefs, keyboard).let { renames(asCopies)?.apply(it) ?: it } })
}

/** The restore dialog's questions for the backup [file]'s keyboards (tests). */
internal fun restorePlanFrom(ctx: Context, file: File): RestorePlan = restorePlan(ctx, readBackup(file))

/** "What to merge" for the backup [keyboard] into the phone's [phone]: what differs ([RestoreMatch.differences]), the
 *  key layouts and saved items as the restore would bring them in ([plan]; R3). */
private fun restoreDifferences(ctx: Context, plan: RestorePlan, keyboard: SettingsSubtype, phone: SettingsSubtype,
                               withSettings: Boolean, asCopies: Boolean): List<RestoreMatch.Difference> =
    RestoreMatch.differences(phone, plan.incoming.getValue(keyboard), withSettings,
        { RestoreMatch.phoneSettings(ctx.realPrefs(), phone) }, { plan.backupSettings(keyboard, asCopies) }, plan.sameLayout)

/** [restoreDifferences] for the backup [file] (tests). */
internal fun restoreDifferencesFrom(ctx: Context, file: File, keyboard: SettingsSubtype, phone: SettingsSubtype,
                                    withSettings: Boolean = true, asCopies: Boolean = true): List<RestoreMatch.Difference> =
    restoreDifferences(ctx, restorePlanFrom(ctx, file), keyboard, phone, withSettings, asCopies)

/**
 * The custom key layouts [wanted] (type to name) out of the backup, for [chosen] keyboards restored on their own: one the
 * phone has under the same name with the same keys is the phone's; a name the phone uses for other keys gets the next
 * free number ("My numrow 2", reusing a numbered one with these keys, so a second restore adds nothing). An unsaved one
 * (a keyboard's own) gets a new unsaved name, reusing one with these keys only if no keyboard but the same one uses it.
 * Nothing on the phone is written over. The renames, by type. Not [write]: what they would be, nothing written, the
 * files it would write in [planned] (path to keys); an unsaved one's new name is planned too (review 2026-10-11 R3; owner
 * 2026-10-11: "Screen 2 should handle unsaved items as named yes").
 */
private fun restoreLayoutFiles(ctx: Context, pending: PendingRestore, wanted: Set<Pair<LayoutType, String>>,
                               chosen: List<SettingsSubtype>, write: Boolean = true,
                               planned: HashMap<String, ByteArray> = HashMap()): Map<LayoutType, Map<String, String>> {
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
    // not [write]: the files it would have written, as if written (review 2026-10-11 R6: else a name numbered earlier in
    // the run looked free to a later one)
    fun exists(f: File) = f.path in planned || f.exists()
    fun bytesOf(f: File) = planned[f.path] ?: f.readBytes()
    fun put(f: File, bytes: ByteArray) { if (write) f.writeBytes(bytes) else planned[f.path] = bytes }
    // (named ones first: whether an unsaved one's keyboard is the same is seen with its named layouts as they came in)
    for ((key, bytes) in contents.entries.sortedBy { LayoutUtilsCustom.isPrivateLayout(it.key.second) }) {
        val (type, name) = key
        val folderDir = File(dir, type.folder)
        val target = File(folderDir, name)
        // (never outside the app's folder, whatever the zip says)
        if (!target.canonicalPath.startsWith(folderDir.canonicalPath + File.separator)) continue
        if (!exists(target)) { if (write) folderDir.mkdirs(); put(target, bytes); continue }
        if (bytesOf(target).contentEquals(bytes)) continue
        val newName = if (LayoutUtilsCustom.isPrivateLayout(name)) privateLayoutFor(ctx, type, name, bytes, chosen, renames)
            ?: LayoutUtilsCustom.scopeOf(name, type).let { scope ->
                if (write) LayoutUtilsCustom.makePrivateLayout(String(bytes), type, scope, ctx)
                else LayoutUtilsCustom.newPrivateLayoutName(type, scope) { exists(File(folderDir, it)) }.also { put(File(folderDir, it), bytes) } }
        else generateSequence(2) { it + 1 }.map { LayoutUtilsCustom.numberedLayoutName(name, type, it) }
            .first { val f = File(folderDir, it); !exists(f) || bytesOf(f).contentEquals(bytes) }
            .also { val f = File(folderDir, it); if (!exists(f)) put(f, bytes) }
        renames.getOrPut(type) { HashMap() }[name] = newName
    }
    if (write) LayoutUtilsCustom.onLayoutFileChanged()
    return renames
}

/** For [restoreLayoutFiles]: an unsaved layout of the phone with [bytes] that only [chosen]'s keyboards naming [name] use
 *  (a restore of the same backup before), its name; null: none (a new one comes in). */
private fun privateLayoutFor(ctx: Context, type: LayoutType, name: String, bytes: ByteArray, chosen: List<SettingsSubtype>,
                             renames: Map<LayoutType, Map<String, String>>): String? {
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
    return reusable?.name
}

/**
 * The backup's loaded fonts and pictures (FontLibrary, PictureLibrary) into the phone's lists, for keyboards restored on
 * their own: a file the phone has under the same name with the same content is the phone's; a name the phone uses for
 * another file gets the next free number ("Mine 2.ttf"). The renames, fonts and pictures, by file name. Not [write]:
 * what they would be, nothing written (review 2026-10-11 R3: the restore question compares with them).
 */
private fun restoreLibraryFiles(ctx: Context, pending: PendingRestore, write: Boolean = true): Pair<Map<String, String>, Map<String, String>> {
    val fonts = HashMap<String, String>(); val pictures = HashMap<String, String>()
    val dir = DeviceProtectedUtils.getFilesDir(ctx)
    // (not [write]: the files it would have written, as if written)
    val planned = HashMap<String, ByteArray>()
    fun File.there() = path in planned || exists()
    fun File.bytes() = planned[path] ?: readBytes()
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
                    if (!target.there() || !target.bytes().contentEquals(bytes)) {
                        if (target.there()) {
                            // the next free number, reusing a numbered one with this content (a second restore adds nothing)
                            val base = fileName.substringBeforeLast('.', fileName); val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                            target = generateSequence(2) { it + 1 }.map { File(folderDir, "$base $it$ext") }
                                .first { !it.there() || it.bytes().contentEquals(bytes) }
                            (if (folder == "fonts") fonts else pictures)[fileName] = target.name
                        }
                        if (!target.there()) { if (write) { folderDir.mkdirs(); target.writeBytes(bytes) } else planned[target.path] = bytes }
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
    // (the moves first, as at app start: the last upgrade step drops an old backup's common set, which they still move
    // values into; 2026-10-11)
    KeyboardProfiles.settingsMoves(ctx.realPrefs())
    checkVersionUpgrade(ctx, restoring = true)
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
        val pool = LearnedKeyboards.poolFor(real, onPhone) // (a restore brings it: creates, owner 2026-10-11)
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

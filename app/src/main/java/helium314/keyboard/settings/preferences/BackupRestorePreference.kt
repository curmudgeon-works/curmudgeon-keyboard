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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
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
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.personalization.LearnedEntry
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.personalization.LearnedStoreFiles
import helium314.keyboard.latin.personalization.LearnedStoreIo
import helium314.keyboard.latin.personalization.LearnedStoreMigration
import helium314.keyboard.latin.personalization.LearnedStores
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.personalization.RestoreGate
import helium314.keyboard.latin.personalization.UserHistoryDictionary
import helium314.keyboard.latin.personalization.byScript
import helium314.keyboard.latin.personalization.countsOf
import helium314.keyboard.latin.personalization.mergeHighest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val backupLauncher = backupLauncher(onError = { error = it })
    val restore = restoreEntry(onError = { error = it })
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
                restore()
            },
            onConfirmed = { backupLauncher.launch(backupIntent(ctx)) }
        )
    }
    BackupRestoreJob.progress?.let { ProgressDialog(it) }
    error?.let { BackupRestoreErrorDialog(it) { error = null } }
}

/** A backup's or restore's error, [error] as [runBackup] and [runRestore] give it; OK closes it ([onDismiss]). */
@Composable
private fun BackupRestoreErrorDialog(error: String, onDismiss: () -> Unit) {
    InfoDialog(
        if (error.startsWith("b"))
            stringResource(R.string.backup_error, error.drop(1))
        // (the full restore found the backup unreadable before it deleted anything, owner 2026-10-12)
        else if (error.startsWith("u")) stringResource(R.string.restore_unreadable)
        // (a keyboard by keyboard restore that failed after it changed something: nothing undone, A3 owner 2026-10-12)
        else if (error.startsWith("p")) stringResource(R.string.restore_partway)
        else stringResource(R.string.restore_error, error.drop(1)),
        onDismiss
    )
}

/**
 * The restore's way in: the function returned opens the file picker, and the restore dialog of the backup picked
 * follows (Backup and restore, and [UnfinishedRestoreNote]'s Restore again). [onError] gets the restore's error.
 */
@Composable
internal fun restoreEntry(onError: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var picked: PickedBackup? by remember { mutableStateOf(null) }
    val restoreLauncher = restoreLauncher { picked = it }
    picked?.let { pick ->
        androidx.compose.runtime.key(pick) {
            RestoreChoiceDialogLoading(pick.file, load = { pick.copyIn(); readRestoreDialog(ctx, pick.file) },
                onDismiss = { picked = null }, onError = onError)
        }
    }
    return {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
        restoreLauncher.launch(intent)
    }
}

/**
 * A full restore that failed after its wipe (owner 2026-10-12: "Yes add this"): set right before the wipe, kept through
 * it, cleared once a full restore and its follow-up finished ([runRestore]); while set, the main settings screen says so
 * ([UnfinishedRestoreNote]), across app restarts. App-wide (no keyboard's: KeyboardProfiles), never backed up or
 * restored ([notBackedUpKeys]).
 */
internal object FullRestore {
    const val PREF_UNFINISHED = "full_restore_unfinished"
    /** [afterStep]'s step at the end of runRestore's follow-up. */
    const val FOLLOW_UP = "(follow-up)"

    fun isUnfinished(real: SharedPreferences) = real.getBoolean(PREF_UNFINISHED, false)

    fun mark(real: SharedPreferences, unfinished: Boolean) =
        real.edit(commit = true) { if (unfinished) putBoolean(PREF_UNFINISHED, true) else remove(PREF_UNFINISHED) }

    /** Called right after the wipe (null) and after each entry restored (its name): a failure thrown here is one after
     *  the wipe (tests); with [FOLLOW_UP] at the end of runRestore's follow-up. */
    @Volatile internal var afterStep: ((String?) -> Unit)? = null

    /** Called after the full restore's check passed, before it opens the backup for its copies (tests). */
    @Volatile internal var afterCheck: (() -> Unit)? = null
}

/** The device's keys a backup never holds, and a restore never brings ([FullRestore]). */
internal val notBackedUpKeys = setOf(FullRestore.PREF_UNFINISHED)

/** "The full restore didn't finish." with Restore again, which opens the restore's file picker ([restoreEntry]): at
 *  the top of the main settings screen while [FullRestore.isUnfinished] (owner 2026-10-12: "Yes add this"). */
@Composable
internal fun UnfinishedRestoreNote() {
    var error: String? by rememberSaveable { mutableStateOf(null) }
    val restore = restoreEntry(onError = { error = it })
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.full_restore_unfinished), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
        androidx.compose.material3.TextButton(onClick = restore) { Text(stringResource(R.string.restore_again)) }
    }
    BackupRestoreJob.progress?.let { ProgressDialog(it) }
    error?.let { BackupRestoreErrorDialog(it) { error = null } }
}

/** The document picker's request for a new backup file (also the factory reset's Backup, 2026-10-11). */
internal fun backupIntent(ctx: Context): Intent {
    val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
    return Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .putExtra(Intent.EXTRA_TITLE, ctx.getString(R.string.english_ime_name).replace(" ", "_") + "_backup_$currentDate.zip")
        .setType("application/zip")
}

/** The picker for [backupIntent], then the backup into the file picked; [onDone]: see [runBackup]. A cancelled pick
 *  calls nothing. */
@Composable
internal fun backupLauncher(onError: (String) -> Unit, onDone: (Boolean) -> Unit = {}): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val resolver = ctx.getActivity()?.contentResolver ?: return@filePicker
        runBackup(ctx, onError, onDone) { resolver.openOutputStream(uri) }
    }
}

/**
 * Writes the backup to the stream [open] gives, on the keyboard executor with the progress dialog up (review
 * 2026-10-08 M2, 2026-10-10: the screen thread waited for it, and a touch meanwhile was an ANR). False when a backup or
 * restore is running already. [onError] is called on the main thread.
 */
internal fun runBackup(ctx: Context, onError: (String) -> Unit, onDone: (Boolean) -> Unit = {}, open: () -> OutputStream?): Boolean {
    if (!BackupRestoreJob.start(R.string.backup_running, 1)) return false
    ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
        var failure: String? = null
        var written = false
        try {
            written = writeBackup(ctx, open)
        } catch (t: Throwable) {
            failure = "b" + t.message
            Log.w("AdvancedScreen", "error during backup", t)
        } finally {
            BackupRestoreJob.post {
                BackupRestoreJob.finish()
                if (written && failure == null) BackupRestoreJob.backupWritten()
                failure?.let(onError)
                // (2026-10-11: true only for a backup written to the end; the factory reset's Backup greys out then)
                onDone(written && failure == null)
            }
        }
    }
    return true
}

/** @return whether the backup was written (false: no files folder, or the file couldn't be opened) */
private fun writeBackup(ctx: Context, open: () -> OutputStream?): Boolean {
    // zip all files matching the backup patterns
    // essentially this is the typed words information, and user-added dictionaries
    val filesDir = ctx.filesDir ?: return false
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
        // the rows still in the write-ahead log into the file first: the file alone is copied (review 2026-10-12 L2)
        try { Database.checkpoint(ctx) } catch (t: Throwable) { Log.w("AdvancedScreen", "error checkpointing the clipboard database", t) }
        if (dbFile.exists()) {
            val fileStream = FileInputStream(dbFile).buffered()
            zipStream.putNextEntry(ZipEntry(Database.NAME))
            fileStream.copyTo(zipStream, 1024)
            fileStream.close()
            zipStream.closeEntry()
        }
        BackupRestoreJob.step()
        zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
        // every keyboard's set, not just the edited one: what is stored (the settings you picked; none for one at its
        // default, no marks since 2026-10-11), so a setting left at its default follows the defaults of the version it's
        // restored on (0.3.008; from 2026-10-03 every default was written out, which froze them)
        // (not the device's own keys, notBackedUpKeys: a full restore that didn't finish, owner 2026-10-12)
        settingsToJsonStream(ctx.realPrefs().all.filterKeys { it !in notBackedUpKeys }.mapKeys { it.key as String? }, zipStream)
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
    } ?: return false
    return true
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
    /** What the restore just done has to say ([hiddenSettingsNote]): shown in a dialog with OK by [AfterRestoreNote],
     *  once (set on the main thread). */
    var afterNote: String? by mutableStateOf(null)
    /** [afterNote]'s dialog's title, if it has one ("Backup restored", owner 2026-10-12). */
    var afterTitle: String? by mutableStateOf(null)
    /** Called on the main thread with every progress shown (tests). */
    internal var onProgress: ((Progress) -> Unit)? = null
    /** The running restore skipped the backup's clipboard ([Database.copyFromDb]): its done message says so (review
     *  2026-10-12 M8; reset by [start]). */
    @Volatile var clipboardSkipped = false

    private val running = AtomicBoolean(false)
    private val done = AtomicInteger(0)
    @Volatile private var total = 1
    @Volatile private var title = 0
    private val updatePosted = AtomicBoolean(false)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    val isRunning get() = running.get()

    /** How many backups this process wrote to the end (observable; changed on the main thread): the factory reset's
     *  Backup greys out once one more is written while its dialog is open, also after the activity was made again
     *  meanwhile (review 2026-10-12 L2). */
    var backupsWritten by mutableStateOf(0)
        private set

    /** A backup was written to the end; called on the main thread. */
    fun backupWritten() { backupsWritten++ }

    /** A restore is running, also after its dialog closed (observable; set on the main thread): the screens that take a
     *  snapshot when they open wait for it to end ([helium314.keyboard.settings.WaitingForRestore], owner 2026-10-12 A5). */
    var restoring by mutableStateOf(false)
        private set

    /** Claims the job (false when one is running) and shows the dialog; called on the main thread. [restore]: it is a
     *  restore ([restoring]). */
    fun start(title: Int, total: Int, restore: Boolean = false): Boolean {
        if (!running.compareAndSet(false, true)) return false
        restoring = restore
        this.title = title
        this.total = total.coerceAtLeast(1)
        done.set(0)
        clipboardSkipped = false
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
        restoring = false
        running.set(false)
    }
}

/** The dialog with what the restore just done has to say ([BackupRestoreJob.afterNote]), OK closes it; on the settings'
 *  top level (SettingsActivity): the restore goes back to the main screen meanwhile. */
@Composable
internal fun AfterRestoreNote() {
    val note = BackupRestoreJob.afterNote ?: return
    val title = BackupRestoreJob.afterTitle
    val dismiss = { BackupRestoreJob.afterNote = null; BackupRestoreJob.afterTitle = null }
    if (title == null) InfoDialog(note, dismiss)
    else ThreeButtonAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        content = { Text(note) },
        scrollContent = true,
        cancelButtonText = stringResource(android.R.string.ok),
        onConfirmed = { },
        confirmButtonText = null,
    )
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
/** [pictureRenames]: an old backup's set numbers -> the ids its keyboards got when it was read (its pictures' file
 *  names, KeyboardIdUpgrade). */
internal class PendingRestore(val file: File, val prefs: Map<String, Any?>, val keyboards: List<SettingsSubtype>, val entries: Set<String>,
                              val pictureRenames: Map<Int, String> = emptyMap()) {
    private val plain = entries.map { it.substringAfter("unprotected${File.separator}") }
    val hasLearnedWords = plain.any { it.startsWith(UserHistoryDictionary.NAME) || it.startsWith("blacklists${File.separator}") }
    val hasDictionaries = plain.any { it.startsWith("dicts${File.separator}") && it.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }
    /** Android's personal dictionary words: brought by a keyboard by keyboard restore with their "Also restore" row
     *  ticked (owner 2026-10-13, R-DICT; 2026-10-12: always, no row). */
    val hasPersonalDictionary = PERSONAL_DICT_FILE_NAME in entries
    val hasClipboard = Database.NAME in entries
    /** Learned or blacklisted words, or personal dictionary words. */
    val hasWords get() = hasLearnedWords || hasPersonalDictionary
}

/** What the restore dialog was told to bring: [keyboards] with their [settings], their learned words, the
 *  keyboards' [dictionaries], Android's [personalDictionary] words and the [clipboard] (merged into the device's), and
 *  the [otherDictionaries]; the last three by their "Also restore" rows (owner 2026-10-13, R-DICT; 2026-10-12: always,
 *  no row). */
private class RestoreChoice(
    val keyboards: List<SettingsSubtype>, val settings: Boolean, val learnedWords: Boolean, val dictionaries: Boolean,
    val personalDictionary: Boolean, val clipboard: Boolean,
    // the backup's saved items: the rows unticked and the questions' answers (owner 2026-10-12: Replace or Keep both;
    // replaces 2026-10-11's "backup wins the name if ticked")
    val names: SavedItemsMerge.Choices = SavedItemsMerge.Choices(),
    // the chosen keyboards the dialog asked about, as answered: merged into a device keyboard, kept both or not
    // restored (owner 2026-10-11: restore matches by language and asks); the others come in as new
    val decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap(),
    // the "Also restore" row "Other dictionaries": the backup's dictionaries of languages no backup keyboard has (R-DICT)
    val otherDictionaries: Boolean = false,
)

/** What a keyboard by keyboard restore did: each restored backup keyboard as it is on the device now ([onDevice]),
 *  those whose settings wait behind the common ones ([hidden], R7), and by name what was merged into the device's
 *  ([merged]: keyboards, and saved items replaced) and what was added ([added]); the dictionaries ([dictionaries]);
 *  whether keyboards' settings came ([settings]), the clipboard history ([clipboard]) and Android's personal dictionary
 *  words ([personalWords]). */
internal class Restored(val onDevice: Map<SettingsSubtype, SettingsSubtype>, val hidden: List<SettingsSubtype>,
                        val merged: List<String>, val added: List<String>,
                        val dictionaries: RestoredDictionaries = RestoredDictionaries(),
                        val settings: Boolean = false, val clipboard: Boolean = false, val personalWords: Boolean = false)

/** The backup's dictionaries a restore brought: [steps], each carried out that changed something (a match already in
 *  use is left out: owner 2026-10-13, 20261013_L3), with the device's dictionary each kept aside ("main_prev"), kept and
 *  selectable. */
internal data class RestoredDictionaries(val steps: List<RestoreDictionaries.Step> = emptyList()) {
    /** The languages whose device dictionary was kept aside for the backup's. */
    val keptAside get() = steps.filter { it.kept != null }.mapTo(LinkedHashSet()) { it.tag }
    val any get() = steps.isNotEmpty()
}

/** The summary's dictionaries line (owner 2026-10-13, 20261013_L3: each language its own phrase): one that came in "Urdu
 *  added", over the device's "French added (yours kept as main_prev)"; a matching device copy selected "Urdu: backup's
 *  set as main", with the device's of that name moved aside "Urdu: backup's set as main (yours kept as main_prev)"; a
 *  match already in use nothing. Null: nothing to say. */
internal fun restoredDictionariesText(ctx: Context, steps: List<RestoreDictionaries.Step>): String? {
    val phrases = steps.filter { !it.unchanged }.groupBy { it.tag }.flatMap { (tag, ofTag) ->
        val name = languageName(ctx, tag)
        val added = ofTag.filter { it.action != RestoreDictionaries.Action.SAME }
        val kept = added.mapNotNull { it.kept?.let(RestoreDictionaries::shownName) }
        listOfNotNull(when {
            added.isEmpty() -> null
            kept.isEmpty() -> ctx.getString(R.string.restore_summary_dictionary_added, name)
            else -> ctx.getString(R.string.restore_summary_dictionary_kept, name, kept.joinToString(", "))
        }) + ofTag.filter { it.action == RestoreDictionaries.Action.SAME }.map { dictionarySetPhrase(ctx, name, it) }
    }
    if (phrases.isEmpty()) return null
    return ctx.getString(R.string.restore_summary_dictionaries, phrases.joinToString("; "))
}

/** "Urdu: backup's set as main", "Urdu: backup's set as main (yours kept as main_prev)": a matching device copy of
 *  language [name] selected (owner 2026-10-13, 20261013_L3). */
private fun dictionarySetPhrase(ctx: Context, name: String, step: RestoreDictionaries.Step): String =
    step.kept?.let { ctx.getString(R.string.restore_summary_dictionary_set_kept, name,
        RestoreDictionaries.shownName(step.target), RestoreDictionaries.shownName(it)) }
        ?: ctx.getString(R.string.restore_summary_dictionary_set, name, RestoreDictionaries.shownName(step.target))

/** The languages (tags) of [keyboards]: their main and other languages. */
internal fun backupKeyboardLanguages(keyboards: Collection<SettingsSubtype>): Set<String> =
    keyboards.flatMap { listOf(it.locale) + getSecondaryLocales(it.extraValues) }.mapTo(LinkedHashSet()) { it.toLanguageTag() }

/** The "Backup restored" dialog's text after a keyboard by keyboard restore, each line only when it applies (owner
 *  2026-10-13, R-DICT; review L3.1 before: "kept as …prev" whatever happened): "Merged: …", "Added: …", "Settings
 *  restored.", "Dictionaries: French added (yours kept as main_prev); Urdu added." ([restoredDictionariesText]), the
 *  clipboard and personal dictionary
 *  words, then the common settings' note when it applies ([hiddenSettingsNote]). */
internal fun restoreSummary(ctx: Context, restored: Restored): String = listOfNotNull(
    restored.merged.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.restore_summary_merged, it.joinToString(", ")) },
    restored.added.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.restore_summary_added, it.joinToString(", ")) },
    if (restored.settings) ctx.getString(R.string.restore_summary_settings) else null,
    restoredDictionariesText(ctx, restored.dictionaries.steps),
    when {
        restored.clipboard && restored.personalWords -> ctx.getString(R.string.restore_summary_clipboard_personal)
        restored.clipboard -> ctx.getString(R.string.restore_summary_clipboard)
        restored.personalWords -> ctx.getString(R.string.restore_summary_personal)
        else -> null
    },
    hiddenSettingsNote(ctx, restored.hidden),
).joinToString("\n")

/** A backup picked for restore, and the cache file it's copied to: its own per pick, so a read cancelled and still
 *  running never meets the next pick's (review 2026-10-11 R5). */
private class PickedBackup(private val resolver: android.content.ContentResolver?, private val uri: android.net.Uri, val file: File) {
    /** Copies the picked backup to [file]; off the main thread. */
    fun copyIn() {
        resolver?.openInputStream(uri)?.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
    }
}

@Composable
private fun restoreLauncher(onPick: (PickedBackup) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        if (BackupRestoreJob.isRunning) return@filePicker // (its file is the one being restored)
        // (copied and read by the restore dialog, off the main thread; R5)
        onPick(PickedBackup(ctx.getActivity()?.contentResolver, uri, File(ctx.cacheDir, "restore-${System.nanoTime()}.zip")))
    }
}

/** What the restore dialog shows of a backup: [pending], read, and the questions for its keyboards, [plan] (with the
 *  languages whose added dictionary clashes, [RestorePlan.dictionaryClashes]). */
internal class RestoreDialogData(val pending: PendingRestore, val plan: RestorePlan)

/** Reads the backup [file] for the restore dialog: the zip read whole (the dry runs too), so never on the main thread
 *  (review 2026-10-11 R5: done in composition, a large backup froze the screen). */
internal fun readRestoreDialog(ctx: Context, file: File): RestoreDialogData =
    readBackup(file).let { RestoreDialogData(it, restorePlan(ctx, it)) }

/** The restore dialog for the backup [file] (tests); [load] reads it (off the main thread). */
@Composable
internal fun RestoreChoiceDialogFrom(file: File, load: (Context, File) -> RestoreDialogData = ::readRestoreDialog, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    RestoreChoiceDialogLoading(file, load = { load(ctx, file) }, onDismiss, onError = { })
}

/**
 * "Reading backup…" while [load] reads the backup [file] off the main thread, then the restore dialog (owner 2026-10-11,
 * R5: "OK"). Cancel while reading closes it, nothing restored, [file] deleted once the read ends; a read that fails gives
 * the restore error, as before.
 */
@Composable
private fun RestoreChoiceDialogLoading(file: File, load: () -> RestoreDialogData, onDismiss: () -> Unit, onError: (String) -> Unit) {
    var data: RestoreDialogData? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        try {
            data = withContext(Dispatchers.IO) { load() }
        } catch (e: CancellationException) {
            file.delete() // (closed while reading: withContext returns once the read ended)
            throw e
        } catch (t: Throwable) {
            Log.w("AdvancedScreen", "error reading backup", t)
            file.delete()
            onDismiss()
            onError("r" + t.message)
        }
    }
    // (a backup without keyboards, older format, too: it can only be restored whole, the dialog's checkbox stays
    // checked, and the wipe is asked about like any other, never in one tap)
    data?.let { RestoreChoiceDialog(it.pending, it.plan, onDismiss, onError) } ?: ReadingDialog(onCancel = onDismiss)
}

/** "Reading backup…", Cancel only (R5). (No moving bar: an endless animation keeps Compose busy, and the UI tests with it.) */
@Composable
private fun ReadingDialog(onCancel: () -> Unit) = ThreeButtonAlertDialog(
    onDismissRequest = onCancel,
    onConfirmed = { },
    confirmButtonText = null,
    title = { Text(stringResource(R.string.restore_reading)) },
)

/**
 * The restore dialog of [pending] (owner 2026-10-12, the restore redesign): screen 1 has "Full restore (delete app data)"
 * on top as before (ticked: the "Delete and restore" question, then the full restore). Unticked: one row per backup
 * keyboard and saved item by section (Keyboards · Layouts & popups · Themes · Palettes & images · Fonts), names as in the
 * backup, every row ticked to start with ([RestoreFlow]); then one question per ticked row that needs merging, Back /
 * Next, the last one's button Restore. Settings, dictionaries and learned words come with each keyboard (a clash of its
 * dictionaries said under its row); a last section "Also restore" has a row each for the other dictionaries, the
 * clipboard history and Android's personal dictionary words, ticked to start (owner 2026-10-13, R-DICT; before, those
 * came with every restore, no row). Everything reads [plan], made once when the backup was read off the main thread
 * ([readRestoreDialog], R5).
 */
@Composable
private fun RestoreChoiceDialog(pending: PendingRestore, plan: RestorePlan, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val flow = remember(pending) { RestoreFlow(plan, pending.keyboards, pending.hasClipboard, pending.hasPersonalDictionary) }
    val top = flow.top
    // once the restore started, the job deletes the file when done with it (2026-10-10: it runs after the dialog closed)
    var started by remember { mutableStateOf(false) }
    fun close() { if (!started) pending.file.delete(); onDismiss() }
    fun start() {
        // (settings and the keyboards' dictionaries and learned words come with each keyboard; the other dictionaries, the
        // clipboard history and Android's personal dictionary words with their "Also restore" rows, owner 2026-10-13
        // R-DICT; were "always, no row", 2026-10-12)
        val choice = RestoreChoice(flow.restoring(), settings = true, learnedWords = pending.hasLearnedWords,
            dictionaries = pending.hasDictionaries, personalDictionary = flow.bringsPersonalWords, clipboard = flow.bringsClipboard,
            names = flow.choices(), decisions = flow.decisions(), otherDictionaries = flow.bringsOtherDictionaries)
        var restored: Restored? = null
        started = runRestore(ctx, onError, R.string.backup_restored, restoreSteps(pending), noteTitle = R.string.backup_restored,
            note = { restored?.let { restoreSummary(ctx, it) } }) {
            try { restored = restoreChosen(ctx, pending, plan, choice) } finally { pending.file.delete() }
        }
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
                started = runRestore(ctx, onError, R.string.backup_restored, restoreSteps(pending), fullRestore = true) {
                    // (checked whole against what this dialog showed before anything is deleted, owner 2026-10-12)
                    try { restoreEverything(ctx, pending.file, promised = pending) } finally { pending.file.delete() }
                }
                close()
            },
        )
        return
    }
    when (val question = flow.current) {
        is RestoreFlow.Question.Keyboard -> {
            // (each question its own state; Back and Next keep the answers in [flow])
            androidx.compose.runtime.key(question, flow.index) {
                KeyboardQuestion(flow, question.keyboard, wordsRow = pending.hasLearnedWords, onRestore = { start(); close() },
                    onError = { close(); onError(it) })
            }
            return
        }
        is RestoreFlow.Question.Item -> {
            androidx.compose.runtime.key(question, flow.index) {
                val asked = remember { flow.itemQuestion(question) }
                if (asked != null) ItemQuestionDialog(flow, asked, onRestore = { start(); close() })
            }
            return
        }
        null -> { }
    }
    ThreeButtonAlertDialog(
        // (its button closes the dialog after onConfirmed: not while the questions come, nor while the wipe is asked about)
        onDismissRequest = { if (flow.index < 0 && !everything) close() },
        title = { Text(stringResource(R.string.button_restore)) },
        content = {
            Column {
                // (a backup without a keyboard list: checked, it can only be restored whole)
                CheckRow(stringResource(R.string.restore_delete_app_data), top.wipe, top.canPickKeyboards) { top.check(it) }
                // checked: nothing below it, a full restore has nothing to choose (owner 2026-10-12, "with the box
                // ticked, hide every row below it. <- ok")
                if (!top.wipe) {
                    // (owner 2026-10-12: only with a ticked row that needs merging; replaces "Prepare to do super-complex
                    // merging! …")
                    if (flow.trickyAhead)
                        Text(stringResource(R.string.restore_tricky), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    // (the sections' headings in capitals, as the owner wrote the earlier ones)
                    @Composable fun Heading(text: Int) = Text(stringResource(text).uppercase(), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    val mergeRequired = stringResource(R.string.restore_merge_required)
                    if (pending.keyboards.isNotEmpty()) Heading(R.string.restore_keyboards)
                    // (names as in the backup: no "(new)" any more, owner 2026-10-12)
                    pending.keyboards.forEach { keyboard ->
                        val ticked = top.ticked(keyboard)
                        // (a clash of its dictionaries, while ticked: "Merge required · Dictionary differs: yours kept
                        // as main_prev", owner 2026-10-13 R-DICT; a matching device copy selected: "Backup's dictionary
                        // set as main, yours kept as main_prev", one already in use nothing, owner 2026-10-13, 20261013_L3)
                        val kept = if (ticked) dictionariesKeptText(ctx, plan.dictionariesKept(keyboard)) else null
                        val set = if (ticked) dictionariesSetText(ctx, plan.dictionariesSet(keyboard)) else null
                        val subtext = listOfNotNull(if (plan.asks(keyboard)) mergeRequired else null,
                            kept?.let { stringResource(R.string.restore_dictionary_differs, it) }, set).joinToString(" · ").ifEmpty { null }
                        CheckRow(keyboardName(keyboard, ctx), ticked, subtext = subtext) {
                            if (it) top.selected.add(keyboard) else top.selected.remove(keyboard)
                        }
                    }
                    for (section in flow.sections) {
                        val items = flow.items(section)
                        if (items.isEmpty()) continue
                        Heading(section.title)
                        for (item in items) {
                            val key = item.kind to item.key
                            // used by a ticked keyboard: ticked and greyed out, "Required for English (US)"; both
                            // subtexts can show together (owner 2026-10-12)
                            val required = flow.requiredFor(key)
                            val subtext = listOfNotNull(
                                if (required.isEmpty()) null else stringResource(R.string.restore_required_for, required.joinToString(", ")),
                                if (item.mergeRequired) mergeRequired else null,
                            ).joinToString(" · ").ifEmpty { null }
                            CheckRow(item.name, flow.ticked(key), required.isEmpty(), subtext) { flow.tick(key, it) }
                        }
                    }
                    // ALSO RESTORE, last: a row each the backup has, ticked to start (owner 2026-10-13, R-DICT)
                    val others = flow.otherDictionaries
                    if (others.isNotEmpty() || flow.hasClipboard || flow.hasPersonalWords) Heading(R.string.restore_section_also)
                    if (others.isNotEmpty())
                        CheckRow(stringResource(R.string.restore_other_dictionaries), flow.otherDictionariesTicked,
                            subtext = otherDictionariesText(ctx, others, plan.dictionarySteps)) { flow.otherDictionariesTicked = it }
                    if (flow.hasClipboard)
                        CheckRow(stringResource(R.string.restore_clipboard), flow.clipboardTicked,
                            subtext = stringResource(R.string.restore_clipboard_added,
                                ctx.prefs().getInt(Settings.PREF_CLIPBOARD_HISTORY_SIZE, Defaults.PREF_CLIPBOARD_HISTORY_SIZE))) {
                            flow.clipboardTicked = it }
                    if (flow.hasPersonalWords)
                        CheckRow(stringResource(R.string.restore_personal_words), flow.personalWordsTicked,
                            subtext = stringResource(R.string.restore_added_to_yours)) { flow.personalWordsTicked = it }
                }
            }
        },
        scrollContent = true,
        confirmFirst = true,
        // (greyed out with nothing ticked, owner 2026-10-12 A6)
        checkOk = { flow.anythingTicked },
        confirmButtonText = stringResource(when {
            top.wipe -> android.R.string.ok
            flow.questions.isEmpty() -> R.string.button_restore
            else -> R.string.restore_next
        }),
        onConfirmed = {
            // checked: the wipe is asked about first (never in one tap)
            if (top.ok() == RestoreDialogState.Ok.ConfirmWipe) { everything = true; return@ThreeButtonAlertDialog }
            // the questions, one by one; none: the restore runs
            if (flow.begin()) start()
        },
    )
}

private fun languageName(ctx: Context, tag: String) = java.util.Locale.forLanguageTag(tag).localizedDisplayName(ctx.resources)

/** A keyboard's dictionaries kept aside ([kept]: language tag, name), compactly: "main_prev" or "main_prev, emoji_prev";
 *  of several languages, each named: "main_prev (French), main_prev (Italian)". Null: none. */
internal fun dictionariesKeptText(ctx: Context, kept: List<Pair<String, String>>): String? {
    if (kept.isEmpty()) return null
    if (kept.map { it.first }.distinct().size == 1) return kept.joinToString(", ") { it.second }
    return kept.joinToString(", ") { (tag, name) -> "$name (${languageName(ctx, tag)})" }
}

/** A keyboard's matching device copies selected ([steps]), each "Backup's dictionary set as main" or "Backup's
 *  dictionary set as main, yours kept as main_prev"; of several languages, each named: "… (French)" (owner 2026-10-13,
 *  20261013_L3). Null: none. */
internal fun dictionariesSetText(ctx: Context, steps: List<RestoreDictionaries.Step>): String? {
    if (steps.isEmpty()) return null
    val named = steps.map { it.tag }.distinct().size > 1
    return steps.joinToString(" · ") { step ->
        val text = step.kept?.let { ctx.getString(R.string.restore_dictionary_set_kept, RestoreDictionaries.shownName(step.target),
            RestoreDictionaries.shownName(it)) } ?: ctx.getString(R.string.restore_dictionary_set, RestoreDictionaries.shownName(step.target))
        if (named) "$text (${languageName(ctx, step.tag)})" else text
    }
}

/** The row "Other dictionaries"' subtext: each language, with what it keeps aside: "Korean: yours kept as main_prev";
 *  with [steps] (the plan's), a language whose matching device copy is selected as the summary says it, "Korean:
 *  backup's set as main (yours kept as main_prev)", and one with only a match already in use not at all (owner
 *  2026-10-13, 20261013_L3). */
internal fun otherDictionariesText(ctx: Context, others: Map<String, List<String>>, steps: List<RestoreDictionaries.Step> = emptyList()): String =
    others.entries.flatMap { (tag, kept) ->
        val ofTag = steps.filter { it.tag == tag }
        val set = ofTag.filter { it.action == RestoreDictionaries.Action.SAME && !it.unchanged }
        val onlyMatches = ofTag.isNotEmpty() && ofTag.all { it.action == RestoreDictionaries.Action.SAME }
        listOfNotNull(when {
            onlyMatches -> null
            kept.isEmpty() -> languageName(ctx, tag)
            else -> ctx.getString(R.string.restore_other_dictionary_kept, languageName(ctx, tag), kept.joinToString(", "))
        }) + set.map { dictionarySetPhrase(ctx, languageName(ctx, tag), it) }
    }.joinToString(", ")

/** A row with a checkbox, and [subtext] under its text if any ([warning]: in the app's warning color). */
@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, subtext: String? = null, warning: Boolean = false,
                     onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp)) {
        // always onChange; not enabled ignores taps (review 2026-10-12 T2: with no onCheckedChange a greyed row lost its
        // checked state for TalkBack)
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        if (subtext == null) Text(text)
        else Column {
            Text(text)
            Text(subtext, style = MaterialTheme.typography.bodySmall,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

/** A row with a radio button, and [subtext] under its text if any; not [enabled]: greyed out, not picked. */
@Composable
private fun RadioRow(text: String, selected: Boolean, subtext: String? = null, enabled: Boolean = true, onClick: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp)) {
        RadioButton(selected = selected, onClick = if (enabled) onClick else null, enabled = enabled)
        // (greyed out as Material greys a disabled control's text)
        val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        if (subtext == null) Text(text, color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else grey)
        else Column {
            Text(text, color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified else grey)
            Text(subtext, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else grey)
        }
    }

/** The name box of a merged or separate keyboard, and the line saying it would be the same as another keyboard. */
// (owner 2026-10-11, keyboards have unique ids: no "This would be the same as your other keyboard" line, nothing blocked)
@Composable
private fun NameBox(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = name, onValueChange = onChange, singleLine = true, label = { Text(stringResource(R.string.restore_name)) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

/** A device keyboard as the questions name it: "<name> (switched off)" for one switched off on the Languages screen. */
private fun phoneKeyboardLabel(ctx: Context, plan: RestorePlan, keyboard: SettingsSubtype) =
    if (plan.isSwitchedOff(keyboard)) ctx.getString(R.string.keyboard_switched_off, keyboardName(keyboard, ctx)) else keyboardName(keyboard, ctx)

/** A question's title, with "n of N" small above it (owner 2026-10-12). */
@Composable
private fun QuestionTitle(flow: RestoreFlow, title: String) = Column {
    Text(stringResource(R.string.restore_question_count, flow.index + 1, flow.questions.size), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(title)
}

/**
 * A backup keyboard's question (owner 2026-10-12: today's merge question): merge into a device keyboard of its language
 * or Keep both ([RestoreMergeDialog]); merging goes on to "What to merge into …" ([RestoreChooseDialog]), worked out off
 * the main thread (R5: it reads the backup's fonts and pictures), "Reading backup…" meanwhile. Back keeps the answers
 * and the ticks ([RestoreFlow.keep]).
 */
@Composable
private fun KeyboardQuestion(flow: RestoreFlow, keyboard: SettingsSubtype, wordsRow: Boolean, onRestore: () -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val question = remember { flow.mergeQuestion(keyboard) }
    val target = question.picked?.phone
    if (!flow.choosing || target == null) {
        RestoreMergeDialog(flow, question,
            onBack = { flow.keep(question); flow.back() },
            onNext = {
                flow.keep(question)
                if (question.picked?.phone != null) flow.choosing = true
                else if (flow.next()) onRestore()
            })
        return
    }
    androidx.compose.runtime.key(target) {
        val names = remember { flow.choices() }
        val found by produceState<List<RestoreMatch.Difference>?>(null) {
            value = try {
                withContext(Dispatchers.IO) { restoreDifferences(ctx, flow.plan, keyboard, target, true, names) }
            } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                Log.w("AdvancedScreen", "error reading backup", t)
                onError("r" + t.message); null
            }
        }
        found?.let { differences ->
            val choice = remember(differences) { flow.mergeChoice(question, target, differences, wordsRow) }
            RestoreChooseDialog(flow, question, choice,
                onBack = { flow.keep(choice, decide = false); flow.back() },
                onNext = { flow.keep(choice, decide = true); if (flow.next()) onRestore() })
        } ?: ReadingDialog(onCancel = { flow.back() })
    }
}

/**
 * The first question for a backup keyboard whose language the device has: merge it into one of those keyboards (one
 * only), or Keep both, named in a box (owner 2026-10-11). The device's with the backup keyboard's id is picked to start
 * with, "Suggested: same keyboard as in the backup"; none: Keep both (owner 2026-10-12, so Next is always enabled).
 */
@Composable
private fun RestoreMergeDialog(flow: RestoreFlow, question: MergeQuestion, onBack: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    // (its buttons close the dialog after their action: the answer moved on already)
    var moved by remember { mutableStateOf(false) }
    fun back() { if (!moved) { moved = true; onBack() } }
    val separate = question.picked.let { it != null && it.phone == null }
    ThreeButtonAlertDialog(
        keepKeyboard = false, // (a text field)
        onDismissRequest = { back() },
        title = { QuestionTitle(flow, stringResource(R.string.restore_merge_title, keyboardName(question.keyboard, ctx))) },
        content = {
            Column {
                Text(stringResource(R.string.restore_merge_message), modifier = Modifier.padding(bottom = 8.dp))
                question.options.forEach { phone ->
                    RadioRow(phoneKeyboardLabel(ctx, question.plan, phone), question.picked?.phone == phone,
                        subtext = if (question.isSameKeyboard(phone)) stringResource(R.string.restore_same_keyboard_suggested) else null
                    ) { question.picked = MergeQuestion.Pick(phone) }
                }
                RadioRow(stringResource(R.string.restore_keep_both), separate) { question.picked = MergeQuestion.Pick(null) }
                if (separate) NameBox(question.separateName) { question.separateName = it }
            }
        },
        scrollContent = true,
        cancelButtonText = stringResource(R.string.restore_back),
        onCancel = { back() },
        checkOk = { question.canGoOn },
        confirmButtonText = stringResource(if (flow.isLast && separate) R.string.button_restore else R.string.restore_next),
        onConfirmed = { moved = true; onNext() },
    )
}

/**
 * What to merge of the backup's keyboard into the device's [MergeChoice.target]: what differs, each unticked (a ticked
 * one takes the backup's, an unticked one keeps the device's), its learned and blacklisted words row, ticked, and the
 * name box ([MergeChoice]). A key layout of the backup the device has under its name with other keys says what the
 * device's becomes ("Backup's My numrow; yours becomes My numrow 2", owner 2026-10-12); an unsaved one, ticked, the name its
 * keys are saved as ([RestoreMatch.unsavedWarning]). Nothing ticked: the keyboard isn't
 * restored. Back / Next, the last question's Restore.
 */
@Composable
private fun RestoreChooseDialog(flow: RestoreFlow, question: MergeQuestion, choice: MergeChoice, onBack: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var moved by remember { mutableStateOf(false) }
    fun back() { if (!moved) { moved = true; onBack() } }
    val decision = choice.decision
    val target = choice.target
    val differences = choice.differences
    ThreeButtonAlertDialog(
        keepKeyboard = false, // (a text field)
        onDismissRequest = { back() },
        title = { QuestionTitle(flow, stringResource(R.string.restore_differs_choose, phoneKeyboardLabel(ctx, question.plan, target))) },
        content = {
            Column {
                if (choice.nothingDiffers) {
                    Text(stringResource(R.string.restore_nothing_differs, keyboardName(target, ctx)))
                    NameBox(choice.name) { choice.typed = it }
                    return@Column
                }
                Text(stringResource(R.string.restore_differs_choose_message), modifier = Modifier.padding(bottom = 8.dp))
                choice.replacesNote(ctx)?.let { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }
                // which ticked menus are common to all keyboards, and whose they are (R-4, owner 2026-10-11)
                choice.commonNote(ctx)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
                differences.forEach { difference ->
                    val keyLayouts = question.plan.items.keyLayouts
                    val ticked = difference in choice.ticked
                    // an unsaved key layout the row leaves unused: the name its keys are saved as, only while ticked
                    // (owner 2026-10-12: "Unsaved number row on the device will be saved as "temp_MMDD" to prevent loss
                    // of unsaved data"; any unsaved one, A1b); the run saves them under that same name
                    // (a menu row: those of the device keyboard's defaults it replaces, owner 2026-10-13, 20261013_M1)
                    val warning = if (!ticked) null else RestoreMatch.unsavedWarning(ctx, difference, question.plan.unsavedSaves(choice.target))
                        ?: (difference as? RestoreMatch.Difference.Menu)?.let {
                            RestoreMatch.unsavedDefaultsWarning(ctx, it, question.plan.unsavedDefaultSaves(choice.target, question.keyboard)) }
                    CheckRow(RestoreMatch.rowText(ctx, difference, question.keyboard, keyLayouts::deviceRename),
                        ticked, subtext = warning, warning = true) {
                        if (it) choice.ticked.add(difference) else choice.ticked.remove(difference)
                    }
                }
                if (choice.wordsRow) CheckRow(stringResource(R.string.restore_learned_row), choice.wordsTicked) { choice.wordsTicked = it }
                if (decision is RestoreDecision.Skip)
                    Text(stringResource(R.string.restore_nothing_ticked, keyboardName(question.keyboard, ctx)), modifier = Modifier.padding(top = 8.dp))
                else NameBox(choice.name) { choice.typed = it }
            }
        },
        scrollContent = true,
        cancelButtonText = stringResource(R.string.restore_back),
        onCancel = { back() },
        confirmButtonText = stringResource(if (flow.isLast) R.string.button_restore else R.string.restore_next),
        onConfirmed = { moved = true; onNext() },
    )
}

/** The noun a saved item's question names its kind with. */
private fun itemNoun(kind: SavedItemsMerge.Kind) = when (kind) {
    SavedItemsMerge.Kind.PALETTES -> R.string.restore_noun_palette
    SavedItemsMerge.Kind.THEMES -> R.string.restore_noun_theme
    SavedItemsMerge.Kind.LAYOUTS -> R.string.restore_noun_layout
    SavedItemsMerge.Kind.POPUP_SETS -> R.string.restore_noun_popup_set
    SavedItemsMerge.Kind.FONTS -> R.string.restore_noun_font
    SavedItemsMerge.Kind.PICTURES -> R.string.restore_noun_image
}

/** A candidate's line in a saved item's question (owner 2026-10-12: "Same name, other colors. Used by your English
 *  (US), French: they take the backup's look"; "Suggested: same colors, other name: renamed Sunset"). [suggested]: the
 *  candidate picked to start with ([ItemQuestion.suggested]). */
internal fun restoreCandidateText(ctx: Context, plan: RestorePlan, item: SavedItemsMerge.ItemPlan<*>, name: String, suggested: Boolean): String {
    val kind = item.kind
    val colors = kind == SavedItemsMerge.Kind.THEMES || kind == SavedItemsMerge.Kind.PALETTES
    val files = kind == SavedItemsMerge.Kind.FONTS || kind == SavedItemsMerge.Kind.PICTURES
    val sameName = name == item.holder
    val what = if (sameName) ctx.getString(when {
        colors -> R.string.restore_item_same_name_colors
        files -> R.string.restore_item_same_name_file
        else -> R.string.restore_item_same_name_settings
    }) else ctx.getString(when {
        colors -> R.string.restore_item_same_content_colors
        files -> R.string.restore_item_same_content_file
        else -> R.string.restore_item_same_content_settings
    }, item.name)
    val head = if (suggested) ctx.getString(R.string.restore_item_suggested, what)
        else what.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    // (the same name: its users take the backup's; one renamed keeps its content, its users their look)
    val users = if (!sameName) emptyList() else plan.deviceUsers[kind to name].orEmpty().map(plan.nameOf)
    if (users.isEmpty()) return head
    val look = kind != SavedItemsMerge.Kind.LAYOUTS && kind != SavedItemsMerge.Kind.POPUP_SETS
    return ctx.getString(R.string.restore_item_used_by, head,
        ctx.getString(if (look) R.string.restore_item_used_by_look else R.string.restore_item_used_by_settings, users.joinToString(", ")))
}

/**
 * A saved item's question (owner 2026-10-12): "Restoring theme Sunset", "This device has a theme like it. Replace it
 * with the backup's, or keep both?", its candidates on the device (the same name first) and Keep both (the backup's
 * keeps its name; the device's becomes "Sunset 2", the keyboards using it keeping their look). Picked to start with:
 * one with the same content, else Keep both (owner 2026-10-12, option D). A candidate an earlier question merges into
 * is greyed out, "Already taken by Dusk", and Keep both renames nothing then (review 2026-10-12 A2). The item acts as a
 * whole: no per-part choice. Back / Next, the last question's Restore.
 */
@Composable
private fun ItemQuestionDialog(flow: RestoreFlow, question: ItemQuestion, onRestore: () -> Unit) {
    val ctx = LocalContext.current
    val item = question.item
    var moved by remember { mutableStateOf(false) }
    fun back() { if (!moved) { moved = true; flow.keep(question); flow.back() } }
    val noun = stringResource(itemNoun(item.kind))
    ThreeButtonAlertDialog(
        onDismissRequest = { back() },
        title = { QuestionTitle(flow, stringResource(R.string.restore_item_title, noun, item.name)) },
        content = {
            Column {
                Text(stringResource(R.string.restore_item_message, noun), modifier = Modifier.padding(bottom = 8.dp))
                item.candidates.forEach { name ->
                    val answer = SavedItemsMerge.Answer.Replace(name)
                    val takenBy = question.taken[name]
                    RadioRow(name, question.picked == answer, enabled = takenBy == null, subtext = takenBy?.let { stringResource(R.string.restore_item_taken, it) }
                        ?: restoreCandidateText(ctx, flow.plan, item, name, suggested = question.suggested == answer)) {
                        question.picked = answer
                    }
                }
                val keepBoth = item.keepBothName?.takeIf { question.keepBothRenames }?.let { stringResource(R.string.restore_item_keep_both_renamed, item.name, it) }
                    ?: stringResource(R.string.restore_item_keep_both_added, item.name)
                RadioRow(stringResource(R.string.restore_keep_both), question.picked == SavedItemsMerge.Answer.KeepBoth, subtext = keepBoth) {
                    question.picked = SavedItemsMerge.Answer.KeepBoth
                }
            }
        },
        scrollContent = true,
        cancelButtonText = stringResource(R.string.restore_back),
        onCancel = { back() },
        confirmButtonText = stringResource(if (flow.isLast) R.string.button_restore else R.string.restore_next),
        onConfirmed = { moved = true; flow.keep(question); if (flow.next()) onRestore() },
    )
}

/** What the restore says when done, in a dialog with OK (owner 2026-10-12: "ahh. Let's do dialog"), if [keyboards] got
 *  their settings while one set is for all (owner 2026-10-11, R7: "should inform so it doesn't look like nothing
 *  happened"), else null: the others' wait in their own sets, behind the first keyboard's, which all keyboards follow
 *  for now; the first keyboard's are the common settings, which all keyboards use now (no separate common set). */
internal fun hiddenSettingsNote(ctx: Context, keyboards: List<SettingsSubtype>): String? {
    if (keyboards.isEmpty()) return null
    val first = KeyboardProfiles.mainKeyboard(ctx.realPrefs())
    val main = keyboards.firstOrNull { it.sameKeyboard(first) }
    val names = keyboards.filter { !it.sameKeyboard(main) }.map { keyboardName(it, ctx) }
    val firstName = keyboardName(first, ctx)
    val others = if (names.isEmpty()) null else if (names.size == 1) ctx.getString(R.string.restore_settings_hidden_one, names.single(), firstName)
        else ctx.getString(R.string.restore_settings_hidden_many, names.joinToString(", "), firstName)
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
                        fullRestore: Boolean = false, noteTitle: Int? = null, work: () -> Unit): Boolean {
    if (!BackupRestoreJob.start(R.string.restore_running, steps + 1, restore = true)) return false
    val app = ctx.applicationContext
    ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
        var failure: String? = null
        // the full restore's check found the backup unreadable before anything was deleted (owner 2026-10-12: "Yes,
        // check backup before wipe"): nothing to follow up or refresh
        var unchanged = false
        try {
            work()
        } catch (t: UnreadableBackupException) {
            failure = "u"
            unchanged = true
            Log.w("AdvancedScreen", "backup unreadable, nothing restored", t)
        } catch (t: RestoreStoppedPartway) {
            // (what it changed stays: "Restore stopped partway; …", owner 2026-10-12 A3: no rollback)
            failure = "p"
            Log.w("AdvancedScreen", "restore stopped partway", t)
        } catch (t: Throwable) {
            failure = "r" + t.message
            Log.w("AdvancedScreen", "error during restore", t)
        }
        // (after a failure too, as before: what was restored by then is followed up)
        var followedUp = false
        if (!unchanged) try {
            helium314.keyboard.latin.gesture.GestureStats.reload() // (its cached rows would come back over the restored ones)
            restoreFollowUp(ctx)
            transferOldPinnedClips(ctx)
            FullRestore.afterStep?.invoke(FullRestore.FOLLOW_UP)
            followedUp = true
        } catch (t: Throwable) {
            Log.w("AdvancedScreen", "error after restore", t)
        }
        // [fullRestore] finished, its follow-up too: the main screen's note goes (owner 2026-10-12: "Yes add this"; and
        // "yes": only after the follow-up, which can fail too)
        if (fullRestore && failure == null && followedUp) try {
            FullRestore.mark(ctx.realPrefs(), false)
        } catch (t: Throwable) {
            Log.w("AdvancedScreen", "error clearing the full restore's flag", t)
        }
        BackupRestoreJob.step() // (the settings)
        BackupRestoreJob.post {
            try {
                if (!unchanged) restoreRefresh(ctx)
            } catch (t: Throwable) {
                Log.w("AdvancedScreen", "error refreshing after restore", t)
            } finally {
                BackupRestoreJob.finish()
            }
            if (failure != null) onError(failure)
            // (what the restore has to say, once, in a dialog with OK, owner 2026-10-12: "ahh. Let's do dialog"; else
            // the done message)
            else {
                // (the backup's clipboard skipped, the rest restored: said too, owner 2026-10-12 "makes senes to do B")
                val skipped = if (BackupRestoreJob.clipboardSkipped) app.getString(R.string.restore_clipboard_skipped) else null
                val said = note()
                // ([noteTitle]: the keyboard by keyboard restore's summary, "Backup restored", owner 2026-10-12)
                if (said != null) {
                    BackupRestoreJob.afterTitle = noteTitle?.let { app.getString(it) }
                    BackupRestoreJob.afterNote = listOfNotNull(said, skipped).joinToString("\n\n")
                }
                else Toast.makeText(app, listOfNotNull(app.getString(doneMessage), skipped).joinToString("\n"), Toast.LENGTH_LONG).show()
            }
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
    // (through the app, to this app: with no screen open, e.g. a restore that ended after its screen closed, nothing
    // reloaded the keyboard's dictionaries before; the keyboard's receiver is the app's own)
    val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION).setPackage(ctx.packageName)
    (ctx.getActivity() ?: ctx.applicationContext).sendBroadcast(newDictBroadcast)
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
private fun restoreEverything(ctx: Context, file: File, promised: PendingRestore? = null) = LearnedKeyboards.withFiles {
    // [promised]: what the restore dialog showed of [file] (null: no dialog, tests), checked with the rest before the wipe
    checkFullBackup(file, promised) // (throws, nothing deleted)
    FullRestore.afterCheck?.invoke()
    val opened = openBackup(file) // (throws, nothing deleted: before the gate, which closes the open stores unsaved)
    try {
        underRestoreGate(ctx) {
            replaceEverythingChecked(ctx, opened)
            // (a backup from before the keyboards had ids: ids first, so the learned words' update finds each keyboard by its own)
            helium314.keyboard.latin.settings.KeyboardIdUpgrade.upgrade(ctx)
            LearnedKeyboards.updateOnce(ctx)
        }
    } finally { opened.zip.close() }
    // (the flag of a full restore that didn't finish stays: runRestore clears it once its follow-up finished too)
}

/**
 * [work] (the full restore's file copies, on this thread) with [RestoreGate] set, so the running keyboard can't save
 * what it still holds of the old device over the restored files (owner 2026-10-12: "A + B + swipe stats makes sense"):
 * the open learned-word stores are closed first, without saving (the restore replaces their files), and stay closed
 * until it's done; the blacklists keep their changes in memory meanwhile, put on top of the restored files at the end
 * and written then (a word removed during the restore stays removed); the swipe statistics aren't written meanwhile,
 * and what they held is dropped at the end. The gate goes after [work], when it failed too, so it never sticks. Only
 * the full restore: a keyboard restore never wipes, and it merges into the live stores and lists under their own locks.
 */
private fun underRestoreGate(ctx: Context, work: () -> Unit) {
    // the device's keyboards' pools before the wipe: a blacklist change made meanwhile goes to its keyboard's list after
    // the restore, found by the keyboard's id (review 2026-10-12 L2: by the pool number, the file's name, it went to
    // whichever keyboard has that number in the backup)
    val poolsBefore = LearnedKeyboards.poolEntries(ctx.realPrefs())
    RestoreGate.begin()
    try {
        PersonalizationHelper.reloadAllFromFiles()
        RemovedWords.awaitWrites() // (one started before the gate lands before the wipe)
        work()
    } finally {
        val filesDir = ctx.filesDir
        // the blacklists the keyboard holds read the restored files; the changes made on them meanwhile are taken off
        // them first, to be made again by keyboard below (still gated: a strike meanwhile stays pending too)
        val gated = ArrayList<Pair<RemovedWords.WordListFile, List<RemovedWords.GatedOp>>>()
        if (filesDir != null) for (list in RemovedWords.cachedIn(filesDir))
            try {
                list.takeGated().takeIf { it.isNotEmpty() }?.let { gated.add(list to it) }
                list.reload()
            } catch (t: Throwable) { Log.w("AdvancedScreen", "error reading a blacklist after restore", t) }
        // the swipe statistics read the restored settings at their next use (what they held is the old device's, never
        // written while gated)
        helium314.keyboard.latin.gesture.GestureStats.reload()
        RestoreGate.end()
        if (filesDir != null && gated.isNotEmpty())
            try { redoBlacklistChanges(ctx, filesDir, poolsBefore, gated) }
            catch (t: Throwable) { Log.w("AdvancedScreen", "error making the blacklist changes again after restore", t) }
        if (filesDir != null) for (list in RemovedWords.cachedIn(filesDir))
            try { list.writePending() } catch (t: Throwable) { Log.w("AdvancedScreen", "error writing a blacklist after restore", t) }
    }
}

/**
 * The blacklist changes made during a full restore ([gated]: each list's, as made) made again on the restored lists
 * (review 2026-10-12 L2): a keyboard's own list's on the list of the keyboard with that id after the restore (its pool
 * there, [poolsBefore]: the pools before the wipe); none if the restore brought no keyboard with that id. The lists of
 * no keyboard (the shared pool, a per-language list of before) on the same file. As made: a strike is one more on the
 * restored count, never the count it made on the old list.
 */
private fun redoBlacklistChanges(ctx: Context, filesDir: File, poolsBefore: Map<String, Int>,
                                 gated: List<Pair<RemovedWords.WordListFile, List<RemovedWords.GatedOp>>>) {
    val keyboardOfPool = poolsBefore.entries.associate { (id, pool) -> pool to id }
    val poolsNow = LearnedKeyboards.poolEntries(ctx.realPrefs())
    for ((list, ops) in gated) {
        val parsed = LearnedStores.blacklistPoolAndScript(filesDir, list.file)
        if (parsed == null) { list.redo(ops); continue }
        val (pool, script) = parsed
        val target = if (pool == LearnedStores.SHARED) pool else keyboardOfPool[pool]?.let { poolsNow[it] }
        if (target == null) {
            Log.i("AdvancedScreen", "blacklist changes during the restore dropped: their keyboard isn't in the backup")
            continue
        }
        RemovedWords.forFile(LearnedStores.blacklistFile(filesDir, script, target)).redo(ops)
    }
    RemovedWords.awaitWrites()
}

/**
 * Reads the whole backup [file] once, writing nothing, before the full restore deletes anything (owner 2026-10-12: "Yes,
 * check backup before wipe"; there is no safety copy of the device's data): the zip opens and lists its entries (the
 * list is at the zip's end, so a file cut short has none), every entry reads to its end through the restore's own reader
 * (which checks each entry's CRC) and they are the ones listed, the settings lines parse, and it has a settings file
 * with at least one setting and what the restore dialog showed ([promised]: its entries and its keyboards). Throws [UnreadableBackupException].
 * (The protected settings and the personal dictionary words aren't parsed: the restore never fails over those.)
 */
private fun checkFullBackup(file: File, promised: PendingRestore?) {
    try {
        val listed = java.util.zip.ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        val read = mutableSetOf<String>()
        var prefs: Map<String, Any?>? = null
        ZipInputStream(FileInputStream(file)).use { zip ->
            val sink = ByteArray(8192)
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                read.add(entry.name)
                if (entry.name == PREFS_FILE_NAME) prefs = readJsonLinesToMap(String(zip.readBytes()).split("\n"), strict = true)
                else while (zip.read(sink) >= 0) { }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (read != listed) throw UnreadableBackupException("entries not readable: ${listed - read}")
        val settings = prefs ?: throw UnreadableBackupException("no settings file")
        // (a settings file that parses but holds no setting at all can't be the device's: owner 2026-10-12, "yes")
        if (settings.isEmpty()) throw UnreadableBackupException("no settings in the settings file")
        if (promised != null) {
            if (!read.containsAll(promised.entries)) throw UnreadableBackupException("entries missing: ${promised.entries - read}")
            // (by language: a backup from before the keyboards had ids gets fresh ids on each read)
            val keyboards = pendingOf(file, settings, read).keyboards
            if (keyboards.map { it.locale } != promised.keyboards.map { it.locale }) throw UnreadableBackupException("keyboards differ")
        }
    } catch (e: UnreadableBackupException) {
        throw e
    } catch (t: Throwable) {
        throw UnreadableBackupException(t.toString(), t)
    }
}

/** The full restore found its backup unreadable before deleting anything ([checkFullBackup]): nothing was changed. */
internal class UnreadableBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A keyboard by keyboard restore that failed ([cause]) after it had changed something on the device: what it changed
 *  stays, and [runRestore] says so (owner 2026-10-12, A3: no rollback; "Restore stopped partway; …"). */
internal class RestoreStoppedPartway(cause: Throwable) : Exception(cause.message, cause)

/** The backup opened for the full restore's copies, its first entry read ([openBackup]). */
private class OpenedBackup(val zip: ZipInputStream, val first: ZipEntry)

/** [file] opened and its first entry read, before the full restore deletes anything (review 2026-10-12 L2: a backup
 *  that can't be read here, after its check, changes nothing, as one its check finds unreadable; before, a file that
 *  had gone was a plain failure, whose follow-up then ran, and one that read as no entries was wiped over with
 *  nothing). Throws [UnreadableBackupException]. */
private fun openBackup(file: File): OpenedBackup {
    val zip = try { ZipInputStream(FileInputStream(file)) } catch (t: Throwable) { throw UnreadableBackupException(t.toString(), t) }
    val first = try { zip.nextEntry } catch (t: Throwable) { zip.close(); throw UnreadableBackupException(t.toString(), t) }
    if (first == null) { zip.close(); throw UnreadableBackupException("no entries") }
    return OpenedBackup(zip, first)
}

private fun replaceEverythingChecked(ctx: Context, opened: OpenedBackup) = keepingPhoneChoices(ctx) {
    val restoredDb = ctx.getDatabasePath(Database.NAME + "_restored")
    // one a failed restore left behind is never this backup's clipboard (owner 2026-10-13, 20261013_L2: a backup without
    // one took another backup's clips over the device's)
    restoredDb.delete()
    opened.zip.use { zip ->
        var entry: ZipEntry? = opened.first
        val filesDir = ctx.filesDir ?: return@keepingPhoneChoices
        val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
        // until the full restore finished, the main screen says it didn't (owner 2026-10-12: "Yes add this"): set right
        // before the wipe, put back after the settings are cleared below
        FullRestore.mark(ctx.realPrefs(), true)
        filesDir.deleteRecursively()
        deviceProtectedFilesDir.deleteRecursively()
        LayoutUtilsCustom.onLayoutFileChanged()
        Settings.getInstance().stopListener()
        FullRestore.afterStep?.invoke(null)
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
                // (the flag in the same edit as the clear (review 2026-10-12 L2): never a moment, nor a crash, with the
                // settings wiped and the flag gone)
                prefs.edit(commit = true) { clear(); putBoolean(FullRestore.PREF_UNFINISHED, true) }
                readJsonLinesToSettings(prefLines, prefs)
                FullRestore.mark(prefs, true) // (a backup never has it, and never brings it: one made by hand neither)
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
            FullRestore.afterStep?.invoke(entry.name)
            entry = zip.nextEntry
        }
    }
    // (never fails the restore: a clipboard it can't read is skipped, the device's kept, review 2026-10-12 M8)
    if (!Database.copyFromDb(restoredDb, ctx)) BackupRestoreJob.clipboardSkipped = true
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

/** Restores from the backup [file] as the restore dialog does, the learned words through [io] (tests). [personal]: the
 *  personal dictionary words; [names]: the saved items' rows and answers. */
internal fun restoreChosenFrom(ctx: Context, file: File, keyboards: List<SettingsSubtype>, settings: Boolean, learnedWords: Boolean,
                               io: LearnedStoreIo, decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap(),
                               dictionaries: Boolean = false, clipboard: Boolean = false, personal: Boolean = false,
                               names: SavedItemsMerge.Choices = SavedItemsMerge.Choices(), otherDictionaries: Boolean = false): Restored {
    val pending = readBackup(file)
    return restoreChosen(ctx, pending, restorePlan(ctx, pending),
        RestoreChoice(keyboards, settings, learnedWords, dictionaries, personal, clipboard, names, decisions, otherDictionaries), io)
}

/**
 * Brings what [choice] asks for out of the backup and leaves the rest of the device as it is, as [plan] (made when the
 * backup was read, owner 2026-10-12: one plan) has it: the keyboards keyboard by keyboard, all of them too
 * ([restoreKeyboards]; only the full restore wipes, [restoreEverything]). Learned words and added dictionaries follow the
 * restored keyboards' languages ([RestoreDictionaries]); the dictionaries of languages no backup keyboard has, the
 * clipboard history and Android's personal dictionary words come with their "Also restore" rows (owner 2026-10-13,
 * R-DICT; replaces 2026-10-12's "always, no row"); the clipboard and those words are merged.
 */
private fun restoreChosen(ctx: Context, pending: PendingRestore, plan: RestorePlan, choice: RestoreChoice,
                          io: LearnedStoreIo = LearnedStoreIo.Native): Restored =
    LearnedKeyboards.withFiles {
        // (each keyboard its own learned words first, if this start couldn't do it yet: they're restored by keyboard)
        LearnedKeyboards.updateOnce(ctx)
        restoreChosenLocked(ctx, pending, plan, choice, io)
    }

/**
 * The order (owner 2026-10-12): the backup's words, dictionaries, clips and personal dictionary words are unpacked aside
 * first (one read of the zip, nothing changed); then [restoreKeyboards] does the device side (new ids, "… 2" names,
 * renamed key layouts) and the backup's things, parts before what uses them: saved items, dictionaries, settings,
 * keyboards; then their word lists, and last the clipboard and Android's personal dictionary words.
 */
private fun restoreChosenLocked(ctx: Context, pending: PendingRestore, plan: RestorePlan, choice: RestoreChoice, io: LearnedStoreIo): Restored {
    // (one answered "Don't restore" brings nothing: not its learned words or dictionaries either)
    val restoring = choice.keyboards.filter { choice.decisions[it] != RestoreDecision.Skip }
    val tags = restoring.flatMap { listOf(it.locale) + getSecondaryLocales(it.extraValues) }.map { it.toLanguageTag() }.toSet()
    // the languages of every keyboard in the backup, ticked or not: their dictionaries are the keyboards' (R-DICT)
    val backupTags = backupKeyboardLanguages(pending.keyboards)
    val filesDir = ctx.filesDir
    val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    // learned words are added to the device's, never replacing them: the backup's stores are unpacked aside, then put
    // together with the device's (the higher count each, DM18); the removed-word lists are combined. The backup's stores are per
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
    // the backup's added dictionaries, unpacked aside (owner 2026-10-13, R-DICT; replaces review L3.5's "no keyboard
    // restored, no dictionary"): a language of a keyboard in the backup only when a restored keyboard (ticked, not
    // "Don't restore") has it; a language no backup keyboard has with the row "Other dictionaries". Either way the
    // backup's takes over (the device's kept aside as "…prev", selectable). Each language's folder on the device
    val dictsDir = File(ctx.cacheDir, "restore_dicts").apply { deleteRecursively() }
    val dictFolders = HashMap<String, File>()
    fun wantedDictionary(path: String): RestoreDictionaries.Entry? {
        val entry = RestoreDictionaries.entryOf(path) ?: return null
        return if (entry.tag in backupTags) entry.takeIf { choice.dictionaries && entry.tag in tags }
            else entry.takeIf { choice.otherDictionaries }
    }
    val restoredDb = ctx.getDatabasePath(Database.NAME + "_restored")
    // (one a failed restore left behind is never this backup's clipboard: owner 2026-10-13, 20261013_L2)
    restoredDb.delete()
    var personalWords: String? = null
    if (filesDir != null) ZipInputStream(FileInputStream(pending.file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            val protected = name.startsWith("unprotected${File.separator}")
            val path = name.substringAfter("unprotected${File.separator}")
            val target = if (protected) File(deviceProtectedFilesDir, path) else File(filesDir, path)
            when {
                !backupFilePatterns.any { path.matches(it) } -> when {
                    name == Database.NAME && choice.clipboard -> FileUtils.copyStreamToNewFile(zip, restoredDb)
                    name == PERSONAL_DICT_FILE_NAME && choice.personalDictionary -> { personalWords = String(zip.readBytes()) }
                }
                choice.learnedWords && isLearnedWords(path) -> {
                    val aside = File(learnedDir, path)
                    if (aside.canonicalPath.startsWith(learnedDir.canonicalPath + File.separator)) FileUtils.copyStreamToNewFile(zip, aside)
                }
                wantedDictionary(path) != null -> {
                    val dict = wantedDictionary(path)!!
                    val aside = File(File(dictsDir, dict.tag), dict.name)
                    if (aside.canonicalPath.startsWith(dictsDir.canonicalPath + File.separator)) {
                        FileUtils.copyStreamToNewFile(zip, aside)
                        target.parentFile?.let { dictFolders[dict.tag] = it }
                    }
                }
            }
            zip.closeEntry()
            BackupRestoreJob.step()
            entry = zip.nextEntry
        }
    }
    // from here on the device changes; a failure after the first change is one partway: nothing is undone, and the
    // restore says so (owner 2026-10-12, A3); one before it fails as before
    var changed = false
    val changing = { changed = true }
    // the keyboard being typed on, before anything changes (Q1)
    val typing = try { SubtypeSettings.getSelectedSubtype(ctx.realPrefs()).toSettingsSubtype() } catch (e: Exception) { null }
    try {
        // the device side, then the saved items, the dictionaries (between them and the keyboards), the settings and the
        // keyboards (owner 2026-10-12: "A keyboard never arrives before what it names")
        var dictionaries = RestoredDictionaries()
        val restored = restoreKeyboards(ctx, pending, plan, restoring, choice.settings, choice.names, choice.decisions, changing) {
            changing()
            // (everything unpacked takes over; a new one is selected too, over a built-in one the device selected, for a
            // restored keyboard's language as for the other dictionaries: owner 2026-10-13 "dictionaries should come and
            // take over")
            dictionaries = restoreDictionaries(ctx, pending.prefs, dictsDir, dictFolders, clashesFor = dictFolders.keys.toSet(),
                selectNew = dictFolders.keys.toSet())
            dictsDir.deleteRecursively()
        }
        // (the learned words, the clips and the personal dictionary words change the device too)
        changing()
        if (choice.learnedWords) {
            // (merged: with its learned words ticked only; kept both: its own words into a pool of its own, owner 2026-10-12)
            restoreLearnedWords(ctx, pending.prefs, restored.onDevice.filterKeys { (choice.decisions[it] as? RestoreDecision.Merge)?.learnedWords != false }
                .toList(), learnedDir, io)
            learnedDir.deleteRecursively()
        }
        // merged into the device's clips (owner 2026-10-12: "yes, merge clipboard"; the full restore still replaces them)
        // (never fails the restore: a clipboard it can't read is skipped, the device's kept, review 2026-10-12 M8)
        // (copyFromDb deletes the file: whether the backup had clips is asked first)
        val hadClips = choice.clipboard && restoredDb.exists()
        val clipboard = hadClips && Database.copyFromDb(restoredDb, ctx,
            mergeKeeping = ctx.prefs().getInt(Settings.PREF_CLIPBOARD_HISTORY_SIZE, Defaults.PREF_CLIPBOARD_HISTORY_SIZE))
        if (hadClips && !clipboard) BackupRestoreJob.clipboardSkipped = true
        // Android's personal dictionary words: a word missing for its locale is added (never fail the whole restore over it)
        personalWords?.let { words ->
            try { restorePersonalDictionary(ctx, words) } catch (t: Throwable) { Log.w("AdvancedScreen", "error restoring personal dictionary", t) }
        }
        BackupRestoreJob.step() // (the learned words)
        LayoutUtilsCustom.onLayoutFileChanged()
        switchToKeptCopy(ctx, choice, restored.onDevice, typing)
        return Restored(restored.onDevice, restored.hidden, restored.merged, restored.added, dictionaries,
            settings = choice.settings && restored.onDevice.isNotEmpty(), clipboard = clipboard, personalWords = personalWords != null)
    } catch (t: Throwable) {
        throw if (changed) RestoreStoppedPartway(t) else t
    }
}

/**
 * After a restore that kept a backup keyboard apart ("Keep both"), the keyboard in use becomes the restored copy of the
 * one being typed on before ([typing]): the kept-apart backup keyboard with its id (as "Suggested: same keyboard as in
 * the backup") (owner 2026-10-13, Q1: "User's choice to restore should take precedence", "restored copy of what I was
 * typing"). With no such copy the keyboard in use stays (Q1b: no fallback to another keyboard), and so do other
 * restores. [onDevice]: each restored backup keyboard as it is on the device now.
 */
private fun switchToKeptCopy(ctx: Context, choice: RestoreChoice, onDevice: Map<SettingsSubtype, SettingsSubtype>, typing: SettingsSubtype?) {
    val id = typing?.keyboardId ?: return
    val target = onDevice.entries.firstOrNull { (backup, _) -> choice.decisions[backup] is RestoreDecision.Separate && backup.keyboardId == id }
        ?.value ?: return
    val real = ctx.realPrefs()
    SubtypeSettings.setSelectedSubtype(real, target.toAdditionalSubtype())
    KeyboardProfiles.refreshImeId(real) // (its own settings; the keyboard takes it with the restore's refresh)
    helium314.keyboard.latin.utils.SettingsEventLog.keyboard(ctx, "in use after the restore", target, null)
}

/**
 * The backup's added dictionaries unpacked in [dictsDir] ("<tag>/<name>") into the device's language folders
 * ([folders], by tag) as [RestoreDictionaries] plans them: a clash, or a match selected (B4), only for the languages
 * [clashesFor] (those the restore takes over with); for the others only the new ones come in, the device's stay; a new
 * one of [selectNew] is selected too. Returns what came in (the "Backup restored" dialog's line).
 */
private fun restoreDictionaries(ctx: Context, backupPrefs: Map<String, Any?>, dictsDir: File, folders: Map<String, File>,
                                clashesFor: Set<String>, selectNew: Set<String> = emptySet()): RestoredDictionaries {
    val backup = HashMap<RestoreDictionaries.Entry, String>()
    for (tag in folders.keys) File(dictsDir, tag).listFiles()?.forEach { f ->
        if (f.isFile) backup[RestoreDictionaries.Entry(tag, f.name)] = f.inputStream().use { RestoreDictionaries.digest(it) }
    }
    if (backup.isEmpty()) return RestoredDictionaries()
    val real = ctx.realPrefs()
    val steps = RestoreDictionaries.plan(backup, backupPrefs, { tag -> folders[tag]?.let { RestoreDictionaries.deviceDigests(it) }.orEmpty() },
        { tag, kind -> real.all[RestoreDictionaries.selectedKey(tag, kind)] as? String })
    val applied = ArrayList<RestoreDictionaries.Step>()
    for (step in steps) {
        // (the device's stays: no clash, and no match selected, B4)
        if (step.action != RestoreDictionaries.Action.NEW && step.tag !in clashesFor) continue
        val dir = folders[step.tag] ?: continue
        try {
            if (!RestoreDictionaries.apply(step, dir, File(File(dictsDir, step.tag), step.backupName), ctx.realPrefs(),
                    selectNew = step.tag in selectNew)) continue
            // (a match already in use: nothing changed, nothing to say, owner 2026-10-13, 20261013_L3)
            if (!step.unchanged) applied += step
        } catch (t: Throwable) {
            Log.w("AdvancedScreen", "error restoring the dictionary ${step.tag}/${step.backupName}", t)
        }
    }
    return RestoredDictionaries(applied)
}

/** The languages whose dictionary in the backup [pending] clashes with the device's (read off the main thread). */
private fun dictionaryClashes(ctx: Context, pending: PendingRestore): Set<String> =
    RestoreDictionaries.clashTags(dictionarySteps(ctx, pending))

/** The backup [pending]'s dictionaries as the restore would bring each against the device's (the restore dialog's
 *  subtexts, R-DICT; read off the main thread). */
private fun dictionarySteps(ctx: Context, pending: PendingRestore): List<RestoreDictionaries.Step> {
    val backup = HashMap<RestoreDictionaries.Entry, String>()
    ZipInputStream(FileInputStream(pending.file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val path = entry.name.substringAfter("unprotected${File.separator}")
            RestoreDictionaries.entryOf(path)?.let { backup[it] = RestoreDictionaries.digest(zip) }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    if (backup.isEmpty()) return emptyList()
    val folders = File(DictionaryInfoUtils.getWordListCacheDirectory(ctx))
    val real = ctx.realPrefs()
    return RestoreDictionaries.plan(backup, pending.prefs, { tag ->
        File(folders, tag).takeIf { it.isDirectory }?.let { RestoreDictionaries.deviceDigests(it) }.orEmpty() },
        { tag, kind -> real.all[RestoreDictionaries.selectedKey(tag, kind)] as? String })
}

/** [dictionaryClashes] for the backup [file] (tests). */
internal fun dictionaryClashesFrom(ctx: Context, file: File): Set<String> = dictionaryClashes(ctx, readBackup(file))

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

/** Restores [keyboards] from the backup [file] as the restore dialog does when not every keyboard is chosen (tests):
 *  each restored keyboard as it is on the device now, and those whose settings wait behind the common ones. */
internal fun restoreKeyboardsFrom(ctx: Context, file: File, keyboards: List<SettingsSubtype>, withSettings: Boolean,
                                  names: SavedItemsMerge.Choices = SavedItemsMerge.Choices(),
                                  decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap()): Pair<Map<SettingsSubtype, SettingsSubtype>, List<SettingsSubtype>> {
    val pending = readBackup(file)
    return restoreKeyboards(ctx, pending, restorePlan(ctx, pending), keyboards.filter { decisions[it] != RestoreDecision.Skip },
        withSettings, names, decisions).let { it.onDevice to it.hidden }
}

/**
 * Only [asked] keyboards come out of the backup, each as [decisions] says (owner 2026-10-11: restore matches by language
 * and asks; review R1: no matching of its own here), and the backup's saved items as [names] says, all as [plan] has
 * them (owner 2026-10-12: one plan, nothing worked out again). A keyboard is merged into the device keyboard picked (in
 * its place, its set and learned words going along; the backup's whole, or the device's with the ticked parts and menus
 * the backup's), or added as a keyboard of its own; named as answered. One without an answer comes in as new, as it is
 * in the backup. A merge keeps the device keyboard's id; one added keeps the backup's (owner 2026-10-11: "backup's ID
 * should take priority"), and a device keyboard with that id gets a new one first (KeyboardIds.giveNewId). Kept both,
 * the backup's keeps its name and the device's of that name becomes "… 2" (owner 2026-10-12; before, the backup's was
 * "English (US) 2"). Each comes with its custom key layouts under the backup's names (the device's of that name with
 * other keys renamed first, its users following) and, [withSettings], its settings and the priorities of its languages,
 * in its own set only; other keyboards stay as they are. With one set of settings for all, separate settings stay off
 * (owner 2026-10-11: R7, a restore never turns separate settings on): the keyboard's own set waits until they are turned
 * on, the main keyboard's is what all keyboards use. [beforeKeyboards]: what comes between the saved items and the
 * keyboards (the dictionaries).
 */
private fun restoreKeyboards(ctx: Context, pending: PendingRestore, plan: RestorePlan, asked: List<SettingsSubtype>, withSettings: Boolean,
                             names: SavedItemsMerge.Choices = SavedItemsMerge.Choices(), decisions: Map<SettingsSubtype, RestoreDecision> = emptyMap(),
                             changing: () -> Unit = { }, beforeKeyboards: () -> Unit = { }): Restored {
    // the unsaved layouts no keyboard uses go once all is restored, not between the keyboards (review 2026-10-11 R3: one
    // only a restored keyboard's settings name went before they were written)
    LayoutUtilsCustom.cleanupHeld++
    try { return restoreKeyboardsHeld(ctx, pending, plan, asked, withSettings, names, decisions, changing, beforeKeyboards) }
    finally { LayoutUtilsCustom.cleanupHeld--; LayoutUtilsCustom.removeUnusedPrivateLayouts(ctx) }
}

private fun restoreKeyboardsHeld(ctx: Context, pending: PendingRestore, plan: RestorePlan, asked: List<SettingsSubtype>, withSettings: Boolean,
                                 names: SavedItemsMerge.Choices, askedDecisions: Map<SettingsSubtype, RestoreDecision>,
                                 changing: () -> Unit, beforeKeyboards: () -> Unit): Restored {
    var decisions = askedDecisions
    val real = ctx.realPrefs()
    val prefs = ctx.prefs()
    val backup = pending.prefs
    val nameOf = { keyboard: SettingsSubtype -> keyboardName(keyboard, ctx) }
    // (owner 2026-10-11, keyboards have unique ids: two keyboards set up alike are two keyboards, none is blocked as the
    // same definition any more)
    val chosen = asked.filter { decisions[it] != RestoreDecision.Skip }
    fun replacesNow() = chosen.mapNotNull { keyboard -> (decisions[keyboard] as? RestoreDecision.Merge)?.let { keyboard to it.replace } }.toMap()
    var replaces = replacesNow()
    // (a keyboard merged in parts: only its ticked parts and menus come from the backup)
    fun takesLayout(keyboard: SettingsSubtype, type: LayoutType) = replaces[keyboard]?.takes(RestoreMatch.Part.Layout(type)) != false
    fun takesSetting(keyboard: SettingsSubtype, key: String) =
        replaces[keyboard]?.menus?.let { menus -> RestoreMatch.menusOf(key).any { it in menus } } ?: true
    // its own set when the backup kept one, else the backup's shared set was what it used
    // (a backup from before 0.3.008 wrote every setting at its default down: those are left out, so they follow the
    // defaults here; see pickedOnlyUpgrade) (by the keyboard as it was in the backup: the backup's sets are found by it)
    val backupSettings = if (withSettings) chosen.associateWith { restoredSettings(ctx, backup, it) } else emptyMap()
    // the custom key layouts the restore brings: those the chosen keyboards use (picked, or their default for a type), of
    // the parts and menus taken from the backup (a layout kept as the device's keeps its file too), and those of the
    // saved Layouts that come in
    val keyLayouts = plan.items.keyLayouts
    val wanted = ((chosen.flatMap { keyboard -> LayoutType.entries.filter { takesLayout(keyboard, it) }
            .mapNotNull { type -> keyboard.layoutName(type)?.let { type to it } } } +
        backupSettings.flatMap { (keyboard, values) -> LayoutType.entries.mapNotNull { type ->
            val key = Settings.PREF_LAYOUT_PREFIX + type.name
            (values[key] as? String)?.takeIf { takesSetting(keyboard, key) }?.let { type to it } } })
        .filter { LayoutUtilsCustom.isCustomLayout(it.second) } +
        (if (!withSettings) emptyList() else plan.items.layouts.filter { names.ticked(SavedItemsMerge.Kind.LAYOUTS, it.key) && it.exact == null }
            .flatMap { plan.items.layoutFiles(it.content) })).toSet()
    // the device's unsaved key layouts a ticked row leaves unused (A1b), as the dialog showed them, before anything on
    // the device changes: (type, device name) -> (the backup's name, the name they are saved as)
    val unsavedSaves = HashMap<Pair<LayoutType, String>, Pair<String?, String>>()
    for (keyboard in chosen) {
        val merge = decisions[keyboard] as? RestoreDecision.Merge ?: continue
        for (type in LayoutType.entries) {
            val name = merge.phone.layoutName(type) ?: continue
            if (!merge.replace.takes(RestoreMatch.Part.Layout(type))) continue
            val backupName = keyboard.layoutName(type)
            plan.unsavedSave(merge.phone, type, name, backupName)?.let { unsavedSaves[type to name] = backupName to it }
        }
        // one it reaches through its own set's default, when the merge takes that setting (owner 2026-10-13,
        // 20261013_M1: exactly like an own pick; before, the backup's settings replaced the default and the cleanup
        // deleted the file)
        if (keyboard in backupSettings) for (save in plan.unsavedDefaultSaves(merge.phone, keyboard)) {
            if (!takesSetting(keyboard, Settings.PREF_LAYOUT_PREFIX + save.type.name)) continue
            unsavedSaves.getOrPut(save.type to save.name) { save.backup to save.saved }
        }
    }

    // ---- the device side first (owner 2026-10-12: answers are recorded against the device's keyboards and items as they
    // were; new ids, then new names, then the renamed key layouts, and every recorded reference through them before
    // anything of the backup is applied: "Nothing applies an answer by an unmapped id") ----

    // a backup keyboard added (kept both, or new) keeps its id (owner 2026-10-11: "backup's ID should take priority"): a
    // device keyboard with that id gets a new one first, its records with it (KeyboardIds.giveNewId); old id -> new id
    val idMap = HashMap<String, String>()
    run {
        val adding = chosen.filter { decisions[it] !is RestoreDecision.Merge }.mapNotNullTo(HashSet()) { it.keyboardId }
        val (on, off) = phoneKeyboards(real)
        for (device in on + off) {
            val old = device.keyboardId ?: continue
            if (old !in adding) continue
            changing()
            helium314.keyboard.latin.settings.KeyboardIds.giveNewId(ctx, device).keyboardId?.let { idMap[old] = it }
        }
    }
    // a backup keyboard added whose Android subtype number a device keyboard's id has too (two ids, one hash; review
    // 2026-10-12 L1#1: Android would take them for one keyboard) comes in under a fresh id, as giveNewId gives one (none
    // the device knows, no clashing number); its settings, pictures, priorities, buttons and learned words are written
    // under the id it gets on the device (onPhone below), as a merge's go to the device keyboard's: backup id -> fresh id
    val freshIds = HashMap<String, String>()
    run {
        val (on, off) = phoneKeyboards(real)
        val deviceNumbers = (on + off).mapNotNullTo(HashSet()) { kb -> kb.keyboardId?.let { helium314.keyboard.latin.settings.KeyboardIds.androidSubtypeId(it) } }
        val taken = chosen.mapNotNullTo(ArrayList()) { it.keyboardId }
        for (keyboard in chosen) {
            if (decisions[keyboard] is RestoreDecision.Merge) continue
            val id = keyboard.keyboardId ?: continue
            if (helium314.keyboard.latin.settings.KeyboardIds.androidSubtypeId(id) !in deviceNumbers) continue
            val fresh = helium314.keyboard.latin.settings.KeyboardIds.newId(real, taken)
            taken += fresh
            freshIds[id] = fresh
        }
    }
    // a device keyboard as the dialog had it, as it is now: by its id through the map (its name and key layouts as now)
    fun onDeviceNow(original: SettingsSubtype): SettingsSubtype? {
        val id = original.keyboardId?.let { idMap[it] ?: it } ?: return null
        val (on, off) = phoneKeyboards(real)
        return (on + off).firstOrNull { it.keyboardId == id }
    }
    // kept both: the backup's keeps its name, a device keyboard of that name gets the next free one, "English (US) 2"
    // (owner 2026-10-12; flips 2026-10-11's, which numbered the backup's). Not one a backup keyboard merges into: that one
    // becomes the backup's
    run {
        val keepNames = chosen.filter { decisions[it] is RestoreDecision.Separate }
            .mapNotNullTo(HashSet()) { RestoreDecision.result(decisions[it], it, nameOf)?.let(nameOf) }
        if (keepNames.isEmpty()) return@run
        val targets = chosen.mapNotNullTo(HashSet()) { (decisions[it] as? RestoreDecision.Merge)?.phone?.keyboardId?.let { id -> idMap[id] ?: id } }
        val (on, off) = phoneKeyboards(real)
        val taken = HashSet<String>()
        (on + off).mapTo(taken, nameOf)
        chosen.mapNotNullTo(taken) { RestoreDecision.result(decisions[it], it, nameOf)?.let(nameOf) }
        for (device in on + off) {
            val name = nameOf(device)
            if (name !in keepNames || device.keyboardId in targets) continue
            val renamed = RestoreDecision.named(device, RestorePlan.nextFreeName(name, taken).also { taken += it }, nameOf)
            changing()
            SubtypeUtilsAdditional.changeAdditionalSubtype(device, renamed, ctx, action = "renamed")
        }
    }
    // one a ticked row replaces with another layout, unused by every other keyboard then and with other keys than the
    // backup's: its keys saved under the name the warning showed, a copy (owner 2026-10-12: "give it same treatment of
    // temp_MMDD ig."; before, it went with the cleanup); the keyboard takes the backup's as answered.
    // Copied before the renames below: one of its name the restore also brings moves it away first, and the cleanup then
    // took the moved file, unused (round-5 review)
    for ((key, save) in unsavedSaves) {
        val (type, name) = key
        if (save.first == name) continue
        val from = LayoutUtilsCustom.getLayoutFile(name, type, ctx)
        val to = LayoutUtilsCustom.getLayoutFile(save.second, type, ctx)
        if (from.isFile && !to.exists()) { changing(); from.copyTo(to); LayoutUtilsCustom.onLayoutFileChanged() }
    }
    // the device's key layout files of a name the restore brings with other keys: renamed first, as the editor's rename
    // does, every keyboard (its identity), default and saved Layout using it following (owner 2026-10-12: "Backup's My
    // numrow; yours becomes My numrow 2"; before, the backup's came in as "My numrow 2")
    // An unsaved one a ticked "What to merge" row replaces with the backup's under its name is saved instead, under the
    // name the row's warning showed (owner 2026-10-12: "Unsaved number row on the device will be saved as "temp_MMDD" to
    // prevent loss of unsaved data"; before, it got a new unsaved name, unused then, and went); its other users follow
    // it there, so they keep their keys. Kept both, or another keyboard's: a new unsaved name, as before.
    for ((type, name) in wanted) {
        val to = unsavedSaves[type to name]?.takeIf { it.first == name }?.second ?: keyLayouts.deviceRename(type, name)
        to?.let { changing(); renamePhoneLayoutFile(ctx, SavedItemsMerge.FileClash(type, name, it)) }
    }
    // the merge targets as they are now (R4: their key layouts renamed; a new id)
    fun mapTargets() {
        decisions = decisions.mapValues { (_, d) -> if (d is RestoreDecision.Merge) onDeviceNow(d.phone)?.let { d.copy(phone = it) } ?: d else d }
        replaces = replacesNow()
    }
    mapTargets()

    // ---- the backup's things, parts before what uses them (owner 2026-10-12): key layouts; palettes, fonts and pictures,
    // popup sets, Layouts, themes; the dictionaries; the settings; the keyboards ----
    changing() // (from here on each step changes the device)
    writeKeyLayouts(ctx, keyLayouts, wanted)
    val applied = if (withSettings) SavedItemsMerge.apply(ctx, real, plan.items, names, write = true, files = backupFiles(pending)) else null
    // the device's keyboards that used an item now the backup's take it ("they take the backup's look"), by the plan's
    // lists through the id map; a Layout can change their keys, so the merge targets are looked up again
    applied?.let { if (it.replaced.isNotEmpty()) { reapplyReplaced(ctx, plan, it.replaced, ::onDeviceNow); mapTargets() } }
    beforeKeyboards()

    // one mapping, backup keyboard -> the device keyboard it becomes: merged or added as answered, named as answered
    // (one added under a fresh id, its Android number a device keyboard's: with that id, its came-from as in the backup)
    val onPhone = chosen.associateWith { keyboard ->
        val result = RestoreDecision.result(decisions[keyboard], keyboard, nameOf)!!
        val fresh = if (decisions[keyboard] is RestoreDecision.Merge) null else result.keyboardId?.let { freshIds[it] }
        if (fresh == null) result else result.withKeyboardId(fresh, result.keyboardFrom).toPref().toSettingsSubtype()
    }
    // its saved items named as they are called on the device after the restore
    val settings = backupSettings.mapValues { (_, values) -> applied?.renames?.apply(values) ?: values }
    val wasSeparate = KeyboardProfiles.isSeparate(real)

    if (withSettings && chosen.isNotEmpty()) {
        // the languages' adding times are app-wide (the priorities are the keyboard's: written with its set below)
        val editor = real.edit()
        for (keyboard in chosen)
            for (locale in listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues))
                for (key in LanguagePriority.keys(locale)) if (!LanguagePriority.isPriorityKey(key)) backup[key]?.let { KeyboardProfiles.put(editor, key, it) }
        editor.apply()
    }

    // with their settings: the pictures they had (added to the device's lists, nothing there replaced)
    val pictureFiles = HashMap<String, ByteArray>()
    // only the chosen keyboards' pictures are read (each can be a few MB)
    val wantedPictures = chosen.flatMap { KeyboardProfiles.restoreFileNames(it.keyboardId ?: KeyboardProfiles.SHARED) }.toSet()
    if (withSettings && wantedPictures.isNotEmpty()) {
        ZipInputStream(FileInputStream(pending.file)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                // (an old backup's pictures are named for its set numbers: by the ids its keyboards got when read)
                val name = helium314.keyboard.latin.settings.KeyboardIdUpgrade.renamedFile(entry.name.substringAfter("unprotected${File.separator}", ""), pending.pictureRenames)
                if (name in wantedPictures) pictureFiles[name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    for (keyboard in chosen) {
        val phoneKeyboard = onPhone.getValue(keyboard)
        val replace = replaces[keyboard]
        // registers it; merged, the device keyboard picked is changed into it (in its place, its id kept: its set and
        // learned words its own, as a Rename does); a switched-off one is switched on (restored)
        SubtypeUtilsAdditional.changeAdditionalSubtype(replace?.phone ?: phoneKeyboard, phoneKeyboard, ctx, action = null)
        if (SubtypeSettings.getEnabledSubtypes().none { it.toSettingsSubtype().sameKeyboard(phoneKeyboard) })
            SubtypeSettings.addEnabledSubtype(prefs, phoneKeyboard.toAdditionalSubtype())
        // (one line, owner 2026-10-12 K1)
        helium314.keyboard.latin.utils.SettingsEventLog.keyboard(ctx, "restored", phoneKeyboard,
            replace?.phone?.let { "merged into the device's " + helium314.keyboard.latin.utils.SettingsEventLog.describe(ctx, it) })
        if (withSettings) {
            val id = phoneKeyboard.keyboardId ?: continue // (always one: a backup is given ids when read)
            // exactly that set, its own only, a shared menu's values too (owner 2026-10-11: "restore should only apply to
            // that keyboard's settings"; the main keyboard's set is the common settings: restored, everyone sees it, owner
            // 2026-10-11: "yep"). Merged in parts: only its entries of the ticked menus, the others stay as they are
            val restored = settings.getValue(keyboard)
            if (replace?.menus != null) KeyboardProfiles.restoreSet(real, id, restored) { takesSetting(keyboard, it) }
            else KeyboardProfiles.restoreSet(real, id, restored)
            // its languages' priorities, into its own set (review session 2026-10-07): the backup's own copy for this
            // keyboard (an old backup's common value is ignored, owner 2026-10-11: "fine to trash common set with upgrade
            // and restore"; merged in parts: only when its languages are the backup's)
            val backupId = keyboard.keyboardId
            if (replace?.takes(RestoreMatch.Part.Extra(helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.SECONDARY_LOCALES)) != false) real.edit {
                for (locale in listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues))
                    for (key in LanguagePriority.keys(locale).filter { LanguagePriority.isPriorityKey(it) }) {
                        val value = backupId?.let { backup[KeyboardProfiles.ownKey(it, key)] } as? Int ?: continue
                        putInt(KeyboardProfiles.ownKey(id, key), value)
                    }
            }
            // its own pictures in the backup, or the backup's shared ones when it had no set of its own (merged in
            // parts: with Appearance ticked)
            if (replace?.takes(KeyboardProfiles.Group.APPEARANCE) != false)
                KeyboardProfiles.restoreFiles(pictureFiles, keyboard.keyboardId, id,
                    perKeyboardBackup = backup["profile_files_migrated"] == true)
        }
    }
    if (withSettings) {
        Settings.clearCachedBackgroundImages(); helium314.keyboard.keyboard.KeyboardTypeface.clearCache()
        // whether they use the other keyboards' learned and blacklisted words, as in the backup (merged in parts: the
        // device's buttons stay)
        if (chosen.isNotEmpty()) LearnedKeyboards.restoreButtons(real, backup, chosen.filter { replaces[it]?.whole != false }, onPhone)
    }
    // (merged with no menu ticked: none of its settings came, nothing to say)
    val hidden = if (withSettings && !wasSeparate) chosen.filter { replaces[it]?.menus?.isEmpty() != true }.map { onPhone.getValue(it) } else emptyList()
    val merged = chosen.filter { decisions[it] is RestoreDecision.Merge }.map { nameOf(onPhone.getValue(it)) } + applied?.replaced.orEmpty().map { it.second }
    val added = chosen.filter { decisions[it] !is RestoreDecision.Merge }.map { nameOf(onPhone.getValue(it)) } + applied?.added.orEmpty().map { it.second }
    return Restored(onPhone, hidden, merged, added)
}

/** The device's key layout file [rename] names gets its new name, as the layout editor's rename does: every keyboard
 *  (its identity), default and saved Layout using it follows ([SubtypeSettings.onRenameLayout]). */
private fun renamePhoneLayoutFile(ctx: Context, rename: SavedItemsMerge.FileClash) {
    val (type, from, to) = rename
    val old = LayoutUtilsCustom.getLayoutFile(from, type, ctx)
    val new = LayoutUtilsCustom.getLayoutFile(to, type, ctx)
    if (!old.isFile || new.exists()) return
    old.copyTo(new)
    old.delete()
    LayoutUtilsCustom.onLayoutFileChanged()
    SubtypeSettings.onRenameLayout(type, from, to, ctx)
}

/** The backup's custom key layouts [wanted] (type, name) written under their names (owner 2026-10-12: the backup's keeps
 *  its name; the device's of that name with other keys was renamed first): one the device has already is the device's,
 *  nothing written over. Never outside the app's folder. */
private fun writeKeyLayouts(ctx: Context, keyLayouts: SavedItemsMerge.KeyLayouts, wanted: Set<Pair<LayoutType, String>>) {
    val dir = DeviceProtectedUtils.getFilesDir(ctx)
    var wrote = false
    for (key in wanted) {
        val bytes = keyLayouts.contents[key] ?: continue
        val (type, name) = key
        val folder = File(dir, type.folder)
        val target = File(folder, name)
        if (!target.canonicalPath.startsWith(folder.canonicalPath + File.separator) || target.exists()) continue
        folder.mkdirs()
        helium314.keyboard.latin.utils.AtomicFiles.write(target, bytes) // (crash-safe, B4w.2)
        wrote = true
    }
    if (wrote) LayoutUtilsCustom.onLayoutFileChanged()
}

/** A loaded font's or picture's bytes in the backup of [pending], by kind and name (read when written). */
private fun backupFiles(pending: PendingRestore): (SavedItemsMerge.Kind, String) -> ByteArray? = { kind, key ->
    val path = "unprotected${File.separator}${if (kind == SavedItemsMerge.Kind.FONTS) "fonts" else "pictures"}${File.separator}$key"
    ZipInputStream(FileInputStream(pending.file)).use { zip ->
        var bytes: ByteArray? = null
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null && bytes == null) {
            if (entry.name == path) bytes = zip.readBytes()
            zip.closeEntry()
            entry = zip.nextEntry
        }
        bytes
    }
}

/** The device's keyboards that used a saved item the restore made the backup's ([replaced]: kind and name) take it
 *  (owner 2026-10-12: "Used by your English (US), French: they take the backup's look"): a theme's or a Layout's settings,
 *  a popup set's arrangement, as their update does on another keyboard. The users are the plan's
 *  ([RestorePlan.deviceUsers]), each as it is now ([now]: through the id map). Palettes and loaded files are named and
 *  read as they are: nothing to do. */
private fun reapplyReplaced(ctx: Context, plan: RestorePlan, replaced: List<Pair<SavedItemsMerge.Kind, String>>,
                            now: (SettingsSubtype) -> SettingsSubtype?) {
    if (replaced.isEmpty()) return
    val real = ctx.realPrefs()
    for ((kind, name) in replaced) for (user in plan.deviceUsers[kind to name].orEmpty()) {
        val keyboard = now(user) ?: continue
        try {
            when (kind) {
                SavedItemsMerge.Kind.THEMES -> helium314.keyboard.settings.AppearanceLooks.load(real).firstOrNull { it.name == name }
                    ?.let { helium314.keyboard.settings.AppearanceLooks.applyToOther(ctx, keyboard, it) }
                SavedItemsMerge.Kind.LAYOUTS -> helium314.keyboard.settings.LayoutPresets.load(real).firstOrNull { it.name == name }
                    ?.let { helium314.keyboard.settings.LayoutPresets.applyToOther(ctx, keyboard, it) }
                SavedItemsMerge.Kind.POPUP_SETS -> helium314.keyboard.latin.utils.KeyPopupOverrides.loadSets(real).firstOrNull { it.name == name }
                    ?.let { helium314.keyboard.settings.screens.applySetToOther(ctx, keyboard, it, helium314.keyboard.settings.SavedItemDefaults.takeNewDefaults(real)) }
                else -> { }
            }
        } catch (t: Throwable) {
            Log.w("AdvancedScreen", "error giving a keyboard the restored ${kind.name.lowercase()}", t)
        }
    }
}

/** The device's keyboards: the enabled ones, and those switched off on the Languages screen (they keep their id and own
 *  set; owner 2026-10-11: treated like switched-on ones). */
private fun phoneKeyboards(real: SharedPreferences): Pair<List<SettingsSubtype>, List<SettingsSubtype>> {
    val enabled = SubtypeSettings.getEnabledSubtypes(true).map { it.toSettingsSubtype() }
    val off = helium314.keyboard.latin.settings.SwitchedOffKeyboards.list(real).filter { kb -> enabled.none { it.sameKeyboard(kb) } }
    return enabled to off
}

/** A file's size and SHA-256, read to its end (the stream is left open). */
private fun fileDigest(input: java.io.InputStream): SavedItemsMerge.FileDigest {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var size = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        md.update(buffer, 0, n)
        size += n
    }
    return SavedItemsMerge.FileDigest(size, md.digest().joinToString("") { "%02x".format(it) })
}

/** The device's time, for the date in the name unsaved key layouts are saved as ("temp_MMDD"; tests set it). */
internal var restoreClock: () -> Long = { System.currentTimeMillis() }

/**
 * The backup's saved items as the restore plans them ([SavedItemsMerge.plan]), reading its zip once: its loaded fonts
 * and pictures, compared with the device's by content, and its custom key layouts (its files, and the keys its saved
 * Layouts keep). A key layout the device has under the same name with other keys gets the device's new name here, once
 * (owner 2026-10-12: "Backup's My numrow; yours becomes My numrow 2"; an unsaved one a new unsaved name, and the name it is
 * saved as when a ticked row replaces it, "temp_MMDD" of today, "temp_1012 2" when taken), none the device or the backup
 * has. So does every unsaved one of a device keyboard a backup keyboard has another layout for: a ticked row can leave
 * it unused (owner 2026-10-12: "give it same treatment of temp_MMDD ig.", A1b; whether it is saved, the dialog and the
 * run work out by [RestorePlan.unsavedSave]). Reads only.
 */
private fun planItems(ctx: Context, pending: PendingRestore): SavedItemsMerge.Plan {
    val real = ctx.realPrefs()
    val dir = DeviceProtectedUtils.getFilesDir(ctx)
    val fonts = ArrayList<SavedItemsMerge.In<SavedItemsMerge.FileDigest>>()
    val pictures = ArrayList<SavedItemsMerge.In<SavedItemsMerge.FileDigest>>()
    val keyFiles = LinkedHashMap<Pair<LayoutType, String>, ByteArray>()
    ZipInputStream(FileInputStream(pending.file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val name = entry.name.substringAfter("unprotected${File.separator}", "")
            val folder = name.substringBefore(File.separator, "")
            val file = name.substringAfter(File.separator, "")
            if ((folder == "fonts" || folder == "pictures") && file.isNotEmpty() && !file.contains(File.separator)
                    && backupFilePatterns.any { name.matches(it) })
                (if (folder == "fonts") fonts else pictures) += SavedItemsMerge.In(file, file, fileDigest(zip))
            else LayoutType.entries.firstOrNull { name.startsWith(it.folder + File.separator) }?.let { type ->
                val layout = name.removePrefix(type.folder + File.separator)
                if (LayoutUtilsCustom.isCustomLayout(layout) && !layout.contains(File.separator)) keyFiles[type to layout] = zip.readBytes()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    fun device(folder: String) = File(dir, folder).listFiles()?.filter { it.isFile }?.sortedBy { it.name }
        ?.map { f -> f.name to f.inputStream().use { fileDigest(it) } }.orEmpty()
    val items = SavedItemsMerge.plan(ctx, real, pending.prefs, fonts, pictures, device("fonts"), device("pictures"))
    // the custom key layouts: the backup's files, and the keys its saved Layouts keep (the file's first)
    val contents = LinkedHashMap(keyFiles)
    for (layout in items.layouts) for ((type, name) in items.layoutFiles(layout.content))
        (layout.content.values[helium314.keyboard.settings.LayoutPresets.LAYOUT_TEXT + type.name] as? String)
            ?.let { text -> if ((type to name) !in contents) contents[type to name] = text.toByteArray() }
    val renames = HashMap<Pair<LayoutType, String>, String>()
    val saves = HashMap<Pair<LayoutType, String>, String>()
    val today = "temp_" + SimpleDateFormat("MMdd", Locale.ROOT).format(java.util.Date(restoreClock()))
    val taken = HashMap<LayoutType, MutableSet<String>>()
    fun taken(type: LayoutType) = taken.getOrPut(type) {
        HashSet<String>().apply { File(dir, type.folder).list()?.let { addAll(it) }; contents.keys.forEach { if (it.first == type) add(it.second) } } }
    // (the next free name of the same languages, counting up: RestorePlan.nextFreeName)
    fun save(type: LayoutType, name: String) {
        val names = taken(type)
        val scope = LayoutUtilsCustom.scopeOf(name, type)
        var display = today
        while (LayoutUtilsCustom.namedLayoutName(display, type, scope) in names) display = RestorePlan.nextFreeName(display, setOf(display))
        val saved = LayoutUtilsCustom.namedLayoutName(display, type, scope)
        names += saved
        saves[type to name] = saved
    }
    for ((key, bytes) in contents) {
        val (type, name) = key
        val folder = File(dir, type.folder)
        val file = File(folder, name)
        // (never outside the app's folder, whatever the zip says)
        if (!file.canonicalPath.startsWith(folder.canonicalPath + File.separator)) continue
        if (!file.isFile || file.readBytes().contentEquals(bytes)) continue
        val names = taken(type)
        val newName = if (LayoutUtilsCustom.isPrivateLayout(name))
                LayoutUtilsCustom.newPrivateLayoutName(type, LayoutUtilsCustom.scopeOf(name, type)) { it in names }
            else generateSequence(2) { it + 1 }.map { LayoutUtilsCustom.numberedLayoutName(name, type, it) }.first { it !in names }
        names += newName
        renames[key] = newName
        if (LayoutUtilsCustom.isPrivateLayout(name)) save(type, name)
    }
    // the device keyboards' other unsaved ones a backup keyboard has another layout for (A1b), in the keyboards' order
    val (on, off) = phoneKeyboards(real)
    for (keyboard in on + off) for (type in LayoutType.entries) {
        val name = keyboard.layoutName(type)?.takeIf { LayoutUtilsCustom.isPrivateLayout(it) } ?: continue
        if ((type to name) in saves || pending.keyboards.none { it.layoutName(type) != name }) continue
        save(type, name)
    }
    // and those they reach through their own sets' defaults a backup keyboard has another default for (owner 2026-10-13,
    // 20261013_M1: like an own pick), after them
    val all = real.all
    val backupDefaults by lazy { pending.keyboards.map { kb -> try { restoredSettings(ctx, pending.prefs, kb) } catch (e: Exception) { emptyMap<String, Any?>() } } }
    for (keyboard in on + off) for (type in LayoutType.entries) {
        val name = ownUnsavedDefault(all, keyboard, type) ?: continue
        if ((type to name) in saves || backupDefaults.none { it[Settings.PREF_LAYOUT_PREFIX + type.name] != name }) continue
        save(type, name)
    }
    return SavedItemsMerge.Plan(items.palettes, items.popupSets, items.layouts, items.themes, items.fonts, items.pictures,
        SavedItemsMerge.KeyLayouts(contents, renames, saves), items.deviceFonts, items.devicePictures)
}

/** The [type] default the device's [keyboard]'s own set stores (shown or hidden) when it names an unsaved key layout,
 *  else null; [all]: the stored preferences (owner 2026-10-13, 20261013_M1). */
private fun ownUnsavedDefault(all: Map<String, *>, keyboard: SettingsSubtype, type: LayoutType): String? =
    keyboard.keyboardId?.let { all[KeyboardProfiles.ownKey(it, Settings.PREF_LAYOUT_PREFIX + type.name)] as? String }
        ?.takeIf { LayoutUtilsCustom.isPrivateLayout(it) }

/** Whether a device keyboard other than [target] names the [type] key layout [name] itself: its own pick, or a default
 *  its own set stores ([all]), as the cleanup counts them ([LayoutUtilsCustom.removeUnusedPrivateLayouts]) (owner
 *  2026-10-13, 20261013_M1). */
private fun namedByOthers(ctx: Context, all: Map<String, *>, type: LayoutType, name: String, target: SettingsSubtype): Boolean {
    val (on, off) = phoneKeyboards(ctx.realPrefs())
    return (on + off).any { kb -> !kb.sameKeyboard(target) &&
        (kb.layoutName(type) == name || kb.keyboardId?.let { all[KeyboardProfiles.ownKey(it, Settings.PREF_LAYOUT_PREFIX + type.name)] } == name) }
}

/**
 * The restore's plan of [pending] (owner 2026-10-12: one plan, made once when the backup is read; the dialog, its
 * questions and the run all read it): its keyboards as they come in (as in the backup), the device's keyboards, every
 * saved item and custom key layout of the backup with its candidates on the device ([planItems]), what uses what. A
 * backup keyboard's settings are compared with its saved items named as the restore names them, and a device keyboard's
 * with the device's items renamed as the restore renames them for the dialog's answers (R3; worked out with nothing
 * written).
 */
private fun restorePlan(ctx: Context, pending: PendingRestore): RestorePlan {
    val real = ctx.realPrefs()
    val (enabled, off) = phoneKeyboards(real)
    val items = try { planItems(ctx, pending) } catch (e: Exception) {
        Log.w("AdvancedScreen", "error planning the backup's saved items", e)
        SavedItemsMerge.Plan()
    }
    val dictionarySteps = try { dictionarySteps(ctx, pending) } catch (e: Exception) { emptyList() }
    val keyLayouts = items.keyLayouts
    val dir = DeviceProtectedUtils.getFilesDir(ctx)
    fun deviceKeys(type: LayoutType, name: String) = File(File(dir, type.folder), name).takeIf { it.isFile }?.readBytes()
    // the same keys: one name whose device file the restore doesn't rename, or two unsaved layouts with the same keys
    // (review 2026-10-11 R3; owner 2026-10-11: "Screen 2 should handle unsaved items as named yes")
    val sameLayout = { type: LayoutType, phone: String, backup: String ->
        if (phone == backup) keyLayouts.deviceRename(type, backup) == null
        else LayoutUtilsCustom.isPrivateLayout(phone) && LayoutUtilsCustom.isPrivateLayout(backup)
            && keyLayouts.contents[type to backup]?.let { keys -> deviceKeys(type, phone)?.contentEquals(keys) } == true
    }
    // an unsaved key layout of the device keyboard [target] a ticked row replaces: saved when the backup's of its name has
    // other keys (A1), or, another layout taking its place, when no other keyboard uses it and the backup's keys are
    // others (owner 2026-10-12: "give it same treatment of temp_MMDD ig.", A1b; nothing lost otherwise)
    val unsavedSave = { target: SettingsSubtype, type: LayoutType, phone: String, backup: String? ->
        keyLayouts.deviceSave(type, phone)?.takeIf {
            if (phone == backup) keyLayouts.deviceRename(type, phone) != null
            else LayoutUtilsCustom.keyboardsUsing(ctx, type, phone, target).isEmpty()
                && backup?.let { keyLayouts.contents[type to it] ?: deviceKeys(type, it) }?.let { keys -> deviceKeys(type, phone)?.contentEquals(keys) } != true
        }
    }
    // the same for an unsaved one the device's [target] reaches through its own set's default, the backup keyboard's
    // default there taking its place (owner 2026-10-13, 20261013_M1: "exactly like an own pick", the same warning and
    // temp_MMDD save; before, the cleanup deleted it). Not one its own pick names too: that row's ([unsavedSave]).
    // Another user is a keyboard naming it itself, by its pick or a default its own set stores (one reading [target]'s
    // set through the common settings reads what the merge replaces)
    val unsavedDefaultSaves = { target: SettingsSubtype, keyboard: SettingsSubtype ->
        val all = real.all
        val backupValues = try { restoredSettings(ctx, pending.prefs, keyboard) } catch (e: Exception) { emptyMap<String, Any?>() }
        LayoutType.entries.mapNotNull { type ->
            val phone = ownUnsavedDefault(all, target, type) ?: return@mapNotNull null
            if (target.layoutName(type) == phone) return@mapNotNull null
            val backup = backupValues[Settings.PREF_LAYOUT_PREFIX + type.name] as? String
            keyLayouts.deviceSave(type, phone)?.takeIf {
                if (phone == backup) keyLayouts.deviceRename(type, phone) != null
                else !namedByOthers(ctx, all, type, phone, target)
                    && backup?.let { keyLayouts.contents[type to it] ?: deviceKeys(type, it) }?.let { keys -> deviceKeys(type, phone)?.contentEquals(keys) } != true
            }?.let { RestorePlan.DefaultSave(type, phone, backup, it) }
        }
    }
    val cache = HashMap<SavedItemsMerge.Choices, SavedItemsMerge.Renames?>()
    // (one at a time: "What to merge" works it out off the main thread, R5)
    fun renames(choices: SavedItemsMerge.Choices) = synchronized(cache) { cache.getOrPut(choices) {
        try { SavedItemsMerge.apply(ctx, real, items, choices, write = false).renames } catch (e: Exception) { null } // (then compared as in the backup)
    } }
    // what uses what: the backup's Layouts and themes their inner items; the backup's keyboards their items, directly or
    // through a Layout or theme ("Required for"); the device's keyboards each device item a question offers
    val usedByItems = HashMap<Pair<SavedItemsMerge.Kind, String>, MutableSet<Pair<SavedItemsMerge.Kind, String>>>()
    for (kind in listOf(SavedItemsMerge.Kind.LAYOUTS, SavedItemsMerge.Kind.THEMES)) for (item in items.of(kind))
        for (inner in SavedItemsMerge.namedByItem(items, item)) usedByItems.getOrPut(inner) { LinkedHashSet() } += kind to item.key
    val usedBy = HashMap<Pair<SavedItemsMerge.Kind, String>, MutableList<SettingsSubtype>>()
    for (keyboard in pending.keyboards) {
        val values = try { restoredSettings(ctx, pending.prefs, keyboard) } catch (e: Exception) { continue }
        val direct = SavedItemsMerge.namedBy(items, values)
        val all = direct + direct.flatMap { (kind, key) -> items.item(kind, key)?.let { SavedItemsMerge.namedByItem(items, it) }.orEmpty() }
        for (key in all) usedBy.getOrPut(key) { ArrayList() }.let { if (keyboard !in it) it += keyboard }
    }
    val deviceSettings = (enabled + off).distinct().associateWith { RestoreMatch.phoneSettings(real, it) }
    val deviceUsers = HashMap<Pair<SavedItemsMerge.Kind, String>, List<SettingsSubtype>>()
    for (kind in SavedItemsMerge.Kind.entries) for (item in items.of(kind)) for (name in item.candidates)
        deviceUsers.getOrPut(kind to name) { deviceSettings.filterValues { SavedItemsMerge.names(kind, name, it) }.keys.toList() }
    return RestorePlan(pending.keyboards.associateWith { it }, enabled, off, nameOf = { keyboardName(it, ctx) }, sameLayout = sameLayout,
        backupSettings = { keyboard, choices -> restoredSettings(ctx, pending.prefs, keyboard).let { renames(choices)?.apply(it) ?: it } },
        phoneSettings = { values, choices -> renames(choices)?.phoneApply(values) ?: values },
        clashes = items.clashes,
        dictionaryClashes = RestoreDictionaries.clashTags(dictionarySteps),
        dictionarySteps = dictionarySteps,
        items = items, usedBy = usedBy, usedByItems = usedByItems, deviceUsers = deviceUsers, unsavedSave = unsavedSave,
        unsavedDefaultSaves = unsavedDefaultSaves)
}

/** The restore dialog's questions for the backup [file]'s keyboards (tests). */
internal fun restorePlanFrom(ctx: Context, file: File): RestorePlan = restorePlan(ctx, readBackup(file))

/** "What to merge" for the backup [keyboard] into the device's [phone]: what differs ([RestoreMatch.differences]), the
 *  key layouts and saved items as the restore brings them in and names them ([plan], [names]; R3). */
private fun restoreDifferences(ctx: Context, plan: RestorePlan, keyboard: SettingsSubtype, phone: SettingsSubtype,
                               withSettings: Boolean, names: SavedItemsMerge.Choices): List<RestoreMatch.Difference> =
    RestoreMatch.differences(phone, plan.incoming.getValue(keyboard), withSettings,
        { plan.phoneSettings(RestoreMatch.phoneSettings(ctx.realPrefs(), phone), names) }, { plan.backupSettings(keyboard, names) }, plan.sameLayout,
        // (an unsaved default of one name with other keys: asked, owner 2026-10-13, 20261013_M1)
        sameDefault = { type, name -> !LayoutUtilsCustom.isPrivateLayout(name) || plan.items.keyLayouts.deviceRename(type, name) == null })

/** [restoreDifferences] for the backup [file] (tests). */
internal fun restoreDifferencesFrom(ctx: Context, file: File, keyboard: SettingsSubtype, phone: SettingsSubtype,
                                    withSettings: Boolean = true, names: SavedItemsMerge.Choices = SavedItemsMerge.Choices()): List<RestoreMatch.Difference> =
    restoreDifferences(ctx, restorePlanFrom(ctx, file), keyboard, phone, withSettings, names)

/** The files behind the settings (restored with all of them): custom layouts (a keyboard's layout must exist for it),
 *  the background pictures, the loaded fonts (FontLibrary) and pictures (PictureLibrary), and the saved themes'
 *  pictures (re-review 2026-10-07: themes came back without them). */
internal fun isSettingsFile(path: String) = path.startsWith("layouts${File.separator}") || path.startsWith("custom_")
    || path.startsWith("fonts${File.separator}") || path.startsWith("pictures${File.separator}")
    || path.startsWith("looks${File.separator}")

/** What app start does to older settings, run right after a restore too, so an older backup's settings are moved now
 *  and not at the next start, over changes made in between (re-review 2026-10-07). */
internal fun restoreFollowUp(ctx: Context) {
    // (a whole restore of a backup from before the keyboards had ids: ids first, owner 2026-10-11)
    helium314.keyboard.latin.settings.KeyboardIdUpgrade.upgrade(ctx)
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
    return pendingOf(file, prefs, entries)
}

/** The backup [file] as read: its preferences [prefs] and the names of its [entries]. */
private fun pendingOf(file: File, prefs: Map<String, Any?>, entries: Set<String>): PendingRestore {
    // a backup from before the keyboards had ids: its keyboards get fresh ones here, their sets found by its own old map
    // (owner 2026-10-11: "don't care about ... past backups at this point", crude)
    val upgraded = HashMap(prefs.filterKeys { it !in notBackedUpKeys }) // (never restored, owner 2026-10-12)
    val renames = helium314.keyboard.latin.settings.KeyboardIdUpgrade.upgrade(upgraded).orEmpty()
    val keyboards = (upgraded[Settings.PREF_ENABLED_SUBTYPES] as? String)?.let { SubtypeSettings.createSettingsSubtypes(it) }.orEmpty()
    return PendingRestore(file, upgraded, keyboards, entries, renames)
}

/** [strict]: a line that is neither a type's heading nor the settings under one throws, and so does a file without any
 *  heading (the full restore's check, owner 2026-10-12: "the settings lines parse"). */
private fun readJsonLinesToMap(list: List<String>, strict: Boolean = false): Map<String, Any?> {
    val map = HashMap<String, Any?>()
    val i = list.iterator()
    var headings = 0
    while (i.hasNext()) {
        val line = i.next()
        val known = when (line) {
            "boolean settings" -> map.putAll(Json.decodeFromString<Map<String, Boolean>>(i.next()))
            "int settings" -> map.putAll(Json.decodeFromString<Map<String, Int>>(i.next()))
            "long settings" -> map.putAll(Json.decodeFromString<Map<String, Long>>(i.next()))
            "float settings" -> map.putAll(Json.decodeFromString<Map<String, Float>>(i.next()))
            "string settings" -> map.putAll(Json.decodeFromString<Map<String, String>>(i.next()))
            "string set settings" -> map.putAll(Json.decodeFromString<Map<String, Set<String>>>(i.next()))
            else -> null
        } != null
        if (known) headings++
        else if (strict && line.isNotBlank()) throw IllegalArgumentException("not a settings line: ${line.take(40)}")
    }
    if (strict && headings == 0) throw IllegalArgumentException("no settings in the settings file")
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
 * languages, each word into its script's). Each word and pair keeps the higher count, the latest last use is kept
 * (mergeHighest, as import and the delete merge; owner 2026-10-11 DM18: "restore word lists shouldn't add the counts,
 * it should take higher yes", so restoring twice changes nothing); blacklists keep the most strikes. A backup store is
 * put into one phone store once, however many chosen keyboards lead there.
 */
private fun restoreLearnedWords(ctx: Context, backup: Map<String, Any?>, keyboards: List<Pair<SettingsSubtype, SettingsSubtype>>,
                                learnedDir: File, io: LearnedStoreIo) {
    val filesDir = ctx.filesDir ?: return
    val real = ctx.realPrefs()
    // (phone pool, script) -> the backup's stores and lists that go there
    val stores = LinkedHashMap<Pair<Int, String>, MutableSet<File>>()
    val lists = LinkedHashMap<Pair<Int, String>, MutableSet<File>>()
    // the pools this restore made (a keyboard kept both, or new): the backup's words alone, nothing of the device's mixed
    // in (owner 2026-10-12: Keep both, "the backup's keyboard gets its own words into an EMPTY pool")
    val fresh = HashSet<Int>()
    // (each keyboard as it was in the backup, and as it is on the phone now)
    for ((keyboard, onPhone) in keyboards) {
        val had = LearnedKeyboards.poolOrNull(real, onPhone) != null
        val pool = LearnedKeyboards.poolFor(real, onPhone) // (a restore brings it: creates, owner 2026-10-11)
        if (!had) fresh += pool
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
        val phone = if (pool in fresh) emptyList<LearnedEntry>() else LearnedStoreFiles.read(io, filesDir, script, pool) ?: continue
        val merged = mergeHighest(listOf(phone) + parts)
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

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.UserDictionary
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
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.database.Database
import helium314.keyboard.latin.gesture.GestureDecoderVocabulary
import helium314.keyboard.latin.personalization.UserHistoryDictionary
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.Settings
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
import java.util.concurrent.CountDownLatch
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
        RestoreChoiceDialog(pending, onDismiss = { pending.file.delete(); pendingRestore = null }, onError = { error = it })
    }
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
        // zip all files matching the backup patterns
        // essentially this is the typed words information, and user-added dictionaries
        val filesDir = ctx.filesDir ?: return@filePicker
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
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                ctx.getActivity()?.contentResolver?.openOutputStream(uri)?.use { os ->
                    // write files to zip
                    val zipStream = ZipOutputStream(os)
                    files.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(filesPath, "")))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    protectedFiles.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(protectedFilesDir.path, "unprotected")))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    val dbFile = ctx.getDatabasePath(Database.NAME)
                    if (dbFile.exists()) {
                        val fileStream = FileInputStream(dbFile).buffered()
                        zipStream.putNextEntry(ZipEntry(Database.NAME))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.realPrefs().all, zipStream) // every keyboard's set, not just the edited one
                    zipStream.closeEntry()
                    zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.protectedPrefs().all, zipStream)
                    zipStream.closeEntry()
                    // fork: include the system personal dictionary (words added via "edit personal dictionary")
                    zipStream.putNextEntry(ZipEntry(PERSONAL_DICT_FILE_NAME))
                    zipStream.write(Json.encodeToString(readPersonalDictionary(ctx)).toByteArray())
                    zipStream.closeEntry()
                    zipStream.close()
                }
            } catch (t: Throwable) {
                onError("b" + t.message)
                Log.w("AdvancedScreen", "error during backup", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
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
)

@Composable
private fun restoreLauncher(onError: (String) -> Unit, onChoose: (PendingRestore) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
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
            runRestore(ctx, onError, R.string.backup_restored) { restoreEverything(ctx, file) }
            file.delete()
        } else onChoose(pending)
    }
}

@Composable
private fun RestoreChoiceDialog(pending: PendingRestore, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val selected = remember(pending) { mutableStateListOf<SettingsSubtype>().apply { addAll(pending.keyboards) } }
    var settings by rememberSaveable { mutableStateOf(true) }
    var learnedWords by rememberSaveable { mutableStateOf(true) }
    var dictionaries by rememberSaveable { mutableStateOf(true) }
    var customWords by rememberSaveable { mutableStateOf(true) }
    var clipboard by rememberSaveable { mutableStateOf(false) }
    val withKeyboards = selected.isNotEmpty() // the "bring with them" rows mean nothing without a keyboard
    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
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
                    CheckRow(keyboardName(keyboard, ctx), keyboard in selected) { if (it) selected.add(keyboard) else selected.remove(keyboard) }
                }
                Heading(R.string.restore_with_keyboards)
                CheckRow(stringResource(R.string.restore_settings), settings, withKeyboards) { settings = it }
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
                dictionaries && pending.hasDictionaries, customWords && pending.hasCustomWords, clipboard && pending.hasClipboard)
            runRestore(ctx, onError, R.string.backup_restored) { restoreChosen(ctx, pending, choice) }
            onDismiss()
        },
    )
}

/** Runs [work] on the keyboard executor, waits for it, then refreshes everything that may have changed. */
private fun runRestore(ctx: Context, onError: (String) -> Unit, doneMessage: Int, work: () -> Unit) {
    val wait = CountDownLatch(1)
    ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
        try {
            work()
            if (Looper.myLooper() == null) Looper.prepare()
            Toast.makeText(ctx, ctx.getString(doneMessage), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            onError("r" + t.message)
            Log.w("AdvancedScreen", "error during restore", t)
        } finally {
            wait.countDown()
        }
    }
    wait.await()
    checkVersionUpgrade(ctx)
    transferOldPinnedClips(ctx)
    Settings.getInstance().startListener()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
    ctx.getActivity()?.sendBroadcast(newDictBroadcast)
    LayoutUtilsCustom.onLayoutFileChanged()
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    SupportedEmojis.load(ctx)
    GestureDecoderVocabulary.clear()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

/** The whole backup replaces everything, as before. */
private fun restoreEverything(ctx: Context, file: File) {
    val restoredDb = ctx.getDatabasePath(Database.NAME + "_restored")
    ZipInputStream(FileInputStream(file)).use { zip ->
        var entry: ZipEntry? = zip.nextEntry
        val filesDir = ctx.filesDir ?: return
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
                val protectedPrefs = ctx.protectedPrefs()
                protectedPrefs.edit { clear() }
                readJsonLinesToSettings(prefLines, protectedPrefs)
            } else if (entry.name == PERSONAL_DICT_FILE_NAME) {
                // fork: merge backed-up personal dictionary into the system one (never fail the whole restore over it)
                try {
                    restorePersonalDictionary(ctx, String(zip.readBytes()))
                } catch (t: Throwable) {
                    Log.w("AdvancedScreen", "error restoring personal dictionary", t)
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    Database.copyFromDb(restoredDb, ctx)
}

/**
 * Brings what [choice] asks for out of the backup and leaves the rest of the phone as it is. With every keyboard
 * of the backup chosen together with the settings, the backup's whole preference set (shared or separate, as it
 * was) replaces the phone's; a subset is restored keyboard by keyboard, see [restoreKeyboards]. Learned words and
 * added dictionaries follow the chosen keyboards' languages.
 */
private fun restoreChosen(ctx: Context, pending: PendingRestore, choice: RestoreChoice) {
    val everyKeyboard = choice.keyboards.toSet() == pending.keyboards.toSet()
    val allSettings = choice.settings && choice.keyboards.isNotEmpty() && everyKeyboard
    if (choice.keyboards.isNotEmpty()) {
        if (allSettings) restoreAllSettings(ctx, pending)
        else restoreKeyboards(ctx, pending, choice.keyboards, choice.settings)
    }
    val tags = choice.keyboards.flatMap { listOf(it.locale) + getSecondaryLocales(it.extraValues) }.map { it.toLanguageTag() }.toSet()
    val filesDir = ctx.filesDir ?: return
    val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    // a language's learned words are replaced as a whole, never mixed with the phone's
    if (choice.learnedWords) for (tag in tags) File(filesDir, "${UserHistoryDictionary.NAME}.$tag.dict").deleteRecursively()
    fun isLearnedWords(path: String) = tags.any { path.startsWith("${UserHistoryDictionary.NAME}.$it.") || path == "blacklists${File.separator}$it.txt" }
    fun isDictionary(path: String) = path.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)
        && tags.any { path.startsWith("dicts${File.separator}$it${File.separator}") }
    // the files behind the settings: custom layouts (a keyboard's layout must exist for it), font and background
    fun isSettingsFile(path: String) = path.startsWith("layouts${File.separator}") || path.startsWith("custom_")
        || path.startsWith("fonts${File.separator}") // the loaded fonts (FontLibrary)
        || path.startsWith("pictures${File.separator}") // the loaded pictures (PictureLibrary)
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
                    name == PROTECTED_PREFS_FILE_NAME && allSettings -> {
                        val protectedPrefs = ctx.protectedPrefs()
                        protectedPrefs.edit { clear() }
                        readJsonLinesToSettings(String(zip.readBytes()).split("\n"), protectedPrefs)
                    }
                    name == PERSONAL_DICT_FILE_NAME && choice.customWords -> try {
                        restorePersonalDictionary(ctx, String(zip.readBytes())) // never fail the whole restore over it
                    } catch (t: Throwable) {
                        Log.w("AdvancedScreen", "error restoring personal dictionary", t)
                    }
                }
                choice.learnedWords && isLearnedWords(path) -> FileUtils.copyStreamToNewFile(zip, target)
                choice.dictionaries && isDictionary(path) -> FileUtils.copyStreamToNewFile(zip, target)
                allSettings && isSettingsFile(path) -> FileUtils.copyStreamToNewFile(zip, target)
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    if (choice.clipboard) Database.copyFromDb(restoredDb, ctx)
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

/** The backup's preferences replace the phone's, every keyboard's set included. */
private fun restoreAllSettings(ctx: Context, pending: PendingRestore) {
    Settings.getInstance().stopListener()
    // the backup's set ids replace the phone's: pictures of the phone's sets would turn up on the backup's keyboards
    KeyboardProfiles.deleteAllFiles()
    Settings.clearCachedBackgroundImages()
    ctx.realPrefs().edit {
        clear()
        for ((key, value) in pending.prefs) KeyboardProfiles.put(this, key, value)
    }
    KeyboardProfiles.editingId = KeyboardProfiles.SHARED
}

/**
 * Only [chosen] keyboards come out of the backup: each is added (or replaced) with its custom layout files and,
 * [withSettings], its settings and the priority / share switches of its languages. Other keyboards stay as they
 * are. The keyboard's settings need a set of its own, so separate settings per keyboard get switched on if they
 * aren't; the existing keyboards keep the shared set they behave by now.
 */
private fun restoreKeyboards(ctx: Context, pending: PendingRestore, chosen: List<SettingsSubtype>, withSettings: Boolean) {
    val real = ctx.realPrefs()
    val prefs = ctx.prefs()
    val backup = pending.prefs
    // its own set when the backup kept one, else the backup's shared set was what it used
    val settings = chosen.associateWith { KeyboardProfiles.ownSettingsIn(backup, it) ?: KeyboardProfiles.sharedSettingsIn(backup) }
    if (withSettings && !KeyboardProfiles.isSeparate(real))
        KeyboardProfiles.enable(real, SubtypeSettings.getEnabledSubtypes().map { it.toSettingsSubtype() }, keepExisting = true)

    // custom layout files the chosen keyboards use, by their path inside the backup
    val wanted = chosen.flatMap { keyboard ->
        LayoutType.entries.mapNotNull { type ->
            keyboard.layoutName(type)?.takeIf { it.startsWith(LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX) }?.let { type.folder + File.separator + it }
        }
    }.toSet()
    if (wanted.isNotEmpty()) {
        val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
        ZipInputStream(FileInputStream(pending.file)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val adjustedName = entry.name.substringAfter("unprotected${File.separator}", "")
                if (adjustedName in wanted) FileUtils.copyStreamToNewFile(zip, File(deviceProtectedFilesDir, adjustedName))
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        LayoutUtilsCustom.onLayoutFileChanged()
    }

    if (withSettings) {
        // the languages' priority and share switches are per language, not per keyboard
        val editor = real.edit()
        for (keyboard in chosen)
            for (locale in listOf(keyboard.locale) + getSecondaryLocales(keyboard.extraValues))
                for (key in LanguagePriority.keys(locale)) backup[key]?.let { KeyboardProfiles.put(editor, key, it) }
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
                    (name.startsWith("fonts${File.separator}") || name.startsWith("pictures${File.separator}"))
                        && backupFilePatterns.any { name.matches(it) } -> {
                        val target = File(deviceProtectedFilesDir, name)
                        // (and never outside the app's folder, whatever the zip says)
                        val inside = target.canonicalPath.startsWith(deviceProtectedFilesDir.canonicalPath + File.separator)
                        if (inside && !target.exists()) FileUtils.copyStreamToNewFile(zip, target)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    for (keyboard in chosen) {
        SubtypeUtilsAdditional.changeAdditionalSubtype(keyboard, keyboard, ctx) // registers it unless it equals a built-in one
        if (SubtypeSettings.getEnabledSubtypes().none { it.toSettingsSubtype() == keyboard })
            SubtypeSettings.addEnabledSubtype(prefs, keyboard.toAdditionalSubtype())
        if (withSettings) {
            val id = KeyboardProfiles.idFor(real, keyboard)
            KeyboardProfiles.write(real, id, settings.getValue(keyboard))
            // its own pictures in the backup, or the backup's shared ones when it had no set of its own
            KeyboardProfiles.restoreFiles(pictureFiles, KeyboardProfiles.idIn(backup, keyboard) ?: KeyboardProfiles.SHARED, id,
                perKeyboardBackup = backup["profile_files_migrated"] == true)
        }
    }
    if (withSettings) { Settings.clearCachedBackgroundImages(); helium314.keyboard.keyboard.KeyboardTypeface.clearCache() }
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

private fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
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

private val backupFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "dicts${File.separator}.*${File.separator}.*user\\.dict".toRegex(),
    "UserHistoryDictionary.*${File.separator}UserHistoryDictionary.*\\.(body|header)".toRegex(),
    "custom_background_image.*".toRegex(),
    "pictures${File.separator}[^${File.separator}]+".toRegex(), // the picture list shared by all keyboards
    "custom_font".toRegex(), // the text style fonts of before; restored ones move into the list (FontLibrary)
    "fonts${File.separator}[^${File.separator}]+".toRegex(),
    "custom_emoji_font.*".toRegex(), // (one per keyboard with separate settings: custom_emoji_font_p<id>)
    "custom_hint_font".toRegex(),
    "custom_suggestion_font".toRegex(),
) }

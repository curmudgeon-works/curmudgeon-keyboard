// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.view.MotionEvent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import helium314.keyboard.compat.locale
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.SettingsEventLog
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.cleanUnusedMainDicts
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.NewDictionaryDialog
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// todo: with compose, app startup is slower and UI needs some "warmup" time to be snappy
//  maybe baseline profiles help?
//  https://developer.android.com/codelabs/android-baseline-profiles-improve
//  https://developer.android.com/codelabs/jetpack-compose-performance#2
//  https://developer.android.com/topic/performance/baselineprofiles/overview
// todo: consider viewModel, at least for LanguageScreen and ColorsScreen it might help making them less awkward and complicated
open class SettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    // the whole file: a keyboard's screens write its own set (p<id>/ keys), which the main view's listener doesn't hear
    private val prefs by lazy { DeviceProtectedUtils.getRealSharedPreferences(this) }
    val prefChanged = MutableStateFlow(0) // simple counter, as the only relevant information is that something changed
    fun prefChanged() = prefChanged.value++
    /** Counts the times the settings were asked back to the main screen (a factory reset, a restore: see SettingsNavHost). */
    val backToMain = MutableStateFlow(0)
    fun backToMain() = backToMain.value++
    private val dictUriFlow = MutableStateFlow<Uri?>(null)
    private val cachedDictionaryFile by lazy { File(this.cacheDir.path + File.separator + "temp_dict") }
    private val crashReportFiles = MutableStateFlow<List<File>>(emptyList())
    // whether this keyboard is the one selected in Android: checked again whenever the app comes back or regains the
    // focus (the keyboard picker closing), so the "not your current keyboard" bar follows a switch
    private val imeSelected = MutableStateFlow(true)
    private fun refreshImeSelected() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imeSelected.value = UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshImeSelected()
    }
    private var paused = true

    /** Set while a dialog that keeps the preview keyboard is open: a tap on this screen closes it (and does nothing else). */
    var outsideTapHandler: (() -> Unit)? = null

    /** Window y from which touches still work normally while such a dialog is open (the try-it bar above the keyboard). */
    var touchPassFromY = Int.MAX_VALUE
    private var passingGesture = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) lastTouchDown = android.os.SystemClock.uptimeMillis()
        val handler = outsideTapHandler ?: return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) passingGesture = ev.y >= touchPassFromY
        if (passingGesture) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) handler()
        return true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.getValues() == null) {
            val inputAttributes = InputAttributes(EditorInfo(), false, packageName)
            Settings.getInstance().loadSettings(this, resources.configuration.locale(), inputAttributes)
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUnusedMainDicts(this) }
        crashReportFiles.value = findCrashReports(!BuildConfig.DEBUG && !DebugFlags.DEBUG_ENABLED)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (!UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm))
            KeyboardIconsSet.instance.loadIcons(this) // otherwise we may crash when displaying toolbar keys

        settingsContainer = SettingsContainer(this)

        val spellchecker = intent?.getBooleanExtra("spellchecker", false) ?: false

        val cv = ComposeView(context = this)
        setContentView(cv)
        cv.setContent {
            // the app's look is the keyboard of the screen on top (its route's set; none: the main choice), read again
            // once per navigation, not on every frame (2026-10-09: a listener on a global the screens set redrew all of
            // the settings on every frame, 62481bb1f / ab8bc3b5e)
            val navController = rememberNavController()
            val top by navController.currentBackStackEntryAsState()
            Theme(keyboardId = SettingsDestination.setIdOf(top)) {
                Surface {
                    val dictUri by dictUriFlow.collectAsState()
                    val crashReports by crashReportFiles.collectAsState()
                    val crashFilePicker = filePicker { saveCrashReports(it) }
                    // the setup only on a real first run (the keyboard not enabled in Android); enabled but another
                    // keyboard selected: the settings, with a bar to switch (see below)
                    var showWelcomeWizard by rememberSaveable { mutableStateOf(
                        !UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm)
                    ) }
                    // the settings opened from the setup's All settings tile: Back from their first screen returns there
                    var setupBehindSettings by rememberSaveable { mutableStateOf(false) }
                    val selected by imeSelected.collectAsState()
                    // the screens' try-it field and preview keyboard follow it (TryItBar)
                    androidx.compose.runtime.CompositionLocalProvider(LocalImeSelected provides selected) {
                    if (spellchecker)
                        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
                            Column(Modifier.padding(innerPadding)) {
                                TopAppBar(
                                    title = { Text(stringResource(R.string.android_spell_checker_settings)) },
                                    windowInsets = WindowInsets(0),
                                    navigationIcon = {
                                        BackButton { this@SettingsActivity.finish() }
                                    },
                                )
                                settingsContainer[Settings.PREF_USE_CONTACTS]!!.Preference()
                                settingsContainer[Settings.PREF_USE_APPS]!!.Preference()
                                settingsContainer[Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE]!!.Preference()
                            }
                        }
                    else {
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                            if (!selected && !showWelcomeWizard) NotSelectedBar { imm.showInputMethodPicker() }
                            // (min constraints passed on, as the Surface did before this Box: a screen's own overlay, like
                            // the personal dictionary's Add a word button, aligns to the screen's corner, not its top start)
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)
                                .then(if (!selected && !showWelcomeWizard) androidx.compose.ui.Modifier.consumeWindowInsets(
                                    androidx.compose.foundation.layout.WindowInsets.statusBars) else androidx.compose.ui.Modifier),
                                propagateMinConstraints = true) {
                                // Back out of the settings' first screen: the setup again when they were opened from
                                // its All settings tile (owner 2026-10-25: "going back doesn't bring us back to the
                                // setup -- I want this to happen"); otherwise the activity closes
                                SettingsNavHost(onClickBack = {
                                    if (setupBehindSettings) { setupBehindSettings = false; showWelcomeWizard = true }
                                    else this@SettingsActivity.finish()
                                }, navController = navController)
                            }
                        }
                        if (showWelcomeWizard) {
                            WelcomeWizard(close = { showWelcomeWizard = false; setupBehindSettings = true }, finish = this::finish)
                        } else if (crashReports.isNotEmpty()) {
                            ConfirmationDialog(
                                cancelButtonText = "ignore",
                                onDismissRequest = { crashReportFiles.value = emptyList() },
                                neutralButtonText = "delete",
                                onNeutral = { crashReports.forEach { it.delete() }; crashReportFiles.value = emptyList() },
                                confirmButtonText = "get",
                                onConfirmed = {
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                                    intent.putExtra(Intent.EXTRA_TITLE, "crash_reports.zip")
                                    intent.type = "application/zip"
                                    crashFilePicker.launch(intent)
                                },
                                content = { Text("Crash report files found") },
                            )
                        }
                    }
                    }
                    // what a restore has to say, once it's done (owner 2026-10-12: a dialog, not a toast)
                    helium314.keyboard.settings.preferences.AfterRestoreNote()
                    if (dictUri != null) {
                        NewDictionaryDialog(
                            onDismissRequest = { dictUriFlow.value = null },
                            cachedFile = cachedDictionaryFile,
                            mainLocale = null
                        )
                    }
                }
            }
        }

        if (intent?.action == Intent.ACTION_VIEW) {
            intent?.data?.let {
                cachedDictionaryFile.delete()
                FileUtils.copyContentUriToNewFile(it, this, cachedDictionaryFile)
                dictUriFlow.value = it
            }
            intent = null
        }

        enableEdgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(this)
        editsLeftOpen = false // (back in front: the edits go on)
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        // leaving the app with Appearance open undoes its changes that weren't kept (or keeps them, see
        // leaveOpenEdits); a file picker we opened (background image, font) and rotating don't count
        if (!isChangingConfigurations) {
            if (awaitingResult) editsLeftOpen = true
            else leaveOpenEdits(if (isFinishing) REASON_CLOSED else REASON_LEFT)
        }
        super.onStop()
    }

    override fun onDestroy() {
        // the window closed counts as leaving too, also one stopped while a file picker it opened was in front (owner
        // 2026-10-14, the settings key's fix A: it closes an older window; that one's drafts stayed running, and the next
        // screen took them over, Layout & typing even for another keyboard). Only when its onStop left them running: a
        // window already left has none of its own, and a draft running now is another window's
        if (!isChangingConfigurations && editsLeftOpen) { editsLeftOpen = false; leaveOpenEdits(REASON_CLOSED) }
        OpenEditors.forget(this)
        super.onDestroy()
    }

    /** The settings are left with a Save/Discard edit open: an editor opened from a main screen undoes its unsaved
     *  changes; the main screens' changes are kept with "Leaving a screen keeps your changes" on (owner 2026-10-14),
     *  else undone. [reason]: the window closed or the app left, for the log. */
    private fun leaveOpenEdits(reason: String) {
        OpenEditors.undoUnsaved(reason) // (first: a main screen's undo or keep then covers what the editor put back)
        val keep = leavingKeepsChanges(this)
        // each open edit with changes, before they are kept or undone (owner 2026-10-14: "add some logging to see if
        // unsaved edits are happening a lot and/or creating problems"); written only with "Log settings changes" on
        for (edit in listOfNotNull(AppearanceDraft.pendingOpen(this), LayoutDraft.pendingOpen(this)) + PrefsDraft.pendingOpen(this))
            SettingsEventLog.log("Unsaved changes ${if (keep) "kept" else "undone"} on leaving ($reason): $edit", withCaller = false)
        if (keep) { AppearanceDraft.acceptOpen(this); LayoutDraft.acceptOpen(); PrefsDraft.acceptOpen() }
        else { AppearanceDraft.rejectOpen(this); LayoutDraft.rejectOpen(this); PrefsDraft.rejectOpen(this) }
    }

    // an activity we started for a result (file pickers) is in front: this isn't leaving the app
    private var awaitingResult = false
    // the window was stopped with a file picker in front, its open edits left running ([onDestroy])
    private var editsLeftOpen = false

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        awaitingResult = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onPause() {
        super.onPause()
        setForceTheme(null, null)
        paused = true
    }

    override fun onResume() {
        super.onResume()
        awaitingResult = false
        paused = false
        refreshImeSelected()
    }

    fun setForceTheme(theme: String?, night: Boolean?) {
        if (paused) return
        if (forceTheme == theme && forceNight == night)
            return
        forceTheme = theme
        forceNight = night
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    private fun findCrashReports(onlyUnprotected: Boolean): List<File> {
        val unprotected = DeviceProtectedUtils.getFilesDir(this)?.listFiles().orEmpty()
        if (onlyUnprotected)
            return unprotected.filter { it.name.startsWith("crash_report") }

        val dir = getExternalFilesDir(null)
        val allFiles = dir?.listFiles()?.toList().orEmpty() + unprotected
        return allFiles.filter { it.name.startsWith("crash_report") }
    }

    private fun saveCrashReports(uri: Uri) {
        val files = findCrashReports(false)
        if (files.isEmpty()) return
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                val bos = BufferedOutputStream(it)
                val z = ZipOutputStream(bos)
                for (file in files) {
                    val f = FileInputStream(file)
                    z.putNextEntry(ZipEntry(file.name))
                    FileUtils.copyStreamToOtherStream(f, z)
                    f.close()
                    z.closeEntry()
                }
                z.close()
                bos.close()
                for (file in files) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        // why the open edits were left ([leaveOpenEdits]), for the log
        private const val REASON_CLOSED = "window closed"
        private const val REASON_LEFT = "left the app"

        /** The settings opened from outside them (the keyboard's settings key, the spell checker's settings): one
         *  window, on its main screen. An older settings window in the task is closed (owner 2026-10-14: "I updated swipe
         *  trail and had to press back a bunch to get to main settings screen": the new one landed on top of it, and Back
         *  walked through the older window's screens); closing it counts as leaving it ([onDestroy]). */
        @JvmStatic fun openFromOutside(context: Context): Intent = Intent(context, SettingsActivity2::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** When the screen (not a dialog) was last touched, uptime ms: the preview keyboard stays quiet until then. */
        @Volatile var lastTouchDown = 0L
        // public write so compose previews can show the screens
        // having it in a companion object is not ideal as it will stay in memory even after settings are closed
        // but it's small enough to not care
        lateinit var settingsContainer: SettingsContainer

        var forceNight: Boolean? = null
        var forceTheme: String? = null
    }

    override fun onSharedPreferenceChanged(prefereces: SharedPreferences?, key: String?) {
        if (key == null || key == Settings.PREF_ADVANCED_SETTINGS) SettingsMode.sync(this) // (null: the file was cleared)
        prefChanged()
    }
}

/** Whether this keyboard is the one selected in Android (the activity's check, again whenever the window regains the
 *  focus): while it isn't, the try-it field brings up no keyboard and offers the switch instead (owner 2026-10-14: "Can we
 *  show this message, but also prevent preview from coming up?"). True where no activity provides it. */
val LocalImeSelected = androidx.compose.runtime.compositionLocalOf { true }

// duplicate of SettingsActivity so we can launch it when the app icon is disabled in Android 9 and older
class SettingsActivity2 : SettingsActivity()

/** On top of every settings screen while another keyboard is selected in Android: says so, Switch opens the picker. */
@androidx.compose.runtime.Composable
private fun NotSelectedBar(onSwitch: () -> Unit) {
    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.fillMaxWidth()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.not_current_keyboard), androidx.compose.ui.Modifier.weight(1f),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer)
            androidx.compose.material3.TextButton(onClick = onSwitch) { Text(stringResource(R.string.switch_to_this_keyboard)) }
        }
    }
}

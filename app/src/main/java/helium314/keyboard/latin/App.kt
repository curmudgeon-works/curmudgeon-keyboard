// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import android.os.Build
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.gesture.GestureCorpusRecorder
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // first, before anything opens learned words or blacklists: the per-language files of before become per-script
        // ones, in the background (the stores wait for it before they read their files)
        helium314.keyboard.latin.personalization.LearnedStoreMigration.startIfNeeded(this)
        DebugFlags.init(this)
        FoldableUtils.init(this)
        Settings.init(this)
        helium314.keyboard.latin.utils.SettingsEventLog.init(this) // first: the crash recoveries below are what it watches
        // the keyboard list first: the Layout & Typing recovery below reloads it, and the other way round the list
        // was loaded twice and showed every keyboard twice until the next start (the "second keyboard", 2026-10-01)
        SubtypeSettings.init(this)
        // the background pictures are per keyboard with separate settings: where they are, and a copy for the keyboards
        // that had their own set before that (the emoji font is one shared file in the font list, see FontLibrary: each
        // keyboard only stores its choice)
        helium314.keyboard.latin.settings.KeyboardProfiles.filesDir = helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(this)
        // once (2026-10-10): every keyboard its own learned words, from the shared ones if they were shared (after
        // unlock, in the background; see LearnedKeyboards)
        helium314.keyboard.latin.personalization.LearnedKeyboards.startUpdate(this)
        // the learned words the keyboard in use reads follow the keyboard list and its buttons "Use lists from other keyboards"
        helium314.keyboard.latin.personalization.LearnedStores.init(this)
        helium314.keyboard.latin.settings.KeyboardProfiles.loadGroups(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(this))
        helium314.keyboard.latin.settings.KeyboardProfiles.migrateFiles(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(this))
        helium314.keyboard.latin.settings.KeyboardProfiles.settingsMoves(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(this))
        // every picture loaded so far joins the picture list all keyboards choose from
        helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(this).let { real ->
            helium314.keyboard.latin.common.PictureLibrary.migrate(this, real.getBoolean("picture_library_migrated", false)) {
                real.edit().putBoolean("picture_library_migrated", true).apply() }
        }
        // the process died with Appearance open (a crash): its changes that weren't kept are undone
        helium314.keyboard.settings.AppearanceDraft.recoverAfterCrash(this)
        helium314.keyboard.settings.LayoutDraft.recoverAfterCrash(this)
        helium314.keyboard.settings.PrefsDraft.recoverAfterCrash(this) // Swipe, Text correction
        helium314.keyboard.latin.utils.SettingsEventLog.log("keyboards in the list at start: " +
            SubtypeSettings.getEnabledSubtypes().joinToString { it.locale }, withCaller = false)
        GestureCorpusRecorder.init(this)
        helium314.keyboard.latin.gesture.SwipeMetrics.init(this)
        helium314.keyboard.latin.personalization.LearningEventLog.init(this)

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { // do some uncritical work in background for faster startup
            SupportedEmojis.load(this@App)
            // an extracted dictionary of a language no longer built in (Hindi was, 2026-10-10 to -11) becomes the user's own
            // copy: the update itself may not clear extracted dictionaries, and only own copies count as a dictionary
            helium314.keyboard.latin.utils.DictionaryInfoUtils.getCacheDirectories(this@App).forEach { dir ->
                dir.listFiles()?.forEach { runCatching { helium314.keyboard.latin.keepUnbundledMainDict(this@App, dir, it) } }
            }
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            // (can't fail for the app itself on a phone; in unit tests this job outlives its test, and the next test's
            // package manager doesn't know the package: the exception landed in that test as a flaky failure)
            val packageInfo = try { packageManager.getPackageInfo(packageName, 0) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { return@launch }
            @Suppress("DEPRECATION")
            Log.i(
                "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                    packageInfo.versionCode
                }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
        }

        RichInputMethodManager.init(this)
        checkVersionUpgrade(this)
        if (BuildConfig.DEBUG) // do this on every debug apk start because we may work on adding a new toolbar key
            upgradeToolbarPrefs(prefs())
        transferOldPinnedClips(this) // todo: remove in a few months, maybe end 2026
        app = this
        Defaults.initDynamicDefaults(this)
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}

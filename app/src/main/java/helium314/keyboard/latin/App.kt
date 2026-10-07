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
        // the background pictures and emoji font are per keyboard with separate settings: where they are, and a copy for
        // the keyboards that had their own set before that
        helium314.keyboard.latin.settings.KeyboardProfiles.filesDir = helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(this)
        helium314.keyboard.latin.settings.KeyboardProfiles.editingStore = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(this)
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
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
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

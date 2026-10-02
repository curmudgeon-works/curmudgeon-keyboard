// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A local trail of the settings changes that have surprised us: the keyboard list, "separate settings per keyboard",
 * and the undo snapshots of Appearance / Layout & Typing being put back. One line per event in `settings_events.log`
 * (the app's external files folder, next to the swipe results), with the time, what happened, and the code that
 * asked for it, so a keyboard that appears or a switch that flips can be traced afterwards. Written only while
 * "Log swipe results" is on (the same diagnostics switch); kept under [MAX_BYTES]. Nothing typed is in it.
 */
object SettingsEventLog {
    private const val FILE_NAME = "settings_events.log"
    private const val MAX_BYTES = 256 * 1024L
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-events").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    @Volatile private var prefs: SharedPreferences? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    // the preferences whose every change is logged, whoever writes them
    private val watched = setOf(Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE,
        "separate_settings_per_keyboard", "keyboard_profile_ids")
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key in watched) log("pref $key = ${p.all[key]}", withCaller = false)
    }

    /** Before anything at app start that can change settings (the drafts' crash recovery). */
    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
        prefs = context.realPrefs().also { it.registerOnSharedPreferenceChangeListener(listener) }
        log("app start (process ${android.os.Process.myPid()})", withCaller = false)
    }

    private fun enabled() = prefs?.getBoolean(Settings.PREF_SWIPE_METRICS, Defaults.PREF_SWIPE_METRICS) == true

    /** [event] plus, with [withCaller], the few app frames that led here (the trigger). */
    fun log(event: String, withCaller: Boolean = true) {
        if (!enabled()) return
        val line = buildString {
            append(time.format(Date())).append("  ").append(event)
            if (withCaller) {
                val frames = Throwable().stackTrace.drop(1)
                    .filter { it.className.startsWith("helium314.") && !it.className.contains("SettingsEventLog") }
                    .take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                append("\n    from ").append(frames)
            }
            append('\n')
        }
        executor.execute {
            try {
                val f = file ?: return@execute
                if (f.length() > MAX_BYTES) { // keep the newer half
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
                f.appendText(line)
            } catch (_: Exception) { }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype
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
 * "Log settings changes" is on (its own switch, on by default; before 2026-10-12 "Log swipe results"); kept under
 * [MAX_BYTES]. Nothing typed is in it.
 * The keyboard history (owner 2026-10-12 K1): one line per action where it is done ([keyboard]: created, copied,
 * restored, switched off, switched on, deleted, renamed), the visible name first, the id and definition after; and a
 * line when a keyboard leaves the list with its records (settings set, learned-words pool) left behind, how the
 * switched-off "stragglers" form ([checkLeft]).
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
        if (key == Settings.PREF_ENABLED_SUBTYPES) checkLeft(p)
    }
    @Volatile private var app: Context? = null
    // the ids in the list at the last look: one gone from it since may have left its records behind
    @Volatile private var listedIds: Set<String> = emptySet()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Before anything at app start that can change settings (the drafts' crash recovery). */
    fun init(context: Context) {
        app = context.applicationContext ?: context
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
        prefs = context.realPrefs().also { it.registerOnSharedPreferenceChangeListener(listener); listedIds = idsListed(it) }
        log("app start (process ${android.os.Process.myPid()})", withCaller = false)
    }

    private fun enabled() = prefs?.getBoolean(Settings.PREF_LOG_SETTINGS, Defaults.PREF_LOG_SETTINGS) == true

    /** Keyboard [keyboard]'s [action] (created, copied, restored, switched off, switched on, deleted, renamed), one line
     *  with the caller: "keyboard deleted: English (US) 2 (k3; <definition>)", [detail] after it (owner 2026-10-12 K1). */
    fun keyboard(context: Context, action: String, keyboard: SettingsSubtype, detail: String? = null) {
        if (!enabled()) return
        log("keyboard $action: ${describe(context, keyboard)}" + (detail?.let { ", $it" } ?: ""))
    }

    /** The visible name, then the id and the definition. */
    fun describe(context: Context, keyboard: SettingsSubtype): String {
        val name = try { helium314.keyboard.settings.screens.keyboardName(keyboard, context) } catch (e: Exception) { "?" }
        return "$name (${keyboard.keyboardId ?: "no id"}; ${keyboard.toPref()})"
    }

    // (a list that can't be read is no keyboard: the log never breaks the write it watches)
    private fun idsListed(p: SharedPreferences): Set<String> = try {
        SubtypeSettings.createSettingsSubtypes(p.getString(Settings.PREF_ENABLED_SUBTYPES, null) ?: Defaults.PREF_ENABLED_SUBTYPES)
            .mapNotNullTo(HashSet()) { it.keyboardId }
    } catch (e: Exception) { emptySet() }

    /** The list changed: a keyboard gone from it whose settings set or learned-words pool is still there once the action
     *  that took it out is done (looked at after it, on the main thread: a delete takes them right after the list) gets
     *  a line, "keyboard left the list, records kept". */
    private fun checkLeft(p: SharedPreferences) {
        val now = idsListed(p)
        val gone = listedIds - now
        listedIds = now
        if (gone.isEmpty() || !enabled()) return
        main.post { try {
            val all = p.all
            val stillListed = idsListed(p)
            val sets = helium314.keyboard.latin.settings.KeyboardProfiles.setIds(all)
            val pools = helium314.keyboard.latin.personalization.LearnedKeyboards.poolEntries(all)
            val known = helium314.keyboard.latin.settings.KeyboardIds.phoneKeyboards(p)
            for (id in gone) {
                if (id in stillListed) continue
                val records = listOfNotNull(if (id in sets) "settings set" else null, pools[id]?.let { "learned words pool $it" })
                if (records.isEmpty()) continue
                val keyboard = known.firstOrNull { it.keyboardId == id }
                val who = if (keyboard == null) "$id (no definition)" else app?.let { describe(it, keyboard) } ?: keyboard.toPref()
                log("keyboard left the list, records kept (${records.joinToString(", ")}): $who", withCaller = false)
            }
        } catch (_: Exception) { } }
    }

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

    /** Tests: the lines asked for so far are written. */
    @androidx.annotation.VisibleForTesting
    internal fun flushForTest() { executor.submit { }.get() }
}

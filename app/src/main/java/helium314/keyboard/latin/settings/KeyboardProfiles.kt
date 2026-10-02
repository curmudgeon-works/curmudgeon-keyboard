// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import helium314.keyboard.latin.utils.getActivity
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.LanguagePriority
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject

/**
 * Separate settings per keyboard. Every keyboard (subtype) has a stable profile id; with the feature on, a
 * keyboard's settings live under keys "p<id>/<key>" in the same preferences file, while the plain keys hold
 * the shared set used when the feature is off. Sets are never deleted by switching, only by deleting a keyboard.
 *
 * Which profile a read or write goes to is decided by [ProfilePreferences]: the keyboard in use for the IME,
 * the keyboard being edited for the settings screens.
 */
object KeyboardProfiles {
    const val SHARED = 0 // profile id of the plain keys

    private const val PREF_SEPARATE = "separate_settings_per_keyboard"
    private const val PREF_IDS = "keyboard_profile_ids" // json: subtype pref string -> id
    private const val PREF_NEXT_ID = "keyboard_profile_next_id"
    private const val PREFIX = "p"
    private const val SEPARATOR = "/"

    /** Keys that are never per keyboard: the keyboards themselves, app-level state, data. */
    private val globalKeys = setOf(
        Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE,
        Settings.PREF_ADVANCED_SETTINGS, Settings.PREF_VERSION_CODE,
        Settings.PREF_SAVE_SUBTYPE_PER_APP, // it picks which keyboard comes up in an app: one switch for all of them
        Settings.PREF_SHOW_SETUP_WIZARD_ICON, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        Settings.PREF_CLIPBOARD_HISTORY_SIZE, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST,
        Settings.PREF_RECORD_GESTURE_CORPUS, Settings.PREF_SWIPE_METRICS, Settings.PREF_AUTO_PREVIEW_KEYBOARD, // logs of the user's swiping: one file, one switch
        PREF_SEPARATE, PREF_IDS, PREF_NEXT_ID,
        "key_popup_sets", // saved popup sets are meant to be reused across keyboards
        "appearance_looks", // saved looks too
    )
    private val globalPrefixes = listOf(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, "language_priority_", "share_user_history_", LanguagePriority.PREF_ADDED_PREFIX, "debug_", "gesture_stats")

    fun isGlobal(key: String) = key in globalKeys || globalPrefixes.any { key.startsWith(it) } || key.startsWith(PREFIX) && key.contains(SEPARATOR)

    fun isSeparate(real: SharedPreferences) = real.getBoolean(PREF_SEPARATE, false)

    fun prefixedKey(id: Int, key: String) = if (id == SHARED || isGlobal(key)) key else "$PREFIX$id$SEPARATOR$key"

    /** The key as the app knows it, or null if [key] belongs to another profile than [id]. */
    fun unprefixedKey(id: Int, key: String): String? {
        if (!key.startsWith(PREFIX)) return key
        val sep = key.indexOf(SEPARATOR)
        if (sep < 0) return key
        val keyId = key.substring(PREFIX.length, sep).toIntOrNull() ?: return key
        return if (keyId == id) key.substring(sep + 1) else null
    }

    // ---- ids ----

    private fun ids(real: SharedPreferences): JSONObject =
        try { JSONObject(real.getString(PREF_IDS, "{}")!!) } catch (e: Exception) { JSONObject() }

    /** Profile id of a keyboard, created on first use. */
    @Synchronized
    fun idFor(real: SharedPreferences, keyboard: SettingsSubtype): Int {
        val map = ids(real)
        val pref = keyboard.toPref()
        if (map.has(pref)) return map.getInt(pref)
        val id = real.getInt(PREF_NEXT_ID, 1)
        map.put(pref, id)
        real.edit().putString(PREF_IDS, map.toString()).putInt(PREF_NEXT_ID, id + 1).apply()
        return id
    }

    /** A keyboard changed identity (layout, languages ...): its settings follow it. */
    @Synchronized
    fun onKeyboardChanged(real: SharedPreferences, from: SettingsSubtype, to: SettingsSubtype) {
        if (from == to) return
        val map = ids(real)
        val fromPref = from.toPref()
        if (!map.has(fromPref)) return
        val id = map.getInt(fromPref)
        map.remove(fromPref)
        map.put(to.toPref(), id)
        real.edit().putString(PREF_IDS, map.toString()).apply()
    }

    /** A keyboard was deleted: its set goes with it. */
    @Synchronized
    fun onKeyboardDeleted(real: SharedPreferences, keyboard: SettingsSubtype) {
        val map = ids(real)
        val pref = keyboard.toPref()
        if (!map.has(pref)) return
        val id = map.getInt(pref)
        map.remove(pref)
        val editor = real.edit().putString(PREF_IDS, map.toString())
        real.all.keys.filter { unprefixedKey(id, it) != null && it.startsWith("$PREFIX$id$SEPARATOR") }.forEach { editor.remove(it) }
        editor.apply()
        deleteFiles(id) // its own pictures: no keyboard uses them any more
    }

    fun hasOwnSettings(real: SharedPreferences, keyboard: SettingsSubtype): Boolean {
        val map = ids(real)
        val pref = keyboard.toPref()
        if (!map.has(pref)) return false
        val start = "$PREFIX${map.getInt(pref)}$SEPARATOR"
        return real.all.keys.any { it.startsWith(start) }
    }

    // ---- copying sets ----

    /** Copy the settings of profile [fromId] over those of profile [toId] (the shared set is id 0). */
    @Synchronized
    fun copy(real: SharedPreferences, fromId: Int, toId: Int) {
        if (fromId == toId) return
        val editor = real.edit()
        // clear the target first so nothing stale survives
        real.all.keys.filter { if (toId == SHARED) !isGlobal(it) else it.startsWith("$PREFIX$toId$SEPARATOR") }.forEach { editor.remove(it) }
        // what the source set reads as: the shared values, then its own on top (a profile's reads fall back to the shared
        // set); before, both were copied in the file's order, so a key in both came out as either at random
        val values = HashMap<String, Any?>()
        for ((key, value) in real.all) {
            if (key.startsWith(PREFIX) && key.contains(SEPARATOR)) continue // another set's
            if (!isGlobal(key)) values[key] = value
        }
        if (fromId != SHARED) for ((key, value) in real.all) {
            val plain = unprefixedKey(fromId, key)?.takeIf { it != key } ?: continue
            if (plain.startsWith(TOMBSTONE)) values.remove(plain.removePrefix(TOMBSTONE)) // at its default in the source
            if (!isGlobal(plain)) values[plain] = value
        }
        for ((plain, value) in values) {
            // a "default" mark only means something in a keyboard's own set
            if (toId == SHARED && plain.startsWith(TOMBSTONE)) continue
            put(editor, prefixedKey(toId, plain), value)
        }
        editor.apply()
        copyFiles(fromId, toId)
        Log.i("KeyboardProfiles", "copied settings $fromId -> $toId")
    }

    /** Write [settings] (plain keys) as the own set of profile [id], replacing what was there. */
    @Synchronized
    fun write(real: SharedPreferences, id: Int, settings: Map<String, Any?>) {
        val editor = real.edit()
        real.all.keys.filter { it.startsWith("$PREFIX$id$SEPARATOR") }.forEach { editor.remove(it) }
        for ((key, value) in settings) put(editor, prefixedKey(id, key), value)
        editor.apply()
    }

    fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value as Set<String>)
        }
    }

    // ---- reading a backup's preference map (keys as stored, i.e. prefixed) ----

    /** [keyboard]'s own set inside a backed-up preference map, plain keys; null when the backup kept no separate set for it. */
    fun ownSettingsIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Map<String, Any?>? {
        if (backup[PREF_SEPARATE] != true) return null
        val map = try { JSONObject(backup[PREF_IDS] as? String ?: "{}") } catch (e: Exception) { JSONObject() }
        val pref = keyboard.toPref()
        if (!map.has(pref)) return null
        val id = map.getInt(pref)
        val start = "$PREFIX$id$SEPARATOR"
        return backup.filterKeys { it.startsWith(start) }.mapKeys { it.key.substring(start.length) }.filterKeys { !isGlobal(it) }
    }

    /** [keyboard]'s set id inside a backed-up preference map, or null when the backup kept no separate set for it. */
    fun idIn(backup: Map<String, Any?>, keyboard: SettingsSubtype): Int? {
        if (backup[PREF_SEPARATE] != true) return null
        val map = try { JSONObject(backup[PREF_IDS] as? String ?: "{}") } catch (e: Exception) { JSONObject() }
        return keyboard.toPref().takeIf { map.has(it) }?.let { map.getInt(it) }
    }

    /** The shared set of a backed-up preference map: plain, non-global keys. */
    fun sharedSettingsIn(backup: Map<String, Any?>): Map<String, Any?> =
        backup.filterKeys { !isGlobal(it) && !(it.startsWith(PREFIX) && it.contains(SEPARATOR)) }

    /**
     * Turn separate settings on: every enabled keyboard without a set of its own gets a copy of the shared set
     * ([keepExisting] false resets keyboards that have an older set too).
     */
    @Synchronized
    fun enable(real: SharedPreferences, keyboards: List<SettingsSubtype>, keepExisting: Boolean) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("separate settings ON for ${keyboards.map { it.toPref() }} (keepExisting $keepExisting)")
        for (keyboard in keyboards) {
            if (keepExisting && hasOwnSettings(real, keyboard)) continue
            copy(real, SHARED, idFor(real, keyboard))
        }
        real.edit().putBoolean(PREF_SEPARATE, true).apply()
    }

    /** Turn separate settings off; [sharedFrom] = the keyboard whose set becomes the shared one, null keeps the previous shared set. */
    @Synchronized
    fun disable(real: SharedPreferences, sharedFrom: SettingsSubtype?) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("separate settings OFF, shared from ${sharedFrom?.toPref()}")
        if (sharedFrom != null) copy(real, idFor(real, sharedFrom), SHARED)
        real.edit().putBoolean(PREF_SEPARATE, false).apply()
    }

    /** Profile of the keyboard in use (the IME's view). Refreshed on every keyboard switch. */
    @Volatile var imeId: Int = SHARED
        private set
    /** Profile the settings screens edit: a keyboard's id, or [SHARED]. */
    @Volatile var editingId: Int = SHARED

    fun refreshImeId(real: SharedPreferences) {
        val old = imeId
        imeId = if (isSeparate(real)) idFor(real, selectedKeyboard(real)) else SHARED
        // another keyboard's background picture and emoji font (see profileFile)
        if (imeId != old) {
            Settings.clearCachedBackgroundImages()
            runCatching { helium314.keyboard.keyboard.KeyboardTypeface.clearCache() }
        }
    }

    // ---- files that belong to a keyboard's settings ----
    // The background pictures (with their framing) are files, not preferences: with separate
    // settings each keyboard has its own copy, named with "_p<id>" (the shared set keeps the plain name). Copying a
    // set copies them too. Before 2026-10-02 every keyboard used the plain files.

    /** The app's files dir, set at start (App) so [copy] can reach the files without a context. */
    @Volatile var filesDir: java.io.File? = null

    /** Before a plain key in a keyboard's own set: "this one is at its default" (not the shared value). */
    const val TOMBSTONE = "~"

    private val profileFileNames = listOf("custom_background_image", "custom_background_image_night",
        "custom_background_image_landscape", "custom_background_image_landscape_night")

    /** [name] for the set in use where [context] is: the settings screens' keyboard, or the keyboard on screen. */
    @JvmStatic
    fun profileFile(context: android.content.Context, name: String): java.io.File {
        val dir = helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(context)
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)
        val id = if (!isSeparate(real)) SHARED
            else if (context.getActivity() != null) editingId else imeId
        return java.io.File(dir, name + suffix(id))
    }

    private fun suffix(id: Int) = if (id == SHARED) "" else "_p$id"

    /** The files of set [fromId] over those of [toId] (one missing in [fromId] is removed in [toId]). */
    private fun copyFiles(fromId: Int, toId: Int) {
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) {
            val from = java.io.File(dir, name + suffix(fromId) + ext)
            val to = java.io.File(dir, name + suffix(toId) + ext)
            runCatching { if (from.exists()) from.copyTo(to, overwrite = true) else to.delete() }
        }
    }

    /** The files of set [id] (not the shared set's). */
    fun deleteFiles(id: Int) {
        if (id == SHARED) return
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) java.io.File(dir, name + suffix(id) + ext).delete()
    }

    /** Every keyboard's own picture files and the shared set's (a factory reset of the settings). */
    fun deleteAllFiles() {
        val dir = filesDir ?: return
        dir.listFiles { f -> profileFileNames.any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    /** Backup restore of one keyboard: its pictures from the backup's files (named for the backup's id [fromId]),
     *  given as name -> bytes, become set [toId]'s. */
    fun restoreFiles(files: Map<String, ByteArray>, fromId: Int, toId: Int) {
        val dir = filesDir ?: return
        for (name in profileFileNames) for (ext in listOf("", ".framing")) {
            val to = java.io.File(dir, name + suffix(toId) + ext)
            val bytes = files[name + suffix(fromId) + ext]
            runCatching { if (bytes != null) to.writeBytes(bytes) else to.delete() }
        }
    }

    /** Once: keyboards that got their own set before the files were per keyboard get a copy of the plain ones. */
    fun migrateFiles(real: SharedPreferences) {
        if (real.getBoolean("profile_files_migrated", false)) return
        val map = ids(real)
        for (key in map.keys()) {
            val id = map.optInt(key, SHARED)
            if (id == SHARED) continue
            val dir = filesDir ?: return
            if (profileFileNames.none { java.io.File(dir, it + suffix(id)).exists() }) copyFiles(SHARED, id)
        }
        real.edit().putBoolean("profile_files_migrated", true).apply()
    }

    /** The keyboard whose settings the screens edit (separate settings on), or null (shared settings). */
    fun editingKeyboard(real: SharedPreferences): SettingsSubtype? {
        val id = editingId
        if (id == SHARED || !isSeparate(real)) return null
        val map = ids(real)
        return map.keys().asSequence().firstOrNull { map.optInt(it, -1) == id }?.toSettingsSubtype()
    }

    fun selectedKeyboard(real: SharedPreferences): SettingsSubtype =
        real.getString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)!!.toSettingsSubtype()
}

/**
 * The preferences every part of the app talks to. Reads and writes are redirected to the active profile's keys
 * when separate settings are on; listeners get the un-prefixed key names. [activeId] is looked up on every call.
 */
class ProfilePreferences(private val real: SharedPreferences, private val activeId: () -> Int) : SharedPreferences {
    private fun id() = if (KeyboardProfiles.isSeparate(real)) activeId() else KeyboardProfiles.SHARED
    private fun k(key: String) = KeyboardProfiles.prefixedKey(id(), key)
    // a profile key that was never written falls back to the shared value, so a half-copied set still behaves
    private fun has(key: String) = real.contains(k(key))
    // set back to its default in this keyboard's own set (the "Default" buttons, Discard, a theme's default): not the
    // shared value
    private fun dead(key: String) = id() != KeyboardProfiles.SHARED && !KeyboardProfiles.isGlobal(key)
        && real.contains(KeyboardProfiles.prefixedKey(id(), KeyboardProfiles.TOMBSTONE + key))

    override fun getAll(): MutableMap<String, *> {
        val id = id()
        val result = HashMap<String, Any?>()
        for ((key, value) in real.all) {
            val plain = KeyboardProfiles.unprefixedKey(KeyboardProfiles.SHARED, key) ?: continue
            if (id == KeyboardProfiles.SHARED || KeyboardProfiles.isGlobal(plain)) { if (!(key.startsWith("p") && key.contains("/"))) result[key] = value }
        }
        if (id != KeyboardProfiles.SHARED) {
            for ((key, value) in real.all) { KeyboardProfiles.unprefixedKey(id, key)?.let { if (it != key) result[it] = value } }
            // at their default here: neither the mark nor the shared value
            result.keys.filter { it.startsWith(KeyboardProfiles.TOMBSTONE) }.forEach { result.remove(it); result.remove(it.removePrefix(KeyboardProfiles.TOMBSTONE)) }
        }
        return result
    }
    override fun getString(key: String, defValue: String?) = if (has(key)) real.getString(k(key), defValue) else if (dead(key)) defValue else real.getString(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = if (has(key)) real.getStringSet(k(key), defValues) else if (dead(key)) defValues else real.getStringSet(key, defValues)
    override fun getInt(key: String, defValue: Int) = if (has(key)) real.getInt(k(key), defValue) else if (dead(key)) defValue else real.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long) = if (has(key)) real.getLong(k(key), defValue) else if (dead(key)) defValue else real.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float) = if (has(key)) real.getFloat(k(key), defValue) else if (dead(key)) defValue else real.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = if (has(key)) real.getBoolean(k(key), defValue) else if (dead(key)) defValue else real.getBoolean(key, defValue)
    override fun contains(key: String) = has(key) || (!dead(key) && real.contains(key))

    override fun edit(): SharedPreferences.Editor = Editor(real.edit(), id())

    private inner class Editor(private val e: SharedPreferences.Editor, private val id: Int) : SharedPreferences.Editor {
        private fun k(key: String) = KeyboardProfiles.prefixedKey(id, key)
        private val ownSet = id != KeyboardProfiles.SHARED
        // a value written: no longer "at its default"; removed in a keyboard's own set: at its default, not the shared value
        private fun alive(key: String) { if (ownSet && !KeyboardProfiles.isGlobal(key)) e.remove(k(KeyboardProfiles.TOMBSTONE + key)) }
        override fun putString(key: String, value: String?) = apply { e.putString(k(key), value); alive(key) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { e.putStringSet(k(key), values); alive(key) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(k(key), value); alive(key) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(k(key), value); alive(key) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(k(key), value); alive(key) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(k(key), value); alive(key) }
        override fun remove(key: String) = apply {
            e.remove(k(key))
            if (ownSet && !KeyboardProfiles.isGlobal(key)) e.putBoolean(k(KeyboardProfiles.TOMBSTONE + key), true)
        }
        override fun clear() = apply { e.clear() }
        override fun commit() = e.commit()
        override fun apply() = e.apply()
    }

    private val listeners = HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val plain = if (key == null) null else KeyboardProfiles.unprefixedKey(id(), key)?.removePrefix(KeyboardProfiles.TOMBSTONE)
            // keys of other profiles are not this profile's business; shared keys still are (fallback reads)
            if (key == null || plain != null) listener.onSharedPreferenceChanged(this, plain)
        }
        // registered again (a restore restarts the listener without stopping it): one callback, not two
        synchronized(listeners) { listeners.put(listener, wrapped) }?.let { real.unregisterOnSharedPreferenceChangeListener(it) }
        real.registerOnSharedPreferenceChangeListener(wrapped)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapped = synchronized(listeners) { listeners.remove(listener) } ?: return
        real.unregisterOnSharedPreferenceChangeListener(wrapped)
    }

    /** The underlying preferences, for the profile bookkeeping and backup. */
    val underlying: SharedPreferences get() = real
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
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
        Settings.PREF_ADVANCED_SETTINGS, Settings.PREF_VERSION_CODE, Settings.PREF_LIBRARY_CHECKSUM,
        Settings.PREF_SHOW_SETUP_WIZARD_ICON, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST,
        PREF_SEPARATE, PREF_IDS, PREF_NEXT_ID,
    )
    private val globalPrefixes = listOf(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX, "language_priority_", "share_user_history_", "debug_")

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
        for ((key, value) in real.all) {
            val plain = unprefixedKey(fromId, key) ?: continue
            if (fromId == SHARED && (key.startsWith(PREFIX) && key.contains(SEPARATOR))) continue
            if (isGlobal(plain)) continue
            val target = prefixedKey(toId, plain)
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> editor.putBoolean(target, value)
                is Int -> editor.putInt(target, value)
                is Long -> editor.putLong(target, value)
                is Float -> editor.putFloat(target, value)
                is String -> editor.putString(target, value)
                is Set<*> -> editor.putStringSet(target, value as Set<String>)
            }
        }
        editor.apply()
        Log.i("KeyboardProfiles", "copied settings $fromId -> $toId")
    }

    /**
     * Turn separate settings on: every enabled keyboard without a set of its own gets a copy of the shared set
     * ([keepExisting] false resets keyboards that have an older set too).
     */
    @Synchronized
    fun enable(real: SharedPreferences, keyboards: List<SettingsSubtype>, keepExisting: Boolean) {
        for (keyboard in keyboards) {
            if (keepExisting && hasOwnSettings(real, keyboard)) continue
            copy(real, SHARED, idFor(real, keyboard))
        }
        real.edit().putBoolean(PREF_SEPARATE, true).apply()
    }

    /** Turn separate settings off; [sharedFrom] = the keyboard whose set becomes the shared one, null keeps the previous shared set. */
    @Synchronized
    fun disable(real: SharedPreferences, sharedFrom: SettingsSubtype?) {
        if (sharedFrom != null) copy(real, idFor(real, sharedFrom), SHARED)
        real.edit().putBoolean(PREF_SEPARATE, false).apply()
    }

    /** Profile of the keyboard in use (the IME's view). Refreshed on every keyboard switch. */
    @Volatile var imeId: Int = SHARED
        private set
    /** Profile the settings screens edit: a keyboard's id, or [SHARED]. */
    @Volatile var editingId: Int = SHARED

    fun refreshImeId(real: SharedPreferences) {
        imeId = if (isSeparate(real)) idFor(real, selectedKeyboard(real)) else SHARED
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

    override fun getAll(): MutableMap<String, *> {
        val id = id()
        val result = HashMap<String, Any?>()
        for ((key, value) in real.all) {
            val plain = KeyboardProfiles.unprefixedKey(KeyboardProfiles.SHARED, key) ?: continue
            if (id == KeyboardProfiles.SHARED || KeyboardProfiles.isGlobal(plain)) { if (!(key.startsWith("p") && key.contains("/"))) result[key] = value }
        }
        if (id != KeyboardProfiles.SHARED)
            for ((key, value) in real.all) { KeyboardProfiles.unprefixedKey(id, key)?.let { if (it != key) result[it] = value } }
        return result
    }
    override fun getString(key: String, defValue: String?) = if (has(key)) real.getString(k(key), defValue) else real.getString(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = if (has(key)) real.getStringSet(k(key), defValues) else real.getStringSet(key, defValues)
    override fun getInt(key: String, defValue: Int) = if (has(key)) real.getInt(k(key), defValue) else real.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long) = if (has(key)) real.getLong(k(key), defValue) else real.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float) = if (has(key)) real.getFloat(k(key), defValue) else real.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = if (has(key)) real.getBoolean(k(key), defValue) else real.getBoolean(key, defValue)
    override fun contains(key: String) = has(key) || real.contains(key)

    override fun edit(): SharedPreferences.Editor = Editor(real.edit(), id())

    private inner class Editor(private val e: SharedPreferences.Editor, private val id: Int) : SharedPreferences.Editor {
        private fun k(key: String) = KeyboardProfiles.prefixedKey(id, key)
        override fun putString(key: String, value: String?) = apply { e.putString(k(key), value) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { e.putStringSet(k(key), values) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(k(key), value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(k(key), value) }
        override fun putFloat(key: String, value: Float) = apply { e.putFloat(k(key), value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(k(key), value) }
        override fun remove(key: String) = apply { e.remove(k(key)) }
        override fun clear() = apply { e.clear() }
        override fun commit() = e.commit()
        override fun apply() = e.apply()
    }

    private val listeners = HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val plain = if (key == null) null else KeyboardProfiles.unprefixedKey(id(), key)
            // keys of other profiles are not this profile's business; shared keys still are (fallback reads)
            if (key == null || plain != null) listener.onSharedPreferenceChanged(this, plain)
        }
        synchronized(listeners) { listeners[listener] = wrapped }
        real.registerOnSharedPreferenceChangeListener(wrapped)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val wrapped = synchronized(listeners) { listeners.remove(listener) } ?: return
        real.unregisterOnSharedPreferenceChangeListener(wrapped)
    }

    /** The underlying preferences, for the profile bookkeeping and backup. */
    val underlying: SharedPreferences get() = real
}

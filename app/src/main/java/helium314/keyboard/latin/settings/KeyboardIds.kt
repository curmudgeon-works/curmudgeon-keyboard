// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.personalization.LearnedKeyboards
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Every keyboard of the user has a unique id (owner 2026-10-11: "identifying a list element by it's features feels
 * fundamentally flawed" -> "do IDs now"): "k" and 10 random base36 characters, a hidden extra value of its definition
 * ([ExtraValue.KEYBOARD_ID]), so it travels with the keyboard everywhere (the lists, Android, the apps' memory, backups).
 * Never shown, never compared as a difference; kept through a rename and any change of keys, languages or accents. Its
 * settings are stored under it ("p<id>/<key>", KeyboardProfiles), its learned words' pool and its buttons are found by
 * it (LearnedKeyboards). A built-in keyboard of the language list has none: only the user's keyboards do.
 * Owner: "this should be a safe ID in general. We can think later to add some protection perhaps".
 */
object KeyboardIds {
    private const val TAG = "KeyboardIds"
    private const val LENGTH = 10
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val secure = SecureRandom()

    /** A random id, "k" and [LENGTH] base36 characters. */
    fun random(): String = "k" + String(CharArray(LENGTH) { CHARS[secure.nextInt(CHARS.length)] })

    /** Where new ids come from (tests replace it). */
    @Volatile internal var source: () -> String = ::random

    /** Whether [text] has the form of an id: "k" and base36 characters. */
    fun isId(text: String?): Boolean = text != null && text.length in 2..32 && text[0] == 'k' &&
        (1 until text.length).all { text[it] in '0'..'9' || text[it] in 'a'..'z' }

    /** Android's subtype id for keyboard id [id]: the first 31 bits of the SHA-256 of its text, never 0 (0 is "none"
     *  for Android). Fixed: Android sees a keyboard as the same one across renames and other keys. */
    fun androidSubtypeId(id: String): Int = subtypeIdOf(id)

    /** How [androidSubtypeId] is worked out (tests replace it to make two ids clash). */
    @Volatile internal var subtypeIdOf: (String) -> Int = ::sha256Id

    private fun sha256Id(id: String): Int {
        val d = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val v = ((d[0].toInt() and 0x7f) shl 24) or ((d[1].toInt() and 0xff) shl 16) or ((d[2].toInt() and 0xff) shl 8) or (d[3].toInt() and 0xff)
        return if (v == 0) 1 else v
    }

    private fun definitions(value: String?): List<SettingsSubtype> =
        value.orEmpty().split(Separators.SETS).mapNotNull { if (it.isEmpty()) null else runCatching { it.toSettingsSubtype() }.getOrNull() }

    /** The keyboards of the lists as stored: the enabled ones, then those only in the added list. */
    fun phoneKeyboards(real: SharedPreferences): List<SettingsSubtype> =
        phoneKeyboards(real.getString(Settings.PREF_ENABLED_SUBTYPES, null), real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, null))

    /** [phoneKeyboards] of the lists as read ([enabledPref], [addedPref]; null: not stored, the default). */
    fun phoneKeyboards(enabledPref: String?, addedPref: String?): List<SettingsSubtype> {
        val enabled = definitions(enabledPref ?: Defaults.PREF_ENABLED_SUBTYPES)
        val added = definitions(addedPref ?: Defaults.PREF_ADDITIONAL_SUBTYPES)
        return enabled + added.filter { a -> enabled.none { it.sameKeyboard(a) } }
    }

    /** Every keyboard id the phone knows: the lists, the selected one, the apps' memory, the settings sets, the learned
     *  words' pools and buttons, the came-from values. */
    fun known(all: Map<String, *>): Set<String> {
        val ids = HashSet<String>()
        fun add(kb: SettingsSubtype) { kb.keyboardId?.let { ids.add(it) }; kb.keyboardFrom?.let { ids.add(it) } }
        for (key in listOf(Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE))
            definitions(all[key] as? String).forEach(::add)
        for ((key, value) in all) {
            if (key.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX)) definitions(value as? String).forEach(::add)
            KeyboardProfiles.splitOwnKey(key)?.first?.let { ids.add(it) }
        }
        LearnedKeyboards.poolEntries(all).keys.forEach { ids.add(it) }
        for (pref in LearnedKeyboards.buttonPrefs) ids.addAll(jsonStrings(all[pref] as? String))
        return ids
    }

    /** A new id: none the phone knows ([known], [alsoTaken]) and none whose Android subtype id another of its keyboards
     *  has (owner 2026-10-11: on a clash "generate a new random id", "agree"). */
    fun newId(all: Map<String, *>, alsoTaken: Collection<String> = emptyList()): String {
        val taken = known(all) + alsoTaken
        val androidIds = taken.mapTo(HashSet()) { androidSubtypeId(it) }
        repeat(1000) {
            val id = source()
            if (isId(id) && id !in taken && androidSubtypeId(id) !in androidIds) return id
        }
        throw IllegalStateException("no free keyboard id")
    }

    fun newId(real: SharedPreferences, alsoTaken: Collection<String> = emptyList()): String = newId(real.all, alsoTaken)

    /** [keyboard] as a new keyboard: a new id, came-from [from] (a copy's original; none for a fresh keyboard). */
    fun fresh(real: SharedPreferences, keyboard: SettingsSubtype, from: String? = null, alsoTaken: Collection<String> = emptyList()): SettingsSubtype =
        keyboard.withKeyboardId(newId(real, alsoTaken), from).toPref().toSettingsSubtype()

    internal fun jsonStrings(json: String?): LinkedHashSet<String> = try {
        JSONArray(json ?: "[]").let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } }
    } catch (e: Exception) { LinkedHashSet() }

    /**
     * The net's ids (owner 2026-10-11: "Ok, A + net"; a net gives LISTED keyboards theirs): every keyboard in the list
     * without an id gets one, everywhere it is named (the list, the selected keyboard, the apps' memory), and every
     * listed keyboard is in the added list (a keyboard with an id is always the user's own, loaded from its definition).
     * Before the list is loaded (SubtypeSettings). @return whether anything was written
     */
    fun ensureListedIds(real: SharedPreferences): Boolean {
        // (never inside itself: its write's listeners load the keyboards, which run it; 2026-10-12, a first-start hang)
        if (inNet) return false
        inNet = true
        try { return giveListedIds(real) } finally { inNet = false }
    }

    @Volatile private var inNet = false

    // [ensureListedIds]: written only when something differs, so a second pass writes nothing
    private fun giveListedIds(real: SharedPreferences): Boolean {
        val enabledPref = real.getString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)!!
        val enabled = definitions(enabledPref)
        val added = definitions(real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES))
        if (enabled.all { kb -> kb.keyboardId != null && added.any { it == kb } }) return false
        val all = real.all
        val given = HashMap<SettingsSubtype, SettingsSubtype>() // plain -> with its id
        val newIds = ArrayList<String>()
        val listed = enabled.map { kb ->
            if (kb.keyboardId != null) kb
            else given.getOrPut(kb) { fresh(real, kb, alsoTaken = newIds).also { newIds.add(it.keyboardId!!) } }
        }
        // (the added list: a keyboard's entry replaced by its definition with the id, the listed ones all in it)
        val newAdded = added.map { a -> given[a] ?: a }.filter { a -> listed.none { it.sameKeyboard(a) && it != a } }.toMutableList()
        for (kb in listed) if (newAdded.none { it == kb }) newAdded.add(kb)
        val editor = real.edit()
        val newEnabledPref = listed.map { it.toPref() }.distinct().joinToString(Separators.SETS)
        val newAddedPref = newAdded.map { it.toPref() }.distinct().joinToString(Separators.SETS)
        if (given.isEmpty() && newEnabledPref == enabledPref && newAddedPref == real.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES))
            return false // (nothing to change: written as it is)
        if (newEnabledPref != enabledPref) editor.putString(Settings.PREF_ENABLED_SUBTYPES, newEnabledPref)
        editor.putString(Settings.PREF_ADDITIONAL_SUBTYPES, newAddedPref)
        val selected = real.getString(Settings.PREF_SELECTED_SUBTYPE, null)?.let { runCatching { it.toSettingsSubtype() }.getOrNull() }
        selected?.let { s -> given[s]?.let { editor.putString(Settings.PREF_SELECTED_SUBTYPE, it.toPref()) } }
        for ((key, value) in all) if (key.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX))
            (value as? String)?.let { runCatching { it.toSettingsSubtype() }.getOrNull() }?.let { given[it] }?.let { editor.putString(key, it.toPref()) }
        editor.commit()
        if (given.isNotEmpty()) Log.i(TAG, "the net: ${given.size} listed keyboards got their ids")
        return true
    }

    /**
     * [keyboard] (a phone keyboard) gets a new id, its came-from its id of before (owner 2026-10-11, restore "Keep as a
     * separate keyboard": "backup's ID should take priority", the phone's keyboard gets a new one), once: every list
     * naming it, its settings set and pictures, its learned words' pool and buttons follow it to the new id. The apps
     * that remembered the keyboard stay on the old id, the restored keyboard's (owner 2026-10-12: "I think restored
     * keyboard should take prio. User has just restored. They don't expect to be using the non-restored version").
     * @return the keyboard as it is now
     */
    fun giveNewId(context: Context, keyboard: SettingsSubtype): SettingsSubtype {
        val real = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context)
        val old = keyboard.keyboardId ?: return keyboard
        val renewed = keyboard.withKeyboardId(newId(real), old).toPref().toSettingsSubtype()
        val id = renewed.keyboardId!!
        fun renew(stored: SettingsSubtype) = stored.withIdOf(renewed).toPref()
        // the records first, then the lists (their write reloads the keyboards)
        KeyboardProfiles.moveSet(real, old, id)
        LearnedKeyboards.moveRecords(real, old, id)
        val editor = real.edit()
        for (key in listOf(Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_ADDITIONAL_SUBTYPES)) {
            val list = definitions(real.getString(key, ""))
            if (list.none { it.keyboardId == old }) continue
            editor.putString(key, list.map { if (it.keyboardId == old) renew(it) else it.toPref() }.distinct().joinToString(Separators.SETS))
        }
        real.getString(Settings.PREF_SELECTED_SUBTYPE, null)?.let { runCatching { it.toSettingsSubtype() }.getOrNull() }
            ?.takeIf { it.keyboardId == old }?.let { editor.putString(Settings.PREF_SELECTED_SUBTYPE, renew(it)) }
        // (the apps' memory is left on the old id: they open the restored keyboard, which keeps it)
        editor.commit()
        Log.i(TAG, "a phone keyboard got a new id (its old one is a restored keyboard's)")
        helium314.keyboard.latin.utils.SubtypeSettings.reloadEnabledSubtypes(context)
        return renewed
    }
}

/**
 * The one-time upgrade to keyboard ids (owner 2026-10-11, pre-launch: "don't care about past installs or past backups at
 * this point. Will care in future, so now is a good time to implement these IDs"): crude, once ([DONE]). Run on the
 * phone's preferences before the keyboards are loaded (App), at the start of checkVersionUpgrade, and on a backup's
 * preferences when it is read (its keyboards get fresh ids; its own old id map finds their sets, else their settings
 * are ignored, owner accepts). Every keyboard of the lists, the old id map and the pools gets a random id; the sets
 * "p<n>/" become "p<id>/"; the lists, selected keyboard, apps' memory and buttons name the keyboards with their ids;
 * the pools are keyed by id; the old id map goes.
 */
object KeyboardIdUpgrade {
    private const val TAG = "KeyboardIdUpgrade"
    const val DONE = "keyboard_ids_done"
    /** The old map, definition -> set number (before 2026-10-11). */
    const val OLD_IDS = "keyboard_profile_ids"
    private const val OLD_NEXT = "keyboard_profile_next_id"
    private const val OLD_PREFIX = "p"
    private const val SEPARATOR = "/"

    /** The upgrade of [map] (preferences as stored), in place. @return the old set numbers -> the new ids (the pictures'
     *  file names follow them), null when it was done already */
    fun upgrade(map: MutableMap<String, Any?>): Map<Int, String>? {
        if (map[DONE] == true) return null
        val oldIds = jsonInts(map[OLD_IDS] as? String)
        val oldPools = jsonInts(map[LearnedKeyboards.PREF_POOLS] as? String)
        val taken = HashSet<String>()
        // (the definitions that already have an id keep it: a net or a later version gave it)
        for (key in listOf(Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_ADDITIONAL_SUBTYPES, Settings.PREF_SELECTED_SUBTYPE))
            parse(map[key] as? String).forEach { kb -> kb.keyboardId?.let { taken.add(it) } }
        val withIds = LinkedHashMap<String, SettingsSubtype>() // definition as stored (sorted) -> with its id
        fun idd(kb: SettingsSubtype): SettingsSubtype = withIds.getOrPut(kb.toPref()) {
            if (kb.keyboardId != null) kb
            else {
                val androidIds = taken.mapTo(HashSet()) { KeyboardIds.androidSubtypeId(it) }
                var id: String
                var tries = 0
                do {
                    id = KeyboardIds.source()
                    check(++tries < 1000) { "no free keyboard id" }
                } while (!KeyboardIds.isId(id) || id in taken || KeyboardIds.androidSubtypeId(id) in androidIds)
                taken.add(id)
                kb.withKeyboardId(id).toPref().toSettingsSubtype()
            }
        }
        fun iddOf(pref: String): SettingsSubtype? = runCatching { pref.toSettingsSubtype() }.getOrNull()?.let { idd(it) }

        val enabled = parse(map[Settings.PREF_ENABLED_SUBTYPES] as? String).map(::idd)
        val added = parse(map[Settings.PREF_ADDITIONAL_SUBTYPES] as? String).map(::idd)
        // the switched-off ones (a set or a pool, not listed): in the added list, where their definitions are kept now
        val off = (oldIds.keys + oldPools.keys).mapNotNull(::iddOf).filter { kb -> enabled.none { it == kb } }
        map[Settings.PREF_ENABLED_SUBTYPES] = join(enabled)
        if (enabled.isNotEmpty() || added.isNotEmpty() || off.isNotEmpty())
            map[Settings.PREF_ADDITIONAL_SUBTYPES] = join(added + enabled + off)
        (map[Settings.PREF_SELECTED_SUBTYPE] as? String)?.takeIf { it.isNotEmpty() }?.let { iddOf(it) }?.let { map[Settings.PREF_SELECTED_SUBTYPE] = it.toPref() }
        // the apps' memory: the keyboards known here; one not on the phone (deleted) is forgotten
        for (key in map.keys.filter { it.startsWith(Settings.PREF_SAVED_APP_SUBTYPE_PREFIX) }) {
            val kb = (map[key] as? String)?.let { runCatching { it.toSettingsSubtype() }.getOrNull() }
            val known = kb?.let { k -> withIds[k.toPref()] }
            if (known != null) map[key] = known.toPref() else map.remove(key)
        }
        // the sets: "p<n>/" -> "p<id>/"; an orphan number's keys go
        val renames = HashMap<Int, String>()
        for ((pref, n) in oldIds) iddOf(pref)?.keyboardId?.let { if (n != 0) renames[n] = it }
        for (key in map.keys.toList()) {
            val n = oldSet(key) ?: continue
            val value = map.remove(key)
            val id = renames[n] ?: continue
            map[OLD_PREFIX + id + SEPARATOR + key.substringAfter(SEPARATOR)] = value
        }
        // the pools and buttons, by id
        val pools = JSONObject()
        for ((pref, pool) in oldPools) iddOf(pref)?.keyboardId?.let { pools.put(it, pool) }
        // (entries keyed by an id already, data of since: kept as they are)
        for ((id, pool) in idEntries(map[LearnedKeyboards.PREF_POOLS] as? String)) if (!pools.has(id)) pools.put(id, pool)
        val learnedDone = map[LearnedKeyboards.PREF_DONE] == true
        val sharedWords = map[Settings.PREF_SHARE_LEARNED_WORDS] as? Boolean ?: Defaults.PREF_SHARE_LEARNED_WORDS
        if (!learnedDone && !sharedWords) {
            // (each keyboard's learned words were its set number's: the 2026-10-10 update, sharing off, done here, as it
            // no longer knows the set numbers; no files change)
            for ((n, id) in renames) pools.put(id, n)
            val next = maxOf((map[LearnedKeyboards.PREF_POOL_NEXT] as? Int) ?: 1, (renames.keys.maxOrNull() ?: 0) + 1, (map[OLD_NEXT] as? Int) ?: 1)
            map[LearnedKeyboards.PREF_POOL_NEXT] = next
            for (pref in LearnedKeyboards.buttonPrefs) map.remove(pref)
            map[LearnedKeyboards.PREF_DONE] = true
            map.remove(LearnedKeyboards.PREF_PLAN)
        } else {
            for (pref in LearnedKeyboards.buttonPrefs) {
                val list = KeyboardIds.jsonStrings(map[pref] as? String)
                    .mapNotNull { if (KeyboardIds.isId(it)) it else iddOf(it)?.keyboardId }.distinct()
                if (list.isEmpty()) map.remove(pref) else map[pref] = JSONArray(list).toString()
            }
            (map[LearnedKeyboards.PREF_PLAN] as? String)?.let { json -> runCatching {
                val plan = JSONObject(json)
                val p = plan.getJSONObject("pools")
                val keyed = JSONObject()
                for (k in p.keys()) (if (KeyboardIds.isId(k)) k else iddOf(k)?.keyboardId)?.let { keyed.put(it, p.getInt(k)) }
                plan.put("pools", keyed)
                map[LearnedKeyboards.PREF_PLAN] = plan.toString()
            } }
        }
        if (pools.length() > 0 || map.containsKey(LearnedKeyboards.PREF_POOLS)) map[LearnedKeyboards.PREF_POOLS] = pools.toString()
        map.remove(OLD_IDS)
        map.remove(OLD_NEXT)
        map[DONE] = true
        Log.i(TAG, "keyboard ids: ${withIds.size} keyboards, ${renames.size} sets")
        return renames
    }

    /** The upgrade of the phone's preferences ([real]) and its pictures' files in [filesDir] (each set's "_p<n>" files
     *  become "_p<id>"). @return whether it ran */
    fun upgrade(real: SharedPreferences, filesDir: java.io.File?): Boolean {
        if (real.getBoolean(DONE, false)) return false
        val before = real.all
        val after = HashMap<String, Any?>(before)
        val renames = upgrade(after) ?: return false
        val editor = real.edit()
        for (key in before.keys) if (key !in after) editor.remove(key)
        for ((key, value) in after) if (before[key] != value) {
            if (value == null) editor.remove(key) else KeyboardProfiles.put(editor, key, value)
        }
        editor.commit()
        if (filesDir != null) for ((n, id) in renames) KeyboardProfiles.moveFiles(filesDir, n.toString(), id)
        return true
    }

    /** [upgrade] of the phone ([context]'s preferences as stored and its files). */
    fun upgrade(context: Context): Boolean = upgrade(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(context),
        helium314.keyboard.latin.utils.DeviceProtectedUtils.getFilesDir(context))

    /** A backup's file [name] (a picture of an old set "_p<n>") as named after [renames] (null: as it is). */
    fun renamedFile(name: String, renames: Map<Int, String>): String {
        if (renames.isEmpty()) return name
        val m = Regex("""^(.*)_p(\d+)(\.framing)?$""").find(name) ?: return name
        val id = m.groupValues[2].toIntOrNull()?.let { renames[it] } ?: return name
        return m.groupValues[1] + "_p" + id + m.groupValues[3]
    }

    // "p<n>/<key>" of an old set number n, else null
    private fun oldSet(key: String): Int? {
        if (!key.startsWith(OLD_PREFIX)) return null
        val sep = key.indexOf(SEPARATOR)
        if (sep <= OLD_PREFIX.length) return null
        return key.substring(OLD_PREFIX.length, sep).toIntOrNull()
    }

    private fun parse(value: String?): List<SettingsSubtype> =
        value.orEmpty().split(Separators.SETS).mapNotNull { if (it.isEmpty()) null else runCatching { it.toSettingsSubtype() }.getOrNull() }

    // json object -> the entries keyed by an id
    private fun idEntries(json: String?): Map<String, Int> {
        val o = try { JSONObject(json ?: "{}") } catch (e: Exception) { return emptyMap() }
        return o.keys().asSequence().filter { KeyboardIds.isId(it) }.associateWith { o.optInt(it) }
    }

    private fun join(list: List<SettingsSubtype>) = list.map { it.toPref() }.distinct().joinToString(Separators.SETS)

    // json object -> definition (sorted, as stored) -> number; keys that are ids (data of since) are left out
    private fun jsonInts(json: String?): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val o = try { JSONObject(json ?: "{}") } catch (e: Exception) { return out }
        for (k in o.keys()) {
            if (KeyboardIds.isId(k)) continue
            val pref = runCatching { k.toSettingsSubtype().toPref() }.getOrNull() ?: continue
            out[pref] = o.optInt(k)
        }
        return out
    }
}

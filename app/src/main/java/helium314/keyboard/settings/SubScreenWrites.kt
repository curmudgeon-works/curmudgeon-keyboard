// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the Save of a screen opened from an editing screen ([LayoutDraft], [AppearanceDraft]) wrote: the stored keys
 * ([inScope], raw: a keyboard's own as p<id>/...) and the files under [liveDir] it changed, as they were after the
 * save. Leaving the app puts back only the editing screen's own changes; these stay as saved (owner 2026-10-11, S2:
 * "yes let's keep saved subscreen changes"). The editing screen's own Discard still undoes them too.
 * What the sub-screen wrote is told from what was there when it opened ([opened]) and at its Save ([saved]); kept on
 * disk in [dir] with the snapshot, so the undo after a crash keeps them as well.
 * Only the sub-screen's own change (owner 2026-10-11, review E4: "On app exit you should only honour the sub-screen's
 * save and discard all else"): a key holding a whole list ([ListElements]: the saved themes, Layouts and popup sets, the
 * keyboard lists, the learned words' pools) is recorded by the elements the sub-screen changed, and leaving the app puts
 * only those onto the list as the snapshot had it. Before, the whole list was taken, with the editing screen's own
 * unsaved changes in it (a theme deleted on Appearance stayed deleted). The palettes are a key each: one palette.
 */
internal class SubScreenWrites(private val dir: File, private val liveDir: File?, private val inScope: (String) -> Boolean) {
    private var ctx: Context? = null
    // what was there when the sub-screen opened, or at its last Save (null: no sub-screen open since the snapshot)
    private var baseline: Map<String, Any?>? = null
    private var baselineFiles: Map<String, Pair<Long, Long>> = emptyMap()
    // raw key -> value after the save, null: removed by it (a key saved again: the latest)
    private val prefs = LinkedHashMap<String, Any?>()
    // a whole-list key ([ListElements]) -> its elements the save changed, by element id
    private val lists = LinkedHashMap<String, LinkedHashMap<String, ListElements.Change>>()
    // path under [liveDir] -> true: written (its copy in [copies]), false: deleted
    private val files = LinkedHashMap<String, Boolean>()
    private val copies get() = File(dir, COPIES)

    fun isEmpty() = prefs.isEmpty() && lists.isEmpty() && files.isEmpty()

    /** A sub-screen opened from the editing screen: what it changes from here on is told at its Save. */
    fun opened(ctx: Context) {
        this.ctx = ctx.applicationContext
        baseline = scoped(ctx)
        baselineFiles = listing(liveDir)
    }

    /** The sub-screen saved: what it changed since it opened (or saved last) is recorded. */
    fun saved() {
        val ctx = ctx ?: return
        val before = baseline ?: return
        val now = scoped(ctx)
        for (key in now.keys + before.keys) if (now[key] != before[key]) {
            val was = ListElements.split(key, before[key])
            val isNow = ListElements.split(key, now[key])
            if (was == null || isNow == null) { prefs[key] = now[key]; continue } // (no list, or one that can't be read: the value)
            val changed = lists.getOrPut(key) { LinkedHashMap() }
            for (id in was.keys + isNow.keys) if (was[id] != isNow[id])
                changed[id] = ListElements.Change(isNow[id], added = id !in was || changed[id]?.added == true)
        }
        val nowFiles = listing(liveDir)
        for (path in nowFiles.keys + baselineFiles.keys) if (nowFiles[path] != baselineFiles[path]) {
            val copy = File(copies, path)
            if (path in nowFiles) { copy.parentFile?.mkdirs(); File(liveDir, path).copyTo(copy, overwrite = true); files[path] = true }
            else { copy.delete(); files[path] = false }
        }
        baseline = now; baselineFiles = nowFiles
        write()
    }

    /** [target] (raw key -> value, the snapshot) with the recorded writes over it: a list key's changed elements onto
     *  the list there. */
    fun over(target: Map<String, Any?>): Map<String, Any?> = target.toMutableMap().also { m ->
        prefs.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        lists.forEach { (k, changed) -> ListElements.applied(k, m[k], changed).let { if (it == null) m.remove(k) else m[k] = it } }
    }

    /** The recorded keys and files written again (after the snapshot was put back); a list key: its changed elements put
     *  onto the list as it is now (again: the same list). */
    fun writeTo(real: SharedPreferences) {
        if (prefs.isNotEmpty() || lists.isNotEmpty()) real.edit().apply {
            prefs.forEach { (k, v) -> if (v == null) remove(k) else KeyboardProfiles.put(this, k, v) }
            val all = real.all
            lists.forEach { (k, changed) ->
                val now = if (k in prefs) prefs[k] else all[k]
                val value = ListElements.applied(k, now, changed)
                if (value != now) { if (value == null) remove(k) else KeyboardProfiles.put(this, k, value) }
            }
        }.commit()
        val live = liveDir ?: return
        for ((path, written) in files) {
            val file = File(live, path)
            if (written) { file.parentFile?.mkdirs(); File(copies, path).copyTo(file, overwrite = true) } else file.delete()
        }
    }

    fun clear() {
        prefs.clear(); lists.clear(); files.clear(); baseline = null; baselineFiles = emptyMap(); ctx = null
        File(dir, FILE).delete(); copies.deleteRecursively()
    }

    private fun scoped(ctx: Context) = DeviceProtectedUtils.getRealSharedPreferences(ctx).all.filterKeys(inScope)

    private fun write() {
        val json = JSONObject()
        json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> v?.let { AppearanceLooks.toJson(it) }?.let { o.put(k, it) } } })
        json.put("removed", JSONArray(prefs.filterValues { it == null }.keys.toList()))
        json.put("files", JSONObject().also { o -> files.forEach { (k, v) -> o.put(k, v) } })
        // (each element as [id, element or null, added], in order: new ones are added in this order)
        json.put("lists", JSONObject().also { o -> lists.forEach { (k, changed) ->
            o.put(k, JSONArray().also { a -> changed.forEach { (id, c) ->
                a.put(JSONArray().put(id).put(c.element ?: JSONObject.NULL).put(c.added)) } })
        } })
        helium314.keyboard.latin.utils.AtomicFiles.write(File(dir, FILE), json.toString()) // (crash-safe, B4w.2)
    }

    companion object {
        private const val FILE = "sub_screen_writes.json"
        private const val COPIES = "sub_screen_files"

        /** relative path -> size and time, enough to tell a change without reading */
        fun listing(dir: File?): Map<String, Pair<Long, Long>> =
            dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }
                ?.associate { it.relativeTo(dir).path to (it.length() to it.lastModified()) } ?: emptyMap()

        /** The writes recorded in [dir] before a crash (none when nothing was recorded). */
        fun load(dir: File, liveDir: File?, inScope: (String) -> Boolean) = SubScreenWrites(dir, liveDir, inScope).apply {
            val json = runCatching { JSONObject(File(dir, FILE).readText()) }.getOrNull() ?: return@apply
            val p = json.optJSONObject("prefs")
            p?.keys()?.forEach { k -> prefs[k] = AppearanceLooks.fromJson(p.getJSONObject(k)) }
            json.optJSONArray("removed")?.let { a -> for (i in 0 until a.length()) prefs[a.getString(i)] = null }
            val f = json.optJSONObject("files")
            f?.keys()?.forEach { k -> files[k] = f.getBoolean(k) }
            val l = json.optJSONObject("lists")
            l?.keys()?.forEach { k ->
                val a = l.getJSONArray(k)
                lists[k] = LinkedHashMap<String, ListElements.Change>().also { m -> for (i in 0 until a.length()) a.getJSONArray(i).let { e ->
                    m[e.getString(0)] = ListElements.Change(if (e.isNull(1)) null else e.getString(1), e.optBoolean(2)) } }
            }
        }
    }
}

/**
 * The stored keys holding a whole list, as their elements (review E4): what a sub-screen's save changed of them is
 * recorded element by element ([SubScreenWrites]). The saved themes, Layouts and popup sets (a JSON list, an element by
 * its "name"), the keyboard list and the added keyboards (an element by its keyboard id), the learned words' pools
 * (keyboard id -> pool). Any other key is one value.
 */
internal object ListElements {
    /** An element a sub-screen's save changed: [element] as saved (null: removed); [added]: none of that id was there
     *  when the sub-screen opened (it made it). */
    class Change(val element: String?, val added: Boolean)

    private val named = setOf(AppearanceLooks.PREF, LayoutPresets.PREF, helium314.keyboard.latin.utils.KeyPopupOverrides.PREF_SETS)
    private val keyboards = setOf(helium314.keyboard.latin.settings.Settings.PREF_ENABLED_SUBTYPES,
        helium314.keyboard.latin.settings.Settings.PREF_ADDITIONAL_SUBTYPES)
    private val maps = setOf(helium314.keyboard.latin.personalization.LearnedKeyboards.PREF_POOLS)
    private val sep = helium314.keyboard.latin.common.Constants.Separators.SETS

    private fun plain(key: String) = LayoutDraft.plain(key)

    /** [key]'s [value] as its elements in order, id -> element (none stored: none); null: no list key, or a value that
     *  can't be read as one. */
    fun split(key: String, value: Any?): LinkedHashMap<String, String>? {
        val p = plain(key)
        if (p !in named && p !in keyboards && p !in maps) return null
        val out = LinkedHashMap<String, String>()
        if (value == null) return out
        if (value !is String) return null
        return runCatching {
            when (p) {
                in named -> { val a = JSONArray(value)
                    for (i in 0 until a.length()) { val o = a.getJSONObject(i); out[o.optString("name").ifEmpty { o.toString() }] = o.toString() } }
                in keyboards -> value.split(sep).filter { it.isNotEmpty() }.forEach { e ->
                    out[runCatching { e.toSettingsSubtype().keyboardId }.getOrNull() ?: e] = e }
                else -> { val o = JSONObject(value); o.keys().forEach { out[it] = o.get(it).toString() } }
            }
            out
        }.getOrNull()
    }

    private fun join(key: String, elements: Collection<Pair<String, String>>): String = when (plain(key)) {
        in named -> JSONArray().also { a -> elements.forEach { a.put(JSONObject(it.second)) } }.toString()
        in keyboards -> elements.joinToString(sep) { it.second }
        else -> JSONObject().also { o -> elements.forEach { (id, e) -> o.put(id, e.toIntOrNull() ?: e) } }.toString()
    }

    /** [changed] onto [value], [key]'s list as it is: a changed element in its place, one the sub-screen made at the end,
     *  a removed one gone; the rest as there. A changed element not in [value] stays out: the editing screen made it
     *  (a theme it renamed, a keyboard it added), and its changes are undone. Null: nothing stored (no element, none
     *  before). A value that can't be read as the list stays as it is. */
    fun applied(key: String, value: Any?, changed: Map<String, Change>): Any? {
        val elements = split(key, value) ?: return value
        for ((id, c) in changed) when {
            c.element == null -> elements.remove(id)
            id in elements || c.added -> elements[id] = c.element
        }
        if (value == null && elements.isEmpty()) return null
        return join(key, elements.map { it.key to it.value })
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.KeyboardProfiles
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
 */
internal class SubScreenWrites(private val dir: File, private val liveDir: File?, private val inScope: (String) -> Boolean) {
    private var ctx: Context? = null
    // what was there when the sub-screen opened, or at its last Save (null: no sub-screen open since the snapshot)
    private var baseline: Map<String, Any?>? = null
    private var baselineFiles: Map<String, Pair<Long, Long>> = emptyMap()
    // raw key -> value after the save, null: removed by it (a key saved again: the latest)
    private val prefs = LinkedHashMap<String, Any?>()
    // path under [liveDir] -> true: written (its copy in [copies]), false: deleted
    private val files = LinkedHashMap<String, Boolean>()
    private val copies get() = File(dir, COPIES)

    fun isEmpty() = prefs.isEmpty() && files.isEmpty()

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
        for (key in now.keys + before.keys) if (now[key] != before[key]) prefs[key] = now[key]
        val nowFiles = listing(liveDir)
        for (path in nowFiles.keys + baselineFiles.keys) if (nowFiles[path] != baselineFiles[path]) {
            val copy = File(copies, path)
            if (path in nowFiles) { copy.parentFile?.mkdirs(); File(liveDir, path).copyTo(copy, overwrite = true); files[path] = true }
            else { copy.delete(); files[path] = false }
        }
        baseline = now; baselineFiles = nowFiles
        write()
    }

    /** [target] (raw key -> value, the snapshot) with the recorded writes over it. */
    fun over(target: Map<String, Any?>): Map<String, Any?> = target.toMutableMap().also { m ->
        prefs.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
    }

    /** The recorded keys and files written again (after the snapshot was put back). */
    fun writeTo(real: SharedPreferences) {
        if (prefs.isNotEmpty()) real.edit().apply {
            prefs.forEach { (k, v) -> if (v == null) remove(k) else KeyboardProfiles.put(this, k, v) }
        }.commit()
        val live = liveDir ?: return
        for ((path, written) in files) {
            val file = File(live, path)
            if (written) { file.parentFile?.mkdirs(); File(copies, path).copyTo(file, overwrite = true) } else file.delete()
        }
    }

    fun clear() {
        prefs.clear(); files.clear(); baseline = null; baselineFiles = emptyMap(); ctx = null
        File(dir, FILE).delete(); copies.deleteRecursively()
    }

    private fun scoped(ctx: Context) = DeviceProtectedUtils.getRealSharedPreferences(ctx).all.filterKeys(inScope)

    private fun write() {
        val json = JSONObject()
        json.put("prefs", JSONObject().also { o -> prefs.forEach { (k, v) -> v?.let { AppearanceLooks.toJson(it) }?.let { o.put(k, it) } } })
        json.put("removed", JSONArray(prefs.filterValues { it == null }.keys.toList()))
        json.put("files", JSONObject().also { o -> files.forEach { (k, v) -> o.put(k, v) } })
        File(dir, FILE).writeText(json.toString())
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
        }
    }
}

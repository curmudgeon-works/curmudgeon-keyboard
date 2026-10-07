// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.realPrefs
import org.json.JSONObject

/**
 * How the swipe decoder fares with each tuning the user tries: per [OwnGestureDecoder.Tuning.key], how many
 * swipes were kept as decoded, replaced from the strip (by rank) or deleted. Kept in the plain preferences under
 * [PREF_KEY] (global, not per keyboard), so the user can compare settings against their own typing.
 */
object GestureStats {
    const val PREF_KEY = "gesture_stats"
    /** Fewer swipes than this say nothing about a tuning. */
    const val MIN_SWIPES_FOR_RECOMMENDATION = 30

    class Row(
        var swipes: Int = 0, var kept: Int = 0, var pickedSecond: Int = 0, var pickedThird: Int = 0,
        var pickedLater: Int = 0, var deleted: Int = 0,
        /** decode time on this phone: how many swipes were timed, their total ms and the slowest */
        var timed: Int = 0, var timeMs: Long = 0, var slowestMs: Int = 0,
        /** when the first and the latest swipe were counted (ms since 1970); 0: before the dates were kept */
        var first: Long = 0, var last: Long = 0,
    ) {
        val averageMs: Int get() = if (timed == 0) 0 else (timeMs / timed).toInt()
        /** Ranking score: a kept swipe counts fully, a second choice half, a third a quarter, the rest nothing. */
        val score: Float get() = if (swipes == 0) 0f else (kept + 0.5f * pickedSecond + 0.25f * pickedThird) / swipes

        fun toJson(): JSONObject = JSONObject().put("n", swipes).put("kept", kept).put("p2", pickedSecond)
            .put("p3", pickedThird).put("pl", pickedLater).put("del", deleted)
            .put("t", timed).put("ms", timeMs).put("max", slowestMs).put("from", first).put("to", last)

        companion object {
            fun fromJson(o: JSONObject) = Row(o.optInt("n"), o.optInt("kept"), o.optInt("p2"), o.optInt("p3"), o.optInt("pl"), o.optInt("del"),
                o.optInt("t"), o.optLong("ms"), o.optInt("max"), o.optLong("from"), o.optLong("to"))
        }
    }

    private var pendingKey: String? = null

    // the rows as stored, parsed once and written back a moment after a change, all changes in one write (review
    // 2026-10-06 Low: two parses and two writes per swipe); where they're stored (replaceable by tests)
    internal var prefsProvider: () -> SharedPreferences? = { Settings.getCurrentContext()?.realPrefs() }
    @Volatile private var live: JSONObject? = null
    private var writePending = false
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "GestureStats").apply { isDaemon = true } }
    private const val WRITE_DELAY_MS = 2_000L

    @Synchronized private fun json(prefs: SharedPreferences): JSONObject = live ?: readJson(prefs).also { live = it }

    @Synchronized private fun scheduleWrite(prefs: SharedPreferences) {
        if (writePending) return
        writePending = true
        writer.schedule({ flush(prefs) }, WRITE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** The stored rows changed under the cached ones (a factory reset, a restore): read again at the next use, and a
     *  write already scheduled writes nothing (reviewer 2026-10-07: the old rows came back over the new ones). */
    @Synchronized fun reload() { live = null }

    /** Writes what changed now (tests, and the scheduled write). */
    @Synchronized fun flush(prefs: SharedPreferences) {
        writePending = false
        val o = live ?: return
        prefs.edit().putString(PREF_KEY, o.toString()).apply()
    }

    /** A swipe was decoded under [tuningKey] in [decodeMs]; an earlier swipe still pending was kept as it came. */
    @Synchronized
    fun onSwipe(tuningKey: String, decodeMs: Long) {
        pendingKey?.let { update(it) { r -> r.swipes++; r.kept++ } }
        pendingKey = tuningKey
        update(tuningKey) { r -> r.timed++; r.timeMs += decodeMs; r.slowestMs = maxOf(r.slowestMs, decodeMs.toInt()) }
    }

    /** The pending swiped word was replaced from the strip; [rank] is 0-based (0 = the word itself, 1 = second choice). */
    @Synchronized
    fun onPicked(rank: Int) {
        val key = pendingKey ?: return
        pendingKey = null
        update(key) { r ->
            r.swipes++
            when (rank) {
                0 -> r.kept++
                1 -> r.pickedSecond++
                2 -> r.pickedThird++
                else -> r.pickedLater++
            }
        }
    }

    /** The pending swiped word was deleted with backspace. */
    @Synchronized
    fun onDeleted() {
        val key = pendingKey ?: return
        pendingKey = null
        update(key) { r -> r.swipes++; r.deleted++ }
    }

    @Synchronized
    fun read(prefs: SharedPreferences): Map<String, Row> {
        val o = json(prefs)
        return o.keys().asSequence().associateWith { Row.fromJson(o.getJSONObject(it)) }
    }

    /** Deletes [tuningKey]'s results for good: it counts from zero again. */
    @Synchronized
    fun clear(prefs: SharedPreferences, tuningKey: String) {
        json(prefs).remove(tuningKey)
        flush(prefs)
    }

    /** A tuning's results put aside ("save and start afresh"), with the time they cover. */
    class Saved(val tuningKey: String, val row: Row)

    /** [tuningKey]'s results move to the saved ones (shown after the others, oldest first); it counts from zero again. */
    @Synchronized
    fun saveAndClear(prefs: SharedPreferences, tuningKey: String) {
        val o = json(prefs)
        val row = o.optJSONObject(tuningKey) ?: return
        val saved = readSavedJson(prefs).put(JSONObject().put("key", tuningKey).put("row", row))
        o.remove(tuningKey)
        prefs.edit().putString(PREF_SAVED_KEY, saved.toString()).apply()
        flush(prefs)
    }

    fun readSaved(prefs: SharedPreferences): List<Saved> {
        val a = readSavedJson(prefs)
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            o.optJSONObject("row")?.let { Saved(o.optString("key"), Row.fromJson(it)) } } }
    }

    private const val PREF_SAVED_KEY = "gesture_stats_saved"

    private fun readSavedJson(prefs: SharedPreferences): org.json.JSONArray =
        try { org.json.JSONArray(prefs.getString(PREF_SAVED_KEY, "[]")!!) } catch (e: Exception) { org.json.JSONArray() }

    /** The tuning with the best score among those tried on enough swipes, or null. */
    fun recommended(rows: Map<String, Row>): String? =
        rows.filterValues { it.swipes >= MIN_SWIPES_FOR_RECOMMENDATION }.maxByOrNull { it.value.score }?.key

    private fun readJson(prefs: SharedPreferences): JSONObject =
        try { JSONObject(prefs.getString(PREF_KEY, "{}")!!) } catch (e: Exception) { JSONObject() }

    private fun update(key: String, change: (Row) -> Unit) {
        val prefs = prefsProvider() ?: return
        try {
            val o = json(prefs)
            val row = o.optJSONObject(key)?.let { Row.fromJson(it) } ?: Row(first = System.currentTimeMillis())
            change(row)
            row.last = System.currentTimeMillis()
            o.put(key, row.toJson())
            scheduleWrite(prefs)
        } catch (e: Exception) {
            Log.w("GestureStats", "could not update swipe statistics", e)
        }
    }
}

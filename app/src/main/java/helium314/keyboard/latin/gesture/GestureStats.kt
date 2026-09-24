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
    ) {
        /** Ranking score: a kept swipe counts fully, a second choice half, a third a quarter, the rest nothing. */
        val score: Float get() = if (swipes == 0) 0f else (kept + 0.5f * pickedSecond + 0.25f * pickedThird) / swipes

        fun toJson(): JSONObject = JSONObject().put("n", swipes).put("kept", kept).put("p2", pickedSecond)
            .put("p3", pickedThird).put("pl", pickedLater).put("del", deleted)

        companion object {
            fun fromJson(o: JSONObject) = Row(o.optInt("n"), o.optInt("kept"), o.optInt("p2"), o.optInt("p3"), o.optInt("pl"), o.optInt("del"))
        }
    }

    private var pendingKey: String? = null

    /** A swipe was decoded under [tuningKey]; an earlier swipe still pending was kept as it came. */
    @Synchronized
    fun onSwipe(tuningKey: String) {
        pendingKey?.let { update(it) { r -> r.swipes++; r.kept++ } }
        pendingKey = tuningKey
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

    fun read(prefs: SharedPreferences): Map<String, Row> {
        val o = readJson(prefs)
        return o.keys().asSequence().associateWith { Row.fromJson(o.getJSONObject(it)) }
    }

    fun clear(prefs: SharedPreferences, tuningKey: String) {
        val o = readJson(prefs)
        o.remove(tuningKey)
        prefs.edit().putString(PREF_KEY, o.toString()).apply()
    }

    /** The tuning with the best score among those tried on enough swipes, or null. */
    fun recommended(rows: Map<String, Row>): String? =
        rows.filterValues { it.swipes >= MIN_SWIPES_FOR_RECOMMENDATION }.maxByOrNull { it.value.score }?.key

    private fun readJson(prefs: SharedPreferences): JSONObject =
        try { JSONObject(prefs.getString(PREF_KEY, "{}")!!) } catch (e: Exception) { JSONObject() }

    private fun update(key: String, change: (Row) -> Unit) {
        val prefs = Settings.getCurrentContext()?.realPrefs() ?: return
        try {
            val o = readJson(prefs)
            val row = o.optJSONObject(key)?.let { Row.fromJson(it) } ?: Row()
            change(row)
            o.put(key, row.toJson())
            prefs.edit().putString(PREF_KEY, o.toString()).apply()
        } catch (e: Exception) {
            Log.w("GestureStats", "could not update swipe statistics", e)
        }
    }
}

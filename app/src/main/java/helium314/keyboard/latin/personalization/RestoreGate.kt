// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

/**
 * Set while a full restore replaces the files under the running keyboard (owner 2026-10-12: "A + B + swipe stats makes
 * sense"): the learned-word stores, the blacklists and the swipe statistics still hold the old device's contents in
 * memory, and a save from them during the restore put those back over the restored files. While it's set, a store that
 * isn't open stays closed (ExpandableBinaryDictionary: its updates and saves do nothing), a user's change to a
 * blacklist isn't kept and no blacklist is written (RemovedWords; since 2026-10-13, owner: until then the changes were
 * held and made again after the restore; the restore's own changes to a list stay pending and are written right after
 * [end], round-7 review 20261013_L4_3), and the swipe statistics aren't written (GestureStats).
 *
 * The restore's own thread ([owner]) still opens the stores it reads and replaces (LearnedStoreMigration.runNow, the
 * per-keyboard update): there, a store that stayed closed would read as empty. Nothing waits for the gate (the restore
 * runs on the KEYBOARD executor, which a waiting load could hold up): what's blocked returns at once.
 */
object RestoreGate {
    @Volatile private var owner: Thread? = null

    /** A full restore is running. */
    @JvmStatic
    val isSet: Boolean get() = owner != null

    /** A full restore is running on another thread: no store opens here now. */
    @JvmStatic
    fun blocks(): Boolean {
        val o = owner
        return o != null && o !== Thread.currentThread()
    }

    /** The full restore starts on this thread. */
    fun begin() { owner = Thread.currentThread() }

    /** The full restore ended (finished or failed). */
    fun end() { owner = null }

    /** Tests: the gate held by [thread] (null: open). */
    internal fun setForTest(thread: Thread?) { owner = thread }
}

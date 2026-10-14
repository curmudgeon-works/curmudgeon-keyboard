// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.settings.ProfilePreferences
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.dialogs.DiscardChangesDialog
import helium314.keyboard.settings.dialogs.SaveChangesDialog
import helium314.keyboard.settings.dialogs.UnsavedChangesDialog
import helium314.keyboard.settings.preferences.BackupRestoreJob
import org.json.JSONObject
import java.io.File

/**
 * What a settings screen's preferences held when it was opened, for the screens that preview on the live keyboard with
 * only preferences in scope (Swipe, Text correction): like [AppearanceDraft], every change applies at once, the tick
 * keeps it and the cross puts this snapshot back. On disk while the screen is open, so changes not accepted are undone
 * when the app is left ([rejectOpen]) or, after a crash, when it starts again ([recoverAfterCrash]).
 */
class PrefsDraft private constructor(private val name: String, private val values: Map<String, Any?>, private val file: File,
                                     private val setId: String) {
    // the set the screen edited (a keyboard's own, or the shared one): crash recovery at app start puts the snapshot
    // back there, not into whatever set the app start reads
    private fun prefs(ctx: Context): android.content.SharedPreferences =
        ProfilePreferences(helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)) { setId }


    /** The preferences whose value differs from the snapshot. */
    fun changedKeys(ctx: Context): Set<String> {
        val all = prefs(ctx).all
        return values.keys.filterTo(HashSet()) { !KnownDefaults.same(it, all[it], values[it]) }
    }

    fun hasChanges(ctx: Context) = changedKeys(ctx).isNotEmpty()

    /** Back to the snapshot; the live keyboard reloads. */
    fun reject(ctx: Context) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("$name draft put back")
        val changed = changedKeys(ctx)
        if (changed.isNotEmpty()) prefs(ctx).edit {
            changed.forEach { key -> values[key].let { if (it == null) remove(key) else KeyboardProfiles.put(this, key, it) } }
        }
        runCatching { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
        discard()
    }

    /** Keeps what is set now; only the snapshot goes. */
    fun accept() = discard()

    private fun discard() {
        file.delete()
        if (active[name] === this) active.remove(name)
    }

    companion object {
        private const val PREFIX = "prefs_draft_"
        private val active = HashMap<String, PrefsDraft>()

        /** The set the settings screen of [ctx] edits (its own keyboard's, see KeyboardScopeContext). */
        fun currentSetId(ctx: Context): String = if (KeyboardProfiles.isSeparate(
            helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx))) KeyboardProfiles.editingId(ctx) else KeyboardProfiles.SHARED

        private fun file(ctx: Context, name: String) = File(ctx.filesDir, "$PREFIX$name.json")

        /** The running draft of screen [name], or a fresh snapshot of [keys]. */
        fun of(ctx: Context, name: String, keys: Collection<String>): PrefsDraft = active[name] ?: run {
            val all = ctx.prefs().all
            val values = keys.associateWith { all[it] }
            val setId = currentSetId(ctx)
            val json = JSONObject().put("name", name).put("set", setId)
            json.put("prefs", JSONObject().also { o -> values.forEach { (k, v) ->
                o.put(k, AppearanceLooks.toJson(v) ?: JSONObject.NULL) } }) // NULL: wasn't set, removed again on reject
            val file = file(ctx, name).apply { writeText(json.toString()) }
            PrefsDraft(name, values, file, setId).also { active[name] = it }
        }

        /** The screen is left with nothing changed: the snapshot goes. */
        fun close(name: String) { active[name]?.discard() }

        /** The app is left with such a screen open: changes not accepted are undone. */
        fun rejectOpen(ctx: Context) { active.values.toList().forEach { it.reject(ctx) } }

        /** The app is left with such a screen open and [leavingKeepsChanges] on: its changes are kept, as with Save. */
        fun acceptOpen() { active.values.toList().forEach { it.accept() } }

        /** The running drafts' pending changes for the log, one each with changes, e.g. "swipe: 2 settings". */
        fun pendingOpen(ctx: Context): List<String> =
            active.values.mapNotNull { d -> d.changedKeys(ctx).size.takeIf { it > 0 }?.let { "${d.name}: $it settings" } }

        /** Whether such a screen is open (its draft running). */
        fun anyOpen(): Boolean = active.isNotEmpty()

        /** Snapshots left on disk by a process that died with the screen open: put them back. Called at app start. */
        fun recoverAfterCrash(ctx: Context) {
            ctx.filesDir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { file ->
                val name = file.name.removePrefix(PREFIX).removeSuffix(".json")
                if (active.containsKey(name)) return@forEach
                runCatching {
                    val json = JSONObject(file.readText())
                    val p = json.getJSONObject("prefs")
                    val values = p.keys().asSequence().associateWith { key -> p.optJSONObject(key)?.let { AppearanceLooks.fromJson(it) } }
                    PrefsDraft(name, values, file, json.optString("set", KeyboardProfiles.SHARED)).reject(ctx)
                }.onFailure { file.delete() }
            }
        }
    }
}

/** Whether a Save/Discard edit is open: Appearance, Layout & Typing, or a screen with a [PrefsDraft]. Backup and restore
 *  and Factory reset are switched off then, and a restore or reset doesn't start (round-6 review 20261013_L4_2, owner
 *  2026-10-13: one check where they start, in place of hiding them from search; a second window, e.g. in split screen,
 *  reached them with an edit open in the first, whose Discard then put the pre-restore snapshot back). */
fun editOpen(): Boolean = AppearanceDraft.activeOrNull() != null || LayoutDraft.isOpen() || PrefsDraft.anyOpen()

/**
 * Whether leaving a main screen with Save and Discard (Appearance, Layout & typing, a screen with a [PrefsDraft]) keeps
 * its changes: Back keeps them without asking, and so does leaving the app; the Save button asks nothing (Discard still
 * asks). Off: Back asks, leaving the app undoes them, Save asks. Owner 2026-10-14: "can it be only for main screen?
 * Sub-items should still discard as per usual? And can this 1 be via a switch in app settings", "can it change the
 * dialog box behaviour -- don't prompt when hitting back, instead just save?", and for the Save button "agree with 1,
 * that's a good idea". The app's switch, not a keyboard's: read from the device's own store.
 */
fun leavingKeepsChanges(ctx: Context): Boolean = helium314.keyboard.latin.utils.DeviceProtectedUtils.getRealSharedPreferences(ctx)
    .getBoolean(helium314.keyboard.latin.settings.Settings.PREF_LEAVING_KEEPS_CHANGES, helium314.keyboard.latin.settings.Defaults.PREF_LEAVING_KEEPS_CHANGES)

/**
 * The editors opened from a main screen that change things at once (the palette editor, Customize popups), each with
 * the undo of its unsaved changes. Leaving the settings runs them before the main screens' changes are kept or undone:
 * an editor's own changes not saved are undone either way (owner 2026-10-14: "Sub-items should still discard as per
 * usual?"; with [leavingKeepsChanges] on, the main screen keeps its changes and no longer undoes the editor's with
 * them). What an editor saved stays. The editor stays open: back in the app it shows them undone. (The layout files
 * editor holds its unsaved text in memory only, written by its Save: nothing to undo.)
 */
object OpenEditors {
    private class Entry(val window: android.app.Activity?, val name: String, val undo: () -> Boolean)
    private val open = LinkedHashMap<Any, Entry>()

    /** Editor [name] opened in [window]; [undo] undoes its unsaved changes, true when it had any. */
    fun register(token: Any, window: android.app.Activity?, name: String, undo: () -> Boolean) { open[token] = Entry(window, name, undo) }
    fun unregister(token: Any) { open.remove(token) }

    /** The settings are left ([reason], for the log): every open editor undoes its unsaved changes. */
    fun undoUnsaved(reason: String) {
        for (editor in open.values.toList()) if (editor.undo())
            helium314.keyboard.latin.utils.SettingsEventLog.log("Unsaved changes undone on leaving ($reason): ${editor.name}", withCaller = false)
    }

    /** Settings window [window] is gone: its editors with it. */
    fun forget(window: android.app.Activity) { open.entries.removeAll { it.value.window === window } }
}

/** [undo], editor [name]'s undo of its unsaved changes (true when it had any), runs when the settings are left with the
 *  editor open ([OpenEditors]). */
@Composable
fun UndoUnsavedOnLeave(name: String, undo: () -> Boolean) {
    val ctx = LocalContext.current
    val latest by androidx.compose.runtime.rememberUpdatedState(undo)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) {
        val token = Any()
        val window = ctx.getActivity()
        OpenEditors.register(token, window, name) { latest() }
        onDispose {
            // (its window closing, not a rotation: still there for the window's onDestroy, which may have to undo it and
            // then forgets the window's editors; once the window is destroyed, nothing will)
            val closing = window != null && !window.isChangingConfigurations && !window.isDestroyed
                && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED
            if (!closing) OpenEditors.unregister(token)
        }
    }
}

/** What a screen with a [PrefsDraft] needs: the pending keys (italic titles), the top bar's cross and tick, and leaving. */
class PrefsDraftUi(val pending: Set<String>, val leave: () -> Unit, val topActions: @Composable RowScope.() -> Unit,
                   val dialogs: @Composable () -> Unit)

/**
 * The tick and cross for a screen whose [keys] apply at once: the screen calls [PrefsDraftUi.leave] for its back arrow,
 * shows [PrefsDraftUi.topActions] in its top bar and [PrefsDraftUi.dialogs] after its content. The screen must recompose
 * on preference changes (they all collect the activity's prefChanged).
 */
@Composable
fun rememberPrefsDraft(name: String, keys: Collection<String>, onClickBack: () -> Unit): PrefsDraftUi {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(PrefsDraft.of(ctx, name, keys)) }
    val pending = draft.changedKeys(ctx)
    var askOnLeave by remember { mutableStateOf(false) }
    var askReject by remember { mutableStateOf(false) }
    var askAccept by remember { mutableStateOf(false) }
    // checked when leaving, not from this composition (the top bar's back arrow can hold an older copy); with
    // "Leaving a screen keeps your changes" on, kept without asking (owner 2026-10-14)
    fun leave() {
        if (!draft.hasChanges(ctx)) { PrefsDraft.close(name); onClickBack() }
        else if (leavingKeepsChanges(ctx)) { draft.accept(); onClickBack() }
        else askOnLeave = true
    }
    // the tick: with the switch on, kept without asking (owner 2026-10-14, "agree with 1, that's a good idea")
    fun save() { draft.accept(); draft = PrefsDraft.of(ctx, name, keys) }
    // back in the app after leaving it (the changes were kept or undone then): a new snapshot of what is there now
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) draft = PrefsDraft.of(ctx, name, keys) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = pending.isNotEmpty()) { leave() }
    return PrefsDraftUi(
        pending = pending,
        leave = ::leave,
        topActions = {
            if (pending.isNotEmpty()) {
                IconButton({ askReject = true }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.appearance_reject)) }
                IconButton({ if (leavingKeepsChanges(ctx)) save() else askAccept = true }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.appearance_accept)) }
            }
        },
        dialogs = {
            if (askReject) DiscardChangesDialog({ askReject = false }) { draft.reject(ctx); draft = PrefsDraft.of(ctx, name, keys) }
            if (askAccept) SaveChangesDialog({ askAccept = false }) { save() }
            if (askOnLeave) UnsavedChangesDialog(
                onKeepWorking = { askOnLeave = false },
                onDiscardAndExit = { draft.reject(ctx); askOnLeave = false; onClickBack() },
                onSaveAndExit = { draft.accept(); askOnLeave = false; onClickBack() },
            )
        },
    )
}

/**
 * While a restore runs (it goes on after its dialog closed), a screen that takes a snapshot when it opens (Appearance,
 * Layout & Typing, the [rememberPrefsDraft] screens) shows "Restore in progress…" under its [title] instead, and opens,
 * its snapshot taken then, once the restore ended (owner 2026-10-12 A5: a snapshot taken mid-restore put a half-restored
 * state back on Discard). True: the waiting screen shows (without a search); the caller shows nothing else and takes no
 * snapshot.
 */
@Composable
fun WaitingForRestore(title: String, onClickBack: () -> Unit): Boolean {
    if (!BackupRestoreJob.restoring) return false
    // no search: there is nothing on it to find (round-7 review 20261013_L10_3, owner 2026-10-14)
    SearchScreen<Any?>(onClickBack = onClickBack, title = { Text(title) }, filteredItems = { emptyList() }, itemContent = { },
        icon = { }) {
        Text(stringResource(R.string.restore_in_progress), modifier = Modifier.padding(16.dp))
    }
    return true
}

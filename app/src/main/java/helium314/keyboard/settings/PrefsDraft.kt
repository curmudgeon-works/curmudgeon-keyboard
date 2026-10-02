// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.KeyboardProfiles
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.dialogs.DiscardChangesDialog
import helium314.keyboard.settings.dialogs.SaveChangesDialog
import helium314.keyboard.settings.dialogs.UnsavedChangesDialog
import org.json.JSONObject
import java.io.File

/**
 * What a settings screen's preferences held when it was opened, for the screens that preview on the live keyboard with
 * only preferences in scope (Swipe, Text correction): like [AppearanceDraft], every change applies at once, the tick
 * keeps it and the cross puts this snapshot back. On disk while the screen is open, so changes not accepted are undone
 * when the app is left ([rejectOpen]) or, after a crash, when it starts again ([recoverAfterCrash]).
 */
class PrefsDraft private constructor(private val name: String, private val values: Map<String, Any?>, private val file: File) {

    /** The preferences whose value differs from the snapshot. */
    fun changedKeys(ctx: Context): Set<String> {
        val all = ctx.prefs().all
        return values.keys.filterTo(HashSet()) { !KnownDefaults.same(it, all[it], values[it]) }
    }

    fun hasChanges(ctx: Context) = changedKeys(ctx).isNotEmpty()

    /** Back to the snapshot; the live keyboard reloads. */
    fun reject(ctx: Context) {
        helium314.keyboard.latin.utils.SettingsEventLog.log("$name draft put back")
        val changed = changedKeys(ctx)
        if (changed.isNotEmpty()) ctx.prefs().edit {
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

        private fun file(ctx: Context, name: String) = File(ctx.filesDir, "$PREFIX$name.json")

        /** The running draft of screen [name], or a fresh snapshot of [keys]. */
        fun of(ctx: Context, name: String, keys: Collection<String>): PrefsDraft = active[name] ?: run {
            val all = ctx.prefs().all
            val values = keys.associateWith { all[it] }
            val json = JSONObject().put("name", name)
            json.put("prefs", JSONObject().also { o -> values.forEach { (k, v) ->
                o.put(k, AppearanceLooks.toJson(v) ?: JSONObject.NULL) } }) // NULL: wasn't set, removed again on reject
            val file = file(ctx, name).apply { writeText(json.toString()) }
            PrefsDraft(name, values, file).also { active[name] = it }
        }

        /** The screen is left with nothing changed: the snapshot goes. */
        fun close(name: String) { active[name]?.discard() }

        /** The app is left with such a screen open: changes not accepted are undone. */
        fun rejectOpen(ctx: Context) { active.values.toList().forEach { it.reject(ctx) } }

        /** Snapshots left on disk by a process that died with the screen open: put them back. Called at app start. */
        fun recoverAfterCrash(ctx: Context) {
            ctx.filesDir.listFiles { f -> f.name.startsWith(PREFIX) }?.forEach { file ->
                val name = file.name.removePrefix(PREFIX).removeSuffix(".json")
                if (active.containsKey(name)) return@forEach
                runCatching {
                    val p = JSONObject(file.readText()).getJSONObject("prefs")
                    val values = p.keys().asSequence().associateWith { key -> p.optJSONObject(key)?.let { AppearanceLooks.fromJson(it) } }
                    PrefsDraft(name, values, file).reject(ctx)
                }.onFailure { file.delete() }
            }
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
    // checked when leaving, not from this composition (the top bar's back arrow can hold an older copy)
    fun leave() { if (draft.hasChanges(ctx)) askOnLeave = true else { PrefsDraft.close(name); onClickBack() } }
    // back in the app after leaving it (the changes were undone then): a new snapshot of what is there now
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
                IconButton({ askAccept = true }) { Icon(painterResource(R.drawable.ic_check), stringResource(R.string.appearance_accept)) }
            }
        },
        dialogs = {
            if (askReject) DiscardChangesDialog({ askReject = false }) { draft.reject(ctx); draft = PrefsDraft.of(ctx, name, keys) }
            if (askAccept) SaveChangesDialog({ askAccept = false }) { draft.accept(); draft = PrefsDraft.of(ctx, name, keys) }
            if (askOnLeave) UnsavedChangesDialog(
                onKeepWorking = { askOnLeave = false },
                onDiscardAndExit = { draft.reject(ctx); askOnLeave = false; onClickBack() },
                onSaveAndExit = { draft.accept(); askOnLeave = false; onClickBack() },
            )
        },
    )
}

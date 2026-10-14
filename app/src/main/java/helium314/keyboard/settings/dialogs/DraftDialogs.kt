// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R

// The Save / Discard dialogs of the screens that preview on the live keyboard (Appearance, Layout & Typing):
// the same wording and buttons on both, and they don't bring the preview keyboard up.

/** What a Discard also undoes, the sub-screen saves since the screen opened ([alsoUndoes]: their names as shown), as the
 *  line under the question ([res]: its text with the names); none: no line. */
@Composable
private fun alsoUndoesLine(res: Int, alsoUndoes: List<String>): (@Composable () -> Unit)? =
    if (alsoUndoes.isEmpty()) null else { { Text(stringResource(res, alsoUndoes.joinToString(" · "))) } }

/** The names of [palettes] (stored names) for that line, as the palette list shows them. */
fun savedPaletteNames(ctx: android.content.Context, palettes: List<String>): List<String> =
    palettes.map { ctx.getString(R.string.discard_also_undoes_palette, helium314.keyboard.keyboard.KeyboardTheme.paletteName(ctx, it)) }

/** The names of the Layout & Typing sub-screens [saved] for that line, as their rows show them. */
fun savedSubScreenNames(ctx: android.content.Context, saved: List<helium314.keyboard.settings.LayoutDraft.SubScreen>): List<String> =
    saved.map { ctx.getString(when (it) {
        helium314.keyboard.settings.LayoutDraft.SubScreen.POPUPS -> R.string.key_popups_full
        helium314.keyboard.settings.LayoutDraft.SubScreen.LAYOUT_FILES -> R.string.layout_files
    }) }

/** The top bar's cross: undo everything changed since the screen opened, [alsoUndoes] (sub-screen saves) named. */
@Composable
fun DiscardChangesDialog(onDismissRequest: () -> Unit, alsoUndoes: List<String> = emptyList(), onDiscard: () -> Unit) = NoPreview {
    ConfirmationDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.appearance_reject_title)) },
        content = alsoUndoesLine(R.string.discard_also_undoes, alsoUndoes),
        cancelButtonText = stringResource(R.string.appearance_keep_working),
        confirmButtonText = stringResource(R.string.appearance_discard_all),
        onConfirmed = onDiscard,
    )
}

/** The top bar's tick: keep everything changed since the screen opened. */
@Composable
fun SaveChangesDialog(onDismissRequest: () -> Unit, onSave: () -> Unit) = NoPreview {
    ConfirmationDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.appearance_accept_title)) },
        cancelButtonText = stringResource(R.string.appearance_keep_working),
        confirmButtonText = stringResource(R.string.appearance_accept_all),
        onConfirmed = onSave,
    )
}

/** Leaving the screen with changes: keep working (left; also a tap outside), or discard / save and leave (right);
 *  [alsoUndoes]: the sub-screen saves a Discard and exit undoes too, named. */
@Composable
fun UnsavedChangesDialog(onKeepWorking: () -> Unit, onDiscardAndExit: () -> Unit, onSaveAndExit: () -> Unit,
                         alsoUndoes: List<String> = emptyList()) = NoPreview {
    ThreeButtonAlertDialog(
        onDismissRequest = onKeepWorking,
        title = { Text(stringResource(R.string.unsaved_changes_title)) },
        content = alsoUndoesLine(R.string.discard_and_exit_also_undoes, alsoUndoes),
        neutralButtonText = stringResource(R.string.appearance_keep_working),
        onNeutral = onKeepWorking,
        cancelButtonText = stringResource(R.string.discard_and_exit),
        onCancel = onDiscardAndExit,
        confirmButtonText = stringResource(R.string.save_and_exit),
        onConfirmed = onSaveAndExit,
    )
}

@Composable
private fun NoPreview(content: @Composable () -> Unit) {
    // the preview keyboard goes down with the question and doesn't come back after it (Save: the try-it box got the
    // focus back and the keyboard with it; Discard: the settings put back counted as changes) until a next touch
    val preview = LocalPreviewKeyboard.current ?: helium314.keyboard.settings.screens.PreviewKeyboard.latest?.get()
    androidx.compose.runtime.DisposableEffect(Unit) { preview?.quiet(); onDispose { preview?.quiet() } }
    CompositionLocalProvider(LocalKeepKeyboard provides false, content = content)
}

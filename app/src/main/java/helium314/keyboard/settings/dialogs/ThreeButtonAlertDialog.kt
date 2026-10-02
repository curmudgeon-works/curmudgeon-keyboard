/*
 * Copyright (C) 2021 The Android Open Source Project
 * parts taken from Material3 AlertDialog.kt
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.settings.dialogs

import helium314.keyboard.settings.SettingsActivity

import androidx.compose.runtime.getValue

import androidx.compose.runtime.rememberUpdatedState


import helium314.keyboard.latin.utils.getActivity

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.LocalContext

import android.view.ViewTreeObserver

import androidx.core.view.WindowInsetsCompat

import androidx.core.view.ViewCompat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark

/** True on screens whose live keyboard is the preview: their dialogs keep it up unless they need focus for a text field. */
val LocalKeepKeyboard = compositionLocalOf { false }

/** Where (window y) a screen's try-it bar begins: keyboard-keeping dialogs end above it. -1 = none, end above the keyboard. */
val LocalBottomBarTop = compositionLocalOf { -1 }

/** A screen's preview keyboard, told when a keyboard-keeping dialog opens and closes (see PreviewKeyboard). */
val LocalPreviewKeyboard = compositionLocalOf<PreviewKeyboardHooks?> { null }

interface PreviewKeyboardHooks {
    fun dialogOpened(emoji: Boolean, people: Boolean)
    fun dialogClosed()
}

/** True around settings about emojis: their dialogs preview on the emoji panel instead of the letters. */
val LocalPreviewEmoji = compositionLocalOf { false }

/** True around the skin tone setting: its preview is the emoji panel's people page. */
val LocalPreviewEmojiPeople = compositionLocalOf { false }

@Composable
fun ThreeButtonAlertDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
    scrollContent: Boolean = false,
    onNeutral: () -> Unit = { },
    onCancel: (() -> Unit)? = null, // the cancel button's own action (default: dismiss, like a tap outside)
    checkOk: () -> Boolean = { true },
    confirmButtonText: String? = stringResource(android.R.string.ok),
    cancelButtonText: String = stringResource(android.R.string.cancel),
    neutralButtonText: String? = null,
    reducePadding: Boolean = false,
    properties: DialogProperties = DialogProperties(),
    confirmFirst: Boolean = false, // OK left of Cancel, for dialogs whose buttons are spelled out as "Ok: …" / "Cancel: …"
    keepKeyboard: Boolean = LocalKeepKeyboard.current, // the keyboard stays up (the dialog takes no focus) and the dialog sits at the top, clear of it
    summonKeyboard: Boolean = true, // with [keepKeyboard]: the screen's preview keyboard comes up for this dialog (off when the dialog previews itself)
) {
    // the preview keyboard is used while its dialog is open (typing, emoji tabs): a tap on it mustn't close the
    // dialog. The platform's outside-tap closing is off; the dialog closes itself on a tap on the settings screen
    // (see below), while the keyboard, drawn above the dialog, gets its own taps and never reaches it
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = if (!keepKeyboard) properties else DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = properties.usePlatformDefaultWidth,
        )
    ) {
        if (keepKeyboard) {
            val preview = if (summonKeyboard) LocalPreviewKeyboard.current else null
            val emoji = LocalPreviewEmoji.current
            val people = LocalPreviewEmojiPeople.current
            DisposableEffect(preview) {
                preview?.dialogOpened(emoji, people)
                onDispose { preview?.dialogClosed() }
            }
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                // the dialog stays focusable (back and outside taps work as usual) but tells the system it has no use
                // for the keyboard, so the one below stays up; no dim, the keyboard is the preview
                window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            // touches outside the dialog go to the window under them: the keyboard gets its taps (typing, emoji tabs);
            // the settings screen, while this dialog is open, turns a tap into closing the dialog
            val currentDismiss by rememberUpdatedState(onDismissRequest)
            val activity = LocalContext.current.getActivity() as? SettingsActivity
            DisposableEffect(window, activity) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                val previous = activity?.outsideTapHandler
                val handler: () -> Unit = { currentDismiss() }
                activity?.outsideTapHandler = handler
                onDispose { if (activity?.outsideTapHandler === handler) activity.outsideTapHandler = previous }
            }
            // placed right above the keyboard (and the toolbar); on a small screen it may overlap the keyboard, but
            // its top stays below the screen's header. Re-placed whenever the keyboard or the dialog changes size.
            val activityDecor = LocalContext.current.getActivity()?.window?.decorView
            val headerHeight = with(LocalDensity.current) { 64.dp.roundToPx() }
            val barTop = LocalBottomBarTop.current
            DisposableEffect(window, activityDecor, barTop) {
                val dialogDecor = window?.decorView
                fun place() {
                    if (window == null || activityDecor == null || dialogDecor == null) return
                    val insets = ViewCompat.getRootWindowInsets(activityDecor) ?: return
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val headerBottom = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + headerHeight
                    // the dialog's bottom edge: at the try-it bar's top when the screen has one, else just above the keyboard
                    val bottomLimit = if (barTop > 0) barTop else activityDecor.height - ime
                    val room = (activityDecor.height - headerBottom - dialogDecor.height).coerceAtLeast(0)
                    val y = minOf(activityDecor.height - bottomLimit, room).coerceAtLeast(0)
                    val params = window.attributes
                    if (params.gravity != Gravity.BOTTOM || params.y != y) {
                        params.gravity = Gravity.BOTTOM
                        params.y = y
                        window.attributes = params
                    }
                }
                val listener = ViewTreeObserver.OnGlobalLayoutListener { place() }
                activityDecor?.viewTreeObserver?.addOnGlobalLayoutListener(listener)
                dialogDecor?.viewTreeObserver?.addOnGlobalLayoutListener(listener)
                place()
                onDispose {
                    activityDecor?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
                    dialogDecor?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
                }
            }
        }
        Box(
            modifier = modifier.widthIn(min = 280.dp, max = 560.dp),
            propagateMinConstraints = true
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                contentColor = contentColorFor(MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(
                    start = if (reducePadding) 8.dp else 16.dp,
                    end = if (reducePadding) 8.dp else 16.dp,
                    top = if (reducePadding) 8.dp else 16.dp,
                    bottom = if (reducePadding) 2.dp else 6.dp
                )) {
                    title?.let {
                        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleMedium) {
                            Box(Modifier.padding(PaddingValues(bottom = if (reducePadding) 4.dp else 16.dp))) {
                                title()
                            }
                        }
                    }
                    content?.let {
                        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                            if (scrollContent) {
                                val scrollState = rememberScrollState()
                                Box(Modifier
                                    .weight(weight = 1f, fill = false)
                                    .padding(bottom = if (reducePadding) 2.dp else 8.dp)
                                    .verticalScroll(scrollState)
                                ) {
                                    content()
                                }
                            } else {
                                Box(Modifier.weight(weight = 1f, fill = false).padding(bottom = if (reducePadding) 2.dp else 8.dp)) {
                                    content()
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.End) {
                        if (neutralButtonText != null)
                            TextButton(
                                onClick = onNeutral
                            ) { Text(neutralButtonText) }
                        Spacer(Modifier.weight(1f))
                        val confirm: @Composable () -> Unit = {
                            if (confirmButtonText != null)
                                TextButton(
                                    enabled = checkOk(),
                                    onClick = { onConfirmed(); onDismissRequest() },
                                ) { Text(confirmButtonText) }
                        }
                        if (confirmFirst) confirm()
                        TextButton(onClick = onCancel ?: onDismissRequest) { Text(cancelButtonText) }
                        if (!confirmFirst) confirm()
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        ThreeButtonAlertDialog(
            onDismissRequest = {},
            onConfirmed = { },
            content = { Text("hello") },
            title = { Text("title") },
            neutralButtonText = "Default"
        )
    }
}

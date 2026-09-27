// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.app.AlertDialog
import android.os.IBinder
import android.view.WindowManager
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R

/** Asks before the clipboard history is cleared; attached to the keyboard window like the input method picker. */
fun createClearClipboardDialog(latinIme: LatinIME, windowToken: IBinder): AlertDialog {
    val dialog = AlertDialog.Builder(getPlatformDialogThemeContext(latinIme))
        .setTitle(R.string.clear_clipboard)
        .setMessage(R.string.clear_clipboard_message)
        .setPositiveButton(R.string.clear_clipboard_confirm) { _, _ -> latinIme.clipboardHistoryManager.clearHistory() }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    val window = dialog.window
    val layoutParams = window?.attributes
    layoutParams?.token = windowToken
    layoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
    window?.attributes = layoutParams
    window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    return dialog
}

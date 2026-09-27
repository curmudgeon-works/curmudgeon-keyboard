// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import kotlin.math.roundToInt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.DropDownField
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.WithSmallTitle
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.io.File

/** The preferences behind one text style dialog: the key labels, or the symbols (hints) on the keys. */
class TextStyleKeys(
    val font: String,
    val fontFile: (Context) -> File,
    val size: String,
    val sizeDefault: Float,
    val sizeRange: ClosedFloatingPointRange<Float>,
    val bold: String,
    val boldDefault: Boolean,
    val italic: String,
    val underline: String,
    val sizeIsInt: Boolean = false, // stored as an Int (dp) rather than a Float scale
    val sizeText: (Float) -> String = { "${(it * 100).toInt()}%" },
    val extraKeys: List<String> = emptyList(), // more preferences the extra rows change, restored on Cancel too
    val extra: (@Composable (reload: () -> Unit) -> Unit)? = null, // rows under B I U
)

/** Font choices; "auto" (nothing chosen yet) means the loaded file when there is one, else the default. */
object TextFonts {
    const val AUTO = "auto"
    const val DEFAULT = "default"
    const val SANS = "sans"
    const val SERIF = "serif"
    const val MONO = "mono"
    const val FILE = "file"
    val choices = listOf(DEFAULT, SANS, SERIF, MONO, FILE)

    /** The choice as the dialog shows it: "auto" resolved to the file or the default. */
    fun shown(stored: String, fileExists: Boolean) = if (stored == AUTO) (if (fileExists) FILE else DEFAULT) else stored
}

/**
 * One tile, one dialog, like a text editor's: a font drop-down (the default, three system families, or a font file),
 * a size slider (applied when let go, a keyboard rebuild per drag step flickers), and bold / italic / underline
 * toggles. Every change shows on the live keyboard; OK keeps, Cancel puts back what was set when it opened.
 */
@Composable
fun TextStylePreference(setting: Setting, keys: TextStyleKeys) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") generation
    val fileExists = keys.fontFile(ctx).exists()
    val font = TextFonts.shown(prefs.getString(keys.font, TextFonts.AUTO)!!, fileExists)
    val size = if (keys.sizeIsInt) prefs.getInt(keys.size, keys.sizeDefault.toInt()).toFloat() else prefs.getFloat(keys.size, keys.sizeDefault)
    val bold = prefs.getBoolean(keys.bold, keys.boldDefault)
    val italic = prefs.getBoolean(keys.italic, false)
    val underline = prefs.getBoolean(keys.underline, false)
    val summary = listOfNotNull(fontName(font), keys.sizeText(size),
        listOfNotNull("B".takeIf { bold }, "I".takeIf { italic }, "U".takeIf { underline }).joinToString(" ").ifEmpty { null })
        .joinToString(" · ")
    Preference(name = setting.title, description = summary, onClick = { showDialog = true }) { }
    if (!showDialog) return

    fun reload() {
        KeyboardTypeface.clearCache()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        generation++
    }
    val allKeys = listOf(keys.font, keys.size, keys.bold, keys.italic, keys.underline) + keys.extraKeys
    val initial = remember { allKeys.associateWith { prefs.all[it] } }
    var confirmed by remember { mutableStateOf(false) }
    var sizePosition by remember { mutableFloatStateOf(size) }
    var showError by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        val tempFile = File(DeviceProtectedUtils.getFilesDir(ctx), "temp_file")
        FileUtils.copyContentUriToNewFile(uri, ctx, tempFile)
        try {
            Typeface.createFromFile(tempFile)
            keys.fontFile(ctx).delete()
            tempFile.renameTo(keys.fontFile(ctx))
            prefs.edit { putString(keys.font, TextFonts.FILE) }
            reload()
        } catch (_: Exception) {
            showError = true
            tempFile.delete()
        }
    }
    val pickFile = { launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")) }

    ThreeButtonAlertDialog(
        onDismissRequest = {
            if (!confirmed && allKeys.any { prefs.all[it] != initial[it] }) {
                prefs.edit { initial.forEach { (key, value) -> when (value) {
                    null -> remove(key)
                    is String -> putString(key, value)
                    is Float -> putFloat(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                } } }
                reload()
            }
            showDialog = false
        },
        onConfirmed = { confirmed = true },
        title = { Text(setting.title) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WithSmallTitle(stringResource(R.string.text_style_font)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DropDownField(
                                items = TextFonts.choices,
                                selectedItem = font,
                                onSelected = { choice ->
                                    if (choice == TextFonts.FILE && !fileExists) pickFile()
                                    else { prefs.edit { putString(keys.font, choice) }; reload() }
                                },
                            ) { Text(fontName(it)) }
                        }
                        if (font == TextFonts.FILE) TextButton(onClick = pickFile) { Text(stringResource(R.string.load)) }
                    }
                }
                WithSmallTitle(stringResource(R.string.text_style_size, keys.sizeText(sizePosition))) {
                    Slider(
                        value = sizePosition,
                        onValueChange = { sizePosition = it },
                        onValueChangeFinished = {
                            prefs.edit { if (keys.sizeIsInt) putInt(keys.size, sizePosition.roundToInt()) else putFloat(keys.size, sizePosition) }
                            reload()
                        },
                        valueRange = keys.sizeRange,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    FilledIconToggleButton(checked = bold, onCheckedChange = { prefs.edit { putBoolean(keys.bold, it) }; reload() }) {
                        Text("B", fontWeight = FontWeight.Bold)
                    }
                    FilledIconToggleButton(checked = italic, onCheckedChange = { prefs.edit { putBoolean(keys.italic, it) }; reload() }) {
                        Text("I", fontStyle = FontStyle.Italic)
                    }
                    FilledIconToggleButton(checked = underline, onCheckedChange = { prefs.edit { putBoolean(keys.underline, it) }; reload() }) {
                        Text("U", textDecoration = TextDecoration.Underline)
                    }
                }
                keys.extra?.invoke(::reload)
            }
        },
    )
    if (showError)
        InfoDialog(stringResource(R.string.file_read_error)) { showError = false }
}

@Composable
private fun fontName(font: String): String = stringResource(when (font) {
    TextFonts.SANS -> R.string.text_font_sans
    TextFonts.SERIF -> R.string.text_font_serif
    TextFonts.MONO -> R.string.text_font_mono
    TextFonts.FILE -> R.string.text_font_file
    else -> R.string.text_font_default
})

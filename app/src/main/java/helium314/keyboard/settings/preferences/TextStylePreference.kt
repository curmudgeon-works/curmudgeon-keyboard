// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import helium314.keyboard.settings.rememberPrefSnapshot
import kotlin.math.roundToInt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.OpenableColumns
import androidx.compose.material3.MaterialTheme
import helium314.keyboard.keyboard.FontLibrary
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
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
import helium314.keyboard.latin.utils.DeleteButton
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.DropDownField
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.WithSmallTitle
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.io.File

/** The preferences behind one text style dialog: the key labels, the symbols (hints) on the keys, or the suggestions. */
class TextStyleKeys(
    val font: String,
    val slot: String, // FontLibrary.SLOT_…: which file of before a stored "file" means
    val size: String,
    val sizeDefault: Float,
    val sizeRange: ClosedFloatingPointRange<Float>,
    val bold: String,
    val boldDefault: (SharedPreferences) -> Boolean,
    val italic: String,
    val underline: String,
    val sizeIsInt: Boolean = false, // stored as an Int (dp) rather than a Float scale
    val sizeText: (Float) -> String = { "${(it * 100).toInt()}%" },
    val extraKeys: List<String> = emptyList(), // more preferences the extra rows change, restored on Cancel too
    val extra: (@Composable (reload: () -> Unit) -> Unit)? = null, // rows under B I U
    // symbols and suggestions: the other one's font preference. While "use key text font" is on they show the key
    // text's font; picking one here turns that off, and the other one keeps the key text's font as its own choice
    val otherFont: String? = null,
)

/** Font choices: the default, three system families, the loaded fonts ("font:<name>"), and loading a new one. */
object TextFonts {
    const val AUTO = "auto" // nothing chosen yet
    const val DEFAULT = "default"
    const val SANS = "sans"
    const val SERIF = "serif"
    const val MONO = "mono"
    const val LOAD = "load"
    val system = listOf(DEFAULT, SANS, SERIF, MONO)

    /** The choice as the dialog shows it: a stored "auto" / "file" resolved to the file it means, or the default. */
    fun shown(ctx: Context, stored: String, slot: String): String = when (stored) {
        AUTO, "file" -> FontLibrary.fileFor(ctx, stored, slot)?.let { FontLibrary.PREFIX + it.name } ?: DEFAULT
        else -> if (stored.startsWith(FontLibrary.PREFIX) && FontLibrary.fileFor(ctx, stored, slot) == null) DEFAULT else stored
    }
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
    val follows = keys.otherFont != null && prefs.getBoolean(Settings.PREF_FONT_FOLLOWS_KEY_TEXT, Defaults.PREF_FONT_FOLLOWS_KEY_TEXT)
    val keyFont = prefs.getString(Settings.PREF_KEY_FONT, TextFonts.AUTO)!!
    val font = if (follows) TextFonts.shown(ctx, keyFont, FontLibrary.SLOT_KEY)
        else TextFonts.shown(ctx, prefs.getString(keys.font, TextFonts.AUTO)!!, keys.slot)
    val size = if (keys.sizeIsInt) prefs.getInt(keys.size, keys.sizeDefault.toInt()).toFloat() else prefs.getFloat(keys.size, keys.sizeDefault)
    val bold = prefs.getBoolean(keys.bold, keys.boldDefault(prefs))
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
    val allKeys = listOf(keys.font, keys.size, keys.bold, keys.italic, keys.underline) + keys.extraKeys +
        listOfNotNull(keys.otherFont, keys.otherFont?.let { Settings.PREF_FONT_FOLLOWS_KEY_TEXT })
    val snapshot = rememberPrefSnapshot(prefs, allKeys)
    var confirmed by remember { mutableStateOf(false) }
    var sizePosition by remember { mutableFloatStateOf(size) }
    var showError by remember { mutableStateOf(false) }
    fun choose(choice: String) {
        prefs.edit {
            if (follows) {
                putBoolean(Settings.PREF_FONT_FOLLOWS_KEY_TEXT, false)
                putString(keys.otherFont!!, keyFont) // the other one looks as before
            }
            putString(keys.font, choice)
        }
        if (!FontLibrary.isPending(choice)) FontLibrary.discardPending(ctx)
        reload()
    }
    // a loaded file previews at once but joins the font list only on OK
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        val tempFile = File(DeviceProtectedUtils.getFilesDir(ctx), "temp_file")
        FileUtils.copyContentUriToNewFile(uri, ctx, tempFile)
        try {
            Typeface.createFromFile(tempFile)
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "font"
            choose(FontLibrary.addPending(ctx, tempFile, name))
        } catch (_: Exception) {
            showError = true
        } finally {
            tempFile.delete()
        }
    }
    val pickFile = { launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")) }
    val choices = TextFonts.system + FontLibrary.names(ctx).map { FontLibrary.PREFIX + it } +
        listOfNotNull(font.takeIf { FontLibrary.isPending(it) }) + TextFonts.LOAD

    ThreeButtonAlertDialog(
        onDismissRequest = {
            if (!confirmed && snapshot.restore()) reload()
            FontLibrary.discardPending(ctx) // after OK it's in the list already
            showDialog = false
        },
        onConfirmed = {
            confirmed = true
            val chosen = prefs.getString(keys.font, TextFonts.AUTO)!!
            if (FontLibrary.isPending(chosen)) { prefs.edit { putString(keys.font, FontLibrary.commit(ctx, chosen)) }; reload() }
        },
        title = { Text(setting.title) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WithSmallTitle(stringResource(R.string.text_style_font)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DropDownField(
                                items = choices,
                                selectedItem = font,
                                onSelected = { choice -> if (choice == TextFonts.LOAD) pickFile() else choose(choice) },
                                itemTrailing = { choice ->
                                    if (choice.startsWith(FontLibrary.PREFIX) && !FontLibrary.isPending(choice))
                                        DeleteButton { FontLibrary.delete(ctx, FontLibrary.displayName(choice)); reload() }
                                },
                            ) { Text(fontName(it)) }
                            if (follows) Text(stringResource(R.string.text_font_follows_key_text),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp))
                        }
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
private fun fontName(font: String): String = when {
    font == TextFonts.LOAD -> stringResource(R.string.text_font_load)
    FontLibrary.isPending(font) -> stringResource(R.string.text_font_new, FontLibrary.displayName(font))
    font.startsWith(FontLibrary.PREFIX) -> FontLibrary.displayName(font)
    else -> stringResource(when (font) {
        TextFonts.SANS -> R.string.text_font_sans
        TextFonts.SERIF -> R.string.text_font_serif
        TextFonts.MONO -> R.string.text_font_mono
        else -> R.string.text_font_default
    })
}

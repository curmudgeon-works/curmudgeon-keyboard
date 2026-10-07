// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.app.Activity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
import helium314.keyboard.latin.common.PictureLibrary
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.common.PictureFraming
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.AppearanceLooks
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The background picture row: no picture yet → choose one; then (and on every later tap) the framing dialog. */
@Composable
fun BackgroundImagePref(setting: Setting, isLandscape: Boolean) {
    var showDayNightDialog by rememberSaveable { mutableStateOf(false) }
    var showFramingDialog by rememberSaveable { mutableStateOf(false) }
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    var isNight by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    fun getFile() = Settings.getCustomBackgroundFile(ctx, isNight, isLandscape)
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) // necessary to reload dayNightPref
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val dayNightPref = ctx.prefs().getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
    if (!dayNightPref)
        isNight = false
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        showDayNightDialog = false
        scope.launch(Dispatchers.IO) {
            val ok = setBackgroundImage(ctx, uri, isNight, isLandscape)
            withContext(Dispatchers.Main) {
                AppearanceLooks.reload(ctx)
                if (ok) showFramingDialog = true // a new picture: place it
                else showErrorDialog = true
            }
        }
    }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("image/*")
    // a picture already loaded (on any keyboard) or a new one from the gallery; straight to the gallery while there are none
    var showPicker by rememberSaveable { mutableStateOf(false) }
    fun pickPicture() { if (PictureLibrary.list(ctx).isEmpty()) launcher.launch(intent) else showPicker = true }
    fun open() {
        if (getFile().exists()) showFramingDialog = true
        else pickPicture()
    }
    Preference(
        name = setting.title,
        onClick = {
            if (dayNightPref) showDayNightDialog = true
            else open()
        }
    )
    if (showDayNightDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDayNightDialog = false },
            onConfirmed = {
                isNight = false
                open()
            },
            confirmButtonText = stringResource(R.string.day_or_night_day),
            cancelButtonText = "",
            onNeutral = {
                isNight = true
                open()
            },
            neutralButtonText = stringResource(R.string.day_or_night_night),
            title = { Text(stringResource(R.string.day_or_night_image)) },
        )
    }
    if (showFramingDialog) {
        val title = setting.title + if (dayNightPref) " · " + stringResource(if (isNight) R.string.day_or_night_night else R.string.day_or_night_day) else ""
        PictureFramingDialog(
            picture = getFile(),
            isLandscape = isLandscape,
            title = title,
            onDismiss = { showFramingDialog = false },
            onOtherPicture = { showFramingDialog = false; pickPicture() },
            onRemove = {
                getFile().delete()
                PictureFraming.fileFor(getFile()).delete()
                AppearanceLooks.reload(ctx)
                showFramingDialog = false
            },
        )
    }
    if (showErrorDialog) {
        InfoDialog(stringResource(R.string.file_read_error)) { showErrorDialog = false }
    }
    if (showPicker) {
        PicturePickerDialog(
            onDismiss = { showPicker = false },
            onGallery = { showPicker = false; launcher.launch(intent) },
            onPicked = { picture ->
                showPicker = false
                // this keyboard's own copy, placed afresh (framed independently of the other keyboards)
                scope.launch(Dispatchers.IO) {
                    val ok = runCatching {
                        val live = getFile()
                        picture.copyTo(live, overwrite = true)
                        PictureFraming.fileFor(live).delete()
                        picture.setLastModified(System.currentTimeMillis())
                        Settings.clearCachedBackgroundImages()
                    }.isSuccess
                    withContext(Dispatchers.Main) {
                        AppearanceLooks.reload(ctx)
                        if (ok) showFramingDialog = true else showErrorDialog = true
                    }
                }
            },
        )
    }
}

/** The pictures loaded so far (shared by all keyboards) to choose from, or a new one from the gallery; a long press
 *  removes one from the list. */
@Composable
private fun PicturePickerDialog(onDismiss: () -> Unit, onGallery: () -> Unit, onPicked: (File) -> Unit) {
    val ctx = LocalContext.current
    var generation by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val pictures = remember(generation) { PictureLibrary.list(ctx) }
    var toForget by remember { mutableStateOf<File?>(null) }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
        onConfirmed = onGallery,
        confirmButtonText = stringResource(R.string.background_picture_gallery),
        title = { Text(stringResource(R.string.background_picture_choose)) },
        content = {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                items(pictures.size, key = { pictures[it].name }) { i ->
                    val picture = pictures[i]
                    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, picture) {
                        value = withContext(Dispatchers.IO) {
                            runCatching<androidx.compose.ui.graphics.ImageBitmap?> { PictureFraming.decode(picture, 300)?.asImageBitmap() }.getOrNull()
                        }
                    }
                    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { onPicked(picture) }, onLongClick = { toForget = picture })) {
                        thumb?.let {
                            androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)))
                        }
                    }
                }
            }
        },
    )
    toForget?.let { picture ->
        val themes = helium314.keyboard.settings.AppearanceLooks.looksUsingPicture(ctx.prefs(), picture.name)
        ConfirmationDialog(
            onDismissRequest = { toForget = null },
            onConfirmed = { PictureLibrary.remove(picture); toForget = null; generation++ },
            content = { Text(stringResource(R.string.background_picture_forget) +
                if (themes > 0) " " + pluralStringResource(R.plurals.background_picture_forget_themes, themes, themes) else "") },
        )
    }
}

/**
 * The picture in a frame shaped like the keyboard: pinch to zoom, drag to move (zoomed out as far as the whole picture,
 * the rest is the theme's background colour), or stretched onto the keyboard. Each change shows on the preview
 * keyboard below after a moment; Cancel puts the framing back as it was.
 */
@Composable
private fun PictureFramingDialog(
    picture: File,
    isLandscape: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onOtherPicture: () -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    val framingFile = PictureFraming.fileFor(picture)
    // (saved across rotation: review 2026-10-06 Low, Cancel after a rotation put back the framing of that moment)
    val beforeSaved = rememberSaveable(picture) { framingFile.takeIf { it.exists() }?.readText() ?: NO_FRAMING }
    val before = beforeSaved.takeIf { it != NO_FRAMING }
    val initial = remember(picture) { PictureFraming.read(picture) }
    var framing by remember(picture) { mutableStateOf(initial) }
    val image = remember(picture) { PictureFraming.decode(picture, 2048)?.asImageBitmap() }
    val band = remember { KeyboardTheme.getColorsForCurrentTheme(ctx).get(ColorType.MAIN_BACKGROUND) }
    val aspect = remember(isLandscape) { keyboardAspect(ctx, isLandscape) }

    var applied by remember(picture) { mutableStateOf(initial) } // what the keyboard shows
    var saved by remember(picture) { mutableStateOf(false) }
    fun apply(f: PictureFraming) {
        if (f == applied) return
        f.write(framingFile)
        applied = f
        AppearanceLooks.reload(ctx)
    }
    // the preview keyboard follows once the fingers rest (a reload per move would flicker)
    LaunchedEffect(framing) {
        if (framing == applied) return@LaunchedEffect
        delay(300)
        apply(framing)
    }

    ThreeButtonAlertDialog(
        // Cancel, back or a tap outside: the framing as it was
        onDismissRequest = {
            if (!saved && applied != initial) {
                if (before == null) framingFile.delete() else framingFile.writeText(before)
                AppearanceLooks.reload(ctx)
            }
            onDismiss()
        },
        onConfirmed = { apply(framing); saved = true },
        confirmButtonText = stringResource(R.string.save),
        title = { Text(title) },
        content = {
            Column {
                if (image == null) {
                    Text(stringResource(R.string.file_read_error))
                } else {
                    val pw = image.width
                    val ph = image.height
                    Box(Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(band))
                        .pointerInput(framing.stretch) {
                            if (framing.stretch) return@pointerInput
                            detectTransformGestures { centroid, pan, zoomChange, _ ->
                                val fw = size.width.toFloat()
                                val fh = size.height.toFloat()
                                val f = framing
                                val r = f.place(pw, ph, fw, fh)
                                val s = r.width() / pw
                                val zoom = (f.zoom * zoomChange).coerceIn(PictureFraming.minZoom(pw, ph, fw, fh), PictureFraming.MAX_ZOOM)
                                val ns = PictureFraming.cover(pw, ph, fw, fh) * zoom
                                // the picture point under the fingers stays under them
                                val px = (centroid.x - r.left) / s
                                val py = (centroid.y - r.top) / s
                                val left = centroid.x + pan.x - px * ns
                                val top = centroid.y + pan.y - py * ns
                                framing = f.copy(zoom = zoom, cx = (fw / 2 - left) / (pw * ns), cy = (fh / 2 - top) / (ph * ns))
                                    .settled(pw, ph, fw, fh)
                            }
                        }
                    ) {
                        Canvas(Modifier.matchParentSize()) {
                            val r = framing.place(pw, ph, size.width, size.height)
                            drawImage(image, IntOffset.Zero, IntSize(pw, ph),
                                IntOffset(r.left.roundToInt(), r.top.roundToInt()), IntSize(r.width().roundToInt(), r.height().roundToInt()),
                                filterQuality = FilterQuality.Medium)
                        }
                    }
                    Text(
                        stringResource(if (framing.stretch) R.string.background_picture_stretched_hint else R.string.background_picture_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.background_picture_stretch), Modifier.weight(1f))
                        Switch(checked = framing.stretch, onCheckedChange = { framing = framing.copy(stretch = it) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.background_picture_remove)) }
                    TextButton(onClick = onOtherPicture) { Text(stringResource(R.string.background_picture_other)) }
                }
            }
        },
    )
}

/** Width / height of the keyboard the picture covers: the live one, or for the other orientation an estimate (the
 *  keyboard as tall a share of the screen, across the screen's long side). */
private fun keyboardAspect(ctx: Context, isLandscape: Boolean): Float {
    val dm = ctx.resources.displayMetrics
    val frame = KeyboardSwitcher.getInstance().mainKeyboardFrame
    var w = frame?.width?.toFloat() ?: 0f
    var h = frame?.height?.toFloat() ?: 0f
    if (w <= 0f || h <= 0f) { w = dm.widthPixels.toFloat(); h = w * if (dm.widthPixels > dm.heightPixels) 0.25f else 0.6f }
    val landscapeNow = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscapeNow != isLandscape) {
        h = h * dm.widthPixels / dm.heightPixels
        w = dm.heightPixels.toFloat()
    }
    return (w / h).coerceIn(0.5f, 8f)
}

/** A new picture: copied in, placed afresh (covering, centred). False if it can't be read. */
private fun setBackgroundImage(ctx: Context, uri: Uri, isNight: Boolean, isLandscape: Boolean): Boolean {
    val imageFile = Settings.getCustomBackgroundFile(ctx, isNight, isLandscape)
    FileUtils.copyContentUriToNewFile(uri, ctx, imageFile)
    PictureFraming.fileFor(imageFile).delete()
    if (PictureFraming.decode(imageFile, 64) == null) {
        imageFile.delete()
        return false
    }
    PictureLibrary.add(ctx, imageFile) // offered to every keyboard from now on
    Settings.clearCachedBackgroundImages()
    return true
}

private const val NO_FRAMING = "-" // (no framing file when the dialog opened)
